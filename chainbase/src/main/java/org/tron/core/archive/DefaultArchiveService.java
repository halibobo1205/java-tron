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
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
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
import org.tron.core.archive.txnum.ArchiveTransactionLocation;
import org.tron.core.archive.txnum.ArchiveTxNumIndex;
import org.tron.core.archive.txnum.ArchiveTxPosition;
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
  private static final long DISK_SAMPLE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5L);
  private static final long MIN_JOURNAL_DISK_HEADROOM_BYTES = 16L * 1024 * 1024;
  private static final long JOURNAL_MUTATION_BASE_ALLOWANCE_BYTES = 8L * 1024L;
  private static final long DEFAULT_CLOSE_DRAIN_TIMEOUT_NANOS =
      TimeUnit.SECONDS.toNanos(30L);
  private static final LongConsumer IGNORE_PUBLICATION_BYTES = ignored -> { };

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
  private final ReentrantLock publicationLock = new ReentrantLock(true);
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
  // Publication is serialized, so only the largest pending publication working set can coexist
  // with the retained journals. Keeping a multiset avoids O(n) rescans as the head drains.
  private final NavigableMap<Long, Integer> inFlightPublicationFootprints = new TreeMap<>();
  private volatile long inFlightPublicationBytes;
  private volatile long activePublicationBytes;
  private volatile long activeCaptureRecordCount;
  private volatile long activeCaptureBytes;
  private volatile long activeExecutionPositionBytes;
  private volatile long activeJournalMutationBytes;
  private volatile long inFlightResourceBytes;
  private volatile long oldestInFlightBlock = -1L;
  private final Object diskSampleMonitor = new Object();
  private final ArchiveDiskSpaceSampler diskSpaceSampler;
  private volatile long lastDiskSampleGeneration;
  private volatile long lastDiskSampleNanos = Long.MIN_VALUE;
  private volatile long lastUsableSpaceBytes = Long.MAX_VALUE;
  private final ArchiveLifecycle lifecycle;
  private final ArchiveMutationBarrier mutationBarrier = new ArchiveMutationBarrier();
  private final ArchiveQueryCoordinator queryCoordinator;
  private final BoundedArchivePublisher publisher;
  private final ArchiveFatalController fatalController;
  private final ArchiveOperationWatchdog publicationWatchdog;
  private final ArchiveOperationWatchdog journalWatchdog;
  private final ArchiveRepairClearPermit repairClearPermit = new ArchiveRepairClearPermit();
  private final Object repairStateMutex = new Object();
  private final Object fatalTransitionMonitor = new Object();
  private long activeFatalTransitions;
  private boolean fatalTransitionsSealed;
  private final ArchivePublisherConfig publisherConfig;
  private final Runnable startupValidator;
  private boolean startupStorageValidated;
  private volatile long closeDrainTimeoutNanos = DEFAULT_CLOSE_DRAIN_TIMEOUT_NANOS;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Object closeMutex = new Object();
  private volatile Throwable terminalCloseFailure;

  public DefaultArchiveService(boolean enabled) {
    this(enabled, enabled ? new InMemoryArchiveTemporalStore() : null);
  }

  /**
   * In-memory reference entry used by tests and semantic oracles. Persistent UNIFIED_V1 stores
   * must be wired through {@link ArchiveServiceFactory} so index, journal, temporal rows, marker,
   * and cursor share one atomic backend.
   */
  public DefaultArchiveService(boolean enabled, ArchiveTemporalStore temporalStore) {
    this(enabled, new InMemoryArchiveTxNumIndex(), ArchiveExecutionContextHolder.get(),
        requireInMemoryReferenceStore(enabled, temporalStore));
  }

  private static ArchiveTemporalStore requireInMemoryReferenceStore(
      boolean enabled, ArchiveTemporalStore temporalStore) {
    if (enabled && !(temporalStore instanceof InMemoryArchiveTemporalStore)) {
      throw new ArchiveException(
          "persistent archive stores must be constructed by ArchiveServiceFactory");
    }
    return temporalStore;
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
    this(enabled, txNumIndex, executionContext, temporalStore, inFlightStore, registry, catalog,
        ArchiveLifecycle.Phase.RUNNING);
  }

  DefaultArchiveService(boolean enabled, ArchiveTxNumIndex txNumIndex,
      ArchiveExecutionContext executionContext, ArchiveTemporalStore temporalStore,
      ArchiveInFlightStore inFlightStore, ArchiveDomainRegistry registry,
      ArchiveDomainCatalog catalog, ArchiveLifecycle.Phase initialPhase) {
    this(enabled, txNumIndex, executionContext, temporalStore, inFlightStore, registry, catalog,
        initialPhase, ArchiveQueryLimits.unlimited());
  }

  DefaultArchiveService(boolean enabled, ArchiveTxNumIndex txNumIndex,
      ArchiveExecutionContext executionContext, ArchiveTemporalStore temporalStore,
      ArchiveInFlightStore inFlightStore, ArchiveDomainRegistry registry,
      ArchiveDomainCatalog catalog, ArchiveLifecycle.Phase initialPhase,
      Runnable startupValidator) {
    this(enabled, txNumIndex, executionContext, temporalStore, inFlightStore, registry, catalog,
        initialPhase, ArchiveQueryLimits.unlimited(), false, startupValidator);
  }

  DefaultArchiveService(boolean enabled, ArchiveTxNumIndex txNumIndex,
      ArchiveExecutionContext executionContext, ArchiveTemporalStore temporalStore,
      ArchiveInFlightStore inFlightStore, ArchiveDomainRegistry registry,
      ArchiveDomainCatalog catalog, ArchiveLifecycle.Phase initialPhase,
      ArchiveQueryLimits queryLimits) {
    this(enabled, txNumIndex, executionContext, temporalStore, inFlightStore, registry, catalog,
        initialPhase, queryLimits, false);
  }

  DefaultArchiveService(boolean enabled, ArchiveTxNumIndex txNumIndex,
      ArchiveExecutionContext executionContext, ArchiveTemporalStore temporalStore,
      ArchiveInFlightStore inFlightStore, ArchiveDomainRegistry registry,
      ArchiveDomainCatalog catalog, ArchiveLifecycle.Phase initialPhase,
      ArchiveQueryLimits queryLimits,
      boolean asyncPublisher) {
    this(enabled, txNumIndex, executionContext, temporalStore, inFlightStore, registry, catalog,
        initialPhase, queryLimits, asyncPublisher, () -> {
        });
  }

  DefaultArchiveService(boolean enabled, ArchiveTxNumIndex txNumIndex,
      ArchiveExecutionContext executionContext, ArchiveTemporalStore temporalStore,
      ArchiveInFlightStore inFlightStore, ArchiveDomainRegistry registry,
      ArchiveDomainCatalog catalog, ArchiveLifecycle.Phase initialPhase,
      ArchiveQueryLimits queryLimits,
      boolean asyncPublisher, Runnable startupValidator) {
    this(enabled, txNumIndex, executionContext, temporalStore, inFlightStore, registry, catalog,
        initialPhase, queryLimits,
        new ArchivePublisherConfig(asyncPublisher, asyncPublisher,
            ArchivePublisherConfig.DEFAULT_SOFT_IN_FLIGHT_BLOCKS,
            ArchivePublisherConfig.DEFAULT_HARD_IN_FLIGHT_BLOCKS,
            ArchivePublisherConfig.DEFAULT_BACKPRESSURE_TIMEOUT_MS), startupValidator);
  }

  DefaultArchiveService(boolean enabled, ArchiveTxNumIndex txNumIndex,
      ArchiveExecutionContext executionContext, ArchiveTemporalStore temporalStore,
      ArchiveInFlightStore inFlightStore, ArchiveDomainRegistry registry,
      ArchiveDomainCatalog catalog, ArchiveLifecycle.Phase initialPhase,
      ArchiveQueryLimits queryLimits,
      ArchivePublisherConfig publisherConfig, Runnable startupValidator) {
    this(enabled, txNumIndex, executionContext, temporalStore, inFlightStore, registry, catalog,
        initialPhase, queryLimits, publisherConfig, startupValidator, null);
  }

  DefaultArchiveService(boolean enabled, ArchiveTxNumIndex txNumIndex,
      ArchiveExecutionContext executionContext, ArchiveTemporalStore temporalStore,
      ArchiveInFlightStore inFlightStore, ArchiveDomainRegistry registry,
      ArchiveDomainCatalog catalog, ArchiveLifecycle.Phase initialPhase,
      ArchiveQueryLimits queryLimits,
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
          publisherConfig.getHardInFlightBytes(), this::reserveActiveCaptureBytes,
          this::reserveActiveCaptureRecords);
      this.temporalStore = temporalStore;
      this.inFlightStore = inFlightStore;
      this.executionTxNumIndex = new InMemoryArchiveTxNumIndex(txNumIndex.getNextTxNum());
      ArchiveMetrics.setRepairRequired(txNumIndex.hasRepairRequired());
      this.diskSpaceSampler = new ArchiveDiskSpaceSampler(
          "archive-disk-space", inFlightStore::usableSpaceBytes);
      try {
        sampleUsableSpaceBytes(true);
        loadInFlightBlocks();
        updateInFlightMetrics();
        if (initialPhase == ArchiveLifecycle.Phase.RUNNING) {
          // Direct RUNNING construction is retained for unit/in-memory callers without a startup
          // RecoveryLease. Production factory instances start in RECOVERING and defer this work.
          validateStartupStorageLocked();
        }
      } catch (RuntimeException | Error failure) {
        try {
          diskSpaceSampler.close();
        } catch (RuntimeException | Error closeFailure) {
          addSuppressedSafely(failure, closeFailure);
        }
        throw failure;
      }
      this.readerFactory = new DefaultArchiveStateReaderFactory(temporalStore, catalog,
          txNumIndex, this::validateAvailableForRead, queryLimits);
      this.fatalController = new ArchiveFatalController("archive-fatal-control");
      this.publicationWatchdog = new ArchiveOperationWatchdog(
          "archive-publication-watchdog", this::armWatchdogFatal);
      this.journalWatchdog = new ArchiveOperationWatchdog(
          "archive-journal-watchdog", this::armWatchdogFatal);
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
      this.diskSpaceSampler = null;
      this.executionTxNumIndex = null;
      this.readerFactory = null;
      this.publisher = null;
      this.fatalController = null;
      this.publicationWatchdog = null;
      this.journalWatchdog = null;
    }
  }

  public ArchiveTxNumIndex getTxNumIndex() {
    if (unifiedBackend != null) {
      throw new ArchiveException("production archive txNum index is not externally mutable");
    }
    return txNumIndex;
  }

  public long getFirstArchivedBlock() {
    return txNumIndex.getFirstArchivedBlock();
  }

  public Optional<ArchiveBlockRange> getCommittedBlockRange(long blockNum) {
    return txNumIndex.getBlockRange(blockNum);
  }

  private void loadInFlightBlocks() {
    long[] startupJournalBytes = {0L};
    long[] startupPublicationBytes = {0L};
    long[] startupResourceBytes = {0L};
    long[] totalRecords = {0L};
    long[] totalBlocks = {0L};
    long[] loadedBytes = {0L};
    long[] loadedRecords = {0L};
    int[] loadedBlocks = {0};
    long[] staleBytes = {0L};
    long[] staleRecords = {0L};
    int[] staleBlocks = {0};
    try (ArchiveTemporalReadView startupView = temporalStore.openReadView()) {
      inFlightStore.forEachBlock(block -> {
        if (unifiedBackend != null) {
          unifiedBackend.validatePublicationAdmission(block);
        }
        ArchiveBlockRange range = block.getRange();
        long retainedBytes = block.estimatedRetainedBytes();
        startupJournalBytes[0] = addSaturated(startupJournalBytes[0], retainedBytes);
        startupPublicationBytes[0] = Math.max(
            startupPublicationBytes[0], blockResourceFootprintBytes(block));
        startupResourceBytes[0] = addSaturated(
            startupJournalBytes[0], startupPublicationBytes[0]);
        totalBlocks[0] = addSaturated(totalBlocks[0], 1L);
        totalRecords[0] = addSaturated(totalRecords[0], block.getRecords().size());
        Optional<ArchiveBlockRange> published = txNumIndex.getBlockRange(range.getBlockNum());
        if (published.isPresent()) {
          if (unifiedBackend != null) {
            throw new ArchiveJournalCorruptionException(
                "UNIFIED_V1 published block still has an in-flight journal: block="
                    + range.getBlockNum());
          }
          staleBlocks[0]++;
          staleBytes[0] = addSaturated(staleBytes[0], retainedBytes);
          staleRecords[0] = addSaturated(staleRecords[0], block.getRecords().size());
          validateStartupJournalBytes(
              startupJournalBytes[0], startupResourceBytes[0], staleBytes[0], loadedBytes[0]);
          validateStartupJournalCounts(totalBlocks[0], totalRecords[0],
              staleBlocks[0], staleRecords[0], loadedBlocks[0], loadedRecords[0]);
          validatePersistentJournalState(range.getBlockNum(), () -> {
            validatePublishedRange(range, published.get());
            validateJournalPositionsMatchIndex(block);
          });
          pendingPublishedJournals.put(range.getBlockNum(), block);
          return;
        }
        loadedBlocks[0]++;
        loadedBytes[0] = addSaturated(loadedBytes[0], retainedBytes);
        loadedRecords[0] = addSaturated(loadedRecords[0], block.getRecords().size());
        validateStartupJournalBytes(
            startupJournalBytes[0], startupResourceBytes[0], staleBytes[0], loadedBytes[0]);
        validateStartupJournalCounts(totalBlocks[0], totalRecords[0],
            staleBlocks[0], staleRecords[0], loadedBlocks[0], loadedRecords[0]);
        validateInFlightAppendResources(block);
        validatePersistentJournalState(range.getBlockNum(), () -> {
          validateInFlightAppendSequence(block);
          validateInFlightPrevValueChain(startupView, block);
          replayExecutionInFlightBlock(block);
        });
        rememberInFlightInMemory(block);
      });
    }
  }

  private static void validatePersistentJournalState(long blockNum, Runnable validator) {
    try {
      validator.run();
    } catch (ArchivePersistentStateCorruptionException e) {
      throw e;
    } catch (ArchiveException e) {
      throw new ArchivePersistentStateCorruptionException(
          "archive durable journal state is inconsistent for block " + blockNum
              + (e.getMessage() == null ? "" : ": " + e.getMessage()), e);
    }
  }

  private void validateStartupJournalBytes(long totalBytes, long resourceBytes,
      long staleBytes, long loadedBytes) {
    if (totalBytes > publisherConfig.getHardInFlightBytes()
        || resourceBytes > publisherConfig.getHardInFlightBytes()) {
      throw new ArchiveException(
          "archive startup journals exceed configured hard resource limit: bytes=" + totalBytes
              + ", resourceBytes=" + resourceBytes + ", staleBytes=" + staleBytes
              + ", loadedBytes=" + loadedBytes);
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

  private void validateInFlightPrevValueChain(ArchiveTemporalReadView startupView,
      ArchiveInFlightBlock block) {
    Map<WrappedByteArray, DomainValue> stagedLatest = new LinkedHashMap<>();
    for (ArchiveChangeRecord record : block.getRecords()) {
      WrappedByteArray key = latestKey(record.getDomain(), record.getCanonicalKey());
      DomainValue expected = stagedLatest.get(key);
      if (expected == null) {
        expected = latestWithInFlight(
            startupView, record.getDomain(), record.getCanonicalKey()).orElse(null);
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
    } catch (RuntimeException | Error e) {
      try {
        executionTxNumIndex.abortBlock(range.getBlockNum());
      } catch (RuntimeException | Error cleanupFailure) {
        addSuppressedSafely(e, cleanupFailure);
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
    if (unifiedBackend != null) {
      throw new ArchiveException("production archive capture engine is not externally mutable");
    }
    return captureEngine;
  }

  public ArchiveTemporalStore getTemporalStore() {
    if (unifiedBackend != null) {
      throw new ArchiveException("production archive temporal store is not externally mutable");
    }
    return temporalStore;
  }

  ArchiveCaptureEngine getCaptureEngineForTesting() {
    return captureEngine;
  }

  ArchiveTemporalStore getTemporalStoreForTesting() {
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
    while (true) {
      long usableSpace;
      try {
        usableSpace = sampleUsableSpaceBytes();
      } catch (RuntimeException e) {
        markDiskSampleFailure(e);
        throw e;
      }
      validateAvailable();
      ArchiveException fatalFailure = null;
      synchronized (backlogMonitor) {
        boolean hardLimitReached =
            inFlightBlockCount >= publisherConfig.getHardInFlightBlocks()
                || inFlightRecordCount >= publisherConfig.getHardInFlightRecords()
                || inFlightResourceBytes >= publisherConfig.getHardInFlightBytes()
                || usableSpace < publisherConfig.getHardMinFreeBytes();
        if (hardLimitReached) {
          fatalFailure = new ArchiveException(
              "archive in-flight journal reached hard watermark: blocks="
                  + inFlightBlockCount + ", records=" + inFlightRecordCount
                  + ", bytes=" + inFlightRetainedBytes
                  + ", resourceBytes=" + inFlightResourceBytes + ", diskFree=" + usableSpace);
        } else {
          boolean softLimitReached =
              inFlightBlockCount >= publisherConfig.getSoftInFlightBlocks()
                  || inFlightRecordCount >= publisherConfig.getSoftInFlightRecords()
                  || inFlightResourceBytes >= publisherConfig.getSoftInFlightBytes()
                  || usableSpace < publisherConfig.getSoftMinFreeBytes();
          if (!softLimitReached || publisher == null || !publisherConfig.isBackpressure()) {
            return;
          }
          long remaining = deadline - System.nanoTime();
          if (timeoutNanos == 0 || remaining <= 0) {
            fatalFailure = new ArchiveException(
                "archive publisher backpressure timed out: blocks=" + inFlightBlockCount
                    + ", records=" + inFlightRecordCount + ", bytes=" + inFlightRetainedBytes
                    + ", resourceBytes=" + inFlightResourceBytes + ", diskFree=" + usableSpace);
          } else {
            try {
              TimeUnit.NANOSECONDS.timedWait(backlogMonitor, remaining);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new ArchiveException("archive publisher backpressure interrupted", e);
            }
          }
        }
      }
      if (fatalFailure != null) {
        markFatal(fatalFailure);
        throw fatalFailure;
      }
    }
  }

  @Override
  public void completeRecovery() {
    if (enabled) {
      try {
        sampleUsableSpaceBytes(true);
      } catch (RuntimeException e) {
        markDiskSampleFailure(e);
        throw e;
      }
      Lock writeLock = consistencyLock.writeLock();
      writeLock.lock();
      try {
        validateLifecycleAvailable();
        try {
          if (lifecycle.getPhase() == ArchiveLifecycle.Phase.DRAINING) {
            lifecycle.completeRecovery();
            return;
          }
          validateCaptureAvailable();
          validateRecoveryStorageWithWatchdog();
          lifecycle.completeRecovery(() -> {
            try {
              if (publisher != null && !publisher.activateForRecovery()) {
                throw new ArchiveException(
                    "archive publisher drain raced a committed recovery activation");
              }
              synchronized (repairStateMutex) {
                // A fatal visible before this point prevents clear. A fatal published during the
                // forced-sync clear waits on this mutex and restores repair evidence before delivery.
                validateLifecycleAvailable();
                clearRepairRequiredWithWatchdog();
                ArchiveMetrics.setRepairRequired(false);
              }
            } catch (RuntimeException | Error activationFailure) {
              // Keep the recovery lease active until repair evidence and fatal delivery settle.
              markFatal(activationFailure);
              throw activationFailure;
            }
          });
        } catch (RuntimeException | Error e) {
          markFatal(e);
          throw e;
        }
      } finally {
        writeLock.unlock();
      }
    }
  }

  private void validateRecoveryStorageWithWatchdog() {
    long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(publisherConfig.getRecoveryTimeoutMs());
    try (ArchiveOperationWatchdog.Scope ignored = journalWatchdog.arm(
        "archive recovery startup validation", timeoutNanos)) {
      validateStartupStorageLocked();
    }
  }

  private void clearRepairRequiredWithWatchdog() {
    long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(publisherConfig.getJournalTimeoutMs());
    try (ArchiveOperationWatchdog.Scope ignored = journalWatchdog.arm(
        "archive recovery repair evidence clear", timeoutNanos)) {
      txNumIndex.clearRepairRequired(repairClearPermit);
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
    beginExecutionPositionResourceScope();
    try {
      captureEngine.beginBlockCapture();
      executionTxNumIndex.beginBlock(block.getNum(), source);
    } catch (RuntimeException | Error e) {
      clearActiveBlockResources();
      throw e;
    }
  }

  @Override
  public void beginSystemTx(BlockCapsule block, ArchivePhase phase) {
    if (!enabled) {
      return;
    }
    validateAvailable();
    requireInactiveExecutionContext();
    long previousPositionBytes = reserveNextExecutionPosition();
    ArchiveTxPosition position;
    try {
      position = executionTxNumIndex.allocateSystemTx(block.getNum(), phase);
    } catch (RuntimeException | Error e) {
      try {
        rollbackExecutionPositionReservation(previousPositionBytes);
      } catch (RuntimeException | Error cleanupFailure) {
        addSuppressedSafely(e, cleanupFailure);
      }
      throw e;
    }
    executionContext.enter(position);
  }

  @Override
  public void beginUserTx(BlockCapsule block, int txIndex, TransactionCapsule tx) {
    if (!enabled) {
      return;
    }
    validateAvailable();
    requireInactiveExecutionContext();
    long previousPositionBytes = reserveNextExecutionPosition();
    ArchiveTxPosition position;
    try {
      byte[] txId = (tx == null) ? null : tx.getTransactionId().getBytes();
      position = executionTxNumIndex.allocateUserTx(block.getNum(), txIndex, txId);
    } catch (RuntimeException | Error e) {
      try {
        rollbackExecutionPositionReservation(previousPositionBytes);
      } catch (RuntimeException | Error cleanupFailure) {
        addSuppressedSafely(e, cleanupFailure);
      }
      throw e;
    }
    executionContext.enter(position);
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
    try {
      refreshDiskSampleBeforeJournal();
    } catch (RuntimeException e) {
      markDiskSampleFailure(e);
      throw e;
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
      validateLifecycleAvailable();
      try {
        validateCaptureAvailable();
        acknowledgeCanonicalCommitLocked(token);
      } catch (RuntimeException | Error e) {
        markFatal(e);
        throw e;
      }
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
      validateLifecycleAvailable();
      try {
        validateCaptureAvailable();
        rollbackJournaledBlockLocked(token, false);
      } catch (RuntimeException | Error e) {
        markFatal(e);
        throw e;
      }
    } finally {
      writeLock.unlock();
    }
  }

  @Override
  public void publishSolidifiedBlocks(long solidifiedBlockNum) {
    if (!enabled) {
      return;
    }
    publicationLock.lock();
    try {
      Lock writeLock = consistencyLock.writeLock();
      writeLock.lock();
      try {
        validateLifecycleAvailable();
        try {
          validateCaptureAvailable();
          publishSolidifiedBlocksLocked(solidifiedBlockNum);
        } catch (RuntimeException | Error e) {
          markFatal(e);
          throw e;
        }
      } finally {
        writeLock.unlock();
      }
    } finally {
      publicationLock.unlock();
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
      validateLifecycleAvailable();
      try {
        validateCaptureAvailable();
        // The canonical block thread owns execution-allocation pruning. The background publisher
        // never touches this allocator, so it cannot race beginBlock(N+1).
        executionTxNumIndex.discardBlocksThrough(solidifiedBlockNum);
        updatePublisherLag(solidifiedBlockNum);
      } catch (RuntimeException | Error e) {
        markFatal(e);
        throw e;
      }
    } finally {
      writeLock.unlock();
    }
    try {
      publisher.request(target);
    } catch (RuntimeException | Error e) {
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
      validateLifecycleAvailable();
      try {
        validateCaptureAvailable();
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
        BlockCapsule previousCanonical = validatePublishedTailBeforeJournals(
            blocks, canonicalHeadNum, canonicalBlockProvider, canonicalHeadKnown);
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
      } catch (RuntimeException | Error e) {
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
      validateLifecycleAvailable();
      try {
        validateCaptureAvailable();
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
      validateAvailable();
      ArchiveInFlightBlock block = inFlightBlocks.firstEntry().getValue();
      validateInFlightVersionHead(block);
      if (unifiedBackend == null) {
        publishInFlightBlock(block);
      } else {
        publishInFlightBlockWithActiveResourcesLocked(block);
      }
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
      publicationLock.lock();
      try {
        if (unifiedBackend == null) {
          return publishNextTargetWithConsistencyLock(target);
        }
        ArchiveInFlightBlock block;
        long[] publicationBytes = new long[1];
        Lock writeLock = consistencyLock.writeLock();
        writeLock.lock();
        try {
          validateAvailable();
          validatePublishTargetLocked(target);
          if (inFlightBlocks.isEmpty()
              || inFlightBlocks.firstKey() > target.getBlockNum()) {
            return true;
          }
          block = inFlightBlocks.firstEntry().getValue();
          validateInFlightVersionHead(block);
          publicationBytes[0] = beginActivePublication(block);
        } finally {
          writeLock.unlock();
        }

        // The mutation lease prevents fork/unwind and publicationLock prevents another publisher.
        // Ordinary commits may reach the independent tail-journal Java lock while publication is
        // in progress; RocksDB/WAL scheduling remains an engine-level shared resource.
        try {
          publishInFlightBlock(block, updatedBytes -> {
            updateActivePublication(block, publicationBytes[0], updatedBytes);
            publicationBytes[0] = updatedBytes;
          });
        } finally {
          endActivePublication(publicationBytes[0]);
        }

        writeLock.lock();
        try {
          if (inFlightBlocks.isEmpty()
              || inFlightBlocks.firstEntry().getValue() != block) {
            throw new ArchiveException(
                "archive in-flight head changed during serialized publication");
          }
          inFlightBlocks.remove(block.getRange().getBlockNum());
          removeInFlightVersionHead(block);
          removeInFlightUsage(block);
          updatePublisherLag(target.getBlockNum());
          return inFlightBlocks.isEmpty()
              || inFlightBlocks.firstKey() > target.getBlockNum();
        } finally {
          writeLock.unlock();
        }
      } finally {
        publicationLock.unlock();
      }
    }
  }

  private boolean publishNextTargetWithConsistencyLock(ArchivePublishTarget target) {
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

  private BlockCapsule validatePublishedTailBeforeJournals(
      List<ArchiveInFlightBlock> blocks, long canonicalHeadNum,
      LongFunction<BlockCapsule> canonicalBlockProvider, boolean canonicalHeadKnown) {
    if (blocks.isEmpty() && pendingPublishedJournals.isEmpty()) {
      return null;
    }
    long publishedHead = txNumIndex.getLastArchivedBlock();
    if (publishedHead < 0) {
      if (!pendingPublishedJournals.isEmpty()) {
        throw new ArchiveException(
            "published archive journals exist without a published archive head");
      }
      return null;
    }
    if (canonicalHeadKnown && publishedHead > canonicalHeadNum) {
      throw new ArchiveException("published archive head " + publishedHead
          + " is after canonical head " + canonicalHeadNum);
    }
    ArchiveBlockRange published = txNumIndex.getBlockRange(publishedHead)
        .orElseThrow(() -> new ArchiveException(
            "published archive head has no block range " + publishedHead));
    BlockCapsule canonical = canonicalBlockProvider.apply(publishedHead);
    if (canonical == null) {
      throw new ArchiveException("published archive head " + publishedHead
          + " has no canonical block");
    }
    if (canonical.getNum() != publishedHead) {
      throw new ArchiveException("published archive head " + publishedHead
          + " resolved to canonical block " + canonical.getNum());
    }
    if (!Arrays.equals(canonical.getBlockId().getBytes(), published.getBlockHash())) {
      throw new ArchiveException("published archive head " + publishedHead
          + " hash mismatch with canonical block before in-flight journal reconciliation");
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
    publishInFlightBlock(block, IGNORE_PUBLICATION_BYTES);
  }

  private void publishInFlightBlock(ArchiveInFlightBlock block,
      LongConsumer publicationBytesObserver) {
    long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(publisherConfig.getPublishTimeoutMs());
    try (ArchiveOperationWatchdog.Scope ignored = publicationWatchdog.arm(
        "archive block " + block.getRange().getBlockNum() + " publication", timeoutNanos)) {
      publishInFlightBlockWatched(block, publicationBytesObserver);
    }
  }

  private void publishInFlightBlockWithActiveResourcesLocked(ArchiveInFlightBlock block) {
    long[] publicationBytes = {beginActivePublication(block)};
    try {
      publishInFlightBlock(block, updatedBytes -> {
        updateActivePublication(block, publicationBytes[0], updatedBytes);
        publicationBytes[0] = updatedBytes;
      });
    } finally {
      endActivePublication(publicationBytes[0]);
    }
  }

  private void publishInFlightBlockWatched(ArchiveInFlightBlock block,
      LongConsumer publicationBytesObserver) {
    ArchiveBlockRange range = block.getRange();
    if (block.getJournalState() != ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED) {
      throw new ArchiveException("cannot publish unacknowledged archive journal block "
          + range.getBlockNum());
    }
    if (unifiedBackend != null) {
      long startedNanos = ArchiveMetrics.startTimer();
      try {
        ArchiveBlockRange publishedRange =
            unifiedBackend.publishBlock(block, publicationBytesObserver);
        validatePublishedRange(range, publishedRange);
        inFlightStore.onBlockPublished(range.getBlockNum());
        ArchiveMetrics.publishFinished(startedNanos, true);
        return;
      } catch (RuntimeException | Error e) {
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
    } catch (RuntimeException | Error e) {
      ArchiveMetrics.publishFinished(startedNanos, false);
      if (!temporalPublished) {
        try {
          if (indexPublished) {
            txNumIndex.unwindBlock(range.getBlockNum());
          } else {
            txNumIndex.abortBlock(range.getBlockNum());
          }
        } catch (RuntimeException | Error cleanupFailure) {
          addSuppressedSafely(e, cleanupFailure);
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
    boolean lifecycleAvailable = false;
    boolean executionCommitted = false;
    try {
      validateLifecycleAvailable();
      lifecycleAvailable = true;
      validateCaptureAvailable();
      if (captureEngine.failure().isPresent()) {
        throw captureEngine.failure().get();
      }
      // Keep semantic no-ops in the durable journal. In particular, a tombstone->tombstone record
      // can be the first proof that a key was absent after a mid-chain coverage floor. Filtering
      // here would also put one random temporal read per unique key on the canonical commit path.
      List<ArchiveChangeRecord> records = captureEngine.records();
      ArchiveMetrics.captureBlock(captureEngine.rawRecordCount(), records.size(),
          captureEngine.capturedPayloadBytes(),
          captureEngine.previousValueReads(), captureEngine.previousValueReadFailures(),
          captureEngine.previousValueReadNanos(), captureEngine.accountAssetPrefixRows(),
          captureEngine.accountAssetPointReads(), captureEngine.accountAssetLookups(),
          captureEngine.accountAssetLookupNanos());
      ArchiveBlockRange range = executionTxNumIndex.commitBlock(
          block.getNum(), block.getBlockId().getBytes(), userTxCount, schemaChecksum);
      executionCommitted = true;
      List<ArchiveTxPosition> positions = positionsOf(range, executionTxNumIndex);
      ArchiveInFlightBlock journal = ArchiveInFlightBlock.fromOwnedLists(
          range, positions, records);
      rememberInFlight(journal);
      return journal.getJournalToken();
    } catch (RuntimeException | Error e) {
      try {
        if (executionCommitted) {
          executionTxNumIndex.unwindBlock(block.getNum());
        } else {
          executionTxNumIndex.abortBlock(block.getNum());
        }
      } catch (RuntimeException | Error cleanupFailure) {
        addSuppressedSafely(e, cleanupFailure);
      }
      if (lifecycleAvailable) {
        markFatal(e);
      }
      throw e;
    } finally {
      clearExecutionContextIfCurrent();
      clearActiveBlockResources();
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
    deleteInFlightJournal(block,
        "archive journal rollback for block " + token.getBlockNum());
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
    acknowledgeInFlightJournal(acknowledged,
        "archive journal acknowledgement for block " + token.getBlockNum());
    inFlightBlocks.put(token.getBlockNum(), acknowledged);
  }

  private Optional<DomainValue> latestInFlight(WrappedByteArray key) {
    Deque<InFlightVersion> versions = inFlightVersions.get(key);
    return versions == null || versions.isEmpty()
        ? Optional.empty() : Optional.of(versions.peekLast().value);
  }

  private Optional<DomainValue> latestWithInFlight(ArchiveTemporalReadView startupView,
      ArchiveDomain domain, byte[] canonicalKey) {
    Deque<InFlightVersion> versions = inFlightVersions.get(latestKey(domain, canonicalKey));
    return versions == null || versions.isEmpty()
        ? startupView.latest(domain, canonicalKey)
        : Optional.of(versions.peekLast().value);
  }

  private void rememberInFlight(ArchiveInFlightBlock block) {
    validateInFlightAppend(block);
    runJournalWrite("archive journal append for block " + block.getRange().getBlockNum(),
        () -> inFlightStore.putBlock(block));
    rememberInFlightInMemory(block);
  }

  private void rememberInFlightInMemory(ArchiveInFlightBlock block) {
    inFlightBlocks.put(block.getRange().getBlockNum(), block);
    appendInFlightVersions(block);
    addInFlightUsage(block);
  }

  private void runJournalWrite(String operation, Runnable write) {
    long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(publisherConfig.getJournalTimeoutMs());
    try (ArchiveOperationWatchdog.Scope ignored = journalWatchdog.arm(operation, timeoutNanos)) {
      write.run();
    }
  }

  private void deleteInFlightJournal(ArchiveInFlightBlock block, String operation) {
    if (unifiedBackend == null) {
      runJournalWrite(operation,
          () -> inFlightStore.deleteBlock(block.getRange().getBlockNum()));
      return;
    }
    long workspaceBytes = estimatedJournalDeleteWorkspace(block);
    beginActiveJournalMutation(block, workspaceBytes, "delete");
    try {
      runJournalWrite(operation, () -> inFlightStore.deleteLoadedBlock(block));
    } finally {
      endActiveJournalMutation(workspaceBytes);
    }
  }

  private void acknowledgeInFlightJournal(ArchiveInFlightBlock block, String operation) {
    if (unifiedBackend == null) {
      runJournalWrite(operation, () -> inFlightStore.acknowledgeBlock(block));
      return;
    }
    long workspaceBytes = estimatedJournalAcknowledgementWorkspace(block);
    beginActiveJournalMutation(block, workspaceBytes, "acknowledgement");
    try {
      runJournalWrite(operation, () -> inFlightStore.acknowledgeLoadedBlock(block));
    } finally {
      endActiveJournalMutation(workspaceBytes);
    }
  }

  private void beginActiveJournalMutation(ArchiveInFlightBlock block, long bytes, String action) {
    synchronized (backlogMonitor) {
      if (activeJournalMutationBytes != 0L) {
        throw new IllegalStateException("archive journal resource scope is already active");
      }
      long projectedBytes = addSaturated(
          addSaturated(inFlightRetainedBytes,
              Math.max(inFlightPublicationBytes,
                  addSaturated(activePublicationBytes, bytes))),
          addSaturated(activeCaptureBytes, activeExecutionPositionBytes));
      if (projectedBytes > publisherConfig.getHardInFlightBytes()) {
        throw new ArchiveException(
            "archive journal " + action + " would exceed hard resource watermark "
                + publisherConfig.getHardInFlightBytes() + " for block "
                + block.getRange().getBlockNum() + ": projectedResourceBytes="
                + projectedBytes + ", journalWorkspaceBytes=" + bytes);
      }
      activeJournalMutationBytes = bytes;
      refreshInFlightResourceBytesLocked();
      backlogMonitor.notifyAll();
    }
    updateInFlightMetrics();
  }

  private void endActiveJournalMutation(long bytes) {
    synchronized (backlogMonitor) {
      if (activeJournalMutationBytes != bytes) {
        throw new IllegalStateException("archive journal resource accounting mismatch");
      }
      activeJournalMutationBytes = 0L;
      refreshInFlightResourceBytesLocked();
      backlogMonitor.notifyAll();
    }
    updateInFlightMetrics();
  }

  private static long estimatedJournalDeleteWorkspace(ArchiveInFlightBlock block) {
    // Compare-and-delete materializes one Java payload and RocksDB JNI's native PinnableSlice.
    return addSaturated(JOURNAL_MUTATION_BASE_ALLOWANCE_BYTES,
        multiplySaturated(block.encodedBlockBytes(), 2L));
  }

  private static long estimatedJournalAcknowledgementWorkspace(ArchiveInFlightBlock block) {
    // Proof verification uses one Java payload plus RocksDB JNI's native PinnableSlice.
    return addSaturated(JOURNAL_MUTATION_BASE_ALLOWANCE_BYTES,
        multiplySaturated(block.encodedBlockBytes(), 2L));
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
      long blockNum = block.getRange().getBlockNum();
      if (oldestInFlightBlock < 0L || blockNum < oldestInFlightBlock) {
        oldestInFlightBlock = blockNum;
      }
      inFlightRecordCount = addSaturated(inFlightRecordCount, block.getRecords().size());
      inFlightRetainedBytes = addSaturated(
          inFlightRetainedBytes, block.estimatedRetainedBytes());
      addPublicationFootprint(blockResourceFootprintBytes(block));
      refreshInFlightResourceBytesLocked();
      backlogMonitor.notifyAll();
    }
    updateInFlightMetrics();
  }

  private void removeInFlightUsage(ArchiveInFlightBlock block) {
    synchronized (backlogMonitor) {
      inFlightBlockCount = inFlightBlocks.size();
      oldestInFlightBlock = inFlightBlocks.isEmpty() ? -1L : inFlightBlocks.firstKey();
      inFlightRecordCount = subtractChecked(
          inFlightRecordCount, block.getRecords().size(), "records");
      inFlightRetainedBytes = subtractChecked(
          inFlightRetainedBytes, block.estimatedRetainedBytes(), "bytes");
      removePublicationFootprint(blockResourceFootprintBytes(block));
      refreshInFlightResourceBytesLocked();
      backlogMonitor.notifyAll();
    }
    updateInFlightMetrics();
  }

  private void updateInFlightMetrics() {
    ArchiveMetrics.setInFlight(
        inFlightBlockCount, inFlightRecordCount, inFlightRetainedBytes, inFlightResourceBytes);
    ArchiveMetrics.setOldestInFlightBlock(
        oldestInFlightBlock);
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
    validateInFlightAppendResources(block);
    validateInFlightAppendSequence(block);
  }

  private void validateInFlightAppendResources(ArchiveInFlightBlock block) {
    long blockNum = block.getRange().getBlockNum();
    ArchiveCoordinates.requireBlockNum(blockNum, "archive in-flight block number");
    if (inFlightBlocks.size() >= maxInFlightBlocks) {
      throw new ArchiveException("archive in-flight buffer reached its cap of " + maxInFlightBlocks
          + " committed-not-solidified blocks; refusing to append block " + blockNum);
    }
    if (unifiedBackend != null) {
      unifiedBackend.validatePublicationAdmission(block);
    }
    long projectedRetainedBytes = addSaturated(
        inFlightRetainedBytes, block.estimatedRetainedBytes());
    long candidatePublicationBytes = blockResourceFootprintBytes(block);
    long projectedPublicationBytes = Math.max(
        inFlightPublicationBytes, candidatePublicationBytes);
    long projectedSteadyBytes = addSaturated(
        projectedRetainedBytes, projectedPublicationBytes);
    // An async head publication can coexist with the tail journal's Java/native encoding. The
    // candidate publication estimate conservatively bounds that journal-write working set.
    long projectedAppendBytes = addSaturated(
        projectedRetainedBytes,
        addSaturated(activePublicationBytes, candidatePublicationBytes));
    long projectedBytes = Math.max(
        inFlightResourceBytes, Math.max(projectedSteadyBytes, projectedAppendBytes));
    if (projectedBytes > publisherConfig.getHardInFlightBytes()) {
      throw new ArchiveException("archive in-flight buffer would exceed hard resource watermark "
          + publisherConfig.getHardInFlightBytes() + " while appending block " + blockNum
          + ": projectedResourceBytes=" + projectedBytes);
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
    long usableSpace = cachedUsableSpaceForJournal();
    if (usableSpace < requiredFree) {
      throw new ArchiveException("archive journal filesystem cannot reserve the next durable write"
          + " while appending block " + blockNum + ": requiredFree=" + requiredFree
          + ", diskFree=" + usableSpace);
    }
  }

  private long blockPublicationBytes(ArchiveInFlightBlock block) {
    return unifiedBackend == null
        ? 0L : unifiedBackend.estimatedPublicationRetainedBytes(block);
  }

  private long blockResourceFootprintBytes(ArchiveInFlightBlock block) {
    if (unifiedBackend == null) {
      return 0L;
    }
    return Math.max(blockPublicationBytes(block), estimatedJournalDeleteWorkspace(block));
  }

  private long beginActivePublication(ArchiveInFlightBlock block) {
    long bytes = blockPublicationBytes(block);
    synchronized (backlogMonitor) {
      if (activePublicationBytes != 0L) {
        throw new IllegalStateException("archive publication resource scope is already active");
      }
      activePublicationBytes = bytes;
      refreshInFlightResourceBytesLocked();
      backlogMonitor.notifyAll();
    }
    updateInFlightMetrics();
    return bytes;
  }

  private void updateActivePublication(ArchiveInFlightBlock block,
      long expectedBytes, long updatedBytes) {
    if (updatedBytes < expectedBytes) {
      throw new IllegalStateException("archive publication resource estimate regressed");
    }
    Lock writeLock = consistencyLock.writeLock();
    writeLock.lock();
    try {
      if (inFlightBlocks.isEmpty() || inFlightBlocks.firstEntry().getValue() != block) {
        throw new ArchiveException(
            "archive in-flight head changed during publication resource admission");
      }
      synchronized (backlogMonitor) {
        if (activePublicationBytes != expectedBytes) {
          throw new IllegalStateException("archive publication resource accounting mismatch");
        }
        long projectedBytes = addSaturated(
            addSaturated(inFlightRetainedBytes,
                Math.max(inFlightPublicationBytes,
                    addSaturated(updatedBytes, activeJournalMutationBytes))),
            addSaturated(activeCaptureBytes, activeExecutionPositionBytes));
        if (projectedBytes > publisherConfig.getHardInFlightBytes()) {
          throw new ArchiveException(
              "archive state-aware publication would exceed hard resource watermark "
                  + publisherConfig.getHardInFlightBytes() + " for block "
                  + block.getRange().getBlockNum() + ": projectedResourceBytes="
                  + projectedBytes + ", publicationBytes=" + updatedBytes);
        }
        activePublicationBytes = updatedBytes;
        refreshInFlightResourceBytesLocked();
        backlogMonitor.notifyAll();
      }
      updateInFlightMetrics();
    } finally {
      writeLock.unlock();
    }
  }

  private void endActivePublication(long bytes) {
    Lock writeLock = consistencyLock.writeLock();
    writeLock.lock();
    try {
      synchronized (backlogMonitor) {
        if (activePublicationBytes != bytes) {
          throw new IllegalStateException("archive publication resource accounting mismatch");
        }
        activePublicationBytes = 0L;
        refreshInFlightResourceBytesLocked();
        backlogMonitor.notifyAll();
      }
      updateInFlightMetrics();
    } finally {
      writeLock.unlock();
    }
  }

  private void refreshInFlightResourceBytesLocked() {
    long backlogAndPublication = addSaturated(inFlightRetainedBytes,
        Math.max(inFlightPublicationBytes,
            addSaturated(activePublicationBytes, activeJournalMutationBytes)));
    inFlightResourceBytes = addSaturated(
        backlogAndPublication,
        addSaturated(activeCaptureBytes, activeExecutionPositionBytes));
  }

  private void reserveActiveCaptureBytes(long bytes) {
    if (bytes < 0L) {
      throw new IllegalArgumentException("archive capture resource bytes must be non-negative");
    }
    boolean cleared = false;
    synchronized (backlogMonitor) {
      if (activeCaptureBytes == bytes) {
        return;
      }
      long previousBytes = activeCaptureBytes;
      long backlogAndPublication = addSaturated(inFlightRetainedBytes,
          Math.max(inFlightPublicationBytes,
              addSaturated(activePublicationBytes, activeJournalMutationBytes)));
      long projectedBytes = addSaturated(
          backlogAndPublication,
          addSaturated(bytes, activeExecutionPositionBytes));
      if (projectedBytes > publisherConfig.getHardInFlightBytes()) {
        throw new ArchiveException(
            "archive concurrent capture would exceed hard resource watermark "
                + publisherConfig.getHardInFlightBytes() + ": projectedResourceBytes="
                + projectedBytes + ", captureBytes=" + bytes);
      }
      activeCaptureBytes = bytes;
      refreshInFlightResourceBytesLocked();
      if (bytes < previousBytes) {
        backlogMonitor.notifyAll();
      }
      cleared = bytes == 0L;
    }
    if (cleared) {
      // Capture changes are intentionally absent from the per-write metrics hot path. Clearing is
      // a block boundary, so refresh the gauge once after commit/abort/close.
      updateInFlightMetrics();
    }
  }

  private void reserveActiveCaptureRecords(long records) {
    if (records < 0L) {
      throw new IllegalArgumentException("archive capture record count must be non-negative");
    }
    synchronized (backlogMonitor) {
      if (activeCaptureRecordCount == records) {
        return;
      }
      long previousRecords = activeCaptureRecordCount;
      long projectedRecords = addSaturated(inFlightRecordCount, records);
      if (projectedRecords > publisherConfig.getHardInFlightRecords()) {
        throw new ArchiveException(
            "archive concurrent capture would exceed hard record watermark "
                + publisherConfig.getHardInFlightRecords() + ": projectedRecords="
                + projectedRecords + ", captureRecords=" + records);
      }
      activeCaptureRecordCount = records;
      if (records < previousRecords) {
        backlogMonitor.notifyAll();
      }
    }
  }

  private void requireInactiveExecutionContext() {
    if (executionContext.hasCurrent()) {
      throw new ArchiveException("archive execution context is already active");
    }
  }

  private void beginExecutionPositionResourceScope() {
    long baseBytes = ArchiveResourceEstimator.estimatedInFlightBlockBaseBytes();
    synchronized (backlogMonitor) {
      if (activeExecutionPositionBytes != 0L) {
        throw new IllegalStateException(
            "archive execution-position resource scope is already active");
      }
      validateExecutionPositionResourceBytesLocked(baseBytes);
      activeExecutionPositionBytes = baseBytes;
      refreshInFlightResourceBytesLocked();
    }
  }

  private long reserveNextExecutionPosition() {
    long baseBytes = ArchiveResourceEstimator.estimatedInFlightBlockBaseBytes();
    synchronized (backlogMonitor) {
      if (activeExecutionPositionBytes < baseBytes) {
        throw new IllegalStateException(
            "archive execution-position resource scope is not active");
      }
      long previousBytes = activeExecutionPositionBytes;
      long updatedBytes = addSaturated(
          previousBytes, ArchiveResourceEstimator.estimatedTxPositionRetainedBytes());
      validateExecutionPositionResourceBytesLocked(updatedBytes);
      activeExecutionPositionBytes = updatedBytes;
      refreshInFlightResourceBytesLocked();
      return previousBytes;
    }
  }

  private void rollbackExecutionPositionReservation(long previousBytes) {
    synchronized (backlogMonitor) {
      long expectedBytes = addSaturated(
          previousBytes, ArchiveResourceEstimator.estimatedTxPositionRetainedBytes());
      if (activeExecutionPositionBytes != expectedBytes) {
        throw new IllegalStateException(
            "archive execution-position resource accounting mismatch");
      }
      activeExecutionPositionBytes = previousBytes;
      refreshInFlightResourceBytesLocked();
      backlogMonitor.notifyAll();
    }
  }

  private void validateExecutionPositionResourceBytesLocked(long positionBytes) {
    long backlogAndPublication = addSaturated(inFlightRetainedBytes,
        Math.max(inFlightPublicationBytes,
            addSaturated(activePublicationBytes, activeJournalMutationBytes)));
    long projectedBytes = addSaturated(backlogAndPublication,
        addSaturated(activeCaptureBytes, positionBytes));
    if (projectedBytes > publisherConfig.getHardInFlightBytes()) {
      throw new ArchiveException(
          "archive execution positions would exceed hard resource watermark "
              + publisherConfig.getHardInFlightBytes() + ": projectedResourceBytes="
              + projectedBytes + ", positionBytes=" + positionBytes);
    }
  }

  private void clearExecutionPositionResourceScope() {
    boolean cleared = false;
    synchronized (backlogMonitor) {
      if (activeExecutionPositionBytes != 0L) {
        activeExecutionPositionBytes = 0L;
        refreshInFlightResourceBytesLocked();
        backlogMonitor.notifyAll();
        cleared = true;
      }
    }
    if (cleared) {
      updateInFlightMetrics();
    }
  }

  private void clearActiveBlockResources() {
    try {
      captureEngine.clear();
    } finally {
      clearExecutionPositionResourceScope();
    }
  }

  private void addPublicationFootprint(long bytes) {
    if (bytes == 0L) {
      return;
    }
    inFlightPublicationFootprints.merge(bytes, 1, Integer::sum);
    inFlightPublicationBytes = inFlightPublicationFootprints.lastKey();
  }

  private void removePublicationFootprint(long bytes) {
    if (bytes == 0L) {
      return;
    }
    Integer count = inFlightPublicationFootprints.get(bytes);
    if (count == null) {
      throw new IllegalStateException("archive publication footprint accounting underflow");
    }
    if (count == 1) {
      inFlightPublicationFootprints.remove(bytes);
    } else {
      inFlightPublicationFootprints.put(bytes, count - 1);
    }
    inFlightPublicationBytes = inFlightPublicationFootprints.isEmpty()
        ? 0L : inFlightPublicationFootprints.lastKey();
  }

  private void validateInFlightAppendSequence(ArchiveInFlightBlock block) {
    long blockNum = block.getRange().getBlockNum();
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

  private long cachedUsableSpaceForJournal() {
    long usableSpace = lastUsableSpaceBytes;
    if (lastDiskSampleNanos == Long.MIN_VALUE) {
      throw new ArchiveException("archive filesystem capacity has not been sampled");
    }
    return usableSpace;
  }

  /** Refreshes outside the consistency lock when the cached capacity is stale or near any cap. */
  private void refreshDiskSampleBeforeJournal() {
    long usableSpace = sampleUsableSpaceBytes();
    long maximumJournalHeadroom = Math.max(
        MIN_JOURNAL_DISK_HEADROOM_BYTES,
        multiplySaturated(publisherConfig.getHardInFlightBytes(), 2L));
    long refreshThreshold = addSaturated(
        publisherConfig.getHardMinFreeBytes(), maximumJournalHeadroom);
    if (usableSpace <= refreshThreshold) {
      sampleUsableSpaceBytes(true);
    }
  }

  private long sampleUsableSpaceBytes(boolean force) {
    long now = System.nanoTime();
    long sampledAt = lastDiskSampleNanos;
    if (!force && sampledAt != Long.MIN_VALUE
        && now - sampledAt < DISK_SAMPLE_INTERVAL_NANOS) {
      return lastUsableSpaceBytes;
    }
    ArchiveDiskSpaceSampler.Sample sample =
        diskSpaceSampler.sample(DISK_SAMPLE_TIMEOUT_NANOS);
    return applyDiskSample(sample);
  }

  private long applyDiskSample(ArchiveDiskSpaceSampler.Sample sample) {
    synchronized (diskSampleMonitor) {
      if (sample.getGeneration() > lastDiskSampleGeneration) {
        lastUsableSpaceBytes = sample.getUsableBytes();
        lastDiskSampleNanos = sample.getSampledAtNanos();
        lastDiskSampleGeneration = sample.getGeneration();
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

  @Override
  public void abortBlock(BlockCapsule block) {
    if (!enabled) {
      return;
    }
    Lock writeLock = consistencyLock.writeLock();
    writeLock.lock();
    try {
      boolean lifecycleAvailable = false;
      Throwable failure = null;
      try {
        validateLifecycleAvailable();
        lifecycleAvailable = true;
        validateCaptureAvailable();
      } catch (RuntimeException | Error e) {
        failure = e;
      }
      try {
        clearExecutionContextIfCurrent();
      } catch (RuntimeException | Error e) {
        if (failure == null) {
          failure = e;
        } else {
          addSuppressedSafely(failure, e);
        }
      }
      Throwable abortFailure = null;
      try {
        executionTxNumIndex.abortBlock(block.getNum());
      } catch (RuntimeException | Error e) {
        abortFailure = e;
        if (failure == null) {
          failure = e;
        } else {
          addSuppressedSafely(failure, e);
        }
      } finally {
        clearActiveBlockResources();
      }
      if (failure != null) {
        if (lifecycleAvailable) {
          markFatal(failure);
        } else if (abortFailure != null) {
          markFatal(abortFailure);
        }
        rethrowOperationFailure(failure);
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
    if (mutationBarrier.isExclusiveHeldByCurrentThread()) {
      unwindBlockWithMutationLease(block);
      return;
    }
    try (ArchiveMutationLease ignored = mutationBarrier.acquireExclusive()) {
      unwindBlockWithMutationLease(block);
    }
  }

  private void unwindBlockWithMutationLease(BlockCapsule block) {
    mutationBarrier.requireExclusiveHeldByCurrentThread();
    Lock writeLock = consistencyLock.writeLock();
    writeLock.lock();
    try {
      validateLifecycleAvailable();
      try {
        validateCaptureAvailable();
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
          deleteInFlightJournal(inFlight,
              "archive journal unwind for block " + block.getNum());
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
            clearActiveBlockResources();
            return;
          }
          throw new ArchiveException("cannot unwind block " + block.getNum()
              + ": not in archive in-flight head");
        }
        clearActiveBlockResources();
      } catch (RuntimeException | Error e) {
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
    validateLifecycleAvailable();
    validateCaptureAvailable();
  }

  private void validateLifecycleAvailable() {
    lifecycle.validateCurrentOperation();
  }

  private void validateCaptureAvailable() {
    if (!ArchiveCaptureHolder.isCurrent(captureEngine)) {
      throw new ArchiveException("archive capture engine is not active");
    }
  }

  @Override
  public ArchiveStateReader openReader(ArchiveStatePoint point) throws ArchiveReaderException {
    return openResolvedReader(context -> {
      requireHistoricalPoint(point);
      return point;
    });
  }

  @Override
  public ArchiveStateReader openBlockEndReader(long blockNum, byte[] canonicalBlockHash)
      throws ArchiveReaderException {
    requireHistoricalBlockNum(blockNum);
    return openResolvedReader(context -> {
      ArchiveBlockRange range = requireBlockRange(context, blockNum);
      validateCanonicalRangeHash(range, canonicalBlockHash);
      return ArchiveStatePoint.blockEnd(
          blockNum, canonicalBlockHash, range.getFinalizeTxNum());
    });
  }

  @Override
  public ArchiveStateReader openBlockEndReader(long blockNum) throws ArchiveReaderException {
    requireHistoricalBlockNum(blockNum);
    return openResolvedReader(context -> {
      ArchiveBlockRange range = requireBlockRange(context, blockNum);
      return ArchiveStatePoint.blockEnd(
          blockNum, range.getBlockHash(), range.getFinalizeTxNum());
    });
  }

  @Override
  public ArchiveStateReader openBlockEndReader(long blockNum,
      LongFunction<byte[]> canonicalBlockHashProvider) throws ArchiveReaderException {
    if (canonicalBlockHashProvider == null) {
      throw new NullPointerException("canonicalBlockHashProvider");
    }
    requireHistoricalBlockNum(blockNum);
    return openResolvedReader(context -> {
      ArchiveBlockRange range = requireBlockRange(context, blockNum);
      byte[] canonicalBlockHash = resolveExternal(
          () -> canonicalBlockHashProvider.apply(blockNum));
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
      long blockNum = resolveExternal(blockNumProvider::getAsLong);
      requireHistoricalBlockNum(blockNum);
      ArchiveBlockRange range = requireBlockRange(context, blockNum);
      byte[] canonicalBlockHash = resolveExternal(
          () -> canonicalBlockHashProvider.apply(blockNum));
      validateCanonicalRangeHash(range, canonicalBlockHash);
      return ArchiveStatePoint.blockEnd(
          blockNum, canonicalBlockHash, range.getFinalizeTxNum());
    });
  }

  @Override
  public ArchiveStateReader openBlockEndReader(LongSupplier blockNumProvider)
      throws ArchiveReaderException {
    if (blockNumProvider == null) {
      throw new NullPointerException("blockNumProvider");
    }
    return openResolvedReader(context -> {
      long blockNum = resolveExternal(blockNumProvider::getAsLong);
      requireHistoricalBlockNum(blockNum);
      ArchiveBlockRange range = requireBlockRange(context, blockNum);
      return ArchiveStatePoint.blockEnd(
          blockNum, range.getBlockHash(), range.getFinalizeTxNum());
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
      BlockCapsule canonicalBlock = resolveExternal(
          () -> canonicalBlockProvider.apply(immutableRequestedHash));
      if (canonicalBlock == null
          || !Arrays.equals(canonicalBlock.getBlockId().getBytes(), immutableRequestedHash)) {
        throw new ArchiveReaderException(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE,
            "archive block hash is not canonical");
      }
      long blockNum = canonicalBlock.getNum();
      requireHistoricalBlockNum(blockNum);
      ArchiveBlockRange range = requireBlockRange(context, blockNum);
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
      // In-memory oracles use a logical three-row cost; Unified counts native point reads.
      recordEstimatedIndexBackendReads(context, 3L);
      Optional<ArchiveTransactionLocation> resolved = queryIndexRead(
          "archive transaction index lookup", () -> txNumIndex.findTransactionByTxId(txId));
      if (!resolved.isPresent()) {
        throw new ArchiveReaderException(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE,
            "transaction not in archive");
      }
      ArchiveTxPosition position = resolved.get().getPosition();
      ArchiveBlockRange range = resolved.get().getRange();
      long txNum = position.getTxNum();
      if (txNum < 1) {
        throw new ArchiveReaderException(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE,
            "transaction has no pre-state archive point");
      }
      if (position.getPhase() != ArchivePhase.USER_TX
          || expectedBlockNum >= 0 && position.getBlockNum() != expectedBlockNum
          || !Arrays.equals(position.getTxId(), txId)) {
        throw new ArchiveReaderException(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE,
            "archive transaction position mismatch");
      }
      long blockNum = position.getBlockNum();
      if (txNum < range.getFirstTxNum() || txNum > range.getLastTxNum()) {
        throw new ArchiveReaderException(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE,
            "archive transaction range mismatch");
      }
      byte[] resolvedBlockHash = canonicalBlockHashProvider == null
          ? canonicalBlockHash
          : resolveExternal(() -> canonicalBlockHashProvider.apply(blockNum));
      validateCanonicalRangeHash(range, resolvedBlockHash);
      return ArchiveStatePoint.txBefore(blockNum, resolvedBlockHash, txNum - 1);
    });
  }

  private static void requireHistoricalBlockNum(long blockNum) throws ArchiveReaderException {
    if (blockNum < 0L || blockNum > ArchiveCoordinates.MAX_COORDINATE) {
      throw new ArchiveReaderException(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE,
          "archive block number is outside the historical coordinate range");
    }
  }

  private static void requireHistoricalPoint(ArchiveStatePoint point)
      throws ArchiveReaderException {
    if (point == null) {
      throw new ArchiveReaderException(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE,
          "no resolved archive state point");
    }
    requireHistoricalBlockNum(point.getBlockNum());
    if (point.getTxNum() < 0L || point.getTxNum() > ArchiveCoordinates.MAX_COORDINATE) {
      throw new ArchiveReaderException(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE,
          "archive txNum is outside the historical coordinate range");
    }
  }

  private static <T> T queryIndexRead(String operation, Supplier<T> read)
      throws ArchiveReaderException {
    try {
      return read.get();
    } catch (ArchiveException e) {
      throw classifyOpeningStorageFailure(operation + " failed", e);
    }
  }

  private static <T> T resolveExternal(Supplier<T> resolver) {
    try (QueryContextHolder.Scope ignored = QueryContextHolder.suspend()) {
      return resolver.get();
    }
  }

  private static ArchiveReaderException classifyOpeningStorageFailure(
      String message, ArchiveException failure) {
    ArchiveReaderException.Reason reason = ArchiveReaderException.reasonForStorageFailure(
        failure, ArchiveReaderException.Reason.CORRUPT_INDEX);
    ArchiveReaderException classified = new ArchiveReaderException(reason, message, failure);
    for (Throwable suppressed : failure.getSuppressed()) {
      addSuppressedSafely(classified, suppressed);
    }
    return classified;
  }

  /**
   * Opens the production from-genesis path directly from one unified snapshot. Publication is one
   * atomic RocksDB batch and this path never consults in-flight/live read-through, so the service
   * consistency lock would only serialize snapshot creation behind journal fsync. A mid-chain
   * archive is rejected because public queries require complete from-genesis coverage.
   */
  private ArchiveStateReader openGenesisCompleteUnifiedReader(ArchiveStatePoint point,
      QueryContext queryContext, SnapshotCleanupTracker cleanupTracker)
      throws ArchiveReaderException {
    UnifiedArchiveBackend.ReadSession readSession;
    try {
      readSession = unifiedBackend.openReadSession(queryContext);
    } catch (ArchiveException e) {
      throw new ArchiveReaderException(ArchiveReaderException.Reason.INTERNAL_IO,
          "archive snapshot open failed", e);
    }
    ArchiveStateReader reader = null;
    try {
      try (UnifiedArchiveTxNumIndex.ReadScope ignoredIndex = readSession.bindIndex();
          QueryContextHolder.Scope ignored = QueryContextHolder.attach(queryContext)) {
        boolean completeHistory = queryIndexRead(
            "archive complete-coverage lookup",
            unifiedBackend::hasGenesisCompleteCoverage);
        requireFromGenesisCoverage(completeHistory);
        ArchiveBlockRange committedRange = requireBlockRange(queryContext, point.getBlockNum());
        validateBlockRangeMarker(readSession, committedRange);
        reader = readerFactory.openSnapshot(
            point, committedRange, readSession.getTemporalView(), queryContext,
            readSession::getBlockRange);
      }
      return reader;
    } catch (RuntimeException | Error | ArchiveReaderException e) {
      if (reader != null) {
        cleanupTracker.record(closeSnapshotOwnerAndSuppress(reader, e));
      } else {
        cleanupTracker.record(closeSnapshotOwnerAndSuppress(readSession, e));
      }
      throw e;
    }
  }

  private static void requireFromGenesisCoverage(long firstArchivedBlock)
      throws ArchiveReaderException {
    requireFromGenesisCoverage(firstArchivedBlock == 0L);
  }

  private static void requireFromGenesisCoverage(boolean completeHistory)
      throws ArchiveReaderException {
    if (!completeHistory) {
      throw new ArchiveReaderException(
          ArchiveReaderException.Reason.HISTORY_UNAVAILABLE,
          "archive public queries require complete from-genesis coverage");
    }
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
    try {
      if (unifiedBackend == null) {
        try (QueryContextHolder.Scope ignored = QueryContextHolder.attach(queryContext)) {
          queryContext.recordBackendRead();
          long firstArchivedBlock = queryIndexRead(
              "archive coverage-floor lookup", txNumIndex::getFirstArchivedBlock);
          requireFromGenesisCoverage(firstArchivedBlock);
        }
      }
    } catch (RuntimeException | Error | ArchiveReaderException e) {
      queryContext.recordFailure(e);
      closeAndSuppress(queryLease, e);
      reportOpeningIntegrityFailure(e);
      throw e;
    }
    ArchiveWorkLease lifecycleLease;
    try {
      lifecycleLease = lifecycle.acquire(ArchiveLifecycle.WorkType.QUERY);
    } catch (RuntimeException | Error e) {
      queryContext.recordFailure(e);
      closeAndSuppress(queryLease, e);
      if (e instanceof Error) {
        throw (Error) e;
      }
      validateAvailableForRead();
      throw new ArchiveReaderException(ArchiveReaderException.Reason.INTERNAL_IO,
          e.getMessage() == null ? "archive query admission failed" : e.getMessage(), e);
    }
    long selectorEpoch;
    try (ArchiveMutationLease selectorLease = acquireQueryMutationLease(queryContext)) {
      selectorEpoch = selectorLease.getEpoch();
    } catch (RuntimeException | Error e) {
      queryContext.recordFailure(e);
      closeAndSuppress(lifecycleLease, e);
      closeAndSuppress(queryLease, e);
      throw e;
    }
    ArchiveSnapshotPermit snapshotPermit;
    try {
      // Reserve the native-snapshot budget before selector resolution. Unified selector reads
      // open short-lived snapshots, so acquiring only for the final reader would let them bypass
      // maxOpenSnapshots and its drain accounting.
      snapshotPermit = queryCoordinator.acquireSnapshot(queryLease);
    } catch (RuntimeException | Error e) {
      queryContext.recordFailure(e);
      closeAndSuppress(lifecycleLease, e);
      closeAndSuppress(queryLease, e);
      if (e instanceof Error) {
        throw (Error) e;
      }
      validateAvailableForRead();
      throw e;
    }
    SnapshotCleanupTracker snapshotCleanupTracker = new SnapshotCleanupTracker();
    ArchiveStatePoint resolvedPoint;
    try {
      validateAvailableForRead();
      queryContext.checkDeadline();
      // Resolve selectors before taking mutation/consistency locks or opening a Unified snapshot.
      // Application callbacks suspend QueryContext separately, so reentrant canonical work cannot
      // inherit query accounting or a snapshot-bound index view.
      try (QueryContextHolder.Scope ignored = QueryContextHolder.attach(queryContext)) {
        resolvedPoint = pointResolver.resolve(queryContext);
      }
    } catch (RuntimeException | Error | ArchiveReaderException e) {
      queryContext.recordFailure(e);
      retainSnapshotPermitIfUncertain(snapshotPermit, e);
      closeAndSuppress(snapshotPermit, e);
      closeAndSuppress(lifecycleLease, e);
      closeAndSuppress(queryLease, e);
      reportOpeningIntegrityFailure(e);
      throw e;
    }
    ArchiveMutationLease mutationLease;
    try {
      mutationLease = acquireQueryMutationLease(queryContext);
    } catch (RuntimeException | Error e) {
      queryContext.recordFailure(e);
      retainSnapshotPermitIfUncertain(snapshotPermit, e);
      closeAndSuppress(snapshotPermit, e);
      closeAndSuppress(lifecycleLease, e);
      closeAndSuppress(queryLease, e);
      throw e;
    }
    try {
      queryContext.checkDeadline();
      if (mutationLease.getEpoch() != selectorEpoch) {
        throw new ArchiveSnapshotInvalidatedException(
            "archive historical selector was invalidated by canonical mutation");
      }
    } catch (RuntimeException | Error e) {
      queryContext.recordFailure(e);
      closeAndSuppress(mutationLease, e);
      retainSnapshotPermitIfUncertain(snapshotPermit, e);
      closeAndSuppress(snapshotPermit, e);
      closeAndSuppress(lifecycleLease, e);
      closeAndSuppress(queryLease, e);
      throw e;
    }
    Lock readLock = consistencyLock.readLock();
    int readHoldCountBefore = consistencyLock.getReadHoldCount();
    boolean lockAcquired = false;
    boolean lockTransferred = false;
    boolean leaseTransferred = false;
    boolean mutationLeaseTransferred = false;
    boolean snapshotPermitTransferred = false;
    Throwable openingFailure = null;
    try {
      try {
        lifecycleLease.start();
      } catch (RuntimeException e) {
        try {
          validateAvailableForRead();
        } catch (ArchiveReaderException unavailable) {
          addSuppressedSafely(unavailable, e);
          throw unavailable;
        }
        throw new ArchiveReaderException(ArchiveReaderException.Reason.INTERNAL_IO,
            e.getMessage() == null ? "archive query start failed" : e.getMessage(), e);
      }
      validateAvailableForRead();
      queryContext.checkDeadline();
      if (unifiedBackend != null) {
        long snapshotEpoch = mutationLease.getEpoch();
        mutationLease.close();
        mutationLeaseTransferred = true;
        ArchiveStateReader snapshotReader = openGenesisCompleteUnifiedReader(
            resolvedPoint, queryContext, snapshotCleanupTracker);
        ManagedArchiveStateReader managed;
        try {
          // Canonical lookup and snapshot construction deliberately run without pinning fork
          // exclusion. Seal the optimistic generation before exposing the reader, and again after
          // response serialization through the managed reader's transport validator.
          validateResponseEpoch(snapshotEpoch, queryContext);
          managed = new ManagedArchiveStateReader(
              snapshotReader, lifecycleLease, queryLease, snapshotPermit,
              ArchiveService.NOOP_MUTATION_LEASE,
              () -> validateResponseEpoch(snapshotEpoch, queryContext), this::markFatal);
        } catch (RuntimeException | Error e) {
          snapshotCleanupTracker.record(closeSnapshotOwnerAndSuppress(snapshotReader, e));
          throw e;
        }
        leaseTransferred = true;
        snapshotPermitTransferred = true;
        return managed;
      }
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
      validateAvailableForRead();
      queryContext.checkDeadline();
      ArchiveBlockRange currentRange = requireBlockRange(
          queryContext, resolvedPoint.getBlockNum());
      // Freeze temporal reads in a snapshot. The canonical epoch is sealed after response
      // serialization, so a long query does not hold the fork barrier while stale results still
      // fail closed before response commit.
      ArchiveTemporalReadView view = temporalStore.openReadView();
      ArchiveStateReader reader;
      try {
        try (QueryContextHolder.Scope ignored = QueryContextHolder.attach(queryContext)) {
          reader = readerFactory.openSnapshot(
              resolvedPoint, currentRange, view, queryLease.getContext());
        }
      } catch (RuntimeException | Error | ArchiveReaderException e) {
        closeAndSuppress(view, e);
        throw e;
      }
      readLock.unlock();
      lockTransferred = true;
      long snapshotEpoch = mutationLease.getEpoch();
      mutationLease.close();
      mutationLeaseTransferred = true;
      ManagedArchiveStateReader managed;
      try {
        managed = new ManagedArchiveStateReader(
            reader, lifecycleLease, queryLease, snapshotPermit,
            ArchiveService.NOOP_MUTATION_LEASE,
            () -> validateResponseEpoch(snapshotEpoch, queryContext), this::markFatal);
      } catch (RuntimeException | Error e) {
        // The reader owns the temporal snapshot; release it if the wrapper ctor fails, so this
        // path cannot leak a RocksDB snapshot that pins SST files.
        closeAndSuppress(reader, e);
        throw e;
      }
      leaseTransferred = true;
      snapshotPermitTransferred = true;
      return managed;
    } catch (RuntimeException | Error | ArchiveReaderException e) {
      openingFailure = e;
      queryContext.recordFailure(e);
      reportOpeningIntegrityFailure(e, snapshotCleanupTracker.isUncertain());
      throw e;
    } finally {
      Throwable cleanupFailure = null;
      if (lockAcquired && !lockTransferred
          && consistencyLock.getReadHoldCount() > readHoldCountBefore) {
        try {
          readLock.unlock();
        } catch (Throwable unlockFailure) {
          cleanupFailure = collectFailure(cleanupFailure, unlockFailure);
        }
      }
      if (snapshotPermit != null && !snapshotPermitTransferred) {
        retainSnapshotPermitIfUncertain(
            snapshotPermit, openingFailure, snapshotCleanupTracker.isUncertain());
        cleanupFailure = closeAndCollect(cleanupFailure, snapshotPermit);
      }
      if (!mutationLeaseTransferred) {
        cleanupFailure = closeAndCollect(cleanupFailure, mutationLease);
      }
      if (!leaseTransferred) {
        cleanupFailure = closeAndCollect(cleanupFailure, lifecycleLease);
        cleanupFailure = closeAndCollect(cleanupFailure, queryLease);
      }
      if (cleanupFailure != null) {
        try {
          queryContext.recordFailure(cleanupFailure);
        } catch (Throwable recordFailure) {
          cleanupFailure = collectFailure(cleanupFailure, recordFailure);
        }
        if (openingFailure != null) {
          addSuppressedSafely(openingFailure, cleanupFailure);
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

  private ArchiveMutationLease acquireQueryMutationLease(QueryContext queryContext) {
    try {
      ArchiveMutationLease lease = mutationBarrier.acquireSharedInterruptibly(
          queryContext.getRemainingNanos());
      if (lease != null) {
        return lease;
      }
      queryContext.checkDeadline();
      throw new HistoricalQueryLimitException(
          HistoricalQueryLimitException.Reason.DEADLINE,
          HistoricalQueryLimitException.Limit.DEADLINE,
          "historical query deadline exceeded while waiting for archive mutation barrier");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HistoricalQueryLimitException(
          HistoricalQueryLimitException.Reason.RESOURCE_EXHAUSTED,
          HistoricalQueryLimitException.Limit.INTERRUPTED,
          "interrupted while waiting for archive mutation barrier");
    }
  }

  private void validateResponseEpoch(long snapshotEpoch, QueryContext queryContext) {
    validateAvailableForResponseSettlement();
    queryContext.checkDeadline();
    try {
      if (!mutationBarrier.requireEpochInterruptibly(
          snapshotEpoch, queryContext.getRemainingNanos())) {
        queryContext.checkDeadline();
        throw new HistoricalQueryLimitException(
            HistoricalQueryLimitException.Reason.DEADLINE,
            HistoricalQueryLimitException.Limit.DEADLINE,
            "historical query deadline exceeded while validating archive snapshot");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HistoricalQueryLimitException(
          HistoricalQueryLimitException.Reason.RESOURCE_EXHAUSTED,
          HistoricalQueryLimitException.Limit.INTERRUPTED,
          ArchiveQueryLimits.UNLIMITED, ArchiveQueryLimits.UNLIMITED,
          "interrupted while validating archive snapshot", e);
    }
    queryContext.checkDeadline();
    validateAvailableForResponseSettlement();
  }

  private void validateAvailableForResponseSettlement() {
    lifecycle.validateAdmittedResponse();
    if (!ArchiveCaptureHolder.isCurrent(captureEngine)) {
      throw new ArchiveException("archive capture engine is not active");
    }
  }

  private void reportOpeningIntegrityFailure(Throwable failure) {
    reportOpeningIntegrityFailure(failure, false);
  }

  private void reportOpeningIntegrityFailure(
      Throwable failure, boolean snapshotReleaseUncertain) {
    if (snapshotReleaseUncertain || ArchiveSnapshotReleaseException.contains(failure)
        || failure instanceof ArchiveReaderException
        && ((ArchiveReaderException) failure).getReason()
            == ArchiveReaderException.Reason.CORRUPT_INDEX) {
      markFatal(failure);
    }
  }

  private static void retainSnapshotPermitIfUncertain(
      ArchiveSnapshotPermit permit, Throwable failure) {
    retainSnapshotPermitIfUncertain(permit, failure, false);
  }

  private static void retainSnapshotPermitIfUncertain(
      ArchiveSnapshotPermit permit, Throwable failure, boolean explicitlyUncertain) {
    if (permit != null
        && (explicitlyUncertain || ArchiveSnapshotReleaseException.contains(failure))) {
      permit.retainAfterUncertainRelease();
    }
  }

  private ArchiveReaderException historyUnavailable(QueryContext context, long blockNum)
      throws ArchiveReaderException {
    recordEstimatedIndexBackendReads(context, 1L);
    long first = queryIndexRead(
        "archive coverage-floor lookup", txNumIndex::getFirstArchivedBlock);
    String suffix = first >= 0 && blockNum < first
        ? "; lowest supported block is " + first
        : "";
    return new ArchiveReaderException(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE,
        "archive history unavailable for block " + blockNum + suffix);
  }

  private ArchiveBlockRange requireBlockRange(QueryContext context, long blockNum)
      throws ArchiveReaderException {
    recordEstimatedIndexBackendReads(context, 1L);
    Optional<ArchiveBlockRange> range = queryIndexRead(
        "archive block-range lookup", () -> txNumIndex.getBlockRange(blockNum));
    if (range.isPresent()) {
      return range.get();
    }
    throw historyUnavailable(context, blockNum);
  }

  private void recordEstimatedIndexBackendReads(QueryContext context, long reads) {
    if (unifiedBackend == null) {
      context.recordBackendReads(reads);
    }
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

  private static void validateBlockRangeMarker(UnifiedArchiveBackend.ReadSession readSession,
      ArchiveBlockRange range) throws ArchiveReaderException {
    try {
      readSession.validateBlockRangeMarker(range);
    } catch (HistoricalQueryLimitException e) {
      throw e;
    } catch (ArchiveException e) {
      throw classifyOpeningStorageFailure(
          "archive block range does not match its temporal commit marker", e);
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
        rethrowCloseFailure(terminalCloseFailure);
        return;
      }
      queryCoordinator.beginDrain();
      lifecycle.beginDrain();
      synchronized (backlogMonitor) {
        backlogMonitor.notifyAll();
      }
      try {
        if (!lifecycle.awaitDrained(closeDrainTimeoutNanos, TimeUnit.NANOSECONDS)) {
          throw new ArchiveException("archive drain timed out with active operations");
        }
        if (!queryCoordinator.awaitDrained(
            closeDrainTimeoutNanos, TimeUnit.NANOSECONDS)) {
          throw new ArchiveException("archive query drain timed out with active readers");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ArchiveException("archive drain interrupted", e);
      }
      if (publisher != null) {
        publisher.beginDrain();
      }
      if (diskSpaceSampler != null) {
        try {
          diskSpaceSampler.close(closeDrainTimeoutNanos);
        } catch (RuntimeException | Error failure) {
          terminalCloseFailure = failure;
          throw failure;
        }
      }
      if (publisher != null) {
        try {
          publisher.close(closeDrainTimeoutNanos, TimeUnit.NANOSECONDS);
        } catch (RuntimeException | Error publisherFailure) {
          // The publisher's failure handler may still be persisting repair evidence. Its worker
          // must be fully stopped before watchdogs and storage adapters can be destroyed.
          terminalCloseFailure = publisherFailure;
          throw publisherFailure;
        }
      }
      try {
        if (!sealAndAwaitFatalTransitions(closeDrainTimeoutNanos)) {
          throw new ArchiveException("archive fatal transition drain timed out");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ArchiveException("archive fatal transition drain interrupted", e);
      }
      try {
        if (!queryCoordinator.close(closeDrainTimeoutNanos, TimeUnit.NANOSECONDS)) {
          throw new ArchiveException("archive query close did not reach CLOSED within timeout");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ArchiveException("archive query close interrupted", e);
      }
      Throwable failure = null;
      failure = closeResource(publicationWatchdog, failure);
      failure = closeResource(journalWatchdog, failure);
      failure = closeResource(fatalController, failure);
      Lock writeLock = consistencyLock.writeLock();
      writeLock.lock();
      try {
        boolean currentCaptureEngine =
            captureEngine != null && ArchiveCaptureHolder.isCurrent(captureEngine);
        if (currentCaptureEngine) {
          executionContext.clear();
        }
        ArchiveCaptureHolder.clearIf(captureEngine);
        // Unified adapters intentionally close in this order. In-flight and temporal do not own
        // the shared DB; the txNum index is the final owner that closes UnifiedArchiveDb.
        failure = closeResource(inFlightStore, failure);
        failure = closeResource(temporalStore, failure);
        failure = closeResource(txNumIndex, failure);
        try {
          lifecycle.markClosed();
        } catch (Throwable lifecycleFailure) {
          failure = collectFailure(failure, lifecycleFailure);
        }
        if (failure == null) {
          try {
            if (captureEngine != null) {
              clearActiveBlockResources();
            }
            if (inFlightStore != null) {
              inFlightStore.ownerCloseSucceeded();
            }
            clearClosedJavaState();
          } catch (Throwable cleanupFailure) {
            failure = collectFailure(failure, cleanupFailure);
          }
        }
        terminalCloseFailure = failure;
        closed.set(true);
        rethrowCloseFailure(failure);
      } finally {
        writeLock.unlock();
      }
    }
  }

  private void clearClosedJavaState() {
    synchronized (backlogMonitor) {
      inFlightBlocks.clear();
      pendingPublishedJournals.clear();
      inFlightVersions.clear();
      inFlightPublicationFootprints.clear();
      inFlightBlockCount = 0;
      inFlightRecordCount = 0L;
      inFlightRetainedBytes = 0L;
      inFlightPublicationBytes = 0L;
      activePublicationBytes = 0L;
      activeCaptureRecordCount = 0L;
      activeCaptureBytes = 0L;
      activeExecutionPositionBytes = 0L;
      activeJournalMutationBytes = 0L;
      inFlightResourceBytes = 0L;
      oldestInFlightBlock = -1L;
      executionTxNumIndex = null;
      backlogMonitor.notifyAll();
    }
    updateInFlightMetrics();
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
      addSuppressedSafely(failure, closeFailure);
    }
  }

  private static boolean closeSnapshotOwnerAndSuppress(
      AutoCloseable resource, Throwable failure) {
    try {
      resource.close();
      return false;
    } catch (Throwable closeFailure) {
      Throwable uncertain = ArchiveSnapshotReleaseException.contains(closeFailure)
          ? closeFailure
          : new ArchiveSnapshotReleaseException(
              "archive snapshot release was not confirmed during reader-open cleanup",
              closeFailure);
      addSuppressedSafely(failure, uncertain);
      return true;
    }
  }

  private static final class SnapshotCleanupTracker {

    private boolean uncertain;

    private void record(boolean cleanupUncertain) {
      uncertain |= cleanupUncertain;
    }

    private boolean isUncertain() {
      return uncertain;
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

  private static Throwable collectFailure(Throwable failure, Throwable candidate) {
    if (failure == null) {
      return candidate;
    }
    addSuppressedSafely(failure, candidate);
    return failure;
  }

  private static void addSuppressedSafely(Throwable primary, Throwable candidate) {
    if (primary == null || primary == candidate) {
      return;
    }
    try {
      primary.addSuppressed(candidate);
    } catch (Throwable ignored) {
      // Preserve the first failure and continue releasing remaining archive resources.
    }
  }

  private static Throwable closeResource(Object resource, Throwable failure) {
    if (resource instanceof AutoCloseable) {
      try {
        ((AutoCloseable) resource).close();
      } catch (Throwable e) {
        return collectFailure(failure, e);
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

  private static void rethrowOperationFailure(Throwable failure) {
    if (failure instanceof Error) {
      throw (Error) failure;
    }
    throw (RuntimeException) failure;
  }

  private void markFatal(Throwable failure) {
    markFatalFailure(failure);
  }

  private void markFatal(RuntimeException failure) {
    markFatalFailure(failure);
  }

  private Runnable armWatchdogFatal(RuntimeException failure) {
    FatalTransition transition = beginFatalTransition();
    if (transition == null) {
      return null;
    }
    try {
      armFatalFailure(failure, transition);
      return transition;
    } catch (RuntimeException | Error armingFailure) {
      transition.close();
      throw armingFailure;
    }
  }

  private void markDiskSampleFailure(RuntimeException failure) {
    ArchiveLifecycle.Phase phase = lifecycle.getPhase();
    if (lifecycle.getFatalFailure() == null
        && (phase == ArchiveLifecycle.Phase.DRAINING
            || phase == ArchiveLifecycle.Phase.CLOSED)) {
      return;
    }
    markFatalFailure(failure);
  }

  private void markFatalFailure(Throwable failure) {
    FatalTransition transition = beginFatalTransition();
    if (transition == null) {
      return;
    }
    try {
      armFatalFailure(failure, transition);
      completeFatalFailure(transition);
    } finally {
      transition.close();
    }
  }

  private void armFatalFailure(Throwable failure, FatalTransition transition) {
    RuntimeException normalized = normalizeFatalFailure(failure);
    String repairReason = fatalReason(normalized);
    boolean first = lifecycle.markFatal(normalized);
    RuntimeException primary = lifecycle.getFatalFailure();
    transition.arm(primary, failure, repairReason, first);
    // Every contender can arm the same primary. This closes the CAS-winner scheduling gap without
    // opening callback delivery before the repair marker has crossed its durability barrier.
    try {
      fatalController.arm(primary);
    } catch (Throwable armFailure) {
      suppressFatalStepFailure(primary, armFailure);
    }
  }

  private void completeFatalFailure(FatalTransition claim) {
    if (!claim.first) {
      suppressFatalStepFailure(claim.primary, claim.contender);
      logAdditionalFatalBestEffort(claim.primary, claim.contender);
      return;
    }
    try {
      queryCoordinator.beginDrain();
    } catch (Throwable drainFailure) {
      suppressFatalStepFailure(claim.primary, drainFailure);
    }
    try {
      if (publisher != null) {
        publisher.beginDrain();
      }
    } catch (Throwable drainFailure) {
      suppressFatalStepFailure(claim.primary, drainFailure);
    }
    try {
      synchronized (backlogMonitor) {
        backlogMonitor.notifyAll();
      }
    } catch (Throwable notifyFailure) {
      suppressFatalStepFailure(claim.primary, notifyFailure);
    }
    synchronized (repairStateMutex) {
      try {
        ArchiveMetrics.setRepairRequired(true);
      } catch (Throwable metricsFailure) {
        suppressFatalStepFailure(claim.primary, metricsFailure);
      }
      try {
        txNumIndex.markRepairRequired(claim.repairReason);
      } catch (Throwable markerFailure) {
        suppressFatalStepFailure(claim.primary, markerFailure);
        return;
      }
    }
    try {
      fatalController.deliver();
    } catch (Throwable deliveryFailure) {
      suppressFatalStepFailure(claim.primary, deliveryFailure);
    }
  }

  private FatalTransition beginFatalTransition() {
    FatalTransition transition = new FatalTransition();
    synchronized (fatalTransitionMonitor) {
      if (fatalTransitionsSealed) {
        return null;
      }
      if (activeFatalTransitions == Long.MAX_VALUE) {
        throw new ArchiveException("archive fatal transition count overflow");
      }
      activeFatalTransitions++;
      return transition;
    }
  }

  private boolean sealAndAwaitFatalTransitions(long timeoutNanos)
      throws InterruptedException {
    long remaining = timeoutNanos;
    synchronized (fatalTransitionMonitor) {
      fatalTransitionsSealed = true;
      while (activeFatalTransitions != 0L) {
        if (remaining == 0L) {
          return false;
        }
        long started = System.nanoTime();
        TimeUnit.NANOSECONDS.timedWait(fatalTransitionMonitor, remaining);
        long elapsed = Math.max(0L, System.nanoTime() - started);
        remaining = elapsed >= remaining ? 0L : remaining - elapsed;
      }
      return true;
    }
  }

  private final class FatalTransition implements AutoCloseable, Runnable {

    private RuntimeException primary;
    private Throwable contender;
    private String repairReason;
    private boolean first;
    private boolean armed;
    private boolean completionStarted;
    private boolean transitionClosed;

    private void arm(RuntimeException primaryFailure, Throwable competingFailure,
        String boundedRepairReason, boolean firstFailure) {
      primary = primaryFailure;
      contender = competingFailure;
      repairReason = boundedRepairReason;
      first = firstFailure;
      armed = true;
    }

    @Override
    public void run() {
      if (!armed || completionStarted) {
        return;
      }
      completionStarted = true;
      try {
        completeFatalFailure(this);
      } finally {
        close();
      }
    }

    @Override
    public void close() {
      synchronized (fatalTransitionMonitor) {
        if (transitionClosed) {
          return;
        }
        transitionClosed = true;
        if (activeFatalTransitions <= 0L) {
          throw new IllegalStateException("archive fatal transition count underflow");
        }
        activeFatalTransitions--;
        if (activeFatalTransitions == 0L) {
          fatalTransitionMonitor.notifyAll();
        }
      }
    }
  }

  private static RuntimeException normalizeFatalFailure(Throwable failure) {
    if (failure instanceof RuntimeException) {
      return (RuntimeException) failure;
    }
    String type = safeFailureType(failure);
    String detail = safeFailureMessage(failure);
    return new ArchiveException("archive fatal " + type
        + (detail == null || detail.isEmpty() ? "" : ": " + detail), failure);
  }

  private static String fatalReason(RuntimeException primary) {
    String reason;
    try {
      String message = primary.getMessage();
      reason = message == null || message.isEmpty() ? safeFailureType(primary) : message;
    } catch (Throwable renderingFailure) {
      suppressFatalStepFailure(primary, renderingFailure);
      reason = safeFailureType(primary);
    }
    return UnifiedArchiveTxNumIndex.boundRepairReason(reason);
  }

  private static String safeFailureMessage(Throwable failure) {
    if (failure == null) {
      return null;
    }
    try {
      return failure.getMessage();
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static String safeFailureType(Throwable failure) {
    if (failure == null) {
      return "unknown";
    }
    try {
      String type = failure.getClass().getSimpleName();
      return type == null || type.isEmpty() ? "Throwable" : type;
    } catch (Throwable ignored) {
      return "Throwable";
    }
  }

  private static void logAdditionalFatalBestEffort(Throwable primary, Throwable failure) {
    try {
      logger.warn("additional archive fatal failure after fail-stop was armed ({})",
          safeFailureType(failure));
    } catch (Throwable loggingFailure) {
      suppressFatalStepFailure(primary, loggingFailure);
    }
  }

  private static void suppressFatalStepFailure(Throwable primary, Throwable candidate) {
    try {
      suppressIfAcyclicAndAbsent(primary, candidate);
    } catch (Throwable ignored) {
      // Secondary failure bookkeeping must not interrupt the fail-stop transition.
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
      addSuppressedSafely(primary, candidate);
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
