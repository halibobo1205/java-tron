package org.tron.core.archive;

import java.util.concurrent.TimeUnit;
import org.rocksdb.ReadOptions;
import org.tron.common.math.StrictMathWrapper;

/** Shared native deadline configuration for archive-related RocksDB reads. */
public final class ArchiveRocksReadOptions {

  private ArchiveRocksReadOptions() {
  }

  public static boolean nativeDeadlineSupported() {
    return true;
  }

  /** Applies the remaining monotonic query budget to one native RocksDB read scope. */
  public static void configureNativeDeadline(ReadOptions readOptions, long remainingNanos) {
    if (readOptions == null) {
      throw new NullPointerException("readOptions");
    }
    if (remainingNanos < 0L) {
      throw new IllegalArgumentException("remaining query time must be non-negative");
    }
    if (remainingNanos == Long.MAX_VALUE) {
      return;
    }
    long timeoutMicros = TimeUnit.NANOSECONDS.toMicros(remainingNanos);
    if (remainingNanos % 1_000L != 0L && timeoutMicros != Long.MAX_VALUE) {
      timeoutMicros++;
    }
    timeoutMicros = StrictMathWrapper.max(1L, timeoutMicros);
    long nowMicros = TimeUnit.MILLISECONDS.toMicros(System.currentTimeMillis());
    long deadlineMicros = addSaturated(nowMicros, timeoutMicros);
    readOptions.setDeadline(deadlineMicros);
    readOptions.setIoTimeout(timeoutMicros);
  }

  private static long addSaturated(long left, long right) {
    return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
  }
}
