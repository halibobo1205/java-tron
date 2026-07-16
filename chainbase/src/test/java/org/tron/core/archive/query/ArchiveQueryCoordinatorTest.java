package org.tron.core.archive.query;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class ArchiveQueryCoordinatorTest {

  @Test
  public void retainedTraceMetricUsesBoundedProportionalSteps() {
    assertEquals(1, ArchiveQueryCoordinator.retainedTraceMetricStep(0));
    assertEquals(1, ArchiveQueryCoordinator.retainedTraceMetricStep(10));
    assertEquals(1024L * 1024L,
        ArchiveQueryCoordinator.retainedTraceMetricStep(256L * 1024L * 1024L));
    assertEquals(1024L * 1024L,
        ArchiveQueryCoordinator.retainedTraceMetricStep(ArchiveQueryLimits.UNLIMITED));
  }

  @Test
  public void unlimitedCoordinatorStillTracksEveryActiveLease() {
    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator();

    QueryLease first = coordinator.acquire();
    QueryLease second = coordinator.acquire();
    assertTrue(coordinator.isFair());
    assertEquals(2, coordinator.getActiveLeaseCount());
    assertNotSame(first.getContext(), second.getContext());

    first.close();
    first.close();
    assertTrue(first.isClosed());
    assertEquals(1, coordinator.getActiveLeaseCount());
    second.close();
    assertEquals(0, coordinator.getActiveLeaseCount());
  }

  @Test
  public void transportScopeDefersLeaseCloseUntilSerializedResponseCompletes() {
    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator();
    QueryLease lease;

    try (ArchiveQueryTransportScope ignored = ArchiveQueryTransportScope.open()) {
      lease = coordinator.acquire();
      ArchiveQueryTransportScope.closeAfterResponse(lease);

      assertFalse(lease.isClosed());
      assertEquals(1, coordinator.getActiveLeaseCount());
    }

    assertTrue(lease.isClosed());
    assertEquals(0, coordinator.getActiveLeaseCount());
  }

  @Test
  public void transportScopeChecksDeadlineBeforeSettlingLease() {
    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator(
        ArchiveQueryLimits.builder().deadlineMs(0L).build());

    HistoricalQueryLimitException failure = assertThrows(
        HistoricalQueryLimitException.class, () -> {
          try (ArchiveQueryTransportScope ignored = ArchiveQueryTransportScope.open()) {
            QueryLease acquired = coordinator.acquire();
            ArchiveQueryTransportScope.closeAfterResponse(acquired);
          }
        });

    assertEquals(HistoricalQueryLimitException.Limit.DEADLINE, failure.getLimit());
    assertEquals(0, coordinator.getActiveLeaseCount());
  }

  @Test
  public void closeAfterResponseWithoutTransportScopeClosesImmediately() {
    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator();
    QueryLease lease = coordinator.acquire();

    ArchiveQueryTransportScope.closeAfterResponse(lease);

    assertTrue(lease.isClosed());
    assertEquals(0, coordinator.getActiveLeaseCount());
  }

  @Test
  public void nativeSnapshotSlotsAreBoundedIndependentlyFromQueries() {
    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator(
        ArchiveQueryLimits.builder()
            .maxConcurrentQueries(2)
            .maxOpenSnapshots(1)
            .build());
    QueryLease firstQuery = coordinator.acquire();
    QueryLease secondQuery = coordinator.acquire();
    ArchiveSnapshotPermit firstSnapshot = coordinator.acquireSnapshot(firstQuery);

    HistoricalQueryLimitException failure = assertThrows(
        HistoricalQueryLimitException.class,
        () -> coordinator.acquireSnapshot(secondQuery));

    assertEquals(HistoricalQueryLimitException.Limit.OPEN_SNAPSHOTS, failure.getLimit());
    assertEquals(1, coordinator.getActiveSnapshotCount());
    firstSnapshot.close();
    try (ArchiveSnapshotPermit ignored = coordinator.acquireSnapshot(secondQuery)) {
      assertEquals(1, coordinator.getActiveSnapshotCount());
    }
    assertEquals(0, coordinator.getActiveSnapshotCount());
    firstQuery.close();
    secondQuery.close();
  }

  @Test
  public void closingOwnerDefersLeaseReleaseUntilItsLastSnapshotCloses() throws Exception {
    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator();
    QueryLease query = coordinator.acquire();
    ArchiveSnapshotPermit snapshot = coordinator.acquireSnapshot(query);

    query.close();

    assertTrue(query.isClosed());
    assertEquals(1, coordinator.getActiveLeaseCount());
    assertEquals(1, coordinator.getActiveSnapshotCount());
    assertThrows(IllegalArgumentException.class, () -> coordinator.acquireSnapshot(query));
    coordinator.beginDrain();
    assertFalse(coordinator.awaitDrained(0, TimeUnit.NANOSECONDS));

    snapshot.close();
    assertEquals(0, coordinator.getActiveSnapshotCount());
    assertEquals(0, coordinator.getActiveLeaseCount());
    assertTrue(coordinator.awaitDrained(1, TimeUnit.SECONDS));
  }

  @Test
  public void retainedTraceBudgetIsSharedAndReleasedExactlyOnce() {
    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator(
        ArchiveQueryLimits.builder()
            .maxConcurrentQueries(3)
            .maxTraceBytes(10)
            .maxRetainedTraceBytes(10)
            .build());
    QueryLease first = coordinator.acquire();
    QueryLease second = coordinator.acquire();

    first.getContext().recordTraceBytes(7);
    second.getContext().recordTraceBytes(3);
    assertEquals(10, coordinator.getRetainedTraceBytes());

    HistoricalQueryLimitException failure = assertThrows(
        HistoricalQueryLimitException.class,
        () -> second.getContext().recordTraceBytes(1));
    assertEquals(HistoricalQueryLimitException.Limit.RETAINED_TRACE_BYTES,
        failure.getLimit());
    assertEquals(10, failure.getConfiguredLimit());
    assertEquals(11, failure.getObserved());
    assertEquals(10, coordinator.getRetainedTraceBytes());

    second.close();
    second.close();
    assertEquals(7, coordinator.getRetainedTraceBytes());
    try (QueryLease replacement = coordinator.acquire()) {
      replacement.getContext().recordTraceBytes(3);
      assertEquals(10, coordinator.getRetainedTraceBytes());
    }
    assertEquals(7, coordinator.getRetainedTraceBytes());
    first.close();
    assertEquals(0, coordinator.getRetainedTraceBytes());
  }

  @Test
  public void retainedTraceReservationLivesUntilLastSnapshotReleasesLease() {
    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator(
        ArchiveQueryLimits.builder().maxRetainedTraceBytes(10).build());
    QueryLease query = coordinator.acquire();
    ArchiveSnapshotPermit snapshot = coordinator.acquireSnapshot(query);
    query.getContext().recordTraceBytes(6);

    query.close();

    assertEquals(6, coordinator.getRetainedTraceBytes());
    assertEquals(1, coordinator.getActiveLeaseCount());
    snapshot.close();
    assertEquals(0, coordinator.getRetainedTraceBytes());
    assertEquals(0, coordinator.getActiveLeaseCount());
  }

  @Test
  public void queuedPermitsAreGrantedInFifoOrder() throws Exception {
    ArchiveQueryCoordinator coordinator = coordinator(1, 3, 5_000);
    QueryLease held = coordinator.acquire();
    List<Integer> order = Collections.synchronizedList(new ArrayList<>());
    List<FutureTask<Void>> tasks = new ArrayList<>();

    for (int value = 1; value <= 3; value++) {
      final int queuedValue = value;
      FutureTask<Void> task = new FutureTask<>(() -> {
        try (QueryLease ignored = coordinator.acquire()) {
          order.add(queuedValue);
        }
        return null;
      });
      tasks.add(task);
      new Thread(task, "query-fairness-" + value).start();
      awaitPending(coordinator, value);
    }

    held.close();
    for (FutureTask<Void> task : tasks) {
      task.get(2, TimeUnit.SECONDS);
    }

    assertEquals(Arrays.asList(1, 2, 3), order);
    assertEquals(0, coordinator.getActiveLeaseCount());
    assertEquals(0, coordinator.getPendingQueryCount());
  }

  @Test
  public void pendingQueueIsBoundedBeforeRegisteringAnotherWaiter() throws Exception {
    ArchiveQueryCoordinator coordinator = coordinator(1, 1, 5_000);
    QueryLease held = coordinator.acquire();
    FutureTask<QueryLease> waiter = new FutureTask<>(coordinator::acquire);
    new Thread(waiter, "query-pending-limit").start();
    awaitPending(coordinator, 1);

    HistoricalQueryLimitException failure = assertThrows(
        HistoricalQueryLimitException.class, coordinator::acquire);

    assertEquals(HistoricalQueryLimitException.Reason.RESOURCE_EXHAUSTED,
        failure.getReason());
    assertEquals(HistoricalQueryLimitException.Limit.PENDING_QUERIES, failure.getLimit());
    assertEquals(1, coordinator.getPendingQueryCount());
    held.close();
    waiter.get(2, TimeUnit.SECONDS).close();
  }

  @Test
  public void permitAcquireHonorsConfiguredTimeout() {
    ArchiveQueryCoordinator coordinator = coordinator(1, 1, 5);
    QueryLease held = coordinator.acquire();

    HistoricalQueryLimitException failure = assertThrows(
        HistoricalQueryLimitException.class, coordinator::acquire);

    assertEquals(HistoricalQueryLimitException.Limit.ACQUIRE_TIMEOUT, failure.getLimit());
    assertEquals(0, coordinator.getPendingQueryCount());
    assertEquals(1, coordinator.getActiveLeaseCount());
    held.close();
  }

  @Test
  public void zeroTimeoutRejectsWithoutEnteringPendingQueue() {
    ArchiveQueryCoordinator coordinator = coordinator(1, 5, 0);
    QueryLease held = coordinator.acquire();

    HistoricalQueryLimitException failure = assertThrows(
        HistoricalQueryLimitException.class, coordinator::acquire);

    assertEquals(HistoricalQueryLimitException.Limit.CONCURRENT_QUERIES, failure.getLimit());
    assertEquals(0, coordinator.getPendingQueryCount());
    held.close();
  }

  @Test
  public void drainingWakesWaitersRejectsNewQueriesAndWaitsOnlyForActiveLeases()
      throws Exception {
    ArchiveQueryCoordinator coordinator = coordinator(1, 2, 5_000);
    QueryLease held = coordinator.acquire();
    FutureTask<HistoricalQueryLimitException> waiter = new FutureTask<>(() -> {
      try (QueryLease ignored = coordinator.acquire()) {
        throw new AssertionError("draining waiter must not acquire a lease");
      } catch (HistoricalQueryLimitException e) {
        return e;
      }
    });
    new Thread(waiter, "query-drain-waiter").start();
    awaitPending(coordinator, 1);

    coordinator.beginDrain();

    assertEquals(ArchiveQueryCoordinator.State.DRAINING, coordinator.getState());
    assertFalse(coordinator.isAcceptingQueries());
    HistoricalQueryLimitException pendingFailure = waiter.get(2, TimeUnit.SECONDS);
    assertEquals(HistoricalQueryLimitException.Limit.QUERY_ADMISSION,
        pendingFailure.getLimit());
    awaitPending(coordinator, 0);
    HistoricalQueryLimitException newFailure = assertThrows(
        HistoricalQueryLimitException.class, coordinator::acquire);
    assertEquals(HistoricalQueryLimitException.Limit.QUERY_ADMISSION, newFailure.getLimit());
    assertFalse(coordinator.awaitDrained(1, TimeUnit.MILLISECONDS));

    held.close();
    held.close();
    assertTrue(coordinator.awaitDrained(1, TimeUnit.SECONDS));
    assertEquals(0, coordinator.getActiveLeaseCount());
  }

  @Test
  public void closeIsIdempotentAndNeverForceClosesActiveOwner() throws Exception {
    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator();
    QueryLease lease = coordinator.acquire();

    coordinator.close();
    coordinator.close();

    assertEquals(ArchiveQueryCoordinator.State.DRAINING, coordinator.getState());
    assertFalse(lease.isClosed());
    assertEquals(1, coordinator.getActiveLeaseCount());
    assertFalse(coordinator.awaitDrained(0, TimeUnit.NANOSECONDS));

    lease.close();
    assertEquals(ArchiveQueryCoordinator.State.CLOSED, coordinator.getState());
    assertTrue(coordinator.awaitDrained(0, TimeUnit.NANOSECONDS));
    coordinator.close();
    assertEquals(ArchiveQueryCoordinator.State.CLOSED, coordinator.getState());
  }

  @Test
  public void interruptedWaiterIsRemovedWithoutLeakingActiveCount() throws Exception {
    ArchiveQueryCoordinator coordinator = coordinator(1, 1, 5_000);
    QueryLease held = coordinator.acquire();
    FutureTask<QueryLease> waiter = new FutureTask<>(coordinator::acquireInterruptibly);
    Thread waiterThread = new Thread(waiter, "query-interrupted-waiter");
    waiterThread.start();
    awaitPending(coordinator, 1);

    waiterThread.interrupt();

    ExecutionException failure = assertThrows(
        ExecutionException.class, () -> waiter.get(2, TimeUnit.SECONDS));
    assertTrue(failure.getCause() instanceof InterruptedException);
    awaitPending(coordinator, 0);
    assertEquals(1, coordinator.getActiveLeaseCount());
    held.close();
  }

  @Test
  public void timedDrainLeavesAdmissionClosedAfterTimeout() throws Exception {
    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator();
    QueryLease held = coordinator.acquire();

    assertFalse(coordinator.beginDrain(0, TimeUnit.NANOSECONDS));
    assertEquals(ArchiveQueryCoordinator.State.DRAINING, coordinator.getState());
    assertThrows(HistoricalQueryLimitException.class, coordinator::acquire);

    held.close();
    assertTrue(coordinator.awaitDrained(1, TimeUnit.SECONDS));
  }

  private static ArchiveQueryCoordinator coordinator(
      long maxConcurrent, long maxPending, long acquireTimeoutMs) {
    return new ArchiveQueryCoordinator(ArchiveQueryLimits.builder()
        .maxConcurrentQueries(maxConcurrent)
        .maxPendingQueries(maxPending)
        .acquireTimeoutMs(acquireTimeoutMs)
        .build());
  }

  private static void awaitPending(ArchiveQueryCoordinator coordinator, int expected)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (coordinator.getPendingQueryCount() != expected && System.nanoTime() < deadline) {
      Thread.sleep(1);
    }
    assertEquals(expected, coordinator.getPendingQueryCount());
  }
}
