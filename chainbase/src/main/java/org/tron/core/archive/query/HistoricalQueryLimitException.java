package org.tron.core.archive.query;

import java.util.Locale;

/** Typed terminal failure for historical-query admission, deadline, and resource budgets. */
public class HistoricalQueryLimitException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public enum Reason {
    RESOURCE_EXHAUSTED,
    DEADLINE
  }

  public enum Limit {
    QUERY_ADMISSION,
    CONCURRENT_QUERIES,
    PENDING_QUERIES,
    OPEN_SNAPSHOTS,
    ACQUIRE_TIMEOUT,
    INTERRUPTED,
    DEADLINE,
    BATCH_QUERIES,
    BATCH_DEADLINE,
    LOGICAL_READS,
    BACKEND_READS,
    VM_STEPS,
    TRACE_BYTES,
    RETAINED_TRACE_BYTES,
    RESPONSE_BYTES
  }

  private final Reason reason;
  private final Limit limit;
  private final long configuredLimit;
  private final long observed;

  public HistoricalQueryLimitException(Reason reason, Limit limit, String message) {
    this(reason, limit, ArchiveQueryLimits.UNLIMITED, ArchiveQueryLimits.UNLIMITED, message, null);
  }

  public HistoricalQueryLimitException(
      Reason reason,
      Limit limit,
      long configuredLimit,
      long observed,
      String message) {
    this(reason, limit, configuredLimit, observed, message, null);
  }

  public HistoricalQueryLimitException(
      Reason reason,
      Limit limit,
      long configuredLimit,
      long observed,
      String message,
      Throwable cause) {
    super(message, cause);
    if (reason == null) {
      throw new NullPointerException("reason");
    }
    if (limit == null) {
      throw new NullPointerException("limit");
    }
    this.reason = reason;
    this.limit = limit;
    this.configuredLimit = configuredLimit;
    this.observed = observed;
  }

  public Reason getReason() {
    return reason;
  }

  public Limit getLimit() {
    return limit;
  }

  public Limit getLimitType() {
    return limit;
  }

  public long getConfiguredLimit() {
    return configuredLimit;
  }

  public long getObserved() {
    return observed;
  }

  static HistoricalQueryLimitException budgetExceeded(
      Limit limit, long configuredLimit, long observed) {
    return new HistoricalQueryLimitException(
        Reason.RESOURCE_EXHAUSTED,
        limit,
        configuredLimit,
        observed,
        "historical query " + displayName(limit) + " budget exceeded: limit="
            + configuredLimit + ", observed=" + observed);
  }

  static HistoricalQueryLimitException deadlineExceeded(long deadlineNanos, long elapsedNanos) {
    return deadlineExceeded(Limit.DEADLINE, deadlineNanos, elapsedNanos);
  }

  static HistoricalQueryLimitException deadlineExceeded(
      Limit limit, long deadlineNanos, long elapsedNanos) {
    return new HistoricalQueryLimitException(
        Reason.DEADLINE,
        limit,
        deadlineNanos,
        elapsedNanos,
        (limit == Limit.BATCH_DEADLINE
            ? "historical JSON-RPC batch deadline exceeded: deadlineNanos="
            : "historical query deadline exceeded: deadlineNanos=") + deadlineNanos
            + ", elapsedNanos=" + elapsedNanos);
  }

  static HistoricalQueryLimitException batchDeadlineExceeded(
      long deadlineNanos, long elapsedNanos) {
    return deadlineExceeded(Limit.BATCH_DEADLINE, deadlineNanos, elapsedNanos);
  }

  static HistoricalQueryLimitException admissionClosed(String state) {
    return new HistoricalQueryLimitException(
        Reason.RESOURCE_EXHAUSTED,
        Limit.QUERY_ADMISSION,
        "historical query admission is closed: state=" + state);
  }

  static HistoricalQueryLimitException concurrentQueriesExceeded(long configuredLimit) {
    return new HistoricalQueryLimitException(
        Reason.RESOURCE_EXHAUSTED,
        Limit.CONCURRENT_QUERIES,
        configuredLimit,
        configuredLimit,
        "historical query concurrency limit reached: limit=" + configuredLimit);
  }

  static HistoricalQueryLimitException pendingQueriesExceeded(
      long configuredLimit, long observed) {
    return new HistoricalQueryLimitException(
        Reason.RESOURCE_EXHAUSTED,
        Limit.PENDING_QUERIES,
        configuredLimit,
        observed,
        "historical query pending limit reached: limit=" + configuredLimit
            + ", observed=" + observed);
  }

  static HistoricalQueryLimitException openSnapshotsExceeded(
      long configuredLimit, long observed) {
    return new HistoricalQueryLimitException(
        Reason.RESOURCE_EXHAUSTED,
        Limit.OPEN_SNAPSHOTS,
        configuredLimit,
        observed,
        "historical query open snapshot limit reached: limit=" + configuredLimit
            + ", observed=" + observed);
  }

  static HistoricalQueryLimitException acquireTimedOut(long timeoutNanos) {
    return new HistoricalQueryLimitException(
        Reason.RESOURCE_EXHAUSTED,
        Limit.ACQUIRE_TIMEOUT,
        timeoutNanos,
        timeoutNanos,
        "timed out acquiring historical query permit: timeoutNanos=" + timeoutNanos);
  }

  static HistoricalQueryLimitException interrupted(InterruptedException cause) {
    return new HistoricalQueryLimitException(
        Reason.RESOURCE_EXHAUSTED,
        Limit.INTERRUPTED,
        ArchiveQueryLimits.UNLIMITED,
        ArchiveQueryLimits.UNLIMITED,
        "interrupted while acquiring historical query permit",
        cause);
  }

  private static String displayName(Limit limit) {
    return limit.name().toLowerCase(Locale.ROOT).replace('_', ' ');
  }
}
