package org.tron.core.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class ArchiveLifecycleTest {

  @Test
  public void recoveryParticipantActivatesRunningAtomically() {
    ArchiveLifecycle lifecycle = new ArchiveLifecycle(ArchiveLifecycle.Phase.RECOVERING);
    ArchiveWorkLease recovery = lifecycle.acquire(ArchiveLifecycle.WorkType.RECOVERY);
    assertThrows(ArchiveException.class,
        () -> lifecycle.acquire(ArchiveLifecycle.WorkType.RECOVERY));
    recovery.start();
    lifecycle.validateCurrentOperation();

    lifecycle.completeRecovery();
    recovery.close();

    assertEquals(ArchiveLifecycle.Phase.RUNNING, lifecycle.getPhase());
    assertEquals(0, lifecycle.getActiveCount(ArchiveLifecycle.WorkType.RECOVERY));
    try (ArchiveWorkLease writer = lifecycle.acquire(ArchiveLifecycle.WorkType.WRITER)) {
      writer.start();
      lifecycle.validateCurrentOperation();
    }
  }

  @Test
  public void drainCountsAcquiredButNotStartedReservation() throws Exception {
    ArchiveLifecycle lifecycle = new ArchiveLifecycle(ArchiveLifecycle.Phase.RUNNING);
    ArchiveWorkLease writer = lifecycle.acquire(ArchiveLifecycle.WorkType.WRITER);
    lifecycle.beginDrain();

    assertFalse(lifecycle.awaitDrained(10, TimeUnit.MILLISECONDS));
    assertThrows(ArchiveException.class, writer::start);
    writer.close();

    assertTrue(lifecycle.awaitDrained(1, TimeUnit.SECONDS));
    assertThrows(ArchiveException.class,
        () -> lifecycle.acquire(ArchiveLifecycle.WorkType.QUERY));
  }

  @Test
  public void startedLeaseMayFinishDuringDrain() throws Exception {
    ArchiveLifecycle lifecycle = new ArchiveLifecycle(ArchiveLifecycle.Phase.RUNNING);
    ArchiveWorkLease query = lifecycle.acquire(ArchiveLifecycle.WorkType.QUERY);
    query.start();
    lifecycle.beginDrain();

    lifecycle.validateCurrentOperation();
    assertFalse(lifecycle.awaitDrained(10, TimeUnit.MILLISECONDS));
    query.close();

    assertTrue(lifecycle.awaitDrained(1, TimeUnit.SECONDS));
    lifecycle.markClosed();
    assertEquals(ArchiveLifecycle.Phase.CLOSED, lifecycle.getPhase());
  }

  @Test
  public void recoveryCompletionCancelsCleanlyDuringDrain() throws Exception {
    ArchiveLifecycle lifecycle = new ArchiveLifecycle(ArchiveLifecycle.Phase.RECOVERING);
    ArchiveWorkLease recovery = lifecycle.acquire(ArchiveLifecycle.WorkType.RECOVERY);
    recovery.start();
    lifecycle.beginDrain();

    assertFalse(lifecycle.completeRecovery());
    recovery.close();

    assertEquals(ArchiveLifecycle.Phase.DRAINING, lifecycle.getPhase());
    assertEquals(0L, lifecycle.getActiveCount(ArchiveLifecycle.WorkType.RECOVERY));
    assertTrue(lifecycle.awaitDrained(1L, TimeUnit.SECONDS));
  }

  @Test
  public void admittedResponseCanSettleDuringNormalDrainButNotFatal() {
    ArchiveLifecycle lifecycle = new ArchiveLifecycle(ArchiveLifecycle.Phase.RUNNING);

    lifecycle.beginDrain();
    lifecycle.validateAdmittedResponse();

    lifecycle.markFatal(new ArchiveException("injected fatal"));
    ArchiveException failure = assertThrows(
        ArchiveException.class, lifecycle::validateAdmittedResponse);
    assertTrue(failure.getMessage().contains("fatal"));
  }

  @Test
  public void firstFatalFailureWinsAndClosesAdmission() {
    ArchiveLifecycle lifecycle = new ArchiveLifecycle(ArchiveLifecycle.Phase.RUNNING);
    ArchiveException first = new ArchiveException("first");
    ArchiveException second = new ArchiveException("second");

    assertTrue(lifecycle.markFatal(first));
    assertFalse(lifecycle.markFatal(second));

    assertSame(first, lifecycle.getFatalFailure());
    ArchiveException unavailable = assertThrows(ArchiveException.class,
        lifecycle::validateCurrentOperation);
    assertSame(first, unavailable.getCause());
    assertThrows(ArchiveException.class,
        () -> lifecycle.acquire(ArchiveLifecycle.WorkType.WRITER));
  }

  @Test
  public void fatalPublicationAbortsRecoveryActivationWithoutWaitingForCallback()
      throws Exception {
    ArchiveLifecycle lifecycle = new ArchiveLifecycle(ArchiveLifecycle.Phase.RECOVERING);
    ArchiveException fatal = new ArchiveException("injected fatal");
    CountDownLatch activationStarted = new CountDownLatch(1);
    CountDownLatch releaseActivation = new CountDownLatch(1);
    FutureTask<Throwable> recovery = new FutureTask<>(() -> {
      try (ArchiveWorkLease lease = lifecycle.acquire(ArchiveLifecycle.WorkType.RECOVERY)) {
        lease.start();
        lifecycle.completeRecovery(() -> {
          activationStarted.countDown();
          awaitUnchecked(releaseActivation);
        });
        return null;
      } catch (Throwable failure) {
        return failure;
      }
    });
    new Thread(recovery).start();
    assertTrue(activationStarted.await(1L, TimeUnit.SECONDS));

    FutureTask<Boolean> fatalTask = new FutureTask<>(() -> lifecycle.markFatal(fatal));
    new Thread(fatalTask).start();
    assertTrue(fatalTask.get(1L, TimeUnit.SECONDS));
    assertSame(fatal, lifecycle.getFatalFailure());
    assertFalse(recovery.isDone());

    FutureTask<Throwable> validation = new FutureTask<>(() -> {
      try {
        lifecycle.validateCurrentOperation();
        return null;
      } catch (Throwable failure) {
        return failure;
      }
    });
    new Thread(validation).start();
    assertTrue(validation.get(1L, TimeUnit.SECONDS) instanceof ArchiveException);
    releaseActivation.countDown();

    Throwable recoveryFailure = recovery.get(1L, TimeUnit.SECONDS);
    assertTrue(recoveryFailure instanceof ArchiveException);
    assertSame(fatal, recoveryFailure.getCause());
    assertEquals(ArchiveLifecycle.Phase.RECOVERING, lifecycle.getPhase());
    assertEquals(0L, lifecycle.getActiveCount(ArchiveLifecycle.WorkType.RECOVERY));
  }

  @Test
  public void drainRequestAndTimeoutDoNotBlockBehindRecoveryActivation() throws Exception {
    ArchiveLifecycle lifecycle = new ArchiveLifecycle(ArchiveLifecycle.Phase.RECOVERING);
    CountDownLatch activationStarted = new CountDownLatch(1);
    CountDownLatch releaseActivation = new CountDownLatch(1);
    AtomicReference<Boolean> completed = new AtomicReference<>();
    FutureTask<Throwable> recovery = new FutureTask<>(() -> {
      try (ArchiveWorkLease lease = lifecycle.acquire(ArchiveLifecycle.WorkType.RECOVERY)) {
        lease.start();
        completed.set(lifecycle.completeRecovery(() -> {
          activationStarted.countDown();
          awaitUnchecked(releaseActivation);
        }));
        return null;
      } catch (Throwable failure) {
        return failure;
      }
    });
    new Thread(recovery).start();
    assertTrue(activationStarted.await(1L, TimeUnit.SECONDS));

    lifecycle.beginDrain();
    assertEquals(ArchiveLifecycle.Phase.DRAINING, lifecycle.getPhase());
    assertFalse(lifecycle.awaitDrained(10L, TimeUnit.MILLISECONDS));

    releaseActivation.countDown();
    assertNull(recovery.get(1L, TimeUnit.SECONDS));
    assertTrue(completed.get());
    assertTrue(lifecycle.awaitDrained(1L, TimeUnit.SECONDS));
    assertEquals(ArchiveLifecycle.Phase.DRAINING, lifecycle.getPhase());
  }

  @Test
  public void nonOwnerCannotCloseLeaseOrReleaseReservation() throws Exception {
    ArchiveLifecycle lifecycle = new ArchiveLifecycle(ArchiveLifecycle.Phase.RUNNING);
    ArchiveWorkLease writer = lifecycle.acquire(ArchiveLifecycle.WorkType.WRITER);
    writer.start();
    CountDownLatch started = new CountDownLatch(1);
    FutureTask<Throwable> foreignClose = new FutureTask<>(() -> {
      started.countDown();
      return assertThrows(ArchiveException.class, writer::close);
    });
    Thread thread = new Thread(foreignClose);
    thread.start();

    assertTrue(started.await(1, TimeUnit.SECONDS));
    assertTrue(foreignClose.get(1, TimeUnit.SECONDS) instanceof ArchiveException);
    assertEquals(1, lifecycle.getActiveCount(ArchiveLifecycle.WorkType.WRITER));
    writer.close();
    assertEquals(0, lifecycle.getActiveCount(ArchiveLifecycle.WorkType.WRITER));
  }

  private static void awaitUnchecked(CountDownLatch latch) {
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
