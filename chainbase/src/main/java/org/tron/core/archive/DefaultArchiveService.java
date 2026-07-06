package org.tron.core.archive;

import com.google.common.primitives.Bytes;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.LongFunction;
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
import org.tron.core.archive.reader.DefaultArchiveStateReaderFactory;
import org.tron.core.archive.temporal.ArchiveTemporalStore;
import org.tron.core.archive.temporal.InMemoryArchiveTemporalStore;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.archive.txnum.ArchiveTxPosition;
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
  /** Published, durable archive index visible to readers. */
  private final ArchiveTxNumIndex txNumIndex;
  /** Execution-only txNum allocator for canonical but not-yet-solidified blocks. */
  private InMemoryArchiveTxNumIndex executionTxNumIndex;
  private final ArchiveExecutionContext executionContext;
  private final ArchiveCaptureEngine captureEngine;
  private final ArchiveTemporalStore temporalStore;
  private final ArchiveInFlightStore inFlightStore;
  private final ArchiveStateReaderFactory readerFactory;
  private final byte[] schemaChecksum;
  private final ReentrantReadWriteLock consistencyLock = new ReentrantReadWriteLock(true);
  private final NavigableMap<Long, ArchiveInFlightBlock> inFlightBlocks = new TreeMap<>();
  private final Map<WrappedByteArray, DomainValue> inFlightLatest = new LinkedHashMap<>();
  private volatile RuntimeException fatalFailure;

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
    this.enabled = enabled;
    this.txNumIndex = txNumIndex;
    this.executionContext = executionContext;
    if (enabled) {
      this.schemaChecksum = ArchiveSchemaChecksum.of(registry, catalog);
      this.captureEngine = new ArchiveCaptureEngine(registry, catalog, new DynamicKeyPolicy(),
          executionContext);
      this.temporalStore = temporalStore;
      this.inFlightStore = inFlightStore;
      this.executionTxNumIndex = new InMemoryArchiveTxNumIndex(txNumIndex.getNextTxNum());
      loadInFlightBlocks();
      this.readerFactory = new DefaultArchiveStateReaderFactory(temporalStore, catalog,
          txNumIndex, this::validateAvailableForRead);
      ArchiveCaptureHolder.set(captureEngine);
    } else {
      ArchiveCaptureHolder.clear();
      this.schemaChecksum = new byte[0];
      this.captureEngine = null;
      this.temporalStore = null;
      this.inFlightStore = null;
      this.executionTxNumIndex = null;
      this.readerFactory = null;
    }
  }

  public ArchiveTxNumIndex getTxNumIndex() {
    return txNumIndex;
  }

  private void loadInFlightBlocks() {
    for (ArchiveInFlightBlock block : inFlightStore.loadBlocks()) {
      ArchiveBlockRange range = block.getRange();
      Optional<ArchiveBlockRange> published = txNumIndex.getBlockRange(range.getBlockNum());
      if (published.isPresent()) {
        validatePublishedRange(range, published.get());
        temporalStore.validateCommittedBlock(published.get());
        inFlightStore.deleteBlock(range.getBlockNum());
        continue;
      }
      validateInFlightAppend(block);
      replayExecutionInFlightBlock(block);
      rememberInFlightInMemory(block);
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
    validateAvailable();
    executionTxNumIndex.beginBlock(block.getNum(), source);
    captureEngine.clear();
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
    executionContext.clear();
  }

  @Override
  public void commitBlock(BlockCapsule block) {
    commitBlock(block, block.getTransactions().size());
  }

  @Override
  public void commitBlock(BlockCapsule block, int userTxCount) {
    if (!enabled) {
      return;
    }
    Lock writeLock = consistencyLock.writeLock();
    writeLock.lock();
    try {
      commitBlockLocked(block, userTxCount);
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
  public void reconcileInFlightOnStartup(long solidifiedBlockNum,
      LongFunction<BlockCapsule> canonicalBlockProvider) {
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
        for (ArchiveInFlightBlock block : inFlightBlocks.values()) {
          validateInFlightMatchesCanonical(block, canonicalBlockProvider);
        }
        publishSolidifiedBlocksLocked(solidifiedBlockNum);
      } catch (RuntimeException e) {
        markFatal(e);
        throw e;
      }
    } finally {
      writeLock.unlock();
    }
  }

  private void publishSolidifiedBlocksLocked(long solidifiedBlockNum) {
    while (!inFlightBlocks.isEmpty()
        && inFlightBlocks.firstKey() <= solidifiedBlockNum) {
      ArchiveInFlightBlock block = inFlightBlocks.firstEntry().getValue();
      publishInFlightBlock(block);
      inFlightBlocks.remove(block.getRange().getBlockNum());
    }
    rebuildInFlightLatest();
  }

  private static void validateInFlightMatchesCanonical(ArchiveInFlightBlock block,
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
  }

  private void publishInFlightBlock(ArchiveInFlightBlock block) {
    ArchiveBlockRange range = block.getRange();
    boolean indexPublished = false;
    boolean temporalPublished = false;
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
      inFlightStore.deleteBlock(range.getBlockNum());
    } catch (RuntimeException e) {
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

  private void commitBlockLocked(BlockCapsule block, int userTxCount) {
    boolean executionCommitted = false;
    try {
      validateAvailable();
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
      records.removeIf(this::isKnownNoop);
      ArchiveBlockRange range = executionTxNumIndex.commitBlock(
          block.getNum(), block.getBlockId().getBytes(), userTxCount, schemaChecksum);
      executionCommitted = true;
      List<ArchiveTxPosition> positions = positionsOf(range, executionTxNumIndex);
      rememberInFlight(new ArchiveInFlightBlock(range, positions, records));
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
      executionContext.clear();
      captureEngine.clear();
    }
  }

  private boolean isKnownNoop(ArchiveChangeRecord record) {
    if (!record.isSameValue()) {
      return false;
    }
    if (record.getValue().isDeleted()) {
      return true;
    }
    Optional<DomainValue> latest = latestWithInFlight(record.getDomain(),
        record.getCanonicalKey());
    return latest.isPresent() && sameDomainValue(latest.get(), record.getValue());
  }

  private Optional<DomainValue> latestWithInFlight(ArchiveDomain domain, byte[] canonicalKey) {
    DomainValue inFlight = inFlightLatest.get(latestKey(domain, canonicalKey));
    return inFlight == null ? temporalStore.latest(domain, canonicalKey) : Optional.of(inFlight);
  }

  private void rememberInFlight(ArchiveInFlightBlock block) {
    validateInFlightAppend(block);
    inFlightStore.putBlock(block);
    rememberInFlightInMemory(block);
  }

  private void rememberInFlightInMemory(ArchiveInFlightBlock block) {
    inFlightBlocks.put(block.getRange().getBlockNum(), block);
    applyInFlightLatest(block);
  }

  private void validateInFlightAppend(ArchiveInFlightBlock block) {
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

  private void applyInFlightLatest(ArchiveInFlightBlock block) {
    for (ArchiveChangeRecord record : block.getRecords()) {
      inFlightLatest.put(latestKey(record.getDomain(), record.getCanonicalKey()),
          record.getValue());
    }
  }

  private void rebuildInFlightLatest() {
    inFlightLatest.clear();
    for (ArchiveInFlightBlock block : inFlightBlocks.values()) {
      applyInFlightLatest(block);
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
    return left.isDeleted() == right.isDeleted()
        && Arrays.equals(left.getValue(), right.getValue());
  }

  /** Identity for within-block change collapsing: (domainId, txNum, canonicalKey). */
  private static WrappedByteArray mergeKey(ArchiveChangeRecord record) {
    return WrappedByteArray.of(Bytes.concat(
        Ints.toByteArray(record.getDomain().getId()),
        Longs.toByteArray(record.getTxNum()),
        record.getCanonicalKey()));
  }

  private void validateAvailableForRead() throws ArchiveReaderException {
    try {
      validateAvailable();
    } catch (RuntimeException e) {
      throw new ArchiveReaderException(ArchiveReaderException.Reason.INTERNAL_IO,
          "archive is unavailable", e);
    }
  }

  @Override
  public void abortBlock(BlockCapsule block) {
    executionContext.clear();
    if (!enabled) {
      return;
    }
    Lock writeLock = consistencyLock.writeLock();
    writeLock.lock();
    try {
      RuntimeException failure = null;
      try {
        validateAvailable();
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
          inFlightStore.deleteBlock(block.getNum());
          inFlightBlocks.remove(block.getNum());
          executionTxNumIndex.unwindBlock(block.getNum());
          rebuildInFlightLatest();
        } else {
          Optional<ArchiveBlockRange> committed = txNumIndex.getBlockRange(block.getNum());
          long firstArchivedBlock = txNumIndex.getFirstArchivedBlock();
          if (committed.isPresent()) {
            throw new ArchiveException("cannot unwind published archive block " + block.getNum());
          }
          if (!committed.isPresent()
              && (firstArchivedBlock < 0 || block.getNum() < firstArchivedBlock)) {
            executionContext.clear();
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
  public void validateAvailable() {
    if (!enabled) {
      return;
    }
    RuntimeException failure = fatalFailure;
    if (failure != null) {
      throw new ArchiveException("archive is unavailable after fatal failure", failure);
    }
    if (!ArchiveCaptureHolder.isCurrent(captureEngine)) {
      throw new ArchiveException("archive capture engine is not active");
    }
  }

  @Override
  public ReadGuard acquireReadGuard() {
    if (!enabled) {
      return ArchiveService.super.acquireReadGuard();
    }
    Lock readLock = consistencyLock.readLock();
    readLock.lock();
    try {
      validateAvailable();
      return readLock::unlock;
    } catch (RuntimeException e) {
      readLock.unlock();
      throw e;
    }
  }

  @Override
  public boolean hasCommittedBlock(long blockNum) {
    validateAvailable();
    return enabled && txNumIndex.getBlockRange(blockNum).isPresent();
  }

  @Override
  public void close() {
    ArchiveCaptureHolder.clearIf(captureEngine);
    RuntimeException failure = null;
    failure = closeResource(inFlightStore, "in-flight store", failure);
    failure = closeResource(temporalStore, "temporal store", failure);
    failure = closeResource(txNumIndex, "txNum index", failure);
    if (failure != null) {
      throw failure;
    }
  }

  private static RuntimeException closeResource(Object resource, String name,
      RuntimeException failure) {
    if (resource instanceof AutoCloseable) {
      try {
        ((AutoCloseable) resource).close();
      } catch (Exception e) {
        RuntimeException closeFailure = e instanceof RuntimeException
            ? (RuntimeException) e
            : new ArchiveException("failed to close archive " + name, e);
        if (failure == null) {
          return closeFailure;
        }
        failure.addSuppressed(closeFailure);
      }
    }
    return failure;
  }

  private void markFatal(RuntimeException failure) {
    if (fatalFailure == null) {
      try {
        txNumIndex.markRepairRequired(failure.getMessage() == null
            ? failure.getClass().getSimpleName()
            : failure.getMessage());
      } catch (RuntimeException markerFailure) {
        failure.addSuppressed(markerFailure);
      }
      fatalFailure = failure;
    }
  }
}
