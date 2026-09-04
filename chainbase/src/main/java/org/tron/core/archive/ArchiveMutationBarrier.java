package org.tron.core.archive;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Fair mutation barrier between ordinary block append/publish work and fork or startup recovery.
 * Every exclusive acquisition advances the canonical epoch before any target can be published.
 */
public final class ArchiveMutationBarrier {

  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
  private long epoch;

  public ArchiveMutationLease acquireShared() {
    Lock readLock = lock.readLock();
    readLock.lock();
    boolean success = false;
    try {
      Lease lease = new Lease(readLock, epoch, false, Thread.currentThread());
      success = true;
      return lease;
    } finally {
      if (!success) {
        readLock.unlock();
      }
    }
  }

  /** Returns {@code null} when the shared lease cannot be acquired before the timeout. */
  public ArchiveMutationLease acquireSharedInterruptibly(long timeoutNanos)
      throws InterruptedException {
    if (timeoutNanos < 0L) {
      throw new IllegalArgumentException("archive mutation timeout must be non-negative");
    }
    Lock readLock = lock.readLock();
    boolean acquired;
    if (timeoutNanos == Long.MAX_VALUE) {
      readLock.lockInterruptibly();
      acquired = true;
    } else {
      acquired = readLock.tryLock(timeoutNanos, TimeUnit.NANOSECONDS);
    }
    if (!acquired) {
      return null;
    }
    boolean success = false;
    try {
      Lease lease = new Lease(readLock, epoch, false, Thread.currentThread());
      success = true;
      return lease;
    } finally {
      if (!success) {
        readLock.unlock();
      }
    }
  }

  public ArchiveMutationLease acquireExclusive() {
    Lock writeLock = lock.writeLock();
    writeLock.lock();
    boolean success = false;
    try {
      if (epoch == Long.MAX_VALUE) {
        throw new ArchiveException("archive canonical epoch overflow");
      }
      epoch++;
      Lease lease = new Lease(writeLock, epoch, true, Thread.currentThread());
      success = true;
      return lease;
    } finally {
      if (!success) {
        writeLock.unlock();
      }
    }
  }

  public long getEpoch() {
    Lock readLock = lock.readLock();
    readLock.lock();
    try {
      return epoch;
    } finally {
      readLock.unlock();
    }
  }

  /**
   * Linearizes a historical response after its snapshot has been consumed. A queued or active
   * exclusive mutation wins first under the fair lock, then makes the stale response fail closed.
   */
  public void requireEpoch(long expectedEpoch) {
    Lock readLock = lock.readLock();
    readLock.lock();
    try {
      requireEpochValue(expectedEpoch);
    } finally {
      readLock.unlock();
    }
  }

  /** Returns {@code false} when the epoch cannot be checked before the supplied timeout. */
  public boolean requireEpochInterruptibly(long expectedEpoch, long timeoutNanos)
      throws InterruptedException {
    ArchiveMutationLease lease = acquireSharedInterruptibly(timeoutNanos);
    if (lease == null) {
      return false;
    }
    try (ArchiveMutationLease ignored = lease) {
      requireEpochValue(expectedEpoch);
      return true;
    }
  }

  private void requireEpochValue(long expectedEpoch) {
    if (epoch != expectedEpoch) {
      throw new ArchiveSnapshotInvalidatedException(
          "archive historical snapshot was invalidated by canonical mutation");
    }
  }

  public void requireHeldByCurrentThread() {
    if (!lock.isWriteLockedByCurrentThread() && lock.getReadHoldCount() == 0) {
      throw new ArchiveException("archive mutation lease is required");
    }
  }

  public void requireExclusiveHeldByCurrentThread() {
    if (!lock.isWriteLockedByCurrentThread()) {
      throw new ArchiveException("exclusive archive mutation lease is required");
    }
  }

  public boolean isExclusiveHeldByCurrentThread() {
    return lock.isWriteLockedByCurrentThread();
  }

  private static final class Lease implements ArchiveMutationLease {

    private final Lock lock;
    private final long epoch;
    private final boolean exclusive;
    private final Thread owner;
    private boolean closed;

    private Lease(Lock lock, long epoch, boolean exclusive, Thread owner) {
      this.lock = lock;
      this.epoch = epoch;
      this.exclusive = exclusive;
      this.owner = owner;
    }

    @Override
    public long getEpoch() {
      requireOpen();
      return epoch;
    }

    @Override
    public boolean isExclusive() {
      requireOpen();
      return exclusive;
    }

    @Override
    public void close() {
      requireOwner();
      if (closed) {
        return;
      }
      closed = true;
      lock.unlock();
    }

    private void requireOpen() {
      requireOwner();
      if (closed) {
        throw new ArchiveException("archive mutation lease is closed");
      }
    }

    private void requireOwner() {
      if (Thread.currentThread() != owner) {
        throw new ArchiveException("archive mutation lease used by a different thread");
      }
    }
  }
}
