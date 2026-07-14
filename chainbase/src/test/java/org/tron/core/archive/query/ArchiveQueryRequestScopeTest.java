package org.tron.core.archive.query;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

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
