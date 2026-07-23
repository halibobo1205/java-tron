package org.tron.core.archive.txnum;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;
import org.rocksdb.RocksDBException;
import org.tron.common.utils.ByteArray;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.ArchiveInFlightBlock;
import org.tron.core.archive.ArchivePersistentStateCorruptionException;
import org.tron.core.archive.ArchiveRepairClearPermit;
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveRocksIterators;
import org.tron.core.archive.ArchiveSnapshotReleaseException;
import org.tron.core.archive.ArchiveSource;
import org.tron.core.archive.query.QueryContext;
import org.tron.core.archive.query.QueryContextHolder;
import org.tron.core.archive.unified.UnifiedArchiveColumnFamily;
import org.tron.core.archive.unified.UnifiedArchiveDb;
import org.tron.core.archive.unified.UnifiedArchiveManifest;
import org.tron.core.archive.unified.UnifiedArchivePublish;
import org.tron.core.archive.unified.UnifiedArchiveIterator;
import org.tron.core.archive.unified.UnifiedArchiveReadView;

/** Persistent txNum/index adapter over the UNIFIED_V1 INDEX and META column families. */
public final class UnifiedArchiveTxNumIndex implements ArchiveTxNumIndex, AutoCloseable {

  public static final int MAX_REPAIR_MARKER_BYTES = 4096;
  private static final String TRUNCATED_REPAIR_SUFFIX = "... [truncated]";
  private static final long NO_FIRST_BLOCK = -1L;
  private static final long MAX_SNAPSHOT_VALUE_BYTES = MAX_REPAIR_MARKER_BYTES;

  private final UnifiedArchiveDb db;
  private final byte[] schemaChecksum;
  private final UnifiedArchiveDb.ProductionWritePermit writePermit;
  private final ThreadLocal<UnifiedArchiveReadView> activeReadView = new ThreadLocal<>();
  private boolean closed;

  public UnifiedArchiveTxNumIndex(UnifiedArchiveDb db, byte[] schemaChecksum,
      boolean fullStartupValidation, boolean deferRepairValidation) {
    this(db, schemaChecksum, fullStartupValidation, deferRepairValidation, null);
  }

  public UnifiedArchiveTxNumIndex(UnifiedArchiveDb db, byte[] schemaChecksum,
      boolean fullStartupValidation, boolean deferRepairValidation,
      UnifiedArchiveDb.ProductionWritePermit writePermit) {
    if (db == null) {
      throw new NullPointerException("db");
    }
    ArchiveBlockRangeCodec.requireSchemaChecksum(schemaChecksum, "archive txNum index");
    if (!Arrays.equals(db.getSchemaChecksum(), schemaChecksum)) {
      throw new ArchiveException("archive txNum index schema checksum does not match its DB");
    }
    this.db = db;
    this.schemaChecksum = Arrays.copyOf(schemaChecksum, schemaChecksum.length);
    this.writePermit = writePermit;
    withScanView(view -> {
      validateStartup(fullStartupValidation, deferRepairValidation);
      return null;
    });
  }

  /** Binds index reads to the same unified snapshot used by the temporal reader. */
  public ReadScope bindReadView(UnifiedArchiveReadView view) {
    if (view == null) {
      throw new NullPointerException("view");
    }
    db.requireOwnedReadView(view);
    if (activeReadView.get() != null) {
      throw new ArchiveException("UNIFIED_V1 index read view is already bound");
    }
    ReadScope scope = new ReadScope(view);
    try {
      activeReadView.set(view);
      return scope;
    } catch (RuntimeException | Error failure) {
      try {
        activeReadView.remove();
      } catch (RuntimeException | Error restoreFailure) {
        if (failure != restoreFailure) {
          failure.addSuppressed(restoreFailure);
        }
      }
      throw failure;
    }
  }

  public boolean isOwnedBy(UnifiedArchiveDb candidate) {
    return db == candidate;
  }

  /** Adds every index row and the published cursor to an atomic block publication. */
  public ArchiveBlockRange stagePublication(UnifiedArchivePublish.Builder publish,
      ArchiveInFlightBlock block) {
    if (publish == null || block == null) {
      throw new NullPointerException("publish/block");
    }
    if (activeReadView.get() == null) {
      throw new ArchiveException("UNIFIED_V1 publication requires one bound read view");
    }
    ArchiveBlockRange persisted = block.getRange();
    validatePublicationBlock(persisted, block.getPositions());
    long nextTxNum = ArchiveCoordinates.nextCursor(
        persisted.getLastTxNum(), "archive published txNum");
    validateAppendOnlyCommit(persisted, nextTxNum);

    Optional<ArchiveBlockRange> lastRange = getLastRange();
    publish.put(UnifiedArchiveColumnFamily.INDEX,
        ArchiveBlockRangeCodec.rangeKey(persisted.getBlockNum()),
        ArchiveBlockRangeCodec.encodeRange(persisted));
    if (!lastRange.isPresent()) {
      publish.put(UnifiedArchiveColumnFamily.INDEX, ArchiveBlockRangeCodec.FIRST_BLOCK_KEY,
          ArchiveBlockRangeCodec.encodeFirstBlock(persisted.getBlockNum()));
    }
    for (ArchiveTxPosition journalPosition : block.getPositions()) {
      ArchiveTxPosition position = persistedPosition(persisted, journalPosition);
      publish.put(UnifiedArchiveColumnFamily.INDEX,
          ArchiveBlockRangeCodec.positionKey(position.getTxNum()),
          ArchiveBlockRangeCodec.encodePosition(position));
      if (position.getTxId().length > 0) {
        if (findTxNumByTxId(position.getTxId()).isPresent()) {
          throw new ArchiveException("archive txId is already committed");
        }
        publish.put(UnifiedArchiveColumnFamily.INDEX,
            ArchiveBlockRangeCodec.txIdKey(position.getTxId()),
            ArchiveBlockRangeCodec.encodeCursor(position.getTxNum()));
      }
    }
    publish.cursor(UnifiedArchiveManifest.publishedCursorKey(),
        ArchiveBlockRangeCodec.encodeCursor(nextTxNum));
    return persisted;
  }

  @Override
  public void beginBlock(long blockNum, ArchiveSource source) {
    throw unsupportedMutableOperation();
  }

  @Override
  public ArchiveTxPosition allocateSystemTx(long blockNum, ArchivePhase phase) {
    throw unsupportedMutableOperation();
  }

  @Override
  public ArchiveTxPosition allocateUserTx(long blockNum, int txIndex, byte[] txId) {
    throw unsupportedMutableOperation();
  }

  @Override
  public ArchiveBlockRange commitBlock(long blockNum, int userTxCount) {
    throw new ArchiveException("UNIFIED_V1 index commits only through atomic block publication");
  }

  @Override
  public void abortBlock(long blockNum) {
    throw unsupportedMutableOperation();
  }

  @Override
  public void unwindBlock(long blockNum) {
    throw new ArchiveException("cannot unwind published UNIFIED_V1 archive block " + blockNum);
  }

  @Override
  public ArchiveBlockRange getHeadBlockRange(long blockNum) {
    return withReadView(ignored -> {
      ArchiveBlockRange range = getBlockRange(blockNum)
          .orElseThrow(() -> new ArchiveException("cannot unwind block " + blockNum
              + ": not committed"));
      Optional<ArchiveBlockRange> last = getLastRange();
      if (!last.isPresent() || last.get().getBlockNum() != blockNum) {
        throw new ArchiveException("cannot unwind block " + blockNum + ": not archive head");
      }
      return range;
    });
  }

  @Override
  public void validateCanonicalHead(long headNum, byte[] headHash) {
    ArchiveCoordinates.requireBlockNum(headNum, "archive head block number");
    Optional<ArchiveBlockRange> last = getLastRange();
    if (!last.isPresent()) {
      return;
    }
    ArchiveBlockRange range = last.get();
    if (range.getBlockNum() != headNum) {
      throw new ArchiveException("archive head block " + range.getBlockNum()
          + " does not match canonical head block " + headNum);
    }
    ArchiveBlockRangeCodec.requireBlockHash(headHash, "canonical head block");
    if (!Arrays.equals(range.getBlockHash(), headHash)) {
      throw new ArchiveException("archive head block hash does not match canonical head hash");
    }
  }

  @Override
  public Optional<ArchiveBlockRange> getBlockRange(long blockNum) {
    ArchiveCoordinates.requireBlockNum(blockNum, "archive block range block number");
    return withReadView(ignored -> getBlockRangeFromCurrentView(blockNum));
  }

  private Optional<ArchiveBlockRange> getBlockRangeFromCurrentView(long blockNum) {
    byte[] key = ArchiveBlockRangeCodec.rangeKey(blockNum);
    byte[] value = get(UnifiedArchiveColumnFamily.INDEX, key);
    if (value == null) {
      return Optional.empty();
    }
    ArchiveBlockRange range = ArchiveBlockRangeCodec.decodeRange(value);
    validateRangeKeyMatchesValue(key, range);
    validateRangeShape(range);
    return Optional.of(range);
  }

  @Override
  public Optional<ArchiveTxPosition> getPosition(long txNum) {
    ArchiveCoordinates.requireTxNum(txNum, "archive tx-position txNum");
    return withReadView(ignored -> getPositionFromCurrentView(txNum));
  }

  private Optional<ArchiveTxPosition> getPositionFromCurrentView(long txNum) {
    byte[] value = get(UnifiedArchiveColumnFamily.INDEX,
        ArchiveBlockRangeCodec.positionKey(txNum));
    if (value == null) {
      return Optional.empty();
    }
    ArchiveTxPosition position = ArchiveBlockRangeCodec.decodePosition(value);
    ArchiveBlockRange range = getBlockRange(position.getBlockNum())
        .orElseThrow(() -> new ArchiveException(
            "archive tx-position has no committed block range for txNum " + txNum));
    validatePosition(range, position, txNum);
    return Optional.of(position);
  }

  @Override
  public OptionalLong findTxNumByBlockAndIndex(long blockNum, int txIndex) {
    if (txIndex < 0) {
      return OptionalLong.empty();
    }
    return withReadView(ignored -> findTxNumByBlockAndIndexFromCurrentView(blockNum, txIndex));
  }

  private OptionalLong findTxNumByBlockAndIndexFromCurrentView(long blockNum, int txIndex) {
    Optional<ArchiveBlockRange> range = getBlockRange(blockNum);
    if (!range.isPresent() || txIndex >= range.get().getUserTxCount()) {
      return OptionalLong.empty();
    }
    long txNum = range.get().getFirstTxNum() + 1L + txIndex;
    ArchiveTxPosition position = getPosition(txNum)
        .orElseThrow(() -> new ArchiveException("archive block-index is orphan for txNum "
            + txNum));
    if (position.getPhase() != ArchivePhase.USER_TX
        || position.getBlockNum() != blockNum || position.getTxIndex() != txIndex) {
      throw new ArchiveException("archive block-index does not match tx-position for txNum "
          + txNum);
    }
    return OptionalLong.of(txNum);
  }

  @Override
  public OptionalLong findTxNumByTxId(byte[] txId) {
    Optional<ArchiveTransactionLocation> location = findTransactionByTxId(txId);
    return location.isPresent()
        ? OptionalLong.of(location.get().getPosition().getTxNum()) : OptionalLong.empty();
  }

  @Override
  public Optional<ArchiveTransactionLocation> findTransactionByTxId(byte[] txId) {
    if (txId == null || txId.length == 0) {
      return Optional.empty();
    }
    ArchiveBlockRangeCodec.requireTxId(txId, "archive txId lookup");
    return withReadView(ignored -> findTransactionByTxIdFromCurrentView(txId));
  }

  private Optional<ArchiveTransactionLocation> findTransactionByTxIdFromCurrentView(byte[] txId) {
    byte[] txIdValue = get(UnifiedArchiveColumnFamily.INDEX,
        ArchiveBlockRangeCodec.txIdKey(txId));
    if (txIdValue == null) {
      return Optional.empty();
    }
    long txNum = ArchiveBlockRangeCodec.decodeCursor(txIdValue);
    byte[] positionValue = get(UnifiedArchiveColumnFamily.INDEX,
        ArchiveBlockRangeCodec.positionKey(txNum));
    if (positionValue == null) {
      throw new ArchiveException("archive txId index is orphan for txNum " + txNum);
    }
    ArchiveTxPosition position = ArchiveBlockRangeCodec.decodePosition(positionValue);
    if (!Arrays.equals(position.getTxId(), txId)) {
      throw new ArchiveException("archive txId index does not match tx-position for txNum "
          + txNum);
    }
    byte[] rangeKey = ArchiveBlockRangeCodec.rangeKey(position.getBlockNum());
    byte[] rangeValue = get(UnifiedArchiveColumnFamily.INDEX, rangeKey);
    if (rangeValue == null) {
      throw new ArchiveException("archive tx-position has no committed block range for txNum "
          + txNum);
    }
    ArchiveBlockRange range = ArchiveBlockRangeCodec.decodeRange(rangeValue);
    validateRangeKeyMatchesValue(rangeKey, range);
    validateRangeShape(range);
    validatePositionShape(range, position, txNum);
    return Optional.of(new ArchiveTransactionLocation(position, range));
  }

  @Override
  public long getNextTxNum() {
    byte[] value = get(UnifiedArchiveColumnFamily.META,
        UnifiedArchiveManifest.publishedCursorKey());
    return value == null ? 0L : ArchiveBlockRangeCodec.decodeCursor(value);
  }

  @Override
  public long getLastArchivedBlock() {
    return getLastRange().map(ArchiveBlockRange::getBlockNum).orElse(-1L);
  }

  @Override
  public long getFirstArchivedBlock() {
    byte[] value = get(UnifiedArchiveColumnFamily.INDEX,
        ArchiveBlockRangeCodec.FIRST_BLOCK_KEY);
    return value == null ? NO_FIRST_BLOCK : ArchiveBlockRangeCodec.decodeFirstBlock(value);
  }

  /**
   * Proves complete-history coverage from one bound snapshot. A mutable floor marker alone is not
   * sufficient: it must agree with the physically first range, whose txNum sequence starts at 0.
   */
  public boolean hasGenesisCompleteCoverage() {
    return withReadView(view -> {
      long floor = getFirstArchivedBlock();
      Optional<ArchiveBlockRange> firstRange = getFirstRange(view);
      if (!firstRange.isPresent()) {
        if (floor != NO_FIRST_BLOCK) {
          throw new ArchiveException(
              "archive coverage floor exists without a committed first range");
        }
        return false;
      }
      ArchiveBlockRange range = firstRange.get();
      if (floor != range.getBlockNum() || range.getFirstTxNum() != 0L) {
        throw new ArchiveException(
            "archive first committed range does not match coverage floor");
      }
      if (!Arrays.equals(range.getSchemaChecksum(), schemaChecksum)) {
        throw new ArchiveException("archive block range schema checksum mismatch for block "
            + range.getBlockNum());
      }
      return floor == 0L;
    });
  }

  @Override
  public void markRepairRequired(String reason) {
    runPersistentMutation(() -> markRepairRequired(db, reason));
  }

  /** Writes repair evidence even when startup validation fails before index construction. */
  public static void markRepairRequired(UnifiedArchiveDb db, String reason) {
    if (db == null) {
      throw new NullPointerException("db");
    }
    byte[] encoded = ArchiveBlockRangeCodec.encodeRepairRequired(reason);
    if (encoded.length > MAX_REPAIR_MARKER_BYTES) {
      throw new ArchiveException("archive repair marker exceeds "
          + MAX_REPAIR_MARKER_BYTES + " bytes");
    }
    db.putMetaDurably(ArchiveBlockRangeCodec.REPAIR_REQUIRED_KEY, encoded);
  }

  /** Writes startup repair evidence through the DB-bound production capability. */
  public static void markRepairRequired(UnifiedArchiveDb db, String reason,
      UnifiedArchiveDb.ProductionWritePermit writePermit) {
    if (writePermit == null) {
      markRepairRequired(db, reason);
    } else {
      db.withProductionWritePermit(writePermit, () -> markRepairRequired(db, reason));
    }
  }

  @Override
  public void clearRepairRequired(ArchiveRepairClearPermit permit) {
    if (permit == null) {
      throw new ArchiveException("archive repair clear permit is required");
    }
    if (get(UnifiedArchiveColumnFamily.META,
        ArchiveBlockRangeCodec.REPAIR_REQUIRED_KEY) != null) {
      runPersistentMutation(() -> db.deleteRepairMetaDurably(
          ArchiveBlockRangeCodec.REPAIR_REQUIRED_KEY, permit));
    }
  }

  private void runPersistentMutation(Runnable mutation) {
    if (writePermit == null) {
      mutation.run();
    } else {
      db.withProductionWritePermit(writePermit, mutation);
    }
  }

  @Override
  public boolean hasRepairRequired() {
    return hasRepairRequired(db);
  }

  /** Reads repair evidence without requiring startup validation to construct an index adapter. */
  public static boolean hasRepairRequired(UnifiedArchiveDb db) {
    if (db == null) {
      throw new NullPointerException("db");
    }
    try (UnifiedArchiveReadView view = db.openReadView()) {
      return view.getBounded(UnifiedArchiveColumnFamily.META,
          ArchiveBlockRangeCodec.REPAIR_REQUIRED_KEY, MAX_REPAIR_MARKER_BYTES,
          "UNIFIED_V1 repair marker") != null;
    }
  }

  /** Bounds internally generated failure evidence without splitting a UTF-8 code point. */
  public static String boundRepairReason(String reason) {
    if (reason == null) {
      throw new NullPointerException("reason");
    }
    if (utf8PrefixEnd(reason, MAX_REPAIR_MARKER_BYTES) == reason.length()) {
      return reason;
    }
    int suffixBytes = TRUNCATED_REPAIR_SUFFIX.length();
    int prefixEnd = utf8PrefixEnd(
        reason, MAX_REPAIR_MARKER_BYTES - suffixBytes);
    return reason.substring(0, prefixEnd) + TRUNCATED_REPAIR_SUFFIX;
  }

  private static int utf8PrefixEnd(String value, int byteBudget) {
    int offset = 0;
    int bytes = 0;
    while (offset < value.length()) {
      int codePoint = value.codePointAt(offset);
      int encodedBytes = utf8Length(codePoint);
      if (bytes + encodedBytes > byteBudget) {
        break;
      }
      bytes += encodedBytes;
      offset += Character.charCount(codePoint);
    }
    return offset;
  }

  private static int utf8Length(int codePoint) {
    if (codePoint <= 0x7f || codePoint >= Character.MIN_SURROGATE
        && codePoint <= Character.MAX_SURROGATE) {
      return 1;
    }
    if (codePoint <= 0x7ff) {
      return 2;
    }
    return codePoint <= 0xffff ? 3 : 4;
  }

  public void validateStartup(boolean full, boolean deferRepairValidation) {
    validatePersistentStartupState(
        () -> validateStartupState(full, deferRepairValidation));
  }

  /** Revalidates only mutable cursor/tail state after startup reconcile has completed. */
  public void validateStartupTail(boolean deferRepairValidation) {
    validatePersistentStartupState(
        () -> validateStartupTailState(deferRepairValidation));
  }

  private void validatePersistentStartupState(Runnable validator) {
    try {
      validator.run();
    } catch (ArchivePersistentStateCorruptionException e) {
      throw e;
    } catch (ArchiveException e) {
      if (hasCause(e, RocksDBException.class)) {
        throw e;
      }
      String detail = e.getMessage();
      throw new ArchivePersistentStateCorruptionException(
          detail == null ? "UNIFIED_V1 txNum/index startup state is invalid"
              : "UNIFIED_V1 txNum/index startup state is invalid: " + detail,
          e);
    }
  }

  private void validateStartupState(boolean full, boolean deferRepairValidation) {
    validateStartupTailState(deferRepairValidation);
    if (full) {
      validateFullKeyspace();
    }
    validateRangeCoverage(full);
  }

  private void validateStartupTailState(boolean deferRepairValidation) {
    if (!deferRepairValidation) {
      byte[] repair = get(UnifiedArchiveColumnFamily.META,
          ArchiveBlockRangeCodec.REPAIR_REQUIRED_KEY);
      if (repair != null) {
        throw new ArchiveException("archive repair required: "
            + ArchiveBlockRangeCodec.decodeRepairRequired(repair));
      }
    }
    validateCursorConsistentWithLastRange();
    Optional<ArchiveBlockRange> last = getLastRange();
    if (last.isPresent()) {
      validateRangeShape(last.get());
      if (!Arrays.equals(last.get().getSchemaChecksum(), schemaChecksum)) {
        throw new ArchiveException("archive block range schema checksum mismatch for block "
            + last.get().getBlockNum());
      }
      for (long txNum = last.get().getFirstTxNum();
          txNum <= last.get().getLastTxNum(); txNum++) {
        long currentTxNum = txNum;
        getPosition(currentTxNum).orElseThrow(() -> new ArchiveException(
            "archive tx-position missing for committed txNum " + currentTxNum));
      }
    }
  }

  private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
    Throwable current = failure;
    while (current != null) {
      if (type.isInstance(current)) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private void validateFullKeyspace() {
    withScanView(view -> {
      UnifiedArchiveIterator meta = view.newIterator(UnifiedArchiveColumnFamily.META);
      meta.seekToFirst();
      while (meta.isValid()) {
        byte[] key = meta.key();
        byte[] value = get(UnifiedArchiveColumnFamily.META, key);
        if (Arrays.equals(key, UnifiedArchiveManifest.key())) {
          // UnifiedArchiveDb validates the immutable manifest before exposing this adapter.
        } else if (Arrays.equals(key, UnifiedArchiveManifest.publishedCursorKey())) {
          ArchiveBlockRangeCodec.decodeCursor(value);
        } else if (Arrays.equals(key, ArchiveBlockRangeCodec.REPAIR_REQUIRED_KEY)) {
          ArchiveBlockRangeCodec.decodeRepairRequired(value);
        } else {
          throw new ArchiveException("UNIFIED_V1 meta column family has an unknown key");
        }
        meta.next();
      }
      ArchiveRocksIterators.requireOk(meta, "UNIFIED_V1 validate meta keyspace");

      UnifiedArchiveIterator index = view.newIterator(UnifiedArchiveColumnFamily.INDEX);
      index.seekToFirst();
      while (index.isValid()) {
        byte[] key = index.key();
        if (Arrays.equals(key, ArchiveBlockRangeCodec.FIRST_BLOCK_KEY)) {
          ArchiveBlockRangeCodec.decodeFirstBlock(
              get(UnifiedArchiveColumnFamily.INDEX, key));
          index.next();
          continue;
        }
        if (key.length == 0) {
          throw new ArchiveException("UNIFIED_V1 index column family has an empty key");
        }
        switch (key[0]) {
          case ArchiveBlockRangeCodec.TXNUM_BLOCK_PREFIX:
            ArchiveBlockRange range = ArchiveBlockRangeCodec.decodeRange(
                get(UnifiedArchiveColumnFamily.INDEX, key));
            validateRangeKeyMatchesValue(key, range);
            validateRangeShape(range);
            break;
          case ArchiveBlockRangeCodec.TXNUM_BY_TXID_PREFIX:
            byte[] txId = ArchiveBlockRangeCodec.txIdFromKey(key);
            long txNum = ArchiveBlockRangeCodec.decodeCursor(
                get(UnifiedArchiveColumnFamily.INDEX, key));
            ArchiveTxPosition txIdPosition = getPosition(txNum)
                .orElseThrow(() -> new ArchiveException(
                    "UNIFIED_V1 txId row has no committed position"));
            if (!Arrays.equals(txId, txIdPosition.getTxId())) {
              throw new ArchiveException("UNIFIED_V1 txId row does not match its position");
            }
            break;
          case ArchiveBlockRangeCodec.TXNUM_META_PREFIX:
            long positionTxNum = ArchiveBlockRangeCodec.txNumFromPositionKey(key);
            ArchiveTxPosition decoded = ArchiveBlockRangeCodec.decodePosition(
                get(UnifiedArchiveColumnFamily.INDEX, key));
            if (decoded.getTxNum() != positionTxNum) {
              throw new ArchiveException("UNIFIED_V1 position key/value txNum mismatch");
            }
            getPosition(positionTxNum).orElseThrow(() -> new ArchiveException(
                "UNIFIED_V1 position row is not committed"));
            break;
          default:
            throw new ArchiveException("UNIFIED_V1 index column family has an unknown key");
        }
        index.next();
      }
      ArchiveRocksIterators.requireOk(index, "UNIFIED_V1 validate index keyspace");
      return null;
    });
  }

  public Optional<ArchiveBlockRange> getLastRange() {
    return withReadView(view -> {
      UnifiedArchiveIterator iterator = view.newIterator(UnifiedArchiveColumnFamily.INDEX);
      iterator.seek(new byte[] {ArchiveBlockRangeCodec.TXNUM_BY_TXID_PREFIX});
      if (iterator.isValid()) {
        iterator.prev();
      } else {
        iterator.seekToLast();
      }
      ArchiveRocksIterators.requireOk(iterator, "UNIFIED_V1 locate highest range row");
      if (!iterator.isValid()) {
        return Optional.empty();
      }
      byte[] key = iterator.key();
      if (key.length == 0) {
        throw new ArchiveException("UNIFIED_V1 index column family has an empty key");
      }
      if (key[0] != ArchiveBlockRangeCodec.TXNUM_BLOCK_PREFIX) {
        return Optional.empty();
      }
      ArchiveBlockRange range = readRange(
          view, key, "UNIFIED_V1 highest block range");
      validateRangeKeyMatchesValue(key, range);
      validateRangeShape(range);
      return Optional.of(range);
    });
  }

  private void validateRangeCoverage(boolean validatePositions) {
    withScanView(view -> {
      UnifiedArchiveIterator iterator = view.newIterator(UnifiedArchiveColumnFamily.INDEX);
      iterator.seek(new byte[] {ArchiveBlockRangeCodec.TXNUM_BLOCK_PREFIX});
      ArchiveBlockRange previous = null;
      while (iterator.isValid()) {
        byte[] key = iterator.key();
        if (key.length == 0) {
          throw new ArchiveException("UNIFIED_V1 index column family has an empty key");
        }
        if (key[0] != ArchiveBlockRangeCodec.TXNUM_BLOCK_PREFIX) {
          break;
        }
        ArchiveBlockRange current = readRange(
            view, key, "UNIFIED_V1 committed block range");
        validateRangeKeyMatchesValue(key, current);
        validateRangeShape(current);
        if (!Arrays.equals(current.getSchemaChecksum(), schemaChecksum)) {
          throw new ArchiveException("archive block range schema checksum mismatch for block "
              + current.getBlockNum());
        }
        if (previous == null) {
          validateFirstRange(current);
        } else {
          validateAdjacentRanges(previous, current);
        }
        if (validatePositions) {
          for (long txNum = current.getFirstTxNum();
              txNum <= current.getLastTxNum(); txNum++) {
            long currentTxNum = txNum;
            byte[] positionValue = view.getBounded(
                UnifiedArchiveColumnFamily.INDEX,
                ArchiveBlockRangeCodec.positionKey(currentTxNum),
                ArchiveBlockRangeCodec.POSITION_VALUE_MAX_LENGTH,
                "UNIFIED_V1 committed tx-position");
            if (positionValue == null) {
              throw new ArchiveException(
                  "archive tx-position missing for committed txNum " + currentTxNum);
            }
            ArchiveTxPosition position = ArchiveBlockRangeCodec.decodePosition(positionValue);
            validatePosition(current, position, currentTxNum);
          }
        }
        previous = current;
        iterator.next();
      }
      ArchiveRocksIterators.requireOk(iterator, "UNIFIED_V1 validate committed ranges");
      return null;
    });
  }

  private void validateCursorConsistentWithLastRange() {
    Optional<ArchiveBlockRange> last = getLastRange();
    long cursor = getNextTxNum();
    if (!last.isPresent()) {
      if (cursor != 0L || getFirstArchivedBlock() != NO_FIRST_BLOCK) {
        throw new ArchiveException("archive cursor/floor exists without a committed block range");
      }
      return;
    }
    if (cursor != last.get().getLastTxNum() + 1L) {
      throw new ArchiveException("archive txNum cursor " + cursor
          + " does not match last committed range cursor "
          + (last.get().getLastTxNum() + 1L));
    }
    long first = getFirstArchivedBlock();
    if (first < 0 || first > last.get().getBlockNum()) {
      throw new ArchiveException("archive first-block marker is inconsistent with committed range");
    }
    hasGenesisCompleteCoverage();
  }

  private Optional<ArchiveBlockRange> getFirstRange(UnifiedArchiveReadView view) {
    UnifiedArchiveIterator iterator = view.newIterator(UnifiedArchiveColumnFamily.INDEX);
      iterator.seek(new byte[] {ArchiveBlockRangeCodec.TXNUM_BLOCK_PREFIX});
      ArchiveRocksIterators.requireOk(iterator, "UNIFIED_V1 locate lowest range row");
      if (!iterator.isValid()) {
        return Optional.empty();
      }
      byte[] key = iterator.key();
      if (key.length == 0) {
        throw new ArchiveException("UNIFIED_V1 index column family has an empty key");
      }
      if (key[0] != ArchiveBlockRangeCodec.TXNUM_BLOCK_PREFIX) {
        return Optional.empty();
      }
      ArchiveBlockRange range = readRange(
          view, key, "UNIFIED_V1 lowest block range");
    validateRangeKeyMatchesValue(key, range);
    validateRangeShape(range);
    return Optional.of(range);
  }

  private static ArchiveBlockRange readRange(UnifiedArchiveReadView view, byte[] key,
      String what) {
    byte[] value = view.getExact(
        UnifiedArchiveColumnFamily.INDEX, key, ArchiveBlockRangeCodec.RANGE_VALUE_LENGTH, what);
    if (value == null) {
      throw new ArchiveException(what + " disappeared from the archive snapshot");
    }
    return ArchiveBlockRangeCodec.decodeRange(value);
  }

  private void validateAppendOnlyCommit(ArchiveBlockRange range, long committedNextTxNum) {
    validateRangeShape(range);
    if (committedNextTxNum != range.getLastTxNum() + 1L) {
      throw new ArchiveException("archive txNum cursor does not match committed range");
    }
    if (getBlockRange(range.getBlockNum()).isPresent()) {
      throw new ArchiveException("archive block range already committed for block "
          + range.getBlockNum());
    }
    Optional<ArchiveBlockRange> last = getLastRange();
    long cursor = getNextTxNum();
    if (!last.isPresent()) {
      if (cursor != 0L || range.getFirstTxNum() != 0L) {
        throw new ArchiveException("first archive block range must start at txNum 0");
      }
      return;
    }
    validateAdjacentRanges(last.get(), range);
    if (cursor != range.getFirstTxNum()) {
      throw new ArchiveException("archive txNum cursor does not match next range first txNum");
    }
  }

  private void validateFirstRange(ArchiveBlockRange range) {
    long first = getFirstArchivedBlock();
    if (first != range.getBlockNum() || range.getFirstTxNum() != 0L) {
      throw new ArchiveException("archive first committed range does not match coverage floor");
    }
  }

  private static void validateAdjacentRanges(ArchiveBlockRange previous,
      ArchiveBlockRange current) {
    if (current.getBlockNum() != previous.getBlockNum() + 1L) {
      throw new ArchiveException("non-contiguous archive block range after block "
          + previous.getBlockNum());
    }
    if (current.getFirstTxNum() != previous.getLastTxNum() + 1L) {
      throw new ArchiveException("non-contiguous archive txNum range at block "
          + current.getBlockNum());
    }
  }

  private static void validateRangeShape(ArchiveBlockRange range) {
    ArchiveBlockRangeCodec.requireBlockHash(range.getBlockHash(), "archive block range");
    ArchiveBlockRangeCodec.requireSchemaChecksum(range.getSchemaChecksum(),
        "archive block range");
    ArchiveCoordinates.requireBlockNum(range.getBlockNum(), "archive block range block number");
    ArchiveCoordinates.requireTxNum(range.getFirstTxNum(), "archive block range first txNum");
    ArchiveCoordinates.requireTxNum(range.getLastTxNum(), "archive block range last txNum");
    ArchiveCoordinates.requireTxNum(range.getPrepareTxNum(), "archive block range prepare txNum");
    ArchiveCoordinates.requireTxNum(range.getFinalizeTxNum(), "archive block range finalize txNum");
    if (range.getPrepareTxNum() != range.getFirstTxNum()
        || range.getFinalizeTxNum() != range.getLastTxNum()
        || range.getUserTxCount() < 0
        || range.getLastTxNum() - range.getFirstTxNum() + 1L
            != (long) range.getUserTxCount() + 2L) {
      throw new ArchiveException("archive block range shape is invalid for block "
          + range.getBlockNum());
    }
  }

  private void validatePublicationBlock(ArchiveBlockRange range,
      List<ArchiveTxPosition> positions) {
    validateRangeShape(range);
    if (!Arrays.equals(range.getSchemaChecksum(), schemaChecksum)) {
      throw new ArchiveException("UNIFIED_V1 publication schema checksum mismatch");
    }
    long expectedCount = range.getLastTxNum() - range.getFirstTxNum() + 1L;
    if (positions == null || expectedCount > Integer.MAX_VALUE
        || positions.size() != (int) expectedCount) {
      throw new ArchiveException("UNIFIED_V1 journal position count does not match block range");
    }
    Set<String> userTxIds = new HashSet<>();
    for (int offset = 0; offset < positions.size(); offset++) {
      ArchiveTxPosition position = positions.get(offset);
      if (position == null) {
        throw new ArchiveException("UNIFIED_V1 journal position is missing");
      }
      long expectedTxNum = range.getFirstTxNum() + offset;
      if (position.getTxNum() != expectedTxNum
          || position.getBlockNum() != range.getBlockNum()
          || position.getSource() != range.getSource()) {
        throw new ArchiveException("UNIFIED_V1 journal position does not match block range");
      }
      byte[] positionBlockHash = position.getBlockHash();
      if (positionBlockHash.length != 0
          && !Arrays.equals(positionBlockHash, range.getBlockHash())) {
        throw new ArchiveException("UNIFIED_V1 journal position block hash mismatch");
      }
      if (offset == 0) {
        requireSystemPosition(position, ArchivePhase.BLOCK_PREPARE);
      } else if (offset == positions.size() - 1) {
        requireSystemPosition(position, ArchivePhase.BLOCK_FINALIZE);
      } else {
        int expectedTxIndex = offset - 1;
        if (position.getPhase() != ArchivePhase.USER_TX
            || position.getTxIndex() != expectedTxIndex) {
          throw new ArchiveException("UNIFIED_V1 journal user position order mismatch");
        }
        ArchiveBlockRangeCodec.requireTxId(position.getTxId(),
            "UNIFIED_V1 journal user transaction");
        if (!userTxIds.add(ByteArray.toHexString(position.getTxId()))) {
          throw new ArchiveException("UNIFIED_V1 journal contains duplicate txId");
        }
      }
    }
  }

  private static void requireSystemPosition(ArchiveTxPosition position, ArchivePhase phase) {
    if (position.getPhase() != phase || position.getTxIndex() != -1
        || position.getTxId().length != 0) {
      throw new ArchiveException("UNIFIED_V1 journal system position mismatch for " + phase);
    }
  }

  private static ArchiveTxPosition persistedPosition(ArchiveBlockRange range,
      ArchiveTxPosition position) {
    return new ArchiveTxPosition(
        position.getTxNum(), position.getBlockNum(), position.getPhase(), position.getSource(),
        position.getTxIndex(), position.getTxId(), range.getBlockHash());
  }

  private static ArchiveException unsupportedMutableOperation() {
    return new ArchiveException(
        "UNIFIED_V1 published index mutates only through atomic block publication");
  }

  private void validatePosition(ArchiveBlockRange range, ArchiveTxPosition position,
      long txNum) {
    validatePositionShape(range, position, txNum);
    if (position.getPhase() != ArchivePhase.USER_TX) {
      return;
    }
    byte[] txIdValue = get(UnifiedArchiveColumnFamily.INDEX,
        ArchiveBlockRangeCodec.txIdKey(position.getTxId()));
    if (txIdValue == null || ArchiveBlockRangeCodec.decodeCursor(txIdValue) != txNum) {
      throw new ArchiveException("archive txId index missing for committed txNum " + txNum);
    }
  }

  private static void validatePositionShape(ArchiveBlockRange range,
      ArchiveTxPosition position, long txNum) {
    if (position.getTxNum() != txNum || position.getBlockNum() != range.getBlockNum()
        || position.getSource() != range.getSource()
        || !Arrays.equals(position.getBlockHash(), range.getBlockHash())) {
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
        || position.getTxIndex() >= range.getUserTxCount()
        || position.getTxNum() != range.getFirstTxNum() + 1L + position.getTxIndex()) {
      throw new ArchiveException("archive user tx-position mismatch for txNum " + txNum);
    }
  }

  private static void validateRangeKeyMatchesValue(byte[] key, ArchiveBlockRange range) {
    if (!Arrays.equals(key, ArchiveBlockRangeCodec.rangeKey(range.getBlockNum()))) {
      throw new ArchiveException("archive block range key does not match encoded block "
          + range.getBlockNum());
    }
  }

  private byte[] get(UnifiedArchiveColumnFamily columnFamily, byte[] key) {
    UnifiedArchiveReadView view = activeReadView.get();
    if (view != null) {
      return view.getBounded(columnFamily, key, MAX_SNAPSHOT_VALUE_BYTES,
          "UNIFIED_V1 index snapshot value");
    }
    return withOwnedReadView(openUnboundReadView(), localView ->
        localView.getBounded(columnFamily, key, MAX_SNAPSHOT_VALUE_BYTES,
            "UNIFIED_V1 index value"));
  }

  private <T> T withReadView(Function<UnifiedArchiveReadView, T> action) {
    UnifiedArchiveReadView current = activeReadView.get();
    if (current != null) {
      return action.apply(current);
    }
    return withOwnedReadView(openUnboundReadView(), view -> {
      try (ReadScope ignored = bindReadView(view)) {
        return action.apply(view);
      }
    });
  }

  private UnifiedArchiveReadView openUnboundReadView() {
    QueryContext queryContext = QueryContextHolder.current();
    return queryContext == null
        ? db.openReadView() : db.openQueryReadView(queryContext, writePermit);
  }

  private <T> T withScanView(Function<UnifiedArchiveReadView, T> action) {
    UnifiedArchiveReadView current = activeReadView.get();
    if (current != null) {
      return action.apply(current);
    }
    return withOwnedReadView(db.openScanView(), view -> {
      try (ReadScope ignored = bindReadView(view)) {
        return action.apply(view);
      }
    });
  }

  private static <T> T withOwnedReadView(UnifiedArchiveReadView view,
      Function<UnifiedArchiveReadView, T> action) {
    Throwable operationFailure = null;
    try {
      return action.apply(view);
    } catch (RuntimeException | Error failure) {
      operationFailure = failure;
      throw failure;
    } finally {
      try {
        view.close();
      } catch (Throwable closeFailure) {
        if (operationFailure == null) {
          rethrowUnchecked(closeFailure);
        }
        ArchiveSnapshotReleaseException uncertain = new ArchiveSnapshotReleaseException(
            "UNIFIED_V1 index read could not confirm snapshot release", closeFailure);
        if (operationFailure != closeFailure) {
          try {
            uncertain.addSuppressed(operationFailure);
          } catch (Throwable ignored) {
            // The release marker and its direct close cause remain authoritative.
          }
        }
        throw uncertain;
      }
    }
  }

  private static void rethrowUnchecked(Throwable failure) {
    if (failure instanceof RuntimeException) {
      throw (RuntimeException) failure;
    }
    if (failure instanceof Error) {
      throw (Error) failure;
    }
    throw new ArchiveException("UNIFIED_V1 index snapshot close failed", failure);
  }

  @Override
  public synchronized void close() {
    if (!closed) {
      // Final owner of the shared DB. DefaultArchiveService closes the no-op in-flight and
      // temporal adapters before reaching this index.
      db.close();
      closed = true;
    }
  }

  public final class ReadScope implements AutoCloseable {

    private final Thread owner = Thread.currentThread();
    private final UnifiedArchiveReadView boundView;
    private boolean scopeClosed;

    private ReadScope(UnifiedArchiveReadView boundView) {
      this.boundView = boundView;
    }

    @Override
    public void close() {
      if (Thread.currentThread() != owner) {
        throw new ArchiveException("UNIFIED_V1 index read scope used from a non-owner thread");
      }
      if (!scopeClosed) {
        if (activeReadView.get() != boundView) {
          throw new ArchiveException("UNIFIED_V1 index read scope is not current");
        }
        activeReadView.remove();
        scopeClosed = true;
      }
    }
  }
}
