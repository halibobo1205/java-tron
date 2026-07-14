package org.tron.core.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import com.google.protobuf.ByteString;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.archive.capture.ArchiveCaptureHolder;
import org.tron.core.archive.domain.DefaultArchiveDomainCatalog;
import org.tron.core.archive.domain.DefaultArchiveDomainRegistry;
import org.tron.core.archive.query.ArchiveQueryLimits;
import org.tron.core.archive.reader.ArchiveReadThrough;
import org.tron.core.archive.temporal.ArchiveTemporalStore;
import org.tron.core.archive.temporal.InMemoryArchiveTemporalStore;
import org.tron.core.archive.txnum.InMemoryArchiveTxNumIndex;
import org.tron.core.capsule.BlockCapsule;

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
        return invocation.callRealMethod();
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

      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (!service.hasCommittedBlock(3) && System.nanoTime() < deadline) {
        Thread.sleep(5);
      }
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
        new DefaultArchiveDomainCatalog(), ArchiveReadThrough.NONE,
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

  private static BlockCapsule block(long number) {
    return new BlockCapsule(number, Sha256Hash.ZERO_HASH, number, ByteString.EMPTY);
  }
}
