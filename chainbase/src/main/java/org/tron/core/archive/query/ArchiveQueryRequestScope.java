package org.tron.core.archive.query;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.tron.common.math.StrictMathWrapper;

/** Applies aggregate historical-query budgets across one JSON-RPC batch request. */
public final class ArchiveQueryRequestScope implements AutoCloseable {

  private static final ThreadLocal<ArchiveQueryRequestScope> CURRENT = new ThreadLocal<>();

  private final Thread owner = Thread.currentThread();
  private final LongSupplier nanoTime;
  private final long startedNanos;
  private long admittedQueries;
  private long configuredDeadlineNanos = Long.MAX_VALUE;
  private boolean deadlineConfigured;
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
    try {
      CURRENT.set(scope);
      return scope;
    } catch (RuntimeException | Error failure) {
      try {
        CURRENT.remove();
      } catch (RuntimeException | Error restoreFailure) {
        if (failure != restoreFailure) {
          failure.addSuppressed(restoreFailure);
        }
      }
      throw failure;
    }
  }

  static void admit(ArchiveQueryLimits limits) {
    ArchiveQueryRequestScope scope = CURRENT.get();
    if (scope == null) {
      return;
    }
    scope.requireOwner();
    scope.registerAndCheckDeadline(limits.getBatchDeadlineMs());
    long maximum = limits.getMaxQueriesPerBatch();
    long observed = scope.admittedQueries == Long.MAX_VALUE
        ? Long.MAX_VALUE : scope.admittedQueries + 1L;
    if (!ArchiveQueryLimits.isUnlimited(maximum) && scope.admittedQueries >= maximum) {
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
    long sampledNanos = scope.nanoTime.getAsLong();
    long remainingNanos = scope.registerAndRemainingNanos(
        limits.getBatchDeadlineMs(), sampledNanos);
    if (!scope.deadlineConfigured) {
      return null;
    }
    return new DeadlineConstraint(scope.nanoTime, sampledNanos, remainingNanos);
  }

  public static void checkCurrentDeadline() {
    ArchiveQueryRequestScope scope = CURRENT.get();
    if (scope == null || !scope.deadlineConfigured) {
      return;
    }
    scope.requireOwner();
    scope.checkDeadline();
  }

  private void registerAndCheckDeadline(long deadlineMs) {
    registerAndRemainingNanos(deadlineMs);
  }

  private long registerAndRemainingNanos(long deadlineMs) {
    return registerAndRemainingNanos(deadlineMs, nanoTime.getAsLong());
  }

  private long registerAndRemainingNanos(long deadlineMs, long sampledNanos) {
    if (ArchiveQueryLimits.isUnlimited(deadlineMs)) {
      return remainingNanos(sampledNanos - startedNanos);
    }
    long deadlineNanos = TimeUnit.MILLISECONDS.toNanos(deadlineMs);
    deadlineConfigured = true;
    configuredDeadlineNanos = StrictMathWrapper.min(configuredDeadlineNanos, deadlineNanos);
    return remainingNanos(sampledNanos - startedNanos);
  }

  private void checkDeadline() {
    if (!deadlineConfigured) {
      return;
    }
    remainingNanos(elapsedNanos());
  }

  private long remainingNanos(long elapsedNanos) {
    if (!deadlineConfigured) {
      return Long.MAX_VALUE;
    }
    if (elapsedNanos >= configuredDeadlineNanos) {
      throw HistoricalQueryLimitException.batchDeadlineExceeded(
          configuredDeadlineNanos, elapsedNanos);
    }
    return configuredDeadlineNanos - elapsedNanos;
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
    if (CURRENT.get() != this) {
      throw new IllegalStateException("archive query request scope is not current");
    }
    CURRENT.remove();
    closed = true;
  }

  private void requireOwner() {
    if (Thread.currentThread() != owner) {
      throw new IllegalStateException("archive query request scope used by another thread");
    }
  }

  static final class DeadlineConstraint {

    final LongSupplier nanoTime;
    final long sampledNanos;
    final long remainingNanos;

    private DeadlineConstraint(LongSupplier nanoTime, long sampledNanos, long remainingNanos) {
      this.nanoTime = nanoTime;
      this.sampledNanos = sampledNanos;
      this.remainingNanos = remainingNanos;
    }
  }
}
