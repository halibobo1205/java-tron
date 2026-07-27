package org.tron.core.archive.query;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import org.tron.common.math.StrictMathWrapper;

public class ArchiveQueryRequestScopeTest {

  @Test
  public void aggregateBatchAdmissionRejectsTheFirstQueryBeyondTheLimit() {
    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator(
        ArchiveQueryLimits.builder()
            .maxConcurrentQueries(1)
            .maxQueriesPerBatch(2)
            .batchDeadlineMs(1_000)
            .build());

    try (ArchiveQueryRequestScope ignored = ArchiveQueryRequestScope.open()) {
      coordinator.acquire().close();
      coordinator.acquire().close();
      HistoricalQueryLimitException failure = assertThrows(
          HistoricalQueryLimitException.class, coordinator::acquire);
      assertEquals(HistoricalQueryLimitException.Limit.BATCH_QUERIES,
          failure.getLimit());
    }
  }

  @Test
  public void aggregateBatchDeadlineAppliesAcrossSeparateLeases() throws Exception {
    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator(
        ArchiveQueryLimits.builder()
            .maxConcurrentQueries(1)
            .maxQueriesPerBatch(10)
            .batchDeadlineMs(0)
            .build());

    try (ArchiveQueryRequestScope ignored = ArchiveQueryRequestScope.open()) {
      Thread.sleep(1L);
      HistoricalQueryLimitException failure = assertThrows(
          HistoricalQueryLimitException.class, coordinator::acquire);
      assertEquals(HistoricalQueryLimitException.Limit.BATCH_DEADLINE,
          failure.getLimit());
    }
  }

  @Test
  public void admittedQueryUsesOnlyTheRemainingBatchDeadline() {
    AtomicLong now = new AtomicLong();
    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator(
        ArchiveQueryLimits.builder()
            .maxConcurrentQueries(1)
            .deadlineMs(1_000)
            .batchDeadlineMs(10)
            .build());

    try (ArchiveQueryRequestScope ignored = ArchiveQueryRequestScope.open(now::get)) {
      now.set(TimeUnit.MILLISECONDS.toNanos(8));
      QueryLease lease = coordinator.acquire();
      try {
        assertEquals(TimeUnit.MILLISECONDS.toNanos(2),
            lease.getContext().getRemainingNanos());
        now.set(TimeUnit.MILLISECONDS.toNanos(10));

        HistoricalQueryLimitException failure = assertThrows(
            HistoricalQueryLimitException.class,
            lease.getContext()::checkDeadline);
        assertEquals(HistoricalQueryLimitException.Limit.BATCH_DEADLINE,
            failure.getLimit());
      } finally {
        lease.close();
      }
    }
  }

  @Test
  public void deadlineConstraintUsesOneClockSampleForCheckAndRemainingTime() {
    long deadlineNanos = TimeUnit.MILLISECONDS.toNanos(1L);
    long[] samples = {0L, deadlineNanos - 1L, deadlineNanos + 1L};
    AtomicLong calls = new AtomicLong();
    ArchiveQueryLimits limits = ArchiveQueryLimits.builder()
        .batchDeadlineMs(1L)
        .build();

    try (ArchiveQueryRequestScope ignored = ArchiveQueryRequestScope.open(
        () -> samples[(int) StrictMathWrapper.min(calls.getAndIncrement(), samples.length - 1L)])) {
      ArchiveQueryRequestScope.DeadlineConstraint constraint =
          ArchiveQueryRequestScope.deadlineConstraint(limits);

      assertEquals(1L, constraint.remainingNanos);
      assertEquals(2L, calls.get());
    }
  }

  @Test
  public void deadlineConstraintDoesNotRestartAfterGrantDelay() {
    AtomicLong now = new AtomicLong();
    ArchiveQueryLimits limits = ArchiveQueryLimits.builder()
        .deadlineMs(1_000L)
        .batchDeadlineMs(10L)
        .build();

    try (ArchiveQueryRequestScope ignored = ArchiveQueryRequestScope.open(now::get)) {
      now.set(TimeUnit.MILLISECONDS.toNanos(8L));
      ArchiveQueryRequestScope.DeadlineConstraint constraint =
          ArchiveQueryRequestScope.deadlineConstraint(limits);
      now.set(TimeUnit.MILLISECONDS.toNanos(10L));

      QueryContext context = new QueryContext(limits, constraint);
      HistoricalQueryLimitException failure = assertThrows(
          HistoricalQueryLimitException.class, context::checkDeadline);

      assertEquals(HistoricalQueryLimitException.Limit.BATCH_DEADLINE,
          failure.getLimit());
      assertEquals(0L, context.getRemainingNanos());
    }
  }

  @Test
  public void saturatedFiniteBatchDeadlineStillCreatesConstraint() {
    ArchiveQueryLimits limits = ArchiveQueryLimits.builder()
        .batchDeadlineMs(Long.MAX_VALUE)
        .build();

    try (ArchiveQueryRequestScope ignored = ArchiveQueryRequestScope.open(() -> 0L)) {
      ArchiveQueryRequestScope.DeadlineConstraint constraint =
          ArchiveQueryRequestScope.deadlineConstraint(limits);

      assertEquals(Long.MAX_VALUE, constraint.remainingNanos);
    }
  }

  @Test
  public void exactLongMaxBatchCountRejectsTheNextAdmission() throws Exception {
    ArchiveQueryLimits limits = ArchiveQueryLimits.builder()
        .maxQueriesPerBatch(Long.MAX_VALUE)
        .build();

    try (ArchiveQueryRequestScope scope = ArchiveQueryRequestScope.open(() -> 0L)) {
      Field admittedQueries = ArchiveQueryRequestScope.class.getDeclaredField("admittedQueries");
      admittedQueries.setAccessible(true);
      admittedQueries.setLong(scope, Long.MAX_VALUE);

      HistoricalQueryLimitException failure = assertThrows(
          HistoricalQueryLimitException.class,
          () -> ArchiveQueryRequestScope.admit(limits));

      assertEquals(HistoricalQueryLimitException.Limit.BATCH_QUERIES, failure.getLimit());
    }
  }

  @Test
  public void queuedAcquireStopsAtTheBatchDeadline() {
    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator(
        ArchiveQueryLimits.builder()
            .maxConcurrentQueries(1)
            .acquireTimeoutMs(2_000)
            .batchDeadlineMs(200)
            .build());

    try (ArchiveQueryRequestScope ignored = ArchiveQueryRequestScope.open();
         QueryLease blocker = coordinator.acquire()) {
      long startedNanos = System.nanoTime();
      HistoricalQueryLimitException failure = assertThrows(
          HistoricalQueryLimitException.class, coordinator::acquire);
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);

      assertEquals(HistoricalQueryLimitException.Limit.BATCH_DEADLINE,
          failure.getLimit());
      assertTrue("batch deadline must win over the longer acquire timeout",
          elapsedMillis < 1_500L);
      assertEquals(0, coordinator.getPendingQueryCount());
    }
  }
}
