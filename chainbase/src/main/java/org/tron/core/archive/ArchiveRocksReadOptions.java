package org.tron.core.archive;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import org.rocksdb.ReadOptions;

/** Shared native deadline configuration for archive-related RocksDB reads. */
public final class ArchiveRocksReadOptions {

  private static final Method SET_READ_DEADLINE = readOptionsMethod("setDeadline");
  private static final Method SET_READ_IO_TIMEOUT = readOptionsMethod("setIoTimeout");

  private ArchiveRocksReadOptions() {
  }

  public static boolean nativeDeadlineSupported() {
    return SET_READ_DEADLINE != null && SET_READ_IO_TIMEOUT != null;
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
    timeoutMicros = Math.max(1L, timeoutMicros);
    long nowMicros = TimeUnit.MILLISECONDS.toMicros(System.currentTimeMillis());
    long deadlineMicros = addSaturated(nowMicros, timeoutMicros);
    invokeReadOptions(SET_READ_DEADLINE, readOptions, deadlineMicros);
    invokeReadOptions(SET_READ_IO_TIMEOUT, readOptions, timeoutMicros);
  }

  private static Method readOptionsMethod(String name) {
    try {
      return ReadOptions.class.getMethod(name, long.class);
    } catch (NoSuchMethodException e) {
      return null;
    }
  }

  private static void invokeReadOptions(Method method, ReadOptions readOptions, long value) {
    if (method == null) {
      return;
    }
    try {
      method.invoke(readOptions, value);
    } catch (IllegalAccessException e) {
      throw new ArchiveException("cannot configure RocksDB query deadline", e);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      }
      if (cause instanceof Error) {
        throw (Error) cause;
      }
      throw new ArchiveException("cannot configure RocksDB query deadline", cause);
    }
  }

  private static long addSaturated(long left, long right) {
    return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
  }
}
