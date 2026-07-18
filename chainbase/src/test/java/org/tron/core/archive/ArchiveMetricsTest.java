package org.tron.core.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.prometheus.client.CollectorRegistry;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.prometheus.MetricKeys;
import org.tron.core.archive.query.ArchiveQueryLimits;
import org.tron.core.archive.query.QueryContext;

public class ArchiveMetricsTest {

  private boolean metricsPreviouslyEnabled;

  @Before
  public void enableMetrics() throws Exception {
    metricsPreviouslyEnabled = CommonParameter.getInstance().isMetricsPrometheusEnable();
    CommonParameter.getInstance().setMetricsPrometheusEnable(true);
    assertTrue(ArchiveMetrics.awaitIdleForTesting(1L, TimeUnit.SECONDS));
  }

  @After
  public void restoreMetrics() throws Exception {
    assertTrue(ArchiveMetrics.awaitIdleForTesting(1L, TimeUnit.SECONDS));
    CommonParameter.getInstance().setMetricsPrometheusEnable(metricsPreviouslyEnabled);
  }

  @Test
  public void postAdmissionFailureSettlesAsFailedInsteadOfCompleted() throws Exception {
    double failedBefore = counter("failed");
    double completedBefore = counter("completed");
    QueryContext context = new QueryContext(ArchiveQueryLimits.unlimited());
    context.recordFailure(new ArchiveException("reader failed"));

    ArchiveMetrics.queryFinished(context);
    assertTrue(ArchiveMetrics.awaitIdleForTesting(1L, TimeUnit.SECONDS));

    assertEquals(failedBefore + 1D, counter("failed"), 0D);
    assertEquals(completedBefore, counter("completed"), 0D);
  }

  @Test
  public void firstFailureControlsMetricClassification() throws Exception {
    double failedBefore = counter("failed");
    double exhaustedBefore = counter("resource_exhausted");
    QueryContext context = new QueryContext(
        ArchiveQueryLimits.builder().maxLogicalReadsPerRequest(0).build());
    context.recordFailure(new ArchiveException("reader failed"));
    assertThrows(RuntimeException.class, context::recordLogicalRead);

    ArchiveMetrics.queryFinished(context);
    assertTrue(ArchiveMetrics.awaitIdleForTesting(1L, TimeUnit.SECONDS));

    assertEquals(failedBefore + 1D, counter("failed"), 0D);
    assertEquals(exhaustedBefore, counter("resource_exhausted"), 0D);
  }

  @Test
  public void operationalGaugesAndThirtySecondQueryBucketAreScrapable() throws Exception {
    double bloomUsefulBefore = workCounter("rocksdb_bloom_filter_useful");
    ArchiveMetrics.setRepairRequired(true);
    ArchiveMetrics.setOldestInFlightBlock(123L);
    ArchiveMetrics.setRocksDbState("rocksdb_pending_compaction_bytes", 789L);
    ArchiveMetrics.addRocksDbCounter("rocksdb_bloom_filter_useful", 3L);
    ArchiveMetrics.queryFinished(new QueryContext(ArchiveQueryLimits.unlimited()));
    assertTrue(ArchiveMetrics.awaitIdleForTesting(1L, TimeUnit.SECONDS));

    assertEquals(1D, gauge("repair_required"), 0D);
    assertEquals(123D, gauge("oldest_inflight_block"), 0D);
    assertEquals(789D, gauge("rocksdb_pending_compaction_bytes"), 0D);
    assertEquals(bloomUsefulBefore + 3D,
        workCounter("rocksdb_bloom_filter_useful"), 0D);
    assertNotNull(CollectorRegistry.defaultRegistry.getSampleValue(
        MetricKeys.Histogram.ARCHIVE_QUERY_LATENCY + "_bucket",
        new String[] {"result", "le"}, new String[] {"completed", "30.0"}));
  }

  @Test
  public void reporterErrorsAndTheirDiagnosticsAreObservational() throws Exception {
    Method safely = ArchiveMetrics.class.getDeclaredMethod("safely", Runnable.class);
    safely.setAccessible(true);
    AssertionError failure = new AssertionError("unrenderable") {
      @Override
      public String getMessage() {
        throw new AssertionError("message rendering failed");
      }
    };

    safely.invoke(null, (Runnable) () -> {
      throw failure;
    });
  }

  @Test
  public void stalledReporterNeverBlocksArchiveCaller() throws Exception {
    assertTrue(ArchiveMetrics.awaitIdleForTesting(1L, TimeUnit.SECONDS));
    CountDownLatch reporterEntered = new CountDownLatch(1);
    CountDownLatch releaseReporter = new CountDownLatch(1);
    ArchiveMetrics.submitForTesting(() -> {
      reporterEntered.countDown();
      await(releaseReporter);
    });
    try {
      assertTrue(reporterEntered.await(1L, TimeUnit.SECONDS));
      long started = System.nanoTime();
      ArchiveMetrics.setRepairRequired(true);
      long elapsed = System.nanoTime() - started;
      assertTrue(elapsed < TimeUnit.SECONDS.toNanos(1L));
    } finally {
      releaseReporter.countDown();
    }
    assertTrue(ArchiveMetrics.awaitIdleForTesting(1L, TimeUnit.SECONDS));
  }

  @Test
  public void dispatchAllocationFailureNeverEscapesArchiveOperation() {
    ArchiveMetrics.failNextDispatchForTesting(new OutOfMemoryError("injected journal metric OOM"));
    ArchiveMetrics.journalWritten(1L, ArchiveMetrics.startTimer());

    ArchiveMetrics.failNextDispatchForTesting(new OutOfMemoryError("injected publish metric OOM"));
    ArchiveMetrics.publishFinished(ArchiveMetrics.startTimer(), true);
  }

  @Test
  public void finalGaugeStatesSurviveFullEventQueue() throws Exception {
    assertTrue(ArchiveMetrics.awaitIdleForTesting(1L, TimeUnit.SECONDS));
    CountDownLatch reporterEntered = new CountDownLatch(1);
    CountDownLatch releaseReporter = new CountDownLatch(1);
    ArchiveMetrics.submitForTesting(() -> {
      reporterEntered.countDown();
      await(releaseReporter);
    });
    try {
      assertTrue(reporterEntered.await(1L, TimeUnit.SECONDS));
      long droppedBefore = ArchiveMetrics.droppedReportsForTesting();
      for (int i = 0; i < 1_024; i++) {
        ArchiveMetrics.submitForTesting(() -> { });
      }
      ArchiveMetrics.submitForTesting(() -> { });
      assertTrue(ArchiveMetrics.droppedReportsForTesting() > droppedBefore);

      ArchiveMetrics.setInFlight(4L, 5L, 6L, 7L);
      ArchiveMetrics.setInFlight(0L, 0L, 0L, 0L);
      ArchiveMetrics.setQueryAdmission(2L, 3L);
      ArchiveMetrics.setQueryAdmission(0L, 0L);
      ArchiveMetrics.setRepairRequired(true);
      ArchiveMetrics.setRepairRequired(false);
    } finally {
      releaseReporter.countDown();
    }
    assertTrue(ArchiveMetrics.awaitIdleForTesting(2L, TimeUnit.SECONDS));
    assertEquals(0D, gauge("inflight_blocks"), 0D);
    assertEquals(0D, gauge("inflight_records"), 0D);
    assertEquals(0D, gauge("inflight_bytes"), 0D);
    assertEquals(0D, gauge("inflight_resource_bytes"), 0D);
    assertEquals(0D, gauge("active_queries"), 0D);
    assertEquals(0D, gauge("pending_queries"), 0D);
    assertEquals(0D, gauge("repair_required"), 0D);
  }

  @Test
  public void disabledMetricsAvoidTimersAndRecordTraversal() {
    CommonParameter.getInstance().setMetricsPrometheusEnable(false);
    try {
      assertEquals(Long.MIN_VALUE, ArchiveMetrics.startTimer());
      ArchiveMetrics.captureBlock(1, 1, 3L,
          0L, 0L, 0L, 0L, 0L, 0L, 0L);
    } finally {
      CommonParameter.getInstance().setMetricsPrometheusEnable(true);
    }
    assertTrue(ArchiveMetrics.startTimer() != 0L);
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

  private static double counter(String result) {
    Double value = CollectorRegistry.defaultRegistry.getSampleValue(
        MetricKeys.Counter.ARCHIVE_QUERIES + "_total",
        new String[] {"result"}, new String[] {result});
    return value == null ? 0D : value;
  }

  private static double gauge(String type) {
    Double value = CollectorRegistry.defaultRegistry.getSampleValue(
        MetricKeys.Gauge.ARCHIVE_STATE,
        new String[] {"type"}, new String[] {type});
    return value == null ? 0D : value;
  }

  private static double workCounter(String type) {
    Double value = CollectorRegistry.defaultRegistry.getSampleValue(
        MetricKeys.Counter.ARCHIVE_WORK + "_total",
        new String[] {"type"}, new String[] {type});
    return value == null ? 0D : value;
  }
}
