package org.tron.core.archive.query;

/** Exactly-once ownership of one native archive snapshot slot. */
public final class ArchiveSnapshotPermit implements AutoCloseable {

  private final ArchiveQueryCoordinator coordinator;
  private final QueryLease owner;
  private boolean closeInProgress;
  private boolean useClaimed;
  private boolean retained;
  private volatile boolean closed;

  ArchiveSnapshotPermit(ArchiveQueryCoordinator coordinator, QueryLease owner) {
    this.coordinator = coordinator;
    this.owner = owner;
  }

  public synchronized boolean isClosed() {
    return closed;
  }

  public synchronized boolean isRetained() {
    return retained;
  }

  public synchronized boolean isInUse() {
    return useClaimed;
  }

  synchronized SnapshotUse tryClaimUse() {
    if (closed || retained || closeInProgress || useClaimed) {
      return null;
    }
    SnapshotUse use = new SnapshotUse(this);
    useClaimed = true;
    return use;
  }

  /** Keeps the slot and owner lease pinned when native snapshot ownership is unknowable. */
  public void retainAfterUncertainRelease() {
    boolean interrupted = false;
    synchronized (this) {
      while (closeInProgress) {
        try {
          wait();
        } catch (InterruptedException e) {
          interrupted = true;
        }
      }
      if (!closed) {
        retained = true;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  void markReleaseCommitted() {
    closed = true;
  }

  @Override
  public void close() {
    boolean interrupted = false;
    synchronized (this) {
      while (closeInProgress) {
        try {
          wait();
        } catch (InterruptedException e) {
          interrupted = true;
        }
      }
      if (closed) {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
        return;
      }
      if (retained) {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
        return;
      }
      if (useClaimed) {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
        throw new IllegalStateException("archive snapshot permit is in use");
      }
      closeInProgress = true;
    }

    boolean released = false;
    try {
      coordinator.releaseSnapshot(this, owner);
      released = true;
    } finally {
      synchronized (this) {
        closed |= released;
        closeInProgress = false;
        notifyAll();
      }
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private synchronized void releaseUse(SnapshotUse use) {
    if (use == null || use.owner != this || !useClaimed) {
      throw new IllegalStateException("archive snapshot use ownership mismatch");
    }
    useClaimed = false;
  }

  /** Exactly-once claim binding one coordinator slot to one native query read view. */
  public static final class SnapshotUse implements AutoCloseable {

    private final ArchiveSnapshotPermit owner;
    private boolean closed;

    private SnapshotUse(ArchiveSnapshotPermit owner) {
      this.owner = owner;
    }

    public synchronized boolean isClosed() {
      return closed;
    }

    @Override
    public synchronized void close() {
      if (closed) {
        return;
      }
      owner.releaseUse(this);
      closed = true;
    }
  }
}
