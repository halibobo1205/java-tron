package org.tron.core.archive.query;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/** Per-request monotonic deadline and overflow-safe resource accounting. */
public final class QueryContext {

  private static final long NANOS_PER_MILLISECOND = 1_000_000L;

  private final ArchiveQueryLimits limits;
  private final LongSupplier nanoTime;
  private final long startedNanos;
  private final long timeoutNanos;
  private final boolean deadlineEnabled;
  private final HistoricalQueryLimitException.Limit deadlineLimit;
  private final AtomicLong logicalReads = new AtomicLong();
  private final AtomicLong backendReads = new AtomicLong();
  private final AtomicLong cacheHits = new AtomicLong();
  private final AtomicLong vmSteps = new AtomicLong();
  private final AtomicLong traceBytes = new AtomicLong();
  private final AtomicLong responseBytes = new AtomicLong();
  private final AtomicReference<HistoricalQueryLimitException> terminal =
      new AtomicReference<>();

  /** Creates a context whose deadline is measured only with {@link System#nanoTime()}. */
  public QueryContext(ArchiveQueryLimits limits) {
    this(limits, System::nanoTime);
  }

  QueryContext(ArchiveQueryLimits limits, LongSupplier nanoTime) {
    this(limits, nanoTime, null);
  }

  QueryContext(ArchiveQueryLimits limits,
      ArchiveQueryRequestScope.DeadlineConstraint batchDeadline) {
    this(limits, batchDeadline == null ? System::nanoTime : batchDeadline.nanoTime,
        batchDeadline);
  }

  private QueryContext(ArchiveQueryLimits limits, LongSupplier nanoTime,
      ArchiveQueryRequestScope.DeadlineConstraint batchDeadline) {
    if (limits == null) {
      throw new NullPointerException("limits");
    }
    if (nanoTime == null) {
      throw new NullPointerException("nanoTime");
    }
    this.limits = limits;
    this.nanoTime = nanoTime;
    startedNanos = nanoTime.getAsLong();
    long requestTimeout = ArchiveQueryLimits.isUnlimited(limits.getDeadlineMs())
        ? Long.MAX_VALUE : millisecondsToNanosSaturated(limits.getDeadlineMs());
    long batchTimeout = batchDeadline == null
        ? Long.MAX_VALUE : batchDeadline.remainingNanos;
    timeoutNanos = Math.min(requestTimeout, batchTimeout);
    deadlineEnabled = timeoutNanos != Long.MAX_VALUE;
    deadlineLimit = batchTimeout <= requestTimeout
        ? HistoricalQueryLimitException.Limit.BATCH_DEADLINE
        : HistoricalQueryLimitException.Limit.DEADLINE;
  }

  public ArchiveQueryLimits getLimits() {
    return limits;
  }

  public long getStartedNanos() {
    return startedNanos;
  }

  /**
   * Returns the monotonic deadline tick. Addition intentionally follows nanoTime wraparound.
   * Unlimited contexts return {@link Long#MAX_VALUE}.
   */
  public long getDeadlineNanos() {
    return deadlineEnabled ? startedNanos + timeoutNanos : Long.MAX_VALUE;
  }

  public long getRemainingNanos() {
    if (!deadlineEnabled) {
      return Long.MAX_VALUE;
    }
    long elapsed = elapsedNanos(nanoTime.getAsLong());
    if (elapsed >= timeoutNanos) {
      return 0;
    }
    return timeoutNanos - elapsed;
  }

  public long getElapsedNanos() {
    return elapsedNanos(nanoTime.getAsLong());
  }

  public long recordLogicalRead() {
    return recordLogicalReads(1L);
  }

  public long recordLogicalReads(long count) {
    return consume(
        logicalReads,
        count,
        limits.getMaxLogicalReadsPerRequest(),
        HistoricalQueryLimitException.Limit.LOGICAL_READS);
  }

  public long consumeLogicalRead() {
    return recordLogicalRead();
  }

  public long consumeLogicalReads(long count) {
    return recordLogicalReads(count);
  }

  public long recordBackendRead() {
    return recordBackendReads(1L);
  }

  public long recordBackendReads(long count) {
    return consume(
        backendReads,
        count,
        limits.getMaxBackendReadsPerRequest(),
        HistoricalQueryLimitException.Limit.BACKEND_READS);
  }

  public long consumeBackendRead() {
    return recordBackendRead();
  }

  public long consumeBackendReads(long count) {
    return recordBackendReads(count);
  }

  public long recordCacheHit() {
    throwIfTerminated();
    return addSaturated(cacheHits, 1L);
  }

  public long recordVmStep() {
    return recordVmSteps(1L);
  }

  public long recordVmSteps(long count) {
    return consume(
        vmSteps,
        count,
        limits.getMaxVmSteps(),
        HistoricalQueryLimitException.Limit.VM_STEPS);
  }

  public long recordTraceStep() {
    return recordVmStep();
  }

  public long recordTraceSteps(long count) {
    return recordVmSteps(count);
  }

  public long consumeVmSteps(long count) {
    return recordVmSteps(count);
  }

  public long recordTraceBytes(long bytes) {
    return consume(
        traceBytes,
        bytes,
        limits.getMaxTraceBytes(),
        HistoricalQueryLimitException.Limit.TRACE_BYTES);
  }

  public long consumeTraceBytes(long bytes) {
    return recordTraceBytes(bytes);
  }

  public long recordResponseBytes(long bytes) {
    return consume(
        responseBytes,
        bytes,
        limits.getMaxResponseBytes(),
        HistoricalQueryLimitException.Limit.RESPONSE_BYTES);
  }

  public long consumeResponseBytes(long bytes) {
    return recordResponseBytes(bytes);
  }

  public long getLogicalReads() {
    return logicalReads.get();
  }

  public long getBackendReads() {
    return backendReads.get();
  }

  public long getCacheHits() {
    return cacheHits.get();
  }

  public long getVmSteps() {
    return vmSteps.get();
  }

  public long getTraceSteps() {
    return getVmSteps();
  }

  public long getTraceBytes() {
    return traceBytes.get();
  }

  public long getResponseBytes() {
    return responseBytes.get();
  }

  /** Checks the monotonic deadline and rethrows the first terminal limit failure, if any. */
  public void checkDeadline() {
    throwIfTerminated();
  }

  /** Rethrows the exact first terminal exception, including one previously swallowed by a VM. */
  public void throwIfTerminated() {
    HistoricalQueryLimitException existing = terminal.get();
    if (existing != null) {
      throw existing;
    }
    HistoricalQueryLimitException deadline = deadlineFailureIfExpired();
    if (deadline != null) {
      throw terminate(deadline);
    }
  }

  public boolean isTerminated() {
    if (terminal.get() != null) {
      return true;
    }
    HistoricalQueryLimitException deadline = deadlineFailureIfExpired();
    if (deadline != null) {
      terminateWithoutThrowing(deadline);
    }
    return terminal.get() != null;
  }

  /** Returns the terminal reason, or {@code null} while the request remains live. */
  public HistoricalQueryLimitException.Reason getTerminalReason() {
    HistoricalQueryLimitException failure = getTerminalException();
    return failure == null ? null : failure.getReason();
  }

  public Optional<HistoricalQueryLimitException.Reason> terminalReason() {
    return Optional.ofNullable(getTerminalReason());
  }

  /** Returns the first terminal exception, or {@code null} while the request remains live. */
  public HistoricalQueryLimitException getTerminalException() {
    isTerminated();
    return terminal.get();
  }

  /**
   * Returns only a terminal failure already recorded by an earlier budget/deadline check. Unlike
   * {@link #getTerminalException()}, this method does not sample the clock and therefore can be
   * used to preserve ordering against an independently detected VM CPU timeout.
   */
  public HistoricalQueryLimitException getRecordedTerminalException() {
    return terminal.get();
  }

  private long consume(
      AtomicLong counter,
      long amount,
      long maximum,
      HistoricalQueryLimitException.Limit limit) {
    if (amount < 0) {
      throw new IllegalArgumentException("budget consumption must be non-negative");
    }
    throwIfTerminated();
    long observed = addSaturated(counter, amount);
    if (!ArchiveQueryLimits.isUnlimited(maximum) && observed > maximum) {
      throw terminate(HistoricalQueryLimitException.budgetExceeded(limit, maximum, observed));
    }
    HistoricalQueryLimitException existing = terminal.get();
    if (existing != null) {
      throw existing;
    }
    return observed;
  }

  private HistoricalQueryLimitException deadlineFailureIfExpired() {
    if (!deadlineEnabled) {
      return null;
    }
    long elapsed = elapsedNanos(nanoTime.getAsLong());
    return elapsed >= timeoutNanos
        ? HistoricalQueryLimitException.deadlineExceeded(deadlineLimit, timeoutNanos, elapsed)
        : null;
  }

  private long elapsedNanos(long now) {
    long elapsed = now - startedNanos;
    return elapsed < 0 ? 0 : elapsed;
  }

  private HistoricalQueryLimitException terminate(HistoricalQueryLimitException candidate) {
    terminateWithoutThrowing(candidate);
    return terminal.get();
  }

  private void terminateWithoutThrowing(HistoricalQueryLimitException candidate) {
    terminal.compareAndSet(null, candidate);
  }

  private static long addSaturated(AtomicLong counter, long amount) {
    while (true) {
      long current = counter.get();
      long updated = current > Long.MAX_VALUE - amount
          ? Long.MAX_VALUE
          : current + amount;
      if (counter.compareAndSet(current, updated)) {
        return updated;
      }
    }
  }

  private static long millisecondsToNanosSaturated(long milliseconds) {
    return milliseconds > Long.MAX_VALUE / NANOS_PER_MILLISECOND
        ? Long.MAX_VALUE
        : milliseconds * NANOS_PER_MILLISECOND;
  }
}
