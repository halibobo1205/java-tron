package org.tron.core.archive.txnum;

import java.util.ArrayList;
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

  public PersistentArchiveTxNumIndex(RocksDbArchiveBlockRangeStore store) {
    this.store = store;
    store.validateCursorConsistentWithLastRange();
    store.validateContiguousCoverage();
    // Resume txNum allocation from the persisted cursor so new blocks never collide with old ones.
    this.inner = new InMemoryArchiveTxNumIndex(store.getCursor());
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
    ArchiveBlockRange range = inner.commitBlock(blockNum, blockHash, userTxCount);
    List<ArchiveTxPosition> positions = new ArrayList<>();
    for (long txNum = range.getFirstTxNum(); txNum <= range.getLastTxNum(); txNum++) {
      inner.getPosition(txNum).ifPresent(positions::add);
    }
    try {
      store.commitRange(range, inner.getCommittedNextTxNum(), positions);
    } catch (RuntimeException e) {
      inner = new InMemoryArchiveTxNumIndex(store.getCursor());
      throw e;
    }
    return range;
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
    inner = new InMemoryArchiveTxNumIndex(range.getFirstTxNum());
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
  public long getFirstArchivedBlock() {
    // The persisted floor survives restart, unlike the in-memory window which only holds blocks
    // committed since startup.
    return store.getFirstArchivedBlock();
  }

  @Override
  public void close() {
    store.close();
  }
}
