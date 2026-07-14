package org.tron.core.archive.query;

/** Exactly-once ownership of one admitted historical query. */
public final class QueryLease implements AutoCloseable {

  private final ArchiveQueryCoordinator coordinator;
  private final QueryContext context;
  private boolean closeRequested;
  private boolean released;
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

  synchronized boolean reserveSnapshot() {
    if (closeRequested || released || activeSnapshots == Long.MAX_VALUE) {
      return false;
    }
    activeSnapshots++;
    return true;
  }

  boolean releaseSnapshotOwnership() {
    synchronized (this) {
      if (activeSnapshots <= 0) {
        throw new IllegalStateException("query snapshot ownership underflow");
      }
      activeSnapshots--;
      if (!closeRequested || activeSnapshots != 0 || released) {
        return false;
      }
      released = true;
    }
    return true;
  }

  @Override
  public void close() {
    boolean releaseNow;
    synchronized (this) {
      if (closeRequested) {
        return;
      }
      closeRequested = true;
      releaseNow = activeSnapshots == 0;
      if (releaseNow) {
        released = true;
      }
    }
    if (releaseNow) {
      coordinator.releaseLease(context);
    }
  }

  void releaseAfterLastSnapshot() {
    coordinator.releaseLease(context);
  }
}
