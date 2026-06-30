package org.tron.core.archive.txnum;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
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
  // Cached lowest-committed-block so commitRange does not read on every block; -1 = not yet known.
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
    try (WriteBatch batch = new WriteBatch(); WriteOptions writeOptions = new WriteOptions()) {
      batch.put(ArchiveBlockRangeCodec.rangeKey(range.getBlockNum()),
          ArchiveBlockRangeCodec.encodeRange(range));
      batch.put(ArchiveBlockRangeCodec.CURSOR_KEY,
          ArchiveBlockRangeCodec.encodeCursor(committedNextTxNum));
      // Record the lowest committed block exactly once; never overwrite on resume (blocks commit in
      // ascending order, so the first commit ever carries the floor of archive coverage).
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

  @Override
  public void close() {
    db.close();
    options.close();
  }
}
