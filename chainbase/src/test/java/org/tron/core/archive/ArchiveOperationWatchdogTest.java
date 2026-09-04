package org.tron.core.archive;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.tron.common.utils.ReflectUtils;

public class ArchiveOperationWatchdogTest {

  @Test
  public void timeoutSignalsFailStopHandler() throws Exception {
    CountDownLatch timedOut = new CountDownLatch(1);
    try (ArchiveOperationWatchdog watchdog = new ArchiveOperationWatchdog(
        "operation-watchdog-test", ignored -> timedOut::countDown)) {
      ArchiveOperationWatchdog.Scope scope = watchdog.arm(
          "injected native write", TimeUnit.MILLISECONDS.toNanos(20L));
      assertTrue(timedOut.await(1L, TimeUnit.SECONDS));
      ArchiveException failure = assertThrows(ArchiveException.class, scope::close);
      assertTrue(failure.getMessage().contains("injected native write"));
      assertThrows(ArchiveException.class, () -> watchdog.arm(
          "second native write", TimeUnit.SECONDS.toNanos(1L)));
    }
  }

  @Test
  public void completedOperationDisarmsTimeout() throws Exception {
    CountDownLatch timedOut = new CountDownLatch(1);
    try (ArchiveOperationWatchdog watchdog = new ArchiveOperationWatchdog(
        "operation-watchdog-disarm", ignored -> timedOut::countDown)) {
      try (ArchiveOperationWatchdog.Scope ignored = watchdog.arm(
          "completed native write", TimeUnit.SECONDS.toNanos(1L))) {
        // Operation completes inside the timeout.
      }
      assertFalse(timedOut.await(50L, TimeUnit.MILLISECONDS));
    }
  }

  @Test
  public void timeoutFailureIsPreparedBeforeTheGenerationCanExpire() {
    ArchiveOperationWatchdog watchdog = new ArchiveOperationWatchdog(
        "operation-watchdog-prepared-failure", ignored -> null);
    ArchiveOperationWatchdog.Scope scope = watchdog.arm(
        "prepared timeout", TimeUnit.SECONDS.toNanos(1L));
    try {
      RuntimeException prepared = ReflectUtils.getFieldValue(watchdog, "timeoutFailure");
      assertNotNull(prepared);
      assertTrue(prepared.getMessage().contains("prepared timeout"));

      scope.close();
      assertNull(ReflectUtils.getFieldValue(watchdog, "timeoutFailure"));
    } finally {
      scope.close();
      watchdog.close();
    }
  }

  @Test
  public void scopeCloseWaitsUntilTimeoutIsArmed() throws Exception {
    CountDownLatch armingEntered = new CountDownLatch(1);
    CountDownLatch releaseArming = new CountDownLatch(1);
    ArchiveOperationWatchdog watchdog = new ArchiveOperationWatchdog(
        "operation-watchdog-arm-barrier", failure -> {
          armingEntered.countDown();
          await(releaseArming);
          return () -> { };
        });
    CountDownLatch scopeArmed = new CountDownLatch(1);
    CountDownLatch attemptClose = new CountDownLatch(1);
    CountDownLatch closeEntered = new CountDownLatch(1);
    FutureTask<RuntimeException> closeResult = new FutureTask<>(() -> {
      ArchiveOperationWatchdog.Scope scope = watchdog.arm(
          "slow timeout arm", TimeUnit.MILLISECONDS.toNanos(20L));
      scopeArmed.countDown();
      await(attemptClose);
      closeEntered.countDown();
      try {
        scope.close();
        return null;
      } catch (RuntimeException failure) {
        return failure;
      }
    });
    Thread closer = new Thread(closeResult, "operation-watchdog-scope-closer");
    try {
      closer.start();
      assertTrue(scopeArmed.await(1L, TimeUnit.SECONDS));
      assertTrue(armingEntered.await(1L, TimeUnit.SECONDS));
      attemptClose.countDown();
      assertBlocked(closeEntered, closeResult, closer);

      releaseArming.countDown();
      RuntimeException failure = closeResult.get(1L, TimeUnit.SECONDS);
      assertTrue(failure instanceof ArchiveException);
      assertTrue(failure.getMessage().contains("slow timeout arm"));
    } finally {
      attemptClose.countDown();
      releaseArming.countDown();
      closer.join(1_000L);
      watchdog.close();
    }
  }

  @Test
  public void watchdogCloseWaitsUntilTimeoutIsArmed() throws Exception {
    CountDownLatch armingEntered = new CountDownLatch(1);
    CountDownLatch releaseArming = new CountDownLatch(1);
    ArchiveOperationWatchdog watchdog = new ArchiveOperationWatchdog(
        "operation-watchdog-close-barrier", failure -> {
          armingEntered.countDown();
          await(releaseArming);
          return () -> { };
        });
    watchdog.arm("close race", TimeUnit.MILLISECONDS.toNanos(20L));
    CountDownLatch closeEntered = new CountDownLatch(1);
    FutureTask<Void> close = new FutureTask<>(() -> {
      closeEntered.countDown();
      watchdog.close();
      return null;
    });
    Thread closer = new Thread(close, "operation-watchdog-closer");
    try {
      assertTrue(armingEntered.await(1L, TimeUnit.SECONDS));
      closer.start();
      assertBlocked(closeEntered, close, closer);

      releaseArming.countDown();
      close.get(1L, TimeUnit.SECONDS);
    } finally {
      releaseArming.countDown();
      closer.join(1_000L);
      watchdog.close();
    }
  }

  @Test
  public void watchdogCloseWaitsUntilTimeoutContinuationCompletes() throws Exception {
    CountDownLatch continuationEntered = new CountDownLatch(1);
    CountDownLatch releaseContinuation = new CountDownLatch(1);
    ArchiveOperationWatchdog watchdog = new ArchiveOperationWatchdog(
        "operation-watchdog-continuation-barrier", failure -> () -> {
          continuationEntered.countDown();
          await(releaseContinuation);
        });
    watchdog.arm("close continuation race", TimeUnit.MILLISECONDS.toNanos(20L));
    CountDownLatch closeEntered = new CountDownLatch(2);
    FutureTask<Void> close = new FutureTask<>(() -> {
      closeEntered.countDown();
      watchdog.close();
      return null;
    });
    FutureTask<Void> concurrentClose = new FutureTask<>(() -> {
      closeEntered.countDown();
      watchdog.close();
      return null;
    });
    Thread closer = new Thread(close, "operation-watchdog-continuation-closer");
    Thread concurrentCloser = new Thread(
        concurrentClose, "operation-watchdog-concurrent-closer");
    try {
      assertTrue(continuationEntered.await(1L, TimeUnit.SECONDS));
      closer.start();
      concurrentCloser.start();
      assertBlocked(closeEntered, close, closer);
      assertBlocked(closeEntered, concurrentClose, concurrentCloser);

      releaseContinuation.countDown();
      close.get(1L, TimeUnit.SECONDS);
      concurrentClose.get(1L, TimeUnit.SECONDS);
    } finally {
      releaseContinuation.countDown();
      closer.join(1_000L);
      concurrentCloser.join(1_000L);
      watchdog.close();
    }
  }

  @Test
  public void timeoutHandlerCannotSelfCloseWatchdog() throws Exception {
    AtomicReference<ArchiveOperationWatchdog> reference = new AtomicReference<>();
    AtomicReference<RuntimeException> rejection = new AtomicReference<>();
    CountDownLatch selfCloseRejected = new CountDownLatch(1);
    ArchiveOperationWatchdog watchdog = new ArchiveOperationWatchdog(
        "operation-watchdog-self-close", failure -> {
          try {
            reference.get().close();
          } catch (RuntimeException rejected) {
            rejection.set(rejected);
          } finally {
            selfCloseRejected.countDown();
          }
          return null;
        });
    reference.set(watchdog);
    ArchiveOperationWatchdog.Scope scope = watchdog.arm(
        "self-close timeout", TimeUnit.MILLISECONDS.toNanos(20L));
    try {
      assertTrue(selfCloseRejected.await(1L, TimeUnit.SECONDS));
      assertTrue(rejection.get() instanceof ArchiveException);
      assertTrue(rejection.get().getMessage().contains("worker thread"));
      assertThrows(ArchiveException.class, scope::close);
    } finally {
      watchdog.close();
    }
  }

  private static void assertBlocked(CountDownLatch entered, FutureTask<?> task, Thread thread)
      throws Exception {
    assertTrue(entered.await(1L, TimeUnit.SECONDS));
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1L);
    while (!task.isDone() && !isBlocked(thread.getState())
        && System.nanoTime() - deadline < 0L) {
      Thread.yield();
    }
    assertFalse(task.isDone());
    assertTrue(isBlocked(thread.getState()));
  }

  private static boolean isBlocked(Thread.State state) {
    return state == Thread.State.BLOCKED
        || state == Thread.State.WAITING
        || state == Thread.State.TIMED_WAITING;
  }

  private static void await(CountDownLatch latch) {
    boolean interrupted = false;
    while (true) {
      try {
        latch.await();
        break;
      } catch (InterruptedException e) {
        interrupted = true;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
