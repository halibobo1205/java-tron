package org.tron.core.archive.query;

import java.util.concurrent.atomic.AtomicBoolean;

/** Exactly-once ownership of one native archive snapshot slot. */
public final class ArchiveSnapshotPermit implements AutoCloseable {

  private final ArchiveQueryCoordinator coordinator;
  private final QueryLease owner;
  private final AtomicBoolean closed = new AtomicBoolean();

  ArchiveSnapshotPermit(ArchiveQueryCoordinator coordinator, QueryLease owner) {
    this.coordinator = coordinator;
    this.owner = owner;
  }

  public boolean isClosed() {
    return closed.get();
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      coordinator.releaseSnapshot(owner);
    }
  }
}
