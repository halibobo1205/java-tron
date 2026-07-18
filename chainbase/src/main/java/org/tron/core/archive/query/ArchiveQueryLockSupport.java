package org.tron.core.archive.query;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

/** Deadline-aware lock admission for storage operations serving historical queries. */
public final class ArchiveQueryLockSupport {

  private ArchiveQueryLockSupport() {
  }

  public static void lock(Lock lock, QueryContext context, String what) {
    if (lock == null) {
      throw new NullPointerException("lock");
    }
    if (context == null) {
      lock.lock();
      return;
    }
    context.checkDeadline();
    boolean acquired;
    try {
      long remainingNanos = context.getRemainingNanos();
      if (remainingNanos == Long.MAX_VALUE) {
        lock.lockInterruptibly();
        acquired = true;
      } else {
        acquired = lock.tryLock(remainingNanos, TimeUnit.NANOSECONDS);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HistoricalQueryLimitException(
          HistoricalQueryLimitException.Reason.RESOURCE_EXHAUSTED,
          HistoricalQueryLimitException.Limit.INTERRUPTED,
          ArchiveQueryLimits.UNLIMITED,
          ArchiveQueryLimits.UNLIMITED,
          "interrupted while waiting for " + what, e);
    }
    if (!acquired) {
      context.checkDeadline();
      throw new HistoricalQueryLimitException(
          HistoricalQueryLimitException.Reason.DEADLINE,
          HistoricalQueryLimitException.Limit.DEADLINE,
          "historical query deadline exceeded while waiting for " + what);
    }
    try {
      context.checkDeadline();
    } catch (RuntimeException | Error failure) {
      lock.unlock();
      throw failure;
    }
  }
}
