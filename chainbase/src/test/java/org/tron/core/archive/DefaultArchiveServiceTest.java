package org.tron.core.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import com.google.protobuf.ByteString;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.After;
import org.junit.Test;
import org.tron.common.utils.ReflectUtils;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.archive.capture.ArchiveCaptureHolder;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.domain.DefaultArchiveDomainCatalog;
import org.tron.core.archive.domain.DefaultArchiveDomainRegistry;
import org.tron.core.archive.domain.ArchiveSchemaChecksum;
import org.tron.core.archive.reader.ArchiveReadResult;
import org.tron.core.archive.reader.ArchiveReaderException;
import org.tron.core.archive.reader.ArchiveReadThrough;
import org.tron.core.archive.reader.ArchiveStatePoint;
import org.tron.core.archive.reader.ArchiveStateReader;
import org.tron.core.archive.query.ArchiveQueryCoordinator;
import org.tron.core.archive.query.ArchiveQueryLimits;
import org.tron.core.archive.query.HistoricalQueryLimitException;
import org.tron.core.archive.temporal.ArchiveTemporalStore;
import org.tron.core.archive.temporal.ArchiveTemporalReadView;
import org.tron.core.archive.temporal.InMemoryArchiveTemporalStore;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.archive.txnum.ArchiveTxPosition;
import org.tron.core.archive.txnum.ArchiveTxNumIndex;
import org.tron.core.archive.txnum.InMemoryArchiveTxNumIndex;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.protos.Protocol.Account;
import org.tron.protos.Protocol.Transaction;

public class DefaultArchiveServiceTest {

  @After
  public void clearCaptureHolder() {
    // An enabled service installs a process-wide capture engine; clear it between tests.
    ArchiveCaptureHolder.clear();
  }

  private static BlockCapsule block(long num) {
    return new BlockCapsule(num, Sha256Hash.ZERO_HASH, 1L, ByteString.EMPTY);
  }

  private static BlockCapsule blockWithParentSeed(long num, byte seed) {
    byte[] parent = new byte[32];
    parent[31] = seed;
    return new BlockCapsule(num, Sha256Hash.wrap(parent), 1L, ByteString.EMPTY);
  }

  private static byte[] schemaChecksum() {
    return ArchiveSchemaChecksum.of(
        new DefaultArchiveDomainRegistry(), new DefaultArchiveDomainCatalog());
  }

  @Test
  public void disabledServiceIsNoOp() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    DefaultArchiveService service = new DefaultArchiveService(false, index, context);
    assertFalse(service.isEnabled());

    BlockCapsule b = block(5);
    service.beginBlock(b, ArchiveSource.NORMAL);
    service.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    // Disabled: nothing allocated, no context entered.
    assertFalse(context.current().isPresent());
    assertFalse(index.getBlockRange(5).isPresent());
    service.endTx();
    service.commitBlock(b);
    service.validateCanonicalHead(null);
    assertFalse(index.getBlockRange(5).isPresent());
  }

  @Test
  public void disabledServiceDoesNotClearLiveOwnerContext() {
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    DefaultArchiveService live =
        new DefaultArchiveService(true, new InMemoryArchiveTxNumIndex(), context);
    BlockCapsule block = block(5);
    live.beginBlock(block, ArchiveSource.NORMAL);
    live.beginSystemTx(block, ArchivePhase.BLOCK_PREPARE);

    DefaultArchiveService disabled =
        new DefaultArchiveService(false, new InMemoryArchiveTxNumIndex(), context);
    try {
      disabled.endTx();
      disabled.abortBlock(block);
      disabled.close();

      assertTrue(context.current().isPresent());
      assertTrue(ArchiveCaptureHolder.isCapturingCurrentTx());
      live.validateAvailable();
    } finally {
      live.close();
    }
  }

  @Test
  public void failedStartupDoesNotClearLiveOwnerContext() {
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    DefaultArchiveService live =
        new DefaultArchiveService(true, new InMemoryArchiveTxNumIndex(), context);
    BlockCapsule block = block(5);
    live.beginBlock(block, ArchiveSource.NORMAL);
    live.beginSystemTx(block, ArchivePhase.BLOCK_PREPARE);

    InMemoryArchiveInFlightStore inFlightStore = new InMemoryArchiveInFlightStore();
    ArchiveBlockRange badRange = new ArchiveBlockRange(
        6, 7, 8, 7, 8, blockWithParentSeed(6, (byte) 6).getBlockId().getBytes(),
        0, ArchiveSource.NORMAL, schemaChecksum());
    inFlightStore.putBlock(new ArchiveInFlightBlock(
        badRange, java.util.Collections.emptyList(), java.util.Collections.emptyList()));

    try {
      ArchiveException ex = assertThrows(ArchiveException.class,
          () -> serviceWithInFlightStore(new InMemoryArchiveTxNumIndex(), context,
              new InMemoryArchiveTemporalStore(), inFlightStore));

      assertTrue(ex.getMessage().contains("non-contiguous archive in-flight txNum"));
      assertTrue(context.current().isPresent());
      assertTrue(ArchiveCaptureHolder.isCapturingCurrentTx());
      live.validateAvailable();
    } finally {
      live.close();
    }
  }

  @Test
  public void enabledEmptyBlockLifecycleCommitsRange() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    DefaultArchiveService service = new DefaultArchiveService(true, index, context);

    BlockCapsule b = block(5); // no transactions
    service.beginBlock(b, ArchiveSource.NORMAL);

    service.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    ArchiveTxPosition prepare = context.current().orElseThrow(AssertionError::new);
    assertEquals(0, prepare.getTxNum());
    assertEquals(ArchivePhase.BLOCK_PREPARE, prepare.getPhase());
    service.endTx();
    assertFalse(context.current().isPresent());

    service.beginSystemTx(b, ArchivePhase.BLOCK_FINALIZE);
    service.endTx();

    service.commitBlock(b); // userTxCount = block.getTransactions().size() = 0
    assertFalse(index.getBlockRange(5).isPresent());
    service.publishSolidifiedBlocks(5);
    ArchiveBlockRange range = index.getBlockRange(5).orElseThrow(AssertionError::new);
    assertEquals(0, range.getFirstTxNum());
    assertEquals(1, range.getLastTxNum());
    assertEquals(0, range.getUserTxCount());
    assertEquals(ArchiveSource.NORMAL, range.getSource());
  }

  @Test
  public void readerFactoryRejectsUncoveredOrMismatchedPoints() throws Exception {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    DefaultArchiveService service = new DefaultArchiveService(true, index, context);

    BlockCapsule b = blockWithParentSeed(5, (byte) 5);
    service.beginBlock(b, ArchiveSource.NORMAL);
    service.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    service.endTx();
    service.beginUserTx(b, 0, new TransactionCapsule(Transaction.getDefaultInstance()));
    service.endTx();
    service.beginSystemTx(b, ArchivePhase.BLOCK_FINALIZE);
    service.endTx();
    service.commitBlock(b, 1);
    service.publishSolidifiedBlocks(5);
    ArchiveBlockRange range = index.getBlockRange(5).orElseThrow(AssertionError::new);

    service.getReaderFactory().open(ArchiveStatePoint.blockEnd(
        5, range.getBlockHash(), range.getFinalizeTxNum())).close();
    service.getReaderFactory().open(ArchiveStatePoint.txBefore(
        5, range.getBlockHash(), range.getPrepareTxNum())).close();
    ArchiveReaderException wrongTxNum = assertThrows(ArchiveReaderException.class,
        () -> service.getReaderFactory().open(ArchiveStatePoint.blockEnd(
            5, range.getBlockHash(), range.getFinalizeTxNum() + 1)));
    assertEquals(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE, wrongTxNum.getReason());
    ArchiveReaderException txBeforeAtFinalize = assertThrows(ArchiveReaderException.class,
        () -> service.getReaderFactory().open(ArchiveStatePoint.txBefore(
            5, range.getBlockHash(), range.getFinalizeTxNum() - 1)));
    assertEquals(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE,
        txBeforeAtFinalize.getReason());
    byte[] wrongBlockHash = range.getBlockHash();
    wrongBlockHash[0] ^= 1;
    ArchiveReaderException wrongHash = assertThrows(ArchiveReaderException.class,
        () -> service.getReaderFactory().open(ArchiveStatePoint.blockEnd(
            5, wrongBlockHash, range.getFinalizeTxNum())));
    assertEquals(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE, wrongHash.getReason());
    ArchiveReaderException missingHash = assertThrows(ArchiveReaderException.class,
        () -> service.getReaderFactory().open(ArchiveStatePoint.blockEnd(
            5, null, range.getFinalizeTxNum())));
    assertEquals(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE, missingHash.getReason());
    ArchiveReaderException uncovered = assertThrows(ArchiveReaderException.class,
        () -> service.getReaderFactory().open(ArchiveStatePoint.blockEnd(
            6, null, range.getFinalizeTxNum())));
    assertEquals(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE, uncovered.getReason());
  }

  @Test
  public void commitBlockDoesNotExposeReaderStateUntilPublish() throws Exception {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    DefaultArchiveService service =
        new DefaultArchiveService(true, index, context, temporal);

    byte[] addr = new byte[21];
    addr[0] = 0x41;
    BlockCapsule b = blockWithParentSeed(5, (byte) 5);
    service.beginBlock(b, ArchiveSource.NORMAL);
    service.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    service.endTx();
    service.beginSystemTx(b, ArchivePhase.BLOCK_FINALIZE);
    long finalizeTxNum = context.current().orElseThrow(AssertionError::new).getTxNum();
    service.getCaptureEngine().capturePut("account", addr, null, account(1));
    service.endTx();
    service.commitBlock(b);

    assertFalse(index.getBlockRange(5).isPresent());
    assertEquals(0, temporal.changeCount());
    ArchiveReaderException unpublished = assertThrows(ArchiveReaderException.class,
        () -> service.getReaderFactory().open(ArchiveStatePoint.blockEnd(
            5, b.getBlockId().getBytes(), finalizeTxNum)));
    assertEquals(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE, unpublished.getReason());

    service.publishSolidifiedBlocks(5);
    assertTrue(index.getBlockRange(5).isPresent());
    assertEquals(1, temporal.changeCount());
    service.getReaderFactory().open(ArchiveStatePoint.blockEnd(
        5, b.getBlockId().getBytes(), finalizeTxNum)).close();
  }

  @Test
  public void explicitUserTxCountAllowsGenesisSyntheticTransactions() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    DefaultArchiveService service = new DefaultArchiveService(true, index, context);

    BlockCapsule b = block(0);
    b.addTransaction(new TransactionCapsule(Transaction.getDefaultInstance()));
    service.beginBlock(b, ArchiveSource.NORMAL);
    service.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    service.endTx();
    service.beginSystemTx(b, ArchivePhase.BLOCK_FINALIZE);
    service.endTx();

    service.commitBlock(b, 0);
    service.publishSolidifiedBlocks(0);

    ArchiveBlockRange range = index.getBlockRange(0).orElseThrow(AssertionError::new);
    assertEquals(0, range.getUserTxCount());
    assertEquals(0, range.getPrepareTxNum());
    assertEquals(1, range.getFinalizeTxNum());
  }

  @Test
  public void enabledUserTxEntersContextWithTxId() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    DefaultArchiveService service = new DefaultArchiveService(true, index, context);

    BlockCapsule b = block(7);
    TransactionCapsule tx = new TransactionCapsule(Transaction.getDefaultInstance());
    service.beginBlock(b, ArchiveSource.NORMAL);
    service.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    service.endTx();

    service.beginUserTx(b, 0, tx);
    ArchiveTxPosition pos = context.current().orElseThrow(AssertionError::new);
    assertEquals(ArchivePhase.USER_TX, pos.getPhase());
    assertEquals(0, pos.getTxIndex());
    assertArrayEquals(tx.getTransactionId().getBytes(), pos.getTxId());
    service.endTx();
    assertFalse(context.current().isPresent());
  }

  private static byte[] account(long balance) {
    return Account.newBuilder().setBalance(balance).build().toByteArray();
  }

  private static long balanceOf(byte[] accountBytes) throws Exception {
    return Account.parseFrom(accountBytes).getBalance();
  }

  private static void commitEmptyBlock(DefaultArchiveService service, BlockCapsule block) {
    service.beginBlock(block, ArchiveSource.NORMAL);
    service.beginSystemTx(block, ArchivePhase.BLOCK_PREPARE);
    service.endTx();
    service.beginSystemTx(block, ArchivePhase.BLOCK_FINALIZE);
    service.endTx();
    service.commitBlock(block);
  }

  private static ArchiveJournalToken journalEmptyBlock(DefaultArchiveService service,
      BlockCapsule block) {
    service.beginBlock(block, ArchiveSource.NORMAL);
    service.beginSystemTx(block, ArchivePhase.BLOCK_PREPARE);
    service.endTx();
    service.beginSystemTx(block, ArchivePhase.BLOCK_FINALIZE);
    service.endTx();
    return service.commitBlockJournaled(block, 0);
  }

  private static void commitAccountChangeBlock(DefaultArchiveService service, BlockCapsule block,
      byte[] addr, byte[] oldAccount, byte[] newAccount) {
    service.beginBlock(block, ArchiveSource.NORMAL);
    service.beginSystemTx(block, ArchivePhase.BLOCK_PREPARE);
    service.endTx();
    service.beginSystemTx(block, ArchivePhase.BLOCK_FINALIZE);
    service.getCaptureEngine().capturePut("account", addr, oldAccount, newAccount);
    service.endTx();
    service.commitBlock(block);
  }

  private static DefaultArchiveService serviceWithInFlightStore(ArchiveTxNumIndex index,
      ArchiveExecutionContext context, ArchiveTemporalStore temporal,
      ArchiveInFlightStore inFlightStore) {
    return new DefaultArchiveService(true, index, context, temporal, inFlightStore,
        new DefaultArchiveDomainRegistry(), new DefaultArchiveDomainCatalog());
  }

  private static DefaultArchiveService serviceWithPublisherConfig(ArchiveTxNumIndex index,
      ArchiveExecutionContext context, ArchiveTemporalStore temporal,
      ArchiveInFlightStore inFlightStore, ArchivePublisherConfig publisherConfig) {
    return new DefaultArchiveService(true, index, context, temporal, inFlightStore,
        new DefaultArchiveDomainRegistry(), new DefaultArchiveDomainCatalog(),
        ArchiveReadThrough.NONE, ArchiveLifecycle.Phase.RUNNING, ArchiveQueryLimits.unlimited(),
        publisherConfig, () -> {
        });
  }

  private static ArchivePublisherConfig startupByteBudget(long hardBytes) {
    return new ArchivePublisherConfig(false, false, 8, 8, hardBytes, hardBytes,
        100L, 100L, 0L, 0L, 1_000L);
  }

  private static ArchivePublisherConfig startupBudget(
      int hardBlocks, long hardRecords, long hardBytes) {
    return new ArchivePublisherConfig(false, false, hardBlocks, hardBlocks,
        hardBytes, hardBytes, hardRecords, hardRecords, 0L, 0L, 1_000L);
  }

  @Test
  public void healthyJournalAppendsReuseRecentDiskSample() {
    CountingCapacityInFlightStore inFlightStore = new CountingCapacityInFlightStore();
    DefaultArchiveService service = serviceWithPublisherConfig(
        new InMemoryArchiveTxNumIndex(), new ArchiveExecutionContext(),
        new InMemoryArchiveTemporalStore(), inFlightStore, startupByteBudget(1024L * 1024L));
    try {
      assertEquals(1, inFlightStore.capacityReads);

      journalEmptyBlock(service, blockWithParentSeed(5L, (byte) 5));
      journalEmptyBlock(service, blockWithParentSeed(6L, (byte) 6));

      assertEquals(1, inFlightStore.capacityReads);
    } finally {
      service.close();
    }
  }

  @Test
  public void nearThresholdDiskSampleIsRefreshedBeforeJournalWrite() {
    TrackingArchiveTxNumIndex index = new TrackingArchiveTxNumIndex();
    CountingCapacityInFlightStore inFlightStore = new CountingCapacityInFlightStore();
    DefaultArchiveService service = serviceWithPublisherConfig(
        index, new ArchiveExecutionContext(), new InMemoryArchiveTemporalStore(),
        inFlightStore, startupByteBudget(1024L * 1024L));
    try {
      ReflectUtils.setFieldValue(service, "lastUsableSpaceBytes", 1L);
      ReflectUtils.setFieldValue(service, "lastDiskSampleNanos", System.nanoTime());
      inFlightStore.usableSpace = 0L;

      ArchiveException failure = assertThrows(ArchiveException.class,
          () -> journalEmptyBlock(service, blockWithParentSeed(5L, (byte) 5)));

      assertTrue(failure.getMessage().contains("cannot reserve the next durable write"));
      assertEquals(2, inFlightStore.capacityReads);
      assertTrue(index.repairReason.contains("cannot reserve the next durable write"));
    } finally {
      service.close();
    }
  }

  @Test
  public void staleHighDiskSampleCannotHideDurableJournalFailure() {
    TrackingArchiveTxNumIndex index = new TrackingArchiveTxNumIndex();
    CountingCapacityInFlightStore inFlightStore = new CountingCapacityInFlightStore();
    DefaultArchiveService service = serviceWithPublisherConfig(
        index, new ArchiveExecutionContext(), new InMemoryArchiveTemporalStore(),
        inFlightStore, startupByteBudget(1024L * 1024L));
    try {
      inFlightStore.failWrites = true;

      ArchiveException failure = assertThrows(ArchiveException.class,
          () -> journalEmptyBlock(service, blockWithParentSeed(5L, (byte) 5)));

      assertTrue(failure.getMessage().contains("injected journal disk failure"));
      assertEquals(1, inFlightStore.capacityReads);
      assertTrue(index.repairReason.contains("injected journal disk failure"));
    } finally {
      service.close();
    }
  }

  private static void invokeMarkFatal(DefaultArchiveService service, RuntimeException failure) {
    ReflectUtils.invokeMethod(service, "markFatal",
        new Class<?>[]{RuntimeException.class}, failure);
  }

  private static DefaultArchiveService serviceWithReadThrough(ArchiveTxNumIndex index,
      ArchiveExecutionContext context, ArchiveTemporalStore temporal,
      ArchiveInFlightStore inFlightStore, ArchiveReadThrough readThrough) {
    return new DefaultArchiveService(true, index, context, temporal, inFlightStore,
        new DefaultArchiveDomainRegistry(), new DefaultArchiveDomainCatalog(), readThrough);
  }

  @Test
  public void journalAcknowledgementIsDurableAndIdempotent() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    InMemoryArchiveInFlightStore inFlightStore = new InMemoryArchiveInFlightStore();
    DefaultArchiveService service = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), new InMemoryArchiveTemporalStore(), inFlightStore);
    BlockCapsule block = blockWithParentSeed(5, (byte) 5);

    ArchiveJournalToken token = journalEmptyBlock(service, block);
    ArchiveInFlightBlock journaled = inFlightStore.loadBlocks().get(0);
    assertEquals(token, journaled.getJournalToken());
    assertEquals(ArchiveInFlightBlock.JournalState.JOURNALED, journaled.getJournalState());

    service.acknowledgeCanonicalCommit(token);
    service.acknowledgeCanonicalCommit(token);

    ArchiveInFlightBlock acknowledged = inFlightStore.loadBlocks().get(0);
    assertEquals(token, acknowledged.getJournalToken());
    assertEquals(ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED,
        acknowledged.getJournalState());
    service.publishSolidifiedBlocks(5);
    assertTrue(index.getBlockRange(5).isPresent());
    assertTrue(inFlightStore.loadBlocks().isEmpty());
  }

  @Test
  public void startupSharesByteBudgetWithoutChargingStaleJournalToRuntimeBacklog() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    DeleteFailingArchiveInFlightStore inFlightStore = new DeleteFailingArchiveInFlightStore();
    DefaultArchiveService service = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), temporal, inFlightStore);
    commitEmptyBlock(service, blockWithParentSeed(5, (byte) 5));
    service.publishSolidifiedBlocks(5L);
    commitEmptyBlock(service, blockWithParentSeed(6, (byte) 6));
    service.close();

    List<ArchiveInFlightBlock> journals = inFlightStore.loadBlocks();
    assertEquals(2, journals.size());
    ArchiveInFlightBlock stale = journals.get(0);
    ArchiveInFlightBlock loaded = journals.get(1);
    assertTrue(index.getBlockRange(stale.getRange().getBlockNum()).isPresent());
    assertFalse(index.getBlockRange(loaded.getRange().getBlockNum()).isPresent());
    long combinedBytes = stale.estimatedRetainedBytes() + loaded.estimatedRetainedBytes();

    DefaultArchiveService exactBudget = serviceWithPublisherConfig(
        index, new ArchiveExecutionContext(), temporal, inFlightStore,
        startupByteBudget(combinedBytes));
    try {
      int runtimeBlocks = ReflectUtils.getFieldValue(exactBudget, "inFlightBlockCount");
      long runtimeBytes = ReflectUtils.getFieldValue(exactBudget, "inFlightRetainedBytes");
      assertEquals(1, runtimeBlocks);
      assertEquals(loaded.estimatedRetainedBytes(), runtimeBytes);
    } finally {
      exactBudget.close();
    }

    ArchiveException failure = assertThrows(ArchiveException.class, () -> {
      DefaultArchiveService unexpected = serviceWithPublisherConfig(
          index, new ArchiveExecutionContext(), temporal, inFlightStore,
          startupByteBudget(combinedBytes - 1L));
      unexpected.close();
    });
    assertTrue(failure.getMessage().contains("startup journals exceed configured hard byte limit"));
    assertTrue(failure.getMessage().contains("staleBytes="));
    assertTrue(failure.getMessage().contains("loadedBytes="));
  }

  @Test
  public void startupBlockLimitAppliesAcrossStaleAndActiveJournals() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    DeleteFailingArchiveInFlightStore inFlightStore = new DeleteFailingArchiveInFlightStore();
    DefaultArchiveService service = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), temporal, inFlightStore);
    commitEmptyBlock(service, blockWithParentSeed(5, (byte) 5));
    service.publishSolidifiedBlocks(5L);
    commitEmptyBlock(service, blockWithParentSeed(6, (byte) 6));
    service.close();

    ArchiveException failure = assertThrows(ArchiveException.class, () -> {
      DefaultArchiveService unexpected = serviceWithPublisherConfig(
          index, new ArchiveExecutionContext(), temporal, inFlightStore,
          startupBudget(1, 100L, 1024L * 1024L));
      unexpected.close();
    });

    assertTrue(failure.getMessage().contains("startup journals exceed configured hard limit"));
    assertTrue(failure.getMessage().contains("totalBlocks=2"));
    assertTrue(failure.getMessage().contains("staleBlocks=1"));
    assertTrue(failure.getMessage().contains("loadedBlocks=1"));
  }

  @Test
  public void startupRecordLimitAppliesAcrossStaleAndActiveJournals() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    DeleteFailingArchiveInFlightStore inFlightStore = new DeleteFailingArchiveInFlightStore();
    DefaultArchiveService service = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), temporal, inFlightStore);
    byte[] address = new byte[21];
    address[0] = 0x41;
    commitAccountChangeBlock(service, blockWithParentSeed(5, (byte) 5),
        address, null, account(1L));
    service.publishSolidifiedBlocks(5L);
    commitAccountChangeBlock(service, blockWithParentSeed(6, (byte) 6),
        address, account(1L), account(2L));
    service.close();

    ArchiveException failure = assertThrows(ArchiveException.class, () -> {
      DefaultArchiveService unexpected = serviceWithPublisherConfig(
          index, new ArchiveExecutionContext(), temporal, inFlightStore,
          startupBudget(8, 1L, 1024L * 1024L));
      unexpected.close();
    });

    assertTrue(failure.getMessage().contains("startup journals exceed configured hard limit"));
    assertTrue(failure.getMessage().contains("totalRecords=2"));
    assertTrue(failure.getMessage().contains("staleRecords=1"));
    assertTrue(failure.getMessage().contains("loadedRecords=1"));
  }

  @Test
  public void exactJournalRollbackIsIdempotent() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    InMemoryArchiveInFlightStore inFlightStore = new InMemoryArchiveInFlightStore();
    DefaultArchiveService service = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), new InMemoryArchiveTemporalStore(), inFlightStore);
    ArchiveJournalToken token = journalEmptyBlock(
        service, blockWithParentSeed(5, (byte) 5));

    service.rollbackJournaledBlock(token);
    service.rollbackJournaledBlock(token);

    assertTrue(inFlightStore.loadBlocks().isEmpty());
    assertFalse(index.getBlockRange(5).isPresent());
    InMemoryArchiveTxNumIndex execution =
        ReflectUtils.getFieldValue(service, "executionTxNumIndex");
    assertFalse(execution.getBlockRange(5).isPresent());
    service.validateAvailable();
  }

  @Test
  public void staleJournalTokenCannotDeleteReplacementGeneration() {
    InMemoryArchiveInFlightStore inFlightStore = new InMemoryArchiveInFlightStore();
    DefaultArchiveService service = serviceWithInFlightStore(
        new InMemoryArchiveTxNumIndex(), new ArchiveExecutionContext(),
        new InMemoryArchiveTemporalStore(), inFlightStore);
    ArchiveJournalToken token = journalEmptyBlock(
        service, blockWithParentSeed(5, (byte) 5));
    byte[] staleNonce = token.getGenerationNonce();
    staleNonce[0] ^= 1;
    ArchiveJournalToken stale = new ArchiveJournalToken(token.getBlockNum(),
        token.getBlockHash(), staleNonce, token.getSchemaChecksum());

    service.rollbackJournaledBlock(stale);

    assertEquals(1, inFlightStore.loadBlocks().size());
    assertEquals(token, inFlightStore.loadBlocks().get(0).getJournalToken());
    service.rollbackJournaledBlock(token);
    assertTrue(inFlightStore.loadBlocks().isEmpty());
  }

  @Test
  public void unacknowledgedJournalCannotBePublished() {
    DefaultArchiveService service = new DefaultArchiveService(
        true, new InMemoryArchiveTxNumIndex(), new ArchiveExecutionContext());
    journalEmptyBlock(service, blockWithParentSeed(5, (byte) 5));

    ArchiveException error = assertThrows(ArchiveException.class,
        () -> service.publishSolidifiedBlocks(5));

    assertTrue(error.getMessage().contains("unacknowledged archive journal"));
    assertThrows(ArchiveException.class, service::validateAvailable);
  }

  @Test
  public void acknowledgedJournalCannotBeCompensated() {
    DefaultArchiveService service = new DefaultArchiveService(
        true, new InMemoryArchiveTxNumIndex(), new ArchiveExecutionContext());
    ArchiveJournalToken token = journalEmptyBlock(
        service, blockWithParentSeed(5, (byte) 5));
    service.acknowledgeCanonicalCommit(token);

    ArchiveException error = assertThrows(ArchiveException.class,
        () -> service.rollbackJournaledBlock(token));

    assertTrue(error.getMessage().contains("canonical-committed"));
    assertThrows(ArchiveException.class, service::validateAvailable);
  }

  @Test
  public void startupReconcilePublishesSolidifiedInFlightBlocks() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    InMemoryArchiveInFlightStore inFlightStore = new InMemoryArchiveInFlightStore();
    BlockCapsule b = blockWithParentSeed(5, (byte) 5);
    DefaultArchiveService service = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), temporal, inFlightStore);
    commitEmptyBlock(service, b);
    assertFalse(index.getBlockRange(5).isPresent());
    assertEquals(1, inFlightStore.loadBlocks().size());
    service.close();

    DefaultArchiveService restarted = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), temporal, inFlightStore);
    try {
      restarted.reconcileInFlightOnStartup(5, blockNum -> b);

      assertTrue(index.getBlockRange(5).isPresent());
      assertEquals(0, inFlightStore.loadBlocks().size());
    } finally {
      restarted.close();
    }
  }

  @Test
  public void emptyCanonicalStartupRollsBackOrphanGenesisJournalBeforeReplay() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    InMemoryArchiveInFlightStore inFlightStore = new InMemoryArchiveInFlightStore();
    BlockCapsule genesis = blockWithParentSeed(0, (byte) 0);
    DefaultArchiveService service = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), temporal, inFlightStore);
    journalEmptyBlock(service, genesis);
    service.close();

    DefaultArchiveService restarted = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), temporal, inFlightStore);
    try {
      restarted.reconcilePublishedHeadOnStartup(-1L);
      restarted.reconcileInFlightOnStartup(-1L, -1L,
          blockNum -> {
            throw new AssertionError("empty canonical startup must not request a block");
          });

      assertTrue(inFlightStore.loadBlocks().isEmpty());
      ArchiveJournalToken replacement = journalEmptyBlock(restarted, genesis);
      assertEquals(0L, replacement.getBlockNum());
    } finally {
      restarted.close();
    }
  }

  @Test
  public void emptyCanonicalStartupRejectsAcknowledgedGenesisWithoutMutation() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    InMemoryArchiveInFlightStore inFlightStore = new InMemoryArchiveInFlightStore();
    DefaultArchiveService service = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), temporal, inFlightStore);
    commitEmptyBlock(service, blockWithParentSeed(0, (byte) 0));
    service.close();

    DefaultArchiveService restarted = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), temporal, inFlightStore);
    try {
      ArchiveException error = assertThrows(ArchiveException.class,
          () -> restarted.reconcileInFlightOnStartup(-1L, -1L, ignored -> null));

      assertTrue(error.getMessage().contains("unacknowledged genesis"));
      assertEquals(1, inFlightStore.loadBlocks().size());
      assertEquals(ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED,
          inFlightStore.loadBlocks().get(0).getJournalState());
    } finally {
      restarted.close();
    }
  }

  @Test
  public void emptyCanonicalStartupRejectsNonGenesisJournalWithoutMutation() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    InMemoryArchiveInFlightStore inFlightStore = new InMemoryArchiveInFlightStore();
    DefaultArchiveService service = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), temporal, inFlightStore);
    journalEmptyBlock(service, blockWithParentSeed(5, (byte) 5));
    service.close();

    DefaultArchiveService restarted = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), temporal, inFlightStore);
    try {
      assertThrows(ArchiveException.class,
          () -> restarted.reconcileInFlightOnStartup(-1L, -1L, ignored -> null));

      assertEquals(1, inFlightStore.loadBlocks().size());
      assertEquals(5L, inFlightStore.loadBlocks().get(0).getRange().getBlockNum());
    } finally {
      restarted.close();
    }
  }

  @Test
  public void emptyCanonicalStartupRejectsMultipleJournalsWithoutMutation() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    InMemoryArchiveInFlightStore inFlightStore = new InMemoryArchiveInFlightStore();
    DefaultArchiveService service = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), temporal, inFlightStore);
    journalEmptyBlock(service, blockWithParentSeed(0, (byte) 0));
    journalEmptyBlock(service, blockWithParentSeed(1, (byte) 1));
    service.close();

    DefaultArchiveService restarted = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), temporal, inFlightStore);
    try {
      ArchiveException error = assertThrows(ArchiveException.class,
          () -> restarted.reconcileInFlightOnStartup(-1L, -1L, ignored -> null));

      assertTrue(error.getMessage().contains("2 in-flight journal blocks"));
      assertEquals(2, inFlightStore.loadBlocks().size());
    } finally {
      restarted.close();
    }
  }

  @Test
  public void emptyCanonicalStartupRejectsPublishedArchiveState() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    DefaultArchiveService service = new DefaultArchiveService(
        true, index, new ArchiveExecutionContext());
    commitEmptyBlock(service, blockWithParentSeed(0, (byte) 0));
    service.publishSolidifiedBlocks(0L);

    ArchiveException error = assertThrows(ArchiveException.class,
        () -> service.reconcilePublishedHeadOnStartup(-1L));

    assertTrue(error.getMessage().contains("canonical database is empty"));
    assertThrows(ArchiveException.class, service::validateAvailable);
  }

  @Test
  public void residualPublishedJournalIsNotDeletedBeforeEmptyCanonicalFailure() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    InMemoryArchiveInFlightStore inFlightStore = new InMemoryArchiveInFlightStore();
    BlockCapsule block = blockWithParentSeed(5, (byte) 5);
    DefaultArchiveService service = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), temporal, inFlightStore);
    commitEmptyBlock(service, block);
    ArchiveInFlightBlock residual = inFlightStore.loadBlocks().get(0);
    service.publishSolidifiedBlocks(5L);
    service.close();
    inFlightStore.putBlock(residual);

    DefaultArchiveService restarted = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), temporal, inFlightStore);
    try {
      assertEquals(1, inFlightStore.loadBlocks().size());

      assertThrows(ArchiveException.class,
          () -> restarted.reconcilePublishedHeadOnStartup(-1L));

      assertEquals(1, inFlightStore.loadBlocks().size());
      assertEquals(residual.getJournalToken(),
          inFlightStore.loadBlocks().get(0).getJournalToken());
    } finally {
      restarted.close();
    }
  }

  @Test
  public void residualPublishedJournalIsCleanedAfterCanonicalPreflight() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    InMemoryArchiveInFlightStore inFlightStore = new InMemoryArchiveInFlightStore();
    BlockCapsule block = blockWithParentSeed(5, (byte) 5);
    DefaultArchiveService service = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), temporal, inFlightStore);
    commitEmptyBlock(service, block);
    ArchiveInFlightBlock residual = inFlightStore.loadBlocks().get(0);
    service.publishSolidifiedBlocks(5L);
    service.close();
    inFlightStore.putBlock(residual);

    DefaultArchiveService restarted = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), temporal, inFlightStore);
    try {
      assertEquals(1, inFlightStore.loadBlocks().size());

      restarted.reconcilePublishedHeadOnStartup(5L);
      assertEquals(1, inFlightStore.loadBlocks().size());
      restarted.reconcileInFlightOnStartup(5L, 5L, ignored -> block);

      assertTrue(inFlightStore.loadBlocks().isEmpty());
      assertTrue(index.getBlockRange(5L).isPresent());
    } finally {
      restarted.close();
    }
  }

  @Test
  public void startupReconcileRejectsPublishedBlocksAfterCanonicalHead() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    InMemoryArchiveInFlightStore inFlightStore = new InMemoryArchiveInFlightStore();
    byte[] addr = new byte[21];
    addr[0] = 0x41;
    BlockCapsule b5 = blockWithParentSeed(5, (byte) 5);
    BlockCapsule b6 = blockWithParentSeed(6, (byte) 6);
    DefaultArchiveService service = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), temporal, inFlightStore);
    commitEmptyBlock(service, b5);
    service.beginBlock(b6, ArchiveSource.NORMAL);
    service.beginSystemTx(b6, ArchivePhase.BLOCK_PREPARE);
    service.endTx();
    service.beginSystemTx(b6, ArchivePhase.BLOCK_FINALIZE);
    service.getCaptureEngine().capturePut("account", addr, null, account(60));
    service.endTx();
    service.commitBlock(b6);
    service.publishSolidifiedBlocks(6);
    assertTrue(index.getBlockRange(6).isPresent());
    assertTrue(temporal.latest(ArchiveDomain.ACCOUNT, addr).isPresent());
    service.close();

    DefaultArchiveService restarted = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), temporal, inFlightStore);
    try {
      ArchiveException error = assertThrows(ArchiveException.class,
          () -> restarted.reconcilePublishedHeadOnStartup(5));
      assertTrue(error.getMessage().contains("published archive head 6"));
      assertTrue(index.getBlockRange(5).isPresent());
      assertTrue(index.getBlockRange(6).isPresent());
      assertTrue(temporal.latest(ArchiveDomain.ACCOUNT, addr).isPresent());
      assertThrows(ArchiveException.class, restarted::validateAvailable);
    } finally {
      restarted.close();
    }
  }

  @Test
  public void inFlightReconcileDoesNotUnwindPublishedHead() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    InMemoryArchiveInFlightStore inFlightStore = new InMemoryArchiveInFlightStore();
    BlockCapsule b5 = blockWithParentSeed(5, (byte) 5);
    DefaultArchiveService service = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), temporal, inFlightStore);
    commitEmptyBlock(service, b5);
    service.publishSolidifiedBlocks(5);
    service.close();

    DefaultArchiveService restarted = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), temporal, inFlightStore);
    try {
      restarted.reconcileInFlightOnStartup(0, 0, blockNum -> null);

      assertTrue(index.getBlockRange(5).isPresent());
    } finally {
      restarted.close();
    }
  }

  @Test
  public void startupRejectsInFlightPrevValueMismatchAgainstTemporalLatest() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    InMemoryArchiveInFlightStore inFlightStore = new InMemoryArchiveInFlightStore();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    DefaultArchiveService service = serviceWithInFlightStore(
        index, context, temporal, inFlightStore);
    byte[] addr = new byte[21];
    addr[0] = 0x41;

    BlockCapsule b4 = blockWithParentSeed(4, (byte) 4);
    service.beginBlock(b4, ArchiveSource.NORMAL);
    service.beginSystemTx(b4, ArchivePhase.BLOCK_PREPARE);
    service.endTx();
    service.beginSystemTx(b4, ArchivePhase.BLOCK_FINALIZE);
    service.getCaptureEngine().capturePut("account", addr, null, account(20));
    service.endTx();
    service.commitBlock(b4);
    service.publishSolidifiedBlocks(4);

    BlockCapsule b5 = blockWithParentSeed(5, (byte) 5);
    service.beginBlock(b5, ArchiveSource.NORMAL);
    service.beginSystemTx(b5, ArchivePhase.BLOCK_PREPARE);
    service.endTx();
    service.beginSystemTx(b5, ArchivePhase.BLOCK_FINALIZE);
    service.getCaptureEngine().capturePut("account", addr, account(20), account(30));
    service.endTx();
    service.commitBlock(b5);
    ArchiveInFlightBlock good = inFlightStore.loadBlocks().get(0);
    ArchiveChangeRecord goodRecord = good.getRecords().get(0);
    ArchiveChangeRecord badRecord = new ArchiveChangeRecord(goodRecord.getPosition(),
        goodRecord.getDomain(), goodRecord.getCanonicalKey(), DomainValue.present(account(99)),
        goodRecord.getValue());
    inFlightStore.putBlock(new ArchiveInFlightBlock(
        good.getRange(), good.getPositions(), Collections.singletonList(badRecord)));
    service.close();

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> serviceWithInFlightStore(
            index, new ArchiveExecutionContext(), temporal, inFlightStore));
    assertTrue(ex.getMessage().contains("prev-value chain mismatch"));
  }

  @Test
  public void readThroughUsesInFlightPrevBeforeLiveHead() throws Exception {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    InMemoryArchiveInFlightStore inFlightStore = new InMemoryArchiveInFlightStore();
    byte[] addr = new byte[21];
    addr[0] = 0x41;
    ArchiveReadThrough liveReadThrough = (domain, key, point) ->
        domain == ArchiveDomain.ACCOUNT && Arrays.equals(key, addr)
            ? Optional.of(DomainValue.present(account(99)))
            : Optional.empty();
    DefaultArchiveService service = serviceWithReadThrough(
        index, context, temporal, inFlightStore, liveReadThrough);

    BlockCapsule published = blockWithParentSeed(5, (byte) 5);
    commitEmptyBlock(service, published);
    service.publishSolidifiedBlocks(5);
    ArchiveBlockRange range = index.getBlockRange(5).orElseThrow(AssertionError::new);

    BlockCapsule inFlight = blockWithParentSeed(6, (byte) 6);
    service.beginBlock(inFlight, ArchiveSource.NORMAL);
    service.beginSystemTx(inFlight, ArchivePhase.BLOCK_PREPARE);
    service.endTx();
    service.beginSystemTx(inFlight, ArchivePhase.BLOCK_FINALIZE);
    service.getCaptureEngine().capturePut("account", addr, account(10), account(20));
    service.endTx();
    service.commitBlock(inFlight);

    ArchiveReadResult<AccountCapsule> result = service.getReaderFactory()
        .open(ArchiveStatePoint.blockEnd(5, range.getBlockHash(), range.getFinalizeTxNum()))
        .getAccount(addr);

    assertEquals(ArchiveReadResult.Status.PRESENT, result.getStatus());
    assertEquals(10, result.getValue().getBalance());
  }

  @Test
  public void readThroughReturnsEarliestPrevAcrossMultipleInFlightBlocks() throws Exception {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    InMemoryArchiveInFlightStore inFlightStore = new InMemoryArchiveInFlightStore();
    byte[] addr = new byte[21];
    addr[0] = 0x41;
    // No live-head value: only the in-flight chain can answer.
    ArchiveReadThrough liveReadThrough = (domain, key, point) -> Optional.empty();
    DefaultArchiveService service = serviceWithReadThrough(
        index, context, temporal, inFlightStore, liveReadThrough);

    BlockCapsule published = blockWithParentSeed(5, (byte) 5);
    commitEmptyBlock(service, published);
    service.publishSolidifiedBlocks(5);
    ArchiveBlockRange range = index.getBlockRange(5).orElseThrow(AssertionError::new);

    // Two UNPUBLISHED in-flight blocks change the same account: b6 (10->20), b7 (20->30).
    commitAccountChangeBlock(service, blockWithParentSeed(6, (byte) 6), addr,
        account(10), account(20));
    commitAccountChangeBlock(service, blockWithParentSeed(7, (byte) 7), addr,
        account(20), account(30));

    ArchiveReadResult<AccountCapsule> result = service.getReaderFactory()
        .open(ArchiveStatePoint.blockEnd(5, range.getBlockHash(), range.getFinalizeTxNum()))
        .getAccount(addr);

    // At block 5 (before both in-flight changes) the value is the EARLIEST in-flight change's
    // pre-value (b6's 10) -- NOT b7's pre-value (20) and not a temporal miss.
    assertEquals(ArchiveReadResult.Status.PRESENT, result.getStatus());
    assertEquals(10, result.getValue().getBalance());
  }

  @Test
  public void genesisCompleteArchiveDoesNotUseLiveReadThroughOnTemporalMiss() throws Exception {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    byte[] addr = new byte[21];
    addr[0] = 0x41;
    ArchiveReadThrough liveReadThrough = (domain, key, point) ->
        Optional.of(DomainValue.present(account(99)));
    DefaultArchiveService service = serviceWithReadThrough(
        index, context, temporal, new InMemoryArchiveInFlightStore(), liveReadThrough);

    BlockCapsule genesis = blockWithParentSeed(0, (byte) 0);
    commitEmptyBlock(service, genesis);
    service.publishSolidifiedBlocks(0);
    ArchiveBlockRange range = index.getBlockRange(0).orElseThrow(AssertionError::new);

    ArchiveReadResult<AccountCapsule> result = service.getReaderFactory()
        .open(ArchiveStatePoint.blockEnd(0, range.getBlockHash(), range.getFinalizeTxNum()))
        .getAccount(addr);

    assertEquals(ArchiveReadResult.Status.MISSING, result.getStatus());
  }

  @Test
  public void midChainTemporalMissDoesNotFallbackToLiveHead() throws Exception {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    byte[] addr = new byte[21];
    addr[0] = 0x41;
    ArchiveReadThrough liveReadThrough = (domain, key, point) ->
        Optional.of(DomainValue.present(account(99)));
    DefaultArchiveService service = serviceWithReadThrough(
        index, new ArchiveExecutionContext(), temporal,
        new InMemoryArchiveInFlightStore(), liveReadThrough);
    BlockCapsule block = blockWithParentSeed(5, (byte) 5);
    commitEmptyBlock(service, block);
    service.publishSolidifiedBlocks(5);
    ArchiveBlockRange range = index.getBlockRange(5).orElseThrow(AssertionError::new);

    ArchiveReaderException error = assertThrows(ArchiveReaderException.class, () -> {
      try (ArchiveStateReader reader = service.getReaderFactory().open(
          ArchiveStatePoint.blockEnd(5, range.getBlockHash(), range.getFinalizeTxNum()))) {
        reader.getAccount(addr);
      }
    });

    assertEquals(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE, error.getReason());
  }

  @Test
  public void startupReconcileRollsBackCanonicalHashMismatch() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    InMemoryArchiveInFlightStore inFlightStore = new InMemoryArchiveInFlightStore();
    BlockCapsule b = blockWithParentSeed(5, (byte) 5);
    DefaultArchiveService service = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), temporal, inFlightStore);
    commitEmptyBlock(service, b);
    service.close();

    DefaultArchiveService restarted = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), temporal, inFlightStore);
    try {
      restarted.reconcileInFlightOnStartup(5,
          blockNum -> blockWithParentSeed(5, (byte) 9));
      assertFalse(index.getBlockRange(5).isPresent());
      assertTrue(inFlightStore.loadBlocks().isEmpty());
      restarted.validateAvailable();
    } finally {
      restarted.close();
    }
  }

  @Test
  public void startupReconcileRejectsInFlightBlockWithoutCanonical() {
    // reconcile loop, canonical==null branch: an in-flight block whose canonical provider returns
    // null fails closed (never silently dropped).
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    InMemoryArchiveInFlightStore inFlightStore = new InMemoryArchiveInFlightStore();
    BlockCapsule b = blockWithParentSeed(5, (byte) 5);
    DefaultArchiveService service = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), temporal, inFlightStore);
    commitEmptyBlock(service, b);
    service.close();

    DefaultArchiveService restarted = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), temporal, inFlightStore);
    try {
      ArchiveException ex = assertThrows(ArchiveException.class,
          () -> restarted.reconcileInFlightOnStartup(5, blockNum -> null));

      assertTrue(ex.getMessage().contains("has no canonical block"));
      assertFalse(index.getBlockRange(5).isPresent());
      assertThrows(ArchiveException.class, restarted::validateAvailable);
    } finally {
      restarted.close();
    }
  }

  @Test
  public void startupReconcileRollsBackCanonicalBlockNumberMismatch() {
    // reconcile loop, height-mismatch branch: canonical provider resolves in-flight block 5 to a
    // block reporting a different height (6) -> fail closed.
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    InMemoryArchiveInFlightStore inFlightStore = new InMemoryArchiveInFlightStore();
    BlockCapsule b5 = blockWithParentSeed(5, (byte) 5);
    DefaultArchiveService service = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), temporal, inFlightStore);
    commitEmptyBlock(service, b5);
    service.close();

    BlockCapsule wrongHeight = blockWithParentSeed(6, (byte) 6);
    DefaultArchiveService restarted = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), temporal, inFlightStore);
    try {
      restarted.reconcileInFlightOnStartup(5, blockNum -> wrongHeight);
      assertFalse(index.getBlockRange(5).isPresent());
      assertTrue(inFlightStore.loadBlocks().isEmpty());
      restarted.validateAvailable();
    } finally {
      restarted.close();
    }
  }

  @Test
  public void startupReconcileRejectsNullCanonicalProvider() {
    // reconcile with a null canonical provider fails closed (and fail-stops the service).
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    InMemoryArchiveInFlightStore inFlightStore = new InMemoryArchiveInFlightStore();
    DefaultArchiveService service = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), temporal, inFlightStore);
    try {
      ArchiveException ex = assertThrows(ArchiveException.class,
          () -> service.reconcileInFlightOnStartup(5, null));

      assertTrue(ex.getMessage().contains("requires canonical blocks"));
      assertThrows(ArchiveException.class, service::validateAvailable);
    } finally {
      service.close();
    }
  }

  @Test
  public void startupRejectsNonContiguousInFlightBlockAfterPublishedTail() {
    // validateInFlightAppend block-number branch: after publishing block 4, an in-flight journal
    // entry for block 6 (skipping 5) whose firstTxNum is contiguous trips ONLY the block-number
    // contiguity guard.
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    InMemoryArchiveInFlightStore inFlightStore = new InMemoryArchiveInFlightStore();
    BlockCapsule b4 = blockWithParentSeed(4, (byte) 4);
    DefaultArchiveService service = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), temporal, inFlightStore);
    commitEmptyBlock(service, b4);
    service.publishSolidifiedBlocks(4);
    assertTrue(index.getBlockRange(4).isPresent());
    assertEquals(0, inFlightStore.loadBlocks().size());
    service.close();

    long nextTxNum = index.getNextTxNum();
    ArchiveBlockRange gapRange = new ArchiveBlockRange(
        6, nextTxNum, nextTxNum + 1, nextTxNum, nextTxNum + 1,
        blockWithParentSeed(6, (byte) 6).getBlockId().getBytes(), 0, ArchiveSource.NORMAL,
        schemaChecksum());
    inFlightStore.putBlock(new ArchiveInFlightBlock(
        gapRange, Collections.emptyList(), Collections.emptyList()));

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> serviceWithInFlightStore(
            index, new ArchiveExecutionContext(), temporal, inFlightStore));

    assertTrue(ex.getMessage().contains("non-contiguous archive in-flight block"));
    assertTrue(ex.getMessage().contains("expected block 5 but got 6"));
  }

  @Test
  public void startupRejectsDuplicateInFlightBlockNumber() {
    // validateInFlightAppend duplicate-block guard (defense-in-depth): a journal that returns the
    // same block twice must fail the second append instead of double-loading it.
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    InMemoryArchiveInFlightStore inFlightStore = new InMemoryArchiveInFlightStore();
    BlockCapsule b = blockWithParentSeed(5, (byte) 5);
    DefaultArchiveService service = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), temporal, inFlightStore);
    commitEmptyBlock(service, b);
    ArchiveInFlightBlock good = inFlightStore.loadBlocks().get(0);
    service.close();

    ArchiveInFlightStore duplicateStore = new ArchiveInFlightStore() {
      @Override
      public List<ArchiveInFlightBlock> loadBlocks() {
        return Arrays.asList(good, good);
      }

      @Override
      public void putBlock(ArchiveInFlightBlock block) {
      }

      @Override
      public void deleteBlock(long blockNum) {
      }
    };

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> serviceWithInFlightStore(
            index, new ArchiveExecutionContext(), temporal, duplicateStore));

    assertTrue(ex.getMessage().contains("already exists for block"));
  }

  @Test
  public void inFlightBufferCapFailStopsRunawayBacklog() {
    // The committed-not-solidified in-flight buffer is bounded: once it reaches the cap (nothing
    // solidifying to drain it), the next commit fail-stops instead of growing without limit.
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    InMemoryArchiveInFlightStore inFlightStore = new InMemoryArchiveInFlightStore();
    DefaultArchiveService service = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), temporal, inFlightStore);
    service.setMaxInFlightBlocksForTest(2);
    try {
      commitEmptyBlock(service, blockWithParentSeed(5, (byte) 5));  // in-flight size 1
      commitEmptyBlock(service, blockWithParentSeed(6, (byte) 6));  // in-flight size 2 (at cap)

      ArchiveException ex = assertThrows(ArchiveException.class,
          () -> commitEmptyBlock(service, blockWithParentSeed(7, (byte) 7)));
      assertTrue(ex.getMessage().contains("in-flight buffer reached its cap"));
      assertThrows(ArchiveException.class, service::validateAvailable);  // fail-stopped
    } finally {
      service.close();
    }
  }

  @Test
  public void openReaderGenesisCompleteReleasesLockAfterSnapshot() throws Exception {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    InMemoryArchiveInFlightStore inFlightStore = new InMemoryArchiveInFlightStore();
    DefaultArchiveService service = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), temporal, inFlightStore);
    try {
      BlockCapsule b0 = blockWithParentSeed(0, (byte) 0);
      commitEmptyBlock(service, b0);
      service.publishSolidifiedBlocks(0);
      assertEquals(0L, index.getFirstArchivedBlock());  // genesis-complete

      ArchiveBlockRange range = index.getBlockRange(0).get();
      ArchiveStatePoint point = ArchiveStatePoint.blockEnd(
          0, b0.getBlockId().getBytes(), range.getFinalizeTxNum());

      try (ArchiveStateReader reader = service.openReader(point)) {
        assertSame(point, reader.getPoint());
        // Genesis-complete releases the read lock once the snapshot is captured, so the SAME thread
        // can acquire the write lock to commit another block -- a deadlock if the read lock were
        // still held for the reader's whole lifetime (as it is on the mid-chain path).
        commitEmptyBlock(service, blockWithParentSeed(1, (byte) 1));
      }
    } finally {
      service.close();
    }
  }

  @Test
  public void readerLifecycleStartFailureIsMappedAndReleasesAdmission() throws Exception {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    DefaultArchiveService service = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), new InMemoryArchiveTemporalStore(),
        new InMemoryArchiveInFlightStore());
    try {
      BlockCapsule b5 = blockWithParentSeed(5, (byte) 5);
      commitEmptyBlock(service, b5);
      service.publishSolidifiedBlocks(5);
      ArchiveBlockRange range = index.getBlockRange(5).get();
      ArchiveStatePoint point = ArchiveStatePoint.blockEnd(
          5, b5.getBlockId().getBytes(), range.getFinalizeTxNum());

      try (ArchiveWorkLease writer = service.acquireWriterLease()) {
        writer.start();
        ArchiveReaderException failure = assertThrows(
            ArchiveReaderException.class, () -> service.openReader(point));
        assertEquals(ArchiveReaderException.Reason.INTERNAL_IO, failure.getReason());
        assertTrue(failure.getMessage().contains("nested archive lifecycle leases"));
      }

      try (ArchiveStateReader reader = service.openReader(point)) {
        assertSame(point, reader.getPoint());
      }
    } finally {
      service.close();
    }
  }

  @Test
  public void coverageReadFailureAfterAdmissionReleasesQueryLease() {
    InMemoryArchiveTxNumIndex index = spy(new InMemoryArchiveTxNumIndex());
    DefaultArchiveService service = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), new InMemoryArchiveTemporalStore(),
        new InMemoryArchiveInFlightStore());
    try {
      doThrow(new ArchiveException("injected coverage read failure"))
          .when(index).getFirstArchivedBlock();

      ArchiveException failure = assertThrows(ArchiveException.class,
          () -> service.openReader(ArchiveStatePoint.blockEnd(0L, new byte[32], 0L)));

      assertTrue(failure.getMessage().contains("injected coverage read failure"));
      ArchiveQueryCoordinator coordinator =
          ReflectUtils.getFieldValue(service, "queryCoordinator");
      assertEquals(0L, coordinator.getActiveLeaseCount());
    } finally {
      service.close();
    }
  }

  @Test
  public void genesisCompleteReadersRespectIndependentSnapshotLimit() throws Exception {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    DefaultArchiveService service = new DefaultArchiveService(
        true, index, new ArchiveExecutionContext(), new InMemoryArchiveTemporalStore(),
        new InMemoryArchiveInFlightStore(), new DefaultArchiveDomainRegistry(),
        new DefaultArchiveDomainCatalog(), ArchiveReadThrough.NONE,
        ArchiveLifecycle.Phase.RUNNING,
        ArchiveQueryLimits.builder()
            .maxConcurrentQueries(2)
            .maxOpenSnapshots(1)
            .build());
    try {
      BlockCapsule b0 = blockWithParentSeed(0, (byte) 0);
      commitEmptyBlock(service, b0);
      service.publishSolidifiedBlocks(0);
      ArchiveBlockRange range = index.getBlockRange(0).get();
      ArchiveStatePoint point = ArchiveStatePoint.blockEnd(
          0, b0.getBlockId().getBytes(), range.getFinalizeTxNum());

      ArchiveStateReader first = service.openReader(point);
      HistoricalQueryLimitException failure = assertThrows(
          HistoricalQueryLimitException.class, () -> service.openReader(point));
      assertEquals(HistoricalQueryLimitException.Limit.OPEN_SNAPSHOTS, failure.getLimit());
      first.close();
      try (ArchiveStateReader ignored = service.openReader(point)) {
        assertEquals(0L, ignored.getPoint().getBlockNum());
      }
    } finally {
      service.close();
    }
  }

  @Test
  public void dynamicBlockSelectorIsResolvedOnlyAfterQueryAdmissionAndBudgetCheck() {
    DefaultArchiveService service = new DefaultArchiveService(
        true, new InMemoryArchiveTxNumIndex(), new ArchiveExecutionContext(),
        new InMemoryArchiveTemporalStore(), new InMemoryArchiveInFlightStore(),
        new DefaultArchiveDomainRegistry(), new DefaultArchiveDomainCatalog(),
        ArchiveReadThrough.NONE, ArchiveLifecycle.Phase.RUNNING,
        ArchiveQueryLimits.builder().maxBackendReads(0).build());
    boolean[] providerCalled = {false};
    try {
      HistoricalQueryLimitException failure = assertThrows(
          HistoricalQueryLimitException.class,
          () -> service.openBlockEndReader(() -> {
            providerCalled[0] = true;
            return 0L;
          }, blockNum -> new byte[32]));

      assertEquals(HistoricalQueryLimitException.Limit.BACKEND_READS, failure.getLimit());
      assertFalse(providerCalled[0]);
    } finally {
      service.close();
    }
  }

  @Test
  public void genesisCompleteOpenReaderDoesNotBlockConcurrentCommit() throws Exception {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    InMemoryArchiveInFlightStore inFlightStore = new InMemoryArchiveInFlightStore();
    DefaultArchiveService service = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), temporal, inFlightStore);
    try {
      BlockCapsule b0 = blockWithParentSeed(0, (byte) 0);
      commitEmptyBlock(service, b0);
      service.publishSolidifiedBlocks(0);
      ArchiveBlockRange range = index.getBlockRange(0).get();
      ArchiveStatePoint point = ArchiveStatePoint.blockEnd(
          0, b0.getBlockId().getBytes(), range.getFinalizeTxNum());

      try (ArchiveStateReader reader = service.openReader(point)) {
        // Genesis-complete released the read lock after capturing the snapshot, so a commit on
        // ANOTHER thread proceeds while the reader is still open -- the whole point of the change.
        FutureTask<Void> commit = new FutureTask<>(() -> {
          commitEmptyBlock(service, blockWithParentSeed(1, (byte) 1));
          return null;
        });
        Thread thread = new Thread(commit, "concurrent-commit");
        thread.start();
        try {
          commit.get(5, TimeUnit.SECONDS);  // completes; the reader does not hold the write lock
        } finally {
          thread.join(1000);
        }
      }
    } finally {
      service.close();
    }
  }

  @Test
  public void midChainOpenReaderBlocksConcurrentCommitUntilClosed() throws Exception {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    InMemoryArchiveInFlightStore inFlightStore = new InMemoryArchiveInFlightStore();
    DefaultArchiveService service = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), temporal, inFlightStore);
    Thread thread = null;
    try {
      // Mid-chain start: the first archived block is 5 (> 0), so the reader holds the read lock for
      // its whole lifetime (the live read-through needs the write-blocking lock held).
      BlockCapsule b5 = blockWithParentSeed(5, (byte) 5);
      commitEmptyBlock(service, b5);
      service.publishSolidifiedBlocks(5);
      assertEquals(5L, index.getFirstArchivedBlock());
      ArchiveBlockRange range = index.getBlockRange(5).get();
      ArchiveStatePoint point = ArchiveStatePoint.blockEnd(
          5, b5.getBlockId().getBytes(), range.getFinalizeTxNum());

      ArchiveStateReader reader = service.openReader(point);  // holds the read lock
      FutureTask<Void> commit = new FutureTask<>(() -> {
        commitEmptyBlock(service, blockWithParentSeed(6, (byte) 6));
        return null;
      });
      thread = new Thread(commit, "blocked-commit");
      thread.start();

      // The write-blocking commit cannot proceed while the mid-chain reader holds the read lock.
      assertThrows(TimeoutException.class, () -> commit.get(500, TimeUnit.MILLISECONDS));

      reader.close();               // release the read lock
      commit.get(5, TimeUnit.SECONDS);  // now the commit proceeds
    } finally {
      if (thread != null) {
        thread.join(1000);
      }
      service.close();
    }
  }

  @Test
  public void startupReconcileRejectsCanonicalParentMismatch() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    InMemoryArchiveInFlightStore inFlightStore = new InMemoryArchiveInFlightStore();
    BlockCapsule b5 = blockWithParentSeed(5, (byte) 5);
    BlockCapsule b6 = blockWithParentSeed(6, (byte) 6);
    DefaultArchiveService service = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), temporal, inFlightStore);
    commitEmptyBlock(service, b5);
    commitEmptyBlock(service, b6);
    service.close();

    DefaultArchiveService restarted = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), temporal, inFlightStore);
    try {
      ArchiveException ex = assertThrows(ArchiveException.class,
          () -> restarted.reconcileInFlightOnStartup(6,
              blockNum -> blockNum == 5 ? b5 : b6));

      assertTrue(ex.getMessage().contains("parent hash mismatch"));
      assertFalse(index.getBlockRange(5).isPresent());
      assertThrows(ArchiveException.class, restarted::validateAvailable);
    } finally {
      restarted.close();
    }
  }

  @Test
  public void startupReconcileRollsBackInFlightPastCanonicalHead() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    InMemoryArchiveInFlightStore inFlightStore = new InMemoryArchiveInFlightStore();
    BlockCapsule stale = blockWithParentSeed(5, (byte) 5);
    DefaultArchiveService service = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), temporal, inFlightStore);
    commitEmptyBlock(service, stale);
    service.close();

    DefaultArchiveService restarted = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), temporal, inFlightStore);
    try {
      ArchiveException error = assertThrows(ArchiveException.class,
          () -> restarted.reconcileInFlightOnStartup(4, 4, blockNum -> stale));
      assertTrue(error.getMessage().contains("has no journal or published range"));
      assertFalse(index.getBlockRange(5).isPresent());
      assertTrue(inFlightStore.loadBlocks().isEmpty());
      assertThrows(ArchiveException.class, restarted::validateAvailable);
    } finally {
      restarted.close();
    }
  }

  @Test
  public void startupReconcileRejectsCanonicalTailMissingInFlightJournal() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    InMemoryArchiveInFlightStore inFlightStore = new InMemoryArchiveInFlightStore();
    BlockCapsule b4 = blockWithParentSeed(4, (byte) 4);
    BlockCapsule b5 = blockWithParentSeed(5, (byte) 5);
    DefaultArchiveService service = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), temporal, inFlightStore);
    commitEmptyBlock(service, b4);
    service.publishSolidifiedBlocks(4);
    assertTrue(index.getBlockRange(4).isPresent());
    assertEquals(0, inFlightStore.loadBlocks().size());
    service.close();

    DefaultArchiveService restarted = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), temporal, inFlightStore);
    try {
      ArchiveException ex = assertThrows(ArchiveException.class,
          () -> restarted.reconcileInFlightOnStartup(4, 5, blockNum -> b5));

      assertTrue(ex.getMessage().contains("in-flight journal missing"));
      assertThrows(ArchiveException.class, restarted::validateAvailable);
    } finally {
      restarted.close();
    }
  }

  @Test
  public void startupReconcileRejectsEmptyArchiveAtMidChainActivation() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    InMemoryArchiveInFlightStore inFlightStore = new InMemoryArchiveInFlightStore();
    DefaultArchiveService service = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), temporal, inFlightStore);
    try {
      ArchiveException error = assertThrows(ArchiveException.class,
          () -> service.reconcileInFlightOnStartup(
              9, 10, blockNum -> blockWithParentSeed(blockNum, (byte) 1)));

      assertTrue(error.getMessage().contains("has no journal or published range"));
      assertThrows(ArchiveException.class, service::validateAvailable);
      assertEquals(-1L, index.getFirstArchivedBlock());
      assertEquals(-1L, index.getLastArchivedBlock());
      assertEquals(0, inFlightStore.loadBlocks().size());
    } finally {
      service.close();
    }
  }

  @Test
  public void closeClearsThreadLocalContextAndCaptureBuffer() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    DefaultArchiveService service = new DefaultArchiveService(true, index, context);
    BlockCapsule b = block(5);
    service.beginBlock(b, ArchiveSource.NORMAL);
    service.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    service.getCaptureEngine().capturePut("account", new byte[21], null, account(1));
    assertTrue(context.current().isPresent());
    assertTrue(ArchiveCaptureHolder.isCapturingCurrentTx());
    assertEquals(1, service.getCaptureEngine().records().size());

    service.close();

    assertFalse(context.current().isPresent());
    assertFalse(ArchiveCaptureHolder.isCapturingCurrentTx());
    assertEquals(0, service.getCaptureEngine().records().size());
  }

  @Test
  public void staleEndTxDoesNotClearNewerActiveTxContext() {
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    DefaultArchiveService older = new DefaultArchiveService(
        true, new InMemoryArchiveTxNumIndex(), context, new InMemoryArchiveTemporalStore());
    DefaultArchiveService newer = new DefaultArchiveService(
        true, new InMemoryArchiveTxNumIndex(), context, new InMemoryArchiveTemporalStore());
    BlockCapsule block = block(5);
    try {
      newer.beginBlock(block, ArchiveSource.NORMAL);
      newer.beginSystemTx(block, ArchivePhase.BLOCK_PREPARE);
      assertTrue(context.current().isPresent());

      older.endTx();

      assertTrue(context.current().isPresent());
      assertTrue(ArchiveCaptureHolder.isCapturingCurrentTx());
    } finally {
      older.close();
      newer.close();
    }
  }

  @Test
  public void staleAbortDoesNotClearNewerActiveTxContext() {
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    DefaultArchiveService older = new DefaultArchiveService(
        true, new InMemoryArchiveTxNumIndex(), context, new InMemoryArchiveTemporalStore());
    DefaultArchiveService newer = new DefaultArchiveService(
        true, new InMemoryArchiveTxNumIndex(), context, new InMemoryArchiveTemporalStore());
    BlockCapsule block = block(5);
    try {
      newer.beginBlock(block, ArchiveSource.NORMAL);
      newer.beginSystemTx(block, ArchivePhase.BLOCK_PREPARE);
      assertTrue(context.current().isPresent());

      assertThrows(ArchiveException.class, () -> older.abortBlock(block));

      assertTrue(context.current().isPresent());
      assertTrue(ArchiveCaptureHolder.isCapturingCurrentTx());
    } finally {
      older.close();
      newer.close();
    }
  }

  @Test
  public void disabledFactoryDoesNotClearLiveThreadLocalContext() {
    ArchiveExecutionContext context = ArchiveExecutionContextHolder.get();
    DefaultArchiveService service =
        new DefaultArchiveService(true, new InMemoryArchiveTxNumIndex(), context);
    BlockCapsule block = block(5);
    service.beginBlock(block, ArchiveSource.NORMAL);
    service.beginSystemTx(block, ArchivePhase.BLOCK_PREPARE);

    try {
      ArchiveServiceFactory.create(null);

      assertTrue(context.current().isPresent());
      assertTrue(ArchiveCaptureHolder.isCapturingCurrentTx());
      service.validateAvailable();
    } finally {
      service.close();
    }
  }

  @Test
  public void unwindNonHeadInFlightBlockLeavesJournalIntact() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    InMemoryArchiveInFlightStore inFlightStore = new InMemoryArchiveInFlightStore();
    DefaultArchiveService service = serviceWithInFlightStore(
        index, context, temporal, inFlightStore);
    BlockCapsule b5 = blockWithParentSeed(5, (byte) 5);
    BlockCapsule b6 = blockWithParentSeed(6, (byte) 6);
    commitEmptyBlock(service, b5);
    commitEmptyBlock(service, b6);
    assertEquals(2, inFlightStore.loadBlocks().size());

    ArchiveException ex = assertThrows(ArchiveException.class, () -> service.unwindBlock(b5));

    assertTrue(ex.getMessage().contains("not archive in-flight head"));
    assertEquals(2, inFlightStore.loadBlocks().size());
  }

  @Test
  public void sequentialUnwindOfInFlightHeadsRewindsAllocatorForReorg() throws Exception {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    InMemoryArchiveInFlightStore inFlightStore = new InMemoryArchiveInFlightStore();
    DefaultArchiveService service = serviceWithInFlightStore(
        index, context, temporal, inFlightStore);
    byte[] addr = new byte[21];
    addr[0] = 0x41;

    // Three unpublished in-flight blocks change A: b5 created->10, b6 10->20, b7 20->30.
    BlockCapsule b5 = blockWithParentSeed(5, (byte) 5);
    BlockCapsule b6 = blockWithParentSeed(6, (byte) 6);
    BlockCapsule b7 = blockWithParentSeed(7, (byte) 7);
    commitAccountChangeBlock(service, b5, addr, null, account(10));
    commitAccountChangeBlock(service, b6, addr, account(10), account(20));
    commitAccountChangeBlock(service, b7, addr, account(20), account(30));
    assertEquals(3, inFlightStore.loadBlocks().size());

    // Reorg: unwind b7 then b6 back-to-back (each is the in-flight head after the prior drop).
    service.unwindBlock(b7);
    service.unwindBlock(b6);
    assertEquals(1, inFlightStore.loadBlocks().size());

    // The execution txNum allocator rewound to b5's last txNum: a replacement b6' commits WITHOUT a
    // "non-contiguous in-flight txNum" error (would throw here if the allocator were stale).
    BlockCapsule b6prime = blockWithParentSeed(6, (byte) 0x60);
    commitAccountChangeBlock(service, b6prime, addr, account(10), account(40));
    service.publishSolidifiedBlocks(6);

    // Published latest reflects the replacement (40), not the reorged-away b7 value (30).
    assertEquals(40, Account.parseFrom(
        temporal.latest(ArchiveDomain.ACCOUNT, addr).orElseThrow(AssertionError::new).getValue())
        .getBalance());
    service.validateAvailable();
  }

  @Test
  public void commitCollapsesSameKeySameTxToFirstPrevLastNew() throws Exception {
    // Two captures of the same account within one user tx (10 -> 20 -> 30): the drain must keep the
    // FIRST prev (10, the true pre-tx value) for history and the LAST value (30) for latest, so a
    // read of the block before the tx returns 10 -- not the intermediate 20.
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    DefaultArchiveService service =
        new DefaultArchiveService(true, index, context, temporal);

    byte[] addr = new byte[21];
    addr[0] = 0x41;
    BlockCapsule b = block(5); // no user transactions
    service.beginBlock(b, ArchiveSource.NORMAL);
    service.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    service.endTx();

    // Two captures of the same account within ONE (system) tx: 10 -> 20 -> 30.
    service.beginSystemTx(b, ArchivePhase.BLOCK_FINALIZE);
    long userTxNum = context.current().orElseThrow(AssertionError::new).getTxNum();
    service.getCaptureEngine().capturePut("account", addr, account(10), account(20));
    service.getCaptureEngine().capturePut("account", addr, account(20), account(30));
    service.endTx();
    service.commitBlock(b);
    service.publishSolidifiedBlocks(5);

    // exactly one history entry for the key (the two captures collapsed into one change).
    assertEquals(1, temporal.changeCount());
    // value at the end of (userTxNum - 1) = the first captured prev (10), not the intermediate 20.
    assertEquals(10, balanceOf(
        temporal.getAsOf(ArchiveDomain.ACCOUNT, addr, userTxNum - 1).get().getValue()));
    // latest = the last captured value (30).
    assertEquals(30, balanceOf(temporal.latest(ArchiveDomain.ACCOUNT, addr).get().getValue()));
    // value at the end of the tx itself falls through to latest (30).
    assertEquals(30, balanceOf(
        temporal.getAsOf(ArchiveDomain.ACCOUNT, addr, userTxNum).get().getValue()));
  }

  @Test
  public void commitRejectsBrokenWithinTxPrevValueChain() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    DefaultArchiveService service =
        new DefaultArchiveService(true, index, context, temporal);

    byte[] addr = new byte[21];
    addr[0] = 0x41;
    BlockCapsule b = block(5);
    service.beginBlock(b, ArchiveSource.NORMAL);
    service.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    service.endTx();
    service.beginSystemTx(b, ArchivePhase.BLOCK_FINALIZE);
    long txNum = context.current().orElseThrow(AssertionError::new).getTxNum();
    service.getCaptureEngine().capturePut("account", addr, account(10), account(20));
    service.getCaptureEngine().capturePut("account", addr, account(99), account(30));
    service.endTx();

    ArchiveException ex = assertThrows(ArchiveException.class, () -> service.commitBlock(b));

    assertTrue(ex.getMessage().contains("prev-value chain mismatch"));
    assertTrue(ex.getMessage().contains(String.valueOf(txNum)));
    assertThrows(ArchiveException.class, service::validateAvailable);
    assertFalse(index.getBlockRange(5).isPresent());
    assertEquals(0, temporal.changeCount());
  }

  @Test
  public void sameValueWritesSeedTemporalLatestForMidChainCoverage() throws Exception {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    DefaultArchiveService service =
        new DefaultArchiveService(true, index, context, temporal);

    byte[] addr = new byte[21];
    addr[0] = 0x41;
    BlockCapsule b = block(5);
    service.beginBlock(b, ArchiveSource.NORMAL);
    service.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    service.getCaptureEngine().capturePut("account", addr, account(10), account(10));
    service.endTx();
    service.beginSystemTx(b, ArchivePhase.BLOCK_FINALIZE);
    service.endTx();

    service.commitBlock(b);
    assertFalse(index.getBlockRange(5).isPresent());
    assertEquals(0, temporal.changeCount());
    service.publishSolidifiedBlocks(5);

    assertTrue(index.getBlockRange(5).isPresent());
    assertEquals(1, temporal.changeCount());
    assertEquals(10, balanceOf(temporal.latest(ArchiveDomain.ACCOUNT, addr).get().getValue()));
    assertEquals(10, balanceOf(
        temporal.getAsOf(ArchiveDomain.ACCOUNT, addr, Long.MAX_VALUE).get().getValue()));

    BlockCapsule b2 = block(6);
    service.beginBlock(b2, ArchiveSource.NORMAL);
    service.beginSystemTx(b2, ArchivePhase.BLOCK_PREPARE);
    service.getCaptureEngine().capturePut("account", addr, account(10), account(10));
    service.endTx();
    service.beginSystemTx(b2, ArchivePhase.BLOCK_FINALIZE);
    service.endTx();
    service.commitBlock(b2);
    service.publishSolidifiedBlocks(6);

    assertTrue(index.getBlockRange(6).isPresent());
    assertEquals(2, temporal.changeCount());
  }

  @Test
  public void publishSolidifiedBlocksPublishesOnlyThroughSolidNum() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    DefaultArchiveService service = new DefaultArchiveService(true, index, context);

    BlockCapsule b5 = blockWithParentSeed(5, (byte) 5);
    commitEmptyBlock(service, b5);
    BlockCapsule b6 = blockWithParentSeed(6, (byte) 6);
    commitEmptyBlock(service, b6);
    BlockCapsule b7 = blockWithParentSeed(7, (byte) 7);
    commitEmptyBlock(service, b7);

    service.publishSolidifiedBlocks(6);

    assertTrue(index.getBlockRange(5).isPresent());
    assertTrue(index.getBlockRange(6).isPresent());
    assertFalse(index.getBlockRange(7).isPresent());
  }

  @Test
  public void publishDiscardsSolidifiedExecutionRowsAndRetainsInFlightTail() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    DefaultArchiveService service = new DefaultArchiveService(
        true, index, new ArchiveExecutionContext());
    BlockCapsule b5 = blockWithParentSeed(5, (byte) 5);
    BlockCapsule b6 = blockWithParentSeed(6, (byte) 6);
    BlockCapsule b7 = blockWithParentSeed(7, (byte) 7);
    commitEmptyBlock(service, b5);
    commitEmptyBlock(service, b6);
    commitEmptyBlock(service, b7);

    service.publishSolidifiedBlocks(6);

    InMemoryArchiveTxNumIndex execution =
        ReflectUtils.getFieldValue(service, "executionTxNumIndex");
    assertFalse(execution.getBlockRange(5).isPresent());
    assertFalse(execution.getBlockRange(6).isPresent());
    assertTrue(execution.getBlockRange(7).isPresent());

    service.unwindBlock(b7);
    BlockCapsule replacement = blockWithParentSeed(7, (byte) 0x70);
    commitEmptyBlock(service, replacement);
    service.publishSolidifiedBlocks(7);
    assertTrue(index.getBlockRange(7).isPresent());
  }

  @Test
  public void deleteMissingKeySeedsKnownAbsentTemporalTombstone() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    DefaultArchiveService service =
        new DefaultArchiveService(true, index, context, temporal);

    byte[] addr = new byte[21];
    addr[0] = 0x41;
    BlockCapsule b = block(5);
    service.beginBlock(b, ArchiveSource.NORMAL);
    service.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    service.getCaptureEngine().captureDelete("account", addr, null);
    service.endTx();
    service.beginSystemTx(b, ArchivePhase.BLOCK_FINALIZE);
    service.endTx();

    service.commitBlock(b);
    service.publishSolidifiedBlocks(5);

    assertTrue(index.getBlockRange(5).isPresent());
    assertEquals(1, temporal.changeCount());
    assertTrue(temporal.latest(ArchiveDomain.ACCOUNT, addr).isPresent());
    assertTrue(temporal.latest(ArchiveDomain.ACCOUNT, addr).get().isDeleted());
  }

  @Test
  public void unwindUnpublishedHeadDropsOnlyInFlightState() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    DefaultArchiveService service =
        new DefaultArchiveService(true, index, context, temporal);

    byte[] addr = new byte[21];
    addr[0] = 0x41;
    BlockCapsule b = blockWithParentSeed(5, (byte) 5);
    service.beginBlock(b, ArchiveSource.NORMAL);
    service.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    service.getCaptureEngine().capturePut("account", addr, null, account(1));
    service.endTx();
    service.beginSystemTx(b, ArchivePhase.BLOCK_FINALIZE);
    service.endTx();
    service.commitBlock(b);

    service.unwindBlock(b);
    service.publishSolidifiedBlocks(5);

    assertFalse(index.getBlockRange(5).isPresent());
    assertEquals(0, temporal.changeCount());
  }

  @Test
  public void abortClearsContextAndDiscardsPending() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    DefaultArchiveService service = new DefaultArchiveService(true, index, context);

    BlockCapsule b = block(5);
    service.beginBlock(b, ArchiveSource.NORMAL);
    service.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    assertTrue(context.current().isPresent());

    service.abortBlock(b);
    assertFalse(context.current().isPresent());
    assertFalse(index.getBlockRange(5).isPresent());
  }

  @Test
  public void commitFailsClosedWhenCaptureFailureWasRecorded() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    DefaultArchiveService service =
        new DefaultArchiveService(true, index, context, temporal);

    BlockCapsule b = block(5);
    service.beginBlock(b, ArchiveSource.NORMAL);
    service.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    service.endTx();
    service.beginSystemTx(b, ArchivePhase.BLOCK_FINALIZE);
    ArchiveCaptureHolder.capturePut("account", new byte[21], null, new byte[] {(byte) 0xff});
    service.endTx();

    assertThrows(ArchiveException.class, () -> service.commitBlock(b));
    assertThrows(ArchiveException.class, service::validateAvailable);
    assertThrows(ArchiveException.class, () -> service.beginBlock(block(6), ArchiveSource.NORMAL));
    assertFalse(index.getBlockRange(5).isPresent());
    assertEquals(0, temporal.changeCount());
  }

  @Test
  public void temporalBatchFailureDuringPublishUnwindsCommittedIndex() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    DefaultArchiveService service =
        new DefaultArchiveService(true, index, context, new FailingTemporalStore());

    byte[] addr = new byte[21];
    addr[0] = 0x41;
    BlockCapsule b = block(5);
    service.beginBlock(b, ArchiveSource.NORMAL);
    service.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    service.getCaptureEngine().capturePut("account", addr, account(1), account(2));
    service.endTx();
    service.beginSystemTx(b, ArchivePhase.BLOCK_FINALIZE);
    service.endTx();

    service.commitBlock(b);
    assertFalse(index.getBlockRange(5).isPresent());

    assertThrows(ArchiveException.class, () -> service.publishSolidifiedBlocks(5));
    assertThrows(ArchiveException.class, service::validateAvailable);
    assertThrows(ArchiveException.class, () -> service.beginBlock(block(6), ArchiveSource.NORMAL));
    assertFalse(index.getBlockRange(5).isPresent());
  }

  @Test
  public void inFlightDeleteFailureAfterDurablePublishIsRecoveredOnRestart() {
    TrackingArchiveTxNumIndex index = new TrackingArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    DeleteFailingArchiveInFlightStore inFlightStore = new DeleteFailingArchiveInFlightStore();
    DefaultArchiveService service =
        serviceWithInFlightStore(index, context, temporal, inFlightStore);

    byte[] addr = new byte[21];
    addr[0] = 0x41;
    BlockCapsule b = block(5);
    service.beginBlock(b, ArchiveSource.NORMAL);
    service.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    service.getCaptureEngine().capturePut("account", addr, account(1), account(2));
    service.endTx();
    service.beginSystemTx(b, ArchivePhase.BLOCK_FINALIZE);
    service.endTx();
    service.commitBlock(b);

    service.publishSolidifiedBlocks(5);

    assertTrue(index.getBlockRange(5).isPresent());
    assertEquals("", index.repairReason);
    service.validateAvailable();
    assertEquals(1, inFlightStore.loadBlocks().size());
    service.close();

    DefaultArchiveService failedCleanup = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), temporal, inFlightStore);
    try {
      assertEquals(1, inFlightStore.loadBlocks().size());
      assertThrows(ArchiveException.class,
          () -> failedCleanup.reconcileInFlightOnStartup(5L, 5L, ignored -> b));
      assertEquals("", index.repairReason);
      assertEquals(1, inFlightStore.loadBlocks().size());
    } finally {
      failedCleanup.close();
    }

    inFlightStore.failDelete = false;
    DefaultArchiveService cleanupRestart = serviceWithInFlightStore(
        index, new ArchiveExecutionContext(), temporal, inFlightStore);
    try {
      assertEquals(1, inFlightStore.loadBlocks().size());
      cleanupRestart.reconcileInFlightOnStartup(5L, 5L, ignored -> b);
      assertEquals(0, inFlightStore.loadBlocks().size());
      cleanupRestart.validateAvailable();
    } finally {
      cleanupRestart.close();
    }
  }

  @Test
  public void publishedUnwindMarksArchiveUnavailable() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    DefaultArchiveService service =
        new DefaultArchiveService(true, index, context, new InMemoryArchiveTemporalStore());

    BlockCapsule b = block(5);
    service.beginBlock(b, ArchiveSource.NORMAL);
    service.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    service.endTx();
    service.beginSystemTx(b, ArchivePhase.BLOCK_FINALIZE);
    service.endTx();
    service.commitBlock(b);
    service.publishSolidifiedBlocks(5);

    assertThrows(ArchiveException.class, () -> service.unwindBlock(b));
    assertThrows(ArchiveException.class, service::validateAvailable);
    assertThrows(ArchiveException.class, () -> service.beginBlock(block(6), ArchiveSource.NORMAL));
  }

  @Test
  public void unwindRejectsSameHeightDifferentHashAndFailsClosed() throws Exception {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    DefaultArchiveService service =
        new DefaultArchiveService(true, index, context, temporal);

    byte[] addr = new byte[21];
    addr[0] = 0x41;
    BlockCapsule b = block(5);
    service.beginBlock(b, ArchiveSource.NORMAL);
    service.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    service.getCaptureEngine().capturePut("account", addr, null, account(1));
    service.endTx();
    service.beginSystemTx(b, ArchivePhase.BLOCK_FINALIZE);
    service.endTx();
    service.commitBlock(b);

    assertThrows(ArchiveException.class,
        () -> service.unwindBlock(blockWithParentSeed(5, (byte) 1)));

    assertFalse(index.getBlockRange(5).isPresent());
    assertEquals(0, temporal.changeCount());
    assertThrows(ArchiveException.class, service::validateAvailable);
  }

  @Test
  public void nestedReadGuardDoubleCloseDoesNotReleaseSiblingHold() throws Exception {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    DefaultArchiveService service = new DefaultArchiveService(true, index, context);
    ArchiveService.ReadGuard first = null;
    ArchiveService.ReadGuard second = null;
    try {
      BlockCapsule b = block(5);
      commitEmptyBlock(service, b);
      first = service.acquireReadGuard();
      second = service.acquireReadGuard();
      ArchiveLifecycle lifecycle = ReflectUtils.getFieldValue(service, "lifecycle");
      assertEquals(2L, lifecycle.getActiveCount(ArchiveLifecycle.WorkType.QUERY));

      first.close();
      first.close();
      assertEquals(1L, lifecycle.getActiveCount(ArchiveLifecycle.WorkType.QUERY));

      CountDownLatch started = new CountDownLatch(1);
      FutureTask<Void> publish = new FutureTask<>(() -> {
        started.countDown();
        service.publishSolidifiedBlocks(5);
        return null;
      });
      Thread thread = new Thread(publish);
      thread.start();

      assertTrue(started.await(1, TimeUnit.SECONDS));
      Thread.sleep(100L);
      assertFalse(index.getBlockRange(5).isPresent());
      second.close();
      publish.get(1, TimeUnit.SECONDS);
      assertTrue(index.getBlockRange(5).isPresent());
      assertEquals(0L, lifecycle.getActiveCount(ArchiveLifecycle.WorkType.QUERY));
    } finally {
      if (second != null) {
        second.close();
      }
      if (first != null) {
        first.close();
      }
      service.close();
    }
  }

  @Test
  public void closeTimesOutForActiveReadGuardThenCompletesAfterRelease() {
    DefaultArchiveService service = new DefaultArchiveService(
        true, new InMemoryArchiveTxNumIndex(), new ArchiveExecutionContext());
    service.setCloseDrainTimeoutForTest(25L, TimeUnit.MILLISECONDS);
    ArchiveService.ReadGuard guard = service.acquireReadGuard();
    boolean guardReleased = false;
    try {
      long startedNanos = System.nanoTime();
      ArchiveException failure = assertThrows(ArchiveException.class, service::close);
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);

      assertTrue(failure.getMessage().contains("drain timed out with active operations"));
      assertTrue("close exceeded its bounded drain timeout", elapsedMillis < 1_000L);
      ArchiveLifecycle lifecycle = ReflectUtils.getFieldValue(service, "lifecycle");
      assertEquals(ArchiveLifecycle.Phase.DRAINING, lifecycle.getPhase());
      ArchiveException admissionFailure = assertThrows(
          ArchiveException.class, service::acquireReadGuard);
      assertTrue(admissionFailure.getMessage().contains("DRAINING"));

      guard.close();
      guardReleased = true;
      service.close();
      assertEquals(ArchiveLifecycle.Phase.CLOSED, lifecycle.getPhase());
    } finally {
      if (!guardReleased) {
        guard.close();
      }
      service.close();
    }
  }

  @Test
  public void fatalCasLoserArmsPrimaryWithoutCreatingSuppressionCycle() {
    DefaultArchiveService service = new DefaultArchiveService(
        true, new InMemoryArchiveTxNumIndex(), new ArchiveExecutionContext());
    try {
      ArchiveLifecycle lifecycle = ReflectUtils.getFieldValue(service, "lifecycle");
      ArchiveFatalController fatalController =
          ReflectUtils.getFieldValue(service, "fatalController");
      ArchiveException primary = new ArchiveException("primary");
      assertTrue(lifecycle.markFatal(primary));

      ArchiveException wrapsPrimary = new ArchiveException("secondary", primary);
      invokeMarkFatal(service, wrapsPrimary);
      assertSame(primary, fatalController.getFailure());
      assertEquals(0, primary.getSuppressed().length);

      ArchiveException independent = new ArchiveException("independent");
      invokeMarkFatal(service, independent);
      invokeMarkFatal(service, independent);
      assertSame(primary, fatalController.getFailure());
      assertEquals(1, primary.getSuppressed().length);
      assertSame(independent, primary.getSuppressed()[0]);
    } finally {
      service.close();
    }
  }

  @Test
  public void preCoverageUnwindIsNoop() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    DefaultArchiveService service = new DefaultArchiveService(true, index, context);

    service.unwindBlock(block(100));
    assertFalse(index.getBlockRange(100).isPresent());
    assertFalse(context.current().isPresent());
  }

  @Test
  public void enabledServiceValidatesCanonicalHead() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    DefaultArchiveService service = new DefaultArchiveService(true, index, context);

    BlockCapsule b = block(5);
    service.beginBlock(b, ArchiveSource.NORMAL);
    service.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    service.endTx();
    service.beginSystemTx(b, ArchivePhase.BLOCK_FINALIZE);
    service.endTx();
    service.commitBlock(b);
    service.publishSolidifiedBlocks(5);

    service.validateCanonicalHead(b);
    assertThrows(ArchiveException.class, () -> service.validateCanonicalHead(block(6)));
  }

  @Test
  public void enabledServiceAllowsEmptyArchiveCanonicalHead() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    DefaultArchiveService service = new DefaultArchiveService(true, index, context);

    service.validateCanonicalHead(block(5));
  }

  @Test
  public void txNumCommitFailureClearsPendingContextAndCaptureBuffer() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    DefaultArchiveService service =
        new DefaultArchiveService(true, index, context, temporal);

    byte[] addr = new byte[21];
    addr[0] = 0x41;
    BlockCapsule b = block(5);
    service.beginBlock(b, ArchiveSource.NORMAL);
    service.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    service.getCaptureEngine().capturePut("account", addr, null, account(1));

    ArchiveException ex = assertThrows(ArchiveException.class, () -> service.commitBlock(b));
    assertTrue(ex.getMessage().contains("requires both prepare and finalize"));
    assertFalse(context.current().isPresent());
    assertTrue(service.getCaptureEngine().records().isEmpty());
    assertFalse(index.getBlockRange(5).isPresent());
    assertEquals(0, temporal.changeCount());

    assertThrows(ArchiveException.class, service::validateAvailable);
    assertThrows(ArchiveException.class, () -> service.beginBlock(block(6), ArchiveSource.NORMAL));
  }

  @Test
  public void ownerDriftDuringCommitMarksRepairAndAbortsPendingBlock() {
    TrackingArchiveTxNumIndex index = new TrackingArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    DefaultArchiveService older =
        new DefaultArchiveService(true, index, context, new InMemoryArchiveTemporalStore());

    byte[] addr = new byte[21];
    addr[0] = 0x41;
    BlockCapsule b = block(5);
    older.beginBlock(b, ArchiveSource.NORMAL);
    older.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    older.getCaptureEngine().capturePut("account", addr, null, account(1));
    older.endTx();
    older.beginSystemTx(b, ArchivePhase.BLOCK_FINALIZE);
    older.endTx();
    DefaultArchiveService newer = new DefaultArchiveService(true);
    try {
      ArchiveException ex = assertThrows(ArchiveException.class, () -> older.commitBlock(b));

      assertTrue(ex.getMessage().contains("capture engine is not active"));
      assertTrue(index.repairReason.contains("capture engine is not active"));
      assertFalse(index.getBlockRange(5).isPresent());
      assertFalse(context.current().isPresent());
      assertTrue(older.getCaptureEngine().records().isEmpty());
      assertThrows(ArchiveException.class, older::validateAvailable);
    } finally {
      newer.close();
    }
  }

  @Test
  public void ownerDriftDuringAbortMarksRepairAndClearsPendingBlock() {
    TrackingArchiveTxNumIndex index = new TrackingArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    DefaultArchiveService older =
        new DefaultArchiveService(true, index, context, new InMemoryArchiveTemporalStore());

    byte[] addr = new byte[21];
    addr[0] = 0x41;
    BlockCapsule b = block(5);
    older.beginBlock(b, ArchiveSource.NORMAL);
    older.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    older.getCaptureEngine().capturePut("account", addr, null, account(1));
    DefaultArchiveService newer = new DefaultArchiveService(true);
    try {
      ArchiveException ex = assertThrows(ArchiveException.class, () -> older.abortBlock(b));

      assertTrue(ex.getMessage().contains("capture engine is not active"));
      assertTrue(index.repairReason.contains("capture engine is not active"));
      assertFalse(index.getBlockRange(5).isPresent());
      assertTrue(context.current().isPresent());
      assertTrue(older.getCaptureEngine().records().isEmpty());
      index.beginBlock(6L, ArchiveSource.NORMAL);
      index.abortBlock(6L);
      assertThrows(ArchiveException.class, older::validateAvailable);
    } finally {
      newer.close();
    }
  }

  @Test
  public void ownerDriftDuringAbortDoesNotTouchPublishedIndex() {
    FailingAbortArchiveTxNumIndex index = new FailingAbortArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    DefaultArchiveService older =
        new DefaultArchiveService(true, index, context, new InMemoryArchiveTemporalStore());

    byte[] addr = new byte[21];
    addr[0] = 0x41;
    BlockCapsule b = block(5);
    older.beginBlock(b, ArchiveSource.NORMAL);
    older.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    older.getCaptureEngine().capturePut("account", addr, null, account(1));
    DefaultArchiveService newer = new DefaultArchiveService(true);
    try {
      ArchiveException ex = assertThrows(ArchiveException.class, () -> older.abortBlock(b));

      assertTrue(ex.getMessage().contains("capture engine is not active"));
      assertEquals(0, ex.getSuppressed().length);
      assertTrue(index.repairReason.contains("capture engine is not active"));
      assertTrue(context.current().isPresent());
      assertTrue(older.getCaptureEngine().records().isEmpty());
    } finally {
      newer.close();
    }
  }

  @Test
  public void ownerDriftDuringUnwindMarksRepairWithoutDroppingArchiveRange() {
    TrackingArchiveTxNumIndex index = new TrackingArchiveTxNumIndex();
    DefaultArchiveService older =
        new DefaultArchiveService(true, index, new ArchiveExecutionContext(),
            new InMemoryArchiveTemporalStore());

    BlockCapsule b = block(5);
    older.beginBlock(b, ArchiveSource.NORMAL);
    older.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    older.endTx();
    older.beginSystemTx(b, ArchivePhase.BLOCK_FINALIZE);
    older.endTx();
    older.commitBlock(b);
    older.publishSolidifiedBlocks(5);
    DefaultArchiveService newer = new DefaultArchiveService(true);
    try {
      ArchiveException ex = assertThrows(ArchiveException.class, () -> older.unwindBlock(b));

      assertTrue(ex.getMessage().contains("capture engine is not active"));
      assertTrue(index.repairReason.contains("capture engine is not active"));
      assertTrue(index.getBlockRange(5).isPresent());
      assertThrows(ArchiveException.class, older::validateAvailable);
    } finally {
      newer.close();
    }
  }

  private static final class FailingAbortArchiveTxNumIndex extends TrackingArchiveTxNumIndex {

    @Override
    public void abortBlock(long blockNum) {
      delegate.abortBlock(blockNum);
      throw new ArchiveException("abort cleanup failed");
    }
  }

  private static class TrackingArchiveTxNumIndex implements ArchiveTxNumIndex {
    protected final InMemoryArchiveTxNumIndex delegate = new InMemoryArchiveTxNumIndex();
    protected String repairReason = "";

    @Override
    public void beginBlock(long blockNum, ArchiveSource source) {
      delegate.beginBlock(blockNum, source);
    }

    @Override
    public ArchiveTxPosition allocateSystemTx(long blockNum, ArchivePhase phase) {
      return delegate.allocateSystemTx(blockNum, phase);
    }

    @Override
    public ArchiveTxPosition allocateUserTx(long blockNum, int txIndex, byte[] txId) {
      return delegate.allocateUserTx(blockNum, txIndex, txId);
    }

    @Override
    public ArchiveBlockRange commitBlock(long blockNum, int userTxCount) {
      return delegate.commitBlock(blockNum, userTxCount);
    }

    @Override
    public ArchiveBlockRange commitBlock(long blockNum, byte[] blockHash, int userTxCount,
        byte[] schemaChecksum) {
      return delegate.commitBlock(blockNum, blockHash, userTxCount, schemaChecksum);
    }

    @Override
    public void abortBlock(long blockNum) {
      delegate.abortBlock(blockNum);
    }

    @Override
    public void unwindBlock(long blockNum) {
      delegate.unwindBlock(blockNum);
    }

    @Override
    public void validateCanonicalHead(long headNum, byte[] headHash) {
      delegate.validateCanonicalHead(headNum, headHash);
    }

    @Override
    public ArchiveBlockRange getHeadBlockRange(long blockNum) {
      return delegate.getHeadBlockRange(blockNum);
    }

    @Override
    public Optional<ArchiveBlockRange> getBlockRange(long blockNum) {
      return delegate.getBlockRange(blockNum);
    }

    @Override
    public Optional<ArchiveTxPosition> getPosition(long txNum) {
      return delegate.getPosition(txNum);
    }

    @Override
    public OptionalLong findTxNumByBlockAndIndex(long blockNum, int txIndex) {
      return delegate.findTxNumByBlockAndIndex(blockNum, txIndex);
    }

    @Override
    public OptionalLong findTxNumByTxId(byte[] txId) {
      return delegate.findTxNumByTxId(txId);
    }

    @Override
    public long getNextTxNum() {
      return delegate.getNextTxNum();
    }

    @Override
    public long getLastArchivedBlock() {
      return delegate.getLastArchivedBlock();
    }

    @Override
    public void markRepairRequired(String reason) {
      repairReason = reason;
    }

    @Override
    public long getFirstArchivedBlock() {
      return delegate.getFirstArchivedBlock();
    }
  }

  private static final class FailingTemporalStore implements ArchiveTemporalStore {

    @Override
    public void putChange(ArchiveChangeRecord record) {
      throw new ArchiveException("boom");
    }

    @Override
    public void putChanges(List<ArchiveChangeRecord> records) {
      throw new ArchiveException("boom");
    }

    @Override
    public Optional<DomainValue> getAsOf(ArchiveDomain domain, byte[] canonicalKey, long txNum) {
      return Optional.empty();
    }

    @Override
    public Optional<DomainValue> latest(ArchiveDomain domain, byte[] canonicalKey) {
      return Optional.empty();
    }

    @Override
    public ArchiveTemporalReadView openReadView() {
      return ArchiveTemporalReadView.passThrough(this);
    }

    @Override
    public void unwind(long fromTxNum) {
    }
  }

  private static final class DeleteFailingArchiveInFlightStore implements ArchiveInFlightStore {

    private final InMemoryArchiveInFlightStore delegate = new InMemoryArchiveInFlightStore();
    private boolean failDelete = true;

    @Override
    public List<ArchiveInFlightBlock> loadBlocks() {
      return delegate.loadBlocks();
    }

    @Override
    public void putBlock(ArchiveInFlightBlock block) {
      delegate.putBlock(block);
    }

    @Override
    public void deleteBlock(long blockNum) {
      if (failDelete) {
        throw new ArchiveException("delete failed");
      }
      delegate.deleteBlock(blockNum);
    }
  }

  private static final class CountingCapacityInFlightStore implements ArchiveInFlightStore {

    private final InMemoryArchiveInFlightStore delegate = new InMemoryArchiveInFlightStore();
    private long usableSpace = Long.MAX_VALUE;
    private int capacityReads;
    private boolean failWrites;

    @Override
    public List<ArchiveInFlightBlock> loadBlocks() {
      return delegate.loadBlocks();
    }

    @Override
    public void forEachBlock(java.util.function.Consumer<ArchiveInFlightBlock> consumer) {
      delegate.forEachBlock(consumer);
    }

    @Override
    public void putBlock(ArchiveInFlightBlock block) {
      if (failWrites) {
        throw new ArchiveException("injected journal disk failure");
      }
      delegate.putBlock(block);
    }

    @Override
    public void acknowledgeBlock(ArchiveJournalToken token) {
      delegate.acknowledgeBlock(token);
    }

    @Override
    public void deleteBlock(long blockNum) {
      delegate.deleteBlock(blockNum);
    }

    @Override
    public long usableSpaceBytes() {
      capacityReads++;
      return usableSpace;
    }
  }

}
