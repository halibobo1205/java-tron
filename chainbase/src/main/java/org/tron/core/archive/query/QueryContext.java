package org.tron.core.archive.query;

import java.util.ArrayList;
import java.util.List;
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
  private final AtomicLong backendReadBytes = new AtomicLong();
  private final AtomicLong cacheHits = new AtomicLong();
  private final AtomicLong vmSteps = new AtomicLong();
  private final AtomicLong vmOverlayBytes = new AtomicLong();
  private final AtomicLong responseBytes = new AtomicLong();
  private final Object snapshotPermitMutex = new Object();
  private final List<ArchiveSnapshotPermit> activeSnapshotPermits = new ArrayList<>(1);
  private final AtomicReference<HistoricalQueryLimitException> terminal =
      new AtomicReference<>();
  private final AtomicReference<RuntimeException> vmTerminalFailure =
      new AtomicReference<>();
  private final AtomicReference<RuntimeException> executionTerminalFailure =
      new AtomicReference<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();

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
    boolean requestDeadlineConfigured = !ArchiveQueryLimits.isUnlimited(limits.getDeadlineMs());
    long requestTimeout = !requestDeadlineConfigured
        ? Long.MAX_VALUE : millisecondsToNanosSaturated(limits.getDeadlineMs());
    long batchTimeout = Long.MAX_VALUE;
    if (batchDeadline != null) {
      long elapsedSinceConstraint = elapsedNanos(startedNanos, batchDeadline.sampledNanos);
      batchTimeout = elapsedSinceConstraint >= batchDeadline.remainingNanos
          ? 0L : batchDeadline.remainingNanos - elapsedSinceConstraint;
    }
    timeoutNanos = Math.min(requestTimeout, batchTimeout);
    deadlineEnabled = requestDeadlineConfigured || batchDeadline != null;
    deadlineLimit = batchDeadline != null && batchTimeout <= requestTimeout
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

  /**
   * Samples this context's monotonic clock once for comparison with another deadline expressed in
   * the same clock domain. This does not newly terminate an expired context; the caller can first
   * decide which deadline was earlier and then call {@link #checkDeadline()} when archive time won.
   * A limit failure recorded before or during the sample is still rethrown immediately.
   */
  public DeadlineSnapshot sampleDeadline() {
    HistoricalQueryLimitException existing = terminal.get();
    if (existing != null) {
      throw existing;
    }
    long sampledNanos = nanoTime.getAsLong();
    long remainingNanos = Long.MAX_VALUE;
    if (deadlineEnabled) {
      remainingNanos = timeoutNanos - elapsedNanos(sampledNanos);
    }
    existing = terminal.get();
    if (existing != null) {
      throw existing;
    }
    return new DeadlineSnapshot(sampledNanos, remainingNanos);
  }

  public long getElapsedNanos() {
    return elapsedNanos(nanoTime.getAsLong());
  }

  /** One immutable monotonic sample of this query deadline. */
  public static final class DeadlineSnapshot {

    private final long sampledNanos;
    private final long remainingNanos;

    private DeadlineSnapshot(long sampledNanos, long remainingNanos) {
      this.sampledNanos = sampledNanos;
      this.remainingNanos = remainingNanos;
    }

    public long getSampledNanos() {
      return sampledNanos;
    }

    public long getRemainingNanos() {
      return remainingNanos;
    }
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

  /** Accounts one materialized backend value before allocating its Java byte array. */
  public long recordBackendValueBytes(long bytes) {
    if (bytes < 0L) {
      throw new IllegalArgumentException("backend value bytes must be non-negative");
    }
    throwIfTerminated();
    long singleValueMaximum = limits.getMaxBackendValueBytes();
    if (!ArchiveQueryLimits.isUnlimited(singleValueMaximum) && bytes > singleValueMaximum) {
      throw terminate(HistoricalQueryLimitException.budgetExceeded(
          HistoricalQueryLimitException.Limit.BACKEND_VALUE_BYTES,
          singleValueMaximum, bytes));
    }
    return consume(
        backendReadBytes,
        bytes,
        limits.getMaxBackendReadBytesPerRequest(),
        HistoricalQueryLimitException.Limit.BACKEND_READ_BYTES);
  }

  /** Checks value budgets without consuming them before a fixed-size native length probe. */
  public void validateBackendValueBytes(long bytes) {
    if (bytes < 0L) {
      throw new IllegalArgumentException("backend value bytes must be non-negative");
    }
    throwIfTerminated();
    long singleValueMaximum = limits.getMaxBackendValueBytes();
    if (!ArchiveQueryLimits.isUnlimited(singleValueMaximum) && bytes > singleValueMaximum) {
      throw terminate(HistoricalQueryLimitException.budgetExceeded(
          HistoricalQueryLimitException.Limit.BACKEND_VALUE_BYTES,
          singleValueMaximum, bytes));
    }
    long aggregateMaximum = limits.getMaxBackendReadBytesPerRequest();
    long current = backendReadBytes.get();
    long projected = current > Long.MAX_VALUE - bytes ? Long.MAX_VALUE : current + bytes;
    if (!ArchiveQueryLimits.isUnlimited(aggregateMaximum)
        && wouldExceed(current, bytes, aggregateMaximum)) {
      throw terminate(HistoricalQueryLimitException.budgetExceeded(
          HistoricalQueryLimitException.Limit.BACKEND_READ_BYTES,
          aggregateMaximum, projected));
    }
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

  public long consumeVmSteps(long count) {
    return recordVmSteps(count);
  }

  /** Accounts allocation retained or generated by the historical VM repository overlay. */
  public long recordVmOverlayBytes(long bytes) {
    return consume(
        vmOverlayBytes,
        bytes,
        limits.getMaxVmOverlayBytes(),
        HistoricalQueryLimitException.Limit.VM_OVERLAY_BYTES);
  }

  public long recordResponseBytes(long bytes) {
    return consume(
        responseBytes,
        bytes,
        limits.getMaxResponseBytes(),
        HistoricalQueryLimitException.Limit.RESPONSE_BYTES);
  }

  /** Reconciles estimates with the actual serialized response size without double counting. */
  public long recordSerializedResponseBytes(long bytes) {
    if (bytes < 0L) {
      throw new IllegalArgumentException("serialized response bytes must be non-negative");
    }
    throwIfTerminated();
    long observed;
    while (true) {
      long current = responseBytes.get();
      if (bytes <= current) {
        observed = current;
        break;
      }
      if (responseBytes.compareAndSet(current, bytes)) {
        observed = bytes;
        break;
      }
    }
    long maximum = limits.getMaxResponseBytes();
    if (!ArchiveQueryLimits.isUnlimited(maximum) && observed > maximum) {
      throw terminate(HistoricalQueryLimitException.budgetExceeded(
          HistoricalQueryLimitException.Limit.RESPONSE_BYTES, maximum, observed));
    }
    HistoricalQueryLimitException existing = terminal.get();
    if (existing != null) {
      throw existing;
    }
    return observed;
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

  public long getBackendReadBytes() {
    return backendReadBytes.get();
  }

  public long getCacheHits() {
    return cacheHits.get();
  }

  public long getVmSteps() {
    return vmSteps.get();
  }

  public long getVmOverlayBytes() {
    return vmOverlayBytes.get();
  }

  public long getResponseBytes() {
    return responseBytes.get();
  }

  /** Returns whether the coordinator currently owns a native-snapshot slot for this query. */
  public boolean hasActiveSnapshotPermit() {
    synchronized (snapshotPermitMutex) {
      return !activeSnapshotPermits.isEmpty();
    }
  }

  boolean reserveSnapshotPermit(ArchiveSnapshotPermit permit) {
    if (permit == null) {
      throw new NullPointerException("permit");
    }
    synchronized (snapshotPermitMutex) {
      for (ArchiveSnapshotPermit current : activeSnapshotPermits) {
        if (current == permit) {
          return false;
        }
      }
      if (activeSnapshotPermits.size() == Integer.MAX_VALUE) {
        return false;
      }
      activeSnapshotPermits.add(permit);
      return true;
    }
  }

  void releaseSnapshotPermit(ArchiveSnapshotPermit permit) {
    synchronized (snapshotPermitMutex) {
      for (int i = 0; i < activeSnapshotPermits.size(); i++) {
        if (activeSnapshotPermits.get(i) == permit) {
          activeSnapshotPermits.remove(i);
          return;
        }
      }
      throw new IllegalStateException("query snapshot permit ownership underflow");
    }
  }

  /** Atomically claims one currently idle coordinator permit for one native query view. */
  public ArchiveSnapshotPermit.SnapshotUse tryClaimSnapshotUse() {
    synchronized (snapshotPermitMutex) {
      for (ArchiveSnapshotPermit permit : activeSnapshotPermits) {
        ArchiveSnapshotPermit.SnapshotUse use = permit.tryClaimUse();
        if (use != null) {
          return use;
        }
      }
      return null;
    }
  }

  /** Checks the monotonic deadline and rethrows the first terminal limit failure, if any. */
  public void checkDeadline() {
    throwIfTerminated();
  }

  /** Terminates a finite context after a timed lock wait consumed its sampled remaining budget. */
  public HistoricalQueryLimitException deadlineExceeded() {
    HistoricalQueryLimitException existing = terminal.get();
    if (existing != null) {
      return existing;
    }
    if (!deadlineEnabled) {
      throw new IllegalStateException("unlimited query context has no deadline");
    }
    long elapsed = Math.max(timeoutNanos, elapsedNanos(nanoTime.getAsLong()));
    return terminate(HistoricalQueryLimitException.deadlineExceeded(
        deadlineLimit, timeoutNanos, elapsed));
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

  /**
   * Records an archive VM failure that must escape even when a nested VM converts it into a
   * failed CALL/CREATE result. The first such failure is retained verbatim for the outer executor.
   */
  public void recordVmTerminalFailure(RuntimeException candidate) {
    if (candidate == null) {
      throw new NullPointerException("candidate");
    }
    vmTerminalFailure.compareAndSet(null, candidate);
    recordExecutionTerminalFailure(candidate);
  }

  /** Returns the first archive VM failure that must be restored by the outer executor. */
  public RuntimeException getRecordedVmTerminalFailure() {
    return vmTerminalFailure.get();
  }

  /** Returns the first budget or archive-VM terminal failure in execution order. */
  public RuntimeException getRecordedExecutionTerminalFailure() {
    return executionTerminalFailure.get();
  }

  /** Records the first post-admission failure so metrics settle this query exactly once. */
  public void recordFailure(Throwable candidate) {
    if (candidate == null) {
      throw new NullPointerException("candidate");
    }
    if (candidate instanceof HistoricalQueryLimitException) {
      terminateWithoutThrowing((HistoricalQueryLimitException) candidate);
    } else {
      failure.compareAndSet(null, candidate);
    }
  }

  /** Returns the first explicitly recorded post-admission failure, if any. */
  public Throwable getRecordedFailure() {
    return failure.get();
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
    long current;
    long observed;
    do {
      current = counter.get();
      observed = current > Long.MAX_VALUE - amount
          ? Long.MAX_VALUE : current + amount;
    } while (!counter.compareAndSet(current, observed));
    if (!ArchiveQueryLimits.isUnlimited(maximum)
        && wouldExceed(current, amount, maximum)) {
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
    return elapsedNanos(now, startedNanos);
  }

  private static long elapsedNanos(long now, long started) {
    long elapsed = now - started;
    return elapsed < 0 ? 0 : elapsed;
  }

  private HistoricalQueryLimitException terminate(HistoricalQueryLimitException candidate) {
    terminateWithoutThrowing(candidate);
    return terminal.get();
  }

  private void terminateWithoutThrowing(HistoricalQueryLimitException candidate) {
    HistoricalQueryLimitException budgetFailure;
    if (terminal.compareAndSet(null, candidate)) {
      budgetFailure = candidate;
    } else {
      budgetFailure = terminal.get();
    }
    recordExecutionTerminalFailure(budgetFailure);
  }

  private void recordExecutionTerminalFailure(RuntimeException candidate) {
    if (executionTerminalFailure.compareAndSet(null, candidate)) {
      failure.compareAndSet(null, candidate);
    }
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

  private static boolean wouldExceed(long current, long amount, long maximum) {
    return current > maximum || amount > maximum - current;
  }

}
