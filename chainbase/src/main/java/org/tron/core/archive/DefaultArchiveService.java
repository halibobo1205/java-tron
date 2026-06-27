package org.tron.core.archive;

import org.tron.core.archive.capture.ArchiveCaptureEngine;
import org.tron.core.archive.capture.ArchiveCaptureHolder;
import org.tron.core.archive.domain.DefaultArchiveDomainCatalog;
import org.tron.core.archive.domain.DefaultArchiveDomainRegistry;
import org.tron.core.archive.domain.DynamicKeyPolicy;
import org.tron.core.archive.txnum.ArchiveTxNumIndex;
import org.tron.core.archive.txnum.InMemoryArchiveTxNumIndex;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;

/**
 * Default {@link ArchiveService}: allocates the canonical txNum coordinate, tracks the current
 * execution position (L2), and owns the {@link ArchiveCaptureEngine} that Store hooks route writes
 * to (L4). When disabled every callback is a no-op and no capture engine is installed. Nothing is
 * persisted yet; L5 wires in the temporal store.
 */
public final class DefaultArchiveService implements ArchiveService {

  private final boolean enabled;
  private final ArchiveTxNumIndex txNumIndex;
  private final ArchiveExecutionContext executionContext;
  private final ArchiveCaptureEngine captureEngine;

  public DefaultArchiveService(boolean enabled) {
    this(enabled, new InMemoryArchiveTxNumIndex(), ArchiveExecutionContextHolder.get());
  }

  DefaultArchiveService(boolean enabled, ArchiveTxNumIndex txNumIndex,
      ArchiveExecutionContext executionContext) {
    this.enabled = enabled;
    this.txNumIndex = txNumIndex;
    this.executionContext = executionContext;
    if (enabled) {
      this.captureEngine = new ArchiveCaptureEngine(new DefaultArchiveDomainRegistry(),
          new DefaultArchiveDomainCatalog(), new DynamicKeyPolicy(), executionContext);
      ArchiveCaptureHolder.set(captureEngine);
    } else {
      this.captureEngine = null;
    }
  }

  public ArchiveTxNumIndex getTxNumIndex() {
    return txNumIndex;
  }

  public ArchiveCaptureEngine getCaptureEngine() {
    return captureEngine;
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
    captureEngine.clear();
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
    captureEngine.clear();
  }

  @Override
  public void unwindBlock(BlockCapsule block) {
    if (!enabled) {
      return;
    }
    txNumIndex.unwindBlock(block.getNum());
    captureEngine.clear();
  }
}
