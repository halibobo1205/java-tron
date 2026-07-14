package org.tron.core.archive.txnum;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveSource;

/**
 * Durable {@link ArchiveTxNumIndex}: runs the in-memory allocation state machine for the current
 * pending block, and persists every committed range/position/txId lookup to a
 * {@link RocksDbArchiveBlockRangeStore}. Committed read APIs are served from the persistent store,
 * so historical trace lookup survives restart.
 */
public final class PersistentArchiveTxNumIndex implements ArchiveTxNumIndex, AutoCloseable {

  private InMemoryArchiveTxNumIndex inner;
  private final RocksDbArchiveBlockRangeStore store;
  private final byte[] schemaChecksum;

  public PersistentArchiveTxNumIndex(RocksDbArchiveBlockRangeStore store,
      byte[] schemaChecksum) {
    this(store, schemaChecksum, true);
  }

  public PersistentArchiveTxNumIndex(RocksDbArchiveBlockRangeStore store,
      byte[] schemaChecksum, boolean fullStartupValidation) {
    this(store, schemaChecksum, fullStartupValidation, false);
  }

  /**
   * Opens the durable index. {@code deferRepairValidation} is reserved for the production recovery
   * path, which validates all durable tails and journals before clearing a prior fatal marker.
   */
  public PersistentArchiveTxNumIndex(RocksDbArchiveBlockRangeStore store,
      byte[] schemaChecksum, boolean fullStartupValidation, boolean deferRepairValidation) {
    this.store = store;
    ArchiveBlockRangeCodec.requireSchemaChecksum(schemaChecksum, "archive txNum index");
    this.schemaChecksum = Arrays.copyOf(schemaChecksum, schemaChecksum.length);
    if (fullStartupValidation) {
      if (!deferRepairValidation) {
        store.validateNoRepairRequired();
      }
      store.validateSchemaChecksum(this.schemaChecksum);
      store.validateCursorConsistentWithLastRange();
      store.validateContiguousCoverage();
      store.validatePositionCoverage();
    } else {
      store.validateStartupTail(this.schemaChecksum, !deferRepairValidation);
    }
    // Resume txNum allocation from the persisted cursor so new blocks never collide with old ones.
    this.inner = delegateFromStore();
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
    return commitBlock(blockNum, new byte[0], userTxCount);
  }

  @Override
  public ArchiveBlockRange commitBlock(long blockNum, byte[] blockHash, int userTxCount) {
    return commitBlock(blockNum, blockHash, userTxCount, schemaChecksum);
  }

  @Override
  public ArchiveBlockRange commitBlock(long blockNum, byte[] blockHash, int userTxCount,
      byte[] commitSchemaChecksum) {
    ArchiveBlockRangeCodec.requireSchemaChecksum(commitSchemaChecksum, "archive txNum commit");
    if (!Arrays.equals(schemaChecksum, commitSchemaChecksum)) {
      throw new ArchiveException(
          "archive txNum commit schema checksum mismatch; rebuild or resync archive data");
    }
    ArchiveBlockRange range = inner.commitBlock(blockNum, blockHash, userTxCount);
    ArchiveBlockRange persistedRange = new ArchiveBlockRange(
        range.getBlockNum(), range.getFirstTxNum(), range.getLastTxNum(),
        range.getPrepareTxNum(), range.getFinalizeTxNum(), range.getBlockHash(),
        range.getUserTxCount(), range.getSource(), commitSchemaChecksum);
    List<ArchiveTxPosition> positions = new ArrayList<>();
    for (long txNum = range.getFirstTxNum(); txNum <= range.getLastTxNum(); txNum++) {
      Optional<ArchiveTxPosition> stored = inner.getPosition(txNum);
      if (!stored.isPresent()) {
        throw new ArchiveException("archive tx-position missing before persist for txNum "
            + txNum);
      }
      ArchiveTxPosition position = stored.get();
      positions.add(new ArchiveTxPosition(position.getTxNum(), position.getBlockNum(),
          position.getPhase(), position.getSource(), position.getTxIndex(), position.getTxId(),
          range.getBlockHash()));
    }
    try {
      store.commitRange(persistedRange, inner.getCommittedNextTxNum(), positions);
    } catch (RuntimeException e) {
      inner = delegateFromStore();
      throw e;
    }
    // Committed lookups are served by RocksDB. Retain only the allocation cursor so the delegate
    // cannot grow with chain height.
    inner = new InMemoryArchiveTxNumIndex(
        persistedRange.getLastTxNum() + 1, persistedRange.getBlockNum());
    return persistedRange;
  }

  @Override
  public void abortBlock(long blockNum) {
    inner.abortBlock(blockNum); // nothing was persisted for an uncommitted block
  }

  @Override
  public void unwindBlock(long blockNum) {
    ArchiveBlockRange range = getHeadBlockRange(blockNum);
    store.unwindRange(range, range.getFirstTxNum());
    // Re-seed the allocation state from the rewound persistent cursor. Committed lookups are served
    // from the store, so dropping the delegate's recent in-memory maps does not lose queryability.
    inner = delegateFromStore();
  }

  @Override
  public ArchiveBlockRange getHeadBlockRange(long blockNum) {
    ArchiveBlockRange range = store.getRange(blockNum)
        .orElseThrow(() -> new ArchiveException("cannot unwind block " + blockNum
            + ": not committed"));
    if (range.getLastTxNum() != store.getCursor() - 1) {
      throw new ArchiveException("cannot unwind block " + blockNum + ": not archive head");
    }
    return range;
  }

  @Override
  public void validateCanonicalHead(long headNum, byte[] headHash) {
    store.validateCanonicalHead(headNum, headHash);
  }

  @Override
  public Optional<ArchiveBlockRange> getBlockRange(long blockNum) {
    return store.getRange(blockNum);
  }

  @Override
  public Optional<ArchiveTxPosition> getPosition(long txNum) {
    return store.getPosition(txNum);
  }

  @Override
  public OptionalLong findTxNumByBlockAndIndex(long blockNum, int txIndex) {
    return store.findTxNumByBlockAndIndex(blockNum, txIndex);
  }

  @Override
  public OptionalLong findTxNumByTxId(byte[] txId) {
    return store.findTxNumByTxId(txId);
  }

  @Override
  public long getNextTxNum() {
    return store.getCursor();
  }

  @Override
  public long getLastArchivedBlock() {
    return store.getLastRange()
        .map(ArchiveBlockRange::getBlockNum)
        .orElse(-1L);
  }

  @Override
  public void markRepairRequired(String reason) {
    store.markRepairRequired(reason);
  }

  @Override
  public void clearRepairRequired() {
    store.clearRepairRequired();
  }

  @Override
  public long getFirstArchivedBlock() {
    // The persisted floor survives restart, unlike the in-memory window which only holds blocks
    // committed since startup.
    return store.getFirstArchivedBlock();
  }

  private InMemoryArchiveTxNumIndex delegateFromStore() {
    long lastCommittedBlock = store.getLastRange()
        .map(ArchiveBlockRange::getBlockNum)
        .orElse(-1L);
    return new InMemoryArchiveTxNumIndex(store.getCursor(), lastCommittedBlock);
  }

  @Override
  public void close() {
    store.close();
  }
}
