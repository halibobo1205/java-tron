package org.tron.core.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;
import org.junit.After;
import org.junit.Test;
import org.tron.common.utils.ReflectUtils;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.archive.capture.ArchiveCaptureHolder;
import org.tron.core.archive.domain.ArchiveDomainCatalog;
import org.tron.core.archive.domain.ArchiveDomainRegistry;
import org.tron.core.archive.domain.ArchiveSchemaChecksum;
import org.tron.core.archive.domain.DefaultArchiveDomainCatalog;
import org.tron.core.archive.domain.DefaultArchiveDomainRegistry;
import org.tron.core.archive.query.ArchiveQueryLimits;
import org.tron.core.archive.temporal.ArchiveTemporalStore;
import org.tron.core.archive.temporal.InMemoryArchiveTemporalStore;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.archive.txnum.ArchiveTxPosition;
import org.tron.core.archive.txnum.InMemoryArchiveTxNumIndex;
import org.tron.core.capsule.BlockCapsule;
import org.tron.protos.Protocol.Account;

public class DefaultArchiveServiceAsyncPublisherTest {

  @After
  public void clearCaptureHolder() {
    ArchiveCaptureHolder.clear();
  }

  @Test
  public void asyncRequestPublishesDurableJournalOneBlockAtATime() throws Exception {
    InMemoryArchiveTemporalStore temporal = spy(new InMemoryArchiveTemporalStore());
    CountDownLatch firstEntered = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CountDownLatch secondEntered = new CountDownLatch(1);
    CountDownLatch releaseSecond = new CountDownLatch(1);
    CountDownLatch thirdCompleted = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    AtomicInteger active = new AtomicInteger();
    AtomicInteger maxActive = new AtomicInteger();
    doAnswer(invocation -> {
      int call = calls.incrementAndGet();
      int activeNow = active.incrementAndGet();
      maxActive.accumulateAndGet(activeNow, Math::max);
      try {
        if (call == 1) {
          firstEntered.countDown();
          assertTrue(releaseFirst.await(5, TimeUnit.SECONDS));
        } else if (call == 2) {
          secondEntered.countDown();
          assertTrue(releaseSecond.await(5, TimeUnit.SECONDS));
        }
        Object result = invocation.callRealMethod();
        if (call == 3) {
          thirdCompleted.countDown();
        }
        return result;
      } finally {
        active.decrementAndGet();
      }
    }).when(temporal).putBlockChanges(any(), any());

    DefaultArchiveService service = service(8, 16, 1_000, temporal);
    BlockCapsule first = block(1);
    BlockCapsule second = block(2);
    BlockCapsule third = block(3);
    journalEmptyBlock(service, first);
    journalEmptyBlock(service, second);
    journalEmptyBlock(service, third);
    try {
      try (ArchiveMutationLease mutation = service.acquireMutationReadLease()) {
        service.requestPublishSolidifiedBlocks(3, third.getBlockId().getBytes());
      }

      assertTrue(firstEntered.await(2, TimeUnit.SECONDS));
      assertEquals(1, calls.get());
      releaseFirst.countDown();
      assertTrue(secondEntered.await(2, TimeUnit.SECONDS));
      assertEquals(2, calls.get());
      releaseSecond.countDown();

      assertTrue(thirdCompleted.await(2, TimeUnit.SECONDS));
      assertTrue(service.hasCommittedBlock(3));
      assertEquals(3, calls.get());
      assertEquals(1, maxActive.get());
    } finally {
      releaseFirst.countDown();
      releaseSecond.countDown();
      service.close();
    }
  }

  @Test
  public void blockedAsyncStorageWriteDoesNotBlockNextBlockJournal() throws Exception {
    CountDownLatch publishEntered = new CountDownLatch(1);
    CountDownLatch releasePublish = new CountDownLatch(1);
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    InMemoryArchiveInFlightStore inFlight = new InMemoryArchiveInFlightStore();
    UnifiedArchiveBackend unifiedBackend = mock(UnifiedArchiveBackend.class);
    when(unifiedBackend.publishBlock(
        any(ArchiveInFlightBlock.class), any(LongConsumer.class))).thenAnswer(invocation -> {
      ArchiveInFlightBlock journal = invocation.getArgument(0);
      publishEntered.countDown();
      assertTrue(releasePublish.await(5, TimeUnit.SECONDS));
      ArchiveBlockRange expected = journal.getRange();
      index.beginBlock(expected.getBlockNum(), expected.getSource());
      for (ArchiveTxPosition position : journal.getPositions()) {
        index.allocateSystemTx(position.getBlockNum(), position.getPhase());
      }
      ArchiveBlockRange published = index.commitBlock(
          expected.getBlockNum(), expected.getBlockHash(), expected.getUserTxCount(),
          expected.getSchemaChecksum());
      inFlight.deleteBlock(expected.getBlockNum());
      return published;
    });

    ArchivePublisherConfig publisherConfig =
        new ArchivePublisherConfig(true, true, 8, 16, 1_000);
    DefaultArchiveService service = new DefaultArchiveService(
        true, index, ArchiveExecutionContextHolder.get(),
        new InMemoryArchiveTemporalStore(), inFlight,
        new DefaultArchiveDomainRegistry(), new DefaultArchiveDomainCatalog(),
        ArchiveLifecycle.Phase.RUNNING,
        ArchiveQueryLimits.unlimited(), publisherConfig, () -> { }, unifiedBackend);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    BlockCapsule first = block(1);
    BlockCapsule second = block(2);
    journalEmptyBlock(service, first);
    try {
      try (ArchiveMutationLease mutation = service.acquireMutationReadLease()) {
        service.requestPublishSolidifiedBlocks(1, first.getBlockId().getBytes());
      }
      assertTrue(publishEntered.await(2, TimeUnit.SECONDS));

      Future<?> secondJournal = executor.submit(() -> journalEmptyBlock(service, second));
      secondJournal.get(2, TimeUnit.SECONDS);
      assertFalse(service.hasCommittedBlock(1));

      releasePublish.countDown();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (!service.hasCommittedBlock(1) && System.nanoTime() < deadline) {
        Thread.sleep(10L);
      }
      assertTrue(service.hasCommittedBlock(1));
    } finally {
      releasePublish.countDown();
      executor.shutdownNow();
      service.close();
    }
  }

  @Test
  public void stalledAsyncPublicationTransitionsArchiveToFatal() throws Exception {
    CountDownLatch publishEntered = new CountDownLatch(1);
    CountDownLatch releasePublish = new CountDownLatch(1);
    CountDownLatch fatalDelivered = new CountDownLatch(1);
    AtomicReference<RuntimeException> fatalFailure = new AtomicReference<>();
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    InMemoryArchiveInFlightStore inFlight = new InMemoryArchiveInFlightStore();
    UnifiedArchiveBackend unifiedBackend = mock(UnifiedArchiveBackend.class);
    when(unifiedBackend.publishBlock(
        any(ArchiveInFlightBlock.class), any(LongConsumer.class))).thenAnswer(invocation -> {
      ArchiveInFlightBlock journal = invocation.getArgument(0);
      publishEntered.countDown();
      assertTrue(releasePublish.await(5L, TimeUnit.SECONDS));
      return journal.getRange();
    });
    ArchivePublisherConfig publisherConfig = new ArchivePublisherConfig(
        true, true, 8, 16, 1024L * 1024L, 2L * 1024L * 1024L,
        1_000L, 2_000L, 0L, 0L, 1_000L, 50L);
    DefaultArchiveService service = new DefaultArchiveService(
        true, index, ArchiveExecutionContextHolder.get(),
        new InMemoryArchiveTemporalStore(), inFlight,
        new DefaultArchiveDomainRegistry(), new DefaultArchiveDomainCatalog(),
        ArchiveLifecycle.Phase.RUNNING,
        ArchiveQueryLimits.unlimited(), publisherConfig, () -> { }, unifiedBackend);
    service.setFatalFailureHandler(failure -> {
      fatalFailure.set(failure);
      fatalDelivered.countDown();
    });
    BlockCapsule first = block(1L);
    try {
      journalEmptyBlock(service, first);
      try (ArchiveMutationLease mutation = service.acquireMutationReadLease()) {
        service.requestPublishSolidifiedBlocks(1L, first.getBlockId().getBytes());
      }
      assertTrue(publishEntered.await(2L, TimeUnit.SECONDS));
      assertTrue(fatalDelivered.await(2L, TimeUnit.SECONDS));
      assertTrue(fatalFailure.get().getMessage().contains("fail-stop timeout"));
      assertThrows(ArchiveException.class, service::validateAvailable);
    } finally {
      releasePublish.countDown();
      service.close();
    }
  }

  @Test
  public void activeAsyncPublicationReservesTailJournalWorkspace() throws Exception {
    CountDownLatch publishEntered = new CountDownLatch(1);
    CountDownLatch releasePublish = new CountDownLatch(1);
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    InMemoryArchiveInFlightStore inFlight = new InMemoryArchiveInFlightStore();
    ArchiveDomainRegistry registry = new DefaultArchiveDomainRegistry();
    ArchiveDomainCatalog catalog = new DefaultArchiveDomainCatalog();
    byte[] schemaChecksum = ArchiveSchemaChecksum.of(registry, catalog);
    BlockCapsule first = block(1L);
    ArchiveInFlightBlock empty = emptyJournal(first, 0L, schemaChecksum);
    long publicationBytes = 4_096L;
    long cleanupBytes = 8L * 1024L + 3L * empty.encodedBlockBytes();
    long steadyWorkspaceBytes = Math.max(publicationBytes, cleanupBytes);
    long hardBytes = empty.estimatedRetainedBytes() * 2L + steadyWorkspaceBytes;
    UnifiedArchiveBackend unifiedBackend = mock(UnifiedArchiveBackend.class);
    when(unifiedBackend.estimatedPublicationRetainedBytes(any()))
        .thenReturn(publicationBytes);
    when(unifiedBackend.publishBlock(
        any(ArchiveInFlightBlock.class), any(LongConsumer.class))).thenAnswer(invocation -> {
      ArchiveInFlightBlock journal = invocation.getArgument(0);
      publishEntered.countDown();
      assertTrue(releasePublish.await(5, TimeUnit.SECONDS));
      ArchiveBlockRange expected = journal.getRange();
      index.beginBlock(expected.getBlockNum(), expected.getSource());
      for (ArchiveTxPosition position : journal.getPositions()) {
        index.allocateSystemTx(position.getBlockNum(), position.getPhase());
      }
      ArchiveBlockRange published = index.commitBlock(
          expected.getBlockNum(), expected.getBlockHash(), expected.getUserTxCount(),
          expected.getSchemaChecksum());
      inFlight.deleteBlock(expected.getBlockNum());
      return published;
    });
    ArchivePublisherConfig publisherConfig = new ArchivePublisherConfig(
        true, true, 8, 16, hardBytes, hardBytes,
        1_000L, 2_000L, 0L, 0L, 1_000L);
    DefaultArchiveService service = new DefaultArchiveService(
        true, index, ArchiveExecutionContextHolder.get(),
        new InMemoryArchiveTemporalStore(), inFlight, registry, catalog,
        ArchiveLifecycle.Phase.RUNNING,
        ArchiveQueryLimits.unlimited(), publisherConfig, () -> { }, unifiedBackend);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      journalEmptyBlock(service, first);
      try (ArchiveMutationLease mutation = service.acquireMutationReadLease()) {
        service.requestPublishSolidifiedBlocks(1L, first.getBlockId().getBytes());
      }
      assertTrue(publishEntered.await(2, TimeUnit.SECONDS));

      Future<?> secondJournal = executor.submit(
          () -> journalEmptyBlock(service, block(2L)));
      ExecutionException failure = assertThrows(
          ExecutionException.class, () -> secondJournal.get(2, TimeUnit.SECONDS));

      assertTrue(failure.getCause() instanceof ArchiveException);
      assertTrue(failure.getCause().getMessage().contains("hard resource watermark"));
      releasePublish.countDown();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
      while ((long) ReflectUtils.getFieldValue(service, "activePublicationBytes") != 0L
          && System.nanoTime() < deadline) {
        Thread.sleep(10L);
      }
      assertEquals(0L,
          (long) ReflectUtils.getFieldValue(service, "activePublicationBytes"));
    } finally {
      releasePublish.countDown();
      executor.shutdownNow();
      service.close();
    }
  }

  @Test
  public void stateAwareAsyncPublicationUpdatesTailJournalWorkspace() throws Exception {
    CountDownLatch publishEntered = new CountDownLatch(1);
    CountDownLatch releasePublish = new CountDownLatch(1);
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    InMemoryArchiveInFlightStore inFlight = new InMemoryArchiveInFlightStore();
    ArchiveDomainRegistry registry = new DefaultArchiveDomainRegistry();
    ArchiveDomainCatalog catalog = new DefaultArchiveDomainCatalog();
    byte[] schemaChecksum = ArchiveSchemaChecksum.of(registry, catalog);
    BlockCapsule first = block(1L);
    ArchiveInFlightBlock empty = emptyJournal(first, 0L, schemaChecksum);
    long publicationBytes = 4_096L;
    long cleanupBytes = 8L * 1024L + 3L * empty.encodedBlockBytes();
    long stateAwareBytes = Math.max(publicationBytes, cleanupBytes) + 4_096L;
    long hardBytes = empty.estimatedRetainedBytes() * 2L + stateAwareBytes;
    UnifiedArchiveBackend unifiedBackend = mock(UnifiedArchiveBackend.class);
    when(unifiedBackend.estimatedPublicationRetainedBytes(any()))
        .thenReturn(publicationBytes);
    when(unifiedBackend.publishBlock(
        any(ArchiveInFlightBlock.class), any(LongConsumer.class))).thenAnswer(invocation -> {
      ArchiveInFlightBlock journal = invocation.getArgument(0);
      LongConsumer observer = invocation.getArgument(1);
      observer.accept(stateAwareBytes);
      publishEntered.countDown();
      assertTrue(releasePublish.await(5, TimeUnit.SECONDS));
      ArchiveBlockRange expected = journal.getRange();
      index.beginBlock(expected.getBlockNum(), expected.getSource());
      for (ArchiveTxPosition position : journal.getPositions()) {
        index.allocateSystemTx(position.getBlockNum(), position.getPhase());
      }
      ArchiveBlockRange published = index.commitBlock(
          expected.getBlockNum(), expected.getBlockHash(), expected.getUserTxCount(),
          expected.getSchemaChecksum());
      inFlight.deleteBlock(expected.getBlockNum());
      return published;
    });
    ArchivePublisherConfig publisherConfig = new ArchivePublisherConfig(
        true, true, 8, 16, hardBytes, hardBytes,
        1_000L, 2_000L, 0L, 0L, 1_000L);
    DefaultArchiveService service = new DefaultArchiveService(
        true, index, ArchiveExecutionContextHolder.get(),
        new InMemoryArchiveTemporalStore(), inFlight, registry, catalog,
        ArchiveLifecycle.Phase.RUNNING,
        ArchiveQueryLimits.unlimited(), publisherConfig, () -> { }, unifiedBackend);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      journalEmptyBlock(service, first);
      try (ArchiveMutationLease mutation = service.acquireMutationReadLease()) {
        service.requestPublishSolidifiedBlocks(1L, first.getBlockId().getBytes());
      }
      assertTrue(publishEntered.await(2, TimeUnit.SECONDS));
      assertEquals(stateAwareBytes,
          (long) ReflectUtils.getFieldValue(service, "activePublicationBytes"));
      assertEquals(empty.estimatedRetainedBytes() + stateAwareBytes,
          (long) ReflectUtils.getFieldValue(service, "inFlightResourceBytes"));

      Future<?> secondJournal = executor.submit(
          () -> journalEmptyBlock(service, block(2L)));
      ExecutionException failure = assertThrows(
          ExecutionException.class, () -> secondJournal.get(2, TimeUnit.SECONDS));

      assertTrue(failure.getCause() instanceof ArchiveException);
      assertTrue(failure.getCause().getMessage().contains("hard resource watermark"));
      releasePublish.countDown();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
      while ((long) ReflectUtils.getFieldValue(service, "activePublicationBytes") != 0L
          && System.nanoTime() < deadline) {
        Thread.sleep(10L);
      }
      assertEquals(0L,
          (long) ReflectUtils.getFieldValue(service, "activePublicationBytes"));
    } finally {
      releasePublish.countDown();
      executor.shutdownNow();
      service.close();
    }
  }

  @Test
  public void stateAwarePublicationCannotOvercommitConcurrentCapture() throws Exception {
    CountDownLatch preflightEntered = new CountDownLatch(1);
    CountDownLatch releasePreflight = new CountDownLatch(1);
    CountDownLatch fatalDelivered = new CountDownLatch(1);
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    InMemoryArchiveInFlightStore inFlight = new InMemoryArchiveInFlightStore();
    ArchiveDomainRegistry registry = new DefaultArchiveDomainRegistry();
    ArchiveDomainCatalog catalog = new DefaultArchiveDomainCatalog();
    byte[] schemaChecksum = ArchiveSchemaChecksum.of(registry, catalog);
    BlockCapsule first = block(1L);
    ArchiveInFlightBlock empty = emptyJournal(first, 0L, schemaChecksum);
    byte[] account = Account.newBuilder().setBalance(7L).build().toByteArray();
    long captureBytes = ArchiveResourceEstimator.estimatedRawRecordPipelineBytes(
        21, 0, account.length);
    long publicationBytes = 4_096L;
    long cleanupBytes = 8L * 1024L + 3L * empty.encodedBlockBytes();
    long steadyWorkspaceBytes = Math.max(publicationBytes, cleanupBytes);
    long stateAwareBytes = steadyWorkspaceBytes + 4_096L;
    long hardBytes = empty.estimatedRetainedBytes() + stateAwareBytes + captureBytes - 1L;
    UnifiedArchiveBackend unifiedBackend = mock(UnifiedArchiveBackend.class);
    when(unifiedBackend.estimatedPublicationRetainedBytes(any()))
        .thenReturn(publicationBytes);
    when(unifiedBackend.publishBlock(
        any(ArchiveInFlightBlock.class), any(LongConsumer.class))).thenAnswer(invocation -> {
      LongConsumer observer = invocation.getArgument(1);
      preflightEntered.countDown();
      assertTrue(releasePreflight.await(5L, TimeUnit.SECONDS));
      observer.accept(stateAwareBytes);
      throw new AssertionError("state-aware publication admission unexpectedly succeeded");
    });
    ArchivePublisherConfig publisherConfig = new ArchivePublisherConfig(
        true, true, 8, 16, hardBytes, hardBytes,
        1_000L, 2_000L, 0L, 0L, 1_000L);
    DefaultArchiveService service = new DefaultArchiveService(
        true, index, ArchiveExecutionContextHolder.get(),
        new InMemoryArchiveTemporalStore(), inFlight, registry, catalog,
        ArchiveLifecycle.Phase.RUNNING,
        ArchiveQueryLimits.unlimited(), publisherConfig, () -> { }, unifiedBackend);
    service.setFatalFailureHandler(ignored -> fatalDelivered.countDown());
    BlockCapsule second = block(2L);
    try {
      journalEmptyBlock(service, first);
      try (ArchiveMutationLease mutation = service.acquireMutationReadLease()) {
        service.requestPublishSolidifiedBlocks(1L, first.getBlockId().getBytes());
      }
      assertTrue(preflightEntered.await(2L, TimeUnit.SECONDS));

      service.beginBlock(second, ArchiveSource.NORMAL);
      service.beginSystemTx(second, ArchivePhase.BLOCK_PREPARE);
      service.getCaptureEngineForTesting().capturePut(
          "account", new byte[21], null, account);
      assertEquals(captureBytes,
          (long) ReflectUtils.getFieldValue(service, "activeCaptureBytes"));

      releasePreflight.countDown();
      assertTrue(fatalDelivered.await(2L, TimeUnit.SECONDS));
      ArchiveLifecycle lifecycle = ReflectUtils.getFieldValue(service, "lifecycle");
      RuntimeException fatal = lifecycle.getFatalFailure();
      assertTrue(fatal.getMessage().contains(
          "state-aware publication would exceed hard resource watermark"));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
      while ((long) ReflectUtils.getFieldValue(service, "activePublicationBytes") != 0L
          && System.nanoTime() < deadline) {
        Thread.sleep(10L);
      }
      assertEquals(0L,
          (long) ReflectUtils.getFieldValue(service, "activePublicationBytes"));
    } finally {
      releasePreflight.countDown();
      service.endTx();
      service.close();
    }
  }

  @Test
  public void softWatermarkWaitsUntilPublisherDrainsBacklog() throws Exception {
    DefaultArchiveService service = service(1, 4, 2_000);
    BlockCapsule block = block(1);
    journalEmptyBlock(service, block);
    CountDownLatch waiterStarted = new CountDownLatch(1);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Future<?> capacity = executor.submit(() -> {
      waiterStarted.countDown();
      service.awaitWriterCapacity();
    });
    try {
      assertTrue(waiterStarted.await(1, TimeUnit.SECONDS));
      Thread.sleep(100L);
      assertFalse(capacity.isDone());

      try (ArchiveMutationLease mutation = service.acquireMutationReadLease()) {
        service.requestPublishSolidifiedBlocks(1, block.getBlockId().getBytes());
      }

      capacity.get(2, TimeUnit.SECONDS);
      assertTrue(service.hasCommittedBlock(1));
    } finally {
      executor.shutdownNow();
      service.close();
    }
  }

  @Test
  public void closeWakesWriterWaitingAtSoftWatermark() throws Exception {
    DefaultArchiveService service = service(1, 4, 10_000);
    journalEmptyBlock(service, block(1));
    CountDownLatch waiterStarted = new CountDownLatch(1);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Future<?> capacity = executor.submit(() -> {
      waiterStarted.countDown();
      service.awaitWriterCapacity();
    });
    try {
      assertTrue(waiterStarted.await(1, TimeUnit.SECONDS));
      Thread.sleep(100L);
      assertFalse(capacity.isDone());

      service.close();

      ExecutionException failure = assertThrows(ExecutionException.class,
          () -> capacity.get(1, TimeUnit.SECONDS));
      assertTrue(failure.getCause() instanceof ArchiveException);
      assertTrue(failure.getCause().getMessage().contains("DRAINING"));
    } finally {
      executor.shutdownNow();
      service.close();
    }
  }

  @Test
  public void hardWatermarkFailsBeforeNextWriterLease() {
    DefaultArchiveService service = service(1, 1, 0);
    journalEmptyBlock(service, block(1));

    ArchiveException failure = assertThrows(ArchiveException.class, service::awaitWriterCapacity);
    assertTrue(failure.getMessage().contains("hard watermark"));
    assertThrows(ArchiveException.class, service::acquireWriterLease);
    service.close();
  }

  private static DefaultArchiveService service(int softLimit, int hardLimit,
      long timeoutMs) {
    return service(softLimit, hardLimit, timeoutMs, new InMemoryArchiveTemporalStore());
  }

  private static DefaultArchiveService service(int softLimit, int hardLimit,
      long timeoutMs, ArchiveTemporalStore temporal) {
    return new DefaultArchiveService(true, new InMemoryArchiveTxNumIndex(),
        ArchiveExecutionContextHolder.get(), temporal,
        new InMemoryArchiveInFlightStore(), new DefaultArchiveDomainRegistry(),
        new DefaultArchiveDomainCatalog(),
        ArchiveLifecycle.Phase.RUNNING, ArchiveQueryLimits.unlimited(),
        new ArchivePublisherConfig(true, true, softLimit, hardLimit, timeoutMs), () -> {
        });
  }

  private static void journalEmptyBlock(DefaultArchiveService service, BlockCapsule block) {
    service.beginBlock(block, ArchiveSource.NORMAL);
    service.beginSystemTx(block, ArchivePhase.BLOCK_PREPARE);
    service.endTx();
    service.beginSystemTx(block, ArchivePhase.BLOCK_FINALIZE);
    service.endTx();
    service.commitBlock(block, 0);
  }

  private static ArchiveInFlightBlock emptyJournal(
      BlockCapsule block, long firstTxNum, byte[] schemaChecksum) {
    ArchiveTxPosition prepare = new ArchiveTxPosition(
        firstTxNum, block.getNum(), ArchivePhase.BLOCK_PREPARE,
        ArchiveSource.NORMAL, -1, null);
    ArchiveTxPosition finalize = new ArchiveTxPosition(
        firstTxNum + 1L, block.getNum(), ArchivePhase.BLOCK_FINALIZE,
        ArchiveSource.NORMAL, -1, null);
    ArchiveBlockRange range = new ArchiveBlockRange(
        block.getNum(), firstTxNum, firstTxNum + 1L, firstTxNum, firstTxNum + 1L,
        block.getBlockId().getBytes(), 0, ArchiveSource.NORMAL, schemaChecksum);
    return new ArchiveInFlightBlock(
        range, java.util.Arrays.asList(prepare, finalize), java.util.Collections.emptyList());
  }

  private static BlockCapsule block(long number) {
    return new BlockCapsule(number, Sha256Hash.ZERO_HASH, number, ByteString.EMPTY);
  }
}
