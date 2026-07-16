package org.tron.core.archive;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

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
  private final ThreadLocal<Lease> current = new ThreadLocal<>();
  private Phase phase;

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

  public void completeRecovery() {
    completeRecovery(() -> {
    });
  }

  /** Publishes startup component gates and RUNNING under the same lifecycle mutex. */
  public void completeRecovery(Runnable activation) {
    if (activation == null) {
      throw new NullPointerException("activation");
    }
    lock.lock();
    try {
      Lease lease = current.get();
      if (lease == null || lease.type != WorkType.RECOVERY
          || lease.state != LeaseState.STARTED) {
        throw new ArchiveException("archive recovery completion requires the active lease");
      }
      requireNoFatal();
      if (phase != Phase.RECOVERING) {
        throw new ArchiveException("archive recovery cannot complete from phase " + phase);
      }
      activation.run();
      phase = Phase.RUNNING;
      closeLeaseLocked(lease);
    } finally {
      lock.unlock();
    }
  }

  public boolean markFatal(RuntimeException failure) {
    if (failure == null) {
      throw new NullPointerException("failure");
    }
    boolean first = fatal.compareAndSet(null, failure);
    if (first) {
      lock.lock();
      try {
        drained.signalAll();
      } finally {
        lock.unlock();
      }
    }
    return first;
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

  public void beginDrain() {
    lock.lock();
    try {
      if (phase != Phase.CLOSED) {
        phase = Phase.DRAINING;
      }
      drained.signalAll();
    } finally {
      lock.unlock();
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
    lock.lockInterruptibly();
    try {
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
    lock.lock();
    try {
      return phase;
    } finally {
      lock.unlock();
    }
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
      lease.state = LeaseState.STARTED;
      current.set(lease);
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
