package org.tron.core.archive;

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
    return new Lease(readLock, epoch, false, Thread.currentThread());
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

  public void requireHeldByCurrentThread() {
    if (!lock.isWriteLockedByCurrentThread() && lock.getReadHoldCount() == 0) {
      throw new ArchiveException("archive mutation lease is required");
    }
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
