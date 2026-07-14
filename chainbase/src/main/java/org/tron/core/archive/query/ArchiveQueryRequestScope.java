package org.tron.core.archive.query;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Applies aggregate historical-query budgets across one JSON-RPC batch request. */
public final class ArchiveQueryRequestScope implements AutoCloseable {

  private static final ThreadLocal<ArchiveQueryRequestScope> CURRENT = new ThreadLocal<>();

  private final Thread owner = Thread.currentThread();
  private final LongSupplier nanoTime;
  private final long startedNanos;
  private long admittedQueries;
  private long configuredDeadlineNanos = Long.MAX_VALUE;
  private boolean closed;

  private ArchiveQueryRequestScope(LongSupplier nanoTime) {
    this.nanoTime = nanoTime;
    this.startedNanos = nanoTime.getAsLong();
  }

  public static ArchiveQueryRequestScope open() {
    return open(System::nanoTime);
  }

  static ArchiveQueryRequestScope open(LongSupplier nanoTime) {
    if (CURRENT.get() != null) {
      throw new IllegalStateException("archive query request scope is already active");
    }
    if (nanoTime == null) {
      throw new NullPointerException("nanoTime");
    }
    ArchiveQueryRequestScope scope = new ArchiveQueryRequestScope(nanoTime);
    CURRENT.set(scope);
    return scope;
  }

  static void admit(ArchiveQueryLimits limits) {
    ArchiveQueryRequestScope scope = CURRENT.get();
    if (scope == null) {
      return;
    }
    scope.requireOwner();
    scope.registerAndCheckDeadline(limits.getBatchDeadlineMs());
    long maximum = limits.getMaxQueriesPerBatch();
    long observed = scope.admittedQueries + 1L;
    if (!ArchiveQueryLimits.isUnlimited(maximum) && observed > maximum) {
      throw HistoricalQueryLimitException.budgetExceeded(
          HistoricalQueryLimitException.Limit.BATCH_QUERIES, maximum, observed);
    }
    scope.admittedQueries = observed;
  }

  static DeadlineConstraint deadlineConstraint(ArchiveQueryLimits limits) {
    ArchiveQueryRequestScope scope = CURRENT.get();
    if (scope == null) {
      return null;
    }
    scope.requireOwner();
    scope.registerAndCheckDeadline(limits.getBatchDeadlineMs());
    if (scope.configuredDeadlineNanos == Long.MAX_VALUE) {
      return null;
    }
    long elapsedNanos = scope.elapsedNanos();
    return new DeadlineConstraint(
        scope.nanoTime, scope.configuredDeadlineNanos - elapsedNanos);
  }

  public static void checkCurrentDeadline() {
    ArchiveQueryRequestScope scope = CURRENT.get();
    if (scope == null || scope.configuredDeadlineNanos == Long.MAX_VALUE) {
      return;
    }
    scope.requireOwner();
    scope.checkDeadline();
  }

  private void registerAndCheckDeadline(long deadlineMs) {
    if (ArchiveQueryLimits.isUnlimited(deadlineMs)) {
      checkDeadline();
      return;
    }
    long deadlineNanos = TimeUnit.MILLISECONDS.toNanos(deadlineMs);
    configuredDeadlineNanos = Math.min(configuredDeadlineNanos, deadlineNanos);
    checkDeadline();
  }

  private void checkDeadline() {
    if (configuredDeadlineNanos == Long.MAX_VALUE) {
      return;
    }
    long elapsedNanos = elapsedNanos();
    if (elapsedNanos >= configuredDeadlineNanos) {
      throw HistoricalQueryLimitException.batchDeadlineExceeded(
          configuredDeadlineNanos, elapsedNanos);
    }
  }

  private long elapsedNanos() {
    return nanoTime.getAsLong() - startedNanos;
  }

  @Override
  public void close() {
    requireOwner();
    if (closed) {
      return;
    }
    closed = true;
    CURRENT.remove();
  }

  private void requireOwner() {
    if (Thread.currentThread() != owner) {
      throw new IllegalStateException("archive query request scope used by another thread");
    }
  }

  static final class DeadlineConstraint {

    final LongSupplier nanoTime;
    final long remainingNanos;

    private DeadlineConstraint(LongSupplier nanoTime, long remainingNanos) {
      this.nanoTime = nanoTime;
      this.remainingNanos = remainingNanos;
    }
  }
}
