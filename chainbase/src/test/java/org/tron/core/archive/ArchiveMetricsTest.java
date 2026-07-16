package org.tron.core.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

import io.prometheus.client.CollectorRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.prometheus.MetricKeys;
import org.tron.core.archive.query.ArchiveQueryCoordinator;
import org.tron.core.archive.query.ArchiveQueryLimits;
import org.tron.core.archive.query.QueryContext;

public class ArchiveMetricsTest {

  private boolean metricsPreviouslyEnabled;

  @Before
  public void enableMetrics() {
    metricsPreviouslyEnabled = CommonParameter.getInstance().isMetricsPrometheusEnable();
    CommonParameter.getInstance().setMetricsPrometheusEnable(true);
  }

  @After
  public void restoreMetrics() {
    CommonParameter.getInstance().setMetricsPrometheusEnable(metricsPreviouslyEnabled);
  }

  @Test
  public void postAdmissionFailureSettlesAsFailedInsteadOfCompleted() {
    double failedBefore = counter("failed");
    double completedBefore = counter("completed");
    QueryContext context = new QueryContext(ArchiveQueryLimits.unlimited());
    context.recordFailure(new ArchiveException("reader failed"));

    ArchiveMetrics.queryFinished(context);

    assertEquals(failedBefore + 1D, counter("failed"), 0D);
    assertEquals(completedBefore, counter("completed"), 0D);
  }

  @Test
  public void firstFailureControlsMetricClassification() {
    double failedBefore = counter("failed");
    double exhaustedBefore = counter("resource_exhausted");
    QueryContext context = new QueryContext(
        ArchiveQueryLimits.builder().maxLogicalReads(0).build());
    context.recordFailure(new ArchiveException("reader failed"));
    assertThrows(RuntimeException.class, context::recordLogicalRead);

    ArchiveMetrics.queryFinished(context);

    assertEquals(failedBefore + 1D, counter("failed"), 0D);
    assertEquals(exhaustedBefore, counter("resource_exhausted"), 0D);
  }

  @Test
  public void operationalGaugesAndThirtySecondQueryBucketAreScrapable() {
    double bloomUsefulBefore = workCounter("rocksdb_bloom_filter_useful");
    ArchiveMetrics.setRepairRequired(true);
    ArchiveMetrics.setOldestInFlightBlock(123L);
    ArchiveMetrics.setRetainedTraceBytes(456L);
    ArchiveMetrics.setRocksDbState("rocksdb_pending_compaction_bytes", 789L);
    ArchiveMetrics.addRocksDbCounter("rocksdb_bloom_filter_useful", 3L);
    ArchiveMetrics.queryFinished(new QueryContext(ArchiveQueryLimits.unlimited()));

    assertEquals(1D, gauge("repair_required"), 0D);
    assertEquals(123D, gauge("oldest_inflight_block"), 0D);
    assertEquals(456D, gauge("retained_trace_bytes"), 0D);
    assertEquals(789D, gauge("rocksdb_pending_compaction_bytes"), 0D);
    assertEquals(bloomUsefulBefore + 3D,
        workCounter("rocksdb_bloom_filter_useful"), 0D);
    assertNotNull(CollectorRegistry.defaultRegistry.getSampleValue(
        MetricKeys.Histogram.ARCHIVE_QUERY_LATENCY + "_bucket",
        new String[] {"result", "le"}, new String[] {"completed", "30.0"}));
  }

  @Test
  public void queryCoordinatorPublishesZeroRetainedTraceBytesAtStartup() {
    ArchiveMetrics.setRetainedTraceBytes(123L);

    new ArchiveQueryCoordinator();

    Double retained = CollectorRegistry.defaultRegistry.getSampleValue(
        MetricKeys.Gauge.ARCHIVE_STATE,
        new String[] {"type"}, new String[] {"retained_trace_bytes"});
    assertNotNull(retained);
    assertEquals(0D, retained, 0D);
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
