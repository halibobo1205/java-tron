package org.tron.core.archive.query;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.tron.core.archive.ArchiveMetrics;

/**
 * Fair query admission with bounded pending work and drain-aware active lease accounting.
 * Admission is acquired before any archive consistency lock.
 */
public final class ArchiveQueryCoordinator implements AutoCloseable {

  private static final long RETAINED_TRACE_METRIC_BUCKETS = 256L;
  private static final long MAX_RETAINED_TRACE_METRIC_STEP_BYTES = 1024L * 1024L;

  public enum State {
    RUNNING,
    DRAINING,
    CLOSED
  }

  private final ArchiveQueryLimits limits;
  private final ReentrantLock lock = new ReentrantLock(true);
  private final Condition drained = lock.newCondition();
  private final Deque<Waiter> waiters = new ArrayDeque<>();
  private final AtomicLong retainedTraceBytes = new AtomicLong();
  private final long retainedTraceMetricStepBytes;
  private final Object retainedTraceMetricMonitor = new Object();

  private State state = State.RUNNING;
  private long activeLeases;
  private long activeSnapshots;
  private boolean closeRequested;

  public ArchiveQueryCoordinator() {
    this(ArchiveQueryLimits.unlimited());
  }

  public ArchiveQueryCoordinator(ArchiveQueryLimits limits) {
    if (limits == null) {
      throw new NullPointerException("limits");
    }
    this.limits = limits;
    retainedTraceMetricStepBytes =
        retainedTraceMetricStep(limits.getMaxRetainedTraceBytes());
    ArchiveMetrics.setQueryAdmission(0L, 0L);
    ArchiveMetrics.setRetainedTraceBytes(0L);
  }

  public ArchiveQueryLimits getLimits() {
    return limits;
  }

  /** Acquires with the configured timeout and preserves interruption on a typed rejection. */
  public QueryLease acquire() {
    try {
      return acquireInterruptibly();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw HistoricalQueryLimitException.interrupted(e);
    }
  }

  public QueryLease acquire(long timeout, TimeUnit unit) {
    try {
      return acquireInterruptibly(timeout, unit);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw HistoricalQueryLimitException.interrupted(e);
    }
  }

  public QueryLease acquireInterruptibly() throws InterruptedException {
    long timeoutMs = limits.getAcquireTimeoutMs();
    return acquireInterruptibly(timeoutMs, TimeUnit.MILLISECONDS);
  }

  public QueryLease acquireInterruptibly(long timeout, TimeUnit unit)
      throws InterruptedException {
    if (unit == null) {
      throw new NullPointerException("unit");
    }
    if (timeout < 0 && timeout != ArchiveQueryLimits.UNLIMITED) {
      throw new IllegalArgumentException("timeout must be non-negative or UNLIMITED");
    }
    ArchiveQueryRequestScope.admit(limits);
    long timeoutNanos = timeout == ArchiveQueryLimits.UNLIMITED
        ? ArchiveQueryLimits.UNLIMITED
        : unit.toNanos(timeout);

    lock.lockInterruptibly();
    try {
      ensureRunning();
      if (waiters.isEmpty() && hasCapacity()) {
        return grantLease();
      }
      if (timeoutNanos == 0) {
        throw HistoricalQueryLimitException.concurrentQueriesExceeded(
            limits.getMaxConcurrentQueries());
      }
      if (pendingLimitReached()) {
        throw HistoricalQueryLimitException.pendingQueriesExceeded(
            limits.getMaxPendingQueries(), ((long) waiters.size()) + 1L);
      }

      Waiter waiter = new Waiter(lock.newCondition());
      waiters.addLast(waiter);
      ArchiveMetrics.setQueryAdmission(activeLeases, waiters.size());
      long remainingAcquireNanos = timeoutNanos;
      try {
        while (true) {
          if (state != State.RUNNING) {
            removeWaiter(waiter);
            throw HistoricalQueryLimitException.admissionClosed(state.name());
          }
          if (waiters.peekFirst() == waiter && hasCapacity()) {
            waiters.removeFirst();
            try {
              return grantLease();
            } finally {
              signalNextWaiter();
            }
          }
          ArchiveQueryRequestScope.DeadlineConstraint batchDeadline =
              ArchiveQueryRequestScope.deadlineConstraint(limits);
          if (remainingAcquireNanos == 0) {
            removeWaiter(waiter);
            throw HistoricalQueryLimitException.acquireTimedOut(timeoutNanos);
          }
          long waitNanos = batchDeadline == null
              ? remainingAcquireNanos
              : minimumTimeout(remainingAcquireNanos, batchDeadline.remainingNanos);
          if (waitNanos == ArchiveQueryLimits.UNLIMITED) {
            waiter.condition.await();
          } else {
            long waitRemainingNanos = waiter.condition.awaitNanos(waitNanos);
            if (remainingAcquireNanos != ArchiveQueryLimits.UNLIMITED) {
              long waitedNanos = elapsedWaitNanos(waitNanos, waitRemainingNanos);
              remainingAcquireNanos = Math.max(0L, remainingAcquireNanos - waitedNanos);
            }
          }
        }
      } catch (InterruptedException e) {
        removeWaiter(waiter);
        throw e;
      } catch (RuntimeException e) {
        removeWaiter(waiter);
        throw e;
      }
    } finally {
      lock.unlock();
    }
  }

  /** Atomically closes admission and wakes every pending acquirer. */
  public void beginDrain() {
    lock.lock();
    try {
      transitionToDraining();
    } finally {
      lock.unlock();
    }
  }

  /** Acquires a native snapshot slot without waiting and before the archive consistency lock. */
  public ArchiveSnapshotPermit acquireSnapshot(QueryLease owner) {
    lock.lock();
    try {
      ensureRunning();
      if (owner == null || !owner.isOwnedBy(this)) {
        throw new IllegalArgumentException("snapshot permit requires an active query lease");
      }
      long maximum = limits.getMaxOpenSnapshots();
      if (!ArchiveQueryLimits.isUnlimited(maximum) && activeSnapshots >= maximum) {
        throw HistoricalQueryLimitException.openSnapshotsExceeded(
            maximum, activeSnapshots + 1L);
      }
      if (activeSnapshots == Long.MAX_VALUE) {
        throw HistoricalQueryLimitException.openSnapshotsExceeded(
            Long.MAX_VALUE, Long.MAX_VALUE);
      }
      if (!owner.reserveSnapshot()) {
        throw new IllegalArgumentException("snapshot permit requires an active query lease");
      }
      activeSnapshots++;
      ArchiveMetrics.setActiveSnapshots(activeSnapshots);
      return new ArchiveSnapshotPermit(this, owner);
    } finally {
      lock.unlock();
    }
  }

  public boolean beginDrain(long timeout, TimeUnit unit) throws InterruptedException {
    beginDrain();
    return awaitDrained(timeout, unit);
  }

  public boolean drain(long timeout, TimeUnit unit) throws InterruptedException {
    return beginDrain(timeout, unit);
  }

  /** Waits only for admitted leases; pending acquirers are rejected by {@link #beginDrain()}. */
  public boolean awaitDrained(long timeout, TimeUnit unit) throws InterruptedException {
    if (unit == null) {
      throw new NullPointerException("unit");
    }
    if (timeout < 0) {
      throw new IllegalArgumentException("timeout must be non-negative");
    }
    long remainingNanos = unit.toNanos(timeout);
    lock.lockInterruptibly();
    try {
      while (activeLeases != 0) {
        if (remainingNanos == 0) {
          return false;
        }
        remainingNanos = drained.awaitNanos(remainingNanos);
        if (remainingNanos < 0) {
          remainingNanos = 0;
        }
      }
      return true;
    } finally {
      lock.unlock();
    }
  }

  public State getState() {
    lock.lock();
    try {
      return state;
    } finally {
      lock.unlock();
    }
  }

  public boolean isAcceptingQueries() {
    return getState() == State.RUNNING;
  }

  public long getActiveLeaseCount() {
    lock.lock();
    try {
      return activeLeases;
    } finally {
      lock.unlock();
    }
  }

  public long getActiveQueries() {
    return getActiveLeaseCount();
  }

  public long getActiveSnapshotCount() {
    lock.lock();
    try {
      return activeSnapshots;
    } finally {
      lock.unlock();
    }
  }

  public int getPendingQueryCount() {
    lock.lock();
    try {
      return waiters.size();
    } finally {
      lock.unlock();
    }
  }

  public int getPendingQueries() {
    return getPendingQueryCount();
  }

  public long getRetainedTraceBytes() {
    return retainedTraceBytes.get();
  }

  public boolean isFair() {
    return lock.isFair();
  }

  /**
   * Idempotently requests close. Existing leases remain valid and keep the coordinator in
   * DRAINING until their owners close them; no thread force-closes another owner's resources.
   */
  @Override
  public void close() {
    lock.lock();
    try {
      if (state == State.CLOSED) {
        return;
      }
      closeRequested = true;
      transitionToDraining();
      finishCloseIfDrained();
    } finally {
      lock.unlock();
    }
  }

  void releaseLease(QueryContext context) {
    long releasedTraceBytes = context.drainRetainedTraceBytes();
    lock.lock();
    try {
      if (activeLeases <= 0) {
        throw new IllegalStateException("query lease count underflow");
      }
      activeLeases--;
      if (activeLeases == 0) {
        drained.signalAll();
      }
      if (state == State.RUNNING) {
        signalNextWaiter();
      }
      finishCloseIfDrained();
      ArchiveMetrics.setQueryAdmission(activeLeases, waiters.size());
    } finally {
      lock.unlock();
    }
    releaseTraceBytes(releasedTraceBytes);
    ArchiveMetrics.queryFinished(context);
  }

  void releaseSnapshot(QueryLease owner) {
    boolean releaseOwner;
    lock.lock();
    try {
      if (activeSnapshots <= 0) {
        throw new IllegalStateException("archive snapshot count underflow");
      }
      activeSnapshots--;
      ArchiveMetrics.setActiveSnapshots(activeSnapshots);
      releaseOwner = owner.releaseSnapshotOwnership();
    } finally {
      lock.unlock();
    }
    if (releaseOwner) {
      owner.releaseAfterLastSnapshot();
    }
  }

  private QueryLease grantLease() {
    if (activeLeases == Long.MAX_VALUE) {
      throw HistoricalQueryLimitException.concurrentQueriesExceeded(Long.MAX_VALUE);
    }
    ArchiveQueryRequestScope.DeadlineConstraint batchDeadline =
        ArchiveQueryRequestScope.deadlineConstraint(limits);
    activeLeases++;
    try {
      QueryLease lease = new QueryLease(
          this, new QueryContext(limits, batchDeadline, this::reserveTraceBytes));
      ArchiveMetrics.setQueryAdmission(activeLeases, waiters.size());
      return lease;
    } catch (RuntimeException e) {
      activeLeases--;
      if (activeLeases == 0) {
        drained.signalAll();
      }
      throw e;
    }
  }

  private void ensureRunning() {
    if (state != State.RUNNING) {
      throw HistoricalQueryLimitException.admissionClosed(state.name());
    }
  }

  private boolean hasCapacity() {
    long maximum = limits.getMaxConcurrentQueries();
    return ArchiveQueryLimits.isUnlimited(maximum) || activeLeases < maximum;
  }

  private boolean pendingLimitReached() {
    long maximum = limits.getMaxPendingQueries();
    return !ArchiveQueryLimits.isUnlimited(maximum) && waiters.size() >= maximum;
  }

  private static long minimumTimeout(long first, long second) {
    first = normalizeTimeout(first);
    second = normalizeTimeout(second);
    if (first == ArchiveQueryLimits.UNLIMITED) {
      return second;
    }
    if (second == ArchiveQueryLimits.UNLIMITED) {
      return first;
    }
    return Math.min(first, second);
  }

  private static long normalizeTimeout(long timeout) {
    return timeout < 0L && timeout != ArchiveQueryLimits.UNLIMITED ? 0L : timeout;
  }

  private static long elapsedWaitNanos(long requestedNanos, long remainingNanos) {
    if (remainingNanos <= 0L) {
      return requestedNanos;
    }
    return remainingNanos >= requestedNanos ? 0L : requestedNanos - remainingNanos;
  }

  private void reserveTraceBytes(long bytes) {
    if (bytes == 0L) {
      return;
    }
    while (true) {
      long current = retainedTraceBytes.get();
      long updated = current > Long.MAX_VALUE - bytes ? Long.MAX_VALUE : current + bytes;
      long maximum = limits.getMaxRetainedTraceBytes();
      if (!ArchiveQueryLimits.isUnlimited(maximum) && updated > maximum) {
        publishRetainedTraceMetric();
        throw HistoricalQueryLimitException.budgetExceeded(
            HistoricalQueryLimitException.Limit.RETAINED_TRACE_BYTES, maximum, updated);
      }
      if (retainedTraceBytes.compareAndSet(current, updated)) {
        if (current / retainedTraceMetricStepBytes
            != updated / retainedTraceMetricStepBytes) {
          publishRetainedTraceMetric();
        }
        return;
      }
    }
  }

  private void releaseTraceBytes(long bytes) {
    if (bytes == 0L) {
      return;
    }
    while (true) {
      long current = retainedTraceBytes.get();
      if (bytes > current) {
        throw new IllegalStateException("retained trace byte accounting underflow");
      }
      long updated = current - bytes;
      if (retainedTraceBytes.compareAndSet(current, updated)) {
        publishRetainedTraceMetric();
        return;
      }
    }
  }

  static long retainedTraceMetricStep(long maximum) {
    if (ArchiveQueryLimits.isUnlimited(maximum)) {
      return MAX_RETAINED_TRACE_METRIC_STEP_BYTES;
    }
    long proportional = Math.max(1L, maximum / RETAINED_TRACE_METRIC_BUCKETS);
    return Math.min(MAX_RETAINED_TRACE_METRIC_STEP_BYTES, proportional);
  }

  private void publishRetainedTraceMetric() {
    synchronized (retainedTraceMetricMonitor) {
      ArchiveMetrics.setRetainedTraceBytes(retainedTraceBytes.get());
    }
  }

  private void removeWaiter(Waiter waiter) {
    boolean wasHead = waiters.peekFirst() == waiter;
    if (waiters.remove(waiter)) {
      ArchiveMetrics.setQueryAdmission(activeLeases, waiters.size());
      if (wasHead) {
        signalNextWaiter();
      }
    }
  }

  private void signalNextWaiter() {
    Waiter next = waiters.peekFirst();
    if (next != null && state == State.RUNNING && hasCapacity()) {
      next.condition.signal();
    }
  }

  private void transitionToDraining() {
    if (state == State.RUNNING) {
      state = State.DRAINING;
    }
    if (state == State.DRAINING) {
      for (Waiter waiter : waiters) {
        waiter.condition.signal();
      }
      if (activeLeases == 0) {
        drained.signalAll();
      }
    }
  }

  private void finishCloseIfDrained() {
    if (closeRequested && activeLeases == 0) {
      state = State.CLOSED;
      drained.signalAll();
    }
  }

  private static final class Waiter {

    private final Condition condition;

    private Waiter(Condition condition) {
      this.condition = condition;
    }
  }
}
