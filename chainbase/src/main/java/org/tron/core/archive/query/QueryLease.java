package org.tron.core.archive.query;

/** Exactly-once ownership of one admitted historical query. */
public final class QueryLease implements AutoCloseable {

  private final ArchiveQueryCoordinator coordinator;
  private final QueryContext context;
  private boolean closeRequested;
  private boolean releaseInProgress;
  private volatile boolean released;
  private long activeSnapshots;

  QueryLease(ArchiveQueryCoordinator coordinator, QueryContext context) {
    this.coordinator = coordinator;
    this.context = context;
  }

  public QueryContext getContext() {
    return context;
  }

  public QueryContext context() {
    return context;
  }

  public synchronized boolean isClosed() {
    return closeRequested;
  }

  boolean isOwnedBy(ArchiveQueryCoordinator expected) {
    return coordinator == expected;
  }

  synchronized boolean reserveSnapshot(ArchiveSnapshotPermit permit) {
    if (closeRequested || released || activeSnapshots == Long.MAX_VALUE) {
      return false;
    }
    if (!context.reserveSnapshotPermit(permit)) {
      return false;
    }
    activeSnapshots++;
    return true;
  }

  boolean releaseSnapshotOwnership(ArchiveSnapshotPermit permit) {
    synchronized (this) {
      if (activeSnapshots <= 0) {
        throw new IllegalStateException("query snapshot ownership underflow");
      }
      context.releaseSnapshotPermit(permit);
      activeSnapshots--;
      if (!closeRequested || activeSnapshots != 0 || released) {
        return false;
      }
      released = true;
      notifyAll();
    }
    return true;
  }

  void markReleaseCommitted() {
    released = true;
  }

  @Override
  public void close() {
    boolean interrupted = false;
    boolean releaseNow;
    synchronized (this) {
      closeRequested = true;
      while (releaseInProgress) {
        try {
          wait();
        } catch (InterruptedException e) {
          interrupted = true;
        }
      }
      if (released) {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
        return;
      }
      releaseNow = activeSnapshots == 0;
      if (releaseNow) {
        releaseInProgress = true;
      }
    }
    if (releaseNow) {
      boolean releaseSucceeded = false;
      try {
        coordinator.releaseLease(this);
        releaseSucceeded = true;
      } finally {
        synchronized (this) {
          releaseInProgress = false;
          released |= releaseSucceeded;
          notifyAll();
        }
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
      return;
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
