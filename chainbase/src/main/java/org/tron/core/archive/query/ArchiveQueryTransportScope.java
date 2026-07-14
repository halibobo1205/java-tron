package org.tron.core.archive.query;

import java.util.ArrayList;
import java.util.List;

/** Holds admitted query permits through JSON serialization into immutable response bytes. */
public final class ArchiveQueryTransportScope implements AutoCloseable {

  private static final ThreadLocal<ArchiveQueryTransportScope> CURRENT = new ThreadLocal<>();

  private final Thread owner = Thread.currentThread();
  private final List<QueryLease> deferred = new ArrayList<>();
  private boolean closed;

  private ArchiveQueryTransportScope() {
  }

  public static ArchiveQueryTransportScope open() {
    if (CURRENT.get() != null) {
      throw new IllegalStateException("archive query transport scope is already active");
    }
    ArchiveQueryTransportScope scope = new ArchiveQueryTransportScope();
    CURRENT.set(scope);
    return scope;
  }

  public static void closeAfterResponse(QueryLease lease) {
    ArchiveQueryTransportScope scope = CURRENT.get();
    if (scope == null) {
      lease.close();
      return;
    }
    scope.requireOwner();
    scope.deferred.add(lease);
  }

  @Override
  public void close() {
    requireOwner();
    if (closed) {
      return;
    }
    closed = true;
    CURRENT.remove();
    RuntimeException failure = null;
    for (int i = deferred.size() - 1; i >= 0; i--) {
      try {
        deferred.get(i).close();
      } catch (RuntimeException e) {
        if (failure == null) {
          failure = e;
        } else {
          failure.addSuppressed(e);
        }
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  private void requireOwner() {
    if (Thread.currentThread() != owner) {
      throw new IllegalStateException("archive query transport scope used by another thread");
    }
  }
}
