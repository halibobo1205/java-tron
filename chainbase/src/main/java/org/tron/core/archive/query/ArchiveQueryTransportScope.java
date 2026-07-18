package org.tron.core.archive.query;

import java.util.ArrayList;
import java.util.List;

/** Holds admitted query permits through JSON serialization into immutable response bytes. */
public final class ArchiveQueryTransportScope implements AutoCloseable {

  private static final ThreadLocal<ArchiveQueryTransportScope> CURRENT = new ThreadLocal<>();
  private static final Runnable NOOP_VALIDATOR = () -> { };

  private final Thread owner = Thread.currentThread();
  private final List<DeferredResponse> deferred = new ArrayList<>();
  private boolean closed;

  private ArchiveQueryTransportScope() {
  }

  public static ArchiveQueryTransportScope open() {
    if (CURRENT.get() != null) {
      throw new IllegalStateException("archive query transport scope is already active");
    }
    ArchiveQueryTransportScope scope = new ArchiveQueryTransportScope();
    try {
      CURRENT.set(scope);
      return scope;
    } catch (RuntimeException | Error failure) {
      try {
        CURRENT.remove();
      } catch (RuntimeException | Error restoreFailure) {
        addSuppressedSafely(failure, restoreFailure);
      }
      throw failure;
    }
  }

  public static void closeAfterResponse(QueryLease lease) {
    closeAfterResponse(lease, NOOP_VALIDATOR);
  }

  /** Defers the final response validator and lease until serialization completes. */
  public static void closeAfterResponse(QueryLease lease, Runnable responseValidator) {
    if (lease == null || responseValidator == null) {
      throw new NullPointerException("archive response settlement");
    }
    ArchiveQueryTransportScope scope = CURRENT.get();
    if (scope == null) {
      settle(lease, responseValidator);
      return;
    }
    scope.requireOwner();
    DeferredResponse response;
    try {
      response = new DeferredResponse(lease, responseValidator);
    } catch (RuntimeException | Error failure) {
      settleAndSuppress(lease, responseValidator, failure);
      throw failure;
    }
    try {
      scope.deferred.add(response);
    } catch (RuntimeException | Error failure) {
      try {
        response.settle();
      } catch (RuntimeException | Error closeFailure) {
        addSuppressedSafely(failure, closeFailure);
      }
      throw failure;
    }
  }

  /** Charges every archive query used by the current response for its final serialized size. */
  public static void recordSerializedResponseBytes(long bytes) {
    ArchiveQueryTransportScope scope = CURRENT.get();
    if (scope == null) {
      return;
    }
    scope.requireOwner();
    for (DeferredResponse response : scope.deferred) {
      response.recordSerializedResponseBytes(bytes);
    }
  }

  static void deferOrClose(List<QueryLease> deferred, QueryLease lease) {
    try {
      deferred.add(lease);
    } catch (RuntimeException | Error failure) {
      try {
        lease.close();
      } catch (RuntimeException | Error closeFailure) {
        addSuppressedSafely(failure, closeFailure);
      }
      throw failure;
    }
  }

  @Override
  public void close() {
    requireOwner();
    if (closed) {
      return;
    }
    if (CURRENT.get() != this) {
      throw new IllegalStateException("archive query transport scope is not current");
    }
    closed = true;
    Throwable failure = null;
    try {
      CURRENT.remove();
    } catch (RuntimeException | Error removeFailure) {
      failure = removeFailure;
    }
    for (int i = deferred.size() - 1; i >= 0; i--) {
      try {
        deferred.get(i).settle();
      } catch (RuntimeException | Error e) {
        failure = collectFailure(failure, e);
      }
    }
    rethrow(failure);
  }

  private static Throwable collectFailure(Throwable primary, Throwable candidate) {
    if (primary == null) {
      return candidate;
    }
    addSuppressedSafely(primary, candidate);
    return primary;
  }

  private static void addSuppressedSafely(Throwable primary, Throwable candidate) {
    if (primary == candidate) {
      return;
    }
    try {
      primary.addSuppressed(candidate);
    } catch (Throwable ignored) {
      // Preserve the first failure and continue settling remaining leases.
    }
  }

  private static void settleAndSuppress(
      QueryLease lease, Runnable responseValidator, Throwable failure) {
    try {
      settle(lease, responseValidator);
    } catch (RuntimeException | Error closeFailure) {
      addSuppressedSafely(failure, closeFailure);
    }
  }

  private static void rethrow(Throwable failure) {
    if (failure instanceof RuntimeException) {
      throw (RuntimeException) failure;
    }
    if (failure instanceof Error) {
      throw (Error) failure;
    }
  }

  private void requireOwner() {
    if (Thread.currentThread() != owner) {
      throw new IllegalStateException("archive query transport scope used by another thread");
    }
  }

  private static final class DeferredResponse {

    private final QueryLease lease;
    private final Runnable responseValidator;

    private DeferredResponse(QueryLease lease, Runnable responseValidator) {
      this.lease = lease;
      this.responseValidator = responseValidator;
    }

    private void settle() {
      ArchiveQueryTransportScope.settle(lease, responseValidator);
    }

    private void recordSerializedResponseBytes(long bytes) {
      lease.getContext().recordSerializedResponseBytes(bytes);
    }
  }

  private static void settle(QueryLease lease, Runnable responseValidator) {
    Throwable failure = null;
    QueryContext context = null;
    try {
      responseValidator.run();
    } catch (RuntimeException | Error e) {
      failure = e;
    }
    try {
      context = lease.getContext();
      context.checkDeadline();
    } catch (RuntimeException | Error e) {
      failure = collectFailure(failure, e);
    }
    if (failure != null && context != null) {
      try {
        context.recordFailure(failure);
      } catch (RuntimeException | Error e) {
        failure = collectFailure(failure, e);
      }
    }
    try {
      lease.close();
    } catch (RuntimeException | Error e) {
      failure = collectFailure(failure, e);
    }
    rethrow(failure);
  }
}
