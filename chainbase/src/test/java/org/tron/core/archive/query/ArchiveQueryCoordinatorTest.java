package org.tron.core.archive.query;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.Test;
import org.tron.core.archive.ArchiveService;
import org.tron.core.archive.ArchiveWorkLease;
import org.tron.core.archive.reader.ArchiveStateReader;
import org.tron.core.archive.reader.ManagedArchiveStateReader;

public class ArchiveQueryCoordinatorTest {

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
    boolean[] validated = {false};

    try (ArchiveQueryTransportScope ignored = ArchiveQueryTransportScope.open()) {
      lease = coordinator.acquire();
      ArchiveQueryTransportScope.closeAfterResponse(lease, () -> validated[0] = true);

      assertFalse(lease.isClosed());
      assertFalse(validated[0]);
      assertEquals(1, coordinator.getActiveLeaseCount());
    }

    assertTrue(validated[0]);
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
  public void transportValidatorFailureIsRecordedBeforeLeaseSettlement() {
    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator();
    QueryLease lease = coordinator.acquire();
    IllegalStateException injected = new IllegalStateException("invalidated generation");

    IllegalStateException failure = assertThrows(IllegalStateException.class, () -> {
      try (ArchiveQueryTransportScope ignored = ArchiveQueryTransportScope.open()) {
        ArchiveQueryTransportScope.closeAfterResponse(lease, () -> {
          throw injected;
        });
      }
    });

    assertSame(injected, failure);
    assertSame(injected, lease.getContext().getRecordedFailure());
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
  public void deferFailureClosesLeaseAndPreservesOriginalError() {
    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator();
    QueryLease lease = coordinator.acquire();
    AssertionError injected = new AssertionError("defer failed");
    List<QueryLease> rejecting = new AbstractList<QueryLease>() {
      @Override
      public QueryLease get(int index) {
        throw new IndexOutOfBoundsException();
      }

      @Override
      public int size() {
        return 0;
      }

      @Override
      public boolean add(QueryLease ignored) {
        throw injected;
      }
    };

    AssertionError thrown = assertThrows(AssertionError.class,
        () -> ArchiveQueryTransportScope.deferOrClose(rejecting, lease));

    assertEquals(injected, thrown);
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
  public void snapshotPermitLockWaitStopsAtQueryDeadlineBeforeCapacityCheck() throws Exception {
    ReentrantLock coordinatorLock = new ReentrantLock(true);
    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator(
        ArchiveQueryLimits.builder()
            .deadlineMs(50L)
            .maxOpenSnapshots(1L)
            .build(), ignored -> { }, coordinatorLock);
    QueryLease query = coordinator.acquire();
    CountDownLatch lockHeld = new CountDownLatch(1);
    CountDownLatch releaseLock = new CountDownLatch(1);
    Thread blocker = new Thread(() -> {
      coordinatorLock.lock();
      try {
        lockHeld.countDown();
        releaseLock.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        coordinatorLock.unlock();
      }
    }, "archive-snapshot-permit-lock-blocker");

    blocker.start();
    assertTrue(lockHeld.await(1L, TimeUnit.SECONDS));
    try {
      HistoricalQueryLimitException failure = assertThrows(
          HistoricalQueryLimitException.class,
          () -> coordinator.acquireSnapshot(query));

      assertEquals(HistoricalQueryLimitException.Limit.DEADLINE, failure.getLimit());
      releaseLock.countDown();
      blocker.join(1_000L);
      assertEquals(0L, coordinator.getActiveSnapshotCount());
    } finally {
      releaseLock.countDown();
      blocker.join(1_000L);
      query.close();
      coordinator.close();
    }
  }

  @Test
  public void admissionLockWaitStopsAtBatchDeadline() throws Exception {
    ReentrantLock coordinatorLock = new ReentrantLock(true);
    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator(
        ArchiveQueryLimits.builder()
            .acquireTimeoutMs(ArchiveQueryLimits.UNLIMITED)
            .batchDeadlineMs(30L)
            .build(), ignored -> { }, coordinatorLock);
    CountDownLatch lockHeld = new CountDownLatch(1);
    CountDownLatch releaseLock = new CountDownLatch(1);
    Thread blocker = holdLock(coordinatorLock, lockHeld, releaseLock,
        "archive-query-admission-batch-lock-blocker");

    blocker.start();
    assertTrue(lockHeld.await(1L, TimeUnit.SECONDS));
    try (ArchiveQueryRequestScope ignored = ArchiveQueryRequestScope.open()) {
      HistoricalQueryLimitException failure = assertThrows(
          HistoricalQueryLimitException.class, coordinator::acquire);

      assertEquals(HistoricalQueryLimitException.Limit.BATCH_DEADLINE, failure.getLimit());
    } finally {
      releaseLock.countDown();
      blocker.join(1_000L);
      coordinator.close();
    }
  }

  @Test
  public void admissionAndDrainTimeoutsIncludeCoordinatorLockWait() throws Exception {
    ReentrantLock coordinatorLock = new ReentrantLock(true);
    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator(
        ArchiveQueryLimits.unlimited(), ignored -> { }, coordinatorLock);
    CountDownLatch lockHeld = new CountDownLatch(1);
    CountDownLatch releaseLock = new CountDownLatch(1);
    Thread blocker = holdLock(coordinatorLock, lockHeld, releaseLock,
        "archive-query-admission-drain-lock-blocker");

    blocker.start();
    assertTrue(lockHeld.await(1L, TimeUnit.SECONDS));
    try {
      HistoricalQueryLimitException admission = assertThrows(
          HistoricalQueryLimitException.class,
          () -> coordinator.acquire(30L, TimeUnit.MILLISECONDS));
      assertEquals(HistoricalQueryLimitException.Limit.ACQUIRE_TIMEOUT,
          admission.getLimit());

      coordinator.beginDrain();
      assertEquals(ArchiveQueryCoordinator.State.DRAINING, coordinator.getState());
      assertFalse(coordinator.awaitDrained(30L, TimeUnit.MILLISECONDS));
    } finally {
      releaseLock.countDown();
      blocker.join(1_000L);
      assertTrue(coordinator.awaitDrained(1L, TimeUnit.SECONDS));
      coordinator.close();
    }
  }

  @Test
  public void finalCloseTransitionDoesNotWaitIndefinitelyForCoordinatorLock() throws Exception {
    ReentrantLock coordinatorLock = new ReentrantLock(true);
    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator(
        ArchiveQueryLimits.unlimited(), ignored -> { }, coordinatorLock);
    CountDownLatch lockHeld = new CountDownLatch(1);
    CountDownLatch releaseLock = new CountDownLatch(1);
    Thread blocker = holdLock(coordinatorLock, lockHeld, releaseLock,
        "archive-query-final-close-lock-blocker");

    blocker.start();
    assertTrue(lockHeld.await(1L, TimeUnit.SECONDS));
    try {
      assertFalse(coordinator.close(30L, TimeUnit.MILLISECONDS));
    } finally {
      releaseLock.countDown();
      blocker.join(1_000L);
    }

    assertTrue(coordinator.close(1L, TimeUnit.SECONDS));
    assertEquals(ArchiveQueryCoordinator.State.CLOSED, coordinator.getState());
  }

  @Test
  public void timedFinalCloseRefusesToReportSuccessWithActiveLease() throws Exception {
    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator();
    QueryLease lease = coordinator.acquire();

    assertFalse(coordinator.close(1L, TimeUnit.SECONDS));
    assertEquals(ArchiveQueryCoordinator.State.DRAINING, coordinator.getState());

    lease.close();
    assertEquals(ArchiveQueryCoordinator.State.CLOSED, coordinator.getState());
    assertTrue(coordinator.close(1L, TimeUnit.SECONDS));
  }

  @Test
  public void snapshotMetricFailureCannotLeakPermitOrOwnerLease() throws Exception {
    OutOfMemoryError metricFailure = new OutOfMemoryError("injected metric allocation failure");
    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator(
        ArchiveQueryLimits.unlimited(), ignored -> {
          throw metricFailure;
        });
    QueryLease query = coordinator.acquire();

    ArchiveSnapshotPermit snapshot = coordinator.acquireSnapshot(query);
    query.close();
    snapshot.close();

    assertTrue(snapshot.isClosed());
    assertEquals(0L, coordinator.getActiveSnapshotCount());
    assertEquals(0L, coordinator.getActiveLeaseCount());
    coordinator.beginDrain();
    assertTrue(coordinator.awaitDrained(1L, TimeUnit.SECONDS));
  }

  @Test
  public void queryLeaseCommitsThroughCoordinatorLockAllocationFailure() {
    FailOnceLock coordinatorLock = new FailOnceLock("coordinator lock failed");
    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator(
        ArchiveQueryLimits.unlimited(), ignored -> { }, coordinatorLock);
    QueryLease query = coordinator.acquire();
    coordinatorLock.failNext();

    OutOfMemoryError failure = assertThrows(OutOfMemoryError.class, query::close);

    assertEquals(coordinatorLock.getFailure(), failure);
    assertTrue(query.isClosed());
    assertEquals(0L, coordinator.getActiveLeaseCount());
    query.close();
    assertEquals(0L, coordinator.getActiveLeaseCount());
  }

  @Test
  public void snapshotPermitCommitsThroughCoordinatorLockAllocationFailure() {
    FailOnceLock coordinatorLock = new FailOnceLock("coordinator lock failed");
    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator(
        ArchiveQueryLimits.unlimited(), ignored -> { }, coordinatorLock);
    QueryLease query = coordinator.acquire();
    ArchiveSnapshotPermit snapshot = coordinator.acquireSnapshot(query);
    query.close();
    coordinatorLock.failNext();

    OutOfMemoryError failure = assertThrows(OutOfMemoryError.class, snapshot::close);

    assertEquals(coordinatorLock.getFailure(), failure);
    assertTrue(snapshot.isClosed());
    assertEquals(0L, coordinator.getActiveSnapshotCount());
    assertEquals(0L, coordinator.getActiveLeaseCount());
    snapshot.close();
    assertTrue(snapshot.isClosed());
    assertEquals(0L, coordinator.getActiveSnapshotCount());
    assertEquals(0L, coordinator.getActiveLeaseCount());
  }

  @Test
  public void managedReaderOneShotCloseCannotLoseRetryableSnapshotOwnership() {
    FailOnceLock coordinatorLock = new FailOnceLock("coordinator lock failed");
    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator(
        ArchiveQueryLimits.unlimited(), ignored -> { }, coordinatorLock);
    QueryLease query = coordinator.acquire();
    ArchiveSnapshotPermit snapshot = coordinator.acquireSnapshot(query);
    ManagedArchiveStateReader reader = new ManagedArchiveStateReader(
        mock(ArchiveStateReader.class), mock(ArchiveWorkLease.class), query, snapshot,
        ArchiveService.NOOP_MUTATION_LEASE, () -> { }, ignored -> { });
    coordinatorLock.failNext();

    OutOfMemoryError failure = assertThrows(OutOfMemoryError.class, reader::close);

    assertEquals(coordinatorLock.getFailure(), failure);
    assertTrue(snapshot.isClosed());
    assertEquals(0L, coordinator.getActiveSnapshotCount());
    assertEquals(0L, coordinator.getActiveLeaseCount());
    reader.close();
  }

  @Test
  public void transportOneShotSettlementCannotLoseRetryableLeaseOwnership() throws Exception {
    FailOnceLock coordinatorLock = new FailOnceLock("coordinator lock failed");
    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator(
        ArchiveQueryLimits.unlimited(), ignored -> { }, coordinatorLock);
    QueryLease query = coordinator.acquire();
    ArchiveQueryTransportScope scope = ArchiveQueryTransportScope.open();
    ArchiveQueryTransportScope.closeAfterResponse(query);
    coordinatorLock.failNext();

    OutOfMemoryError failure = assertThrows(OutOfMemoryError.class, scope::close);

    assertEquals(coordinatorLock.getFailure(), failure);
    assertEquals(0L, coordinator.getActiveLeaseCount());
    scope.close();
    try (ArchiveQueryTransportScope ignored = ArchiveQueryTransportScope.open()) {
      assertTrue(coordinator.awaitDrained(0L, TimeUnit.NANOSECONDS));
    }
  }

  @Test
  public void concurrentQueryCloseWaitsForCommitAfterLockFailure() throws Exception {
    FailOnceLock coordinatorLock = new FailOnceLock("coordinator lock failed");
    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator(
        ArchiveQueryLimits.unlimited(), ignored -> { }, coordinatorLock);
    QueryLease query = coordinator.acquire();
    coordinatorLock.blockAndFailNext();
    FutureTask<Void> firstClose = closeTask(query);
    FutureTask<Void> secondClose = closeTask(query);

    new Thread(firstClose, "archive-first-query-close").start();
    assertTrue(coordinatorLock.awaitBlockedFailure());
    new Thread(secondClose, "archive-second-query-close").start();
    coordinatorLock.releaseBlockedFailure();

    ExecutionException firstFailure = assertThrows(ExecutionException.class,
        () -> firstClose.get(2L, TimeUnit.SECONDS));
    assertEquals(coordinatorLock.getFailure(), firstFailure.getCause());
    secondClose.get(2L, TimeUnit.SECONDS);
    assertEquals(0L, coordinator.getActiveLeaseCount());
  }

  @Test
  public void concurrentSnapshotCloseWaitsForCommitAfterLockFailure() throws Exception {
    FailOnceLock coordinatorLock = new FailOnceLock("coordinator lock failed");
    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator(
        ArchiveQueryLimits.unlimited(), ignored -> { }, coordinatorLock);
    QueryLease query = coordinator.acquire();
    ArchiveSnapshotPermit snapshot = coordinator.acquireSnapshot(query);
    query.close();
    coordinatorLock.blockAndFailNext();
    FutureTask<Void> firstClose = closeTask(snapshot);
    FutureTask<Void> secondClose = closeTask(snapshot);

    new Thread(firstClose, "archive-first-snapshot-retry-close").start();
    assertTrue(coordinatorLock.awaitBlockedFailure());
    new Thread(secondClose, "archive-second-snapshot-retry-close").start();
    coordinatorLock.releaseBlockedFailure();

    ExecutionException firstFailure = assertThrows(ExecutionException.class,
        () -> firstClose.get(2L, TimeUnit.SECONDS));
    assertEquals(coordinatorLock.getFailure(), firstFailure.getCause());
    secondClose.get(2L, TimeUnit.SECONDS);
    assertTrue(snapshot.isClosed());
    assertEquals(0L, coordinator.getActiveSnapshotCount());
    assertEquals(0L, coordinator.getActiveLeaseCount());
  }

  @Test
  public void queryLeaseRemainsCommittedAfterUnlockFailure() {
    FailOnceLock coordinatorLock = new FailOnceLock("coordinator unlock failed");
    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator(
        ArchiveQueryLimits.unlimited(), ignored -> { }, coordinatorLock);
    QueryLease query = coordinator.acquire();
    coordinatorLock.failNextUnlockAfterRelease();

    OutOfMemoryError failure = assertThrows(OutOfMemoryError.class, query::close);

    assertEquals(coordinatorLock.getFailure(), failure);
    assertEquals(0L, coordinator.getActiveLeaseCount());
    query.close();
    assertEquals(0L, coordinator.getActiveLeaseCount());
  }

  @Test
  public void snapshotPermitRemainsCommittedAfterUnlockFailure() {
    FailOnceLock coordinatorLock = new FailOnceLock("coordinator unlock failed");
    AtomicLong reportedSnapshots = new AtomicLong(-1L);
    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator(
        ArchiveQueryLimits.unlimited(), reportedSnapshots::set, coordinatorLock);
    QueryLease query = coordinator.acquire();
    ArchiveSnapshotPermit snapshot = coordinator.acquireSnapshot(query);
    query.close();
    coordinatorLock.failNextUnlockAfterRelease();

    OutOfMemoryError failure = assertThrows(OutOfMemoryError.class, snapshot::close);

    assertEquals(coordinatorLock.getFailure(), failure);
    assertTrue(snapshot.isClosed());
    assertEquals(0L, coordinator.getActiveSnapshotCount());
    assertEquals(0L, coordinator.getActiveLeaseCount());
    assertEquals(0L, reportedSnapshots.get());
    snapshot.close();
    query.close();
    assertEquals(0L, coordinator.getActiveSnapshotCount());
    assertEquals(0L, coordinator.getActiveLeaseCount());
  }

  @Test
  public void concurrentSnapshotMetricsCannotPublishAnOlderCountLast() throws Exception {
    CountDownLatch staleReporterEntered = new CountDownLatch(1);
    CountDownLatch releaseStaleReporter = new CountDownLatch(1);
    AtomicBoolean blockStaleReporter = new AtomicBoolean();
    AtomicBoolean staleReporterWaitFailed = new AtomicBoolean();
    AtomicLong reported = new AtomicLong(-1L);
    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator(
        ArchiveQueryLimits.unlimited(), count -> {
          if (blockStaleReporter.get() && count == 1L) {
            staleReporterEntered.countDown();
            try {
              if (!releaseStaleReporter.await(2L, TimeUnit.SECONDS)) {
                staleReporterWaitFailed.set(true);
              }
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              staleReporterWaitFailed.set(true);
            }
          }
          reported.set(count);
        });
    QueryLease query = coordinator.acquire();
    ArchiveSnapshotPermit first = coordinator.acquireSnapshot(query);
    ArchiveSnapshotPermit second = coordinator.acquireSnapshot(query);
    blockStaleReporter.set(true);
    FutureTask<Void> firstClose = new FutureTask<>(() -> {
      first.close();
      return null;
    });
    FutureTask<Void> secondClose = new FutureTask<>(() -> {
      second.close();
      return null;
    });
    Thread firstCloser = new Thread(firstClose, "archive-first-snapshot-close");
    Thread secondCloser = new Thread(secondClose, "archive-second-snapshot-close");

    firstCloser.start();
    assertTrue(staleReporterEntered.await(1L, TimeUnit.SECONDS));
    secondCloser.start();
    releaseStaleReporter.countDown();
    firstClose.get(2L, TimeUnit.SECONDS);
    secondClose.get(2L, TimeUnit.SECONDS);

    assertFalse(staleReporterWaitFailed.get());
    assertEquals(0L, coordinator.getActiveSnapshotCount());
    assertEquals(0L, reported.get());
    query.close();
  }

  @Test
  public void exactLongMaxSnapshotLimitReportsSaturatedObservation() throws Exception {
    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator(
        ArchiveQueryLimits.builder()
            .maxOpenSnapshots(Long.MAX_VALUE)
            .build());
    QueryLease owner = coordinator.acquire();
    Field activeSnapshots = ArchiveQueryCoordinator.class.getDeclaredField("activeSnapshots");
    activeSnapshots.setAccessible(true);
    activeSnapshots.setLong(coordinator, Long.MAX_VALUE);
    try {
      HistoricalQueryLimitException failure = assertThrows(
          HistoricalQueryLimitException.class,
          () -> coordinator.acquireSnapshot(owner));

      assertEquals(HistoricalQueryLimitException.Limit.OPEN_SNAPSHOTS, failure.getLimit());
      assertEquals(Long.MAX_VALUE, failure.getObserved());
    } finally {
      activeSnapshots.setLong(coordinator, 0L);
      owner.close();
    }
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

  private static Thread holdLock(ReentrantLock lock, CountDownLatch lockHeld,
      CountDownLatch releaseLock, String threadName) {
    return new Thread(() -> {
      lock.lock();
      try {
        lockHeld.countDown();
        releaseLock.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        lock.unlock();
      }
    }, threadName);
  }

  private static FutureTask<Void> closeTask(AutoCloseable closeable) {
    return new FutureTask<>(() -> {
      closeable.close();
      return null;
    });
  }

  private static void awaitPending(ArchiveQueryCoordinator coordinator, int expected)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (coordinator.getPendingQueryCount() != expected && System.nanoTime() < deadline) {
      Thread.sleep(1);
    }
    assertEquals(expected, coordinator.getPendingQueryCount());
  }

  private static final class FailOnceLock extends ReentrantLock {

    private final OutOfMemoryError failure;
    private final AtomicBoolean failNext = new AtomicBoolean();
    private final AtomicBoolean failNextUnlockAfterRelease = new AtomicBoolean();
    private final AtomicBoolean blockFailure = new AtomicBoolean();
    private final CountDownLatch blockedFailureEntered = new CountDownLatch(1);
    private final CountDownLatch releaseBlockedFailure = new CountDownLatch(1);

    private FailOnceLock(String message) {
      failure = new OutOfMemoryError(message);
    }

    private void failNext() {
      failNext.set(true);
    }

    private void blockAndFailNext() {
      blockFailure.set(true);
      failNext();
    }

    private void failNextUnlockAfterRelease() {
      failNextUnlockAfterRelease.set(true);
    }

    private boolean awaitBlockedFailure() throws InterruptedException {
      return blockedFailureEntered.await(2L, TimeUnit.SECONDS);
    }

    private void releaseBlockedFailure() {
      releaseBlockedFailure.countDown();
    }

    private OutOfMemoryError getFailure() {
      return failure;
    }

    @Override
    public void lock() {
      if (failNext.compareAndSet(true, false)) {
        if (blockFailure.compareAndSet(true, false)) {
          blockedFailureEntered.countDown();
          try {
            if (!releaseBlockedFailure.await(5L, TimeUnit.SECONDS)) {
              throw new AssertionError("timed out waiting to release injected lock failure");
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
          }
        }
        throw failure;
      }
      super.lock();
    }

    @Override
    public void unlock() {
      super.unlock();
      if (failNextUnlockAfterRelease.compareAndSet(true, false)) {
        throw failure;
      }
    }
  }
}
