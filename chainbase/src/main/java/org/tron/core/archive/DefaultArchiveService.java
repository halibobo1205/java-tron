package org.tron.core.archive;

import org.tron.core.archive.txnum.ArchiveTxNumIndex;
import org.tron.core.archive.txnum.ArchiveTxPosition;
import org.tron.core.archive.txnum.InMemoryArchiveTxNumIndex;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;

/**
 * L2 {@link ArchiveService}: allocates the canonical txNum coordinate and tracks the current
 * execution position. When disabled every callback is a no-op (only the thread-local context is
 * defensively cleared). L2 does not persist anything; L5 wires in the temporal store.
 */
public final class DefaultArchiveService implements ArchiveService {

  private final boolean enabled;
  private final ArchiveTxNumIndex txNumIndex;
  private final ArchiveExecutionContext executionContext;

  public DefaultArchiveService(boolean enabled) {
    this(enabled, new InMemoryArchiveTxNumIndex(), ArchiveExecutionContextHolder.get());
  }

  DefaultArchiveService(boolean enabled, ArchiveTxNumIndex txNumIndex,
      ArchiveExecutionContext executionContext) {
    this.enabled = enabled;
    this.txNumIndex = txNumIndex;
    this.executionContext = executionContext;
  }

  public ArchiveTxNumIndex getTxNumIndex() {
    return txNumIndex;
  }

  @Override
  public boolean isEnabled() {
    return enabled;
  }

  @Override
  public void beginBlock(BlockCapsule block, ArchiveSource source) {
    if (!enabled) {
      return;
    }
    txNumIndex.beginBlock(block.getNum(), source);
  }

  @Override
  public void beginSystemTx(BlockCapsule block, ArchivePhase phase) {
    if (!enabled) {
      return;
    }
    executionContext.enter(txNumIndex.allocateSystemTx(block.getNum(), phase));
  }

  @Override
  public void beginUserTx(BlockCapsule block, int txIndex, TransactionCapsule tx) {
    if (!enabled) {
      return;
    }
    byte[] txId = (tx == null) ? null : tx.getTransactionId().getBytes();
    executionContext.enter(txNumIndex.allocateUserTx(block.getNum(), txIndex, txId));
  }

  @Override
  public void endTx() {
    executionContext.clear();
  }

  @Override
  public void commitBlock(BlockCapsule block) {
    if (!enabled) {
      return;
    }
    txNumIndex.commitBlock(block.getNum(), block.getTransactions().size());
  }

  @Override
  public void abortBlock(BlockCapsule block) {
    executionContext.clear();
    if (!enabled) {
      return;
    }
    txNumIndex.abortBlock(block.getNum());
  }

  @Override
  public void unwindBlock(BlockCapsule block) {
    if (!enabled) {
      return;
    }
    txNumIndex.unwindBlock(block.getNum());
  }
}
