package org.tron.core.archive;

import org.tron.core.archive.capture.ArchiveCaptureEngine;
import org.tron.core.archive.capture.ArchiveCaptureHolder;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.domain.DefaultArchiveDomainCatalog;
import org.tron.core.archive.domain.DefaultArchiveDomainRegistry;
import org.tron.core.archive.domain.DynamicKeyPolicy;
import org.tron.core.archive.temporal.ArchiveTemporalStore;
import org.tron.core.archive.temporal.InMemoryArchiveTemporalStore;
import org.tron.core.archive.txnum.ArchiveTxNumIndex;
import org.tron.core.archive.txnum.InMemoryArchiveTxNumIndex;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;

/**
 * Default {@link ArchiveService}: allocates the canonical txNum coordinate, tracks the current
 * execution position (L2), and owns the {@link ArchiveCaptureEngine} that Store hooks route writes
 * to (L4), and an {@link ArchiveTemporalStore} that committed blocks drain into for getAsOf reads
 * (L5). When disabled every callback is a no-op and neither is installed. The temporal store is the
 * in-memory reference; the RocksDB-backed store (arm64 module) supersedes it for real nodes.
 */
public final class DefaultArchiveService implements ArchiveService {

  private final boolean enabled;
  private final ArchiveTxNumIndex txNumIndex;
  private final ArchiveExecutionContext executionContext;
  private final ArchiveCaptureEngine captureEngine;
  private final ArchiveTemporalStore temporalStore;

  public DefaultArchiveService(boolean enabled) {
    this(enabled, enabled ? new InMemoryArchiveTemporalStore() : null);
  }

  /** Production entry: the factory injects a persistent (RocksDB) temporal store when enabled. */
  public DefaultArchiveService(boolean enabled, ArchiveTemporalStore temporalStore) {
    this(enabled, new InMemoryArchiveTxNumIndex(), ArchiveExecutionContextHolder.get(),
        temporalStore);
  }

  DefaultArchiveService(boolean enabled, ArchiveTxNumIndex txNumIndex,
      ArchiveExecutionContext executionContext) {
    this(enabled, txNumIndex, executionContext,
        enabled ? new InMemoryArchiveTemporalStore() : null);
  }

  DefaultArchiveService(boolean enabled, ArchiveTxNumIndex txNumIndex,
      ArchiveExecutionContext executionContext, ArchiveTemporalStore temporalStore) {
    this.enabled = enabled;
    this.txNumIndex = txNumIndex;
    this.executionContext = executionContext;
    if (enabled) {
      this.captureEngine = new ArchiveCaptureEngine(new DefaultArchiveDomainRegistry(),
          new DefaultArchiveDomainCatalog(), new DynamicKeyPolicy(), executionContext);
      this.temporalStore = temporalStore;
      ArchiveCaptureHolder.set(captureEngine);
    } else {
      this.captureEngine = null;
      this.temporalStore = null;
    }
  }

  public ArchiveTxNumIndex getTxNumIndex() {
    return txNumIndex;
  }

  public ArchiveCaptureEngine getCaptureEngine() {
    return captureEngine;
  }

  public ArchiveTemporalStore getTemporalStore() {
    return temporalStore;
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
    // Drain the committed block's captured changes into the temporal store, then clear the buffer.
    for (ArchiveChangeRecord record : captureEngine.records()) {
      temporalStore.putChange(record);
    }
    captureEngine.clear();
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
    // Drop the reverted block's already-persisted changes (txNum >= its first txNum) before the
    // index forgets the range, so the temporal store never retains rolled-back state.
    txNumIndex.getBlockRange(block.getNum())
        .ifPresent(range -> temporalStore.unwind(range.getPrepareTxNum()));
    txNumIndex.unwindBlock(block.getNum());
    captureEngine.clear();
  }

  @Override
  public void close() {
    ArchiveCaptureHolder.clear();
    if (temporalStore instanceof AutoCloseable) {
      try {
        ((AutoCloseable) temporalStore).close();
      } catch (Exception e) {
        throw new ArchiveException("failed to close archive temporal store", e);
      }
    }
  }
}
