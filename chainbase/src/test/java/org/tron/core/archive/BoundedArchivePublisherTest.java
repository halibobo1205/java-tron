package org.tron.core.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class BoundedArchivePublisherTest {

  @Test
  public void coalescesToHighestTargetWithoutCopyingBacklog() throws Exception {
    List<ArchivePublishTarget> calls = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch firstEntered = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CountDownLatch twoCalls = new CountDownLatch(2);
    BoundedArchivePublisher publisher = new BoundedArchivePublisher("archive-publisher-test",
        target -> {
          calls.add(target);
          twoCalls.countDown();
          if (calls.size() == 1) {
            firstEntered.countDown();
            await(releaseFirst);
          }
          return true;
        }, failure -> {
          throw new AssertionError(failure);
        });
    try {
      assertThrows(ArchiveException.class,
          () -> publisher.request(target(1, 1, 0)));
      publisher.activate();
      publisher.request(target(1, 1, 0));
      assertTrue(firstEntered.await(1, TimeUnit.SECONDS));
      publisher.request(target(2, 2, 0));
      publisher.request(target(9, 9, 0));
      publisher.request(target(7, 7, 0));
      releaseFirst.countDown();

      assertTrue(twoCalls.await(2, TimeUnit.SECONDS));
      assertTrue(publisher.awaitIdle(2, TimeUnit.SECONDS));
      assertEquals(2, calls.size());
      assertEquals(1, calls.get(0).getBlockNum());
      assertEquals(9, calls.get(1).getBlockNum());
      assertArrayEquals(hash(9), calls.get(1).getBlockHash());
    } finally {
      publisher.close();
    }
  }

  @Test
  public void newerEpochReplacesHigherOldEpochTarget() throws Exception {
    AtomicReference<ArchivePublishTarget> observed = new AtomicReference<>();
    CountDownLatch called = new CountDownLatch(1);
    BoundedArchivePublisher publisher = new BoundedArchivePublisher("archive-epoch-test",
        target -> {
          observed.set(target);
          called.countDown();
          return true;
        }, failure -> {
          throw new AssertionError(failure);
        });
    try {
      publisher.activate();
      publisher.request(target(100, 1, 1));
      publisher.request(target(5, 2, 2));
      assertTrue(called.await(2, TimeUnit.SECONDS));
      assertTrue(publisher.awaitIdle(2, TimeUnit.SECONDS));
      ArchivePublishTarget target = observed.get();
      assertEquals(2, target.getCanonicalEpoch());
      assertEquals(5, target.getBlockNum());
    } finally {
      publisher.close();
    }
  }

  @Test
  public void failureClosesAdmissionAndReportsExactlyOnce() throws Exception {
    AtomicReference<Throwable> reported = new AtomicReference<>();
    CountDownLatch failed = new CountDownLatch(1);
    ArchiveException expected = new ArchiveException("publish failed");
    BoundedArchivePublisher publisher = new BoundedArchivePublisher("archive-failure-test",
        target -> {
          throw expected;
        }, failure -> {
          reported.compareAndSet(null, failure);
          failed.countDown();
        });
    try {
      publisher.activate();
      publisher.request(target(1, 1, 0));
      assertTrue(failed.await(2, TimeUnit.SECONDS));
      assertEquals(expected, reported.get());
      assertEquals(BoundedArchivePublisher.State.FAILED, publisher.getState());
      assertThrows(ArchiveException.class,
          () -> publisher.request(target(2, 2, 0)));
    } finally {
      publisher.close();
    }
  }

  @Test
  public void fatalErrorStillClosesAdmissionAndSignalsFailureHandler() throws Exception {
    AtomicReference<Throwable> reported = new AtomicReference<>();
    CountDownLatch failed = new CountDownLatch(1);
    AssertionError fatal = new AssertionError("publisher error");
    BoundedArchivePublisher publisher = new BoundedArchivePublisher("archive-error-test",
        target -> {
          throw fatal;
        }, failure -> {
          reported.set(failure);
          failed.countDown();
        });
    try {
      publisher.activate();
      publisher.request(target(1, 1, 0));

      assertTrue(failed.await(2, TimeUnit.SECONDS));
      assertSame(fatal, reported.get());
      assertEquals(BoundedArchivePublisher.State.FAILED, publisher.getState());
    } finally {
      publisher.close();
    }
  }

  @Test
  public void conflictingHashAtSameHeightAndEpochIsRejected() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    BoundedArchivePublisher publisher = new BoundedArchivePublisher("archive-conflict-test",
        target -> {
          entered.countDown();
          await(release);
          return true;
        }, failure -> {
          throw new AssertionError(failure);
        });
    try {
      publisher.activate();
      publisher.request(target(7, 1, 3));
      assertTrue(entered.await(1, TimeUnit.SECONDS));

      ArchiveException failure = assertThrows(ArchiveException.class,
          () -> publisher.request(target(7, 2, 3)));
      assertTrue(failure.getMessage().contains("conflicting archive publish target"));
    } finally {
      release.countDown();
      publisher.close();
    }
  }

  @Test
  public void lateWriterTargetIsDroppedAfterDrain() {
    AtomicInteger executions = new AtomicInteger();
    BoundedArchivePublisher publisher = new BoundedArchivePublisher("archive-drain-test",
        target -> {
          executions.incrementAndGet();
          return true;
        }, failure -> {
          throw new AssertionError(failure);
        });
    publisher.activate();
    publisher.beginDrain();

    publisher.request(target(7, 1, 0));
    publisher.close();

    assertEquals(0, executions.get());
  }

  @Test
  public void recoveryActivationLosesCleanlyToDrain() {
    BoundedArchivePublisher publisher = new BoundedArchivePublisher(
        "archive-recovery-drain-test", target -> true, failure -> {
          throw new AssertionError(failure);
        });
    publisher.beginDrain();

    assertFalse(publisher.activateForRecovery());
    assertTrue(publisher.getState() == BoundedArchivePublisher.State.DRAINING
        || publisher.getState() == BoundedArchivePublisher.State.CLOSED);
    publisher.close();
  }

  @Test
  public void workerCannotBypassCloseBarrierByClosingItself() throws Exception {
    AtomicReference<BoundedArchivePublisher> reference = new AtomicReference<>();
    AtomicReference<Throwable> reported = new AtomicReference<>();
    CountDownLatch failed = new CountDownLatch(1);
    BoundedArchivePublisher publisher = new BoundedArchivePublisher(
        "archive-self-close-test", target -> {
          reference.get().close();
          return true;
        }, failure -> {
          reported.set(failure);
          failed.countDown();
        });
    reference.set(publisher);
    try {
      publisher.activate();
      publisher.request(target(1L, 1, 0L));

      assertTrue(failed.await(2L, TimeUnit.SECONDS));
      assertTrue(reported.get().getMessage().contains("worker thread"));
      assertEquals(BoundedArchivePublisher.State.FAILED, publisher.getState());
    } finally {
      publisher.close();
    }
  }

  private static ArchivePublishTarget target(long blockNum, int hashSeed, long epoch) {
    return new ArchivePublishTarget(blockNum, hash(hashSeed), epoch);
  }

  private static byte[] hash(int seed) {
    byte[] hash = new byte[32];
    hash[hash.length - 1] = (byte) seed;
    return hash;
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ArchiveException("test interrupted", e);
    }
  }
}
