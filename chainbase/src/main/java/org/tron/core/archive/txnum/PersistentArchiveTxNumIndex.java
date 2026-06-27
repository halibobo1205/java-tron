package org.tron.core.archive.txnum;

import java.util.Optional;
import java.util.OptionalLong;
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveSource;

/**
 * Durable {@link ArchiveTxNumIndex}: runs the in-memory allocation state machine but persists each
 * committed block's range + the committed cursor to a {@link RocksDbArchiveBlockRangeStore}, so the
 * block-to-txNum mapping (and txNum allocation) survive restart. {@code getBlockRange} falls to
 * the persistent store for blocks committed before this process started.
 *
 * <p>Per-tx lookups (getPosition / findTxNumBy*) are still served from the in-memory delegate, so
 * they only cover blocks committed since startup; persisting those indexes is a later concern.
 */
public final class PersistentArchiveTxNumIndex implements ArchiveTxNumIndex, AutoCloseable {

  private final InMemoryArchiveTxNumIndex inner;
  private final RocksDbArchiveBlockRangeStore store;

  public PersistentArchiveTxNumIndex(RocksDbArchiveBlockRangeStore store) {
    this.store = store;
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
    ArchiveBlockRange range = inner.commitBlock(blockNum, userTxCount);
    store.commitRange(range, inner.getCommittedNextTxNum());
    return range;
  }

  @Override
  public void abortBlock(long blockNum) {
    inner.abortBlock(blockNum); // nothing was persisted for an uncommitted block
  }

  @Override
  public void unwindBlock(long blockNum) {
    inner.unwindBlock(blockNum);
    store.unwindRange(blockNum, inner.getCommittedNextTxNum());
  }

  @Override
  public Optional<ArchiveBlockRange> getBlockRange(long blockNum) {
    Optional<ArchiveBlockRange> recent = inner.getBlockRange(blockNum);
    return recent.isPresent() ? recent : store.getRange(blockNum);
  }

  @Override
  public Optional<ArchiveTxPosition> getPosition(long txNum) {
    return inner.getPosition(txNum);
  }

  @Override
  public OptionalLong findTxNumByBlockAndIndex(long blockNum, int txIndex) {
    return inner.findTxNumByBlockAndIndex(blockNum, txIndex);
  }

  @Override
  public OptionalLong findTxNumByTxId(byte[] txId) {
    return inner.findTxNumByTxId(txId);
  }

  @Override
  public void close() {
    store.close();
  }
}
