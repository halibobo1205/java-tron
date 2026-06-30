package org.tron.core.archive;

import com.google.common.primitives.Bytes;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.tron.core.archive.capture.ArchiveCaptureEngine;
import org.tron.core.archive.capture.ArchiveCaptureHolder;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.domain.ArchiveDomainCatalog;
import org.tron.core.archive.domain.DefaultArchiveDomainCatalog;
import org.tron.core.archive.domain.DefaultArchiveDomainRegistry;
import org.tron.core.archive.domain.DynamicKeyPolicy;
import org.tron.core.archive.reader.ArchiveStateReaderFactory;
import org.tron.core.archive.reader.DefaultArchiveStateReaderFactory;
import org.tron.core.archive.temporal.ArchiveTemporalStore;
import org.tron.core.archive.temporal.InMemoryArchiveTemporalStore;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.archive.txnum.ArchiveTxNumIndex;
import org.tron.core.archive.txnum.InMemoryArchiveTxNumIndex;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.db2.common.WrappedByteArray;

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
  private final ArchiveStateReaderFactory readerFactory;

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
      ArchiveDomainCatalog catalog = new DefaultArchiveDomainCatalog();
      this.captureEngine = new ArchiveCaptureEngine(new DefaultArchiveDomainRegistry(),
          catalog, new DynamicKeyPolicy(), executionContext);
      this.temporalStore = temporalStore;
      this.readerFactory = new DefaultArchiveStateReaderFactory(temporalStore, catalog);
      ArchiveCaptureHolder.set(captureEngine);
    } else {
      this.captureEngine = null;
      this.temporalStore = null;
      this.readerFactory = null;
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

  /** Opens historical state readers over the temporal store; null when archive is disabled. */
  public ArchiveStateReaderFactory getReaderFactory() {
    return readerFactory;
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
    boolean txNumCommitted = false;
    try {
      if (captureEngine.failure().isPresent()) {
        throw captureEngine.failure().get();
      }
      // Drain captured changes into the temporal store, then clear the buffer.
      // Collapse repeated (domain,key,txNum) captures within this block into one change:
      // keep the FIRST prevValue (the value before the tx's first sub-change, so history stores the
      // true pre-tx value -- Erigon AddPrevValue) and the LAST value (after its last sub-change).
      Map<WrappedByteArray, ArchiveChangeRecord> merged = new LinkedHashMap<>();
      for (ArchiveChangeRecord record : captureEngine.records()) {
        WrappedByteArray id = mergeKey(record);
        ArchiveChangeRecord prior = merged.get(id);
        merged.put(id, prior == null ? record
            : new ArchiveChangeRecord(prior.getPosition(), prior.getDomain(),
                prior.getCanonicalKey(), prior.getPrevValue(), record.getValue()));
      }
      List<ArchiveChangeRecord> records = new ArrayList<>(merged.values());
      ArchiveBlockRange range = txNumIndex.commitBlock(
          block.getNum(), block.getTransactions().size());
      txNumCommitted = true;
      temporalStore.putBlockChanges(range, records);
    } catch (RuntimeException e) {
      try {
        if (txNumCommitted) {
          txNumIndex.unwindBlock(block.getNum());
        } else {
          txNumIndex.abortBlock(block.getNum());
        }
      } catch (RuntimeException cleanupFailure) {
        e.addSuppressed(cleanupFailure);
      }
      throw e;
    } finally {
      executionContext.clear();
      captureEngine.clear();
    }
  }

  /** Identity for within-block change collapsing: (domainId, txNum, canonicalKey). */
  private static WrappedByteArray mergeKey(ArchiveChangeRecord record) {
    return WrappedByteArray.of(Bytes.concat(
        Ints.toByteArray(record.getDomain().getId()),
        Longs.toByteArray(record.getTxNum()),
        record.getCanonicalKey()));
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
        .ifPresent(temporalStore::unwindBlock);
    txNumIndex.unwindBlock(block.getNum());
    captureEngine.clear();
  }

  @Override
  public void close() {
    ArchiveCaptureHolder.clear();
    closeQuietly(temporalStore, "temporal store");
    closeQuietly(txNumIndex, "txNum index");
  }

  private static void closeQuietly(Object resource, String name) {
    if (resource instanceof AutoCloseable) {
      try {
        ((AutoCloseable) resource).close();
      } catch (Exception e) {
        throw new ArchiveException("failed to close archive " + name, e);
      }
    }
  }
}
