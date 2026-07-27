package org.tron.core.archive;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.tron.common.math.StrictMathWrapper;

/** Fair lifecycle admission and drain accounting for the archive sidecar. */
public final class ArchiveLifecycle {

  public enum Phase {
    NEW,
    RECOVERING,
    RUNNING,
    DRAINING,
    CLOSED
  }

  public enum WorkType {
    RECOVERY,
    WRITER,
    QUERY,
    PUBLISHER
  }

  private enum LeaseState {
    ACQUIRED_NOT_STARTED,
    STARTED,
    CLOSED
  }

  private final ReentrantLock lock = new ReentrantLock(true);
  private final Condition drained = lock.newCondition();
  private final Map<WorkType, Long> active = new EnumMap<>(WorkType.class);
  private final AtomicReference<RuntimeException> fatal = new AtomicReference<>();
  private final AtomicBoolean drainRequested = new AtomicBoolean();
  private final ThreadLocal<Lease> current = new ThreadLocal<>();
  private boolean recoveryActivationInProgress;
  private volatile Phase phase;

  public ArchiveLifecycle(Phase initialPhase) {
    if (initialPhase == null) {
      throw new NullPointerException("initialPhase");
    }
    phase = initialPhase;
    for (WorkType type : WorkType.values()) {
      active.put(type, 0L);
    }
  }

  public ArchiveWorkLease acquire(WorkType type) {
    if (type == null) {
      throw new NullPointerException("type");
    }
    lock.lock();
    try {
      requireAdmission(type);
      long count = active.get(type);
      if (count == Long.MAX_VALUE) {
        throw new ArchiveException("archive " + type + " active count overflow");
      }
      if (type == WorkType.RECOVERY && count != 0) {
        throw new ArchiveException("archive recovery participant already exists");
      }
      Lease lease = new Lease(type, Thread.currentThread());
      active.put(type, count + 1);
      return lease;
    } finally {
      lock.unlock();
    }
  }

  public boolean completeRecovery() {
    return completeRecovery(() -> {
    });
  }

  /** Commits recovery, runs activation without the lifecycle mutex, then publishes the phase. */
  public boolean completeRecovery(Runnable activation) {
    if (activation == null) {
      throw new NullPointerException("activation");
    }
    Lease lease;
    lock.lock();
    try {
      lease = current.get();
      if (lease == null || lease.type != WorkType.RECOVERY
          || lease.state != LeaseState.STARTED) {
        throw new ArchiveException("archive recovery completion requires the active lease");
      }
      if (recoveryActivationInProgress) {
        throw new ArchiveException("archive recovery activation is already in progress");
      }
      requireNoFatal();
      applyDrainLocked();
      if (phase == Phase.DRAINING) {
        closeLeaseLocked(lease);
        return false;
      }
      if (phase != Phase.RECOVERING) {
        throw new ArchiveException("archive recovery cannot complete from phase " + phase);
      }
      recoveryActivationInProgress = true;
    } finally {
      lock.unlock();
    }

    try {
      activation.run();
    } catch (RuntimeException | Error activationFailure) {
      finishFailedRecoveryActivation(lease, activationFailure);
      throw activationFailure;
    }

    lock.lock();
    try {
      recoveryActivationInProgress = false;
      try {
        requireNoFatal();
        if (phase == Phase.RECOVERING) {
          phase = Phase.RUNNING;
        } else if (phase != Phase.DRAINING) {
          throw new ArchiveException(
              "archive recovery activation cannot settle from phase " + phase);
        }
        applyDrainLocked();
      } catch (RuntimeException | Error settlementFailure) {
        closeLeaseAndSuppress(lease, settlementFailure);
        throw settlementFailure;
      }
      closeLeaseLocked(lease);
      return true;
    } finally {
      lock.unlock();
    }
  }

  private void finishFailedRecoveryActivation(Lease lease, Throwable activationFailure) {
    lock.lock();
    try {
      recoveryActivationInProgress = false;
      applyDrainLocked();
      closeLeaseAndSuppress(lease, activationFailure);
    } finally {
      lock.unlock();
    }
  }

  private void closeLeaseAndSuppress(Lease lease, Throwable failure) {
    try {
      closeLeaseLocked(lease);
    } catch (RuntimeException | Error closeFailure) {
      if (failure != closeFailure) {
        try {
          failure.addSuppressed(closeFailure);
        } catch (Throwable ignored) {
          // Preserve the activation or settlement failure when suppression cannot allocate.
        }
      }
    }
  }

  public boolean markFatal(RuntimeException failure) {
    if (failure == null) {
      throw new NullPointerException("failure");
    }
    lock.lock();
    try {
      boolean first = fatal.compareAndSet(null, failure);
      if (first) {
        drained.signalAll();
      }
      return first;
    } finally {
      lock.unlock();
    }
  }

  public RuntimeException getFatalFailure() {
    return fatal.get();
  }

  public void validateCurrentOperation() {
    RuntimeException failure = fatal.get();
    if (failure != null) {
      throw new ArchiveException("archive is unavailable after fatal failure", failure);
    }
    lock.lock();
    try {
      requireNoFatal();
      applyDrainLocked();
      if (phase == Phase.RUNNING) {
        return;
      }
      Lease lease = current.get();
      if (lease != null && lease.state == LeaseState.STARTED
          && (phase == Phase.DRAINING
              || phase == Phase.RECOVERING && lease.type == WorkType.RECOVERY)) {
        return;
      }
      throw new ArchiveException("archive is not available in lifecycle phase " + phase);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Validates the final settlement of a response admitted while the archive was running.
   * Normal drain waits for its query lease, so settlement may finish in {@link Phase#DRAINING};
   * fatal state and every phase that cannot own an admitted query still fail closed.
   */
  public void validateAdmittedResponse() {
    RuntimeException failure = fatal.get();
    if (failure != null) {
      throw new ArchiveException("archive is unavailable after fatal failure", failure);
    }
    lock.lock();
    try {
      requireNoFatal();
      applyDrainLocked();
      if (phase == Phase.RUNNING || phase == Phase.DRAINING) {
        return;
      }
      throw new ArchiveException(
          "archive response cannot settle in lifecycle phase " + phase);
    } finally {
      lock.unlock();
    }
  }

  public void beginDrain() {
    drainRequested.set(true);
    if (lock.tryLock()) {
      try {
        applyDrainLocked();
        drained.signalAll();
      } finally {
        lock.unlock();
      }
    }
  }

  public boolean awaitDrained(long timeout, TimeUnit unit) throws InterruptedException {
    if (unit == null) {
      throw new NullPointerException("unit");
    }
    if (timeout < 0) {
      throw new IllegalArgumentException("timeout must be non-negative");
    }
    long remaining = unit.toNanos(timeout);
    long lockStartedNanos = System.nanoTime();
    boolean acquired = remaining == Long.MAX_VALUE
        ? acquireInterruptibly()
        : lock.tryLock(remaining, TimeUnit.NANOSECONDS);
    if (!acquired) {
      return false;
    }
    try {
      if (remaining != Long.MAX_VALUE) {
        long elapsed = StrictMathWrapper.max(0L, System.nanoTime() - lockStartedNanos);
        remaining = elapsed >= remaining ? 0L : remaining - elapsed;
      }
      applyDrainLocked();
      while (activeCountLocked() != 0) {
        if (remaining == 0) {
          return false;
        }
        remaining = drained.awaitNanos(remaining);
        if (remaining < 0) {
          remaining = 0;
        }
      }
      return true;
    } finally {
      lock.unlock();
    }
  }

  public void markClosed() {
    lock.lock();
    try {
      if (activeCountLocked() != 0) {
        throw new ArchiveException("archive cannot close with active lifecycle leases");
      }
      phase = Phase.CLOSED;
      drained.signalAll();
    } finally {
      lock.unlock();
    }
  }

  public Phase getPhase() {
    Phase currentPhase = phase;
    return drainRequested.get() && currentPhase != Phase.CLOSED
        ? Phase.DRAINING : currentPhase;
  }

  public long getActiveCount(WorkType type) {
    lock.lock();
    try {
      return active.get(type);
    } finally {
      lock.unlock();
    }
  }

  private void requireAdmission(WorkType type) {
    requireNoFatal();
    applyDrainLocked();
    boolean allowed = type == WorkType.RECOVERY
        ? phase == Phase.RECOVERING
        : phase == Phase.RUNNING;
    if (!allowed) {
      throw new ArchiveException("archive " + type + " admission is closed in phase " + phase);
    }
  }

  private void requireNoFatal() {
    RuntimeException failure = fatal.get();
    if (failure != null) {
      throw new ArchiveException("archive is unavailable after fatal failure", failure);
    }
  }

  private long activeCountLocked() {
    long total = 0;
    for (long count : active.values()) {
      if (Long.MAX_VALUE - total < count) {
        return Long.MAX_VALUE;
      }
      total += count;
    }
    return total;
  }

  private boolean acquireInterruptibly() throws InterruptedException {
    lock.lockInterruptibly();
    return true;
  }

  private void applyDrainLocked() {
    if (drainRequested.get() && phase != Phase.CLOSED) {
      phase = Phase.DRAINING;
    }
  }

  private void startLease(Lease lease) {
    lease.requireOwner();
    lock.lock();
    try {
      if (lease.state != LeaseState.ACQUIRED_NOT_STARTED) {
        if (lease.state == LeaseState.STARTED) {
          return;
        }
        throw new ArchiveException("archive lifecycle lease is closed");
      }
      if (current.get() != null) {
        throw new ArchiveException("nested archive lifecycle leases are not supported");
      }
      requireAdmission(lease.type);
      try {
        current.set(lease);
        lease.state = LeaseState.STARTED;
      } catch (RuntimeException | Error failure) {
        try {
          current.remove();
        } catch (RuntimeException | Error restoreFailure) {
          if (failure != restoreFailure) {
            failure.addSuppressed(restoreFailure);
          }
        }
        throw failure;
      }
    } finally {
      lock.unlock();
    }
  }

  private void closeLease(Lease lease) {
    lease.requireOwner();
    lock.lock();
    try {
      closeLeaseLocked(lease);
    } finally {
      lock.unlock();
    }
  }

  private void closeLeaseLocked(Lease lease) {
    if (lease.state == LeaseState.CLOSED) {
      return;
    }
    if (lease.state == LeaseState.STARTED) {
      if (current.get() != lease) {
        throw new ArchiveException("archive lifecycle lease is not current");
      }
      current.remove();
    }
    lease.state = LeaseState.CLOSED;
    long count = active.get(lease.type);
    if (count <= 0) {
      throw new ArchiveException("archive lifecycle active count underflow");
    }
    active.put(lease.type, count - 1);
    if (activeCountLocked() == 0) {
      drained.signalAll();
    }
  }

  private final class Lease implements ArchiveWorkLease {

    private final WorkType type;
    private final Thread owner;
    private LeaseState state = LeaseState.ACQUIRED_NOT_STARTED;

    private Lease(WorkType type, Thread owner) {
      this.type = type;
      this.owner = owner;
    }

    @Override
    public void start() {
      startLease(this);
    }

    @Override
    public void close() {
      closeLease(this);
    }

    private void requireOwner() {
      if (Thread.currentThread() != owner) {
        throw new ArchiveException("archive lifecycle lease used by a different thread");
      }
    }
  }
}
