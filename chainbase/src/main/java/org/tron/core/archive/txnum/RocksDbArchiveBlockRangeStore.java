package org.tron.core.archive.txnum;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Consumer;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.ArchiveRocksDbWriteOptions;
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
    RocksDB opened = null;
    try {
      opened = RocksDB.open(options, path);
      validateOrInstallManifest(opened);
      byte[] value = opened.get(ArchiveBlockRangeCodec.FIRST_BLOCK_KEY);
      this.firstArchivedBlock =
          (value == null) ? NO_FIRST_BLOCK : ArchiveBlockRangeCodec.decodeFirstBlock(value);
      this.db = opened;
    } catch (RocksDBException e) {
      closeQuietly(opened);
      options.close();
      throw new ArchiveException("failed to open archive block-range store at " + path, e);
    } catch (RuntimeException e) {
      closeQuietly(opened);
      options.close();
      throw e;
    }
  }

  private static void closeQuietly(RocksDB db) {
    if (db != null) {
      db.close();
    }
  }

  private static void validateOrInstallManifest(RocksDB db) throws RocksDBException {
    byte[] manifest = db.get(ArchiveBlockRangeCodec.manifestKey());
    if (manifest != null) {
      if (ArchiveBlockRangeCodec.manifestMatches(manifest)) {
        validateCurrentKeyspace(db);
        return;
      }
      if (ArchiveBlockRangeCodec.legacySchemaThreeManifestMatches(manifest)) {
        if (isOnlyKey(db, ArchiveBlockRangeCodec.manifestKey())) {
          installManifest(db);
          return;
        }
        throw new ArchiveException("archive txNum schema=3 requires rebuild or resync");
      } else {
        throw new ArchiveException("archive txNum manifest mismatch");
      }
    }
    byte[] legacyManifest = db.get(ArchiveBlockRangeCodec.legacyManifestKey());
    if (legacyManifest != null) {
      if (ArchiveBlockRangeCodec.legacySchemaOneManifestMatches(legacyManifest)
          || ArchiveBlockRangeCodec.legacySchemaTwoManifestMatches(legacyManifest)) {
        if (isOnlyKey(db, ArchiveBlockRangeCodec.legacyManifestKey())) {
          try (WriteBatch batch = new WriteBatch();
               WriteOptions writeOptions = ArchiveRocksDbWriteOptions.create()) {
            batch.delete(ArchiveBlockRangeCodec.legacyManifestKey());
            batch.put(ArchiveBlockRangeCodec.manifestKey(), ArchiveBlockRangeCodec.manifestValue());
            db.write(writeOptions, batch);
          }
          return;
        }
        throw new ArchiveException("archive txNum legacy schema requires rebuild or resync");
      }
      throw new ArchiveException("archive txNum manifest mismatch");
    }
    if (!isEmpty(db)) {
      throw new ArchiveException("archive txNum store is non-empty but missing manifest");
    }
    installManifest(db);
  }

  private static void installManifest(RocksDB db) throws RocksDBException {
    try (WriteOptions writeOptions = ArchiveRocksDbWriteOptions.create()) {
      db.put(writeOptions, ArchiveBlockRangeCodec.manifestKey(),
          ArchiveBlockRangeCodec.manifestValue());
    }
  }

  private static boolean isEmpty(RocksDB db) {
    try (RocksIterator it = db.newIterator()) {
      it.seekToFirst();
      return !it.isValid();
    }
  }

  private static boolean isOnlyKey(RocksDB db, byte[] expectedKey) {
    try (RocksIterator it = db.newIterator()) {
      it.seekToFirst();
      return it.isValid() && Arrays.equals(it.key(), expectedKey)
          && !hasSecondRow(it);
    }
  }

  private static boolean hasSecondRow(RocksIterator it) {
    it.next();
    return it.isValid();
  }

  private static void validateCurrentKeyspace(RocksDB db) {
    try (RocksIterator it = db.newIterator()) {
      it.seekToFirst();
      while (it.isValid()) {
        validateCurrentKey(it.key());
        it.next();
      }
    }
  }

  private static void validateCurrentKey(byte[] key) {
    if (Arrays.equals(key, ArchiveBlockRangeCodec.manifestKey())
        || Arrays.equals(key, ArchiveBlockRangeCodec.CURSOR_KEY)
        || Arrays.equals(key, ArchiveBlockRangeCodec.FIRST_BLOCK_KEY)
        || Arrays.equals(key, ArchiveBlockRangeCodec.REPAIR_REQUIRED_KEY)) {
      return;
    }
    if (key == null || key.length == 0) {
      throw new ArchiveException("archive txNum store has empty key");
    }
    switch (key[0]) {
      case ArchiveBlockRangeCodec.TXNUM_BLOCK_PREFIX:
        if (key.length != 1 + Long.BYTES) {
          throw new ArchiveException("archive txNum block-range key is invalid");
        }
        return;
      case ArchiveBlockRangeCodec.TXNUM_BY_TXID_PREFIX:
        ArchiveBlockRangeCodec.txIdFromKey(key);
        return;
      case ArchiveBlockRangeCodec.TXNUM_META_PREFIX:
        if (Arrays.equals(key, ArchiveBlockRangeCodec.legacyManifestKey())) {
          throw new ArchiveException("archive txNum legacy schema row requires rebuild or resync");
        }
        ArchiveBlockRangeCodec.txNumFromPositionKey(key);
        return;
      default:
        throw new ArchiveException("archive txNum store has unknown key prefix " + key[0]);
    }
  }

  /** Fail closed after a prior post-canonical archive failure until manual repair clears the DB. */
  public void validateNoRepairRequired() {
    try {
      byte[] value = db.get(ArchiveBlockRangeCodec.REPAIR_REQUIRED_KEY);
      if (value != null) {
        throw new ArchiveException("archive requires repair after fatal failure: "
            + ArchiveBlockRangeCodec.decodeRepairRequired(value));
      }
    } catch (RocksDBException e) {
      throw new ArchiveException("archive repair marker read failed", e);
    }
  }

  public void markRepairRequired(String reason) {
    try (WriteOptions writeOptions = ArchiveRocksDbWriteOptions.create()) {
      db.put(writeOptions, ArchiveBlockRangeCodec.REPAIR_REQUIRED_KEY,
          ArchiveBlockRangeCodec.encodeRepairRequired(reason));
    } catch (RocksDBException e) {
      throw new ArchiveException("archive repair marker write failed", e);
    }
  }

  public void commitRange(ArchiveBlockRange range, long committedNextTxNum) {
    validateRangeShape(range);
    commitRange(range, committedNextTxNum, defaultPositions(range));
  }

  /** Atomically persist a committed block's range, tx positions, and committed cursor. */
  public void commitRange(ArchiveBlockRange range, long committedNextTxNum,
      List<ArchiveTxPosition> positions) {
    validateAppendOnlyCommit(range, committedNextTxNum);
    validatePositionsForCommit(range, positions);
    try (WriteBatch batch = new WriteBatch();
         WriteOptions writeOptions = ArchiveRocksDbWriteOptions.create()) {
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
        ArchiveTxPosition persistedPosition = position.getBlockHash().length == 0
            ? new ArchiveTxPosition(position.getTxNum(), position.getBlockNum(),
                position.getPhase(), position.getSource(), position.getTxIndex(),
                position.getTxId(), range.getBlockHash())
            : position;
        batch.put(ArchiveBlockRangeCodec.positionKey(persistedPosition.getTxNum()),
            ArchiveBlockRangeCodec.encodePosition(persistedPosition));
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
    validateHeadUnwind(range, committedNextTxNum);
    try (WriteBatch batch = new WriteBatch();
         WriteOptions writeOptions = ArchiveRocksDbWriteOptions.create()) {
      batch.delete(ArchiveBlockRangeCodec.rangeKey(range.getBlockNum()));
      batch.put(ArchiveBlockRangeCodec.CURSOR_KEY,
          ArchiveBlockRangeCodec.encodeCursor(committedNextTxNum));
      boolean removeFirstBlock = range.getBlockNum() == firstArchivedBlock;
      if (removeFirstBlock) {
        batch.delete(ArchiveBlockRangeCodec.FIRST_BLOCK_KEY);
      }
      for (long txNum = range.getFirstTxNum(); txNum <= range.getLastTxNum(); txNum++) {
        byte[] positionKey = ArchiveBlockRangeCodec.positionKey(txNum);
        ArchiveTxPosition position = validatePosition(range, txNum);
        batch.delete(positionKey);
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
      it.seek(new byte[] {ArchiveBlockRangeCodec.TXNUM_BLOCK_PREFIX});
      while (it.isValid() && it.key()[0] == ArchiveBlockRangeCodec.TXNUM_BLOCK_PREFIX) {
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
    validateNoOrphanIndexRows();
  }

  /** Fail closed if persisted txNum ranges were written under a different archive schema. */
  public void validateSchemaChecksum(byte[] schemaChecksum) {
    ArchiveBlockRangeCodec.requireSchemaChecksum(schemaChecksum, "archive schema");
    validateCommittedRanges(range -> {
      if (!Arrays.equals(range.getSchemaChecksum(), schemaChecksum)) {
        throw new ArchiveException("archive block range schema checksum mismatch for block "
            + range.getBlockNum());
      }
    });
  }

  /** Iterate every persisted committed range in block order, failing on corrupt range rows. */
  public void validateCommittedRanges(Consumer<ArchiveBlockRange> validator) {
    try (RocksIterator it = db.newIterator()) {
      it.seek(new byte[] {ArchiveBlockRangeCodec.TXNUM_BLOCK_PREFIX});
      while (it.isValid() && it.key()[0] == ArchiveBlockRangeCodec.TXNUM_BLOCK_PREFIX) {
        ArchiveBlockRange current = ArchiveBlockRangeCodec.decodeRange(it.value());
        validateRangeKeyMatchesValue(it.key(), current);
        validateRangeShape(current);
        validator.accept(current);
        it.next();
      }
    }
  }

  private ArchiveTxPosition validatePosition(ArchiveBlockRange range, long txNum) {
    Optional<ArchiveTxPosition> stored = getRawPosition(txNum);
    if (!stored.isPresent()) {
      throw new ArchiveException("archive tx-position missing for committed txNum " + txNum);
    }
    ArchiveTxPosition position = stored.get();
    if (position.getTxNum() != txNum || position.getBlockNum() != range.getBlockNum()
        || position.getSource() != range.getSource()) {
      throw new ArchiveException("archive tx-position mismatch for committed txNum " + txNum);
    }
    if (!Arrays.equals(position.getBlockHash(), range.getBlockHash())) {
      throw new ArchiveException("archive tx-position block hash mismatch for txNum " + txNum);
    }
    if (position.getPhase() == ArchivePhase.BLOCK_PREPARE) {
      if (txNum != range.getPrepareTxNum() || position.getTxIndex() != -1
          || position.getTxId().length != 0) {
        throw new ArchiveException("archive prepare tx-position mismatch for txNum " + txNum);
      }
      return position;
    }
    if (position.getPhase() == ArchivePhase.BLOCK_FINALIZE) {
      if (txNum != range.getFinalizeTxNum() || position.getTxIndex() != -1
          || position.getTxId().length != 0) {
        throw new ArchiveException("archive finalize tx-position mismatch for txNum " + txNum);
      }
      return position;
    }
    if (position.getPhase() != ArchivePhase.USER_TX || position.getTxIndex() < 0
        || position.getTxIndex() >= range.getUserTxCount()) {
      throw new ArchiveException("archive user tx-position mismatch for txNum " + txNum);
    }
    long expectedTxNum = range.getFirstTxNum() + 1 + position.getTxIndex();
    if (position.getTxNum() != expectedTxNum) {
      throw new ArchiveException("archive user tx-position order mismatch for txNum " + txNum);
    }
    byte[] txId = position.getTxId();
    if (txId.length > 0) {
      OptionalLong byTxId = findRawTxNumByTxId(txId);
      if (!byTxId.isPresent() || byTxId.getAsLong() != txNum) {
        throw new ArchiveException("archive txId index missing for committed txNum " + txNum);
      }
    }
    return position;
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
      it.seek(new byte[] {ArchiveBlockRangeCodec.TXNUM_BY_TXID_PREFIX});
      if (it.isValid()) {
        it.prev();
      } else {
        it.seekToLast();
      }
      if (!it.isValid() || it.key()[0] != ArchiveBlockRangeCodec.TXNUM_BLOCK_PREFIX) {
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
    Optional<ArchiveTxPosition> stored = getRawPosition(txNum);
    if (!stored.isPresent()) {
      return Optional.empty();
    }
    ArchiveTxPosition position = stored.get();
    ArchiveBlockRange range = committedRangeForPosition(position, txNum);
    validatePosition(range, txNum);
    return stored;
  }

  private Optional<ArchiveTxPosition> getRawPosition(long txNum) {
    try {
      byte[] value = db.get(ArchiveBlockRangeCodec.positionKey(txNum));
      return (value == null) ? Optional.empty()
          : Optional.of(ArchiveBlockRangeCodec.decodePosition(value));
    } catch (RocksDBException e) {
      throw new ArchiveException("archive tx-position read failed", e);
    }
  }

  public boolean hasCommittedTxNum(long txNum) {
    Optional<ArchiveTxPosition> position = getRawPosition(txNum);
    if (!position.isPresent()) {
      return false;
    }
    Optional<ArchiveBlockRange> range = getRange(position.get().getBlockNum());
    if (!range.isPresent()) {
      return false;
    }
    ArchiveBlockRange blockRange = range.get();
    if (txNum < blockRange.getFirstTxNum() || txNum > blockRange.getLastTxNum()) {
      return false;
    }
    validatePosition(blockRange, txNum);
    return true;
  }

  public OptionalLong findTxNumByBlockAndIndex(long blockNum, int txIndex) {
    if (txIndex < 0) {
      return OptionalLong.empty();
    }
    Optional<ArchiveBlockRange> range = getRange(blockNum);
    if (!range.isPresent()) {
      return OptionalLong.empty();
    }
    ArchiveBlockRange blockRange = range.get();
    validateRangeShape(blockRange);
    if (txIndex >= blockRange.getUserTxCount()) {
      return OptionalLong.empty();
    }
    long txNum = blockRange.getFirstTxNum() + 1L + txIndex;
    ArchiveTxPosition position = getPosition(txNum)
        .orElseThrow(() -> new ArchiveException(
            "archive block-index is orphan for txNum " + txNum));
    if (position.getPhase() != ArchivePhase.USER_TX
        || position.getBlockNum() != blockNum
        || position.getTxIndex() != txIndex) {
      throw new ArchiveException("archive block-index does not match tx-position for txNum "
          + txNum);
    }
    return OptionalLong.of(txNum);
  }

  public OptionalLong findTxNumByTxId(byte[] txId) {
    if (txId == null || txId.length == 0) {
      return OptionalLong.empty();
    }
    OptionalLong txNum = findRawTxNumByTxId(txId);
    if (!txNum.isPresent()) {
      return OptionalLong.empty();
    }
    ArchiveTxPosition position = getRawPosition(txNum.getAsLong())
        .orElseThrow(() -> new ArchiveException(
            "archive txId index is orphan for txNum " + txNum.getAsLong()));
    ArchiveBlockRange range = committedRangeForPosition(position, txNum.getAsLong());
    validatePosition(range, txNum.getAsLong());
    if (!Arrays.equals(position.getTxId(), txId)) {
      throw new ArchiveException("archive txId index does not match tx-position for txNum "
          + txNum.getAsLong());
    }
    return txNum;
  }

  private OptionalLong findRawTxNumByTxId(byte[] txId) {
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
    if (!Arrays.equals(lastRange.get().getSchemaChecksum(), range.getSchemaChecksum())) {
      throw new ArchiveException("archive block range schema checksum mismatch for block "
          + range.getBlockNum());
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
    ArchiveBlockRangeCodec.requireSchemaChecksum(range.getSchemaChecksum(),
        "archive block range");
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

  private static List<ArchiveTxPosition> defaultPositions(ArchiveBlockRange range) {
    if (range.getUserTxCount() != 0) {
      throw new ArchiveException("archive block range with user txs requires tx-position rows");
    }
    return Arrays.asList(
        new ArchiveTxPosition(range.getPrepareTxNum(), range.getBlockNum(),
            ArchivePhase.BLOCK_PREPARE, range.getSource(), -1, null, range.getBlockHash()),
        new ArchiveTxPosition(range.getFinalizeTxNum(), range.getBlockNum(),
            ArchivePhase.BLOCK_FINALIZE, range.getSource(), -1, null, range.getBlockHash()));
  }

  private void validatePositionsForCommit(ArchiveBlockRange range,
      List<ArchiveTxPosition> positions) {
    long expectedSize = range.getLastTxNum() - range.getFirstTxNum() + 1L;
    if (positions == null || positions.size() != expectedSize) {
      throw new ArchiveException("archive tx-position coverage mismatch for block "
          + range.getBlockNum());
    }
    Set<ByteArrayKey> txIds = new HashSet<>();
    for (long txNum = range.getFirstTxNum(); txNum <= range.getLastTxNum(); txNum++) {
      int index = (int) (txNum - range.getFirstTxNum());
      ArchiveTxPosition position = positions.get(index);
      validatePositionForCommit(range, txNum, position);
      byte[] txId = position.getTxId();
      if (txId.length > 0 && !txIds.add(new ByteArrayKey(txId))) {
        throw new ArchiveException("archive duplicate txId in block " + range.getBlockNum());
      }
      if (txId.length > 0 && findRawTxNumByTxId(txId).isPresent()) {
        throw new ArchiveException("archive duplicate txId already committed in block "
            + range.getBlockNum());
      }
    }
  }

  private void validatePositionForCommit(ArchiveBlockRange range, long txNum,
      ArchiveTxPosition position) {
    if (position.getTxNum() != txNum || position.getBlockNum() != range.getBlockNum()
        || position.getSource() != range.getSource()) {
      throw new ArchiveException("archive tx-position mismatch for commit txNum " + txNum);
    }
    byte[] positionBlockHash = position.getBlockHash();
    if (positionBlockHash.length != 0 && !Arrays.equals(positionBlockHash, range.getBlockHash())) {
      throw new ArchiveException("archive tx-position block hash mismatch for commit txNum "
          + txNum);
    }
    if (txNum == range.getPrepareTxNum()) {
      if (position.getPhase() != ArchivePhase.BLOCK_PREPARE || position.getTxIndex() != -1
          || position.getTxId().length != 0) {
        throw new ArchiveException("archive prepare tx-position mismatch for commit txNum "
            + txNum);
      }
      return;
    }
    if (txNum == range.getFinalizeTxNum()) {
      if (position.getPhase() != ArchivePhase.BLOCK_FINALIZE || position.getTxIndex() != -1
          || position.getTxId().length != 0) {
        throw new ArchiveException("archive finalize tx-position mismatch for commit txNum "
            + txNum);
      }
      return;
    }
    int expectedTxIndex = (int) (txNum - range.getFirstTxNum() - 1);
    if (position.getPhase() != ArchivePhase.USER_TX || position.getTxIndex() != expectedTxIndex) {
      throw new ArchiveException("archive user tx-position mismatch for commit txNum " + txNum);
    }
  }

  private void validateNoOrphanIndexRows() {
    try (RocksIterator it = db.newIterator()) {
      it.seek(new byte[] {ArchiveBlockRangeCodec.TXNUM_META_PREFIX});
      while (it.isValid() && it.key()[0] == ArchiveBlockRangeCodec.TXNUM_META_PREFIX) {
        long txNum = ArchiveBlockRangeCodec.txNumFromPositionKey(it.key());
        ArchiveTxPosition position = ArchiveBlockRangeCodec.decodePosition(it.value());
        if (position.getTxNum() != txNum) {
          throw new ArchiveException("archive tx-position key mismatch for txNum " + txNum);
        }
        ArchiveBlockRange range = committedRangeForPosition(position, txNum);
        validatePosition(range, txNum);
        it.next();
      }
    }
    try (RocksIterator it = db.newIterator()) {
      it.seek(new byte[] {ArchiveBlockRangeCodec.TXNUM_BY_TXID_PREFIX});
      while (it.isValid() && it.key()[0] == ArchiveBlockRangeCodec.TXNUM_BY_TXID_PREFIX) {
        byte[] txId = ArchiveBlockRangeCodec.txIdFromKey(it.key());
        long txNum = ArchiveBlockRangeCodec.decodeCursor(it.value());
        ArchiveTxPosition position = getRawPosition(txNum)
            .orElseThrow(() -> new ArchiveException(
                "archive txId index is orphan for txNum " + txNum));
        ArchiveBlockRange range = committedRangeForPosition(position, txNum);
        validatePosition(range, txNum);
        if (!Arrays.equals(position.getTxId(), txId)) {
          throw new ArchiveException(
              "archive txId index does not match tx-position for txNum " + txNum);
        }
        it.next();
      }
    }
  }

  private ArchiveBlockRange committedRangeForPosition(ArchiveTxPosition position, long txNum) {
    ArchiveBlockRange range = getRange(position.getBlockNum())
        .orElseThrow(() -> new ArchiveException(
            "archive tx-position is orphan for committed txNum " + txNum));
    if (txNum < range.getFirstTxNum() || txNum > range.getLastTxNum()) {
      throw new ArchiveException("archive tx-position is outside committed range for txNum "
          + txNum);
    }
    return range;
  }

  private void validateHeadUnwind(ArchiveBlockRange range, long committedNextTxNum) {
    ArchiveBlockRange stored = getRange(range.getBlockNum())
        .orElseThrow(() -> new ArchiveException(
            "cannot unwind archive block " + range.getBlockNum() + ": range not committed"));
    if (!sameRange(stored, range)) {
      throw new ArchiveException("cannot unwind archive block " + range.getBlockNum()
          + ": range does not match persisted head");
    }
    long cursor = getCursor();
    if (cursor != range.getLastTxNum() + 1) {
      throw new ArchiveException("cannot unwind archive block " + range.getBlockNum()
          + ": not archive head");
    }
    if (committedNextTxNum != range.getFirstTxNum()) {
      throw new ArchiveException("archive txNum unwind cursor " + committedNextTxNum
          + " does not match range first txNum " + range.getFirstTxNum());
    }
  }

  private static boolean sameRange(ArchiveBlockRange left, ArchiveBlockRange right) {
    return left.getBlockNum() == right.getBlockNum()
        && left.getFirstTxNum() == right.getFirstTxNum()
        && left.getLastTxNum() == right.getLastTxNum()
        && left.getPrepareTxNum() == right.getPrepareTxNum()
        && left.getFinalizeTxNum() == right.getFinalizeTxNum()
        && left.getUserTxCount() == right.getUserTxCount()
        && left.getSource() == right.getSource()
        && Arrays.equals(left.getBlockHash(), right.getBlockHash())
        && Arrays.equals(left.getSchemaChecksum(), right.getSchemaChecksum());
  }

  private static final class ByteArrayKey {
    private final byte[] bytes;

    private ByteArrayKey(byte[] bytes) {
      this.bytes = Arrays.copyOf(bytes, bytes.length);
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (!(obj instanceof ByteArrayKey)) {
        return false;
      }
      return Arrays.equals(bytes, ((ByteArrayKey) obj).bytes);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(bytes);
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
