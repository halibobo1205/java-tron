package org.tron.core.archive;

import com.google.common.primitives.Bytes;
import com.google.common.primitives.Ints;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tron.core.archive.capture.ArchiveCaptureEngine;
import org.tron.core.archive.capture.ArchiveCaptureHolder;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.domain.ArchiveDomainCatalog;
import org.tron.core.archive.domain.ArchiveDomainRegistry;
import org.tron.core.archive.domain.ArchiveSchemaChecksum;
import org.tron.core.archive.domain.DefaultArchiveDomainCatalog;
import org.tron.core.archive.domain.DefaultArchiveDomainRegistry;
import org.tron.core.archive.domain.DynamicKeyPolicy;
import org.tron.core.archive.reader.ArchiveReaderException;
import org.tron.core.archive.reader.ArchiveReadThrough;
import org.tron.core.archive.reader.ArchiveStateReaderFactory;
import org.tron.core.archive.reader.ArchiveStatePoint;
import org.tron.core.archive.reader.ArchiveStateReader;
import org.tron.core.archive.reader.DefaultArchiveStateReaderFactory;
import org.tron.core.archive.reader.ManagedArchiveStateReader;
import org.tron.core.archive.query.ArchiveQueryCoordinator;
import org.tron.core.archive.query.ArchiveQueryLimits;
import org.tron.core.archive.query.ArchiveSnapshotPermit;
import org.tron.core.archive.query.HistoricalQueryLimitException;
import org.tron.core.archive.query.QueryContext;
import org.tron.core.archive.query.QueryContextHolder;
import org.tron.core.archive.query.QueryLease;
import org.tron.core.archive.temporal.ArchiveTemporalReadView;
import org.tron.core.archive.temporal.ArchiveTemporalStore;
import org.tron.core.archive.temporal.InMemoryArchiveTemporalStore;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.archive.txnum.ArchiveCoordinates;
import org.tron.core.archive.txnum.ArchiveTxPosition;
import org.tron.core.archive.txnum.ArchiveTxNumIndex;
import org.tron.core.archive.txnum.InMemoryArchiveTxNumIndex;
import org.tron.core.archive.txnum.UnifiedArchiveTxNumIndex;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.db2.common.WrappedByteArray;

/**
 * Default {@link ArchiveService}: allocates the canonical txNum coordinate, tracks the current
 * execution position (L2), and owns the {@link ArchiveCaptureEngine} that Store hooks route writes
 * to (L4), and an {@link ArchiveTemporalStore} that committed blocks drain into for getAsOf reads
 * (L5). When disabled every callback is a no-op and neither is installed. Tests may use the
 * in-memory reference; enabled production nodes use the unified cross-column-family backend.
 */
public final class DefaultArchiveService implements ArchiveService {

  private static final Logger logger = LoggerFactory.getLogger("archive");
  private static final long DISK_SAMPLE_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1L);
  private static final long MIN_JOURNAL_DISK_HEADROOM_BYTES = 16L * 1024 * 1024;
  private static final long DEFAULT_CLOSE_DRAIN_TIMEOUT_NANOS =
      TimeUnit.SECONDS.toNanos(30L);

  private final boolean enabled;
  /** Published, durable archive index visible to readers. */
  private final ArchiveTxNumIndex txNumIndex;
  /** Execution-only txNum allocator for canonical but not-yet-solidified blocks. */
  private InMemoryArchiveTxNumIndex executionTxNumIndex;
  private final ArchiveExecutionContext executionContext;
  private final ArchiveCaptureEngine captureEngine;
  private final ArchiveTemporalStore temporalStore;
  private final ArchiveInFlightStore inFlightStore;
  private final UnifiedArchiveBackend unifiedBackend;
  private final DefaultArchiveStateReaderFactory readerFactory;
  private final byte[] schemaChecksum;
  private final ReentrantReadWriteLock consistencyLock = new ReentrantReadWriteLock(true);
  private final NavigableMap<Long, ArchiveInFlightBlock> inFlightBlocks = new TreeMap<>();
  // Journals whose ranges are already published remain durable until canonical startup
  // preflight proves that the matching canonical block is available.
  private final NavigableMap<Long, ArchiveInFlightBlock> pendingPublishedJournals =
      new TreeMap<>();
  // Per-key append-tail/publish-head/unwind-tail versions. Startup builds this once from durable
  // journals; steady-state transitions only touch records belonging to the current block.
  private final Map<WrappedByteArray, Deque<InFlightVersion>> inFlightVersions = new HashMap<>();
  // Defensive backstop on the committed-not-solidified in-flight buffer. Healthy DPoS
  // solidification lags the head by tens of blocks; this cap sits far above any legitimate lag and
  // only fires if solidification stalls while the head keeps advancing -- so the node fail-stops
  // instead of OOMing.
  static final int DEFAULT_MAX_IN_FLIGHT_BLOCKS = 65_536;
  private int maxInFlightBlocks = DEFAULT_MAX_IN_FLIGHT_BLOCKS;
  private final Object backlogMonitor = new Object();
  private volatile int inFlightBlockCount;
  private volatile long inFlightRecordCount;
  private volatile long inFlightRetainedBytes;
  private final Object diskSampleMonitor = new Object();
  private volatile long lastDiskSampleNanos = Long.MIN_VALUE;
  private volatile long lastUsableSpaceBytes = Long.MAX_VALUE;
  private final ArchiveLifecycle lifecycle;
  private final ArchiveMutationBarrier mutationBarrier = new ArchiveMutationBarrier();
  private final ArchiveQueryCoordinator queryCoordinator;
  private final BoundedArchivePublisher publisher;
  private final ArchiveFatalController fatalController;
  private final ArchivePublisherConfig publisherConfig;
  private final Runnable startupValidator;
  private boolean startupStorageValidated;
  private volatile long closeDrainTimeoutNanos = DEFAULT_CLOSE_DRAIN_TIMEOUT_NANOS;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Object closeMutex = new Object();

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
    this(enabled, txNumIndex, executionContext, temporalStore, new InMemoryArchiveInFlightStore(),
        new DefaultArchiveDomainRegistry(), new DefaultArchiveDomainCatalog());
  }

  DefaultArchiveService(boolean enabled, ArchiveTxNumIndex txNumIndex,
      ArchiveExecutionContext executionContext, ArchiveTemporalStore temporalStore,
      ArchiveDomainRegistry registry, ArchiveDomainCatalog catalog) {
    this(enabled, txNumIndex, executionContext, temporalStore, new InMemoryArchiveInFlightStore(),
        registry, catalog);
  }

  DefaultArchiveService(boolean enabled, ArchiveTxNumIndex txNumIndex,
      ArchiveExecutionContext executionContext, ArchiveTemporalStore temporalStore,
      ArchiveInFlightStore inFlightStore, ArchiveDomainRegistry registry,
      ArchiveDomainCatalog catalog) {
    this(enabled, txNumIndex, executionContext, temporalStore, inFlightStore,
        registry, catalog, ArchiveReadThrough.NONE);
  }

  DefaultArchiveService(boolean enabled, ArchiveTxNumIndex txNumIndex,
      ArchiveExecutionContext executionContext, ArchiveTemporalStore temporalStore,
      ArchiveInFlightStore inFlightStore, ArchiveDomainRegistry registry,
      ArchiveDomainCatalog catalog, ArchiveReadThrough liveReadThrough) {
    this(enabled, txNumIndex, executionContext, temporalStore, inFlightStore, registry, catalog,
        liveReadThrough, ArchiveLifecycle.Phase.RUNNING);
  }

  DefaultArchiveService(boolean enabled, ArchiveTxNumIndex txNumIndex,
      ArchiveExecutionContext executionContext, ArchiveTemporalStore temporalStore,
      ArchiveInFlightStore inFlightStore, ArchiveDomainRegistry registry,
      ArchiveDomainCatalog catalog, ArchiveReadThrough liveReadThrough,
      ArchiveLifecycle.Phase initialPhase) {
    this(enabled, txNumIndex, executionContext, temporalStore, inFlightStore, registry, catalog,
        liveReadThrough, initialPhase, ArchiveQueryLimits.unlimited());
  }

  DefaultArchiveService(boolean enabled, ArchiveTxNumIndex txNumIndex,
      ArchiveExecutionContext executionContext, ArchiveTemporalStore temporalStore,
      ArchiveInFlightStore inFlightStore, ArchiveDomainRegistry registry,
      ArchiveDomainCatalog catalog, ArchiveReadThrough liveReadThrough,
      ArchiveLifecycle.Phase initialPhase, Runnable startupValidator) {
    this(enabled, txNumIndex, executionContext, temporalStore, inFlightStore, registry, catalog,
        liveReadThrough, initialPhase, ArchiveQueryLimits.unlimited(), false, startupValidator);
  }

  DefaultArchiveService(boolean enabled, ArchiveTxNumIndex txNumIndex,
      ArchiveExecutionContext executionContext, ArchiveTemporalStore temporalStore,
      ArchiveInFlightStore inFlightStore, ArchiveDomainRegistry registry,
      ArchiveDomainCatalog catalog, ArchiveReadThrough liveReadThrough,
      ArchiveLifecycle.Phase initialPhase, ArchiveQueryLimits queryLimits) {
    this(enabled, txNumIndex, executionContext, temporalStore, inFlightStore, registry, catalog,
        liveReadThrough, initialPhase, queryLimits, false);
  }

  DefaultArchiveService(boolean enabled, ArchiveTxNumIndex txNumIndex,
      ArchiveExecutionContext executionContext, ArchiveTemporalStore temporalStore,
      ArchiveInFlightStore inFlightStore, ArchiveDomainRegistry registry,
      ArchiveDomainCatalog catalog, ArchiveReadThrough liveReadThrough,
      ArchiveLifecycle.Phase initialPhase, ArchiveQueryLimits queryLimits,
      boolean asyncPublisher) {
    this(enabled, txNumIndex, executionContext, temporalStore, inFlightStore, registry, catalog,
        liveReadThrough, initialPhase, queryLimits, asyncPublisher, () -> {
        });
  }

  DefaultArchiveService(boolean enabled, ArchiveTxNumIndex txNumIndex,
      ArchiveExecutionContext executionContext, ArchiveTemporalStore temporalStore,
      ArchiveInFlightStore inFlightStore, ArchiveDomainRegistry registry,
      ArchiveDomainCatalog catalog, ArchiveReadThrough liveReadThrough,
      ArchiveLifecycle.Phase initialPhase, ArchiveQueryLimits queryLimits,
      boolean asyncPublisher, Runnable startupValidator) {
    this(enabled, txNumIndex, executionContext, temporalStore, inFlightStore, registry, catalog,
        liveReadThrough, initialPhase, queryLimits,
        new ArchivePublisherConfig(asyncPublisher, asyncPublisher,
            ArchivePublisherConfig.DEFAULT_SOFT_IN_FLIGHT_BLOCKS,
            ArchivePublisherConfig.DEFAULT_HARD_IN_FLIGHT_BLOCKS,
            ArchivePublisherConfig.DEFAULT_BACKPRESSURE_TIMEOUT_MS), startupValidator);
  }

  DefaultArchiveService(boolean enabled, ArchiveTxNumIndex txNumIndex,
      ArchiveExecutionContext executionContext, ArchiveTemporalStore temporalStore,
      ArchiveInFlightStore inFlightStore, ArchiveDomainRegistry registry,
      ArchiveDomainCatalog catalog, ArchiveReadThrough liveReadThrough,
      ArchiveLifecycle.Phase initialPhase, ArchiveQueryLimits queryLimits,
      ArchivePublisherConfig publisherConfig, Runnable startupValidator) {
    this(enabled, txNumIndex, executionContext, temporalStore, inFlightStore, registry, catalog,
        liveReadThrough, initialPhase, queryLimits, publisherConfig, startupValidator, null);
  }

  DefaultArchiveService(boolean enabled, ArchiveTxNumIndex txNumIndex,
      ArchiveExecutionContext executionContext, ArchiveTemporalStore temporalStore,
      ArchiveInFlightStore inFlightStore, ArchiveDomainRegistry registry,
      ArchiveDomainCatalog catalog, ArchiveReadThrough liveReadThrough,
      ArchiveLifecycle.Phase initialPhase, ArchiveQueryLimits queryLimits,
      ArchivePublisherConfig publisherConfig, Runnable startupValidator,
      UnifiedArchiveBackend unifiedBackend) {
    if (startupValidator == null) {
      throw new NullPointerException("startupValidator");
    }
    if (publisherConfig == null) {
      throw new NullPointerException("publisherConfig");
    }
    this.enabled = enabled;
    this.txNumIndex = txNumIndex;
    this.executionContext = executionContext;
    this.lifecycle = new ArchiveLifecycle(initialPhase);
    this.queryCoordinator = new ArchiveQueryCoordinator(queryLimits);
    this.startupValidator = startupValidator;
    this.publisherConfig = publisherConfig;
    this.unifiedBackend = unifiedBackend;
    this.maxInFlightBlocks = publisherConfig.getHardInFlightBlocks();
    if (enabled) {
      this.schemaChecksum = ArchiveSchemaChecksum.of(registry, catalog);
      this.captureEngine = new ArchiveCaptureEngine(registry, catalog, new DynamicKeyPolicy(),
          executionContext, publisherConfig.getHardInFlightRecords(),
          publisherConfig.getHardInFlightBytes());
      this.temporalStore = temporalStore;
      this.inFlightStore = inFlightStore;
      this.executionTxNumIndex = new InMemoryArchiveTxNumIndex(txNumIndex.getNextTxNum());
      ArchiveMetrics.setRepairRequired(txNumIndex.hasRepairRequired());
      loadInFlightBlocks();
      updateInFlightMetrics();
      if (initialPhase == ArchiveLifecycle.Phase.RUNNING) {
        // Direct RUNNING construction is retained for unit/in-memory callers without a startup
        // RecoveryLease. Production factory instances start in RECOVERING and defer this work.
        validateStartupStorageLocked();
      }
      this.readerFactory = new DefaultArchiveStateReaderFactory(temporalStore, catalog,
          txNumIndex, this::validateAvailableForRead, this::readThrough, queryLimits);
      this.fatalController = new ArchiveFatalController("archive-fatal-control");
      this.publisher = publisherConfig.isAsync()
          ? new BoundedArchivePublisher(
              "archive-publisher", this::publishNextTarget, this::markFatal)
          : null;
      executionContext.clear();
      captureEngine.clear();
      ArchiveCaptureHolder.set(captureEngine);
      if (publisher != null && initialPhase == ArchiveLifecycle.Phase.RUNNING) {
        publisher.activate();
      }
    } else {
      this.schemaChecksum = new byte[0];
      this.captureEngine = null;
      this.temporalStore = null;
      this.inFlightStore = null;
      this.executionTxNumIndex = null;
      this.readerFactory = null;
      this.publisher = null;
      this.fatalController = null;
    }
  }

  public ArchiveTxNumIndex getTxNumIndex() {
    return txNumIndex;
  }

  private void loadInFlightBlocks() {
    long[] startupJournalBytes = {0L};
    long[] totalRecords = {0L};
    long[] totalBlocks = {0L};
    long[] loadedBytes = {0L};
    long[] loadedRecords = {0L};
    int[] loadedBlocks = {0};
    long[] staleBytes = {0L};
    long[] staleRecords = {0L};
    int[] staleBlocks = {0};
    inFlightStore.forEachBlock(block -> {
      ArchiveBlockRange range = block.getRange();
      long retainedBytes = block.estimatedRetainedBytes();
      startupJournalBytes[0] = addSaturated(startupJournalBytes[0], retainedBytes);
      totalBlocks[0] = addSaturated(totalBlocks[0], 1L);
      totalRecords[0] = addSaturated(totalRecords[0], block.getRecords().size());
      Optional<ArchiveBlockRange> published = txNumIndex.getBlockRange(range.getBlockNum());
      if (published.isPresent()) {
        staleBlocks[0]++;
        staleBytes[0] = addSaturated(staleBytes[0], retainedBytes);
        staleRecords[0] = addSaturated(staleRecords[0], block.getRecords().size());
        validateStartupJournalBytes(startupJournalBytes[0], staleBytes[0], loadedBytes[0]);
        validateStartupJournalCounts(totalBlocks[0], totalRecords[0],
            staleBlocks[0], staleRecords[0], loadedBlocks[0], loadedRecords[0]);
        validatePublishedRange(range, published.get());
        validateJournalPositionsMatchIndex(block);
        pendingPublishedJournals.put(range.getBlockNum(), block);
        return;
      }
      loadedBlocks[0]++;
      loadedBytes[0] = addSaturated(loadedBytes[0], retainedBytes);
      loadedRecords[0] = addSaturated(loadedRecords[0], block.getRecords().size());
      validateStartupJournalBytes(startupJournalBytes[0], staleBytes[0], loadedBytes[0]);
      validateStartupJournalCounts(totalBlocks[0], totalRecords[0],
          staleBlocks[0], staleRecords[0], loadedBlocks[0], loadedRecords[0]);
      validateInFlightAppend(block);
      validateInFlightPrevValueChain(block);
      replayExecutionInFlightBlock(block);
      rememberInFlightInMemory(block);
    });
  }

  private void validateStartupJournalBytes(long totalBytes, long staleBytes, long loadedBytes) {
    if (totalBytes > publisherConfig.getHardInFlightBytes()) {
      throw new ArchiveException(
          "archive startup journals exceed configured hard byte limit: bytes=" + totalBytes
              + ", staleBytes=" + staleBytes + ", loadedBytes=" + loadedBytes);
    }
  }

  private void validateStartupJournalCounts(long totalBlocks, long totalRecords,
      int staleBlocks, long staleRecords, int loadedBlocks, long loadedRecords) {
    if (totalBlocks > maxInFlightBlocks
        || totalRecords > publisherConfig.getHardInFlightRecords()) {
      throw new ArchiveException(
          "archive startup journals exceed configured hard limit: totalBlocks=" + totalBlocks
              + ", totalRecords=" + totalRecords + ", staleBlocks=" + staleBlocks
              + ", staleRecords=" + staleRecords + ", loadedBlocks=" + loadedBlocks
              + ", loadedRecords=" + loadedRecords);
    }
  }

  private void validateInFlightPrevValueChain(ArchiveInFlightBlock block) {
    Map<WrappedByteArray, DomainValue> stagedLatest = new LinkedHashMap<>();
    for (ArchiveChangeRecord record : block.getRecords()) {
      WrappedByteArray key = latestKey(record.getDomain(), record.getCanonicalKey());
      DomainValue expected = stagedLatest.get(key);
      if (expected == null) {
        expected = latestWithInFlight(record.getDomain(), record.getCanonicalKey()).orElse(null);
      }
      if (expected != null && !sameDomainValue(expected, record.getPrevValue())) {
        throw new ArchiveException("archive in-flight prev-value chain mismatch for txNum "
            + record.getTxNum());
      }
      stagedLatest.put(key, record.getValue());
    }
  }

  private void replayExecutionInFlightBlock(ArchiveInFlightBlock block) {
    ArchiveBlockRange range = block.getRange();
    executionTxNumIndex.beginBlock(range.getBlockNum(), range.getSource());
    try {
      for (ArchiveTxPosition position : block.getPositions()) {
        ArchiveTxPosition allocated = allocateExecutionPosition(position);
        validatePublishedPosition(position, allocated);
      }
      ArchiveBlockRange replayed = executionTxNumIndex.commitBlock(
          range.getBlockNum(), range.getBlockHash(), range.getUserTxCount(), schemaChecksum);
      validatePublishedRange(range, replayed);
    } catch (RuntimeException e) {
      try {
        executionTxNumIndex.abortBlock(range.getBlockNum());
      } catch (RuntimeException cleanupFailure) {
        e.addSuppressed(cleanupFailure);
      }
      throw e;
    }
  }

  private void validateJournalPositionsMatchIndex(ArchiveInFlightBlock block) {
    for (ArchiveTxPosition expected : block.getPositions()) {
      ArchiveTxPosition actual = txNumIndex.getPosition(expected.getTxNum())
          .orElseThrow(() -> new ArchiveException(
              "archive in-flight position is missing from published index txNum "
                  + expected.getTxNum()));
      try {
        validatePublishedPosition(expected, actual);
      } catch (ArchiveException mismatch) {
        throw new ArchiveException(
            "archive in-flight position does not match published index "
                + expected.getTxNum(), mismatch);
      }
    }
  }

  public ArchiveCaptureEngine getCaptureEngine() {
    return captureEngine;
  }

  public ArchiveTemporalStore getTemporalStore() {
    return temporalStore;
  }

  /** Opens historical state readers over the temporal store; null when archive is disabled. */
  ArchiveStateReaderFactory getReaderFactory() {
    return readerFactory;
  }

  /** Internal startup-only reader; callers must already own the recovery/mutation scope. */
  public ArchiveStateReader openRecoveryReader(ArchiveStatePoint point)
      throws ArchiveReaderException {
    if (lifecycle.getPhase() != ArchiveLifecycle.Phase.RECOVERING) {
      throw new ArchiveReaderException(ArchiveReaderException.Reason.INTERNAL_IO,
          "archive recovery reader is available only during startup recovery");
    }
    mutationBarrier.requireHeldByCurrentThread();
    Lock readLock = consistencyLock.readLock();
    readLock.lock();
    try {
      return readerFactory.open(point, readLock::unlock);
    } catch (RuntimeException | Error | ArchiveReaderException e) {
      readLock.unlock();
      throw e;
    }
  }

  @Override
  public boolean isEnabled() {
    return enabled;
  }

  @Override
  public ArchiveWorkLease acquireRecoveryLease() {
    return enabled
        ? lifecycle.acquire(ArchiveLifecycle.WorkType.RECOVERY)
        : ArchiveService.NOOP_WORK_LEASE;
  }

  @Override
  public ArchiveWorkLease acquireWriterLease() {
    return enabled
        ? lifecycle.acquire(ArchiveLifecycle.WorkType.WRITER)
        : ArchiveService.NOOP_WORK_LEASE;
  }

  @Override
  public ArchiveWorkLease acquirePublisherLease() {
    return enabled
        ? lifecycle.acquire(ArchiveLifecycle.WorkType.PUBLISHER)
        : ArchiveService.NOOP_WORK_LEASE;
  }

  @Override
  public ArchiveMutationLease acquireMutationReadLease() {
    return enabled ? mutationBarrier.acquireShared() : ArchiveService.NOOP_MUTATION_LEASE;
  }

  @Override
  public ArchiveMutationLease acquireMutationWriteLease() {
    return enabled ? mutationBarrier.acquireExclusive() : ArchiveService.NOOP_MUTATION_LEASE;
  }

  @Override
  public void awaitWriterCapacity() {
    if (!enabled) {
      return;
    }
    long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(
        publisherConfig.getBackpressureTimeoutMs());
    long deadline = System.nanoTime() + timeoutNanos;
    synchronized (backlogMonitor) {
      while (true) {
        validateAvailable();
        long usableSpace;
        try {
          usableSpace = sampleUsableSpaceBytes();
        } catch (RuntimeException e) {
          markFatal(e);
          throw e;
        }
        boolean hardLimitReached =
            inFlightBlockCount >= publisherConfig.getHardInFlightBlocks()
                || inFlightRecordCount >= publisherConfig.getHardInFlightRecords()
                || inFlightRetainedBytes >= publisherConfig.getHardInFlightBytes()
                || usableSpace < publisherConfig.getHardMinFreeBytes();
        if (hardLimitReached) {
          ArchiveException failure = new ArchiveException(
              "archive in-flight journal reached hard watermark: blocks="
                  + inFlightBlockCount + ", records=" + inFlightRecordCount
                  + ", bytes=" + inFlightRetainedBytes + ", diskFree=" + usableSpace);
          markFatal(failure);
          throw failure;
        }
        boolean softLimitReached =
            inFlightBlockCount >= publisherConfig.getSoftInFlightBlocks()
                || inFlightRecordCount >= publisherConfig.getSoftInFlightRecords()
                || inFlightRetainedBytes >= publisherConfig.getSoftInFlightBytes()
                || usableSpace < publisherConfig.getSoftMinFreeBytes();
        if (!softLimitReached || publisher == null || !publisherConfig.isBackpressure()) {
          return;
        }
        long remaining = deadline - System.nanoTime();
        if (timeoutNanos == 0 || remaining <= 0) {
          ArchiveException failure = new ArchiveException(
              "archive publisher backpressure timed out: blocks=" + inFlightBlockCount
                  + ", records=" + inFlightRecordCount + ", bytes=" + inFlightRetainedBytes
                  + ", diskFree=" + usableSpace);
          markFatal(failure);
          throw failure;
        }
        try {
          TimeUnit.NANOSECONDS.timedWait(backlogMonitor, remaining);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new ArchiveException("archive publisher backpressure interrupted", e);
        }
      }
    }
  }

  @Override
  public void completeRecovery() {
    if (enabled) {
      Lock writeLock = consistencyLock.writeLock();
      writeLock.lock();
      try {
        validateAvailable();
        validateStartupStorageLocked();
        txNumIndex.clearRepairRequired();
        ArchiveMetrics.setRepairRequired(false);
        lifecycle.completeRecovery(() -> {
          if (publisher != null) {
            publisher.activate();
          }
        });
      } catch (RuntimeException e) {
        markFatal(e);
        throw e;
      } finally {
        writeLock.unlock();
      }
    }
  }

  @Override
  public void setFatalFailureHandler(Consumer<RuntimeException> fatalHandler) {
    if (enabled) {
      fatalController.setHandler(fatalHandler);
    }
  }

  @Override
  public void beginBlock(BlockCapsule block, ArchiveSource source) {
    if (!enabled) {
      return;
    }
    validateAvailable();
    executionTxNumIndex.beginBlock(block.getNum(), source);
    captureEngine.beginBlockCapture();
  }

  @Override
  public void beginSystemTx(BlockCapsule block, ArchivePhase phase) {
    if (!enabled) {
      return;
    }
    validateAvailable();
    executionContext.enter(executionTxNumIndex.allocateSystemTx(block.getNum(), phase));
  }

  @Override
  public void beginUserTx(BlockCapsule block, int txIndex, TransactionCapsule tx) {
    if (!enabled) {
      return;
    }
    validateAvailable();
    byte[] txId = (tx == null) ? null : tx.getTransactionId().getBytes();
    executionContext.enter(executionTxNumIndex.allocateUserTx(block.getNum(), txIndex, txId));
  }

  @Override
  public void endTx() {
    if (!enabled) {
      return;
    }
    clearExecutionContextIfCurrent();
  }

  @Override
  public void commitBlock(BlockCapsule block) {
    commitBlock(block, block.getTransactions().size());
  }

  @Override
  public void commitBlock(BlockCapsule block, int userTxCount) {
    ArchiveJournalToken token = commitBlockJournaled(block, userTxCount);
    acknowledgeCanonicalCommit(token);
  }

  @Override
  public ArchiveJournalToken commitBlockJournaled(BlockCapsule block, int userTxCount) {
    if (!enabled) {
      return null;
    }
    Lock writeLock = consistencyLock.writeLock();
    writeLock.lock();
    try {
      return commitBlockLocked(block, userTxCount);
    } finally {
      writeLock.unlock();
    }
  }

  @Override
  public void acknowledgeCanonicalCommit(ArchiveJournalToken token) {
    if (!enabled || token == null) {
      return;
    }
    Lock writeLock = consistencyLock.writeLock();
    writeLock.lock();
    try {
      validateAvailable();
      acknowledgeCanonicalCommitLocked(token);
    } catch (RuntimeException e) {
      markFatal(e);
      throw e;
    } finally {
      writeLock.unlock();
    }
  }

  @Override
  public void rollbackJournaledBlock(ArchiveJournalToken token) {
    if (!enabled || token == null) {
      return;
    }
    Lock writeLock = consistencyLock.writeLock();
    writeLock.lock();
    try {
      validateAvailable();
      rollbackJournaledBlockLocked(token, false);
    } catch (RuntimeException e) {
      markFatal(e);
      throw e;
    } finally {
      writeLock.unlock();
    }
  }

  @Override
  public void publishSolidifiedBlocks(long solidifiedBlockNum) {
    if (!enabled) {
      return;
    }
    Lock writeLock = consistencyLock.writeLock();
    writeLock.lock();
    try {
      try {
        validateAvailable();
        publishSolidifiedBlocksLocked(solidifiedBlockNum);
      } catch (RuntimeException e) {
        markFatal(e);
        throw e;
      }
    } finally {
      writeLock.unlock();
    }
  }

  @Override
  public void recoverSynchronouslyTo(long solidifiedBlockNum) {
    publishSolidifiedBlocks(solidifiedBlockNum);
  }

  @Override
  public void requestPublishSolidifiedBlocks(long solidifiedBlockNum,
      byte[] solidifiedBlockHash) {
    if (!enabled) {
      return;
    }
    if (publisher == null) {
      publishSolidifiedBlocks(solidifiedBlockNum);
      return;
    }
    mutationBarrier.requireHeldByCurrentThread();
    ArchivePublishTarget target = new ArchivePublishTarget(
        solidifiedBlockNum, solidifiedBlockHash, mutationBarrier.getEpoch());
    Lock writeLock = consistencyLock.writeLock();
    writeLock.lock();
    try {
      validateAvailable();
      // The canonical block thread owns execution-allocation pruning. The background publisher
      // never touches this allocator, so it cannot race beginBlock(N+1).
      executionTxNumIndex.discardBlocksThrough(solidifiedBlockNum);
      updatePublisherLag(solidifiedBlockNum);
    } catch (RuntimeException e) {
      markFatal(e);
      throw e;
    } finally {
      writeLock.unlock();
    }
    try {
      publisher.request(target);
    } catch (RuntimeException e) {
      markFatal(e);
      throw e;
    }
  }

  @Override
  public boolean requiresPublishTargetHash() {
    return publisher != null;
  }

  @Override
  public void reconcileInFlightOnStartup(long solidifiedBlockNum,
      LongFunction<BlockCapsule> canonicalBlockProvider) {
    reconcileInFlightOnStartup(
        solidifiedBlockNum, -1L, canonicalBlockProvider, false);
  }

  @Override
  public void reconcileInFlightOnStartup(long solidifiedBlockNum, long canonicalHeadNum,
      LongFunction<BlockCapsule> canonicalBlockProvider) {
    reconcileInFlightOnStartup(
        solidifiedBlockNum, canonicalHeadNum, canonicalBlockProvider, true);
  }

  private void reconcileInFlightOnStartup(long solidifiedBlockNum, long canonicalHeadNum,
      LongFunction<BlockCapsule> canonicalBlockProvider, boolean canonicalHeadKnown) {
    if (!enabled) {
      return;
    }
    Lock writeLock = consistencyLock.writeLock();
    writeLock.lock();
    try {
      try {
        validateAvailable();
        if (canonicalBlockProvider == null) {
          throw new ArchiveException("archive startup reconciliation requires canonical blocks");
        }
        if (pendingPublishedJournals.isEmpty()) {
          validateStartupStorageLocked();
        } else {
          // A published range can legitimately be ahead of its temporal commit marker after a
          // crash. Check capacity now, then run the full tail/scrub validator after the durable
          // journal has been matched to canonical chain and replayed idempotently.
          validateStartupDiskSpaceLocked();
        }
        List<ArchiveInFlightBlock> blocks = new ArrayList<>(inFlightBlocks.values());
        if (canonicalHeadKnown && canonicalHeadNum < 0) {
          validateEmptyCanonicalJournal(blocks, txNumIndex.getLastArchivedBlock());
        }
        validatePendingPublishedJournals(
            canonicalHeadNum, canonicalBlockProvider, canonicalHeadKnown);
        BlockCapsule previousCanonical = null;
        int retained = 0;
        if (!canonicalHeadKnown || canonicalHeadNum >= 0) {
          for (ArchiveInFlightBlock block : blocks) {
            if (canonicalHeadKnown && block.getRange().getBlockNum() > canonicalHeadNum) {
              break;
            }
            BlockCapsule canonical = canonicalBlockProvider.apply(block.getRange().getBlockNum());
            if (canonical == null) {
              throw new ArchiveException("archive in-flight block "
                  + block.getRange().getBlockNum() + " has no canonical block");
            }
            if (canonical.getNum() != block.getRange().getBlockNum()
                || !Arrays.equals(canonical.getBlockId().getBytes(),
                    block.getRange().getBlockHash())) {
              break;
            }
            validateCanonicalParentLink(previousCanonical, canonical);
            previousCanonical = canonical;
            retained++;
          }
        }
        reconcilePendingPublishedJournals();
        validateStartupStorageLocked();
        for (int i = blocks.size() - 1; i >= retained; i--) {
          rollbackJournaledBlockLocked(blocks.get(i).getJournalToken(), true);
        }
        for (ArchiveInFlightBlock block : new ArrayList<>(inFlightBlocks.values())) {
          acknowledgeCanonicalCommitLocked(block.getJournalToken());
        }
        if (canonicalHeadKnown) {
          validateCanonicalTailCovered(canonicalHeadNum, canonicalBlockProvider);
        }
        publishSolidifiedBlocksLocked(solidifiedBlockNum);
      } catch (RuntimeException e) {
        if (!(e instanceof RetryableStartupCleanupException)) {
          markFatal(e);
        }
        throw e;
      }
    } finally {
      writeLock.unlock();
    }
  }

  private static void validateEmptyCanonicalJournal(
      List<ArchiveInFlightBlock> blocks, long publishedHead) {
    if (publishedHead >= 0) {
      throw new ArchiveException("published archive head " + publishedHead
          + " exists while canonical database is empty");
    }
    if (blocks.isEmpty()) {
      return;
    }
    if (blocks.size() != 1) {
      throw new ArchiveException("empty canonical database has " + blocks.size()
          + " in-flight journal blocks; refusing destructive recovery");
    }
    ArchiveInFlightBlock block = blocks.get(0);
    if (block.getRange().getBlockNum() != 0
        || block.getJournalState() != ArchiveInFlightBlock.JournalState.JOURNALED) {
      throw new ArchiveException("empty canonical database may only discard one unacknowledged "
          + "genesis journal");
    }
  }

  private void validatePendingPublishedJournals(long canonicalHeadNum,
      LongFunction<BlockCapsule> canonicalBlockProvider, boolean canonicalHeadKnown) {
    for (ArchiveInFlightBlock block : pendingPublishedJournals.values()) {
      long blockNum = block.getRange().getBlockNum();
      if (canonicalHeadKnown && blockNum > canonicalHeadNum) {
        throw new ArchiveException("published archive journal block " + blockNum
            + " is after canonical head " + canonicalHeadNum);
      }
      validateInFlightMatchesCanonical(block, canonicalBlockProvider);
    }
  }

  private void reconcilePendingPublishedJournals() {
    for (ArchiveInFlightBlock block : new ArrayList<>(pendingPublishedJournals.values())) {
      ArchiveBlockRange published = txNumIndex.getBlockRange(
          block.getRange().getBlockNum()).orElseThrow(
              () -> new ArchiveException("published archive journal lost its index range for block "
                  + block.getRange().getBlockNum()));
      temporalStore.reconcilePublishedBlock(published, block.getRecords());
      try {
        deletePublishedInFlightBlock(block.getRange().getBlockNum(), true);
      } catch (RuntimeException e) {
        throw new RetryableStartupCleanupException(
            "failed to delete reconciled published journal for block "
                + block.getRange().getBlockNum(), e);
      }
      pendingPublishedJournals.remove(block.getRange().getBlockNum());
    }
  }

  private void validateStartupStorageLocked() {
    if (startupStorageValidated) {
      return;
    }
    validateStartupDiskSpaceLocked();
    startupValidator.run();
    startupStorageValidated = true;
  }

  private void validateStartupDiskSpaceLocked() {
    long usableSpace = sampleUsableSpaceBytes();
    if (usableSpace < publisherConfig.getHardMinFreeBytes()) {
      throw new ArchiveException("archive journal filesystem is below hard free-space watermark: "
          + usableSpace + " < " + publisherConfig.getHardMinFreeBytes());
    }
  }

  @Override
  public void reconcilePublishedHeadOnStartup(long canonicalHeadNum) {
    if (!enabled) {
      return;
    }
    Lock writeLock = consistencyLock.writeLock();
    writeLock.lock();
    try {
      try {
        validateAvailable();
        unwindPublishedBlocksAfterCanonicalHead(canonicalHeadNum);
      } catch (RuntimeException e) {
        markFatal(e);
        throw e;
      }
    } finally {
      writeLock.unlock();
    }
  }

  private void unwindPublishedBlocksAfterCanonicalHead(long canonicalHeadNum) {
    long archiveHead = txNumIndex.getLastArchivedBlock();
    if (canonicalHeadNum < 0) {
      if (archiveHead >= 0) {
        throw new ArchiveException("published archive head " + archiveHead
            + " exists while canonical database is empty");
      }
      return;
    }
    if (archiveHead > canonicalHeadNum) {
      throw new ArchiveException("published archive head " + archiveHead
          + " is after canonical head " + canonicalHeadNum);
    }
  }

  private void validateCanonicalTailCovered(long canonicalHeadNum,
      LongFunction<BlockCapsule> canonicalBlockProvider) {
    if (canonicalHeadNum < 0) {
      return;
    }
    long archiveTail = inFlightBlocks.isEmpty()
        ? txNumIndex.getLastArchivedBlock()
        : inFlightBlocks.lastKey();
    if (archiveTail < 0) {
      throw new ArchiveException("archive has no journal or published range while canonical head is "
          + canonicalHeadNum);
    }
    if (archiveTail >= canonicalHeadNum) {
      return;
    }
    long missingBlock = archiveTail + 1;
    BlockCapsule canonical = canonicalBlockProvider.apply(missingBlock);
    if (canonical == null || canonical.getNum() != missingBlock) {
      throw new ArchiveException("archive startup cannot load canonical block " + missingBlock
          + " after archive tail " + archiveTail);
    }
    throw new ArchiveException("archive in-flight journal missing for canonical block "
        + missingBlock + " after archive tail " + archiveTail);
  }

  private void publishSolidifiedBlocksLocked(long solidifiedBlockNum) {
    while (!inFlightBlocks.isEmpty()
        && inFlightBlocks.firstKey() <= solidifiedBlockNum) {
      ArchiveInFlightBlock block = inFlightBlocks.firstEntry().getValue();
      validateInFlightVersionHead(block);
      publishInFlightBlock(block);
      inFlightBlocks.remove(block.getRange().getBlockNum());
      removeInFlightVersionHead(block);
      removeInFlightUsage(block);
    }
    if (solidifiedBlockNum >= 0) {
      executionTxNumIndex.discardBlocksThrough(solidifiedBlockNum);
    }
    updatePublisherLag(solidifiedBlockNum);
  }

  /** Worker callback: publish at most one block, releasing every lock between backlog blocks. */
  private boolean publishNextTarget(ArchivePublishTarget target) {
    ArchiveWorkLease reservedPublisherLease;
    try {
      reservedPublisherLease = acquirePublisherLease();
    } catch (ArchiveException e) {
      ArchiveLifecycle.Phase phase = lifecycle.getPhase();
      if (lifecycle.getFatalFailure() == null
          && (phase == ArchiveLifecycle.Phase.DRAINING
              || phase == ArchiveLifecycle.Phase.CLOSED)) {
        // Drain closed admission after the worker took a target but before it reserved a lease.
        return true;
      }
      throw e;
    }
    try (ArchiveWorkLease publisherLease = reservedPublisherLease;
         ArchiveMutationLease mutationLease = acquireMutationReadLease()) {
      if (target.getCanonicalEpoch() != mutationLease.getEpoch()) {
        return true;
      }
      try {
        publisherLease.start();
      } catch (ArchiveException e) {
        ArchiveLifecycle.Phase phase = lifecycle.getPhase();
        if (lifecycle.getFatalFailure() == null
            && (phase == ArchiveLifecycle.Phase.DRAINING
                || phase == ArchiveLifecycle.Phase.CLOSED)) {
          // Drain won before this reserved publisher touched any archive state.
          return true;
        }
        throw e;
      }
      Lock writeLock = consistencyLock.writeLock();
      writeLock.lock();
      try {
        validateAvailable();
        validatePublishTargetLocked(target);
        if (inFlightBlocks.isEmpty()
            || inFlightBlocks.firstKey() > target.getBlockNum()) {
          return true;
        }
        ArchiveInFlightBlock block = inFlightBlocks.firstEntry().getValue();
        validateInFlightVersionHead(block);
        publishInFlightBlock(block);
        inFlightBlocks.remove(block.getRange().getBlockNum());
        removeInFlightVersionHead(block);
        removeInFlightUsage(block);
        updatePublisherLag(target.getBlockNum());
        return inFlightBlocks.isEmpty()
            || inFlightBlocks.firstKey() > target.getBlockNum();
      } finally {
        writeLock.unlock();
      }
    }
  }

  private void validatePublishTargetLocked(ArchivePublishTarget target) {
    ArchiveBlockRange targetRange = txNumIndex.getBlockRange(target.getBlockNum()).orElse(null);
    if (targetRange == null) {
      ArchiveInFlightBlock targetBlock = inFlightBlocks.get(target.getBlockNum());
      targetRange = targetBlock == null ? null : targetBlock.getRange();
    }
    if (targetRange != null) {
      if (!Arrays.equals(targetRange.getBlockHash(), target.getBlockHash())) {
        throw new ArchiveException("archive publish target hash mismatch for block "
            + target.getBlockNum());
      }
      return;
    }
    long firstArchived = txNumIndex.getFirstArchivedBlock();
    if (firstArchived < 0 && !inFlightBlocks.isEmpty()) {
      firstArchived = inFlightBlocks.firstKey();
    }
    long archiveTail = inFlightBlocks.isEmpty()
        ? txNumIndex.getLastArchivedBlock()
        : inFlightBlocks.lastKey();
    if (firstArchived >= 0 && target.getBlockNum() >= firstArchived
        && target.getBlockNum() <= archiveTail) {
      throw new ArchiveException("archive publish target has no indexed or journaled block "
          + target.getBlockNum());
    }
  }

  private static BlockCapsule validateInFlightMatchesCanonical(ArchiveInFlightBlock block,
      LongFunction<BlockCapsule> canonicalBlockProvider) {
    ArchiveBlockRange range = block.getRange();
    BlockCapsule canonical = canonicalBlockProvider.apply(range.getBlockNum());
    if (canonical == null) {
      throw new ArchiveException("archive in-flight block " + range.getBlockNum()
          + " has no canonical block");
    }
    if (canonical.getNum() != range.getBlockNum()) {
      throw new ArchiveException("archive in-flight block " + range.getBlockNum()
          + " resolved to canonical block " + canonical.getNum());
    }
    if (!Arrays.equals(canonical.getBlockId().getBytes(), range.getBlockHash())) {
      throw new ArchiveException("archive in-flight block " + range.getBlockNum()
          + " hash mismatch with canonical block");
    }
    return canonical;
  }

  private static void validateCanonicalParentLink(BlockCapsule previous, BlockCapsule current) {
    if (previous == null) {
      return;
    }
    if (!Arrays.equals(current.getParentHash().getBytes(), previous.getBlockId().getBytes())) {
      throw new ArchiveException("archive in-flight block " + current.getNum()
          + " parent hash mismatch with previous canonical block");
    }
  }

  private void publishInFlightBlock(ArchiveInFlightBlock block) {
    ArchiveBlockRange range = block.getRange();
    if (block.getJournalState() != ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED) {
      throw new ArchiveException("cannot publish unacknowledged archive journal block "
          + range.getBlockNum());
    }
    if (unifiedBackend != null) {
      long startedNanos = ArchiveMetrics.startTimer();
      try {
        ArchiveBlockRange publishedRange = unifiedBackend.publishBlock(block);
        validatePublishedRange(range, publishedRange);
        ArchiveMetrics.publishFinished(startedNanos, true);
        return;
      } catch (RuntimeException e) {
        ArchiveMetrics.publishFinished(startedNanos, false);
        throw e;
      }
    }
    boolean indexPublished = false;
    boolean temporalPublished = false;
    long startedNanos = ArchiveMetrics.startTimer();
    try {
      txNumIndex.beginBlock(range.getBlockNum(), range.getSource());
      for (ArchiveTxPosition position : block.getPositions()) {
        ArchiveTxPosition allocated = allocatePublishedPosition(position);
        validatePublishedPosition(position, allocated);
      }
      ArchiveBlockRange publishedRange = txNumIndex.commitBlock(
          range.getBlockNum(), range.getBlockHash(), range.getUserTxCount(), schemaChecksum);
      indexPublished = true;
      validatePublishedRange(range, publishedRange);
      temporalStore.putBlockChanges(publishedRange, block.getRecords());
      temporalPublished = true;
      deletePublishedInFlightBlock(range.getBlockNum(), false);
      ArchiveMetrics.publishFinished(startedNanos, true);
    } catch (RuntimeException e) {
      ArchiveMetrics.publishFinished(startedNanos, false);
      if (!temporalPublished) {
        try {
          if (indexPublished) {
            txNumIndex.unwindBlock(range.getBlockNum());
          } else {
            txNumIndex.abortBlock(range.getBlockNum());
          }
        } catch (RuntimeException cleanupFailure) {
          e.addSuppressed(cleanupFailure);
        }
      }
      throw e;
    }
  }

  private void deletePublishedInFlightBlock(long blockNum, boolean required) {
    try {
      inFlightStore.deleteBlock(blockNum);
    } catch (RuntimeException deleteFailure) {
      // The reader-visible index and temporal rows are already durable. Leave the journal row for
      // startup cleanup instead of marking the archive repair-required.
      ArchiveMetrics.staleJournalDeleteFailed();
      logger.warn("Could not delete published archive journal for block {}; startup will retry",
          blockNum, deleteFailure);
      if (required) {
        throw deleteFailure;
      }
    }
  }

  private ArchiveTxPosition allocatePublishedPosition(ArchiveTxPosition position) {
    if (position.getPhase() == ArchivePhase.USER_TX) {
      return txNumIndex.allocateUserTx(
          position.getBlockNum(), position.getTxIndex(), position.getTxId());
    }
    return txNumIndex.allocateSystemTx(position.getBlockNum(), position.getPhase());
  }

  private ArchiveTxPosition allocateExecutionPosition(ArchiveTxPosition position) {
    if (position.getPhase() == ArchivePhase.USER_TX) {
      return executionTxNumIndex.allocateUserTx(
          position.getBlockNum(), position.getTxIndex(), position.getTxId());
    }
    return executionTxNumIndex.allocateSystemTx(position.getBlockNum(), position.getPhase());
  }

  private static void validatePublishedPosition(ArchiveTxPosition expected,
      ArchiveTxPosition actual) {
    if (expected.getTxNum() != actual.getTxNum()
        || expected.getBlockNum() != actual.getBlockNum()
        || expected.getPhase() != actual.getPhase()
        || expected.getSource() != actual.getSource()
        || expected.getTxIndex() != actual.getTxIndex()
        || !Arrays.equals(expected.getTxId(), actual.getTxId())) {
      throw new ArchiveException("published archive tx-position does not match in-flight block "
          + expected.getBlockNum());
    }
  }

  private static void validatePublishedRange(ArchiveBlockRange expected,
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
      throw new ArchiveException("published archive block range does not match in-flight block "
          + expected.getBlockNum());
    }
  }

  private ArchiveJournalToken commitBlockLocked(BlockCapsule block, int userTxCount) {
    boolean executionCommitted = false;
    try {
      validateAvailable();
      if (captureEngine.failure().isPresent()) {
        throw captureEngine.failure().get();
      }
      // Keep semantic no-ops in the durable journal. In particular, a tombstone->tombstone record
      // can be the first proof that a key was absent after a mid-chain coverage floor. Filtering
      // here would also put one random temporal read per unique key on the canonical commit path.
      List<ArchiveChangeRecord> records = captureEngine.records();
      ArchiveMetrics.captureBlock(captureEngine.rawRecordCount(), records,
          captureEngine.previousValueReads(), captureEngine.previousValueReadFailures(),
          captureEngine.previousValueReadNanos(), captureEngine.accountAssetPrefixRows(),
          captureEngine.accountAssetPointReads(), captureEngine.accountAssetLookups(),
          captureEngine.accountAssetLookupNanos());
      ArchiveBlockRange range = executionTxNumIndex.commitBlock(
          block.getNum(), block.getBlockId().getBytes(), userTxCount, schemaChecksum);
      executionCommitted = true;
      List<ArchiveTxPosition> positions = positionsOf(range, executionTxNumIndex);
      ArchiveInFlightBlock journal = new ArchiveInFlightBlock(range, positions, records);
      rememberInFlight(journal);
      return journal.getJournalToken();
    } catch (RuntimeException e) {
      try {
        if (executionCommitted) {
          executionTxNumIndex.unwindBlock(block.getNum());
        } else {
          executionTxNumIndex.abortBlock(block.getNum());
        }
      } catch (RuntimeException cleanupFailure) {
        e.addSuppressed(cleanupFailure);
      }
      markFatal(e);
      throw e;
    } finally {
      clearExecutionContextIfCurrent();
      captureEngine.clear();
    }
  }

  private void rollbackJournaledBlockLocked(ArchiveJournalToken token,
      boolean allowCanonicalCommitted) {
    ArchiveInFlightBlock block = inFlightBlocks.get(token.getBlockNum());
    if (block == null) {
      if (txNumIndex.getBlockRange(token.getBlockNum()).isPresent()) {
        throw new ArchiveException("cannot rollback published archive journal block "
            + token.getBlockNum());
      }
      return;
    }
    if (!token.equals(block.getJournalToken())) {
      return;
    }
    if (!allowCanonicalCommitted
        && block.getJournalState() == ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED) {
      throw new ArchiveException("cannot rollback canonical-committed archive journal block "
          + token.getBlockNum());
    }
    if (inFlightBlocks.lastKey() != token.getBlockNum()) {
      throw new ArchiveException("cannot rollback archive journal block " + token.getBlockNum()
          + ": not in-flight head");
    }
    executionTxNumIndex.getHeadBlockRange(token.getBlockNum());
    validateInFlightVersionTail(block);
    inFlightStore.deleteBlock(token.getBlockNum());
    executionTxNumIndex.unwindBlock(token.getBlockNum());
    inFlightBlocks.remove(token.getBlockNum());
    removeInFlightVersionTail(block);
    removeInFlightUsage(block);
  }

  private void acknowledgeCanonicalCommitLocked(ArchiveJournalToken token) {
    ArchiveInFlightBlock block = inFlightBlocks.get(token.getBlockNum());
    if (block == null) {
      Optional<ArchiveBlockRange> published = txNumIndex.getBlockRange(token.getBlockNum());
      if (published.isPresent() && token.matches(published.get())) {
        return;
      }
      throw new ArchiveException("archive journal token has no in-flight block "
          + token.getBlockNum());
    }
    if (!token.equals(block.getJournalToken())) {
      return;
    }
    if (block.getJournalState() == ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED) {
      return;
    }
    ArchiveInFlightBlock acknowledged = block.withJournalState(
        ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED);
    inFlightStore.acknowledgeBlock(token);
    inFlightBlocks.put(token.getBlockNum(), acknowledged);
  }

  private Optional<DomainValue> latestInFlight(WrappedByteArray key) {
    Deque<InFlightVersion> versions = inFlightVersions.get(key);
    return versions == null || versions.isEmpty()
        ? Optional.empty() : Optional.of(versions.peekLast().value);
  }

  private Optional<DomainValue> latestWithInFlight(ArchiveDomain domain, byte[] canonicalKey) {
    Deque<InFlightVersion> versions = inFlightVersions.get(latestKey(domain, canonicalKey));
    return versions == null || versions.isEmpty()
        ? temporalStore.latest(domain, canonicalKey)
        : Optional.of(versions.peekLast().value);
  }

  private void rememberInFlight(ArchiveInFlightBlock block) {
    validateInFlightAppend(block);
    inFlightStore.putBlock(block);
    rememberInFlightInMemory(block);
  }

  private void rememberInFlightInMemory(ArchiveInFlightBlock block) {
    inFlightBlocks.put(block.getRange().getBlockNum(), block);
    appendInFlightVersions(block);
    addInFlightUsage(block);
  }

  // Test-only: shrink the in-flight cap so the fail-stop can be exercised without 65k real blocks.
  void setMaxInFlightBlocksForTest(int max) {
    this.maxInFlightBlocks = max;
  }

  void setCloseDrainTimeoutForTest(long timeout, TimeUnit unit) {
    if (timeout < 0L) {
      throw new IllegalArgumentException("timeout must be non-negative");
    }
    if (unit == null) {
      throw new NullPointerException("unit");
    }
    closeDrainTimeoutNanos = unit.toNanos(timeout);
  }

  private void addInFlightUsage(ArchiveInFlightBlock block) {
    synchronized (backlogMonitor) {
      inFlightBlockCount = inFlightBlocks.size();
      inFlightRecordCount = addSaturated(inFlightRecordCount, block.getRecords().size());
      inFlightRetainedBytes = addSaturated(
          inFlightRetainedBytes, block.estimatedRetainedBytes());
      backlogMonitor.notifyAll();
    }
    updateInFlightMetrics();
  }

  private void removeInFlightUsage(ArchiveInFlightBlock block) {
    synchronized (backlogMonitor) {
      inFlightBlockCount = inFlightBlocks.size();
      inFlightRecordCount = subtractChecked(
          inFlightRecordCount, block.getRecords().size(), "records");
      inFlightRetainedBytes = subtractChecked(
          inFlightRetainedBytes, block.estimatedRetainedBytes(), "bytes");
      backlogMonitor.notifyAll();
    }
    updateInFlightMetrics();
  }

  private void updateInFlightMetrics() {
    ArchiveMetrics.setInFlight(
        inFlightBlockCount, inFlightRecordCount, inFlightRetainedBytes);
    ArchiveMetrics.setOldestInFlightBlock(
        inFlightBlocks.isEmpty() ? -1L : inFlightBlocks.firstKey());
  }

  private void updatePublisherLag(long targetBlockNum) {
    long publishedThrough = txNumIndex.getLastArchivedBlock();
    if (publishedThrough < 0 && !inFlightBlocks.isEmpty()) {
      publishedThrough = inFlightBlocks.firstKey() - 1L;
    }
    long lag = publishedThrough < 0 ? 0L : Math.max(0L, targetBlockNum - publishedThrough);
    ArchiveMetrics.setPublisherLag(lag);
  }

  private void validateInFlightAppend(ArchiveInFlightBlock block) {
    long blockNum = block.getRange().getBlockNum();
    ArchiveCoordinates.requireBlockNum(blockNum, "archive in-flight block number");
    if (inFlightBlocks.size() >= maxInFlightBlocks) {
      throw new ArchiveException("archive in-flight buffer reached its cap of " + maxInFlightBlocks
          + " committed-not-solidified blocks; refusing to append block " + blockNum);
    }
    long projectedBytes = addSaturated(
        inFlightRetainedBytes, block.estimatedRetainedBytes());
    if (projectedBytes > publisherConfig.getHardInFlightBytes()) {
      throw new ArchiveException("archive in-flight buffer would exceed hard byte watermark "
          + publisherConfig.getHardInFlightBytes() + " while appending block " + blockNum
          + ": projectedBytes=" + projectedBytes);
    }
    long projectedRecords = addSaturated(inFlightRecordCount, block.getRecords().size());
    if (projectedRecords > publisherConfig.getHardInFlightRecords()) {
      throw new ArchiveException("archive in-flight buffer would exceed hard record watermark "
          + publisherConfig.getHardInFlightRecords() + " while appending block " + blockNum
          + ": projectedRecords=" + projectedRecords);
    }
    long journalHeadroom = Math.max(
        MIN_JOURNAL_DISK_HEADROOM_BYTES, multiplySaturated(block.estimatedRetainedBytes(), 2L));
    long requiredFree = addSaturated(publisherConfig.getHardMinFreeBytes(), journalHeadroom);
    long usableSpace = sampleUsableSpaceForJournal(requiredFree, journalHeadroom);
    if (usableSpace < requiredFree) {
      throw new ArchiveException("archive journal filesystem cannot reserve the next durable write"
          + " while appending block " + blockNum + ": requiredFree=" + requiredFree
          + ", diskFree=" + usableSpace);
    }
    if (inFlightBlocks.containsKey(blockNum)) {
      throw new ArchiveException("archive in-flight block already exists for block "
          + blockNum);
    }
    long previousBlock = inFlightBlocks.isEmpty()
        ? txNumIndex.getLastArchivedBlock()
        : inFlightBlocks.lastKey();
    if (previousBlock >= 0 && blockNum != previousBlock + 1) {
      throw new ArchiveException("non-contiguous archive in-flight block: expected block "
          + (previousBlock + 1) + " but got " + blockNum);
    }
    long expectedFirstTxNum = inFlightBlocks.isEmpty()
        ? txNumIndex.getNextTxNum()
        : inFlightBlocks.lastEntry().getValue().getRange().getLastTxNum() + 1;
    if (block.getRange().getFirstTxNum() != expectedFirstTxNum) {
      throw new ArchiveException("non-contiguous archive in-flight txNum: expected "
          + expectedFirstTxNum + " but got " + block.getRange().getFirstTxNum());
    }
  }

  private long sampleUsableSpaceBytes() {
    return sampleUsableSpaceBytes(false);
  }

  private long sampleUsableSpaceForJournal(long requiredFree, long journalHeadroom) {
    long usableSpace = sampleUsableSpaceBytes();
    long refreshThreshold = addSaturated(requiredFree, journalHeadroom);
    return usableSpace <= refreshThreshold ? sampleUsableSpaceBytes(true) : usableSpace;
  }

  private long sampleUsableSpaceBytes(boolean force) {
    long now = System.nanoTime();
    long sampledAt = lastDiskSampleNanos;
    if (!force && sampledAt != Long.MIN_VALUE
        && now - sampledAt < DISK_SAMPLE_INTERVAL_NANOS) {
      return lastUsableSpaceBytes;
    }
    synchronized (diskSampleMonitor) {
      sampledAt = lastDiskSampleNanos;
      if (force || sampledAt == Long.MIN_VALUE
          || now - sampledAt >= DISK_SAMPLE_INTERVAL_NANOS) {
        long usableSpace = inFlightStore.usableSpaceBytes();
        lastUsableSpaceBytes = usableSpace;
        lastDiskSampleNanos = now;
        ArchiveMetrics.setDiskFree(usableSpace);
      }
      return lastUsableSpaceBytes;
    }
  }

  private static long multiplySaturated(long left, long right) {
    return left != 0L && right > Long.MAX_VALUE / left ? Long.MAX_VALUE : left * right;
  }

  private void appendInFlightVersions(ArchiveInFlightBlock block) {
    long blockNum = block.getRange().getBlockNum();
    for (ArchiveChangeRecord record : block.getRecords()) {
      WrappedByteArray key = latestKey(record.getDomain(), record.getCanonicalKey());
      inFlightVersions.computeIfAbsent(key, ignored -> new ArrayDeque<>())
          .addLast(new InFlightVersion(
              blockNum, record.getTxNum(), record.getPrevValue(), record.getValue()));
    }
  }

  private void validateInFlightVersionHead(ArchiveInFlightBlock block) {
    Map<WrappedByteArray, Iterator<InFlightVersion>> iterators = new HashMap<>();
    for (ArchiveChangeRecord record : block.getRecords()) {
      WrappedByteArray key = latestKey(record.getDomain(), record.getCanonicalKey());
      Deque<InFlightVersion> versions = inFlightVersions.get(key);
      Iterator<InFlightVersion> iterator = iterators.computeIfAbsent(key,
          ignored -> versions == null ? null : versions.iterator());
      if (iterator == null || !iterator.hasNext()
          || !iterator.next().matches(block.getRange().getBlockNum(), record)) {
        throw new ArchiveException("archive in-flight version head mismatch for block "
            + block.getRange().getBlockNum() + " txNum " + record.getTxNum());
      }
    }
  }

  private void removeInFlightVersionHead(ArchiveInFlightBlock block) {
    for (ArchiveChangeRecord record : block.getRecords()) {
      WrappedByteArray key = latestKey(record.getDomain(), record.getCanonicalKey());
      Deque<InFlightVersion> versions = inFlightVersions.get(key);
      versions.removeFirst();
      if (versions.isEmpty()) {
        inFlightVersions.remove(key);
      }
    }
  }

  private void validateInFlightVersionTail(ArchiveInFlightBlock block) {
    Map<WrappedByteArray, Iterator<InFlightVersion>> iterators = new HashMap<>();
    List<ArchiveChangeRecord> records = block.getRecords();
    for (int i = records.size() - 1; i >= 0; i--) {
      ArchiveChangeRecord record = records.get(i);
      WrappedByteArray key = latestKey(record.getDomain(), record.getCanonicalKey());
      Deque<InFlightVersion> versions = inFlightVersions.get(key);
      Iterator<InFlightVersion> iterator = iterators.computeIfAbsent(key,
          ignored -> versions == null ? null : versions.descendingIterator());
      if (iterator == null || !iterator.hasNext()
          || !iterator.next().matches(block.getRange().getBlockNum(), record)) {
        throw new ArchiveException("archive in-flight version tail mismatch for block "
            + block.getRange().getBlockNum() + " txNum " + record.getTxNum());
      }
    }
  }

  private void removeInFlightVersionTail(ArchiveInFlightBlock block) {
    List<ArchiveChangeRecord> records = block.getRecords();
    for (int i = records.size() - 1; i >= 0; i--) {
      ArchiveChangeRecord record = records.get(i);
      WrappedByteArray key = latestKey(record.getDomain(), record.getCanonicalKey());
      Deque<InFlightVersion> versions = inFlightVersions.get(key);
      versions.removeLast();
      if (versions.isEmpty()) {
        inFlightVersions.remove(key);
      }
    }
  }

  private static WrappedByteArray latestKey(ArchiveDomain domain, byte[] canonicalKey) {
    return WrappedByteArray.copyOf(Bytes.concat(
        Ints.toByteArray(domain.getId()), canonicalKey));
  }

  private static List<ArchiveTxPosition> positionsOf(ArchiveBlockRange range,
      ArchiveTxNumIndex index) {
    List<ArchiveTxPosition> positions = new ArrayList<>();
    for (long txNum = range.getFirstTxNum(); txNum <= range.getLastTxNum(); txNum++) {
      Optional<ArchiveTxPosition> position = index.getPosition(txNum);
      if (!position.isPresent()) {
        throw new ArchiveException("archive tx-position missing for txNum " + txNum);
      }
      positions.add(position.get());
    }
    return positions;
  }

  private static boolean sameDomainValue(DomainValue left, DomainValue right) {
    return left.contentEquals(right);
  }

  private static long addSaturated(long left, long right) {
    return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
  }

  private static long subtractChecked(long current, long removed, String what) {
    if (removed < 0L || removed > current) {
      throw new ArchiveException("archive in-flight " + what + " accounting underflow");
    }
    return current - removed;
  }

  private void validateAvailableForRead() throws ArchiveReaderException {
    try {
      validateAvailable();
    } catch (RuntimeException e) {
      throw new ArchiveReaderException(ArchiveReaderException.Reason.INTERNAL_IO,
          e.getMessage() == null ? "archive is unavailable" : e.getMessage(), e);
    }
  }

  private Optional<DomainValue> readThrough(ArchiveDomain domain, byte[] canonicalKey,
      ArchiveStatePoint point) throws ArchiveReaderException {
    if (!canUseInFlightShield(point)) {
      return Optional.empty();
    }
    // Ordinary ChainBase head may include pending/packing overlays. Until a dedicated canonical
    // committed view exists, only the same-generation in-flight earliest-prev shield is safe.
    return readThroughInFlight(domain, canonicalKey, point.getTxNum());
  }

  private boolean canUseInFlightShield(ArchiveStatePoint point) {
    long firstArchivedBlock = txNumIndex.getFirstArchivedBlock();
    return firstArchivedBlock > 0 && point.getBlockNum() >= firstArchivedBlock;
  }

  private Optional<DomainValue> readThroughInFlight(ArchiveDomain domain, byte[] canonicalKey,
      long pointTxNum) {
    Deque<InFlightVersion> versions = inFlightVersions.get(latestKey(domain, canonicalKey));
    if (versions == null) {
      return Optional.empty();
    }
    // Reader points are resolved only from the published index, so every retained in-flight
    // version must be strictly newer. The head prev is therefore the O(1) shield value.
    InFlightVersion first = versions.peekFirst();
    if (first.txNum <= pointTxNum) {
      throw new ArchiveException("archive in-flight shield is not after reader point txNum "
          + pointTxNum);
    }
    return Optional.of(first.prevValue);
  }

  @Override
  public void abortBlock(BlockCapsule block) {
    if (!enabled) {
      return;
    }
    Lock writeLock = consistencyLock.writeLock();
    writeLock.lock();
    try {
      RuntimeException failure = null;
      try {
        validateAvailable();
        clearExecutionContextIfCurrent();
      } catch (RuntimeException e) {
        failure = e;
      }
      try {
        executionTxNumIndex.abortBlock(block.getNum());
      } catch (RuntimeException e) {
        if (failure == null) {
          failure = e;
        } else {
          failure.addSuppressed(e);
        }
      } finally {
        captureEngine.clear();
      }
      if (failure != null) {
        markFatal(failure);
        throw failure;
      }
    } finally {
      writeLock.unlock();
    }
  }

  @Override
  public void unwindBlock(BlockCapsule block) {
    if (!enabled) {
      return;
    }
    Lock writeLock = consistencyLock.writeLock();
    writeLock.lock();
    try {
      try {
        validateAvailable();
        ArchiveInFlightBlock inFlight = inFlightBlocks.get(block.getNum());
        if (inFlight != null) {
          if (!Arrays.equals(inFlight.getRange().getBlockHash(), block.getBlockId().getBytes())) {
            throw new ArchiveException("cannot unwind block " + block.getNum()
                + ": archive in-flight block hash mismatch");
          }
          if (inFlightBlocks.isEmpty() || inFlightBlocks.lastKey() != block.getNum()) {
            throw new ArchiveException("cannot unwind block " + block.getNum()
                + ": not archive in-flight head");
          }
          executionTxNumIndex.getHeadBlockRange(block.getNum());
          validateInFlightVersionTail(inFlight);
          inFlightStore.deleteBlock(block.getNum());
          inFlightBlocks.remove(block.getNum());
          executionTxNumIndex.unwindBlock(block.getNum());
          removeInFlightVersionTail(inFlight);
          removeInFlightUsage(inFlight);
        } else {
          Optional<ArchiveBlockRange> committed = txNumIndex.getBlockRange(block.getNum());
          long firstArchivedBlock = txNumIndex.getFirstArchivedBlock();
          if (committed.isPresent()) {
            throw new ArchiveException("cannot unwind published archive block " + block.getNum());
          }
          if (!committed.isPresent()
              && (firstArchivedBlock < 0 || block.getNum() < firstArchivedBlock)) {
            clearExecutionContextIfCurrent();
            captureEngine.clear();
            return;
          }
          throw new ArchiveException("cannot unwind block " + block.getNum()
              + ": not in archive in-flight head");
        }
        captureEngine.clear();
      } catch (RuntimeException e) {
        markFatal(e);
        throw e;
      }
    } finally {
      writeLock.unlock();
    }
  }

  @Override
  public void validateCanonicalHead(BlockCapsule canonicalHead) {
    if (!enabled) {
      return;
    }
    validateAvailable();
    txNumIndex.validateCanonicalHead(
        canonicalHead.getNum(), canonicalHead.getBlockId().getBytes());
  }

  @Override
  public void markRebuildRequired(String reason) {
    if (!enabled) {
      return;
    }
    if (reason == null || reason.isEmpty()) {
      throw new IllegalArgumentException("archive rebuild reason must not be empty");
    }
    Lock writeLock = consistencyLock.writeLock();
    writeLock.lock();
    try {
      txNumIndex.markRepairRequired(reason);
      ArchiveMetrics.setRepairRequired(true);
    } finally {
      writeLock.unlock();
    }
  }

  @Override
  public void validateAvailable() {
    if (!enabled) {
      return;
    }
    lifecycle.validateCurrentOperation();
    if (!ArchiveCaptureHolder.isCurrent(captureEngine)) {
      throw new ArchiveException("archive capture engine is not active");
    }
  }

  @Override
  public ReadGuard acquireReadGuard() {
    if (!enabled) {
      return ArchiveService.super.acquireReadGuard();
    }
    ArchiveWorkLease lifecycleLease = lifecycle.acquire(ArchiveLifecycle.WorkType.QUERY);
    Lock readLock = consistencyLock.readLock();
    boolean lockAcquired = false;
    try {
      readLock.lock();
      lockAcquired = true;
      validateAvailable();
      AtomicBoolean released = new AtomicBoolean();
      Thread owner = Thread.currentThread();
      return () -> {
        if (Thread.currentThread() != owner) {
          throw new ArchiveException("archive read guard used by a different thread");
        }
        if (!released.compareAndSet(false, true)) {
          return;
        }
        try {
          readLock.unlock();
        } catch (RuntimeException | Error e) {
          closeAndSuppress(lifecycleLease, e);
          throw e;
        }
        lifecycleLease.close();
      };
    } catch (RuntimeException | Error e) {
      if (lockAcquired) {
        closeAndSuppress(readLock::unlock, e);
      }
      closeAndSuppress(lifecycleLease, e);
      throw e;
    }
  }

  @Override
  public ArchiveStateReader openReader(ArchiveStatePoint point) throws ArchiveReaderException {
    return openResolvedReader(context -> point);
  }

  @Override
  public ArchiveStateReader openBlockEndReader(long blockNum, byte[] canonicalBlockHash)
      throws ArchiveReaderException {
    return openResolvedReader(context -> {
      context.recordBackendRead();
      ArchiveBlockRange range = txNumIndex.getBlockRange(blockNum)
          .orElseThrow(() -> historyUnavailable(blockNum));
      validateCanonicalRangeHash(range, canonicalBlockHash);
      return ArchiveStatePoint.blockEnd(
          blockNum, canonicalBlockHash, range.getFinalizeTxNum());
    });
  }

  @Override
  public ArchiveStateReader openBlockEndReader(long blockNum,
      LongFunction<byte[]> canonicalBlockHashProvider) throws ArchiveReaderException {
    if (canonicalBlockHashProvider == null) {
      throw new NullPointerException("canonicalBlockHashProvider");
    }
    return openResolvedReader(context -> {
      context.recordBackendRead();
      ArchiveBlockRange range = txNumIndex.getBlockRange(blockNum)
          .orElseThrow(() -> historyUnavailable(blockNum));
      context.recordBackendRead();
      byte[] canonicalBlockHash = canonicalBlockHashProvider.apply(blockNum);
      validateCanonicalRangeHash(range, canonicalBlockHash);
      return ArchiveStatePoint.blockEnd(
          blockNum, canonicalBlockHash, range.getFinalizeTxNum());
    });
  }

  @Override
  public ArchiveStateReader openBlockEndReader(LongSupplier blockNumProvider,
      LongFunction<byte[]> canonicalBlockHashProvider) throws ArchiveReaderException {
    if (blockNumProvider == null) {
      throw new NullPointerException("blockNumProvider");
    }
    if (canonicalBlockHashProvider == null) {
      throw new NullPointerException("canonicalBlockHashProvider");
    }
    return openResolvedReader(context -> {
      context.recordBackendRead();
      long blockNum = blockNumProvider.getAsLong();
      context.recordBackendRead();
      ArchiveBlockRange range = txNumIndex.getBlockRange(blockNum)
          .orElseThrow(() -> historyUnavailable(blockNum));
      context.recordBackendRead();
      byte[] canonicalBlockHash = canonicalBlockHashProvider.apply(blockNum);
      validateCanonicalRangeHash(range, canonicalBlockHash);
      return ArchiveStatePoint.blockEnd(
          blockNum, canonicalBlockHash, range.getFinalizeTxNum());
    });
  }

  @Override
  public ArchiveStateReader openBlockHashReader(byte[] requestedBlockHash,
      Function<byte[], BlockCapsule> canonicalBlockProvider) throws ArchiveReaderException {
    if (requestedBlockHash == null
        || requestedBlockHash.length != ArchiveBlockRange.BLOCK_HASH_LENGTH) {
      throw new ArchiveReaderException(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE,
          "archive block hash must be 32 bytes");
    }
    if (canonicalBlockProvider == null) {
      throw new NullPointerException("canonicalBlockProvider");
    }
    byte[] immutableRequestedHash = Arrays.copyOf(
        requestedBlockHash, requestedBlockHash.length);
    return openResolvedReader(context -> {
      context.recordBackendReads(2L);
      BlockCapsule canonicalBlock = canonicalBlockProvider.apply(immutableRequestedHash);
      if (canonicalBlock == null
          || !Arrays.equals(canonicalBlock.getBlockId().getBytes(), immutableRequestedHash)) {
        throw new ArchiveReaderException(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE,
            "archive block hash is not canonical");
      }
      long blockNum = canonicalBlock.getNum();
      context.recordBackendRead();
      ArchiveBlockRange range = txNumIndex.getBlockRange(blockNum)
          .orElseThrow(() -> historyUnavailable(blockNum));
      validateCanonicalRangeHash(range, immutableRequestedHash);
      return ArchiveStatePoint.blockEnd(
          blockNum, immutableRequestedHash, range.getFinalizeTxNum());
    });
  }

  @Override
  public ArchiveStateReader openTransactionReader(byte[] txId, long expectedBlockNum,
      byte[] canonicalBlockHash) throws ArchiveReaderException {
    return openTransactionReader(
        txId, expectedBlockNum, canonicalBlockHash, null);
  }

  @Override
  public ArchiveStateReader openTransactionReader(byte[] txId,
      LongFunction<byte[]> canonicalBlockHashProvider) throws ArchiveReaderException {
    if (canonicalBlockHashProvider == null) {
      throw new NullPointerException("canonicalBlockHashProvider");
    }
    return openTransactionReader(
        txId, -1L, null, canonicalBlockHashProvider);
  }

  private ArchiveStateReader openTransactionReader(byte[] txId, long expectedBlockNum,
      byte[] canonicalBlockHash, LongFunction<byte[]> canonicalBlockHashProvider)
      throws ArchiveReaderException {
    if (txId == null || txId.length != ArchiveBlockRange.BLOCK_HASH_LENGTH) {
      throw new ArchiveReaderException(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE,
          "archive transaction id must be 32 bytes");
    }
    return openResolvedReader(context -> {
      // txId row + referenced position + referenced committed range validation.
      context.recordBackendReads(3L);
      OptionalLong resolvedTxNum = txNumIndex.findTxNumByTxId(txId);
      if (!resolvedTxNum.isPresent()) {
        throw new ArchiveReaderException(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE,
            "transaction not in archive");
      }
      long txNum = resolvedTxNum.getAsLong();
      if (txNum < 1) {
        throw new ArchiveReaderException(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE,
            "transaction has no pre-state archive point");
      }
      // Position lookup revalidates its committed block range.
      context.recordBackendReads(2L);
      ArchiveTxPosition position = txNumIndex.getPosition(txNum)
          .orElseThrow(() -> new ArchiveReaderException(
              ArchiveReaderException.Reason.HISTORY_UNAVAILABLE,
              "archive tx-position missing for transaction"));
      if (position.getPhase() != ArchivePhase.USER_TX
          || expectedBlockNum >= 0 && position.getBlockNum() != expectedBlockNum
          || !Arrays.equals(position.getTxId(), txId)) {
        throw new ArchiveReaderException(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE,
            "archive transaction position mismatch");
      }
      long blockNum = position.getBlockNum();
      context.recordBackendRead();
      ArchiveBlockRange range = txNumIndex.getBlockRange(blockNum)
          .orElseThrow(() -> historyUnavailable(blockNum));
      if (txNum < range.getFirstTxNum() || txNum > range.getLastTxNum()) {
        throw new ArchiveReaderException(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE,
            "archive transaction range mismatch");
      }
      byte[] resolvedBlockHash = canonicalBlockHashProvider == null
          ? canonicalBlockHash
          : canonicalBlockHash(context, canonicalBlockHashProvider, blockNum);
      validateCanonicalRangeHash(range, resolvedBlockHash);
      return ArchiveStatePoint.txBefore(blockNum, resolvedBlockHash, txNum - 1);
    });
  }

  private static byte[] canonicalBlockHash(QueryContext context,
      LongFunction<byte[]> canonicalBlockHashProvider, long blockNum) {
    // Canonical block lookup resolves both the height index and the block row.
    context.recordBackendReads(2L);
    return canonicalBlockHashProvider.apply(blockNum);
  }

  private ArchiveStateReader openResolvedReader(PointResolver pointResolver)
      throws ArchiveReaderException {
    if (!enabled) {
      throw new ArchiveReaderException(ArchiveReaderException.Reason.ARCHIVE_DISABLED,
          "archive temporal store is not available");
    }
    // Preserve the archive-unavailable error surface after a fatal failure or during shutdown.
    // Budget/admission errors are meaningful only while the archive lifecycle is RUNNING.
    validateAvailableForRead();
    QueryLease queryLease;
    try {
      queryLease = queryCoordinator.acquire();
    } catch (RuntimeException e) {
      if (e instanceof HistoricalQueryLimitException) {
        ArchiveMetrics.queryRejected((HistoricalQueryLimitException) e);
      }
      validateAvailableForRead();
      throw e;
    }
    QueryContext queryContext = queryLease.getContext();
    boolean genesisComplete;
    try {
      genesisComplete = unifiedBackend == null
          && txNumIndex.getFirstArchivedBlock() == 0L;
    } catch (RuntimeException | Error e) {
      queryContext.recordFailure(e);
      closeAndSuppress(queryLease, e);
      throw e;
    }
    ArchiveSnapshotPermit snapshotPermit = null;
    if (unifiedBackend != null || genesisComplete) {
      try {
        snapshotPermit = queryCoordinator.acquireSnapshot(queryLease);
      } catch (RuntimeException | Error e) {
        queryContext.recordFailure(e);
        closeAndSuppress(queryLease, e);
        if (e instanceof Error) {
          throw (Error) e;
        }
        validateAvailableForRead();
        throw e;
      }
    }
    ArchiveWorkLease lifecycleLease;
    try {
      lifecycleLease = lifecycle.acquire(ArchiveLifecycle.WorkType.QUERY);
    } catch (RuntimeException | Error e) {
      queryContext.recordFailure(e);
      if (snapshotPermit != null) {
        closeAndSuppress(snapshotPermit, e);
      }
      closeAndSuppress(queryLease, e);
      if (e instanceof Error) {
        throw (Error) e;
      }
      validateAvailableForRead();
      throw new ArchiveReaderException(ArchiveReaderException.Reason.INTERNAL_IO,
          e.getMessage() == null ? "archive query admission failed" : e.getMessage(), e);
    }
    Lock readLock = consistencyLock.readLock();
    int readHoldCountBefore = consistencyLock.getReadHoldCount();
    boolean lockAcquired = false;
    boolean lockTransferred = false;
    boolean leaseTransferred = false;
    boolean snapshotPermitTransferred = false;
    Throwable openingFailure = null;
    try {
      try {
        long remaining = queryContext.getRemainingNanos();
        if (remaining == Long.MAX_VALUE) {
          readLock.lockInterruptibly();
          lockAcquired = true;
        } else {
          lockAcquired = readLock.tryLock(remaining, TimeUnit.NANOSECONDS);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        HistoricalQueryLimitException interrupted = new HistoricalQueryLimitException(
            HistoricalQueryLimitException.Reason.RESOURCE_EXHAUSTED,
            HistoricalQueryLimitException.Limit.INTERRUPTED,
            "interrupted while waiting for archive consistency lock");
        throw interrupted;
      }
      if (!lockAcquired) {
        queryContext.checkDeadline();
        HistoricalQueryLimitException deadline = new HistoricalQueryLimitException(
            HistoricalQueryLimitException.Reason.DEADLINE,
            HistoricalQueryLimitException.Limit.DEADLINE,
            "historical query deadline exceeded while waiting for archive consistency lock");
        throw deadline;
      }
      try {
        lifecycleLease.start();
      } catch (RuntimeException e) {
        try {
          validateAvailableForRead();
        } catch (ArchiveReaderException unavailable) {
          unavailable.addSuppressed(e);
          throw unavailable;
        }
        throw new ArchiveReaderException(ArchiveReaderException.Reason.INTERNAL_IO,
            e.getMessage() == null ? "archive query start failed" : e.getMessage(), e);
      }
      validateAvailableForRead();
      queryContext.checkDeadline();
      if (unifiedBackend != null) {
        UnifiedArchiveBackend.ReadSession readSession = unifiedBackend.openReadSession();
        ArchiveStateReader unifiedReader = null;
        try {
          ArchiveStatePoint point;
          try (UnifiedArchiveTxNumIndex.ReadScope ignoredIndex = readSession.bindIndex()) {
            genesisComplete = txNumIndex.getFirstArchivedBlock() == 0L;
            try (QueryContextHolder.Scope ignored = QueryContextHolder.attach(queryContext)) {
              point = pointResolver.resolve(queryContext);
            }
            queryContext.recordBackendRead();
            Runnable onClose = genesisComplete ? () -> { } : readLock::unlock;
            unifiedReader = readerFactory.openSnapshot(point, readSession.getTemporalView(),
                onClose, genesisComplete, queryContext);
          }
          if (genesisComplete) {
            readLock.unlock();
          }
          lockTransferred = true;
          ManagedArchiveStateReader managed = new ManagedArchiveStateReader(
              unifiedReader, lifecycleLease, queryLease, snapshotPermit);
          leaseTransferred = true;
          snapshotPermitTransferred = true;
          return managed;
        } catch (RuntimeException | Error | ArchiveReaderException e) {
          if (unifiedReader != null) {
            closeAndSuppress(unifiedReader, e);
          } else {
            closeAndSuppress(readSession, e);
          }
          throw e;
        }
      }
      ArchiveStatePoint point;
      try (QueryContextHolder.Scope ignored = QueryContextHolder.attach(queryContext)) {
        point = pointResolver.resolve(queryContext);
      }
      queryContext.recordBackendRead();
      ArchiveStateReader reader;
      if (genesisComplete) {
        // Genesis-complete: freeze temporal reads in a snapshot, release the lock, and let the VM
        // run against the snapshot -- so a long historical eth_call/trace no longer stalls block
        // commit. The live/in-flight read-through is gated on firstArchivedBlock > 0, so it is
        // unused here; the temporal snapshot alone is a complete, consistent view.
        ArchiveTemporalReadView view = temporalStore.openReadView();
        try {
          reader = readerFactory.openSnapshot(point, view, queryLease.getContext());
        } catch (RuntimeException | Error | ArchiveReaderException e) {
          closeAndSuppress(view, e);
          throw e;
        }
        readLock.unlock();
        lockTransferred = true;
        ManagedArchiveStateReader managed;
        try {
          managed = new ManagedArchiveStateReader(
              reader, lifecycleLease, queryLease, snapshotPermit);
        } catch (RuntimeException | Error e) {
          // The reader owns the temporal snapshot; release it if the wrapper ctor fails, so this
          // path cannot leak a RocksDB snapshot that pins SST files. close() has a no-op onClose
          // here (the lock was already released above), so it only releases the snapshot.
          closeAndSuppress(reader, e);
          throw e;
        }
        leaseTransferred = true;
        snapshotPermitTransferred = true;
        return managed;
      }
      // Mid-chain: keep the read lock for the reader lifetime so the temporal snapshot and
      // in-flight earliest-prev shield belong to one generation. Missing shield evidence fails
      // closed; ordinary ChainBase head is never consulted.
      reader = readerFactory.openLocked(point, readLock::unlock, queryLease.getContext());
      ManagedArchiveStateReader managed =
          new ManagedArchiveStateReader(reader, lifecycleLease, queryLease, null);
      lockTransferred = true;
      leaseTransferred = true;
      return managed;
    } catch (RuntimeException | Error | ArchiveReaderException e) {
      openingFailure = e;
      queryContext.recordFailure(e);
      throw e;
    } finally {
      Throwable cleanupFailure = null;
      if (lockAcquired && !lockTransferred
          && consistencyLock.getReadHoldCount() > readHoldCountBefore) {
        cleanupFailure = runAndCollect(cleanupFailure, readLock::unlock);
      }
      if (snapshotPermit != null && !snapshotPermitTransferred) {
        cleanupFailure = closeAndCollect(cleanupFailure, snapshotPermit);
      }
      if (!leaseTransferred) {
        cleanupFailure = closeAndCollect(cleanupFailure, lifecycleLease);
        cleanupFailure = closeAndCollect(cleanupFailure, queryLease);
      }
      if (cleanupFailure != null) {
        queryContext.recordFailure(cleanupFailure);
        if (openingFailure != null) {
          if (openingFailure != cleanupFailure) {
            openingFailure.addSuppressed(cleanupFailure);
          }
        } else if (cleanupFailure instanceof Error) {
          throw (Error) cleanupFailure;
        } else if (cleanupFailure instanceof RuntimeException) {
          throw (RuntimeException) cleanupFailure;
        } else {
          throw new ArchiveException("archive reader-open cleanup failed", cleanupFailure);
        }
      }
    }
  }

  private ArchiveReaderException historyUnavailable(long blockNum) {
    long first = txNumIndex.getFirstArchivedBlock();
    String suffix = first >= 0 && blockNum < first
        ? "; lowest supported block is " + first
        : "";
    return new ArchiveReaderException(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE,
        "archive history unavailable for block " + blockNum + suffix);
  }

  private static void validateCanonicalRangeHash(ArchiveBlockRange range,
      byte[] canonicalBlockHash) throws ArchiveReaderException {
    if (canonicalBlockHash == null
        || canonicalBlockHash.length != ArchiveBlockRange.BLOCK_HASH_LENGTH
        || !Arrays.equals(range.getBlockHash(), canonicalBlockHash)) {
      throw new ArchiveReaderException(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE,
          "archive history hash mismatch for block " + range.getBlockNum());
    }
  }

  @FunctionalInterface
  private interface PointResolver {

    ArchiveStatePoint resolve(QueryContext context) throws ArchiveReaderException;
  }

  @Override
  public boolean hasCommittedBlock(long blockNum) {
    validateAvailable();
    return enabled && txNumIndex.getBlockRange(blockNum).isPresent();
  }

  @Override
  public void close() {
    synchronized (closeMutex) {
      if (closed.get()) {
        return;
      }
      if (publisher != null) {
        publisher.beginDrain();
      }
      queryCoordinator.beginDrain();
      lifecycle.beginDrain();
      synchronized (backlogMonitor) {
        backlogMonitor.notifyAll();
      }
      try {
        long drainStartedNanos = System.nanoTime();
        if (!lifecycle.awaitDrained(closeDrainTimeoutNanos, TimeUnit.NANOSECONDS)) {
          throw new ArchiveException("archive drain timed out with active operations");
        }
        long elapsedNanos = System.nanoTime() - drainStartedNanos;
        long remainingNanos = Math.max(0L, closeDrainTimeoutNanos - elapsedNanos);
        if (!queryCoordinator.awaitDrained(remainingNanos, TimeUnit.NANOSECONDS)) {
          throw new ArchiveException("archive query drain timed out with active readers");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ArchiveException("archive drain interrupted", e);
      }
      if (publisher != null) {
        publisher.close();
      }
      if (fatalController != null) {
        fatalController.close();
      }
      Lock writeLock = consistencyLock.writeLock();
      writeLock.lock();
      try {
        boolean currentCaptureEngine =
            captureEngine != null && ArchiveCaptureHolder.isCurrent(captureEngine);
        if (currentCaptureEngine) {
          executionContext.clear();
        }
        if (captureEngine != null) {
          captureEngine.clear();
        }
        ArchiveCaptureHolder.clearIf(captureEngine);
        Throwable failure = null;
        // Unified adapters intentionally close in this order. In-flight and temporal close are
        // no-ops; the txNum index is the final owner that closes the shared UnifiedArchiveDb.
        failure = closeResource(inFlightStore, "in-flight store", failure);
        failure = closeResource(temporalStore, "temporal store", failure);
        failure = closeResource(txNumIndex, "txNum index", failure);
        closed.set(true);
        failure = runAndCollect(failure, lifecycle::markClosed);
        failure = runAndCollect(failure, queryCoordinator::close);
        rethrowCloseFailure(failure);
      } finally {
        writeLock.unlock();
      }
    }
  }

  private void clearExecutionContextIfCurrent() {
    if (captureEngine != null && ArchiveCaptureHolder.isCurrent(captureEngine)) {
      executionContext.clear();
    }
  }

  private static final class InFlightVersion {

    private final long blockNum;
    private final long txNum;
    private final DomainValue prevValue;
    private final DomainValue value;

    private InFlightVersion(long blockNum, long txNum, DomainValue prevValue, DomainValue value) {
      this.blockNum = blockNum;
      this.txNum = txNum;
      this.prevValue = prevValue;
      this.value = value;
    }

    private boolean matches(long expectedBlockNum, ArchiveChangeRecord record) {
      return blockNum == expectedBlockNum
          && txNum == record.getTxNum()
          && sameDomainValue(prevValue, record.getPrevValue())
          && sameDomainValue(value, record.getValue());
    }
  }

  private static final class RetryableStartupCleanupException extends ArchiveException {

    private RetryableStartupCleanupException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private static void closeAndSuppress(AutoCloseable resource, Throwable failure) {
    try {
      resource.close();
    } catch (Throwable closeFailure) {
      if (failure != closeFailure) {
        failure.addSuppressed(closeFailure);
      }
    }
  }

  private static Throwable closeAndCollect(Throwable failure, AutoCloseable resource) {
    try {
      resource.close();
    } catch (Throwable closeFailure) {
      return collectFailure(failure, closeFailure);
    }
    return failure;
  }

  private static Throwable runAndCollect(Throwable failure, Runnable action) {
    try {
      action.run();
    } catch (Throwable actionFailure) {
      return collectFailure(failure, actionFailure);
    }
    return failure;
  }

  private static Throwable collectFailure(Throwable failure, Throwable candidate) {
    if (failure == null) {
      return candidate;
    }
    if (failure != candidate) {
      failure.addSuppressed(candidate);
    }
    return failure;
  }

  private static Throwable closeResource(Object resource, String name, Throwable failure) {
    if (resource instanceof AutoCloseable) {
      try {
        ((AutoCloseable) resource).close();
      } catch (Throwable e) {
        Throwable closeFailure = e instanceof RuntimeException || e instanceof Error
            ? e : new ArchiveException("failed to close archive " + name, e);
        if (failure == null) {
          return closeFailure;
        }
        if (failure != closeFailure) {
          failure.addSuppressed(closeFailure);
        }
      }
    }
    return failure;
  }

  private static void rethrowCloseFailure(Throwable failure) {
    if (failure instanceof RuntimeException) {
      throw (RuntimeException) failure;
    }
    if (failure instanceof Error) {
      throw (Error) failure;
    }
    if (failure != null) {
      throw new ArchiveException("archive close failed", failure);
    }
  }

  private void markFatal(RuntimeException failure) {
    boolean first = lifecycle.markFatal(failure);
    RuntimeException primary = lifecycle.getFatalFailure();
    // Every contender can arm the same primary. This closes the CAS-winner scheduling gap without
    // opening callback delivery before the repair marker has crossed its durability barrier.
    fatalController.arm(primary);
    if (!first) {
      suppressIfAcyclicAndAbsent(primary, failure);
      logger.warn("additional archive fatal failure after fail-stop was armed", failure);
      return;
    }
    ArchiveMetrics.setRepairRequired(true);
    queryCoordinator.beginDrain();
    if (publisher != null) {
      publisher.beginDrain();
    }
    synchronized (backlogMonitor) {
      backlogMonitor.notifyAll();
    }
    try {
      txNumIndex.markRepairRequired(primary.getMessage() == null
          ? primary.getClass().getSimpleName()
          : primary.getMessage());
    } catch (RuntimeException markerFailure) {
      suppressIfAcyclicAndAbsent(primary, markerFailure);
    } finally {
      fatalController.deliver();
    }
  }

  private static void suppressIfAcyclicAndAbsent(Throwable primary, Throwable candidate) {
    if (primary == null || candidate == null || primary == candidate) {
      return;
    }
    synchronized (primary) {
      if (throwableGraphContains(candidate, primary)
          || throwableGraphContains(primary, candidate)) {
        return;
      }
      primary.addSuppressed(candidate);
    }
  }

  private static boolean throwableGraphContains(Throwable root, Throwable target) {
    Deque<Throwable> pending = new ArrayDeque<>();
    Map<Throwable, Boolean> visited = new IdentityHashMap<>();
    pending.add(root);
    while (!pending.isEmpty()) {
      Throwable current = pending.removeFirst();
      if (current == target) {
        return true;
      }
      if (visited.put(current, Boolean.TRUE) != null) {
        continue;
      }
      Throwable cause = current.getCause();
      if (cause != null) {
        pending.addLast(cause);
      }
      for (Throwable suppressed : current.getSuppressed()) {
        pending.addLast(suppressed);
      }
    }
    return false;
  }
}
