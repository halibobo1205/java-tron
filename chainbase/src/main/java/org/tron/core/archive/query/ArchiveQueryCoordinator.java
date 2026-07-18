package org.tron.core.archive.query;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongConsumer;
import org.tron.core.archive.ArchiveMetrics;

/**
 * Fair query admission with bounded pending work and drain-aware active lease accounting.
 * Admission is acquired before any archive consistency lock.
 */
public final class ArchiveQueryCoordinator implements AutoCloseable {

  public enum State {
    RUNNING,
    DRAINING,
    CLOSED
  }

  private final ArchiveQueryLimits limits;
  private final ReentrantLock lock;
  private final Condition drained;
  private final Deque<Waiter> waiters = new ArrayDeque<>();
  private final LongConsumer snapshotMetric;
  private final AtomicInteger snapshotMetricWork = new AtomicInteger();
  private final AtomicBoolean drainRequested = new AtomicBoolean();

  private volatile State state = State.RUNNING;
  private long activeLeases;
  private long activeSnapshots;
  private long snapshotMetricVersion;
  private volatile long latestSnapshotMetricCount;
  private volatile long latestSnapshotMetricVersion;
  private long reportedSnapshotMetricVersion;
  private boolean closeRequested;

  public ArchiveQueryCoordinator() {
    this(ArchiveQueryLimits.unlimited());
  }

  public ArchiveQueryCoordinator(ArchiveQueryLimits limits) {
    this(limits, ArchiveMetrics::setActiveSnapshots);
  }

  ArchiveQueryCoordinator(ArchiveQueryLimits limits, LongConsumer snapshotMetric) {
    this(limits, snapshotMetric, new ReentrantLock(true));
  }

  ArchiveQueryCoordinator(ArchiveQueryLimits limits, LongConsumer snapshotMetric,
      ReentrantLock lock) {
    if (limits == null) {
      throw new NullPointerException("limits");
    }
    if (snapshotMetric == null) {
      throw new NullPointerException("snapshotMetric");
    }
    if (lock == null) {
      throw new NullPointerException("lock");
    }
    this.limits = limits;
    this.snapshotMetric = snapshotMetric;
    this.lock = lock;
    drained = lock.newCondition();
    reportQueryAdmission(0L, 0L);
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

    ArchiveQueryRequestScope.DeadlineConstraint initialBatchDeadline =
        ArchiveQueryRequestScope.deadlineConstraint(limits);
    long initialLockTimeout = initialBatchDeadline == null
        ? timeoutNanos : minimumTimeout(timeoutNanos, initialBatchDeadline.remainingNanos);
    long lockStartedNanos = System.nanoTime();
    boolean lockAcquired;
    if (initialLockTimeout == ArchiveQueryLimits.UNLIMITED) {
      lock.lockInterruptibly();
      lockAcquired = true;
    } else {
      lockAcquired = lock.tryLock(initialLockTimeout, TimeUnit.NANOSECONDS);
    }
    if (!lockAcquired) {
      ArchiveQueryRequestScope.checkCurrentDeadline();
      throw HistoricalQueryLimitException.acquireTimedOut(timeoutNanos);
    }
    try {
      ArchiveQueryRequestScope.checkCurrentDeadline();
      long remainingAcquireNanos = timeoutNanos;
      if (remainingAcquireNanos != ArchiveQueryLimits.UNLIMITED) {
        long elapsed = Math.max(0L, System.nanoTime() - lockStartedNanos);
        remainingAcquireNanos = elapsed >= remainingAcquireNanos
            ? 0L : remainingAcquireNanos - elapsed;
        if (timeoutNanos != 0L && remainingAcquireNanos == 0L) {
          throw HistoricalQueryLimitException.acquireTimedOut(timeoutNanos);
        }
      }
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
      reportQueryAdmission(activeLeases, waiters.size());
      try {
        while (true) {
          applyDrainLocked();
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
      } catch (RuntimeException | Error e) {
        removeWaiter(waiter);
        throw e;
      }
    } finally {
      lock.unlock();
    }
  }

  /** Atomically closes admission and wakes every pending acquirer. */
  public void beginDrain() {
    drainRequested.set(true);
    if (lock.tryLock()) {
      try {
        transitionToDraining();
      } finally {
        lock.unlock();
      }
    }
  }

  /** Acquires a native snapshot slot without waiting and before the archive consistency lock. */
  public ArchiveSnapshotPermit acquireSnapshot(QueryLease owner) {
    if (owner == null || !owner.isOwnedBy(this)) {
      throw new IllegalArgumentException("snapshot permit requires an active query lease");
    }
    QueryContext context = owner.getContext();
    context.checkDeadline();
    long remainingNanos = context.getRemainingNanos();
    boolean acquired;
    try {
      if (remainingNanos == Long.MAX_VALUE) {
        lock.lock();
        acquired = true;
      } else {
        acquired = lock.tryLock(remainingNanos, TimeUnit.NANOSECONDS);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw HistoricalQueryLimitException.interrupted(e);
    }
    if (!acquired) {
      throw context.deadlineExceeded();
    }
    ArchiveSnapshotPermit permit;
    long snapshotCount;
    long metricVersion;
    try {
      context.checkDeadline();
      ensureRunning();
      long maximum = limits.getMaxOpenSnapshots();
      long observed = activeSnapshots == Long.MAX_VALUE
          ? Long.MAX_VALUE : activeSnapshots + 1L;
      if (!ArchiveQueryLimits.isUnlimited(maximum) && activeSnapshots >= maximum) {
        throw HistoricalQueryLimitException.openSnapshotsExceeded(
            maximum, observed);
      }
      if (activeSnapshots == Long.MAX_VALUE) {
        throw HistoricalQueryLimitException.openSnapshotsExceeded(
            Long.MAX_VALUE, Long.MAX_VALUE);
      }
      permit = new ArchiveSnapshotPermit(this, owner);
      if (!owner.reserveSnapshot(permit)) {
        throw new IllegalArgumentException("snapshot permit requires an active query lease");
      }
      activeSnapshots++;
      snapshotCount = activeSnapshots;
      metricVersion = ++snapshotMetricVersion;
      latestSnapshotMetricCount = snapshotCount;
      latestSnapshotMetricVersion = metricVersion;
    } finally {
      lock.unlock();
    }
    reportSnapshotMetric();
    return permit;
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
    long lockStartedNanos = System.nanoTime();
    boolean acquired = remainingNanos == Long.MAX_VALUE
        ? acquireLockInterruptibly()
        : lock.tryLock(remainingNanos, TimeUnit.NANOSECONDS);
    if (!acquired) {
      return false;
    }
    try {
      if (remainingNanos != Long.MAX_VALUE) {
        long elapsed = Math.max(0L, System.nanoTime() - lockStartedNanos);
        remainingNanos = elapsed >= remainingNanos ? 0L : remainingNanos - elapsed;
      }
      transitionToDraining();
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
    if (drainRequested.get()) {
      State current = state;
      if (current != State.CLOSED) {
        return State.DRAINING;
      }
    }
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

  /** Publishes the final close transition without waiting indefinitely for the coordinator lock. */
  public boolean close(long timeout, TimeUnit unit) throws InterruptedException {
    if (unit == null) {
      throw new NullPointerException("unit");
    }
    if (timeout < 0L) {
      throw new IllegalArgumentException("timeout must be non-negative");
    }
    drainRequested.set(true);
    long timeoutNanos = unit.toNanos(timeout);
    boolean acquired = timeoutNanos == Long.MAX_VALUE
        ? acquireLockInterruptibly()
        : lock.tryLock(timeoutNanos, TimeUnit.NANOSECONDS);
    if (!acquired) {
      return false;
    }
    try {
      if (state == State.CLOSED) {
        return true;
      }
      closeRequested = true;
      transitionToDraining();
      finishCloseIfDrained();
      return state == State.CLOSED;
    } finally {
      lock.unlock();
    }
  }

  void releaseLease(QueryLease owner) {
    Throwable mutationFailure = null;
    Throwable unlockFailure = null;
    int previousHoldCount = lock.getHoldCount();
    OutOfMemoryError entryFailure = acquireMutationLock(previousHoldCount);
    try {
      releaseLeaseLocked();
      owner.markReleaseCommitted();
      reportQueryAdmission(activeLeases, waiters.size());
    } catch (RuntimeException | Error e) {
      mutationFailure = e;
      if (entryFailure != null) {
        addSuppressedSafely(e, entryFailure);
      }
      throw e;
    } finally {
      Throwable failure = unlockAfterMutation(previousHoldCount);
      if (mutationFailure == null) {
        unlockFailure = failure;
      } else if (failure != null) {
        addSuppressedSafely(mutationFailure, failure);
      }
    }
    reportQueryFinished(owner.getContext());
    rethrowReleaseFailure(entryFailure, unlockFailure);
  }

  void releaseSnapshot(ArchiveSnapshotPermit permit, QueryLease owner) {
    Throwable mutationFailure = null;
    Throwable unlockFailure = null;
    boolean releaseOwner;
    QueryContext finishedContext = null;
    long snapshotCount;
    long metricVersion;
    int previousHoldCount = lock.getHoldCount();
    OutOfMemoryError entryFailure = acquireMutationLock(previousHoldCount);
    try {
      if (activeSnapshots <= 0) {
        throw new IllegalStateException("archive snapshot count underflow");
      }
      if (activeLeases <= 0) {
        throw new IllegalStateException("query lease count underflow");
      }
      releaseOwner = owner.releaseSnapshotOwnership(permit);
      activeSnapshots--;
      if (releaseOwner) {
        releaseLeaseLocked();
        finishedContext = owner.getContext();
        reportQueryAdmission(activeLeases, waiters.size());
      }
      snapshotCount = activeSnapshots;
      metricVersion = ++snapshotMetricVersion;
      latestSnapshotMetricCount = snapshotCount;
      latestSnapshotMetricVersion = metricVersion;
      permit.markReleaseCommitted();
    } catch (RuntimeException | Error e) {
      mutationFailure = e;
      if (entryFailure != null) {
        addSuppressedSafely(e, entryFailure);
      }
      throw e;
    } finally {
      Throwable failure = unlockAfterMutation(previousHoldCount);
      if (mutationFailure == null) {
        unlockFailure = failure;
      } else if (failure != null) {
        addSuppressedSafely(mutationFailure, failure);
      }
    }
    if (finishedContext != null) {
      reportQueryFinished(finishedContext);
    }
    reportSnapshotMetric();
    rethrowReleaseFailure(entryFailure, unlockFailure);
  }

  private void reportSnapshotMetric() {
    if (snapshotMetricWork.getAndIncrement() != 0) {
      return;
    }
    int missed = 1;
    do {
      long metricVersion = latestSnapshotMetricVersion;
      long snapshotCount = latestSnapshotMetricCount;
      if (metricVersion > reportedSnapshotMetricVersion) {
        try {
          snapshotMetric.accept(snapshotCount);
        } catch (Throwable ignored) {
          // Metrics are observational and must never retain a permit or query lease.
        }
        reportedSnapshotMetricVersion = metricVersion;
      }
      missed = snapshotMetricWork.addAndGet(-missed);
    } while (missed != 0);
  }

  private Throwable unlockAfterMutation(int previousHoldCount) {
    Throwable failure = null;
    for (int attempt = 0; attempt < 2 && lock.getHoldCount() > previousHoldCount; attempt++) {
      try {
        lock.unlock();
      } catch (RuntimeException | Error e) {
        if (failure == null) {
          failure = e;
        } else {
          addSuppressedSafely(failure, e);
        }
      }
    }
    return failure;
  }

  private OutOfMemoryError acquireMutationLock(int previousHoldCount) {
    try {
      lock.lock();
      return null;
    } catch (OutOfMemoryError failure) {
      long backoffNanos = 1_000L;
      while (lock.getHoldCount() == previousHoldCount && !lock.tryLock()) {
        LockSupport.parkNanos(backoffNanos);
        backoffNanos = Math.min(TimeUnit.MILLISECONDS.toNanos(1L), backoffNanos * 2L);
      }
      return failure;
    }
  }

  private static void addSuppressedSafely(Throwable primary, Throwable suppressed) {
    try {
      primary.addSuppressed(suppressed);
    } catch (Throwable ignored) {
      // Preserve the first release failure even if suppression itself cannot allocate.
    }
  }

  private static void rethrowUnchecked(Throwable failure) {
    if (failure instanceof RuntimeException) {
      throw (RuntimeException) failure;
    }
    if (failure instanceof Error) {
      throw (Error) failure;
    }
  }

  private static void rethrowReleaseFailure(Throwable entryFailure, Throwable unlockFailure) {
    if (entryFailure != null) {
      if (unlockFailure != null) {
        addSuppressedSafely(entryFailure, unlockFailure);
      }
      rethrowUnchecked(entryFailure);
    }
    rethrowUnchecked(unlockFailure);
  }

  private void releaseLeaseLocked() {
    applyDrainLocked();
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
  }

  private static void reportQueryAdmission(long active, long pending) {
    try {
      ArchiveMetrics.setQueryAdmission(active, pending);
    } catch (Throwable ignored) {
      // Metrics are observational and must not alter admission ownership.
    }
  }

  private static void reportQueryFinished(QueryContext context) {
    try {
      ArchiveMetrics.queryFinished(context);
    } catch (Throwable ignored) {
      // Metrics are observational and must not alter admission ownership.
    }
  }

  private QueryLease grantLease() {
    applyDrainLocked();
    ensureRunning();
    if (activeLeases == Long.MAX_VALUE) {
      throw HistoricalQueryLimitException.concurrentQueriesExceeded(Long.MAX_VALUE);
    }
    ArchiveQueryRequestScope.DeadlineConstraint batchDeadline =
        ArchiveQueryRequestScope.deadlineConstraint(limits);
    activeLeases++;
    try {
      QueryLease lease = new QueryLease(
          this, new QueryContext(limits, batchDeadline));
      reportQueryAdmission(activeLeases, waiters.size());
      return lease;
    } catch (RuntimeException | Error e) {
      activeLeases--;
      if (activeLeases == 0) {
        drained.signalAll();
      }
      throw e;
    }
  }

  private void ensureRunning() {
    applyDrainLocked();
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

  private void removeWaiter(Waiter waiter) {
    boolean wasHead = waiters.peekFirst() == waiter;
    if (waiters.remove(waiter)) {
      reportQueryAdmission(activeLeases, waiters.size());
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
    drainRequested.set(true);
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

  private void applyDrainLocked() {
    if (drainRequested.get()) {
      transitionToDraining();
    }
  }

  private boolean acquireLockInterruptibly() throws InterruptedException {
    lock.lockInterruptibly();
    return true;
  }

  private static final class Waiter {

    private final Condition condition;

    private Waiter(Condition condition) {
      this.condition = condition;
    }
  }
}
