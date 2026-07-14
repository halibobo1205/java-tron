package org.tron.core.archive;

/** Immutable bounded-publisher settings. Async publication remains opt-in. */
public final class ArchivePublisherConfig {

  public static final int DEFAULT_SOFT_IN_FLIGHT_BLOCKS = 32_768;
  public static final int DEFAULT_HARD_IN_FLIGHT_BLOCKS = 65_536;
  public static final long DEFAULT_SOFT_IN_FLIGHT_BYTES = 128L * 1024 * 1024;
  public static final long DEFAULT_HARD_IN_FLIGHT_BYTES = 256L * 1024 * 1024;
  public static final long DEFAULT_SOFT_IN_FLIGHT_RECORDS = 1_000_000L;
  public static final long DEFAULT_HARD_IN_FLIGHT_RECORDS = 2_000_000L;
  public static final long DEFAULT_SOFT_MIN_FREE_BYTES = 5L * 1024 * 1024 * 1024;
  public static final long DEFAULT_HARD_MIN_FREE_BYTES = 1L * 1024 * 1024 * 1024;
  public static final long DEFAULT_BACKPRESSURE_TIMEOUT_MS = 30_000L;

  private final boolean async;
  private final boolean backpressure;
  private final int softInFlightBlocks;
  private final int hardInFlightBlocks;
  private final long softInFlightBytes;
  private final long hardInFlightBytes;
  private final long softInFlightRecords;
  private final long hardInFlightRecords;
  private final long softMinFreeBytes;
  private final long hardMinFreeBytes;
  private final long backpressureTimeoutMs;

  public ArchivePublisherConfig(boolean async, boolean backpressure, int softInFlightBlocks,
      int hardInFlightBlocks, long backpressureTimeoutMs) {
    this(async, backpressure, softInFlightBlocks, hardInFlightBlocks,
        DEFAULT_SOFT_IN_FLIGHT_BYTES, DEFAULT_HARD_IN_FLIGHT_BYTES,
        DEFAULT_SOFT_IN_FLIGHT_RECORDS, DEFAULT_HARD_IN_FLIGHT_RECORDS,
        DEFAULT_SOFT_MIN_FREE_BYTES, DEFAULT_HARD_MIN_FREE_BYTES, backpressureTimeoutMs);
  }

  public ArchivePublisherConfig(boolean async, boolean backpressure, int softInFlightBlocks,
      int hardInFlightBlocks, long softInFlightBytes, long hardInFlightBytes,
      long backpressureTimeoutMs) {
    this(async, backpressure, softInFlightBlocks, hardInFlightBlocks,
        softInFlightBytes, hardInFlightBytes,
        DEFAULT_SOFT_IN_FLIGHT_RECORDS, DEFAULT_HARD_IN_FLIGHT_RECORDS,
        DEFAULT_SOFT_MIN_FREE_BYTES, DEFAULT_HARD_MIN_FREE_BYTES, backpressureTimeoutMs);
  }

  public ArchivePublisherConfig(boolean async, boolean backpressure, int softInFlightBlocks,
      int hardInFlightBlocks, long softInFlightBytes, long hardInFlightBytes,
      long softInFlightRecords, long hardInFlightRecords, long softMinFreeBytes,
      long hardMinFreeBytes, long backpressureTimeoutMs) {
    if (softInFlightBlocks <= 0) {
      throw new IllegalArgumentException("softInFlightBlocks must be positive");
    }
    if (hardInFlightBlocks < softInFlightBlocks) {
      throw new IllegalArgumentException(
          "hardInFlightBlocks must be greater than or equal to softInFlightBlocks");
    }
    if (softInFlightBytes <= 0) {
      throw new IllegalArgumentException("softInFlightBytes must be positive");
    }
    if (hardInFlightBytes < softInFlightBytes) {
      throw new IllegalArgumentException(
          "hardInFlightBytes must be greater than or equal to softInFlightBytes");
    }
    if (softInFlightRecords <= 0) {
      throw new IllegalArgumentException("softInFlightRecords must be positive");
    }
    if (hardInFlightRecords < softInFlightRecords) {
      throw new IllegalArgumentException(
          "hardInFlightRecords must be greater than or equal to softInFlightRecords");
    }
    if (hardMinFreeBytes < 0) {
      throw new IllegalArgumentException("hardMinFreeBytes must be non-negative");
    }
    if (softMinFreeBytes < hardMinFreeBytes) {
      throw new IllegalArgumentException(
          "softMinFreeBytes must be greater than or equal to hardMinFreeBytes");
    }
    if (backpressureTimeoutMs < 0) {
      throw new IllegalArgumentException("backpressureTimeoutMs must be non-negative");
    }
    this.async = async;
    this.backpressure = backpressure;
    this.softInFlightBlocks = softInFlightBlocks;
    this.hardInFlightBlocks = hardInFlightBlocks;
    this.softInFlightBytes = softInFlightBytes;
    this.hardInFlightBytes = hardInFlightBytes;
    this.softInFlightRecords = softInFlightRecords;
    this.hardInFlightRecords = hardInFlightRecords;
    this.softMinFreeBytes = softMinFreeBytes;
    this.hardMinFreeBytes = hardMinFreeBytes;
    this.backpressureTimeoutMs = backpressureTimeoutMs;
  }

  public static ArchivePublisherConfig synchronous() {
    return new ArchivePublisherConfig(false, false, DEFAULT_SOFT_IN_FLIGHT_BLOCKS,
        DEFAULT_HARD_IN_FLIGHT_BLOCKS, DEFAULT_SOFT_IN_FLIGHT_BYTES,
        DEFAULT_HARD_IN_FLIGHT_BYTES, DEFAULT_SOFT_IN_FLIGHT_RECORDS,
        DEFAULT_HARD_IN_FLIGHT_RECORDS, DEFAULT_SOFT_MIN_FREE_BYTES,
        DEFAULT_HARD_MIN_FREE_BYTES, DEFAULT_BACKPRESSURE_TIMEOUT_MS);
  }

  public boolean isAsync() {
    return async;
  }

  public boolean isBackpressure() {
    return backpressure;
  }

  public int getSoftInFlightBlocks() {
    return softInFlightBlocks;
  }

  public int getHardInFlightBlocks() {
    return hardInFlightBlocks;
  }

  public long getSoftInFlightBytes() {
    return softInFlightBytes;
  }

  public long getHardInFlightBytes() {
    return hardInFlightBytes;
  }

  public long getSoftInFlightRecords() {
    return softInFlightRecords;
  }

  public long getHardInFlightRecords() {
    return hardInFlightRecords;
  }

  public long getSoftMinFreeBytes() {
    return softMinFreeBytes;
  }

  public long getHardMinFreeBytes() {
    return hardMinFreeBytes;
  }

  public long getBackpressureTimeoutMs() {
    return backpressureTimeoutMs;
  }
}
