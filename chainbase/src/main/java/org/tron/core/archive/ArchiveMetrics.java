package org.tron.core.archive;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tron.common.math.StrictMathWrapper;
import org.tron.common.prometheus.MetricKeys;
import org.tron.common.prometheus.Metrics;
import org.tron.core.archive.query.HistoricalQueryLimitException;
import org.tron.core.archive.query.QueryContext;

/** Low-cardinality, block/request-aggregated archive metrics. */
public final class ArchiveMetrics {

  private static final double NANOS_PER_SECOND = 1_000_000_000D;
  private static final int MAX_PENDING_REPORTS = 1_024;
  private static final int MAX_PENDING_STATE_TYPES = 64;
  private static final int IN_FLIGHT_STATE = 0;
  private static final int QUERY_ADMISSION_STATE = 1;
  private static final int SCALAR_STATE = 2;
  private static final int STATE_KIND_COUNT = 3;
  private static final Logger logger = LoggerFactory.getLogger("archive");
  private static final AtomicBoolean metricFailureReported = new AtomicBoolean();

  private ArchiveMetrics() {
  }

  public static boolean enabled() {
    try {
      return Metrics.enabled();
    } catch (Throwable failure) {
      reportFailureBestEffort(failure);
      return false;
    }
  }

  public static long startTimer() {
    return enabled() ? System.nanoTime() : Long.MIN_VALUE;
  }

  public static void captureBlock(int rawRecords, int mergedRecordCount, long capturedBytes,
      long previousValueReads, long previousValueReadFailures, long previousValueReadNanos,
      long accountAssetPrefixRows, long accountAssetPointReads, long accountAssetLookups,
      long accountAssetLookupNanos) {
    try {
      if (!enabled()) {
        return;
      }
      submit(() -> {
        incrementWork("captured_blocks", 1L);
        incrementWork("raw_records", rawRecords);
        incrementWork("merged_records", mergedRecordCount);
        incrementWork("capture_bytes", capturedBytes);
        incrementWork("previous_value_reads", previousValueReads);
        incrementWork("previous_value_read_failures", previousValueReadFailures);
        if (previousValueReads > 0 || previousValueReadFailures > 0) {
          Metrics.histogramObserve(MetricKeys.Histogram.ARCHIVE_STAGE_LATENCY,
              StrictMathWrapper.max(0L, previousValueReadNanos) / NANOS_PER_SECOND,
              "previous_value_reads_block");
        }
        incrementWork("account_asset_prefix_rows", accountAssetPrefixRows);
        incrementWork("account_asset_point_reads", accountAssetPointReads);
        if (accountAssetLookups > 0) {
          Metrics.histogramObserve(MetricKeys.Histogram.ARCHIVE_STAGE_LATENCY,
              StrictMathWrapper.max(0L, accountAssetLookupNanos) / NANOS_PER_SECOND,
              "account_asset_diff_block");
        }
      });
    } catch (Throwable ignored) {
      // Metric preparation is observational and retains no caller-owned records.
    }
  }

  public static void journalWritten(long bytes, long startedNanos) {
    try {
      if (!enabled()) {
        return;
      }
      double elapsedSeconds = elapsedSeconds(startedNanos);
      dispatcher().submit(() -> {
        incrementWork("journal_blocks", 1L);
        incrementWork("journal_bytes", bytes);
        observeStage("journal", elapsedSeconds);
      });
    } catch (Throwable failure) {
      reportFailureBestEffort(failure);
    }
  }

  public static void journalAcknowledged(long bytes, long startedNanos) {
    try {
      if (!enabled()) {
        return;
      }
      double elapsedSeconds = elapsedSeconds(startedNanos);
      dispatcher().submit(() -> {
        incrementWork("journal_ack_bytes", bytes);
        observeStage("journal_ack", elapsedSeconds);
      });
    } catch (Throwable failure) {
      reportFailureBestEffort(failure);
    }
  }

  public static void staleJournalDeleteFailed() {
    try {
      if (enabled()) {
        dispatcher().submit(() -> incrementWork("stale_journal_delete_failures", 1L));
      }
    } catch (Throwable failure) {
      reportFailureBestEffort(failure);
    }
  }

  public static void publishFinished(long startedNanos, boolean success) {
    try {
      if (!enabled()) {
        return;
      }
      double elapsedSeconds = elapsedSeconds(startedNanos);
      dispatcher().submit(() -> {
        incrementWork(success ? "published_blocks" : "publish_failures", 1L);
        observeStage(success ? "publish" : "publish_failed", elapsedSeconds);
      });
    } catch (Throwable failure) {
      reportFailureBestEffort(failure);
    }
  }

  public static void setInFlight(long blocks, long records, long bytes, long resourceBytes) {
    try {
      if (enabled()) {
        dispatcher().setInFlight(blocks, records, bytes, resourceBytes);
      }
    } catch (Throwable failure) {
      reportFailureBestEffort(failure);
    }
  }

  public static void setPublisherLag(long blocks) {
    setState("publisher_lag_blocks", blocks);
  }

  public static void setOldestInFlightBlock(long blockNum) {
    setState("oldest_inflight_block", blockNum);
  }

  public static void setRepairRequired(boolean required) {
    setState("repair_required", required ? 1L : 0L);
  }

  public static void setDiskFree(long bytes) {
    if (bytes != Long.MAX_VALUE) {
      setState("disk_free_bytes", bytes);
    }
  }

  public static void setQueryAdmission(long active, long pending) {
    try {
      if (enabled()) {
        dispatcher().setQueryAdmission(active, pending);
      }
    } catch (Throwable failure) {
      reportFailureBestEffort(failure);
    }
  }

  public static void setActiveSnapshots(long active) {
    setState("active_snapshots", active);
  }

  public static void queryRejected(HistoricalQueryLimitException failure) {
    try {
      if (!enabled()) {
        return;
      }
      String result = "rejected_" + lower(failure.getReason().name());
      dispatcher().submit(
          () -> Metrics.counterInc(MetricKeys.Counter.ARCHIVE_QUERIES, 1D, result));
    } catch (Throwable metricFailure) {
      reportFailureBestEffort(metricFailure);
    }
  }

  public static void queryFinished(QueryContext context) {
    try {
      if (!enabled()) {
        return;
      }
      Throwable failure = context.getRecordedFailure();
      HistoricalQueryLimitException terminal = context.getRecordedTerminalException();
      String result;
      if (failure instanceof HistoricalQueryLimitException) {
        result = lower(((HistoricalQueryLimitException) failure).getReason().name());
      } else if (failure != null) {
        result = "failed";
      } else {
        result = terminal == null ? "completed" : lower(terminal.getReason().name());
      }
      long logicalReads = context.getLogicalReads();
      long backendReads = context.getBackendReads();
      long backendReadBytes = context.getBackendReadBytes();
      long cacheHits = context.getCacheHits();
      long vmSteps = context.getVmSteps();
      long vmOverlayBytes = context.getVmOverlayBytes();
      long responseBytes = context.getResponseBytes();
      double elapsedSeconds = context.getElapsedNanos() / NANOS_PER_SECOND;
      dispatcher().submit(() -> {
        Metrics.counterInc(MetricKeys.Counter.ARCHIVE_QUERIES, 1D, result);
        incrementQueryResource("logical_reads", logicalReads);
        incrementQueryResource("backend_reads", backendReads);
        incrementQueryResource("backend_read_bytes", backendReadBytes);
        incrementQueryResource("cache_hits", cacheHits);
        incrementQueryResource("vm_steps", vmSteps);
        incrementQueryResource("vm_overlay_bytes", vmOverlayBytes);
        incrementQueryResource("response_bytes", responseBytes);
        Metrics.histogramObserve(MetricKeys.Histogram.ARCHIVE_QUERY_LATENCY,
            elapsedSeconds, result);
      });
    } catch (Throwable ignored) {
      // Query settlement must not retain or delay a completed request.
    }
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
    try {
      if (enabled()) {
        dispatcher().setState(type, value);
      }
    } catch (Throwable failure) {
      reportFailureBestEffort(failure);
    }
  }

  private static void setStateDirect(String type, long value) {
    Metrics.gaugeSet(MetricKeys.Gauge.ARCHIVE_STATE, value, type);
  }

  private static double elapsedSeconds(long startedNanos) {
    if (startedNanos == Long.MIN_VALUE) {
      return 0D;
    }
    long elapsed = System.nanoTime() - startedNanos;
    return StrictMathWrapper.max(0L, elapsed) / NANOS_PER_SECOND;
  }

  private static void observeStage(String stage, double elapsedSeconds) {
    Metrics.histogramObserve(MetricKeys.Histogram.ARCHIVE_STAGE_LATENCY,
        elapsedSeconds, stage);
  }

  private static void submit(Runnable report) {
    try {
      if (enabled()) {
        dispatcher().submit(report);
      }
    } catch (Throwable failure) {
      reportFailureBestEffort(failure);
    }
  }

  static void submitForTesting(Runnable report) {
    dispatcher().submit(report);
  }

  static boolean awaitIdleForTesting(long timeout, TimeUnit unit) throws InterruptedException {
    return dispatcher().awaitIdle(timeout, unit);
  }

  static void failNextDispatchForTesting(Throwable failure) {
    dispatcher().failNext(failure);
  }

  static long droppedReportsForTesting() {
    return dispatcher().getDroppedReports();
  }

  private static MetricDispatcher dispatcher() {
    return DispatcherHolder.INSTANCE;
  }

  private static final class DispatcherHolder {

    private static final MetricDispatcher INSTANCE =
        new MetricDispatcher("archive-metrics", MAX_PENDING_REPORTS);
  }

  private static String lower(String value) {
    return value.toLowerCase(Locale.ROOT);
  }

  private static void safely(Runnable action) {
    try {
      action.run();
    } catch (Throwable failure) {
      reportFailureBestEffort(failure);
    }
  }

  private static void reportFailureBestEffort(Throwable failure) {
    try {
      if (metricFailureReported.compareAndSet(false, true)) {
        logger.warn("archive metrics reporter failed ({}); archive work will continue",
            safeFailureType(failure));
      }
    } catch (Throwable ignored) {
      // Metrics and their diagnostics are observational and must never alter archive behavior.
    }
  }

  private static String safeFailureType(Throwable failure) {
    try {
      return failure == null ? "unknown" : failure.getClass().getName();
    } catch (Throwable ignored) {
      return "unknown";
    }
  }

  /** Bounded queue keeps every archive caller independent of a wedged metrics backend. */
  private static final class MetricDispatcher {

    private final Object monitor = new Object();
    private final ArrayDeque<Runnable> pending = new ArrayDeque<>();
    private final LinkedHashMap<String, Long> pendingStates = new LinkedHashMap<>();
    private final AtomicReference<Throwable> nextFailure = new AtomicReference<>();
    private final int maximumPending;
    private final Thread worker;
    private InFlightState pendingInFlight;
    private QueryAdmissionState pendingQueryAdmission;
    private boolean preferState;
    private int nextStateKind;
    private boolean reporting;
    private long droppedReports;

    private MetricDispatcher(String threadName, int maximumPending) {
      this.maximumPending = maximumPending;
      worker = new Thread(this::run, threadName);
      worker.setDaemon(true);
      worker.start();
    }

    private void submit(Runnable report) {
      if (report == null) {
        return;
      }
      failIfInjected();
      synchronized (monitor) {
        if (pending.size() >= maximumPending) {
          recordDroppedReport();
          return;
        }
        pending.addLast(report);
        monitor.notifyAll();
      }
    }

    private void setState(String type, long value) {
      failIfInjected();
      synchronized (monitor) {
        if (!pendingStates.containsKey(type)
            && pendingStates.size() >= MAX_PENDING_STATE_TYPES) {
          recordDroppedReport();
          return;
        }
        pendingStates.put(type, value);
        monitor.notifyAll();
      }
    }

    private void recordDroppedReport() {
      droppedReports = droppedReports == Long.MAX_VALUE
          ? Long.MAX_VALUE : droppedReports + 1L;
      pendingStates.put("metrics_dropped_reports", droppedReports);
      monitor.notifyAll();
    }

    private void setInFlight(long blocks, long records, long bytes, long resourceBytes) {
      failIfInjected();
      InFlightState state = new InFlightState(blocks, records, bytes, resourceBytes);
      synchronized (monitor) {
        pendingInFlight = state;
        monitor.notifyAll();
      }
    }

    private void setQueryAdmission(long active, long pendingQueries) {
      failIfInjected();
      QueryAdmissionState state = new QueryAdmissionState(active, pendingQueries);
      synchronized (monitor) {
        pendingQueryAdmission = state;
        monitor.notifyAll();
      }
    }

    private void failNext(Throwable failure) {
      if (failure == null) {
        throw new NullPointerException("failure");
      }
      if (!nextFailure.compareAndSet(null, failure)) {
        throw new IllegalStateException("metric dispatch failure is already installed");
      }
    }

    private long getDroppedReports() {
      synchronized (monitor) {
        return droppedReports;
      }
    }

    private void failIfInjected() {
      Throwable failure = nextFailure.getAndSet(null);
      if (failure instanceof RuntimeException) {
        throw (RuntimeException) failure;
      }
      if (failure instanceof Error) {
        throw (Error) failure;
      }
      if (failure != null) {
        throw new ArchiveException("injected archive metric dispatch failure", failure);
      }
    }

    private boolean awaitIdle(long timeout, TimeUnit unit) throws InterruptedException {
      if (unit == null) {
        throw new NullPointerException("unit");
      }
      if (timeout < 0L) {
        throw new IllegalArgumentException("timeout must be non-negative");
      }
      long remaining = unit.toNanos(timeout);
      synchronized (monitor) {
        while (reporting || !pending.isEmpty() || !pendingStates.isEmpty()
            || pendingInFlight != null || pendingQueryAdmission != null) {
          if (remaining <= 0L) {
            return false;
          }
          long started = System.nanoTime();
          TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
          long elapsed = System.nanoTime() - started;
          remaining = elapsed >= remaining ? 0L : remaining - elapsed;
        }
        return true;
      }
    }

    private void run() {
      while (true) {
        Runnable report = null;
        InFlightState inFlight = null;
        QueryAdmissionState queryAdmission = null;
        String stateType = null;
        long stateValue = 0L;
        synchronized (monitor) {
          while (pending.isEmpty() && pendingInFlight == null
              && pendingQueryAdmission == null && pendingStates.isEmpty()) {
            try {
              monitor.wait();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              return;
            }
          }
          boolean hasState = pendingInFlight != null
              || pendingQueryAdmission != null || !pendingStates.isEmpty();
          if (!pending.isEmpty() && (!preferState || !hasState)) {
            report = pending.removeFirst();
            preferState = hasState;
          } else {
            int stateKind = nextPendingStateKind();
            if (stateKind == IN_FLIGHT_STATE) {
              inFlight = pendingInFlight;
              pendingInFlight = null;
            } else if (stateKind == QUERY_ADMISSION_STATE) {
              queryAdmission = pendingQueryAdmission;
              pendingQueryAdmission = null;
            } else {
              Map.Entry<String, Long> state = pendingStates.entrySet().iterator().next();
              stateType = state.getKey();
              stateValue = state.getValue();
              pendingStates.remove(stateType);
            }
            preferState = false;
          }
          reporting = true;
        }
        try {
          if (report != null) {
            report.run();
          } else if (inFlight != null) {
            setStateDirect("inflight_blocks", inFlight.blocks);
            setStateDirect("inflight_records", inFlight.records);
            setStateDirect("inflight_bytes", inFlight.bytes);
            setStateDirect("inflight_resource_bytes", inFlight.resourceBytes);
          } else if (queryAdmission != null) {
            setStateDirect("active_queries", queryAdmission.active);
            setStateDirect("pending_queries", queryAdmission.pending);
          } else {
            setStateDirect(stateType, stateValue);
          }
        } catch (Throwable failure) {
          reportFailureBestEffort(failure);
        }
        synchronized (monitor) {
          reporting = false;
          monitor.notifyAll();
        }
      }
    }

    private int nextPendingStateKind() {
      for (int offset = 0; offset < STATE_KIND_COUNT; offset++) {
        int candidate = (nextStateKind + offset) % STATE_KIND_COUNT;
        if ((candidate == IN_FLIGHT_STATE && pendingInFlight != null)
            || (candidate == QUERY_ADMISSION_STATE && pendingQueryAdmission != null)
            || (candidate == SCALAR_STATE && !pendingStates.isEmpty())) {
          nextStateKind = (candidate + 1) % STATE_KIND_COUNT;
          return candidate;
        }
      }
      throw new IllegalStateException("archive metric state queue is empty");
    }
  }

  private static final class InFlightState {

    private final long blocks;
    private final long records;
    private final long bytes;
    private final long resourceBytes;

    private InFlightState(long blocks, long records, long bytes, long resourceBytes) {
      this.blocks = blocks;
      this.records = records;
      this.bytes = bytes;
      this.resourceBytes = resourceBytes;
    }
  }

  private static final class QueryAdmissionState {

    private final long active;
    private final long pending;

    private QueryAdmissionState(long active, long pending) {
      this.active = active;
      this.pending = pending;
    }
  }
}
