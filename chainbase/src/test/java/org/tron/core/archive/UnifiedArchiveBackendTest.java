package org.tron.core.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.tron.core.archive.unified.UnifiedArchiveTestMaintenance.openWithBeforeBatchWrite;
import static org.tron.core.archive.unified.UnifiedArchiveTestMaintenance.openWithStatistics;
import static org.tron.core.archive.unified.UnifiedArchiveTestMaintenance.write;

import com.google.common.base.Strings;
import com.google.protobuf.ByteString;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.Snapshot;
import org.rocksdb.Statistics;
import org.rocksdb.TickerType;
import org.rocksdb.WriteOptions;
import org.tron.common.math.StrictMathWrapper;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.utils.Sha256Hash;
import org.tron.common.utils.ReflectUtils;
import org.tron.core.archive.capture.ArchiveCaptureHolder;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.domain.ArchiveDomainCatalog;
import org.tron.core.archive.domain.ArchiveDomainRegistry;
import org.tron.core.archive.domain.ArchiveSchemaChecksum;
import org.tron.core.archive.domain.DefaultArchiveDomainCatalog;
import org.tron.core.archive.domain.DefaultArchiveDomainRegistry;
import org.tron.core.archive.query.ArchiveQueryCoordinator;
import org.tron.core.archive.query.ArchiveQueryLimits;
import org.tron.core.archive.query.ArchiveQueryTransportScope;
import org.tron.core.archive.query.HistoricalQueryLimitException;
import org.tron.core.archive.query.QueryContext;
import org.tron.core.archive.query.QueryContextHolder;
import org.tron.core.archive.reader.ArchiveReadResult;
import org.tron.core.archive.reader.ArchiveReaderException;
import org.tron.core.archive.reader.ArchiveStatePoint;
import org.tron.core.archive.reader.ArchiveStateReader;
import org.tron.core.archive.temporal.ArchiveTemporalReadView;
import org.tron.core.archive.temporal.UnifiedArchiveTemporalStore;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.archive.txnum.ArchiveBlockRangeCodec;
import org.tron.core.archive.txnum.ArchiveTransactionLocation;
import org.tron.core.archive.txnum.ArchiveTxPosition;
import org.tron.core.archive.txnum.UnifiedArchiveTxNumIndex;
import org.tron.core.archive.unified.UnifiedArchiveColumnFamily;
import org.tron.core.archive.unified.UnifiedArchiveDb;
import org.tron.core.archive.unified.UnifiedArchiveIterator;
import org.tron.core.archive.unified.UnifiedArchiveMaintenanceBatch;
import org.tron.core.archive.unified.UnifiedArchivePublish;
import org.tron.core.archive.unified.UnifiedArchiveReadView;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.protos.Protocol.Account;
import org.tron.protos.Protocol.Transaction;

public class UnifiedArchiveBackendTest {

  @Rule
  public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private Path dbPath;
  private ArchiveDomainCatalog catalog;
  private byte[] schemaChecksum;
  private UnifiedArchiveDb db;
  private UnifiedArchiveTxNumIndex index;
  private UnifiedArchiveTemporalStore temporal;
  private UnifiedArchiveInFlightStore inFlight;
  private UnifiedArchiveBackend backend;
  private DefaultArchiveService service;

  @Before
  public void setUp() {
    dbPath = temporaryFolder.getRoot().toPath().resolve("unified");
    ArchiveDomainRegistry registry = new DefaultArchiveDomainRegistry();
    catalog = new DefaultArchiveDomainCatalog();
    schemaChecksum = ArchiveSchemaChecksum.of(registry, catalog);
    db = UnifiedArchiveDb.initialize(dbPath, schemaChecksum);
    wire(false);
  }

  @After
  public void tearDown() {
    if (service != null) {
      service.close();
      service = null;
      index = null;
    } else if (index != null) {
      index.close();
    }
    ArchiveCaptureHolder.clear();
  }

  @Test
  public void atomicPublishAndTypedRowsSurviveRestart() {
    publish(block(0L, DomainValue.tombstone(), value(1)));

    assertEquals(0L, index.getFirstArchivedBlock());
    assertEquals(0L, index.getLastArchivedBlock());
    assertEquals(2L, index.getNextTxNum());
    assertTrue(inFlight.loadBlocks().isEmpty());
    assertValue(temporal.getAsOf(ArchiveDomain.ACCOUNT, accountKey(), 0L), 1);
    backend.validateStartup(true, false);

    index.close();
    index = null;
    db = UnifiedArchiveDb.open(dbPath, schemaChecksum);
    wire(true);

    assertEquals(0L, index.getFirstArchivedBlock());
    assertEquals(0L, index.getLastArchivedBlock());
    assertEquals(2L, index.getNextTxNum());
    assertTrue(inFlight.loadBlocks().isEmpty());
    assertValue(temporal.getAsOf(ArchiveDomain.ACCOUNT, accountKey(), 0L), 1);
    backend.validateStartup(true, false);
  }

  @Test
  public void indexPublicationRejectsDuplicateUserTxIdsWithinBlock() {
    byte[] duplicateTxId = hash(99L);
    ArchiveBlockRange range = new ArchiveBlockRange(
        0L, 0L, 3L, 0L, 3L, hash(1L), 2, ArchiveSource.NORMAL, schemaChecksum);
    ArchiveTxPosition prepare = new ArchiveTxPosition(
        0L, 0L, ArchivePhase.BLOCK_PREPARE, ArchiveSource.NORMAL, -1, null);
    ArchiveTxPosition first = new ArchiveTxPosition(
        1L, 0L, ArchivePhase.USER_TX, ArchiveSource.NORMAL, 0, duplicateTxId);
    ArchiveTxPosition second = new ArchiveTxPosition(
        2L, 0L, ArchivePhase.USER_TX, ArchiveSource.NORMAL, 1, duplicateTxId);
    ArchiveTxPosition finalize = new ArchiveTxPosition(
        3L, 0L, ArchivePhase.BLOCK_FINALIZE, ArchiveSource.NORMAL, -1, null);
    ArchiveInFlightBlock duplicate = new ArchiveInFlightBlock(
        range, Arrays.asList(prepare, first, second, finalize), Collections.emptyList());

    ArchiveException failure;
    try (UnifiedArchiveReadView view = db.openReadView();
        UnifiedArchiveTxNumIndex.ReadScope ignored = index.bindReadView(view)) {
      failure = assertThrows(ArchiveException.class,
          () -> index.stagePublication(UnifiedArchivePublish.builder(), duplicate));
    }

    assertTrue(failure.getMessage().contains("duplicate txId"));
    assertFalse(index.getBlockRange(0L).isPresent());
  }

  @Test
  public void productionServiceDoesNotExposeMutableStorageOrCaptureInternals() {
    service = unifiedService();

    assertThrows(ArchiveException.class, service::getTemporalStore);
    assertThrows(ArchiveException.class, service::getCaptureEngine);
    assertThrows(ArchiveException.class, service::getTxNumIndex);

    assertSame(temporal, service.getTemporalStoreForTesting());
    assertNotNull(service.getCaptureEngineForTesting());
    assertEquals(-1L, index.getLastArchivedBlock());
  }

  @Test
  public void productionTemporalStoreRejectsDirectMaintenance() {
    publish(block(0L, DomainValue.tombstone(), value(1)));
    publish(block(1L, value(1), value(2)));
    ArchiveTxPosition position = index.getPosition(0L)
        .orElseThrow(() -> new AssertionError("missing block-zero prepare position"));
    byte[] injectedKey = accountKey();
    injectedKey[injectedKey.length - 1] = 1;

    ArchiveException failure = assertThrows(
        ArchiveException.class, () -> temporal.putChange(new ArchiveChangeRecord(
            position, ArchiveDomain.ACCOUNT, injectedKey, DomainValue.tombstone(), value(9))));

    assertTrue(failure.getMessage().contains("direct UNIFIED_V1 temporal maintenance"));
    assertFalse(index.hasRepairRequired());
    assertFalse(temporal.latest(ArchiveDomain.ACCOUNT, injectedKey).isPresent());
  }

  @Test
  public void repairEvidenceRequiresRecoveryPermitToClear() {
    write(db, new UnifiedArchiveMaintenanceBatch());
    assertTrue(index.hasRepairRequired());

    assertThrows(ArchiveException.class, () -> index.clearRepairRequired(null));
    assertTrue(index.hasRepairRequired());

    index.clearRepairRequired(new ArchiveRepairClearPermit());
    assertFalse(index.hasRepairRequired());
  }

  @Test
  public void repairEvidenceHonorsRestartReadableMetadataBoundary() {
    String maximumReason = Strings.repeat("x", 4_096);
    index.markRepairRequired(maximumReason);
    assertArrayEquals(maximumReason.getBytes(StandardCharsets.UTF_8),
        db.get(UnifiedArchiveColumnFamily.META,
            ArchiveBlockRangeCodec.repairRequiredKey()));

    index.close();
    index = null;
    db = UnifiedArchiveDb.open(dbPath, schemaChecksum);
    wire(false, true);
    assertTrue(index.hasRepairRequired());
    index.clearRepairRequired(new ArchiveRepairClearPermit());
    assertFalse(index.hasRepairRequired());

    index.markRepairRequired("preserved");
    ArchiveException failure = assertThrows(
        ArchiveException.class, () -> index.markRepairRequired(Strings.repeat("y", 4_097)));
    assertTrue(failure.getMessage().contains("4096"));
    assertArrayEquals("preserved".getBytes(StandardCharsets.UTF_8),
        db.get(UnifiedArchiveColumnFamily.META,
            ArchiveBlockRangeCodec.repairRequiredKey()));
  }

  @Test
  public void internallyBoundRepairReasonPreservesUtf8Boundary() {
    String bounded =
        UnifiedArchiveTxNumIndex.boundRepairReason(Strings.repeat("\u754c", 2_000));

    assertTrue(bounded.endsWith("... [truncated]"));
    assertTrue(bounded.getBytes(StandardCharsets.UTF_8).length
        <= UnifiedArchiveTxNumIndex.MAX_REPAIR_MARKER_BYTES);
    assertFalse(bounded.contains("\ufffd"));
  }

  @Test
  public void oversizedDiskRepairMarkerFailsBoundedStartupProbe() {
    db.putMetaDurably(ArchiveBlockRangeCodec.repairRequiredKey(), new byte[4_097]);

    ArchiveException failure = assertThrows(
        ArchiveException.class, () -> UnifiedArchiveTxNumIndex.hasRepairRequired(db));

    assertTrue(failure.getMessage().contains("4096"));
  }

  @Test
  public void oversizedRuntimeFatalPersistsBoundedEvidenceAcrossRestart() throws Exception {
    service = unifiedService();
    CountDownLatch delivered = new CountDownLatch(1);
    service.setFatalFailureHandler(ignored -> delivered.countDown());

    ReflectUtils.invokeMethod(service, "markFatal",
        new Class<?>[] {RuntimeException.class},
        new ArchiveException(Strings.repeat("x", 4_097)));

    assertTrue(delivered.await(1L, TimeUnit.SECONDS));
    byte[] marker = db.get(UnifiedArchiveColumnFamily.META,
        ArchiveBlockRangeCodec.repairRequiredKey());
    assertNotNull(marker);
    assertTrue(marker.length <= UnifiedArchiveTxNumIndex.MAX_REPAIR_MARKER_BYTES);
    assertTrue(new String(marker, StandardCharsets.UTF_8).endsWith("... [truncated]"));

    service.close();
    service = null;
    index = null;
    db = UnifiedArchiveDb.open(dbPath, schemaChecksum);
    wire(false, true);
    assertTrue(index.hasRepairRequired());
  }

  @Test
  public void readSessionBlockRangesStayOnTheCapturedUnifiedSnapshot() {
    publish(block(0L, DomainValue.tombstone(), value(1)));

    try (UnifiedArchiveBackend.ReadSession session = backend.openReadSession()) {
      publish(block(1L, value(1), value(2)));

      assertTrue(session.getBlockRange(0L).isPresent());
      assertFalse(session.getBlockRange(1L).isPresent());
    }

    try (UnifiedArchiveBackend.ReadSession current = backend.openReadSession()) {
      assertTrue(current.getBlockRange(1L).isPresent());
    }
  }

  @Test
  public void readSessionSetupCleanupFailurePromotesSnapshotUncertainty() {
    UnifiedArchiveDb mockedDb = mock(UnifiedArchiveDb.class);
    UnifiedArchiveTxNumIndex mockedIndex = mock(UnifiedArchiveTxNumIndex.class);
    UnifiedArchiveTemporalStore mockedTemporal = mock(UnifiedArchiveTemporalStore.class);
    UnifiedArchiveReadView mockedView = mock(UnifiedArchiveReadView.class);
    when(mockedIndex.isOwnedBy(mockedDb)).thenReturn(true);
    when(mockedTemporal.isOwnedBy(mockedDb)).thenReturn(true);
    when(mockedDb.openScanView()).thenReturn(mockedView);
    SuppressionDisabledException setupFailure =
        new SuppressionDisabledException("injected read-session setup failure");
    AssertionError closeFailure = new AssertionError("injected snapshot close failure");
    doThrow(setupFailure).when(mockedTemporal).wrapReadView(mockedView);
    doThrow(closeFailure).when(mockedView).close();
    UnifiedArchiveBackend mockedBackend =
        new UnifiedArchiveBackend(mockedDb, mockedIndex, mockedTemporal);

    ArchiveSnapshotReleaseException failure = assertThrows(
        ArchiveSnapshotReleaseException.class, mockedBackend::openReadSession);

    assertSame(closeFailure, failure.getCause());
    assertEquals(1, failure.getSuppressed().length);
    assertSame(setupFailure, failure.getSuppressed()[0]);
  }

  @Test
  public void indexOwnedViewCleanupFailurePromotesSnapshotUncertainty() throws Exception {
    UnifiedArchiveReadView mockedView = mock(UnifiedArchiveReadView.class);
    SuppressionDisabledException operationFailure =
        new SuppressionDisabledException("injected index operation failure");
    AssertionError closeFailure = new AssertionError("injected index snapshot close failure");
    doThrow(closeFailure).when(mockedView).close();
    Method withOwnedReadView = UnifiedArchiveTxNumIndex.class.getDeclaredMethod(
        "withOwnedReadView", UnifiedArchiveReadView.class, Function.class);
    withOwnedReadView.setAccessible(true);
    Function<UnifiedArchiveReadView, Object> action = ignored -> {
      throw operationFailure;
    };

    InvocationTargetException invocation = assertThrows(
        InvocationTargetException.class,
        () -> withOwnedReadView.invoke(null, mockedView, action));
    ArchiveSnapshotReleaseException failure =
        (ArchiveSnapshotReleaseException) invocation.getCause();

    assertSame(closeFailure, failure.getCause());
    assertEquals(1, failure.getSuppressed().length);
    assertSame(operationFailure, failure.getSuppressed()[0]);
  }

  @Test
  public void readScopesRejectForeignCloseWithoutPoisoningOwnerCleanup() throws Exception {
    publish(block(0L, DomainValue.tombstone(), value(1)));

    try (UnifiedArchiveBackend.ReadSession session = backend.openReadSession()) {
      UnifiedArchiveTxNumIndex.ReadScope scope = session.bindIndex();
      FutureTask<Throwable> foreignScopeClose = new FutureTask<>(() -> {
        try {
          scope.close();
          return null;
        } catch (Throwable failure) {
          return failure;
        }
      });
      Thread scopeThread = new Thread(foreignScopeClose, "archive-foreign-index-scope-close");
      scopeThread.start();
      assertTrue(foreignScopeClose.get(1L, TimeUnit.SECONDS) instanceof ArchiveException);
      scopeThread.join(1_000L);

      assertTrue(index.getBlockRange(0L).isPresent());
      scope.close();
      try (UnifiedArchiveTxNumIndex.ReadScope ignored = session.bindIndex()) {
        assertTrue(index.getBlockRange(0L).isPresent());
      }

      FutureTask<Throwable> foreignSessionClose = new FutureTask<>(() -> {
        try {
          session.close();
          return null;
        } catch (Throwable failure) {
          return failure;
        }
      });
      Thread sessionThread = new Thread(
          foreignSessionClose, "archive-foreign-read-session-close");
      sessionThread.start();
      assertTrue(foreignSessionClose.get(1L, TimeUnit.SECONDS) instanceof ArchiveException);
      sessionThread.join(1_000L);

      assertTrue(session.getBlockRange(0L).isPresent());
    }
  }

  @Test
  public void temporalSnapshotRejectsForeignCloseWithoutPoisoningOwnerCleanup() throws Exception {
    publish(block(0L, DomainValue.tombstone(), value(1)));
    ArchiveTemporalReadView view = temporal.openReadView();
    try {
      FutureTask<Throwable> foreignClose = new FutureTask<>(() -> {
        try {
          view.close();
          return null;
        } catch (Throwable failure) {
          return failure;
        }
      });
      Thread thread = new Thread(foreignClose, "archive-foreign-temporal-view-close");
      thread.start();
      assertTrue(foreignClose.get(1L, TimeUnit.SECONDS) instanceof ArchiveException);
      thread.join(1_000L);

      assertValue(view.getAsOf(ArchiveDomain.ACCOUNT, accountKey(), 0L), 1);
    } finally {
      view.close();
    }
    assertThrows(ArchiveException.class,
        () -> view.getAsOf(ArchiveDomain.ACCOUNT, accountKey(), 0L));
  }

  @Test
  public void normalStartupValidationRejectsMissingMiddleRange() {
    publish(block(0L, DomainValue.tombstone(), value(1)));
    publish(block(1L, value(1), value(2)));
    publish(block(2L, value(2), value(3)));
    byte[] missingRangeKey = ByteBuffer.allocate(1 + Long.BYTES)
        .put((byte) 0x10)
        .putLong(1L)
        .array();
    write(db, new UnifiedArchiveMaintenanceBatch().delete(
        UnifiedArchiveColumnFamily.INDEX, missingRangeKey));

    ArchiveException failure = assertThrows(
        ArchiveException.class, () -> index.validateStartup(false, true));

    assertTrue(failure.getMessage().contains("non-contiguous archive block range"));
  }

  @Test
  public void journalAdmissionRejectsImpossiblePublicationBeforeDurableWrite() {
    ArchiveInFlightBlock candidate = block(0L, DomainValue.tombstone(), value(1));
    assertEquals(ArchiveInFlightCodec.encodeBlock(candidate).length,
        ArchiveInFlightCodec.encodedBlockSize(candidate));
    long journalOnlyBytes = candidate.estimatedRetainedBytes();
    long publishBytes = backend.estimatedPublicationRetainedBytes(candidate);
    long hardBytes = journalOnlyBytes + 1L;
    assertTrue(publishBytes > hardBytes);

    UnifiedArchiveBackend boundedBackend =
        new UnifiedArchiveBackend(db, index, temporal, hardBytes, 100L);
    ArchivePublisherConfig publisherConfig = new ArchivePublisherConfig(
        false, false, 32, 64, hardBytes, hardBytes,
        1_000L, 2_000L, 0L, 0L, 1_000L);
    service = new DefaultArchiveService(true, index, new ArchiveExecutionContext(),
        temporal, inFlight, new DefaultArchiveDomainRegistry(), catalog,
        ArchiveLifecycle.Phase.RUNNING,
        ArchiveQueryLimits.unlimited(), publisherConfig, () -> { }, boundedBackend);
    BlockCapsule block = new BlockCapsule(
        0L, Sha256Hash.ZERO_HASH, 1L, ByteString.EMPTY);
    service.beginBlock(block, ArchiveSource.NORMAL);
    service.beginSystemTx(block, ArchivePhase.BLOCK_PREPARE);
    service.endTx();
    service.beginSystemTx(block, ArchivePhase.BLOCK_FINALIZE);
    service.endTx();

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> service.commitBlock(block, 0));

    assertTrue(failure.getMessage().contains("cannot fit publish retained byte limit"));
    assertTrue(inFlight.loadBlocks().isEmpty());
    assertNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT,
        ArchiveInFlightCodec.blockKey(0L)));
  }

  @Test
  public void journalAdmissionUsesTemporalPreparationBudgetBeforeDurableWrite() {
    long hardBytes = 64L * 1024L;
    UnifiedArchiveBackend boundedBackend =
        new UnifiedArchiveBackend(db, index, temporal, hardBytes, 100L);
    ArchivePublisherConfig publisherConfig = new ArchivePublisherConfig(
        false, false, 32, 64, hardBytes, hardBytes,
        1_000L, 2_000L, 0L, 0L, 1_000L);
    service = new DefaultArchiveService(true, index, new ArchiveExecutionContext(),
        temporal, inFlight, new DefaultArchiveDomainRegistry(), catalog,
        ArchiveLifecycle.Phase.RUNNING,
        ArchiveQueryLimits.unlimited(), publisherConfig, () -> { }, boundedBackend);
    BlockCapsule block = canonicalBlock(0L);
    ArchiveException failure = assertThrows(
        ArchiveException.class, () -> {
          service.beginBlock(block, ArchiveSource.NORMAL);
          service.beginSystemTx(block, ArchivePhase.BLOCK_PREPARE);
          service.getCaptureEngineForTesting().capturePut(
              "account", accountKey(), null, largeValue(1, 8 * 1024).getValue());
        });

    assertTrue(failure.getMessage().contains("pipeline resource watermark"));
    assertNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT,
        ArchiveInFlightCodec.blockKey(0L)));
    assertNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT,
        ArchiveInFlightCodec.tokenKey(0L)));
    assertNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT,
        ArchiveInFlightCodec.acknowledgementKey(0L)));
  }

  @Test
  public void journalAdmissionCombinesBacklogAndFuturePublicationResources() {
    BlockCapsule block = canonicalBlock(0L);
    ArchiveTxPosition prepare = new ArchiveTxPosition(
        0L, 0L, ArchivePhase.BLOCK_PREPARE, ArchiveSource.NORMAL, -1, null);
    ArchiveTxPosition finalize = new ArchiveTxPosition(
        1L, 0L, ArchivePhase.BLOCK_FINALIZE, ArchiveSource.NORMAL, -1, null);
    ArchiveBlockRange range = new ArchiveBlockRange(
        0L, 0L, 1L, 0L, 1L, block.getBlockId().getBytes(), 0,
        ArchiveSource.NORMAL, schemaChecksum);
    ArchiveInFlightBlock candidate = new ArchiveInFlightBlock(
        range, Arrays.asList(prepare, finalize), Collections.emptyList());
    long publishBytes = backend.estimatedPublicationRetainedBytes(candidate);
    long hardBytes = StrictMathWrapper.max(candidate.estimatedRetainedBytes(), publishBytes);
    assertTrue(candidate.estimatedRetainedBytes() + publishBytes > hardBytes);
    ArchivePublisherConfig publisherConfig = new ArchivePublisherConfig(
        false, false, 32, 64, hardBytes, hardBytes,
        1_000L, 2_000L, 0L, 0L, 1_000L);
    service = new DefaultArchiveService(true, index, new ArchiveExecutionContext(),
        temporal, inFlight, new DefaultArchiveDomainRegistry(), catalog,
        ArchiveLifecycle.Phase.RUNNING,
        ArchiveQueryLimits.unlimited(), publisherConfig, () -> { }, backend);
    service.beginBlock(block, ArchiveSource.NORMAL);
    service.beginSystemTx(block, ArchivePhase.BLOCK_PREPARE);
    service.endTx();
    service.beginSystemTx(block, ArchivePhase.BLOCK_FINALIZE);
    service.endTx();

    ArchiveException failure = assertThrows(
        ArchiveException.class, () -> service.commitBlockJournaled(block, 0));

    assertTrue(failure.getMessage().contains("hard resource watermark"));
    assertTrue(inFlight.loadBlocks().isEmpty());
  }

  @Test
  public void journalAdmissionCountsOnlyOneSerializedPublicationWorkingSet() {
    BlockCapsule firstBlock = canonicalBlock(0L);
    ArchiveTxPosition prepare = new ArchiveTxPosition(
        0L, 0L, ArchivePhase.BLOCK_PREPARE, ArchiveSource.NORMAL, -1, null);
    ArchiveTxPosition finalize = new ArchiveTxPosition(
        1L, 0L, ArchivePhase.BLOCK_FINALIZE, ArchiveSource.NORMAL, -1, null);
    ArchiveBlockRange range = new ArchiveBlockRange(
        0L, 0L, 1L, 0L, 1L, firstBlock.getBlockId().getBytes(), 0,
        ArchiveSource.NORMAL, schemaChecksum);
    ArchiveInFlightBlock candidate = new ArchiveInFlightBlock(
        range, Arrays.asList(prepare, finalize), Collections.emptyList());
    long retainedBytes = candidate.estimatedRetainedBytes();
    long publicationBytes = backend.estimatedPublicationRetainedBytes(candidate);
    long hardBytes = retainedBytes * 2L + publicationBytes;
    ArchivePublisherConfig publisherConfig = new ArchivePublisherConfig(
        false, false, 32, 64, hardBytes, hardBytes,
        1_000L, 2_000L, 0L, 0L, 1_000L);
    service = new DefaultArchiveService(true, index, new ArchiveExecutionContext(),
        temporal, inFlight, new DefaultArchiveDomainRegistry(), catalog,
        ArchiveLifecycle.Phase.RUNNING,
        ArchiveQueryLimits.unlimited(), publisherConfig, () -> { }, backend);

    for (long blockNum = 0L; blockNum < 2L; blockNum++) {
      BlockCapsule block = canonicalBlock(blockNum);
      service.beginBlock(block, ArchiveSource.NORMAL);
      service.beginSystemTx(block, ArchivePhase.BLOCK_PREPARE);
      service.endTx();
      service.beginSystemTx(block, ArchivePhase.BLOCK_FINALIZE);
      service.endTx();
      ArchiveJournalToken token = service.commitBlockJournaled(block, 0);
      service.acknowledgeCanonicalCommit(token);
    }

    assertEquals(2, inFlight.loadBlocks().size());
    assertEquals(hardBytes,
        (long) ReflectUtils.getFieldValue(service, "inFlightResourceBytes"));
    service.publishSolidifiedBlocks(1L);
    assertEquals(0L,
        (long) ReflectUtils.getFieldValue(service, "inFlightResourceBytes"));
  }

  @Test
  public void fullScrubIteratorCreationDoesNotScaleWithBlockCount() {
    boolean metricsPreviouslyEnabled =
        CommonParameter.getInstance().isMetricsPrometheusEnable();
    try {
      reopenWithMetrics(true);
      TickerType iteratorCreationTicker = requireIteratorCreationTicker();
      publish(block(0L, DomainValue.tombstone(), value(1)));
      Statistics statistics = ReflectUtils.getFieldValue(db, "statistics");
      long oneBlockIterators =
          fullScrubIteratorCount(statistics, iteratorCreationTicker);

      for (int blockNum = 1; blockNum < 16; blockNum++) {
        publish(block(blockNum, value(blockNum), value(blockNum + 1)));
      }
      long manyBlockIterators =
          fullScrubIteratorCount(statistics, iteratorCreationTicker);

      assertEquals("full scrub iterator creation must stay O(1)",
          oneBlockIterators, manyBlockIterators);
    } finally {
      reopenWithMetrics(metricsPreviouslyEnabled);
    }
  }

  @Test
  public void ordinaryPostReconcileValidationDoesNotRescanEveryPublishedRange() {
    DomainValue previous = DomainValue.tombstone();
    for (int blockNum = 0; blockNum < 32; blockNum++) {
      DomainValue current = value(blockNum + 1);
      publish(block(blockNum, previous, current));
      previous = current;
    }
    boolean metricsPreviouslyEnabled =
        CommonParameter.getInstance().isMetricsPrometheusEnable();
    try {
      reopenWithMetrics(true);
      Statistics statistics = ReflectUtils.getFieldValue(db, "statistics");
      long before = statistics.getTickerCount(TickerType.NUMBER_DB_NEXT);

      backend.validatePostReconcileStartup(false, true);

      long nextCalls = statistics.getTickerCount(TickerType.NUMBER_DB_NEXT) - before;
      assertTrue("ordinary post-reconcile validation rescanned published ranges: nextCalls="
          + nextCalls, nextCalls < 16L);
    } finally {
      reopenWithMetrics(metricsPreviouslyEnabled);
    }
  }

  @Test
  public void largeTemporalPublicationReadsDoNotPopulatePayloadBlocks() {
    DomainValue first = largeValue(1, 128 * 1024);
    DomainValue second = largeValue(2, 128 * 1024);
    publish(block(0L, DomainValue.tombstone(), first));
    boolean metricsPreviouslyEnabled =
        CommonParameter.getInstance().isMetricsPrometheusEnable();
    try {
      reopenWithMetrics(true);
      Statistics statistics = ReflectUtils.getFieldValue(db, "statistics");
      long before = statistics.getTickerCount(TickerType.BLOCK_CACHE_DATA_BYTES_INSERT);

      publish(block(1L, first, second));

      long inserted = statistics.getTickerCount(
          TickerType.BLOCK_CACHE_DATA_BYTES_INSERT) - before;
      assertTrue("only compact index metadata may enter the cache: inserted=" + inserted,
          inserted < 4_096L);
    } finally {
      reopenWithMetrics(metricsPreviouslyEnabled);
    }
  }

  @Test
  public void stateAwareAdmissionRejectsLargePersistedHistoryBeforePayloadRead() {
    DomainValue largeAnchor = largeIncompressibleValue(1, 1024 * 1024);
    DomainValue current = value(2);
    publish(block(0L, largeAnchor, current));
    boolean metricsPreviouslyEnabled =
        CommonParameter.getInstance().isMetricsPrometheusEnable();
    try {
      reopenWithMetrics(true);
      ArchiveInFlightBlock candidate = block(1L, current, value(3));
      ArchiveInFlightBlock validated = prepareCanonicalJournal(candidate);
      long staticPublicationBytes = backend.estimatedPublicationRetainedBytes(candidate);
      UnifiedArchiveBackend boundedBackend = new UnifiedArchiveBackend(
          db, index, temporal, staticPublicationBytes + 64L * 1024L, 100L);
      Statistics statistics = ReflectUtils.getFieldValue(db, "statistics");
      long before = statistics.getTickerCount(TickerType.BYTES_READ);

      ArchiveException failure = assertThrows(
          ArchiveException.class, () -> boundedBackend.publishBlock(validated));

      assertTrue(failure.getMessage().contains("state-aware publication"));
      assertTrue(failure.getMessage().contains("persistedTemporalPreparationBytes"));
      long bytesRead = statistics.getTickerCount(TickerType.BYTES_READ) - before;
      assertTrue("state-aware admission must read locators only: bytesRead=" + bytesRead,
          bytesRead < 128L * 1024L);
    } finally {
      reopenWithMetrics(metricsPreviouslyEnabled);
    }
  }

  @Test
  public void observerRejectionClosesSnapshotBoundPublicationPreflight() {
    DomainValue largeAnchor = largeIncompressibleValue(1, 1024 * 1024);
    DomainValue current = value(2);
    publish(block(0L, largeAnchor, current));
    boolean metricsPreviouslyEnabled =
        CommonParameter.getInstance().isMetricsPrometheusEnable();
    try {
      reopenWithMetrics(true);
      ArchiveInFlightBlock candidate = block(1L, current, value(3));
      ArchiveInFlightBlock validated = prepareCanonicalJournal(candidate);
      AtomicInteger activeReadViews = ReflectUtils.getFieldValue(db, "activeReadViews");
      Statistics statistics = ReflectUtils.getFieldValue(db, "statistics");
      long before = statistics.getTickerCount(TickerType.BYTES_READ);
      assertEquals(0, activeReadViews.get());

      ArchiveException failure = assertThrows(ArchiveException.class,
          () -> backend.publishBlock(validated, ignored -> {
            assertEquals(1, activeReadViews.get());
            long bytesRead = statistics.getTickerCount(TickerType.BYTES_READ) - before;
            assertTrue("observer must run before persisted payload reads: bytesRead=" + bytesRead,
                bytesRead < 128L * 1024L);
            throw new ArchiveException("injected publication admission rejection");
          }));

      assertTrue(failure.getMessage().contains("injected publication admission rejection"));
      assertEquals(0, activeReadViews.get());
      assertNotNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT,
          ArchiveInFlightCodec.blockKey(1L)));
    } finally {
      reopenWithMetrics(metricsPreviouslyEnabled);
    }
  }

  @Test
  public void publicationPreflightIsThreadBoundSingleUseAndReleasesSnapshot()
      throws Exception {
    ArchiveInFlightBlock candidate = block(0L, DomainValue.tombstone(), value(1));
    AtomicInteger activeReadViews = ReflectUtils.getFieldValue(db, "activeReadViews");
    UnifiedArchiveTemporalStore.PublicationPreflight preflight =
        temporal.preflightPublication(candidate.getRecords());
    try {
      assertEquals(1, activeReadViews.get());
      AtomicReference<Throwable> foreignFailure = new AtomicReference<>();
      Thread foreign = new Thread(() -> {
        try {
          preflight.getPersistedPreparationBytes();
        } catch (Throwable failure) {
          foreignFailure.set(failure);
        }
      }, "archive-preflight-foreign-owner");
      foreign.start();
      foreign.join();
      assertTrue(foreignFailure.get() instanceof ArchiveException);
      assertTrue(foreignFailure.get().getMessage().contains("non-owner thread"));

      temporal.stagePublication(
          UnifiedArchivePublish.builder(), candidate.getRange(), preflight);
      ArchiveException consumed = assertThrows(ArchiveException.class,
          () -> temporal.stagePublication(
              UnifiedArchivePublish.builder(), candidate.getRange(), preflight));
      assertTrue(consumed.getMessage().contains("already consumed"));
      assertEquals(1, activeReadViews.get());
    } finally {
      preflight.close();
    }
    assertEquals(0, activeReadViews.get());
  }

  @Test
  public void publicationPreflightPreparationRemainsBoundToOriginalSnapshot() {
    DomainValue first = value(1);
    publish(block(0L, DomainValue.tombstone(), first));
    ArchiveInFlightBlock candidate = block(1L, first, value(2));

    try (UnifiedArchiveTemporalStore.PublicationPreflight preflight =
        temporal.preflightPublication(candidate.getRecords())) {
      publish(block(1L, first, value(3)));

      temporal.stagePublication(
          UnifiedArchivePublish.builder(), candidate.getRange(), preflight);
    }
  }

  @Test
  public void atomicBatchWriteStartsAfterPublicationSnapshotsAreReleased() {
    index.close();
    index = null;
    db = null;
    AtomicBoolean publishing = new AtomicBoolean();
    AtomicBoolean observedPublicationWrite = new AtomicBoolean();
    db = openWithBeforeBatchWrite(dbPath, schemaChecksum, () -> {
      if (publishing.get()) {
        AtomicInteger activeReadViews = ReflectUtils.getFieldValue(db, "activeReadViews");
        assertEquals(0, activeReadViews.get());
        observedPublicationWrite.set(true);
      }
    });
    wire(false);
    ArchiveInFlightBlock candidate = block(0L, DomainValue.tombstone(), value(1));
    ArchiveInFlightBlock validated = prepareCanonicalJournal(candidate);

    publishing.set(true);
    backend.publishBlock(validated);

    assertTrue(observedPublicationWrite.get());
  }

  @Test
  public void rollbackRejectsJournalDeleteWorkspaceDuringStateAwarePublication() {
    ArchiveInFlightBlock head = block(0L, DomainValue.tombstone(), value(1));
    ArchiveInFlightBlock tail = block(1L, value(1), value(2));
    inFlight.putBlock(head);
    inFlight.acknowledgeBlock(head.getJournalToken());
    inFlight.putBlock(tail);
    long staticHeadBytes = backend.estimatedPublicationRetainedBytes(head);
    long staticPublicationBytes = StrictMathWrapper.max(
        staticHeadBytes, backend.estimatedPublicationRetainedBytes(tail));
    long stateAwarePublicationBytes = staticPublicationBytes + 1_024L;
    long hardBytes = head.estimatedRetainedBytes() + tail.estimatedRetainedBytes()
        + stateAwarePublicationBytes;
    UnifiedArchiveBackend boundedBackend =
        new UnifiedArchiveBackend(db, index, temporal, hardBytes, 100L);
    ArchivePublisherConfig publisherConfig = new ArchivePublisherConfig(
        false, false, 32, 64, hardBytes, hardBytes,
        1_000L, 2_000L, 0L, 0L, 1_000L);
    ArchiveDomainRegistry registry = new DefaultArchiveDomainRegistry();
    service = new DefaultArchiveService(true, index, new ArchiveExecutionContext(),
        temporal, inFlight, registry, catalog, ArchiveLifecycle.Phase.RUNNING,
        ArchiveQueryLimits.unlimited(), publisherConfig, () -> { }, boundedBackend);
    service.setFatalFailureHandler(failure -> { });
    Map<Long, ArchiveInFlightBlock> loadedBlocks =
        ReflectUtils.getFieldValue(service, "inFlightBlocks");
    ArchiveInFlightBlock loadedHead = loadedBlocks.get(0L);
    ReflectUtils.invokeMethod(service, "beginActivePublication",
        new Class<?>[]{ArchiveInFlightBlock.class}, loadedHead);
    ReflectUtils.invokeMethod(service, "updateActivePublication",
        new Class<?>[]{ArchiveInFlightBlock.class, long.class, long.class},
        loadedHead, staticHeadBytes, stateAwarePublicationBytes);
    try {
      ArchiveException failure = assertThrows(ArchiveException.class,
          () -> service.rollbackJournaledBlock(tail.getJournalToken()));

      assertTrue(failure.getMessage().contains(
          "journal delete would exceed hard resource watermark"));
      assertEquals(0L,
          (long) ReflectUtils.getFieldValue(service, "activeJournalMutationBytes"));
      assertNotNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT,
          ArchiveInFlightCodec.blockKey(1L)));
    } finally {
      ReflectUtils.invokeMethod(service, "endActivePublication",
          new Class<?>[]{long.class}, stateAwarePublicationBytes);
    }
  }

  @Test
  public void acknowledgementUsesCompactWorkspaceDuringStateAwarePublication() {
    ArchiveInFlightBlock head = block(0L, DomainValue.tombstone(), value(1));
    ArchiveInFlightBlock tail = block(1L, value(1), value(2));
    inFlight.putBlock(head);
    inFlight.acknowledgeBlock(head.getJournalToken());
    inFlight.putBlock(tail);
    long staticHeadBytes = backend.estimatedPublicationRetainedBytes(head);
    long staticWorkspaceBytes = StrictMathWrapper.max(
        StrictMathWrapper.max(staticHeadBytes, backend.estimatedPublicationRetainedBytes(tail)),
        StrictMathWrapper.max(8L * 1024L + 3L * head.encodedBlockBytes(),
            8L * 1024L + 3L * tail.encodedBlockBytes()));
    long stateAwarePublicationBytes = staticWorkspaceBytes + 1_024L;
    long hardBytes = head.estimatedRetainedBytes() + tail.estimatedRetainedBytes()
        + stateAwarePublicationBytes + 8L * 1024L;
    UnifiedArchiveBackend boundedBackend =
        new UnifiedArchiveBackend(db, index, temporal, hardBytes, 100L);
    ArchivePublisherConfig publisherConfig = new ArchivePublisherConfig(
        false, false, 32, 64, hardBytes, hardBytes,
        1_000L, 2_000L, 0L, 0L, 1_000L);
    ArchiveDomainRegistry registry = new DefaultArchiveDomainRegistry();
    service = new DefaultArchiveService(true, index, new ArchiveExecutionContext(),
        temporal, inFlight, registry, catalog, ArchiveLifecycle.Phase.RUNNING,
        ArchiveQueryLimits.unlimited(), publisherConfig, () -> { }, boundedBackend);
    service.setFatalFailureHandler(failure -> { });
    Map<Long, ArchiveInFlightBlock> loadedBlocks =
        ReflectUtils.getFieldValue(service, "inFlightBlocks");
    ArchiveInFlightBlock loadedHead = loadedBlocks.get(0L);
    ReflectUtils.invokeMethod(service, "beginActivePublication",
        new Class<?>[]{ArchiveInFlightBlock.class}, loadedHead);
    ReflectUtils.invokeMethod(service, "updateActivePublication",
        new Class<?>[]{ArchiveInFlightBlock.class, long.class, long.class},
        loadedHead, staticHeadBytes, stateAwarePublicationBytes);
    try {
      service.acknowledgeCanonicalCommit(tail.getJournalToken());

      assertEquals(0L,
          (long) ReflectUtils.getFieldValue(service, "activeJournalMutationBytes"));
      assertNotNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT,
          ArchiveInFlightCodec.blockKey(1L)));
      assertNotNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT,
          ArchiveInFlightCodec.acknowledgementKey(1L)));
    } finally {
      ReflectUtils.invokeMethod(service, "endActivePublication",
          new Class<?>[]{long.class}, stateAwarePublicationBytes);
    }
  }

  @Test
  public void synchronousPublicationCombinesBacklogWithPersistedHistoryWorkspace() {
    DomainValue largeAnchor = largeValue(1, 1024 * 1024);
    DomainValue current = value(2);
    publish(block(0L, largeAnchor, current));
    ArchiveInFlightBlock candidate = block(1L, current, value(3));
    prepareCanonicalJournal(candidate);
    long staticPublicationBytes = backend.estimatedPublicationRetainedBytes(candidate);
    long stateAwarePublicationBytes = staticPublicationBytes
        + persistedPreparationBytes(candidate);
    long retainedBytes = candidate.estimatedRetainedBytes();
    long hardBytes = stateAwarePublicationBytes + StrictMathWrapper.max(1L, retainedBytes / 2L);
    assertTrue(retainedBytes + staticPublicationBytes <= hardBytes);
    assertTrue(stateAwarePublicationBytes <= hardBytes);
    assertTrue(retainedBytes + stateAwarePublicationBytes > hardBytes);
    UnifiedArchiveBackend boundedBackend =
        new UnifiedArchiveBackend(db, index, temporal, hardBytes, 100L);
    ArchivePublisherConfig publisherConfig = new ArchivePublisherConfig(
        false, false, 32, 64, hardBytes, hardBytes,
        1_000L, 2_000L, 0L, 0L, 1_000L);
    ArchiveDomainRegistry registry = new DefaultArchiveDomainRegistry();
    service = new DefaultArchiveService(true, index, new ArchiveExecutionContext(),
        temporal, inFlight, registry, catalog, ArchiveLifecycle.Phase.RUNNING,
        ArchiveQueryLimits.unlimited(), publisherConfig, () -> { }, boundedBackend);
    service.setFatalFailureHandler(failure -> { });

    ArchiveException failure = assertThrows(
        ArchiveException.class, () -> service.publishSolidifiedBlocks(1L));

    assertTrue(failure.getMessage().contains(
        "state-aware publication would exceed hard resource watermark"));
    assertNotNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT,
        ArchiveInFlightCodec.blockKey(1L)));
  }

  @Test
  public void extendingTemporalChainUsesOnePreflightAndOnePreparationPass() {
    DomainValue first = largeIncompressibleValue(1, 256 * 1024);
    publish(block(0L, DomainValue.tombstone(), first));
    boolean metricsPreviouslyEnabled =
        CommonParameter.getInstance().isMetricsPrometheusEnable();
    try {
      reopenWithMetrics(true);
      Statistics statistics = ReflectUtils.getFieldValue(db, "statistics");
      long before = statistics.getTickerCount(TickerType.NUMBER_KEYS_READ);

      publish(block(1L, first, value(2)));

      assertEquals("one metadata preflight plus one preparation pass must stay bounded",
          19L, statistics.getTickerCount(TickerType.NUMBER_KEYS_READ) - before);
    } finally {
      reopenWithMetrics(metricsPreviouslyEnabled);
    }
  }

  @Test
  public void snapshotIndexReadRejectsOversizedValueBeforeMaterialization() {
    byte[] rangeKey = new byte[1 + Long.BYTES];
    rangeKey[0] = 0x10;
    write(db, new UnifiedArchiveMaintenanceBatch().put(
        UnifiedArchiveColumnFamily.INDEX, rangeKey, new byte[4097]));

    try (UnifiedArchiveBackend.ReadSession session = backend.openReadSession();
        UnifiedArchiveTxNumIndex.ReadScope ignored = session.bindIndex()) {
      ArchiveException failure = assertThrows(
          ArchiveException.class, () -> index.getBlockRange(0L));
      assertTrue(failure.getMessage().contains("exceeds byte limit"));
    }
  }

  @Test
  public void unboundIndexReadRejectsOversizedValueBeforeMaterialization() {
    byte[] rangeKey = new byte[1 + Long.BYTES];
    rangeKey[0] = 0x10;
    write(db, new UnifiedArchiveMaintenanceBatch().put(
        UnifiedArchiveColumnFamily.INDEX, rangeKey, new byte[4097]));

    ArchiveException failure = assertThrows(
        ArchiveException.class, () -> index.getBlockRange(0L));

    assertTrue(failure.getMessage().contains("exceeds byte limit"));
  }

  @Test
  public void oversizedTemporalPayloadIsRejectedBeforeNativePayloadRead() {
    DomainValue large = largeValue(1, 128 * 1024);
    publish(block(0L, DomainValue.tombstone(), large));
    boolean metricsPreviouslyEnabled =
        CommonParameter.getInstance().isMetricsPrometheusEnable();
    try {
      reopenWithMetrics(true);
      Statistics statistics = ReflectUtils.getFieldValue(db, "statistics");
      QueryContext context = new QueryContext(ArchiveQueryLimits.builder()
          .maxBackendValueBytes(4_096L)
          .maxBackendReadBytesPerRequest(16_384L)
          .build());
      long before = statistics.getTickerCount(TickerType.BYTES_READ);

      try (QueryContextHolder.Scope ignored = QueryContextHolder.attach(context)) {
        assertThrows(HistoricalQueryLimitException.class,
            () -> temporal.getAsOf(ArchiveDomain.ACCOUNT, accountKey(), 0L));
      }

      long bytesRead = statistics.getTickerCount(TickerType.BYTES_READ) - before;
      assertTrue("oversized payload must not reach native Get: bytesRead=" + bytesRead,
          bytesRead < 4_096L);
    } finally {
      reopenWithMetrics(metricsPreviouslyEnabled);
    }
  }

  @Test
  public void cumulativeBudgetRejectsTemporalPayloadBeforeNativePayloadRead() {
    DomainValue large = largeValue(1, 128 * 1024);
    publish(block(0L, DomainValue.tombstone(), large));
    boolean metricsPreviouslyEnabled =
        CommonParameter.getInstance().isMetricsPrometheusEnable();
    try {
      reopenWithMetrics(true);
      Statistics statistics = ReflectUtils.getFieldValue(db, "statistics");
      QueryContext context = new QueryContext(ArchiveQueryLimits.builder()
          .maxBackendValueBytes(256L * 1024L)
          .maxBackendReadBytesPerRequest(64L)
          .build());
      long before = statistics.getTickerCount(TickerType.BYTES_READ);

      HistoricalQueryLimitException failure;
      try (QueryContextHolder.Scope ignored = QueryContextHolder.attach(context)) {
        failure = assertThrows(HistoricalQueryLimitException.class,
            () -> temporal.getAsOf(ArchiveDomain.ACCOUNT, accountKey(), 0L));
      }

      assertEquals(HistoricalQueryLimitException.Limit.BACKEND_READ_BYTES,
          failure.getLimit());
      long bytesRead = statistics.getTickerCount(TickerType.BYTES_READ) - before;
      assertTrue("aggregate budget failure must precede payload Get: bytesRead=" + bytesRead,
          bytesRead < 4_096L);
    } finally {
      reopenWithMetrics(metricsPreviouslyEnabled);
    }
  }

  @Test
  public void publishedSequenceMatchesIndependentTxNumAndStateOracle() {
    publish(block(0L, DomainValue.tombstone(), value(1)));
    publish(block(1L, value(1), value(2)));
    publish(block(2L, value(2), DomainValue.tombstone()));
    publish(block(3L, DomainValue.tombstone(), value(3)));

    for (long blockNum = 0L; blockNum <= 3L; blockNum++) {
      long firstTxNum = blockNum * 2L;
      ArchiveBlockRange range = index.getBlockRange(blockNum).orElseThrow(AssertionError::new);
      assertEquals(firstTxNum, range.getFirstTxNum());
      assertEquals(firstTxNum + 1L, range.getLastTxNum());
      assertEquals(firstTxNum, range.getPrepareTxNum());
      assertEquals(firstTxNum + 1L, range.getFinalizeTxNum());
      assertEquals(ArchivePhase.BLOCK_PREPARE,
          index.getPosition(firstTxNum).orElseThrow(AssertionError::new).getPhase());
      assertEquals(ArchivePhase.BLOCK_FINALIZE,
          index.getPosition(firstTxNum + 1L).orElseThrow(AssertionError::new).getPhase());
    }

    assertEquals(8L, index.getNextTxNum());
    assertValue(temporal.getAsOf(ArchiveDomain.ACCOUNT, accountKey(), 0L), 1);
    assertValue(temporal.getAsOf(ArchiveDomain.ACCOUNT, accountKey(), 1L), 1);
    assertValue(temporal.getAsOf(ArchiveDomain.ACCOUNT, accountKey(), 2L), 2);
    assertValue(temporal.getAsOf(ArchiveDomain.ACCOUNT, accountKey(), 3L), 2);
    assertTrue(temporal.getAsOf(ArchiveDomain.ACCOUNT, accountKey(), 4L)
        .orElseThrow(AssertionError::new).isDeleted());
    assertTrue(temporal.getAsOf(ArchiveDomain.ACCOUNT, accountKey(), 5L)
        .orElseThrow(AssertionError::new).isDeleted());
    assertValue(temporal.getAsOf(ArchiveDomain.ACCOUNT, accountKey(), 6L), 3);
    assertValue(temporal.latest(ArchiveDomain.ACCOUNT, accountKey()), 3);
    backend.validateStartup(true, false);
  }

  @Test
  public void publishedProofCacheDoesNotTrackHistoricalBlocks() {
    DomainValue previous = DomainValue.tombstone();
    for (long blockNum = 0L; blockNum < 128L; blockNum++) {
      DomainValue current = value((int) blockNum + 1);
      publish(block(blockNum, previous, current));
      previous = current;
    }

    assertTrue(inFlight.loadBlocks().isEmpty());
    assertTrue(validatedProofs().isEmpty());
  }

  @Test
  public void journalProofCacheWaitsForFinalOwnerCloseSuccess() {
    inFlight.putBlock(block(0L, DomainValue.tombstone(), value(1)));
    assertEquals(1, inFlight.loadBlocks().size());
    assertFalse(validatedProofs().isEmpty());

    inFlight.close();

    assertFalse(validatedProofs().isEmpty());
    inFlight.ownerCloseSucceeded();

    assertTrue(validatedProofs().isEmpty());
    assertNotNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT,
        ArchiveInFlightCodec.blockKey(0L)));
  }

  @Test
  public void acknowledgementKeepsJournalPayloadImmutableAndFoldsAtLoad() {
    ArchiveInFlightBlock block = block(0L, DomainValue.tombstone(), value(1));
    long blockNum = block.getRange().getBlockNum();
    byte[] journalKey = ArchiveInFlightCodec.blockKey(blockNum);
    byte[] acknowledgementKey = ArchiveInFlightCodec.acknowledgementKey(blockNum);
    inFlight.putBlock(block);
    byte[] before = db.get(UnifiedArchiveColumnFamily.INFLIGHT, journalKey);

    inFlight.acknowledgeBlock(block.getJournalToken());

    byte[] after = db.get(UnifiedArchiveColumnFamily.INFLIGHT, journalKey);
    byte[] acknowledgement = db.get(
        UnifiedArchiveColumnFamily.INFLIGHT, acknowledgementKey);
    assertArrayEquals(before, after);
    assertTrue(acknowledgement.length < after.length);
    assertEquals(ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED,
        inFlight.loadBlocks().get(0).getJournalState());

    inFlight.deleteBlock(blockNum);
    assertNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT, journalKey));
    assertNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT,
        ArchiveInFlightCodec.tokenKey(blockNum)));
    assertNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT, acknowledgementKey));
  }

  @Test
  public void acknowledgementReadsOnlyCompactJournalLifecycleRows() {
    ArchiveInFlightBlock block = block(
        0L, DomainValue.tombstone(), largeValue(1, 512 * 1024));
    ArchiveJournalToken token = block.getJournalToken();
    inFlight.putBlock(block);
    int journalBytes = db.get(UnifiedArchiveColumnFamily.INFLIGHT,
        ArchiveInFlightCodec.blockKey(0L)).length;
    boolean metricsPreviouslyEnabled =
        CommonParameter.getInstance().isMetricsPrometheusEnable();
    try {
      reopenWithMetrics(true);
      assertEquals(1, inFlight.loadBlocks().size());
      Statistics statistics = ReflectUtils.getFieldValue(db, "statistics");
      long before = statistics.getTickerCount(TickerType.BYTES_READ);

      inFlight.acknowledgeBlock(token);

      long bytesRead = statistics.getTickerCount(TickerType.BYTES_READ) - before;
      assertTrue("ACK must not materialize its " + journalBytes
              + "-byte journal payload: bytesRead=" + bytesRead,
          bytesRead < 4L * 1024L);
    } finally {
      reopenWithMetrics(metricsPreviouslyEnabled);
    }
  }

  @Test
  public void failedJournalScanInvalidatesValidatedProofCache() {
    ArchiveInFlightBlock block = block(0L, DomainValue.tombstone(), value(1));
    inFlight.putBlock(block);
    db.putJournalDurably(new byte[] {(byte) 0x7f}, new byte[] {1});

    assertThrows(ArchiveJournalCorruptionException.class, inFlight::loadBlocks);
    assertThrows(ArchiveJournalCorruptionException.class,
        () -> inFlight.acknowledgeBlock(block.getJournalToken()));

    assertNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT,
        ArchiveInFlightCodec.acknowledgementKey(0L)));
  }

  @Test
  public void acknowledgementDefersTamperedJournalPayloadDetectionToPublication() {
    ArchiveInFlightBlock block = block(0L, DomainValue.tombstone(), value(1));
    inFlight.putBlock(block);
    byte[] journalKey = ArchiveInFlightCodec.blockKey(0L);
    byte[] tampered = db.get(UnifiedArchiveColumnFamily.INFLIGHT, journalKey);
    tampered[tampered.length - 1] ^= 1;
    replaceInflightValueUnchecked(journalKey, tampered);

    inFlight.acknowledgeBlock(block.getJournalToken());

    assertNotNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT,
        ArchiveInFlightCodec.acknowledgementKey(0L)));
    assertThrows(ArchiveException.class, () -> backend.publishBlock(
        block.withJournalState(ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED)));
    assertFalse(index.getBlockRange(0L).isPresent());
  }

  @Test
  public void publicationCorruptionAfterCompactAckMarksServiceRepairRequired() {
    service = unifiedService();
    BlockCapsule block = canonicalBlock(0L);
    service.beginBlock(block, ArchiveSource.NORMAL);
    service.beginSystemTx(block, ArchivePhase.BLOCK_PREPARE);
    service.endTx();
    service.beginSystemTx(block, ArchivePhase.BLOCK_FINALIZE);
    service.endTx();
    ArchiveJournalToken token = service.commitBlockJournaled(block, 0);
    byte[] journalKey = ArchiveInFlightCodec.blockKey(0L);
    byte[] tampered = db.get(UnifiedArchiveColumnFamily.INFLIGHT, journalKey);
    tampered[tampered.length - 1] ^= 1;
    replaceInflightValueUnchecked(journalKey, tampered);

    service.acknowledgeCanonicalCommit(token);
    assertThrows(ArchiveException.class, () -> service.publishSolidifiedBlocks(0L));

    assertTrue(index.hasRepairRequired());
    assertNotNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT,
        ArchiveInFlightCodec.acknowledgementKey(0L)));
  }

  @Test
  public void commitMarkerScanUsesBoundedLocatorPointReads() {
    ArchiveInFlightBlock block = block(0L, DomainValue.tombstone(), value(1));
    publish(block);
    boolean metricsPreviouslyEnabled =
        CommonParameter.getInstance().isMetricsPrometheusEnable();
    try {
      reopenWithMetrics(true);
      Statistics statistics = ReflectUtils.getFieldValue(db, "statistics");
      long before = statistics.getTickerCount(TickerType.NUMBER_KEYS_READ);

      temporal.validateCommittedBlock(block.getRange());

      assertEquals(6L,
          statistics.getTickerCount(TickerType.NUMBER_KEYS_READ) - before);
    } finally {
      reopenWithMetrics(metricsPreviouslyEnabled);
    }
  }

  @Test
  public void commitMarkerScanRejectsOversizedLocatorBeforeMaterialization() {
    ArchiveInFlightBlock block = block(0L, DomainValue.tombstone(), value(1));
    publish(block);
    byte[] historyKey = firstKey(db, UnifiedArchiveColumnFamily.HISTORY);
    write(db, new UnifiedArchiveMaintenanceBatch().put(
        UnifiedArchiveColumnFamily.HISTORY, historyKey, new byte[4_096]));

    ArchiveException failure = assertThrows(
        ArchiveException.class, () -> temporal.validateCommittedBlock(block.getRange()));

    assertTrue(failure.getMessage().contains("length mismatch"));
    assertTrue(failure.getMessage().contains("actualBytes=4096"));
  }

  @Test
  public void journalProofRejectsSemanticallyValidSameLengthPayloadRewrite() {
    ArchiveInFlightBlock original = block(0L, DomainValue.tombstone(), value(1));
    inFlight.putBlock(original);
    ArchiveChangeRecord originalRecord = original.getRecords().get(0);
    ArchiveChangeRecord rewrittenRecord = new ArchiveChangeRecord(
        originalRecord.getPosition(), originalRecord.getDomain(),
        originalRecord.getCanonicalKey(), originalRecord.getPrevValue(), value(2));
    ArchiveInFlightBlock rewritten = new ArchiveInFlightBlock(
        original.getRange(), original.getPositions(),
        Collections.singletonList(rewrittenRecord), original.getJournalToken(),
        ArchiveInFlightBlock.JournalState.JOURNALED);
    byte[] originalBytes = ArchiveInFlightCodec.encodeBlock(original);
    byte[] rewrittenBytes = ArchiveInFlightCodec.encodeBlock(rewritten);
    assertEquals(originalBytes.length, rewrittenBytes.length);
    replaceInflightValueUnchecked(
        ArchiveInFlightCodec.blockKey(original.getRange().getBlockNum()), rewrittenBytes);
    AtomicInteger consumed = new AtomicInteger();

    assertThrows(ArchiveException.class,
        () -> inFlight.forEachBlock(ignored -> consumed.incrementAndGet()));

    assertEquals(0, consumed.get());
  }

  @Test
  public void journalScanRejectsPreCanonicalAttestationProofVersion() {
    ArchiveInFlightBlock block = block(0L, DomainValue.tombstone(), value(1));
    inFlight.putBlock(block);
    byte[] proofKey = ArchiveInFlightCodec.tokenKey(0L);
    byte[] legacyProof = db.get(UnifiedArchiveColumnFamily.INFLIGHT, proofKey);
    legacyProof[0] = 1;
    replaceInflightValueUnchecked(proofKey, legacyProof);

    ArchiveJournalCorruptionException failure = assertThrows(
        ArchiveJournalCorruptionException.class, inFlight::loadBlocks);

    assertTrue(failure.getMessage().contains("proof"));
    assertTrue(failure.getCause().getMessage().contains("version mismatch"));
  }

  @Test
  public void journalScanRejectsLegacyDigestDisguisedAsCanonicalProof() {
    ArchiveInFlightBlock block = block(0L, DomainValue.tombstone(), value(1));
    inFlight.putBlock(block);
    byte[] journalKey = ArchiveInFlightCodec.blockKey(0L);
    byte[] payload = db.get(UnifiedArchiveColumnFamily.INFLIGHT, journalKey);
    ArchiveJournalProof disguisedLegacyProof = new ArchiveJournalProof(
        block.getJournalToken(), payload.length, legacyV1JournalDigest(journalKey, payload));
    replaceInflightValueUnchecked(
        ArchiveInFlightCodec.tokenKey(0L),
        ArchiveInFlightCodec.encodeProof(disguisedLegacyProof));

    ArchiveJournalCorruptionException failure = assertThrows(
        ArchiveJournalCorruptionException.class, inFlight::loadBlocks);

    assertTrue(failure.getMessage().contains("durable proof"));
  }

  @Test
  public void malformedCanonicalValueLeavesNoJournalRows() {
    ArchiveInFlightBlock valid = block(0L, DomainValue.tombstone(), value(1));
    ArchiveChangeRecord original = valid.getRecords().get(0);
    ArchiveChangeRecord malformed = new ArchiveChangeRecord(
        original.getPosition(), original.getDomain(), original.getCanonicalKey(),
        original.getPrevValue(), DomainValue.present(new byte[] {(byte) 0x80}));
    ArchiveInFlightBlock candidate = new ArchiveInFlightBlock(
        valid.getRange(), valid.getPositions(), Collections.singletonList(malformed),
        valid.getJournalToken(), ArchiveInFlightBlock.JournalState.JOURNALED);

    assertThrows(ArchiveException.class, () -> inFlight.putBlock(candidate));

    assertNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT,
        ArchiveInFlightCodec.blockKey(0L)));
    assertNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT,
        ArchiveInFlightCodec.tokenKey(0L)));
    assertNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT,
        ArchiveInFlightCodec.acknowledgementKey(0L)));
  }

  @Test
  public void directDeleteDetectsOrphanPayloadWithoutReadingItsBody() {
    ArchiveInFlightBlock block = block(
        0L, DomainValue.tombstone(), largeIncompressibleValue(1, 128 * 1024));
    inFlight.putBlock(block);
    deleteInflightValueUnchecked(ArchiveInFlightCodec.tokenKey(0L));

    ArchiveException failure = assertThrows(
        ArchiveException.class, () -> inFlight.deleteBlock(0L));

    assertTrue(failure.getMessage().contains("lifecycle row is orphaned"));
    assertNotNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT,
        ArchiveInFlightCodec.blockKey(0L)));
  }

  @Test
  public void loadedDeleteRejectsSameLengthJournalPayloadRewrite() {
    ArchiveInFlightBlock original = block(0L, DomainValue.tombstone(), value(1));
    inFlight.putBlock(original);
    inFlight.acknowledgeBlock(original.getJournalToken());
    ArchiveInFlightBlock loaded = inFlight.loadBlocks().get(0);
    byte[] journalKey = ArchiveInFlightCodec.blockKey(0L);
    byte[] tampered = db.get(UnifiedArchiveColumnFamily.INFLIGHT, journalKey);
    tampered[tampered.length - 1] ^= 1;
    replaceInflightValueUnchecked(journalKey, tampered);

    assertThrows(ArchiveJournalCorruptionException.class,
        () -> inFlight.deleteLoadedBlock(loaded));

    assertJournalBundlePresent(0L);
  }

  @Test
  public void loadedDeleteRejectsSameLengthProofRewrite() {
    ArchiveInFlightBlock original = block(0L, DomainValue.tombstone(), value(1));
    inFlight.putBlock(original);
    inFlight.acknowledgeBlock(original.getJournalToken());
    ArchiveInFlightBlock loaded = inFlight.loadBlocks().get(0);
    byte[] proofKey = ArchiveInFlightCodec.tokenKey(0L);
    byte[] tampered = db.get(UnifiedArchiveColumnFamily.INFLIGHT, proofKey);
    tampered[tampered.length - 1] ^= 1;
    replaceInflightValueUnchecked(proofKey, tampered);

    assertThrows(ArchiveJournalCorruptionException.class,
        () -> inFlight.deleteLoadedBlock(loaded));

    assertJournalBundlePresent(0L);
  }

  @Test
  public void loadedDeleteRejectsSameLengthAcknowledgementRewrite() {
    ArchiveInFlightBlock original = block(0L, DomainValue.tombstone(), value(1));
    inFlight.putBlock(original);
    inFlight.acknowledgeBlock(original.getJournalToken());
    ArchiveInFlightBlock loaded = inFlight.loadBlocks().get(0);
    byte[] acknowledgementKey = ArchiveInFlightCodec.acknowledgementKey(0L);
    byte[] tampered = db.get(UnifiedArchiveColumnFamily.INFLIGHT, acknowledgementKey);
    tampered[tampered.length - 1] ^= 1;
    replaceInflightValueUnchecked(acknowledgementKey, tampered);

    assertThrows(ArchiveJournalCorruptionException.class,
        () -> inFlight.deleteLoadedBlock(loaded));

    assertJournalBundlePresent(0L);
  }

  @Test
  public void loadedDeleteRejectsMissingAcknowledgementForCommittedBlock() {
    ArchiveInFlightBlock original = block(0L, DomainValue.tombstone(), value(1));
    inFlight.putBlock(original);
    inFlight.acknowledgeBlock(original.getJournalToken());
    ArchiveInFlightBlock loaded = inFlight.loadBlocks().get(0);
    byte[] acknowledgementKey = ArchiveInFlightCodec.acknowledgementKey(0L);
    deleteInflightValueUnchecked(acknowledgementKey);

    assertThrows(ArchiveJournalCorruptionException.class,
        () -> inFlight.deleteLoadedBlock(loaded));

    assertNotNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT,
        ArchiveInFlightCodec.blockKey(0L)));
    assertNotNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT,
        ArchiveInFlightCodec.tokenKey(0L)));
    assertNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT, acknowledgementKey));
  }

  @Test
  public void loadedDeleteRejectsUnexpectedAcknowledgementForJournaledBlock() {
    ArchiveInFlightBlock original = block(0L, DomainValue.tombstone(), value(1));
    inFlight.putBlock(original);
    ArchiveInFlightBlock loaded = inFlight.loadBlocks().get(0);
    byte[] proof = db.get(UnifiedArchiveColumnFamily.INFLIGHT,
        ArchiveInFlightCodec.tokenKey(0L));
    replaceInflightValueUnchecked(
        ArchiveInFlightCodec.acknowledgementKey(0L), proof);

    assertThrows(ArchiveJournalCorruptionException.class,
        () -> inFlight.deleteLoadedBlock(loaded));

    assertJournalBundlePresent(0L);
  }

  @Test
  public void publicationRechecksJournalAfterStartupProofValidation() {
    ArchiveInFlightBlock original = block(0L, DomainValue.tombstone(), value(1));
    inFlight.putBlock(original);
    inFlight.acknowledgeBlock(original.getJournalToken());
    ArchiveInFlightBlock validated = inFlight.loadBlocks().get(0);
    ArchiveChangeRecord originalRecord = original.getRecords().get(0);
    ArchiveChangeRecord rewrittenRecord = new ArchiveChangeRecord(
        originalRecord.getPosition(), originalRecord.getDomain(),
        originalRecord.getCanonicalKey(), originalRecord.getPrevValue(), value(2));
    ArchiveInFlightBlock rewritten = new ArchiveInFlightBlock(
        original.getRange(), original.getPositions(),
        Collections.singletonList(rewrittenRecord), original.getJournalToken(),
        ArchiveInFlightBlock.JournalState.JOURNALED);
    replaceInflightValueUnchecked(
        ArchiveInFlightCodec.blockKey(original.getRange().getBlockNum()),
        ArchiveInFlightCodec.encodeBlock(rewritten));

    assertThrows(ArchiveException.class, () -> backend.publishBlock(validated));

    assertFalse(index.getBlockRange(0L).isPresent());
    assertTrue(db.get(UnifiedArchiveColumnFamily.INFLIGHT,
        ArchiveInFlightCodec.blockKey(0L)) != null);
  }

  @Test
  public void publicationRejectsOversizedJournalRewriteWithExpectedLengthBuffer() {
    ArchiveInFlightBlock original = block(0L, DomainValue.tombstone(), value(1));
    inFlight.putBlock(original);
    inFlight.acknowledgeBlock(original.getJournalToken());
    ArchiveInFlightBlock validated = inFlight.loadBlocks().get(0);
    replaceInflightValueUnchecked(
        ArchiveInFlightCodec.blockKey(original.getRange().getBlockNum()),
        new byte[128 * 1024]);

    ArchiveException failure = assertThrows(
        ArchiveException.class, () -> backend.publishBlock(validated));

    assertTrue(failure.getMessage().contains("journal lifecycle row byte length changed"));
    assertFalse(index.getBlockRange(0L).isPresent());
    assertTrue(db.get(UnifiedArchiveColumnFamily.INFLIGHT,
        ArchiveInFlightCodec.blockKey(0L)) != null);
  }

  @Test
  public void publishedAndUnacknowledgedJournalCoexistenceFailsClosed() {
    ArchiveInFlightBlock published = block(0L, DomainValue.tombstone(), value(1));
    publish(published);
    inFlight.putBlock(published);

    assertThrows(ArchiveException.class, this::unifiedService);

    assertTrue(index.getBlockRange(0L).isPresent());
    assertTrue(db.get(UnifiedArchiveColumnFamily.INFLIGHT,
        ArchiveInFlightCodec.blockKey(0L)) != null);
  }

  @Test
  public void publishedAndAcknowledgedJournalCoexistenceFailsClosed() {
    ArchiveInFlightBlock published = block(0L, DomainValue.tombstone(), value(1));
    publish(published);
    ArchiveInFlightBlock conflicting = blockWithAdditionalRecord(published);
    inFlight.putBlock(conflicting);
    inFlight.acknowledgeBlock(conflicting.getJournalToken());

    assertThrows(ArchiveException.class, this::unifiedService);

    assertTrue(index.getBlockRange(0L).isPresent());
    assertTrue(db.get(UnifiedArchiveColumnFamily.INFLIGHT,
        ArchiveInFlightCodec.blockKey(0L)) != null);
  }

  @Test
  public void restartReconcileReconstructsAckAndReloadsNonSolidifiedCanonicalBlock() {
    BlockCapsule canonical = canonicalBlock(0L);
    ArchiveInFlightBlock journaled = block(
        canonical, DomainValue.tombstone(), value(1));
    long blockNum = journaled.getRange().getBlockNum();
    byte[] journalKey = ArchiveInFlightCodec.blockKey(blockNum);
    byte[] tokenKey = ArchiveInFlightCodec.tokenKey(blockNum);
    byte[] acknowledgementKey = ArchiveInFlightCodec.acknowledgementKey(blockNum);
    inFlight.putBlock(journaled);
    byte[] durableJournal =
        db.get(UnifiedArchiveColumnFamily.INFLIGHT, journalKey);
    byte[] encodedProof = db.get(UnifiedArchiveColumnFamily.INFLIGHT, tokenKey);

    assertArrayEquals(encodedProof,
        db.get(UnifiedArchiveColumnFamily.INFLIGHT, tokenKey));
    assertNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT, acknowledgementKey));

    index.close();
    index = null;
    db = UnifiedArchiveDb.open(dbPath, schemaChecksum);
    wire(true);
    service = unifiedService();

    assertEquals(ArchiveInFlightBlock.JournalState.JOURNALED,
        inFlight.loadBlocks().get(0).getJournalState());
    service.reconcileInFlightOnStartup(-1L, canonical.getNum(), ignored -> canonical);

    assertFalse(index.getBlockRange(blockNum).isPresent());
    assertArrayEquals(durableJournal,
        db.get(UnifiedArchiveColumnFamily.INFLIGHT, journalKey));
    assertArrayEquals(encodedProof,
        db.get(UnifiedArchiveColumnFamily.INFLIGHT, tokenKey));
    assertArrayEquals(encodedProof,
        db.get(UnifiedArchiveColumnFamily.INFLIGHT, acknowledgementKey));

    service.close();
    service = null;
    index = null;
    db = UnifiedArchiveDb.open(dbPath, schemaChecksum);
    wire(true);

    ArchiveInFlightBlock reloaded = inFlight.loadBlocks().get(0);
    assertEquals(journaled.getJournalToken(), reloaded.getJournalToken());
    assertEquals(ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED,
        reloaded.getJournalState());
  }

  @Test
  public void restartReconcilePublishesSolidifiedBlockAndDeletesJournalBundle() {
    BlockCapsule canonical = canonicalBlock(0L);
    ArchiveInFlightBlock journaled = block(
        canonical, DomainValue.tombstone(), value(1));
    long blockNum = journaled.getRange().getBlockNum();
    byte[] journalKey = ArchiveInFlightCodec.blockKey(blockNum);
    byte[] tokenKey = ArchiveInFlightCodec.tokenKey(blockNum);
    byte[] acknowledgementKey = ArchiveInFlightCodec.acknowledgementKey(blockNum);
    inFlight.putBlock(journaled);

    assertNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT, acknowledgementKey));

    index.close();
    index = null;
    db = UnifiedArchiveDb.open(dbPath, schemaChecksum);
    wire(true);
    service = unifiedService();
    service.reconcileInFlightOnStartup(
        blockNum, canonical.getNum(), ignored -> canonical);

    assertTrue(index.getBlockRange(blockNum).isPresent());
    assertValue(temporal.getAsOf(ArchiveDomain.ACCOUNT, accountKey(), 0L), 1);
    assertTrue(inFlight.loadBlocks().isEmpty());
    assertNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT, journalKey));
    assertNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT, tokenKey));
    assertNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT, acknowledgementKey));
  }

  @Test
  public void sharedSnapshotDoesNotCrossAConcurrentPublication() throws Exception {
    publish(block(0L, DomainValue.tombstone(), value(1)));
    ArchiveInFlightBlock next = prepareCanonicalJournal(
        block(1L, value(1), value(2)));

    try (UnifiedArchiveBackend.ReadSession oldSession = backend.openReadSession();
         UnifiedArchiveTxNumIndex.ReadScope ignored = oldSession.bindIndex()) {
      assertFalse(index.getBlockRange(1L).isPresent());
      assertValue(oldSession.getTemporalView().getAsOf(
          ArchiveDomain.ACCOUNT, accountKey(), 0L), 1);

      AtomicReference<Throwable> failure = new AtomicReference<>();
      Thread publisher = new Thread(() -> {
        try {
          backend.publishBlock(next);
        } catch (Throwable t) {
          failure.set(t);
        }
      }, "unified-archive-test-publisher");
      publisher.start();
      publisher.join();
      if (failure.get() != null) {
        throw new AssertionError("concurrent unified publication failed", failure.get());
      }

      assertFalse(index.getBlockRange(1L).isPresent());
      assertValue(oldSession.getTemporalView().getAsOf(
          ArchiveDomain.ACCOUNT, accountKey(), 0L), 1);
    }

    try (UnifiedArchiveBackend.ReadSession currentSession = backend.openReadSession();
         UnifiedArchiveTxNumIndex.ReadScope ignored = currentSession.bindIndex()) {
      assertTrue(index.getBlockRange(1L).isPresent());
      assertValue(currentSession.getTemporalView().getAsOf(
          ArchiveDomain.ACCOUNT, accountKey(), 2L), 2);
    }
  }

  @Test
  public void defaultServicePublishesAndReadsThroughUnifiedBackend() throws Exception {
    ArchiveDomainRegistry registry = new DefaultArchiveDomainRegistry();
    ArchivePublisherConfig publisherConfig = new ArchivePublisherConfig(
        false, false, 32, 64, 1024L * 1024L, 2L * 1024L * 1024L,
        1_000L, 2_000L, 0L, 0L, 1_000L);
    service = new DefaultArchiveService(true, index, new ArchiveExecutionContext(),
        temporal, inFlight, registry, catalog,
        ArchiveLifecycle.Phase.RUNNING, ArchiveQueryLimits.unlimited(), publisherConfig,
        () -> backend.validateStartup(false, true), backend);
    BlockCapsule block = new BlockCapsule(
        0L, Sha256Hash.ZERO_HASH, 1L, ByteString.EMPTY);
    byte[] account = Account.newBuilder().setBalance(99L).build().toByteArray();

    service.beginBlock(block, ArchiveSource.NORMAL);
    service.beginSystemTx(block, ArchivePhase.BLOCK_PREPARE);
    service.endTx();
    service.beginSystemTx(block, ArchivePhase.BLOCK_FINALIZE);
    service.getCaptureEngineForTesting().capturePut("account", accountKey(), null, account);
    service.endTx();
    ArchiveJournalToken token = service.commitBlockJournaled(block, 0);
    service.acknowledgeCanonicalCommit(token);
    long reservedResources = ReflectUtils.getFieldValue(service, "inFlightResourceBytes");
    assertTrue(reservedResources > 0L);
    service.publishSolidifiedBlocks(0L);

    assertTrue(inFlight.loadBlocks().isEmpty());
    assertEquals(0L, (long) ReflectUtils.getFieldValue(service, "inFlightResourceBytes"));
    assertTrue(validatedProofs().isEmpty());
    try (ArchiveStateReader reader = service.openBlockEndReader(
        0L, block.getBlockId().getBytes())) {
      long backendReadsBeforeHash = reader.getQueryContext().getBackendReads();
      assertArrayEquals(block.getBlockId().getBytes(), reader.getBlockHash(0L));
      assertEquals(backendReadsBeforeHash + 2L,
          reader.getQueryContext().getBackendReads());
      ArchiveReadResult<AccountCapsule> result = reader.getAccount(accountKey());
      assertTrue(result.isPresent());
      assertEquals(99L, result.getValue().getBalance());
      assertTrue(reader.isGenesisComplete());
    }
  }

  @Test
  public void transactionSelectorUsesOneThreeRowIndexResolution() throws Exception {
    service = unifiedService();
    BlockCapsule block = canonicalBlock(0L);
    TransactionCapsule transaction =
        new TransactionCapsule(Transaction.getDefaultInstance());

    service.beginBlock(block, ArchiveSource.NORMAL);
    service.beginSystemTx(block, ArchivePhase.BLOCK_PREPARE);
    service.endTx();
    service.beginUserTx(block, 0, transaction);
    service.endTx();
    service.beginSystemTx(block, ArchivePhase.BLOCK_FINALIZE);
    service.endTx();
    ArchiveJournalToken token = service.commitBlockJournaled(block, 1);
    service.acknowledgeCanonicalCommit(token);
    service.publishSolidifiedBlocks(0L);

    try (ArchiveStateReader reader = service.openTransactionReader(
        transaction.getTransactionId().getBytes(), 0L, block.getBlockId().getBytes())) {
      // Three coverage reads + txId/position/range + snapshot range recheck + temporal marker.
      assertEquals(8L, reader.getQueryContext().getBackendReads());
    }
  }

  @Test
  public void selectorAndSnapshotRangeReadsDoNotFillSharedCache() throws Exception {
    ArchiveInFlightBlock published = block(0L, DomainValue.tombstone(), value(1));
    publish(published);
    service = unifiedService();
    RocksDB raw = ReflectUtils.getFieldValue(db, "db");
    RocksDB observed = spy(raw);
    EnumMap<UnifiedArchiveColumnFamily, ColumnFamilyHandle> handles =
        ReflectUtils.getFieldValue(db, "handles");
    ColumnFamilyHandle indexHandle = handles.get(UnifiedArchiveColumnFamily.INDEX);
    byte[] targetRangeKey = rangeKey(0L);
    AtomicInteger rangeReads = new AtomicInteger();
    doAnswer(invocation -> {
      ColumnFamilyHandle handle = invocation.getArgument(0);
      ReadOptions options = invocation.getArgument(1);
      byte[] key = invocation.getArgument(2);
      byte[] value = invocation.getArgument(3);
      if (handle == indexHandle && Arrays.equals(key, targetRangeKey)) {
        assertFalse(options.fillCache());
        rangeReads.incrementAndGet();
      }
      return raw.get(handle, options, key, value);
    }).when(observed).get(any(ColumnFamilyHandle.class), any(ReadOptions.class),
        any(byte[].class), any(byte[].class));

    ReflectUtils.setFieldValue(db, "db", observed);
    try (ArchiveStateReader ignored = service.openBlockEndReader(
        0L, published.getRange().getBlockHash())) {
      assertTrue("selector and final snapshot must both re-read the range cachelessly",
          rangeReads.get() >= 2);
    } finally {
      ReflectUtils.setFieldValue(db, "db", raw);
    }
  }

  @Test
  public void unboundCompositeSelectorsUseOneSnapshotEach() throws Exception {
    service = unifiedService();
    BlockCapsule block = canonicalBlock(0L);
    TransactionCapsule transaction =
        new TransactionCapsule(Transaction.getDefaultInstance());

    service.beginBlock(block, ArchiveSource.NORMAL);
    service.beginSystemTx(block, ArchivePhase.BLOCK_PREPARE);
    service.endTx();
    service.beginUserTx(block, 0, transaction);
    service.endTx();
    service.beginSystemTx(block, ArchivePhase.BLOCK_FINALIZE);
    service.endTx();
    ArchiveJournalToken token = service.commitBlockJournaled(block, 1);
    service.acknowledgeCanonicalCommit(token);
    service.publishSolidifiedBlocks(0L);

    RocksDB raw = ReflectUtils.getFieldValue(db, "db");
    RocksDB observed = spy(raw);
    ReflectUtils.setFieldValue(db, "db", observed);
    try {
      ArchiveTxPosition position = index.getPosition(1L).orElseThrow(AssertionError::new);
      assertEquals(1L, position.getTxNum());
      verify(observed, times(1)).getSnapshot();

      clearInvocations(observed);
      assertEquals(1L, index.findTxNumByBlockAndIndex(0L, 0).getAsLong());
      verify(observed, times(1)).getSnapshot();

      clearInvocations(observed);
      ArchiveTransactionLocation location = index.findTransactionByTxId(
          transaction.getTransactionId().getBytes()).orElseThrow(AssertionError::new);

      assertEquals(0L, location.getRange().getBlockNum());
      assertEquals(1L, location.getPosition().getTxNum());
      verify(observed, times(1)).getSnapshot();

      clearInvocations(observed);
      assertEquals(0L, index.getHeadBlockRange(0L).getBlockNum());
      verify(observed, times(1)).getSnapshot();
    } finally {
      ReflectUtils.setFieldValue(db, "db", raw);
    }
  }

  @Test
  public void nullExternalPointKeepsTypedHistoryUnavailableFailure() {
    publish(block(0L, DomainValue.tombstone(), value(1)));
    service = unifiedService();

    ArchiveReaderException failure = assertThrows(
        ArchiveReaderException.class, () -> service.openReader(null));

    assertEquals(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE, failure.getReason());
    assertFalse(index.hasRepairRequired());
    service.validateAvailable();
  }

  @Test
  public void genesisCompleteUnifiedReaderDoesNotBlockForkAndRejectsStaleClose()
      throws Exception {
    ArchiveInFlightBlock published = block(0L, DomainValue.tombstone(), value(1));
    publish(published);
    service = unifiedService();
    ArchiveStatePoint point = ArchiveStatePoint.blockEnd(
        0L, published.getRange().getBlockHash(), published.getRange().getFinalizeTxNum());

    CountDownLatch mutationStarted = new CountDownLatch(1);
    FutureTask<Void> mutation = new FutureTask<>(() -> {
      mutationStarted.countDown();
      try (ArchiveMutationLease ignored = service.acquireMutationWriteLease()) {
        return null;
      }
    });
    Thread thread = new Thread(mutation, "unified-concurrent-fork-mutation");
    ArchiveStateReader reader = service.openReader(point);
    try {
      thread.start();
      assertTrue(mutationStarted.await(1L, TimeUnit.SECONDS));
      mutation.get(2L, TimeUnit.SECONDS);
      assertTrue("fork mutation must not wait for the query snapshot", mutation.isDone());
      ArchiveReadResult<AccountCapsule> account = reader.getAccount(accountKey());
      assertTrue(account.isPresent());
      assertEquals(1L, account.getValue().getBalance());
    } finally {
      ArchiveSnapshotInvalidatedException failure = assertThrows(
          ArchiveSnapshotInvalidatedException.class, reader::close);
      assertTrue(failure.getMessage().contains("invalidated"));
      thread.join(1_000L);
    }
  }

  @Test
  public void transportSealRejectsSuccessAfterArchiveBecomesFatal() throws Exception {
    ArchiveInFlightBlock published = block(0L, DomainValue.tombstone(), value(1));
    publish(published);
    service = unifiedService();
    ArchiveStatePoint point = ArchiveStatePoint.blockEnd(
        0L, published.getRange().getBlockHash(), published.getRange().getFinalizeTxNum());
    ArchiveQueryTransportScope transport = ArchiveQueryTransportScope.open();
    try {
      ArchiveStateReader reader = service.openReader(point);
      assertTrue(reader.getAccount(accountKey()).isPresent());
      reader.close();
      ReflectUtils.invokeMethod(service, "markFatal",
          new Class<?>[] {RuntimeException.class}, new ArchiveException("injected fatal"));

      ArchiveException failure = assertThrows(ArchiveException.class, transport::close);

      assertTrue(failure.getMessage().contains("unavailable after fatal failure"));
      ArchiveQueryCoordinator coordinator = ReflectUtils.getFieldValue(
          service, "queryCoordinator");
      assertEquals(0L, coordinator.getActiveLeaseCount());
      assertEquals(0L, coordinator.getActiveSnapshotCount());
    } finally {
      transport.close();
    }
  }

  @Test
  public void queryWaitingForMutationBarrierDoesNotReserveSnapshotCapacity() throws Exception {
    ArchiveInFlightBlock published = block(0L, DomainValue.tombstone(), value(1));
    publish(published);
    service = unifiedService(ArchiveQueryLimits.builder()
        .maxConcurrentQueries(2L)
        .maxOpenSnapshots(1L)
        .deadlineMs(2_000L)
        .build());
    ArchiveStatePoint point = ArchiveStatePoint.blockEnd(
        0L, published.getRange().getBlockHash(), published.getRange().getFinalizeTxNum());
    ArchiveQueryCoordinator coordinator = ReflectUtils.getFieldValue(service, "queryCoordinator");
    CountDownLatch openingStarted = new CountDownLatch(1);
    FutureTask<Boolean> opening = new FutureTask<>(() -> {
      openingStarted.countDown();
      try (ArchiveStateReader reader = service.openReader(point)) {
        return reader.getAccount(accountKey()).isPresent();
      }
    });
    Thread thread = new Thread(opening, "archive-reader-waiting-for-mutation-barrier");

    try (ArchiveMutationLease ignored = service.acquireMutationWriteLease()) {
      thread.start();
      assertTrue(openingStarted.await(5L, TimeUnit.SECONDS));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
      while (coordinator.getActiveLeaseCount() == 0L && System.nanoTime() < deadline) {
        Thread.sleep(1L);
      }
      assertEquals(1L, coordinator.getActiveLeaseCount());
      assertEquals(0L, coordinator.getActiveSnapshotCount());
      assertFalse(opening.isDone());
    }

    try {
      assertTrue(opening.get(2L, TimeUnit.SECONDS));
    } finally {
      thread.join(1_000L);
    }
    assertEquals(0L, coordinator.getActiveLeaseCount());
    assertEquals(0L, coordinator.getActiveSnapshotCount());
  }

  @Test
  public void selectorResolutionReservesSnapshotCapacityBeforeOpeningUnboundView()
      throws Exception {
    ArchiveInFlightBlock published = block(0L, DomainValue.tombstone(), value(1));
    publish(published);
    service = unifiedService(ArchiveQueryLimits.builder()
        .maxConcurrentQueries(2L)
        .maxOpenSnapshots(1L)
        .deadlineMs(2_000L)
        .build());
    ArchiveStatePoint point = ArchiveStatePoint.blockEnd(
        0L, published.getRange().getBlockHash(), published.getRange().getFinalizeTxNum());
    ArchiveQueryCoordinator coordinator = ReflectUtils.getFieldValue(service, "queryCoordinator");
    CountDownLatch selectorEntered = new CountDownLatch(1);
    CountDownLatch releaseSelector = new CountDownLatch(1);
    FutureTask<Boolean> opening = new FutureTask<>(() -> {
      try (ArchiveStateReader reader = service.openBlockEndReader(() -> {
        selectorEntered.countDown();
        try {
          assertTrue(releaseSelector.await(1L, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new AssertionError("selector interrupted", e);
        }
        return 0L;
      })) {
        return reader.getAccount(accountKey()).isPresent();
      }
    });
    Thread thread = new Thread(opening, "archive-selector-snapshot-reservation");
    thread.start();
    assertTrue(selectorEntered.await(1L, TimeUnit.SECONDS));
    assertEquals(1L, coordinator.getActiveSnapshotCount());

    HistoricalQueryLimitException rejected = assertThrows(
        HistoricalQueryLimitException.class, () -> service.openReader(point));
    assertEquals(HistoricalQueryLimitException.Limit.OPEN_SNAPSHOTS, rejected.getLimit());

    releaseSelector.countDown();
    try {
      assertTrue(opening.get(2L, TimeUnit.SECONDS));
    } finally {
      releaseSelector.countDown();
      thread.join(1_000L);
    }
    assertEquals(0L, coordinator.getActiveSnapshotCount());
  }

  @Test
  public void readerCloseRespectsDeadlineWhileForkHoldsExclusiveMutation() throws Exception {
    ArchiveInFlightBlock published = block(0L, DomainValue.tombstone(), value(1));
    publish(published);
    service = unifiedService(ArchiveQueryLimits.builder().deadlineMs(500L).build());
    ArchiveStatePoint point = ArchiveStatePoint.blockEnd(
        0L, published.getRange().getBlockHash(), published.getRange().getFinalizeTxNum());
    ArchiveStateReader reader = service.openReader(point);
    CountDownLatch forkHeld = new CountDownLatch(1);
    CountDownLatch releaseFork = new CountDownLatch(1);
    FutureTask<Void> fork = new FutureTask<>(() -> {
      try (ArchiveMutationLease ignored = service.acquireMutationWriteLease()) {
        forkHeld.countDown();
        assertTrue(releaseFork.await(2L, TimeUnit.SECONDS));
      }
      return null;
    });
    Thread forkThread = new Thread(fork, "fork-holding-query-epoch-seal");
    forkThread.start();
    assertTrue(forkHeld.await(1L, TimeUnit.SECONDS));

    try {
      HistoricalQueryLimitException failure = assertThrows(
          HistoricalQueryLimitException.class, reader::close);
      assertEquals(HistoricalQueryLimitException.Limit.DEADLINE, failure.getLimit());
      ArchiveQueryCoordinator coordinator =
          ReflectUtils.getFieldValue(service, "queryCoordinator");
      assertEquals(0L, coordinator.getActiveLeaseCount());
      assertEquals(0L, coordinator.getActiveSnapshotCount());
    } finally {
      releaseFork.countDown();
    }
    fork.get(1L, TimeUnit.SECONDS);
    forkThread.join(1_000L);
  }

  @Test
  public void genesisCompleteUnifiedReaderOpensDuringConsistencyWrite() throws Exception {
    ArchiveInFlightBlock published = block(0L, DomainValue.tombstone(), value(1));
    publish(published);
    service = unifiedService(ArchiveQueryLimits.builder().deadlineMs(5_000L).build());
    ArchiveStatePoint point = ArchiveStatePoint.blockEnd(
        0L, published.getRange().getBlockHash(), published.getRange().getFinalizeTxNum());
    ReentrantReadWriteLock consistencyLock =
        ReflectUtils.getFieldValue(service, "consistencyLock");
    CountDownLatch writeHeld = new CountDownLatch(1);
    CountDownLatch releaseWrite = new CountDownLatch(1);
    CountDownLatch readerOpened = new CountDownLatch(1);
    AtomicReference<Throwable> blockerFailure = new AtomicReference<>();
    AtomicReference<Throwable> readerFailure = new AtomicReference<>();
    Thread blocker = new Thread(() -> {
      consistencyLock.writeLock().lock();
      try {
        writeHeld.countDown();
        if (!releaseWrite.await(5L, TimeUnit.SECONDS)) {
          blockerFailure.set(new AssertionError("timed out holding consistency write lock"));
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        blockerFailure.set(e);
      } finally {
        consistencyLock.writeLock().unlock();
      }
    }, "blocked-unified-consistency-write");
    Thread reader = new Thread(() -> {
      try (ArchiveStateReader ignored = service.openReader(point)) {
        readerOpened.countDown();
      } catch (Throwable failure) {
        readerFailure.set(failure);
      }
    }, "concurrent-genesis-complete-reader");

    try {
      blocker.start();
      assertTrue(writeHeld.await(5L, TimeUnit.SECONDS));
      reader.start();
      assertTrue("genesis-complete snapshot must not wait for consistency publication",
          readerOpened.await(1L, TimeUnit.SECONDS));
    } finally {
      releaseWrite.countDown();
      blocker.join(5_000L);
      reader.join(5_000L);
    }

    assertFalse(blocker.isAlive());
    assertFalse(reader.isAlive());
    assertNull(blockerFailure.get());
    assertNull(readerFailure.get());
  }

  @Test
  public void blockedCanonicalResolverDoesNotBlockForkAndOpenFailsClosed() throws Exception {
    ArchiveInFlightBlock published = block(0L, DomainValue.tombstone(), value(1));
    publish(published);
    service = unifiedService(ArchiveQueryLimits.builder().deadlineMs(5_000L).build());
    CountDownLatch resolverEntered = new CountDownLatch(1);
    CountDownLatch releaseResolver = new CountDownLatch(1);
    FutureTask<Throwable> opening = new FutureTask<>(() -> {
      try (ArchiveStateReader ignored = service.openBlockEndReader(0L, blockNum -> {
        resolverEntered.countDown();
        try {
          if (!releaseResolver.await(5L, TimeUnit.SECONDS)) {
            throw new ArchiveException("timed out waiting to release canonical resolver");
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new ArchiveException("canonical resolver interrupted", e);
        }
        return published.getRange().getBlockHash();
      })) {
        return new AssertionError("reader opened across a canonical epoch change");
      } catch (Throwable failure) {
        return failure;
      }
    });
    Thread openingThread = new Thread(opening, "blocked-canonical-resolver");
    openingThread.start();
    assertTrue(resolverEntered.await(1L, TimeUnit.SECONDS));

    FutureTask<Long> fork = new FutureTask<>(() -> {
      try (ArchiveMutationLease mutation = service.acquireMutationWriteLease()) {
        return mutation.getEpoch();
      }
    });
    Thread forkThread = new Thread(fork, "fork-during-canonical-resolver");
    forkThread.start();
    try {
      assertTrue("fork must not wait for the external canonical resolver",
          fork.get(1L, TimeUnit.SECONDS) > 0L);
    } finally {
      releaseResolver.countDown();
    }

    Throwable failure = opening.get(2L, TimeUnit.SECONDS);
    assertTrue(failure instanceof ArchiveSnapshotInvalidatedException);
    assertTrue(failure.getMessage().contains("invalidated"));
    openingThread.join(1_000L);
    forkThread.join(1_000L);
  }

  @Test
  public void canonicalResolverCannotLeakQueryStateIntoReentrantPublication() throws Exception {
    ArchiveInFlightBlock published = block(0L, DomainValue.tombstone(), value(1));
    publish(published);
    service = unifiedService();
    BlockCapsule tail = canonicalBlock(1L);
    service.beginBlock(tail, ArchiveSource.NORMAL);
    service.beginSystemTx(tail, ArchivePhase.BLOCK_PREPARE);
    service.endTx();
    service.beginSystemTx(tail, ArchivePhase.BLOCK_FINALIZE);
    service.endTx();
    ArchiveJournalToken token = service.commitBlockJournaled(tail, 0);
    service.acknowledgeCanonicalCommit(token);

    try (ArchiveStateReader reader = service.openBlockEndReader(0L, blockNum -> {
      assertNull(QueryContextHolder.current());
      service.publishSolidifiedBlocks(1L);
      return published.getRange().getBlockHash();
    })) {
      assertEquals(0L, reader.getPoint().getBlockNum());
    }

    assertTrue(index.getBlockRange(1L).isPresent());
    assertFalse(index.hasRepairRequired());
    service.validateAvailable();
  }

  @Test
  public void midChainUnifiedReaderFailsClosedBeforeConsistencyLock() throws Exception {
    ArchiveInFlightBlock published = blockAtTxNum(
        5L, 0L, hash(6L), DomainValue.tombstone(), value(1));
    publish(published);
    service = unifiedService(ArchiveQueryLimits.builder().deadlineMs(5_000L).build());
    ArchiveStatePoint point = ArchiveStatePoint.blockEnd(
        5L, published.getRange().getBlockHash(), published.getRange().getFinalizeTxNum());
    ReentrantReadWriteLock consistencyLock =
        ReflectUtils.getFieldValue(service, "consistencyLock");
    CountDownLatch writeHeld = new CountDownLatch(1);
    CountDownLatch releaseWrite = new CountDownLatch(1);
    CountDownLatch readerFinished = new CountDownLatch(1);
    AtomicReference<Throwable> blockerFailure = new AtomicReference<>();
    AtomicReference<Throwable> readerFailure = new AtomicReference<>();
    Thread blocker = new Thread(() -> {
      consistencyLock.writeLock().lock();
      try {
        writeHeld.countDown();
        if (!releaseWrite.await(5L, TimeUnit.SECONDS)) {
          blockerFailure.set(new AssertionError("timed out holding consistency write lock"));
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        blockerFailure.set(e);
      } finally {
        consistencyLock.writeLock().unlock();
      }
    }, "blocked-mid-chain-consistency-write");
    Thread reader = new Thread(() -> {
      try (ArchiveStateReader ignored = service.openReader(point)) {
        readerFailure.set(new AssertionError("mid-chain reader unexpectedly opened"));
      } catch (Throwable failure) {
        readerFailure.set(failure);
      } finally {
        readerFinished.countDown();
      }
    }, "concurrent-mid-chain-reader");

    try {
      blocker.start();
      assertTrue(writeHeld.await(5L, TimeUnit.SECONDS));
      reader.start();
      assertTrue("coverage rejection must not wait for the consistency lock",
          readerFinished.await(1L, TimeUnit.SECONDS));
    } finally {
      releaseWrite.countDown();
      blocker.join(5_000L);
      reader.join(5_000L);
    }

    assertFalse(blocker.isAlive());
    assertFalse(reader.isAlive());
    assertNull(blockerFailure.get());
    assertTrue(readerFailure.get() instanceof ArchiveReaderException);
    ArchiveReaderException failure = (ArchiveReaderException) readerFailure.get();
    assertEquals(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE, failure.getReason());
    assertTrue(failure.getMessage().contains("from-genesis"));
  }

  @Test
  public void corruptedCoverageFloorCannotEnableCompleteHistorySemantics() {
    ArchiveInFlightBlock published = blockAtTxNum(
        5L, 0L, hash(6L), DomainValue.tombstone(), value(1));
    publish(published);
    service = unifiedService();
    byte[] floorName = "first-block".getBytes(StandardCharsets.US_ASCII);
    byte[] floorKey = new byte[1 + floorName.length];
    floorKey[0] = 0x01;
    System.arraycopy(floorName, 0, floorKey, 1, floorName.length);
    write(db, new UnifiedArchiveMaintenanceBatch().put(
        UnifiedArchiveColumnFamily.INDEX, floorKey, new byte[Long.BYTES]));
    ArchiveStatePoint point = ArchiveStatePoint.blockEnd(
        5L, published.getRange().getBlockHash(), published.getRange().getFinalizeTxNum());

    ArchiveReaderException failure = assertThrows(
        ArchiveReaderException.class, () -> service.openReader(point));

    assertEquals(ArchiveReaderException.Reason.CORRUPT_INDEX, failure.getReason());
    assertTrue(failure.getMessage().contains("complete-coverage"));
    assertTrue(index.hasRepairRequired());
    assertThrows(ArchiveException.class, service::validateAvailable);
  }

  @Test
  public void readerOpeningAccountsUnifiedIndexValueBytes() {
    ArchiveInFlightBlock published = block(0L, DomainValue.tombstone(), value(1));
    publish(published);
    service = unifiedService(ArchiveQueryLimits.builder()
        .maxBackendReadBytesPerRequest(0L)
        .build());

    HistoricalQueryLimitException failure = assertThrows(
        HistoricalQueryLimitException.class,
        () -> service.openBlockEndReader(0L, published.getRange().getBlockHash()));

    assertEquals(HistoricalQueryLimitException.Limit.BACKEND_READ_BYTES,
        failure.getLimit());
  }

  @Test
  public void queryDetectedUnifiedIntegrityFailurePersistsRepairRequired() throws Exception {
    ArchiveInFlightBlock published = block(0L, DomainValue.tombstone(), value(1));
    publish(published);
    service = unifiedService();
    byte[] latestKey = firstKey(db, UnifiedArchiveColumnFamily.LATEST);
    write(db, new UnifiedArchiveMaintenanceBatch().put(
        UnifiedArchiveColumnFamily.LATEST, latestKey,
        firstValue(db, UnifiedArchiveColumnFamily.HISTORY)));
    ArchiveStatePoint point = ArchiveStatePoint.blockEnd(
        0L, published.getRange().getBlockHash(), published.getRange().getFinalizeTxNum());

    ArchiveStateReader reader = service.openReader(point);
    try {
      ArchiveReaderException failure = assertThrows(
          ArchiveReaderException.class, () -> reader.getAccount(accountKey()));
      assertEquals(ArchiveReaderException.Reason.CORRUPT_VALUE, failure.getReason());
      assertTrue(failure.getMessage().contains("temporal read failed"));
      assertThrows(ArchiveException.class, reader::close);
    } finally {
      reader.close();
    }

    assertTrue(index.hasRepairRequired());
    assertThrows(ArchiveException.class, service::validateAvailable);
  }

  @Test
  public void onlineRocksReadFailureAfterReaderOpenIsRequestLocal() throws Exception {
    ArchiveInFlightBlock published = block(0L, DomainValue.tombstone(), value(1));
    publish(published);
    service = unifiedService();
    RocksDB raw = ReflectUtils.getFieldValue(db, "db");
    RocksDB failing = spy(raw);
    AtomicBoolean failReads = new AtomicBoolean();
    doAnswer(invocation -> {
      if (failReads.get()) {
        throw new RocksDBException("injected online archive read failure");
      }
      ColumnFamilyHandle handle = invocation.getArgument(0);
      ReadOptions options = invocation.getArgument(1);
      byte[] key = invocation.getArgument(2);
      byte[] value = invocation.getArgument(3);
      return raw.get(handle, options, key, value);
    }).when(failing).get(any(ColumnFamilyHandle.class), any(ReadOptions.class),
        any(byte[].class), any(byte[].class));
    doAnswer(invocation -> {
      if (failReads.get()) {
        throw new RocksDBException("injected online archive read failure");
      }
      ColumnFamilyHandle handle = invocation.getArgument(0);
      ReadOptions options = invocation.getArgument(1);
      byte[] key = invocation.getArgument(2);
      return raw.get(handle, options, key);
    }).when(failing).get(any(ColumnFamilyHandle.class), any(ReadOptions.class),
        any(byte[].class));

    ReflectUtils.setFieldValue(db, "db", failing);
    ArchiveStateReader reader = null;
    ArchiveReaderException failure;
    try {
      reader = service.openBlockEndReader(0L, published.getRange().getBlockHash());
      failReads.set(true);
      ArchiveStateReader openedReader = reader;
      failure = assertThrows(
          ArchiveReaderException.class, () -> openedReader.getAccount(accountKey()));
      failReads.set(false);
      reader.close();
      reader = null;
    } finally {
      failReads.set(false);
      if (reader != null) {
        reader.close();
      }
      ReflectUtils.setFieldValue(db, "db", raw);
    }

    assertEquals(ArchiveReaderException.Reason.INTERNAL_IO, failure.getReason());
    assertFalse(index.hasRepairRequired());
    service.validateAvailable();
  }

  @Test
  public void blockHashIndexGapFailsStopThroughManagedReader() throws Exception {
    publish(block(0L, DomainValue.tombstone(), value(1)));
    publish(block(1L, value(1), value(2)));
    ArchiveInFlightBlock head = block(2L, value(2), value(3));
    publish(head);
    service = unifiedService();
    byte[] middleRangeKey = ByteBuffer.allocate(1 + Long.BYTES)
        .put((byte) 0x10)
        .putLong(1L)
        .array();
    write(db, new UnifiedArchiveMaintenanceBatch().delete(
        UnifiedArchiveColumnFamily.INDEX, middleRangeKey));

    ArchiveStateReader reader = service.openBlockEndReader(
        2L, head.getRange().getBlockHash());
    try {
      ArchiveReaderException failure = assertThrows(
          ArchiveReaderException.class, () -> reader.getBlockHash(1L));
      assertEquals(ArchiveReaderException.Reason.CORRUPT_INDEX, failure.getReason());
      assertThrows(ArchiveException.class, reader::close);
    } finally {
      reader.close();
    }

    assertTrue(index.hasRepairRequired());
    assertThrows(ArchiveException.class, service::validateAvailable);
  }

  @Test
  public void selectedBlockRangeTamperFailsAgainstSnapshotMarkerBeforeReaderOpens() {
    publish(block(0L, DomainValue.tombstone(), value(1)));
    publish(block(1L, value(1), value(2)));
    ArchiveInFlightBlock target = block(2L, value(2), value(3));
    publish(target);
    service = unifiedService();

    byte[] previousRange = db.get(UnifiedArchiveColumnFamily.INDEX, rangeKey(1L));
    byte[] targetRange = db.get(UnifiedArchiveColumnFamily.INDEX, rangeKey(2L));
    // Preserve target height/hash/schema while redirecting its valid coordinates to block 1.
    System.arraycopy(previousRange, 9, targetRange, 9, 4 * Long.BYTES);
    write(db, new UnifiedArchiveMaintenanceBatch().put(
        UnifiedArchiveColumnFamily.INDEX, rangeKey(2L), targetRange));

    ArchiveReaderException failure = assertThrows(ArchiveReaderException.class,
        () -> service.openBlockEndReader(2L, target.getRange().getBlockHash()));

    assertEquals(ArchiveReaderException.Reason.CORRUPT_INDEX, failure.getReason());
    assertTrue(failure.getMessage().contains("commit marker"));
    assertTrue(index.hasRepairRequired());
    assertThrows(ArchiveException.class, service::validateAvailable);
  }

  @Test
  public void targetMarkerRocksIoFailureIsRequestLocal() throws Exception {
    ArchiveInFlightBlock published = block(0L, DomainValue.tombstone(), value(1));
    publish(published);
    service = unifiedService();
    RocksDB raw = ReflectUtils.getFieldValue(db, "db");
    RocksDB failing = spy(raw);
    EnumMap<UnifiedArchiveColumnFamily, ColumnFamilyHandle> handles =
        ReflectUtils.getFieldValue(db, "handles");
    ColumnFamilyHandle markerHandle = handles.get(UnifiedArchiveColumnFamily.BLOCK_MARKER);
    doAnswer(invocation -> {
      ColumnFamilyHandle handle = invocation.getArgument(0);
      if (handle == markerHandle) {
        throw new RocksDBException("injected BLOCK_MARKER read failure");
      }
      ReadOptions options = invocation.getArgument(1);
      byte[] key = invocation.getArgument(2);
      byte[] value = invocation.getArgument(3);
      return raw.get(handle, options, key, value);
    }).when(failing).get(any(ColumnFamilyHandle.class), any(ReadOptions.class),
        any(byte[].class), any(byte[].class));

    ArchiveReaderException failure;
    ReflectUtils.setFieldValue(db, "db", failing);
    try {
      failure = assertThrows(ArchiveReaderException.class,
          () -> service.openBlockEndReader(0L, published.getRange().getBlockHash()));
    } finally {
      ReflectUtils.setFieldValue(db, "db", raw);
    }

    assertEquals(ArchiveReaderException.Reason.INTERNAL_IO, failure.getReason());
    assertFalse(index.hasRepairRequired());
    service.validateAvailable();
  }

  @Test
  public void missingNativeSnapshotIsRequestLocalInternalIo() {
    ArchiveInFlightBlock published = block(0L, DomainValue.tombstone(), value(1));
    publish(published);
    service = unifiedService();
    RocksDB raw = ReflectUtils.getFieldValue(db, "db");
    RocksDB failing = spy(raw);
    doReturn(null).when(failing).getSnapshot();

    ArchiveReaderException failure;
    ReflectUtils.setFieldValue(db, "db", failing);
    try {
      failure = assertThrows(ArchiveReaderException.class,
          () -> service.openBlockEndReader(0L, published.getRange().getBlockHash()));
    } finally {
      ReflectUtils.setFieldValue(db, "db", raw);
    }

    assertEquals(ArchiveReaderException.Reason.INTERNAL_IO, failure.getReason());
    assertFalse(index.hasRepairRequired());
    service.validateAvailable();
  }

  @Test
  public void blockHashRangeTamperFailsAgainstSnapshotMarker() throws Exception {
    ArchiveInFlightBlock genesis = block(0L, DomainValue.tombstone(), value(1));
    publish(genesis);
    publish(block(1L, value(1), value(2)));
    ArchiveInFlightBlock head = block(2L, value(2), value(3));
    publish(head);
    byte[] genesisRangeKey = ByteBuffer.allocate(1 + Long.BYTES)
        .put((byte) 0x10)
        .putLong(0L)
        .array();
    byte[] encodedRange;
    try (UnifiedArchiveReadView view = db.openReadView()) {
      encodedRange = view.getBounded(UnifiedArchiveColumnFamily.INDEX, genesisRangeKey,
          4_096L, "test genesis range");
    }
    assertTrue(encodedRange != null);
    // RANGE v1 stores its 32-byte block hash after the fixed 50-byte header.
    encodedRange[50] ^= 0x01;
    write(db, new UnifiedArchiveMaintenanceBatch().put(
        UnifiedArchiveColumnFamily.INDEX, genesisRangeKey, encodedRange));
    service = unifiedService();

    ArchiveStateReader reader = service.openBlockEndReader(
        2L, head.getRange().getBlockHash());
    try {
      ArchiveReaderException failure = assertThrows(
          ArchiveReaderException.class, () -> reader.getBlockHash(0L));
      assertEquals(ArchiveReaderException.Reason.CORRUPT_INDEX, failure.getReason());
      assertThrows(ArchiveException.class, reader::close);
    } finally {
      reader.close();
    }

    assertTrue(index.hasRepairRequired());
    assertThrows(ArchiveException.class, service::validateAvailable);
  }

  @Test
  public void readerOpeningIndexCorruptionPersistsRepairRequired() {
    ArchiveInFlightBlock published = block(0L, DomainValue.tombstone(), value(1));
    publish(published);
    service = unifiedService();
    byte[] rangeKey = new byte[1 + Long.BYTES];
    rangeKey[0] = 0x10;
    write(db, new UnifiedArchiveMaintenanceBatch().put(
        UnifiedArchiveColumnFamily.INDEX, rangeKey, new byte[] {1}));

    ArchiveReaderException failure = assertThrows(
        ArchiveReaderException.class,
        () -> service.openBlockEndReader(0L, published.getRange().getBlockHash()));

    assertEquals(ArchiveReaderException.Reason.CORRUPT_INDEX, failure.getReason());
    assertTrue(index.hasRepairRequired());
    assertThrows(ArchiveException.class, service::validateAvailable);
  }

  @Test
  public void readerOpenCorruptionWinsOverSnapshotCleanupFailure() throws Exception {
    ArchiveInFlightBlock published = block(0L, DomainValue.tombstone(), value(1));
    publish(published);
    service = unifiedService();
    byte[] rangeKey = new byte[1 + Long.BYTES];
    rangeKey[0] = 0x10;
    write(db, new UnifiedArchiveMaintenanceBatch().put(
        UnifiedArchiveColumnFamily.INDEX, rangeKey, new byte[] {1}));
    RocksDB raw = ReflectUtils.getFieldValue(db, "db");
    RocksDB failing = spy(raw);
    AssertionError cleanupFailure = new AssertionError("injected snapshot cleanup failure");
    AtomicReference<Snapshot> leakedSnapshot = new AtomicReference<>();
    doAnswer(invocation -> {
      leakedSnapshot.set(invocation.getArgument(0));
      throw cleanupFailure;
    }).when(failing).releaseSnapshot(any(Snapshot.class));

    ArchiveReaderException failure;
    ArchiveQueryCoordinator coordinator = ReflectUtils.getFieldValue(service, "queryCoordinator");
    long[] retainedCounts = new long[2];
    ReflectUtils.setFieldValue(db, "db", failing);
    try {
      failure = assertThrows(ArchiveReaderException.class,
          () -> service.openBlockEndReader(0L, published.getRange().getBlockHash()));
    } finally {
      retainedCounts[0] = coordinator.getActiveSnapshotCount();
      retainedCounts[1] = coordinator.getActiveLeaseCount();
      ReflectUtils.setFieldValue(db, "db", raw);
      if (leakedSnapshot.get() != null) {
        raw.releaseSnapshot(leakedSnapshot.get());
        ReflectUtils.invokeMethod(db, "releaseReadView", new Class<?>[0]);
      }
      ReflectUtils.setFieldValue(coordinator, "activeSnapshots", 0L);
      ReflectUtils.setFieldValue(coordinator, "activeLeases", 0L);
      ArchiveMetrics.setActiveSnapshots(0L);
    }

    assertEquals(ArchiveReaderException.Reason.CORRUPT_INDEX, failure.getReason());
    assertTrue(ArchiveSnapshotReleaseException.contains(failure));
    assertEquals(1L, retainedCounts[0]);
    assertEquals(1L, retainedCounts[1]);
    assertTrue(index.hasRepairRequired());
    assertThrows(ArchiveException.class, service::validateAvailable);
  }

  @Test
  public void fullScrubRejectsUnknownIndexAndIntegrityRows() {
    write(db, new UnifiedArchiveMaintenanceBatch()
        .put(UnifiedArchiveColumnFamily.INDEX, new byte[] {(byte) 0x7f}, new byte[] {1}));
    assertThrows(ArchiveException.class, () -> backend.validateStartup(true, false));

    index.close();
    index = null;
    db = UnifiedArchiveDb.open(dbPath, schemaChecksum);
    write(db, new UnifiedArchiveMaintenanceBatch()
        .delete(UnifiedArchiveColumnFamily.INDEX, new byte[] {(byte) 0x7f})
        .put(UnifiedArchiveColumnFamily.COMMITMENT, new byte[] {1}, new byte[] {1}));
    wire(false, true);
    assertThrows(ArchiveException.class, () -> backend.validateStartup(true, true));
  }

  @Test
  public void fullScrubRejectsPublishedRowCorruptionMatrix() throws Exception {
    assertPublishedCorruptionRejected("missing-index-row",
        (caseDb, rowKey) -> deleteFirstRow(caseDb, UnifiedArchiveColumnFamily.INDEX));
    assertPublishedCorruptionRejected("missing-latest-row",
        (caseDb, rowKey) -> deleteFirstRow(caseDb, UnifiedArchiveColumnFamily.LATEST));
    assertPublishedCorruptionRejected("missing-history-row",
        (caseDb, rowKey) -> deleteFirstRow(caseDb, UnifiedArchiveColumnFamily.HISTORY));
    assertPublishedCorruptionRejected("missing-changeset-row",
        (caseDb, rowKey) -> deleteFirstRow(caseDb, UnifiedArchiveColumnFamily.CHANGESET));
    assertPublishedCorruptionRejected("missing-block-marker",
        (caseDb, rowKey) -> deleteFirstRow(caseDb, UnifiedArchiveColumnFamily.BLOCK_MARKER));
    assertPublishedCorruptionRejected("missing-anchor-row",
        (caseDb, rowKey) -> deleteFirstRow(caseDb, UnifiedArchiveColumnFamily.COMMITMENT));
    assertPublishedCorruptionRejected("oversized-index-value",
        (caseDb, rowKey) -> replaceFirstValue(
            caseDb, UnifiedArchiveColumnFamily.INDEX, new byte[4097]));
    assertPublishedCorruptionRejected("oversized-history-locator",
        (caseDb, rowKey) -> replaceFirstValue(
            caseDb, UnifiedArchiveColumnFamily.HISTORY, new byte[128 * 1024]));
    assertPublishedCorruptionRejected("malformed-block-marker-value",
        (caseDb, rowKey) -> replaceFirstValue(
            caseDb, UnifiedArchiveColumnFamily.BLOCK_MARKER, new byte[] {1}));
    assertPublishedCorruptionRejected("oversized-block-marker-value",
        (caseDb, rowKey) -> replaceFirstValue(
            caseDb, UnifiedArchiveColumnFamily.BLOCK_MARKER, new byte[128 * 1024]));
    assertPublishedCorruptionRejected("missing-changeset-payload",
        (caseDb, rowKey) -> deleteTemporalPayloadFor(
            caseDb, UnifiedArchiveColumnFamily.CHANGESET));
    assertPublishedCorruptionRejected("missing-anchor-payload",
        (caseDb, rowKey) -> deleteTemporalPayloadFor(
            caseDb, UnifiedArchiveColumnFamily.COMMITMENT));
    assertPublishedCorruptionRejected("tampered-history-reference",
        (caseDb, rowKey) -> tamperFirstValue(
            caseDb, UnifiedArchiveColumnFamily.HISTORY));
    assertPublishedCorruptionRejected("tampered-latest-reference",
        (caseDb, rowKey) -> tamperFirstValue(
            caseDb, UnifiedArchiveColumnFamily.LATEST));
    assertPublishedCorruptionRejected("tampered-changeset-payload",
        (caseDb, rowKey) -> tamperTemporalPayloadFor(
            caseDb, UnifiedArchiveColumnFamily.CHANGESET));
    assertPublishedCorruptionRejected("tampered-anchor-payload",
        (caseDb, rowKey) -> tamperTemporalPayloadFor(
            caseDb, UnifiedArchiveColumnFamily.COMMITMENT));
    assertPublishedCorruptionRejected("malformed-latest-value",
        (caseDb, rowKey) -> write(caseDb,
            new UnifiedArchiveMaintenanceBatch().put(
                UnifiedArchiveColumnFamily.LATEST, rowKey, new byte[] {(byte) 0x7f})));
    assertPublishedCorruptionRejected("malformed-history-value",
        (caseDb, rowKey) -> replaceFirstValue(
            caseDb, UnifiedArchiveColumnFamily.HISTORY, new byte[] {(byte) 0x7f}));
    assertPublishedCorruptionRejected("malformed-changeset-value",
        (caseDb, rowKey) -> replaceFirstValue(
            caseDb, UnifiedArchiveColumnFamily.CHANGESET, new byte[] {(byte) 0x7f}));
    assertPublishedCorruptionRejected("malformed-anchor-value",
        (caseDb, rowKey) -> replaceFirstValue(
            caseDb, UnifiedArchiveColumnFamily.COMMITMENT, new byte[] {(byte) 0x7f}));
    assertPublishedCorruptionRejected("latest-value-mismatch",
        (caseDb, rowKey) -> write(caseDb,
            new UnifiedArchiveMaintenanceBatch().put(
                UnifiedArchiveColumnFamily.LATEST, rowKey,
                firstValue(caseDb, UnifiedArchiveColumnFamily.HISTORY))));
    assertPublishedCorruptionRejected("unknown-latest-key",
        (caseDb, rowKey) -> putUnknownRow(caseDb, UnifiedArchiveColumnFamily.LATEST));
    assertPublishedCorruptionRejected("unknown-history-key",
        (caseDb, rowKey) -> putUnknownRow(caseDb, UnifiedArchiveColumnFamily.HISTORY));
    assertPublishedCorruptionRejected("unknown-changeset-key",
        (caseDb, rowKey) -> putUnknownRow(caseDb, UnifiedArchiveColumnFamily.CHANGESET));
    assertPublishedCorruptionRejected("unknown-block-marker-key",
        (caseDb, rowKey) -> putUnknownRow(caseDb, UnifiedArchiveColumnFamily.BLOCK_MARKER));
  }

  @Test
  public void journalScanRejectsUnknownRows() {
    db.putJournalDurably(new byte[] {(byte) 0x7f}, new byte[] {1});
    assertThrows(ArchiveJournalCorruptionException.class, inFlight::loadBlocks);
  }

  @Test
  public void journalScanRejectsPayloadLargerThanConfiguredLimitBeforeDecode() {
    ArchiveInFlightBlock block = block(0L, DomainValue.tombstone(), value(1));
    inFlight.putBlock(block);
    int encodedLength = ArchiveInFlightCodec.encodeBlock(block).length;
    UnifiedArchiveInFlightStore bounded =
        new UnifiedArchiveInFlightStore(db, catalog, encodedLength - 1L);

    ArchiveJournalLimitException failure = assertThrows(
        ArchiveJournalLimitException.class, bounded::loadBlocks);

    assertTrue(failure.getMessage().contains("encoded bytes"));
    assertTrue(failure.getMessage().contains("configured limit"));
  }

  @Test
  public void journalScanTreatsProofBoundPayloadLengthMismatchAsCorruption() {
    ArchiveInFlightBlock block = block(0L, DomainValue.tombstone(), value(1));
    inFlight.putBlock(block);
    byte[] journalKey = ArchiveInFlightCodec.blockKey(0L);
    byte[] original = db.get(UnifiedArchiveColumnFamily.INFLIGHT, journalKey);
    byte[] enlarged = Arrays.copyOf(original, original.length + 1);
    replaceInflightValueUnchecked(journalKey, enlarged);

    ArchiveJournalCorruptionException failure = assertThrows(
        ArchiveJournalCorruptionException.class, inFlight::loadBlocks);

    assertTrue(failure.getMessage().contains("payload"));
    assertTrue(failure.getMessage().contains("length mismatch"));
  }

  @Test
  public void journalScanRejectsRetainedHeapEstimateBeforeObjectDecode() {
    ArchiveInFlightBlock block = block(0L, DomainValue.tombstone(), value(1));
    inFlight.putBlock(block);
    long encodedLength = ArchiveInFlightCodec.encodedBlockSize(block);
    ArchiveChangeRecord record = block.getRecords().get(0);
    long decodeTransientBytes = record.canonicalKeySize()
        + StrictMathWrapper.max(record.getPrevValue().size(), record.getValue().size());
    long nativeReadPeak = encodedLength * 2L;
    long decodePeak = encodedLength + block.estimatedRetainedBytes() + decodeTransientBytes;
    assertTrue(decodePeak > nativeReadPeak);
    UnifiedArchiveInFlightStore bounded =
        new UnifiedArchiveInFlightStore(db, catalog, decodePeak - 1L);

    ArchiveException failure = assertThrows(ArchiveException.class, bounded::loadBlocks);

    assertTrue(failure.getMessage().contains("retained bytes"));
  }

  @Test
  public void journalScanAcceptsExactPayloadPlusDecodedRetainedPeak() {
    ArchiveInFlightBlock block = block(0L, DomainValue.tombstone(), value(1));
    inFlight.putBlock(block);
    long encodedLength = ArchiveInFlightCodec.encodedBlockSize(block);
    ArchiveChangeRecord record = block.getRecords().get(0);
    long decodeTransientBytes = record.canonicalKeySize()
        + StrictMathWrapper.max(record.getPrevValue().size(), record.getValue().size());
    long nativeReadPeak = encodedLength * 2L;
    long decodePeak = encodedLength + block.estimatedRetainedBytes() + decodeTransientBytes;
    long peakBytes = StrictMathWrapper.max(nativeReadPeak, decodePeak);
    UnifiedArchiveInFlightStore bounded =
        new UnifiedArchiveInFlightStore(db, catalog, peakBytes);

    List<ArchiveInFlightBlock> loaded = bounded.loadBlocks();

    assertEquals(1, loaded.size());
    assertEquals(block.getJournalToken(), loaded.get(0).getJournalToken());
  }

  @Test
  public void journalScanRejectsRecordCountBeforeConsumerCallbacks() {
    ArchiveInFlightBlock block = blockWithAdditionalRecord(
        block(0L, DomainValue.tombstone(), value(1)));
    inFlight.putBlock(block);
    UnifiedArchiveInFlightStore bounded =
        new UnifiedArchiveInFlightStore(db, catalog, Long.MAX_VALUE, 1L);
    AtomicInteger consumed = new AtomicInteger();

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> bounded.forEachBlock(ignored -> consumed.incrementAndGet()));

    assertTrue(failure.getMessage().contains("record count"));
    assertEquals(0, consumed.get());
  }

  @Test
  public void journalScanRejectsOversizedLifecycleValueBeforeDecode() {
    ArchiveInFlightBlock block = block(0L, DomainValue.tombstone(), value(1));
    inFlight.putBlock(block);
    byte[] journalKey = ArchiveInFlightCodec.blockKey(0L);
    byte[] journal = db.get(UnifiedArchiveColumnFamily.INFLIGHT, journalKey);
    inFlight.deleteBlock(0L);
    db.putJournalDurably(journalKey, journal);
    db.putJournalDurably(ArchiveInFlightCodec.tokenKey(0L),
        new byte[ArchiveInFlightCodec.LIFECYCLE_VALUE_BYTES + 1]);

    ArchiveJournalCorruptionException failure = assertThrows(
        ArchiveJournalCorruptionException.class, inFlight::loadBlocks);

    assertTrue(failure.getMessage().contains("journal proof"));
    assertTrue(failure.getMessage().contains("byte limit"));
  }

  @Test
  public void journalScanRejectsAggregateRetainedBytesBeforeConsumerCallbacks() {
    ArchiveInFlightBlock first = block(0L, DomainValue.tombstone(), value(1));
    ArchiveInFlightBlock second = block(1L, value(1), value(2));
    inFlight.putBlock(first);
    inFlight.putBlock(second);
    long retainedLimit = first.estimatedRetainedBytes()
        + second.estimatedRetainedBytes() - 1L;
    UnifiedArchiveInFlightStore bounded =
        new UnifiedArchiveInFlightStore(db, catalog, retainedLimit);
    AtomicInteger consumed = new AtomicInteger();

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> bounded.forEachBlock(ignored -> consumed.incrementAndGet()));

    assertTrue(failure.getMessage().contains("retained bytes"));
    assertEquals(0, consumed.get());
  }

  @Test
  public void journalScanRejectsAggregateRecordCountBeforeConsumerCallbacks() {
    inFlight.putBlock(block(0L, DomainValue.tombstone(), value(1)));
    inFlight.putBlock(block(1L, value(1), value(2)));
    UnifiedArchiveInFlightStore bounded =
        new UnifiedArchiveInFlightStore(db, catalog, Long.MAX_VALUE, 1L);
    AtomicInteger consumed = new AtomicInteger();

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> bounded.forEachBlock(ignored -> consumed.incrementAndGet()));

    assertTrue(failure.getMessage().contains("record count"));
    assertEquals(0, consumed.get());
  }

  @Test
  public void journalScanRejectsAggregateBlockCountBeforeConsumerCallbacks() {
    inFlight.putBlock(block(0L, DomainValue.tombstone(), value(1)));
    inFlight.putBlock(block(1L, value(1), value(2)));
    UnifiedArchiveInFlightStore bounded = new UnifiedArchiveInFlightStore(
        db, catalog, Long.MAX_VALUE, Long.MAX_VALUE, 1);
    AtomicInteger consumed = new AtomicInteger();

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> bounded.forEachBlock(ignored -> consumed.incrementAndGet()));

    assertTrue(failure.getMessage().contains("block count"));
    assertEquals(0, consumed.get());
  }

  @Test
  public void journalScanRejectsOrphanLifecycleRows() {
    ArchiveInFlightBlock block = block(0L, DomainValue.tombstone(), value(1));
    db.putJournalDurably(ArchiveInFlightCodec.acknowledgementKey(0L),
        journalProofValue(block));

    assertThrows(ArchiveJournalCorruptionException.class, inFlight::loadBlocks);
  }

  @Test
  public void journalScanRejectsSelfConsistentForeignSchemaBeforeConsumerCallbacks() {
    ArchiveInFlightBlock current = block(0L, DomainValue.tombstone(), value(1));
    byte[] foreignSchema = schemaChecksum.clone();
    foreignSchema[0] ^= 1;
    ArchiveBlockRange currentRange = current.getRange();
    ArchiveBlockRange foreignRange = new ArchiveBlockRange(
        currentRange.getBlockNum(), currentRange.getFirstTxNum(),
        currentRange.getLastTxNum(), currentRange.getPrepareTxNum(),
        currentRange.getFinalizeTxNum(), currentRange.getBlockHash(),
        currentRange.getUserTxCount(), currentRange.getSource(), foreignSchema);
    ArchiveInFlightBlock foreign = new ArchiveInFlightBlock(
        foreignRange, current.getPositions(), current.getRecords());
    byte[] journalKey = ArchiveInFlightCodec.blockKey(foreignRange.getBlockNum());
    byte[] payload = ArchiveInFlightCodec.encodeBlock(foreign);
    db.putJournalBlockDurably(
        journalKey, payload,
        ArchiveInFlightCodec.tokenKey(foreignRange.getBlockNum()),
        ArchiveInFlightCodec.encodeProof(
            ArchiveJournalProof.create(foreign.getJournalToken(), journalKey, payload)),
        ArchiveInFlightCodec.acknowledgementKey(foreignRange.getBlockNum()), null);
    AtomicInteger consumed = new AtomicInteger();
    UnifiedArchiveInFlightStore oneByteBudget =
        new UnifiedArchiveInFlightStore(db, catalog, 1L);

    ArchiveJournalCorruptionException failure = assertThrows(
        ArchiveJournalCorruptionException.class,
        () -> oneByteBudget.forEachBlock(ignored -> consumed.incrementAndGet()));

    assertTrue(failure.getMessage().contains("schema checksum mismatch"));
    assertEquals(0, consumed.get());
  }

  @Test
  public void journalScanDoesNotExposeValidatedPrefixBeforeRejectingOrphanRows() {
    inFlight.putBlock(block(0L, DomainValue.tombstone(), value(1)));
    ArchiveInFlightBlock orphan = block(1L, value(1), value(2));
    db.putJournalDurably(ArchiveInFlightCodec.acknowledgementKey(1L),
        journalProofValue(orphan));
    AtomicInteger consumed = new AtomicInteger();

    assertThrows(ArchiveJournalCorruptionException.class,
        () -> inFlight.forEachBlock(ignored -> consumed.incrementAndGet()));

    assertEquals(0, consumed.get());
  }

  @Test
  public void journalCorruptionMatrixIsFullyValidatedBeforeConsumerCallbacks() throws Exception {
    assertJournalCorruptionRejectedBeforeConsumer("unknown-row",
        (caseDb, caseStore, corrupt) ->
            caseDb.putJournalDurably(new byte[] {(byte) 0x7f}, new byte[] {1}));
    assertJournalCorruptionRejectedBeforeConsumer("missing-token",
        (caseDb, caseStore, corrupt) -> caseDb.putJournalDurably(
            ArchiveInFlightCodec.blockKey(1L), ArchiveInFlightCodec.encodeBlock(corrupt)));
    assertJournalCorruptionRejectedBeforeConsumer("orphan-token",
        (caseDb, caseStore, corrupt) -> caseDb.putJournalDurably(
            ArchiveInFlightCodec.tokenKey(1L),
            journalProofValue(corrupt)));
    assertJournalCorruptionRejectedBeforeConsumer("mismatched-token",
        (caseDb, caseStore, corrupt) -> {
          caseDb.putJournalDurably(
              ArchiveInFlightCodec.blockKey(1L), ArchiveInFlightCodec.encodeBlock(corrupt));
          caseDb.putJournalDurably(
              ArchiveInFlightCodec.tokenKey(1L),
              journalProofValue(corrupt, differentGeneration(corrupt.getJournalToken())));
        });
    assertJournalCorruptionRejectedBeforeConsumer("mismatched-acknowledgement",
        (caseDb, caseStore, corrupt) -> {
          caseStore.putBlock(corrupt);
          caseDb.putJournalDurably(
              ArchiveInFlightCodec.acknowledgementKey(1L),
              journalProofValue(corrupt, differentGeneration(corrupt.getJournalToken())));
        });
    assertJournalCorruptionRejectedBeforeConsumer("mutable-payload-state",
        (caseDb, caseStore, corrupt) -> {
          caseDb.putJournalDurably(
              ArchiveInFlightCodec.blockKey(1L),
              ArchiveInFlightCodec.encodeBlock(corrupt.withJournalState(
                  ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED)));
          caseDb.putJournalDurably(
              ArchiveInFlightCodec.tokenKey(1L),
              journalProofValue(corrupt.withJournalState(
                  ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED)));
        });
  }

  @Test
  public void commitMarkerMatchesForMultipleVariableLengthKeysInOneTx() {
    // Two DYNAMIC_PROPERTIES changes share one (txNum, domain): "AA" (2 bytes, lexicographically
    // smaller) and "B" (1 byte, lexicographically greater). prepare() must fold the block digest in
    // stored changeset-key order (keyLen-first -> [B, AA]), NOT RECORD_ORDER (canonicalKey
    // lex-then-length -> [AA, B]); otherwise validateCommittedBlock fail-stops a valid block.
    ArchiveBlockRange range = new ArchiveBlockRange(
        0L, 0L, 1L, 0L, 1L, hash(1L), 0, ArchiveSource.NORMAL, schemaChecksum);
    ArchiveTxPosition pos = new ArchiveTxPosition(
        0L, 0L, ArchivePhase.BLOCK_PREPARE, ArchiveSource.NORMAL, -1, null);
    byte[] longerLexSmaller = "AA".getBytes(StandardCharsets.US_ASCII);
    byte[] shorterLexGreater = "B".getBytes(StandardCharsets.US_ASCII);
    ArchiveChangeRecord first = new ArchiveChangeRecord(
        pos, ArchiveDomain.DYNAMIC_PROPERTIES, longerLexSmaller,
        DomainValue.tombstone(), DomainValue.present(new byte[] {0x0A}));
    ArchiveChangeRecord second = new ArchiveChangeRecord(
        pos, ArchiveDomain.DYNAMIC_PROPERTIES, shorterLexGreater,
        DomainValue.tombstone(), DomainValue.present(new byte[] {0x0B}));
    write(db, temporal.prepareMaintenanceBatch(range, Arrays.asList(first, second)));
    // Recompute must equal the stored marker digest; pre-fix this threw "commit marker missing".
    temporal.validateCommittedBlock(range);
  }

  @Test
  public void unifiedComponentsAndSnapshotsCannotCrossDatabaseOwners() {
    Path foreignPath = temporaryFolder.getRoot().toPath().resolve("foreign-unified");
    try (UnifiedArchiveDb foreignDb = UnifiedArchiveDb.initialize(
        foreignPath, schemaChecksum)) {
      UnifiedArchiveTemporalStore foreignTemporal =
          new UnifiedArchiveTemporalStore(foreignDb, catalog);

      assertThrows(ArchiveException.class,
          () -> new UnifiedArchiveBackend(db, index, foreignTemporal));
      try (UnifiedArchiveReadView foreignView = foreignDb.openReadView()) {
        assertThrows(ArchiveException.class, () -> index.bindReadView(foreignView));
        assertThrows(ArchiveException.class, () -> temporal.wrapReadView(foreignView));
      }
    }
  }

  @Test
  public void txNumIndexChecksumMustMatchItsDatabase() {
    byte[] foreignChecksum = Arrays.copyOf(schemaChecksum, schemaChecksum.length);
    foreignChecksum[0] ^= 0x01;

    assertThrows(ArchiveException.class,
        () -> new UnifiedArchiveTxNumIndex(db, foreignChecksum, false, false));
  }

  private void wire(boolean fullStartupValidation) {
    wire(fullStartupValidation, false);
  }

  private void wire(boolean fullStartupValidation, boolean deferRepairValidation) {
    index = new UnifiedArchiveTxNumIndex(
        db, schemaChecksum, fullStartupValidation, deferRepairValidation);
    temporal = new UnifiedArchiveTemporalStore(db, catalog);
    inFlight = new UnifiedArchiveInFlightStore(db, catalog);
    backend = new UnifiedArchiveBackend(db, index, temporal);
  }

  private long fullScrubIteratorCount(
      Statistics statistics, TickerType iteratorCreationTicker) {
    long before = statistics.getTickerCount(iteratorCreationTicker);
    backend.validateStartup(true, false);
    return statistics.getTickerCount(iteratorCreationTicker) - before;
  }

  private TickerType requireIteratorCreationTicker() {
    for (TickerType tickerType : TickerType.values()) {
      if ("NO_ITERATOR_CREATED".equals(tickerType.name())) {
        return tickerType;
      }
    }
    // RocksDB 5.15 exposes only the current open-iterator gauge, not this cumulative counter.
    Assume.assumeTrue("RocksDB does not expose an iterator creation counter", false);
    throw new AssertionError("unreachable");
  }

  private void reopenWithMetrics(boolean enabled) {
    if (index != null) {
      index.close();
      index = null;
      db = null;
    }
    CommonParameter.getInstance().setMetricsPrometheusEnable(enabled);
    db = enabled
        ? openWithStatistics(dbPath, schemaChecksum)
        : UnifiedArchiveDb.open(dbPath, schemaChecksum);
    wire(false);
  }

  private void publish(ArchiveInFlightBlock block) {
    backend.publishBlock(prepareCanonicalJournal(block));
    inFlight.onBlockPublished(block.getRange().getBlockNum());
  }

  private long persistedPreparationBytes(ArchiveInFlightBlock block) {
    try (UnifiedArchiveTemporalStore.PublicationPreflight preflight =
        temporal.preflightPublication(block.getRecords())) {
      return preflight.getPersistedPreparationBytes();
    }
  }

  @SuppressWarnings("unchecked")
  private Map<Long, ArchiveJournalProof> validatedProofs() {
    return ReflectUtils.getFieldValue(inFlight, "validatedProofs");
  }

  private ArchiveInFlightBlock prepareCanonicalJournal(ArchiveInFlightBlock block) {
    inFlight.putBlock(block);
    inFlight.acknowledgeBlock(block.getJournalToken());
    return block.withJournalState(ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED);
  }

  private ArchiveInFlightBlock block(long blockNum, DomainValue previous, DomainValue current) {
    return block(blockNum, hash(blockNum + 1L), previous, current);
  }

  private ArchiveInFlightBlock block(
      BlockCapsule canonical, DomainValue previous, DomainValue current) {
    return block(canonical.getNum(), canonical.getBlockId().getBytes(), previous, current);
  }

  private ArchiveInFlightBlock block(
      long blockNum, byte[] blockHash, DomainValue previous, DomainValue current) {
    return blockAtTxNum(blockNum, blockNum * 2L, blockHash, previous, current);
  }

  private ArchiveInFlightBlock blockAtTxNum(long blockNum, long firstTxNum,
      byte[] blockHash, DomainValue previous, DomainValue current) {
    ArchiveBlockRange range = new ArchiveBlockRange(
        blockNum, firstTxNum, firstTxNum + 1L, firstTxNum, firstTxNum + 1L,
        blockHash, 0, ArchiveSource.NORMAL, schemaChecksum);
    ArchiveTxPosition prepare = new ArchiveTxPosition(
        firstTxNum, blockNum, ArchivePhase.BLOCK_PREPARE, ArchiveSource.NORMAL, -1, null);
    ArchiveTxPosition finalize = new ArchiveTxPosition(
        firstTxNum + 1L, blockNum, ArchivePhase.BLOCK_FINALIZE,
        ArchiveSource.NORMAL, -1, null);
    ArchiveChangeRecord record = new ArchiveChangeRecord(
        prepare, ArchiveDomain.ACCOUNT, accountKey(), previous, current);
    return new ArchiveInFlightBlock(
        range, Arrays.asList(prepare, finalize), Collections.singletonList(record));
  }

  private ArchiveInFlightBlock blockWithAdditionalRecord(ArchiveInFlightBlock block) {
    ArchiveChangeRecord first = block.getRecords().get(0);
    byte[] secondKey = accountKey();
    secondKey[secondKey.length - 1] = 1;
    ArchiveChangeRecord second = new ArchiveChangeRecord(
        first.getPosition(), ArchiveDomain.ACCOUNT, secondKey,
        DomainValue.tombstone(), value(2));
    return new ArchiveInFlightBlock(
        block.getRange(), block.getPositions(), Arrays.asList(first, second));
  }

  private DefaultArchiveService unifiedService() {
    return unifiedService(ArchiveQueryLimits.unlimited());
  }

  private DefaultArchiveService unifiedService(ArchiveQueryLimits queryLimits) {
    ArchiveDomainRegistry registry = new DefaultArchiveDomainRegistry();
    ArchivePublisherConfig publisherConfig = new ArchivePublisherConfig(
        false, false, 32, 64, 1024L * 1024L, 2L * 1024L * 1024L,
        1_000L, 2_000L, 0L, 0L, 1_000L);
    return new DefaultArchiveService(true, index, new ArchiveExecutionContext(),
        temporal, inFlight, registry, catalog,
        ArchiveLifecycle.Phase.RUNNING, queryLimits, publisherConfig,
        () -> backend.validateStartup(false, true), backend);
  }

  private void assertJournalCorruptionRejectedBeforeConsumer(
      String caseName, JournalCorruptor corruptor) throws Exception {
    Path casePath = temporaryFolder.newFolder(caseName).toPath().resolve("unified");
    UnifiedArchiveDb caseDb = UnifiedArchiveDb.initialize(casePath, schemaChecksum);
    try {
      UnifiedArchiveInFlightStore caseStore =
          new UnifiedArchiveInFlightStore(caseDb, catalog);
      caseStore.putBlock(block(0L, DomainValue.tombstone(), value(1)));
      ArchiveInFlightBlock corrupt = block(1L, value(1), value(2));
      corruptor.corrupt(caseDb, caseStore, corrupt);
      AtomicInteger consumed = new AtomicInteger();

      assertThrows(ArchiveJournalCorruptionException.class,
          () -> caseStore.forEachBlock(ignored -> consumed.incrementAndGet()));

      assertEquals(caseName, 0, consumed.get());
    } finally {
      caseDb.close();
    }
  }

  private void assertPublishedCorruptionRejected(
      String caseName, PublishedCorruptor corruptor) throws Exception {
    Path casePath = temporaryFolder.newFolder(caseName).toPath().resolve("unified");
    UnifiedArchiveDb caseDb = UnifiedArchiveDb.initialize(casePath, schemaChecksum);
    UnifiedArchiveTxNumIndex caseIndex = null;
    try {
      caseIndex = new UnifiedArchiveTxNumIndex(caseDb, schemaChecksum, false, false);
      UnifiedArchiveTemporalStore caseTemporal =
          new UnifiedArchiveTemporalStore(caseDb, catalog);
      UnifiedArchiveInFlightStore caseInFlight =
          new UnifiedArchiveInFlightStore(caseDb, catalog);
      UnifiedArchiveBackend caseBackend =
          new UnifiedArchiveBackend(caseDb, caseIndex, caseTemporal);
      ArchiveInFlightBlock published =
          block(0L, DomainValue.tombstone(), value(1));
      caseInFlight.putBlock(published);
      caseInFlight.acknowledgeBlock(published.getJournalToken());
      caseBackend.publishBlock(
          published.withJournalState(ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED));

      byte[] latestKey = firstKey(caseDb, UnifiedArchiveColumnFamily.LATEST);
      corruptor.corrupt(caseDb, latestKey);

      assertThrows(caseName, ArchiveException.class,
          () -> caseBackend.validateStartup(true, false));
    } finally {
      if (caseIndex == null) {
        caseDb.close();
      } else {
        caseIndex.close();
      }
    }
  }

  private static void deleteFirstRow(UnifiedArchiveDb db,
      UnifiedArchiveColumnFamily columnFamily) {
    write(db, new UnifiedArchiveMaintenanceBatch()
        .delete(columnFamily, firstKey(db, columnFamily)));
  }

  private static void deleteTemporalPayloadFor(UnifiedArchiveDb db,
      UnifiedArchiveColumnFamily columnFamily) {
    write(db, new UnifiedArchiveMaintenanceBatch().delete(
        UnifiedArchiveColumnFamily.TEMPORAL_PAYLOAD,
        temporalPayloadKey(columnFamily, firstKey(db, columnFamily))));
  }

  private static void tamperTemporalPayloadFor(UnifiedArchiveDb db,
      UnifiedArchiveColumnFamily columnFamily) {
    byte[] payloadKey = temporalPayloadKey(columnFamily, firstKey(db, columnFamily));
    byte[] payload = db.get(UnifiedArchiveColumnFamily.TEMPORAL_PAYLOAD, payloadKey);
    assertTrue(columnFamily.getName() + " payload must exist", payload != null);
    payload[payload.length - 1] ^= 0x01;
    write(db, new UnifiedArchiveMaintenanceBatch().put(
        UnifiedArchiveColumnFamily.TEMPORAL_PAYLOAD, payloadKey, payload));
  }

  private static void tamperFirstValue(UnifiedArchiveDb db,
      UnifiedArchiveColumnFamily columnFamily) {
    byte[] key = firstKey(db, columnFamily);
    byte[] value = db.get(columnFamily, key);
    assertTrue(columnFamily.getName() + " row must exist", value != null);
    value[value.length - 1] ^= 0x01;
    write(db, new UnifiedArchiveMaintenanceBatch().put(columnFamily, key, value));
  }

  private static byte[] temporalPayloadKey(UnifiedArchiveColumnFamily columnFamily,
      byte[] logicalKey) {
    byte tableTag;
    switch (columnFamily) {
      case CHANGESET:
        tableTag = 0x01;
        break;
      case COMMITMENT:
        tableTag = 0x02;
        break;
      default:
        throw new AssertionError("not a temporal integrity family: " + columnFamily);
    }
    byte[] payloadKey = new byte[1 + logicalKey.length];
    payloadKey[0] = tableTag;
    System.arraycopy(logicalKey, 0, payloadKey, 1, logicalKey.length);
    return payloadKey;
  }

  private static void replaceFirstValue(UnifiedArchiveDb db,
      UnifiedArchiveColumnFamily columnFamily, byte[] value) {
    write(db, new UnifiedArchiveMaintenanceBatch()
        .put(columnFamily, firstKey(db, columnFamily), value));
  }

  private static void putUnknownRow(UnifiedArchiveDb db,
      UnifiedArchiveColumnFamily columnFamily) {
    write(db, new UnifiedArchiveMaintenanceBatch()
        .put(columnFamily, new byte[] {(byte) 0x7f}, new byte[] {1}));
  }

  private static byte[] firstKey(UnifiedArchiveDb db,
      UnifiedArchiveColumnFamily columnFamily) {
    try (org.tron.core.archive.unified.UnifiedArchiveReadView view = db.openReadView()) {
      UnifiedArchiveIterator iterator = view.newIterator(columnFamily);
      iterator.seekToFirst();
      assertTrue(columnFamily.getName() + " must contain a test row", iterator.isValid());
      return iterator.key().clone();
    }
  }

  private static byte[] rangeKey(long blockNum) {
    return ByteBuffer.allocate(1 + Long.BYTES)
        .put((byte) 0x10)
        .putLong(blockNum)
        .array();
  }

  private static byte[] firstValue(UnifiedArchiveDb db,
      UnifiedArchiveColumnFamily columnFamily) {
    try (org.tron.core.archive.unified.UnifiedArchiveReadView view = db.openReadView()) {
      UnifiedArchiveIterator iterator = view.newIterator(columnFamily);
      iterator.seekToFirst();
      assertTrue(columnFamily.getName() + " must contain a test row", iterator.isValid());
      return iterator.value().clone();
    }
  }

  private void replaceInflightValueUnchecked(byte[] key, byte[] value) {
    RocksDB raw = ReflectUtils.getFieldValue(db, "db");
    EnumMap<UnifiedArchiveColumnFamily, ColumnFamilyHandle> handles =
        ReflectUtils.getFieldValue(db, "handles");
    try (WriteOptions writeOptions = new WriteOptions().setDisableWAL(false).setSync(true)) {
      raw.put(handles.get(UnifiedArchiveColumnFamily.INFLIGHT), writeOptions, key, value);
    } catch (Exception e) {
      throw new AssertionError("failed to inject in-flight corruption", e);
    }
  }

  private void deleteInflightValueUnchecked(byte[] key) {
    RocksDB raw = ReflectUtils.getFieldValue(db, "db");
    EnumMap<UnifiedArchiveColumnFamily, ColumnFamilyHandle> handles =
        ReflectUtils.getFieldValue(db, "handles");
    try (WriteOptions writeOptions = new WriteOptions().setDisableWAL(false).setSync(true)) {
      raw.delete(handles.get(UnifiedArchiveColumnFamily.INFLIGHT), writeOptions, key);
    } catch (Exception e) {
      throw new AssertionError("failed to inject in-flight deletion", e);
    }
  }

  private void assertJournalBundlePresent(long blockNum) {
    assertNotNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT,
        ArchiveInFlightCodec.blockKey(blockNum)));
    assertNotNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT,
        ArchiveInFlightCodec.tokenKey(blockNum)));
    assertNotNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT,
        ArchiveInFlightCodec.acknowledgementKey(blockNum)));
  }

  private static byte[] journalProofValue(ArchiveInFlightBlock block) {
    return journalProofValue(block, block.getJournalToken());
  }

  private static byte[] journalProofValue(
      ArchiveInFlightBlock block, ArchiveJournalToken proofToken) {
    byte[] journalKey = ArchiveInFlightCodec.blockKey(block.getRange().getBlockNum());
    byte[] payload = ArchiveInFlightCodec.encodeBlock(block);
    return ArchiveInFlightCodec.encodeProof(
        ArchiveJournalProof.create(proofToken, journalKey, payload));
  }

  private static byte[] legacyV1JournalDigest(byte[] journalKey, byte[] payload) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update("tron-archive-unified/inflight-journal-proof/v1"
          .getBytes(StandardCharsets.US_ASCII));
      digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(journalKey.length).array());
      digest.update(journalKey);
      digest.update(ByteBuffer.allocate(Long.BYTES).putLong(payload.length).array());
      digest.update(payload);
      return digest.digest();
    } catch (Exception e) {
      throw new AssertionError("SHA-256 must be available", e);
    }
  }

  private static ArchiveJournalToken differentGeneration(ArchiveJournalToken token) {
    byte[] nonce = token.getGenerationNonce();
    nonce[0] ^= 1;
    return new ArchiveJournalToken(
        token.getBlockNum(), token.getBlockHash(), nonce, token.getSchemaChecksum());
  }

  private static BlockCapsule canonicalBlock(long blockNum) {
    return new BlockCapsule(
        blockNum, Sha256Hash.ZERO_HASH, 1L, ByteString.EMPTY);
  }

  private static DomainValue value(int seed) {
    return DomainValue.present(Account.newBuilder().setBalance(seed).build().toByteArray());
  }

  private static DomainValue largeValue(int seed, int bytes) {
    return DomainValue.present(Account.newBuilder()
        .setBalance(seed)
        .setAccountName(ByteString.copyFrom(new byte[bytes]))
        .build()
        .toByteArray());
  }

  private static DomainValue largeIncompressibleValue(int seed, int bytes) {
    byte[] payload = new byte[bytes];
    int state = seed;
    for (int i = 0; i < payload.length; i++) {
      state = state * 1_664_525 + 1_013_904_223;
      payload[i] = (byte) (state >>> 24);
    }
    return DomainValue.present(Account.newBuilder()
        .setBalance(seed)
        .setAccountName(ByteString.copyFrom(payload))
        .build()
        .toByteArray());
  }

  private static byte[] accountKey() {
    byte[] key = new byte[21];
    key[0] = 0x41;
    return key;
  }

  private static byte[] hash(long seed) {
    byte[] hash = new byte[ArchiveBlockRange.BLOCK_HASH_LENGTH];
    hash[hash.length - 1] = (byte) seed;
    return hash;
  }

  private static void assertValue(Optional<DomainValue> actual, int expected) {
    assertTrue(actual.isPresent());
    assertFalse(actual.get().isDeleted());
    assertArrayEquals(Account.newBuilder().setBalance(expected).build().toByteArray(),
        actual.get().getValue());
  }

  private static final class SuppressionDisabledException extends RuntimeException {

    private SuppressionDisabledException(String message) {
      super(message, null, false, true);
    }
  }

  @FunctionalInterface
  private interface JournalCorruptor {

    void corrupt(UnifiedArchiveDb db, UnifiedArchiveInFlightStore store,
        ArchiveInFlightBlock block);
  }

  @FunctionalInterface
  private interface PublishedCorruptor {

    void corrupt(UnifiedArchiveDb db, byte[] latestKey);
  }
}
