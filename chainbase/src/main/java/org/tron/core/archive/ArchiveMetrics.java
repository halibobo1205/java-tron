package org.tron.core.archive;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tron.common.prometheus.MetricKeys;
import org.tron.common.prometheus.Metrics;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.query.HistoricalQueryLimitException;
import org.tron.core.archive.query.QueryContext;

/** Low-cardinality, block/request-aggregated archive metrics. */
public final class ArchiveMetrics {

  private static final double NANOS_PER_SECOND = 1_000_000_000D;
  private static final Logger logger = LoggerFactory.getLogger("archive");
  private static final AtomicBoolean metricFailureReported = new AtomicBoolean();

  private ArchiveMetrics() {
  }

  public static boolean enabled() {
    return Metrics.enabled();
  }

  public static long startTimer() {
    return enabled() ? System.nanoTime() : Long.MIN_VALUE;
  }

  public static void captureBlock(int rawRecords, List<ArchiveChangeRecord> mergedRecords,
      long previousValueReads, long previousValueReadFailures, long previousValueReadNanos,
      long accountAssetPrefixRows, long accountAssetPointReads, long accountAssetLookups,
      long accountAssetLookupNanos) {
    if (!enabled()) {
      return;
    }
    safely(() -> {
      long bytes = 0L;
      for (ArchiveChangeRecord record : mergedRecords) {
        bytes = addSaturated(bytes, record.canonicalKeySize());
        bytes = addSaturated(bytes, record.getPrevValue().size());
        bytes = addSaturated(bytes, record.getValue().size());
      }
      incrementWork("captured_blocks", 1L);
      incrementWork("raw_records", rawRecords);
      incrementWork("merged_records", mergedRecords.size());
      incrementWork("capture_bytes", bytes);
      incrementWork("previous_value_reads", previousValueReads);
      incrementWork("previous_value_read_failures", previousValueReadFailures);
      if (previousValueReads > 0 || previousValueReadFailures > 0) {
        Metrics.histogramObserve(MetricKeys.Histogram.ARCHIVE_STAGE_LATENCY,
            Math.max(0L, previousValueReadNanos) / NANOS_PER_SECOND,
            "previous_value_reads_block");
      }
      incrementWork("account_asset_prefix_rows", accountAssetPrefixRows);
      incrementWork("account_asset_point_reads", accountAssetPointReads);
      if (accountAssetLookups > 0) {
        Metrics.histogramObserve(MetricKeys.Histogram.ARCHIVE_STAGE_LATENCY,
            Math.max(0L, accountAssetLookupNanos) / NANOS_PER_SECOND,
            "account_asset_diff_block");
      }
    });
  }

  public static void journalWritten(long bytes, long startedNanos) {
    if (!enabled()) {
      return;
    }
    safely(() -> {
      incrementWork("journal_blocks", 1L);
      incrementWork("journal_bytes", bytes);
      observeStage("journal", startedNanos);
    });
  }

  public static void journalAcknowledged(long bytes, long startedNanos) {
    if (!enabled()) {
      return;
    }
    safely(() -> {
      incrementWork("journal_ack_bytes", bytes);
      observeStage("journal_ack", startedNanos);
    });
  }

  public static void staleJournalDeleteFailed() {
    if (!enabled()) {
      return;
    }
    safely(() -> incrementWork("stale_journal_delete_failures", 1L));
  }

  public static void publishFinished(long startedNanos, boolean success) {
    if (!enabled()) {
      return;
    }
    safely(() -> {
      incrementWork(success ? "published_blocks" : "publish_failures", 1L);
      observeStage(success ? "publish" : "publish_failed", startedNanos);
    });
  }

  public static void setInFlight(long blocks, long records, long bytes) {
    setState("inflight_blocks", blocks);
    setState("inflight_records", records);
    setState("inflight_bytes", bytes);
  }

  public static void setPublisherLag(long blocks) {
    setState("publisher_lag_blocks", blocks);
  }

  public static void setDiskFree(long bytes) {
    if (bytes != Long.MAX_VALUE) {
      setState("disk_free_bytes", bytes);
    }
  }

  public static void setQueryAdmission(long active, long pending) {
    if (!enabled()) {
      return;
    }
    safely(() -> {
      setState("active_queries", active);
      setState("pending_queries", pending);
    });
  }

  public static void setActiveSnapshots(long active) {
    setState("active_snapshots", active);
  }

  public static void queryRejected(HistoricalQueryLimitException failure) {
    if (!enabled()) {
      return;
    }
    safely(() -> Metrics.counterInc(MetricKeys.Counter.ARCHIVE_QUERIES, 1D,
        "rejected_" + lower(failure.getReason().name())));
  }

  public static void queryFinished(QueryContext context) {
    if (!enabled()) {
      return;
    }
    safely(() -> {
      HistoricalQueryLimitException terminal = context.getRecordedTerminalException();
      String result = terminal == null ? "completed" : lower(terminal.getReason().name());
      Metrics.counterInc(MetricKeys.Counter.ARCHIVE_QUERIES, 1D, result);
      incrementQueryResource("logical_reads", context.getLogicalReads());
      incrementQueryResource("backend_reads", context.getBackendReads());
      incrementQueryResource("cache_hits", context.getCacheHits());
      incrementQueryResource("trace_steps", context.getTraceSteps());
      incrementQueryResource("trace_bytes", context.getTraceBytes());
      incrementQueryResource("response_bytes", context.getResponseBytes());
      Metrics.histogramObserve(MetricKeys.Histogram.ARCHIVE_QUERY_LATENCY,
          context.getElapsedNanos() / NANOS_PER_SECOND, result);
    });
  }

  private static void incrementWork(String type, long amount) {
    if (amount > 0) {
      Metrics.counterInc(MetricKeys.Counter.ARCHIVE_WORK, amount, type);
    }
  }

  private static void incrementQueryResource(String type, long amount) {
    if (amount > 0) {
      Metrics.counterInc(MetricKeys.Counter.ARCHIVE_QUERY_RESOURCES, amount, type);
    }
  }

  private static void setState(String type, long value) {
    safely(() -> Metrics.gaugeSet(MetricKeys.Gauge.ARCHIVE_STATE, value, type));
  }

  private static void observeStage(String stage, long startedNanos) {
    if (startedNanos != Long.MIN_VALUE) {
      long elapsed = System.nanoTime() - startedNanos;
      Metrics.histogramObserve(MetricKeys.Histogram.ARCHIVE_STAGE_LATENCY,
          Math.max(0L, elapsed) / NANOS_PER_SECOND, stage);
    }
  }

  private static long addSaturated(long left, long right) {
    return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
  }

  private static String lower(String value) {
    return value.toLowerCase(Locale.ROOT);
  }

  private static void safely(Runnable action) {
    try {
      action.run();
    } catch (RuntimeException | LinkageError e) {
      if (metricFailureReported.compareAndSet(false, true)) {
        logger.warn("archive metrics disabled by reporter failure: {}", e.getMessage());
      }
    }
  }
}
