package org.tron.core.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
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
}
