package org.tron.core.archive.txnum;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.ArchivePhase;

/**
 * RocksDB-backed persistence for committed block ranges, committed-txNum cursor, and per-tx lookup
 * indexes, so block/txId/txNum mapping survives restart. Commit/unwind write every affected index
 * row in one atomic batch.
 */
public final class RocksDbArchiveBlockRangeStore implements AutoCloseable {

  static {
    RocksDB.loadLibrary();
  }

  /** Sentinel for "no block committed yet" (empty archive). */
  public static final long NO_FIRST_BLOCK = -1L;

  private final Options options;
  private final RocksDB db;
  // Cached lowest currently committed block so commitRange does not read on every block.
  private volatile long firstArchivedBlock;

  public RocksDbArchiveBlockRangeStore(String path) {
    this.options = new Options().setCreateIfMissing(true);
    try {
      this.db = RocksDB.open(options, path);
      byte[] value = db.get(ArchiveBlockRangeCodec.FIRST_BLOCK_KEY);
      this.firstArchivedBlock =
          (value == null) ? NO_FIRST_BLOCK : ArchiveBlockRangeCodec.decodeFirstBlock(value);
    } catch (RocksDBException e) {
      options.close();
      throw new ArchiveException("failed to open archive block-range store at " + path, e);
    }
  }

  public void commitRange(ArchiveBlockRange range, long committedNextTxNum) {
    commitRange(range, committedNextTxNum, Collections.emptyList());
  }

  /** Atomically persist a committed block's range, tx positions, and committed cursor. */
  public void commitRange(ArchiveBlockRange range, long committedNextTxNum,
      List<ArchiveTxPosition> positions) {
    validateAppendOnlyCommit(range, committedNextTxNum);
    try (WriteBatch batch = new WriteBatch(); WriteOptions writeOptions = new WriteOptions()) {
      batch.put(ArchiveBlockRangeCodec.rangeKey(range.getBlockNum()),
          ArchiveBlockRangeCodec.encodeRange(range));
      batch.put(ArchiveBlockRangeCodec.CURSOR_KEY,
          ArchiveBlockRangeCodec.encodeCursor(committedNextTxNum));
      // Record the lowest committed block for this coverage run. Blocks commit in ascending order,
      // so the first commit carries the floor until unwind removes the archive entirely.
      boolean recordFirstBlock = firstArchivedBlock == NO_FIRST_BLOCK;
      if (recordFirstBlock) {
        batch.put(ArchiveBlockRangeCodec.FIRST_BLOCK_KEY,
            ArchiveBlockRangeCodec.encodeFirstBlock(range.getBlockNum()));
      }
      for (ArchiveTxPosition position : positions) {
        batch.put(ArchiveBlockRangeCodec.positionKey(position.getTxNum()),
            ArchiveBlockRangeCodec.encodePosition(position));
        if (position.getPhase() == ArchivePhase.USER_TX && position.getTxIndex() >= 0) {
          batch.put(ArchiveBlockRangeCodec.blockIndexKey(
              position.getBlockNum(), position.getTxIndex()),
              ArchiveBlockRangeCodec.encodeCursor(position.getTxNum()));
        }
        byte[] txId = position.getTxId();
        if (txId.length > 0) {
          batch.put(ArchiveBlockRangeCodec.txIdKey(txId),
              ArchiveBlockRangeCodec.encodeCursor(position.getTxNum()));
        }
      }
      db.write(writeOptions, batch);
      if (recordFirstBlock) {
        firstArchivedBlock = range.getBlockNum();
      }
    } catch (RocksDBException e) {
      throw new ArchiveException("archive block-range commit failed", e);
    }
  }

  /** The lowest block ever committed, or {@link #NO_FIRST_BLOCK} if the archive is empty. */
  public long getFirstArchivedBlock() {
    return firstArchivedBlock;
  }

  /** Atomically drop a reverted block's range/index rows and rewind the persisted cursor. */
  public void unwindRange(ArchiveBlockRange range, long committedNextTxNum) {
    try (WriteBatch batch = new WriteBatch(); WriteOptions writeOptions = new WriteOptions()) {
      batch.delete(ArchiveBlockRangeCodec.rangeKey(range.getBlockNum()));
      batch.put(ArchiveBlockRangeCodec.CURSOR_KEY,
          ArchiveBlockRangeCodec.encodeCursor(committedNextTxNum));
      boolean removeFirstBlock = range.getBlockNum() == firstArchivedBlock;
      if (removeFirstBlock) {
        batch.delete(ArchiveBlockRangeCodec.FIRST_BLOCK_KEY);
      }
      for (long txNum = range.getFirstTxNum(); txNum <= range.getLastTxNum(); txNum++) {
        byte[] positionKey = ArchiveBlockRangeCodec.positionKey(txNum);
        byte[] encodedPosition = db.get(positionKey);
        if (encodedPosition == null) {
          throw new ArchiveException("archive tx-position missing for unwind txNum " + txNum);
        }
        ArchiveTxPosition position = ArchiveBlockRangeCodec.decodePosition(encodedPosition);
        if (position.getTxNum() != txNum || position.getBlockNum() != range.getBlockNum()) {
          throw new ArchiveException("archive tx-position mismatch for unwind txNum " + txNum);
        }
        batch.delete(positionKey);
        if (position.getPhase() == ArchivePhase.USER_TX && position.getTxIndex() >= 0) {
          batch.delete(ArchiveBlockRangeCodec.blockIndexKey(
              position.getBlockNum(), position.getTxIndex()));
        }
        byte[] txId = position.getTxId();
        if (txId.length > 0) {
          batch.delete(ArchiveBlockRangeCodec.txIdKey(txId));
        }
      }
      db.write(writeOptions, batch);
      if (removeFirstBlock) {
        firstArchivedBlock = NO_FIRST_BLOCK;
      }
    } catch (RocksDBException e) {
      throw new ArchiveException("archive block-range unwind failed", e);
    }
  }

  /** Fail closed if the persisted cursor and highest committed range disagree. */
  public void validateCursorConsistentWithLastRange() {
    Optional<ArchiveBlockRange> lastRange = getLastRange();
    long cursor = getCursor();
    if (!lastRange.isPresent()) {
      if (cursor != 0L) {
        throw new ArchiveException("archive txNum cursor " + cursor
            + " exists without a committed block range");
      }
      if (firstArchivedBlock != NO_FIRST_BLOCK) {
        throw new ArchiveException(
            "archive first-block marker exists without a committed block range");
      }
      return;
    }
    long expectedCursor = lastRange.get().getLastTxNum() + 1;
    if (cursor != expectedCursor) {
      throw new ArchiveException("archive txNum cursor " + cursor
          + " does not match last committed range cursor " + expectedCursor);
    }
    long firstBlock = getFirstArchivedBlock();
    if (firstBlock == NO_FIRST_BLOCK || firstBlock > lastRange.get().getBlockNum()) {
      throw new ArchiveException("archive first-block marker is inconsistent with committed range");
    }
  }

  /** Fail closed if persisted block ranges contain holes or txNum discontinuities. */
  public void validateContiguousCoverage() {
    try (RocksIterator it = db.newIterator()) {
      ArchiveBlockRange previous = null;
      it.seek(new byte[] {ArchiveBlockRangeCodec.RANGE_PREFIX});
      while (it.isValid() && it.key()[0] == ArchiveBlockRangeCodec.RANGE_PREFIX) {
        ArchiveBlockRange current = ArchiveBlockRangeCodec.decodeRange(it.value());
        validateRangeKeyMatchesValue(it.key(), current);
        validateRangeShape(current);
        if (previous == null) {
          validateFirstRange(current);
        } else {
          validateAdjacentRanges(previous, current);
        }
        previous = current;
        it.next();
      }
    }
  }

  /** Fail closed if any committed range is missing its txNum position/index rows. */
  public void validatePositionCoverage() {
    validateCommittedRanges(range -> {
      for (long txNum = range.getFirstTxNum(); txNum <= range.getLastTxNum(); txNum++) {
        validatePosition(range, txNum);
      }
    });
  }

  /** Iterate every persisted committed range in block order, failing on corrupt range rows. */
  public void validateCommittedRanges(Consumer<ArchiveBlockRange> validator) {
    try (RocksIterator it = db.newIterator()) {
      it.seek(new byte[] {ArchiveBlockRangeCodec.RANGE_PREFIX});
      while (it.isValid() && it.key()[0] == ArchiveBlockRangeCodec.RANGE_PREFIX) {
        ArchiveBlockRange current = ArchiveBlockRangeCodec.decodeRange(it.value());
        validateRangeKeyMatchesValue(it.key(), current);
        validateRangeShape(current);
        validator.accept(current);
        it.next();
      }
    }
  }

  private void validatePosition(ArchiveBlockRange range, long txNum) {
    Optional<ArchiveTxPosition> stored = getPosition(txNum);
    if (!stored.isPresent()) {
      throw new ArchiveException("archive tx-position missing for committed txNum " + txNum);
    }
    ArchiveTxPosition position = stored.get();
    if (position.getTxNum() != txNum || position.getBlockNum() != range.getBlockNum()
        || position.getSource() != range.getSource()) {
      throw new ArchiveException("archive tx-position mismatch for committed txNum " + txNum);
    }
    if (position.getPhase() == ArchivePhase.BLOCK_PREPARE) {
      if (txNum != range.getPrepareTxNum() || position.getTxIndex() != -1
          || position.getTxId().length != 0) {
        throw new ArchiveException("archive prepare tx-position mismatch for txNum " + txNum);
      }
      return;
    }
    if (position.getPhase() == ArchivePhase.BLOCK_FINALIZE) {
      if (txNum != range.getFinalizeTxNum() || position.getTxIndex() != -1
          || position.getTxId().length != 0) {
        throw new ArchiveException("archive finalize tx-position mismatch for txNum " + txNum);
      }
      return;
    }
    if (position.getPhase() != ArchivePhase.USER_TX || position.getTxIndex() < 0
        || position.getTxIndex() >= range.getUserTxCount()) {
      throw new ArchiveException("archive user tx-position mismatch for txNum " + txNum);
    }
    long expectedTxNum = range.getFirstTxNum() + 1 + position.getTxIndex();
    if (position.getTxNum() != expectedTxNum) {
      throw new ArchiveException("archive user tx-position order mismatch for txNum " + txNum);
    }
    OptionalLong byBlockIndex = findTxNumByBlockAndIndex(
        position.getBlockNum(), position.getTxIndex());
    if (!byBlockIndex.isPresent() || byBlockIndex.getAsLong() != txNum) {
      throw new ArchiveException("archive block-index missing for committed txNum " + txNum);
    }
    byte[] txId = position.getTxId();
    if (txId.length > 0) {
      OptionalLong byTxId = findTxNumByTxId(txId);
      if (!byTxId.isPresent() || byTxId.getAsLong() != txNum) {
        throw new ArchiveException("archive txId index missing for committed txNum " + txNum);
      }
    }
  }

  public Optional<ArchiveBlockRange> getRange(long blockNum) {
    try {
      byte[] value = db.get(ArchiveBlockRangeCodec.rangeKey(blockNum));
      return (value == null) ? Optional.empty()
          : Optional.of(ArchiveBlockRangeCodec.decodeRange(value));
    } catch (RocksDBException e) {
      throw new ArchiveException("archive block-range read failed", e);
    }
  }

  public Optional<ArchiveBlockRange> getLastRange() {
    try (RocksIterator it = db.newIterator()) {
      it.seek(ArchiveBlockRangeCodec.CURSOR_KEY);
      it.prev();
      if (!it.isValid() || it.key()[0] != ArchiveBlockRangeCodec.RANGE_PREFIX) {
        return Optional.empty();
      }
      return Optional.of(ArchiveBlockRangeCodec.decodeRange(it.value()));
    }
  }

  public void validateCanonicalHead(long headNum, byte[] headHash) {
    Optional<ArchiveBlockRange> lastRange = getLastRange();
    if (!lastRange.isPresent()) {
      return;
    }
    ArchiveBlockRange range = lastRange.get();
    if (range.getBlockNum() != headNum) {
      throw new ArchiveException("archive head block " + range.getBlockNum()
          + " does not match canonical head block " + headNum);
    }
    byte[] archiveHash = range.getBlockHash();
    ArchiveBlockRangeCodec.requireBlockHash(archiveHash, "archive head block");
    ArchiveBlockRangeCodec.requireBlockHash(headHash, "canonical head block");
    if (!Arrays.equals(archiveHash, headHash)) {
      throw new ArchiveException("archive head block hash does not match canonical head hash");
    }
  }

  public Optional<ArchiveTxPosition> getPosition(long txNum) {
    try {
      byte[] value = db.get(ArchiveBlockRangeCodec.positionKey(txNum));
      return (value == null) ? Optional.empty()
          : Optional.of(ArchiveBlockRangeCodec.decodePosition(value));
    } catch (RocksDBException e) {
      throw new ArchiveException("archive tx-position read failed", e);
    }
  }

  public OptionalLong findTxNumByBlockAndIndex(long blockNum, int txIndex) {
    try {
      byte[] value = db.get(ArchiveBlockRangeCodec.blockIndexKey(blockNum, txIndex));
      return (value == null) ? OptionalLong.empty()
          : OptionalLong.of(ArchiveBlockRangeCodec.decodeCursor(value));
    } catch (RocksDBException e) {
      throw new ArchiveException("archive block/index txNum read failed", e);
    }
  }

  public OptionalLong findTxNumByTxId(byte[] txId) {
    if (txId == null || txId.length == 0) {
      return OptionalLong.empty();
    }
    try {
      byte[] value = db.get(ArchiveBlockRangeCodec.txIdKey(txId));
      return (value == null) ? OptionalLong.empty()
          : OptionalLong.of(ArchiveBlockRangeCodec.decodeCursor(value));
    } catch (RocksDBException e) {
      throw new ArchiveException("archive txId txNum read failed", e);
    }
  }

  /** The persisted committed-txNum cursor, or 0 if never written (fresh store). */
  public long getCursor() {
    try {
      byte[] value = db.get(ArchiveBlockRangeCodec.CURSOR_KEY);
      return (value == null) ? 0L : ArchiveBlockRangeCodec.decodeCursor(value);
    } catch (RocksDBException e) {
      throw new ArchiveException("archive block-range cursor read failed", e);
    }
  }

  private void validateAppendOnlyCommit(ArchiveBlockRange range, long committedNextTxNum) {
    validateRangeShape(range);
    long expectedCommittedNextTxNum = range.getLastTxNum() + 1;
    if (committedNextTxNum != expectedCommittedNextTxNum) {
      throw new ArchiveException("archive txNum cursor " + committedNextTxNum
          + " does not match committed range cursor " + expectedCommittedNextTxNum);
    }
    if (getRange(range.getBlockNum()).isPresent()) {
      throw new ArchiveException(
          "archive block range already committed for block " + range.getBlockNum());
    }
    Optional<ArchiveBlockRange> lastRange = getLastRange();
    long cursor = getCursor();
    if (!lastRange.isPresent()) {
      if (cursor != 0L) {
        throw new ArchiveException("archive txNum cursor " + cursor
            + " exists without a committed block range");
      }
      if (range.getFirstTxNum() != 0L) {
        throw new ArchiveException("first archive block range must start at txNum 0 but got "
            + range.getFirstTxNum());
      }
      return;
    }
    validateAdjacentRanges(lastRange.get(), range);
    if (cursor != range.getFirstTxNum()) {
      throw new ArchiveException("archive txNum cursor " + cursor
          + " does not match next range first txNum " + range.getFirstTxNum());
    }
  }

  private void validateFirstRange(ArchiveBlockRange range) {
    long firstBlock = getFirstArchivedBlock();
    if (firstBlock == NO_FIRST_BLOCK) {
      throw new ArchiveException("archive first-block marker missing for committed range");
    }
    if (firstBlock != range.getBlockNum()) {
      throw new ArchiveException("archive first-block marker " + firstBlock
          + " does not match first committed range block " + range.getBlockNum());
    }
    if (range.getFirstTxNum() != 0L) {
      throw new ArchiveException("first archive block range must start at txNum 0 but got "
          + range.getFirstTxNum());
    }
  }

  private void validateAdjacentRanges(ArchiveBlockRange previous, ArchiveBlockRange current) {
    long expectedBlock = previous.getBlockNum() + 1;
    if (current.getBlockNum() != expectedBlock) {
      throw new ArchiveException("non-contiguous archive block range: expected block "
          + expectedBlock + " after " + previous.getBlockNum() + " but got "
          + current.getBlockNum());
    }
    long expectedFirstTxNum = previous.getLastTxNum() + 1;
    if (current.getFirstTxNum() != expectedFirstTxNum) {
      throw new ArchiveException("non-contiguous archive txNum range: expected first txNum "
          + expectedFirstTxNum + " but got " + current.getFirstTxNum());
    }
  }

  private void validateRangeShape(ArchiveBlockRange range) {
    ArchiveBlockRangeCodec.requireBlockHash(range.getBlockHash(), "archive block range");
    if (range.getFirstTxNum() > range.getLastTxNum()) {
      throw new ArchiveException("archive block range has inverted txNum bounds for block "
          + range.getBlockNum());
    }
    if (range.getUserTxCount() < 0) {
      throw new ArchiveException("archive user tx count is negative for block "
          + range.getBlockNum());
    }
    if (range.getPrepareTxNum() != range.getFirstTxNum()) {
      throw new ArchiveException("archive prepare txNum must be first for block "
          + range.getBlockNum());
    }
    if (range.getFinalizeTxNum() != range.getLastTxNum()) {
      throw new ArchiveException("archive finalize txNum must be last for block "
          + range.getBlockNum());
    }
    long expectedSpan = (long) range.getUserTxCount() + 2L;
    long actualSpan = range.getLastTxNum() - range.getFirstTxNum() + 1L;
    if (actualSpan != expectedSpan) {
      throw new ArchiveException("archive txNum span does not match user tx count for block "
          + range.getBlockNum());
    }
  }

  private void validateRangeKeyMatchesValue(byte[] key, ArchiveBlockRange range) {
    byte[] expectedKey = ArchiveBlockRangeCodec.rangeKey(range.getBlockNum());
    if (!Arrays.equals(key, expectedKey)) {
      throw new ArchiveException("archive block range key does not match encoded block "
          + range.getBlockNum());
    }
  }

  @Override
  public void close() {
    db.close();
    options.close();
  }
}
