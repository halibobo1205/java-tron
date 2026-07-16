package org.tron.core.archive.txnum;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;
import org.rocksdb.RocksIterator;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.ArchiveInFlightBlock;
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveRocksIterators;
import org.tron.core.archive.ArchiveSource;
import org.tron.core.archive.unified.UnifiedArchiveColumnFamily;
import org.tron.core.archive.unified.UnifiedArchiveDb;
import org.tron.core.archive.unified.UnifiedArchiveManifest;
import org.tron.core.archive.unified.UnifiedArchivePublish;
import org.tron.core.archive.unified.UnifiedArchiveReadView;

/** Persistent txNum/index adapter over the UNIFIED_V1 INDEX and META column families. */
public final class UnifiedArchiveTxNumIndex implements ArchiveTxNumIndex, AutoCloseable {

  private static final long NO_FIRST_BLOCK = -1L;

  private final UnifiedArchiveDb db;
  private final byte[] schemaChecksum;
  private final ThreadLocal<UnifiedArchiveReadView> activeReadView = new ThreadLocal<>();
  private InMemoryArchiveTxNumIndex inner;
  private boolean closed;

  public UnifiedArchiveTxNumIndex(UnifiedArchiveDb db, byte[] schemaChecksum,
      boolean fullStartupValidation, boolean deferRepairValidation) {
    if (db == null) {
      throw new NullPointerException("db");
    }
    ArchiveBlockRangeCodec.requireSchemaChecksum(schemaChecksum, "archive txNum index");
    this.db = db;
    this.schemaChecksum = Arrays.copyOf(schemaChecksum, schemaChecksum.length);
    validateStartup(fullStartupValidation, deferRepairValidation);
    inner = delegateFromStore();
  }

  /** Binds index reads to the same unified snapshot used by the temporal reader. */
  public ReadScope bindReadView(UnifiedArchiveReadView view) {
    if (view == null) {
      throw new NullPointerException("view");
    }
    if (activeReadView.get() != null) {
      throw new ArchiveException("UNIFIED_V1 index read view is already bound");
    }
    activeReadView.set(view);
    return new ReadScope();
  }

  /** Adds every index row and the published cursor to an atomic block publication. */
  public ArchiveBlockRange stagePublication(UnifiedArchivePublish.Builder publish,
      ArchiveInFlightBlock block) {
    if (publish == null || block == null) {
      throw new NullPointerException("publish/block");
    }
    ArchiveBlockRange expected = block.getRange();
    try {
      inner.beginBlock(expected.getBlockNum(), expected.getSource());
      for (ArchiveTxPosition position : block.getPositions()) {
        ArchiveTxPosition allocated = position.getPhase() == ArchivePhase.USER_TX
            ? inner.allocateUserTx(position.getBlockNum(), position.getTxIndex(),
                position.getTxId())
            : inner.allocateSystemTx(position.getBlockNum(), position.getPhase());
        validateEquivalentPosition(position, allocated);
      }
      ArchiveBlockRange allocated = inner.commitBlock(
          expected.getBlockNum(), expected.getBlockHash(), expected.getUserTxCount());
      ArchiveBlockRange persisted = new ArchiveBlockRange(
          allocated.getBlockNum(), allocated.getFirstTxNum(), allocated.getLastTxNum(),
          allocated.getPrepareTxNum(), allocated.getFinalizeTxNum(), allocated.getBlockHash(),
          allocated.getUserTxCount(), allocated.getSource(), schemaChecksum);
      validateEquivalentRange(expected, persisted);
      validateAppendOnlyCommit(persisted, inner.getCommittedNextTxNum());

      Optional<ArchiveBlockRange> lastRange = getLastRange();
      publish.put(UnifiedArchiveColumnFamily.INDEX,
          ArchiveBlockRangeCodec.rangeKey(persisted.getBlockNum()),
          ArchiveBlockRangeCodec.encodeRange(persisted));
      if (!lastRange.isPresent()) {
        publish.put(UnifiedArchiveColumnFamily.INDEX, ArchiveBlockRangeCodec.FIRST_BLOCK_KEY,
            ArchiveBlockRangeCodec.encodeFirstBlock(persisted.getBlockNum()));
      }
      for (ArchiveTxPosition allocatedPosition : positionsOf(allocated)) {
        ArchiveTxPosition position = new ArchiveTxPosition(
            allocatedPosition.getTxNum(), allocatedPosition.getBlockNum(),
            allocatedPosition.getPhase(), allocatedPosition.getSource(),
            allocatedPosition.getTxIndex(), allocatedPosition.getTxId(),
            persisted.getBlockHash());
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
          ArchiveBlockRangeCodec.encodeCursor(inner.getCommittedNextTxNum()));
      return persisted;
    } catch (RuntimeException e) {
      resetAfterPublication();
      throw e;
    }
  }

  public void publicationSucceeded(ArchiveBlockRange range) {
    inner = new InMemoryArchiveTxNumIndex(range.getLastTxNum() + 1L, range.getBlockNum());
  }

  public void publicationFailed() {
    resetAfterPublication();
  }

  @Override
  public void beginBlock(long blockNum, ArchiveSource source) {
    inner.beginBlock(blockNum, source);
  }

  @Override
  public ArchiveTxPosition allocateSystemTx(long blockNum, ArchivePhase phase) {
    return inner.allocateSystemTx(blockNum, phase);
  }

  @Override
  public ArchiveTxPosition allocateUserTx(long blockNum, int txIndex, byte[] txId) {
    return inner.allocateUserTx(blockNum, txIndex, txId);
  }

  @Override
  public ArchiveBlockRange commitBlock(long blockNum, int userTxCount) {
    throw new ArchiveException("UNIFIED_V1 index commits only through atomic block publication");
  }

  @Override
  public void abortBlock(long blockNum) {
    inner.abortBlock(blockNum);
  }

  @Override
  public void unwindBlock(long blockNum) {
    throw new ArchiveException("cannot unwind published UNIFIED_V1 archive block " + blockNum);
  }

  @Override
  public ArchiveBlockRange getHeadBlockRange(long blockNum) {
    ArchiveBlockRange range = getBlockRange(blockNum)
        .orElseThrow(() -> new ArchiveException("cannot unwind block " + blockNum
            + ": not committed"));
    Optional<ArchiveBlockRange> last = getLastRange();
    if (!last.isPresent() || last.get().getBlockNum() != blockNum) {
      throw new ArchiveException("cannot unwind block " + blockNum + ": not archive head");
    }
    return range;
  }

  @Override
  public void validateCanonicalHead(long headNum, byte[] headHash) {
    if (headNum < 0) {
      throw new ArchiveException("archive head block number must be non-negative");
    }
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
    if (blockNum < 0) {
      throw new ArchiveException("archive block range block number must be non-negative");
    }
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
    if (txNum < 0) {
      throw new ArchiveException("archive tx-position txNum must be non-negative");
    }
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
    if (txId == null || txId.length == 0) {
      return OptionalLong.empty();
    }
    ArchiveBlockRangeCodec.requireTxId(txId, "archive txId lookup");
    byte[] value = get(UnifiedArchiveColumnFamily.INDEX,
        ArchiveBlockRangeCodec.txIdKey(txId));
    if (value == null) {
      return OptionalLong.empty();
    }
    long txNum = ArchiveBlockRangeCodec.decodeCursor(value);
    ArchiveTxPosition position = getPosition(txNum)
        .orElseThrow(() -> new ArchiveException("archive txId index is orphan for txNum "
            + txNum));
    if (!Arrays.equals(position.getTxId(), txId)) {
      throw new ArchiveException("archive txId index does not match tx-position for txNum "
          + txNum);
    }
    return OptionalLong.of(txNum);
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

  @Override
  public void markRepairRequired(String reason) {
    db.putMetaDurably(ArchiveBlockRangeCodec.REPAIR_REQUIRED_KEY,
        ArchiveBlockRangeCodec.encodeRepairRequired(reason));
  }

  @Override
  public void clearRepairRequired() {
    if (db.get(UnifiedArchiveColumnFamily.META,
        ArchiveBlockRangeCodec.REPAIR_REQUIRED_KEY) != null) {
      db.deleteMetaDurably(ArchiveBlockRangeCodec.REPAIR_REQUIRED_KEY);
    }
  }

  @Override
  public boolean hasRepairRequired() {
    return get(UnifiedArchiveColumnFamily.META,
        ArchiveBlockRangeCodec.REPAIR_REQUIRED_KEY) != null;
  }

  public void validateStartup(boolean full, boolean deferRepairValidation) {
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
    if (full) {
      validateFullKeyspace();
      validateFullCoverage();
    }
  }

  private void validateFullKeyspace() {
    withScanView(view -> {
      RocksIterator meta = view.newIterator(UnifiedArchiveColumnFamily.META);
      meta.seekToFirst();
      while (meta.isValid()) {
        byte[] key = meta.key();
        byte[] value = meta.value();
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

      RocksIterator index = view.newIterator(UnifiedArchiveColumnFamily.INDEX);
      index.seekToFirst();
      while (index.isValid()) {
        byte[] key = index.key();
        if (Arrays.equals(key, ArchiveBlockRangeCodec.FIRST_BLOCK_KEY)) {
          ArchiveBlockRangeCodec.decodeFirstBlock(index.value());
          index.next();
          continue;
        }
        if (key.length == 0) {
          throw new ArchiveException("UNIFIED_V1 index column family has an empty key");
        }
        switch (key[0]) {
          case ArchiveBlockRangeCodec.TXNUM_BLOCK_PREFIX:
            ArchiveBlockRange range = ArchiveBlockRangeCodec.decodeRange(index.value());
            validateRangeKeyMatchesValue(key, range);
            validateRangeShape(range);
            break;
          case ArchiveBlockRangeCodec.TXNUM_BY_TXID_PREFIX:
            byte[] txId = ArchiveBlockRangeCodec.txIdFromKey(key);
            long txNum = ArchiveBlockRangeCodec.decodeCursor(index.value());
            ArchiveTxPosition txIdPosition = getPosition(txNum)
                .orElseThrow(() -> new ArchiveException(
                    "UNIFIED_V1 txId row has no committed position"));
            if (!Arrays.equals(txId, txIdPosition.getTxId())) {
              throw new ArchiveException("UNIFIED_V1 txId row does not match its position");
            }
            break;
          case ArchiveBlockRangeCodec.TXNUM_META_PREFIX:
            long positionTxNum = ArchiveBlockRangeCodec.txNumFromPositionKey(key);
            ArchiveTxPosition decoded = ArchiveBlockRangeCodec.decodePosition(index.value());
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
      RocksIterator iterator = view.newIterator(UnifiedArchiveColumnFamily.INDEX);
      iterator.seek(new byte[] {ArchiveBlockRangeCodec.TXNUM_BY_TXID_PREFIX});
      if (iterator.isValid()) {
        iterator.prev();
      } else {
        iterator.seekToLast();
      }
      ArchiveRocksIterators.requireOk(iterator, "UNIFIED_V1 locate highest range row");
      if (!iterator.isValid()
          || iterator.key()[0] != ArchiveBlockRangeCodec.TXNUM_BLOCK_PREFIX) {
        return Optional.empty();
      }
      ArchiveBlockRange range = ArchiveBlockRangeCodec.decodeRange(iterator.value());
      validateRangeKeyMatchesValue(iterator.key(), range);
      validateRangeShape(range);
      return Optional.of(range);
    });
  }

  private void validateFullCoverage() {
    withScanView(view -> {
      RocksIterator iterator = view.newIterator(UnifiedArchiveColumnFamily.INDEX);
      iterator.seek(new byte[] {ArchiveBlockRangeCodec.TXNUM_BLOCK_PREFIX});
      ArchiveBlockRange previous = null;
      while (iterator.isValid()
          && iterator.key()[0] == ArchiveBlockRangeCodec.TXNUM_BLOCK_PREFIX) {
        ArchiveBlockRange current = ArchiveBlockRangeCodec.decodeRange(iterator.value());
        validateRangeKeyMatchesValue(iterator.key(), current);
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
        for (long txNum = current.getFirstTxNum();
            txNum <= current.getLastTxNum(); txNum++) {
          long currentTxNum = txNum;
          getPosition(currentTxNum).orElseThrow(() -> new ArchiveException(
              "archive tx-position missing for committed txNum " + currentTxNum));
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
    if (range.getBlockNum() < 0 || range.getFirstTxNum() < 0 || range.getLastTxNum() < 0
        || range.getPrepareTxNum() != range.getFirstTxNum()
        || range.getFinalizeTxNum() != range.getLastTxNum()
        || range.getUserTxCount() < 0
        || range.getLastTxNum() - range.getFirstTxNum() + 1L
            != (long) range.getUserTxCount() + 2L) {
      throw new ArchiveException("archive block range shape is invalid for block "
          + range.getBlockNum());
    }
  }

  private void validatePosition(ArchiveBlockRange range, ArchiveTxPosition position,
      long txNum) {
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
    byte[] txIdValue = get(UnifiedArchiveColumnFamily.INDEX,
        ArchiveBlockRangeCodec.txIdKey(position.getTxId()));
    if (txIdValue == null || ArchiveBlockRangeCodec.decodeCursor(txIdValue) != txNum) {
      throw new ArchiveException("archive txId index missing for committed txNum " + txNum);
    }
  }

  private static void validateEquivalentPosition(ArchiveTxPosition expected,
      ArchiveTxPosition actual) {
    if (expected.getTxNum() != actual.getTxNum()
        || expected.getBlockNum() != actual.getBlockNum()
        || expected.getPhase() != actual.getPhase()
        || expected.getSource() != actual.getSource()
        || expected.getTxIndex() != actual.getTxIndex()
        || !Arrays.equals(expected.getTxId(), actual.getTxId())) {
      throw new ArchiveException("UNIFIED_V1 allocated tx-position does not match journal");
    }
  }

  private static void validateEquivalentRange(ArchiveBlockRange expected,
      ArchiveBlockRange actual) {
    if (expected.getBlockNum() != actual.getBlockNum()
        || expected.getFirstTxNum() != actual.getFirstTxNum()
        || expected.getLastTxNum() != actual.getLastTxNum()
        || expected.getPrepareTxNum() != actual.getPrepareTxNum()
        || expected.getFinalizeTxNum() != actual.getFinalizeTxNum()
        || expected.getUserTxCount() != actual.getUserTxCount()
        || expected.getSource() != actual.getSource()
        || !Arrays.equals(expected.getBlockHash(), actual.getBlockHash())
        || !Arrays.equals(expected.getSchemaChecksum(), actual.getSchemaChecksum())) {
      throw new ArchiveException("UNIFIED_V1 published range does not match journal block "
          + expected.getBlockNum());
    }
  }

  private static void validateRangeKeyMatchesValue(byte[] key, ArchiveBlockRange range) {
    if (!Arrays.equals(key, ArchiveBlockRangeCodec.rangeKey(range.getBlockNum()))) {
      throw new ArchiveException("archive block range key does not match encoded block "
          + range.getBlockNum());
    }
  }

  private static List<ArchiveTxPosition> positionsOf(ArchiveBlockRange range,
      InMemoryArchiveTxNumIndex index) {
    List<ArchiveTxPosition> positions = new ArrayList<>();
    for (long txNum = range.getFirstTxNum(); txNum <= range.getLastTxNum(); txNum++) {
      long currentTxNum = txNum;
      positions.add(index.getPosition(currentTxNum)
          .orElseThrow(() -> new ArchiveException(
              "archive tx-position missing before UNIFIED_V1 publication for txNum "
                  + currentTxNum)));
    }
    return positions;
  }

  private List<ArchiveTxPosition> positionsOf(ArchiveBlockRange range) {
    return positionsOf(range, inner);
  }

  private byte[] get(UnifiedArchiveColumnFamily columnFamily, byte[] key) {
    UnifiedArchiveReadView view = activeReadView.get();
    return view == null ? db.get(columnFamily, key) : view.get(columnFamily, key);
  }

  private <T> T withReadView(Function<UnifiedArchiveReadView, T> action) {
    UnifiedArchiveReadView current = activeReadView.get();
    if (current != null) {
      return action.apply(current);
    }
    try (UnifiedArchiveReadView view = db.openReadView()) {
      activeReadView.set(view);
      try {
        return action.apply(view);
      } finally {
        activeReadView.remove();
      }
    }
  }

  private <T> T withScanView(Function<UnifiedArchiveReadView, T> action) {
    UnifiedArchiveReadView current = activeReadView.get();
    if (current != null) {
      return action.apply(current);
    }
    try (UnifiedArchiveReadView view = db.openScanView()) {
      activeReadView.set(view);
      try {
        return action.apply(view);
      } finally {
        activeReadView.remove();
      }
    }
  }

  private InMemoryArchiveTxNumIndex delegateFromStore() {
    return new InMemoryArchiveTxNumIndex(getNextTxNum(), getLastArchivedBlock());
  }

  private void resetAfterPublication() {
    inner = delegateFromStore();
  }

  @Override
  public void close() {
    if (!closed) {
      closed = true;
      // Final owner of the shared DB. DefaultArchiveService closes the no-op in-flight and
      // temporal adapters before reaching this index.
      db.close();
    }
  }

  public final class ReadScope implements AutoCloseable {

    private boolean scopeClosed;

    private ReadScope() {
    }

    @Override
    public void close() {
      if (!scopeClosed) {
        scopeClosed = true;
        activeReadView.remove();
      }
    }
  }
}
