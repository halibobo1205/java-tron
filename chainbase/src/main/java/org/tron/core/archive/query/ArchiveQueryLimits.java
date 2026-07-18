package org.tron.core.archive.query;

import java.util.Objects;

/**
 * Immutable admission and per-request limits for historical archive queries. A value of
 * {@link #UNLIMITED} disables enforcement for that dimension while retaining accounting.
 */
public final class ArchiveQueryLimits {

  public static final long UNLIMITED = -1L;
  public static final int DEFAULT_MAX_CACHED_ENTRIES = 4_096;
  public static final long DEFAULT_MAX_CACHED_BYTES = 4L * 1024 * 1024;

  private static final ArchiveQueryLimits UNLIMITED_LIMITS = new Builder().build();

  private final long maxConcurrentQueries;
  private final long maxPendingQueries;
  private final long maxOpenSnapshots;
  private final long acquireTimeoutMs;
  private final long deadlineMs;
  private final long maxQueriesPerBatch;
  private final long batchDeadlineMs;
  private final long maxLogicalReadsPerRequest;
  private final long maxBackendReadsPerRequest;
  private final long maxBackendValueBytes;
  private final long maxBackendReadBytesPerRequest;
  private final int maxCachedEntries;
  private final long maxCachedBytes;
  private final long maxVmSteps;
  private final long maxVmOverlayBytes;
  private final long maxResponseBytes;

  /** Creates the compatibility default: unlimited resources with fail-fast permit acquisition. */
  public ArchiveQueryLimits() {
    this(new Builder());
  }

  private ArchiveQueryLimits(Builder builder) {
    maxConcurrentQueries = requirePositiveOrUnlimited(
        "maxConcurrentQueries", builder.maxConcurrentQueries);
    maxPendingQueries = requireNonNegativeOrUnlimited(
        "maxPendingQueries", builder.maxPendingQueries);
    maxOpenSnapshots = requireNonNegativeOrUnlimited(
        "maxOpenSnapshots", builder.maxOpenSnapshots);
    acquireTimeoutMs = requireNonNegativeOrUnlimited(
        "acquireTimeoutMs", builder.acquireTimeoutMs);
    deadlineMs = requireNonNegativeOrUnlimited("deadlineMs", builder.deadlineMs);
    maxQueriesPerBatch = requirePositiveOrUnlimited(
        "maxQueriesPerBatch", builder.maxQueriesPerBatch);
    batchDeadlineMs = requireNonNegativeOrUnlimited(
        "batchDeadlineMs", builder.batchDeadlineMs);
    maxLogicalReadsPerRequest = requireNonNegativeOrUnlimited(
        "maxLogicalReadsPerRequest", builder.maxLogicalReadsPerRequest);
    maxBackendReadsPerRequest = requireNonNegativeOrUnlimited(
        "maxBackendReadsPerRequest", builder.maxBackendReadsPerRequest);
    maxBackendValueBytes = requireNonNegativeOrUnlimited(
        "maxBackendValueBytes", builder.maxBackendValueBytes);
    maxBackendReadBytesPerRequest = requireNonNegativeOrUnlimited(
        "maxBackendReadBytesPerRequest", builder.maxBackendReadBytesPerRequest);
    maxCachedEntries = requireNonNegative("maxCachedEntries", builder.maxCachedEntries);
    maxCachedBytes = requireNonNegative("maxCachedBytes", builder.maxCachedBytes);
    maxVmSteps = requireNonNegativeOrUnlimited("maxVmSteps", builder.maxVmSteps);
    maxVmOverlayBytes = requireNonNegativeOrUnlimited(
        "maxVmOverlayBytes", builder.maxVmOverlayBytes);
    maxResponseBytes = requireNonNegativeOrUnlimited(
        "maxResponseBytes", builder.maxResponseBytes);
  }

  public static ArchiveQueryLimits unlimited() {
    return UNLIMITED_LIMITS;
  }

  public static Builder builder() {
    return new Builder();
  }

  public Builder toBuilder() {
    return new Builder(this);
  }

  public long getMaxConcurrentQueries() {
    return maxConcurrentQueries;
  }

  public long getMaxPendingQueries() {
    return maxPendingQueries;
  }

  public long getMaxOpenSnapshots() {
    return maxOpenSnapshots;
  }

  public long getAcquireTimeoutMs() {
    return acquireTimeoutMs;
  }

  public long getDeadlineMs() {
    return deadlineMs;
  }

  public long getMaxQueriesPerBatch() {
    return maxQueriesPerBatch;
  }

  public long getBatchDeadlineMs() {
    return batchDeadlineMs;
  }

  public long getMaxLogicalReadsPerRequest() {
    return maxLogicalReadsPerRequest;
  }

  public long getMaxBackendReadsPerRequest() {
    return maxBackendReadsPerRequest;
  }

  public long getMaxBackendValueBytes() {
    return maxBackendValueBytes;
  }

  public long getMaxBackendReadBytesPerRequest() {
    return maxBackendReadBytesPerRequest;
  }

  public int getMaxCachedEntries() {
    return maxCachedEntries;
  }

  public long getMaxCachedBytes() {
    return maxCachedBytes;
  }

  public long getMaxVmSteps() {
    return maxVmSteps;
  }

  public long getMaxVmOverlayBytes() {
    return maxVmOverlayBytes;
  }

  public long getMaxResponseBytes() {
    return maxResponseBytes;
  }

  public static boolean isUnlimited(long value) {
    return value == UNLIMITED;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ArchiveQueryLimits)) {
      return false;
    }
    ArchiveQueryLimits that = (ArchiveQueryLimits) other;
    return maxConcurrentQueries == that.maxConcurrentQueries
        && maxPendingQueries == that.maxPendingQueries
        && maxOpenSnapshots == that.maxOpenSnapshots
        && acquireTimeoutMs == that.acquireTimeoutMs
        && deadlineMs == that.deadlineMs
        && maxQueriesPerBatch == that.maxQueriesPerBatch
        && batchDeadlineMs == that.batchDeadlineMs
        && maxLogicalReadsPerRequest == that.maxLogicalReadsPerRequest
        && maxBackendReadsPerRequest == that.maxBackendReadsPerRequest
        && maxBackendValueBytes == that.maxBackendValueBytes
        && maxBackendReadBytesPerRequest == that.maxBackendReadBytesPerRequest
        && maxCachedEntries == that.maxCachedEntries
        && maxCachedBytes == that.maxCachedBytes
        && maxVmSteps == that.maxVmSteps
        && maxVmOverlayBytes == that.maxVmOverlayBytes
        && maxResponseBytes == that.maxResponseBytes;
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        maxConcurrentQueries,
        maxPendingQueries,
        maxOpenSnapshots,
        acquireTimeoutMs,
        deadlineMs,
        maxQueriesPerBatch,
        batchDeadlineMs,
        maxLogicalReadsPerRequest,
        maxBackendReadsPerRequest,
        maxBackendValueBytes,
        maxBackendReadBytesPerRequest,
        maxCachedEntries,
        maxCachedBytes,
        maxVmSteps,
        maxVmOverlayBytes,
        maxResponseBytes);
  }

  @Override
  public String toString() {
    return "ArchiveQueryLimits{"
        + "maxConcurrentQueries=" + maxConcurrentQueries
        + ", maxPendingQueries=" + maxPendingQueries
        + ", maxOpenSnapshots=" + maxOpenSnapshots
        + ", acquireTimeoutMs=" + acquireTimeoutMs
        + ", deadlineMs=" + deadlineMs
        + ", maxQueriesPerBatch=" + maxQueriesPerBatch
        + ", batchDeadlineMs=" + batchDeadlineMs
        + ", maxLogicalReadsPerRequest=" + maxLogicalReadsPerRequest
        + ", maxBackendReadsPerRequest=" + maxBackendReadsPerRequest
        + ", maxBackendValueBytes=" + maxBackendValueBytes
        + ", maxBackendReadBytesPerRequest=" + maxBackendReadBytesPerRequest
        + ", maxCachedEntries=" + maxCachedEntries
        + ", maxCachedBytes=" + maxCachedBytes
        + ", maxVmSteps=" + maxVmSteps
        + ", maxVmOverlayBytes=" + maxVmOverlayBytes
        + ", maxResponseBytes=" + maxResponseBytes
        + '}';
  }

  private static long requirePositiveOrUnlimited(String name, long value) {
    if (value != UNLIMITED && value <= 0) {
      throw new IllegalArgumentException(name + " must be positive or UNLIMITED");
    }
    return value;
  }

  private static long requireNonNegativeOrUnlimited(String name, long value) {
    if (value < 0 && value != UNLIMITED) {
      throw new IllegalArgumentException(name + " must be non-negative or UNLIMITED");
    }
    return value;
  }

  private static int requireNonNegative(String name, int value) {
    if (value < 0) {
      throw new IllegalArgumentException(name + " must be non-negative");
    }
    return value;
  }

  private static long requireNonNegative(String name, long value) {
    if (value < 0) {
      throw new IllegalArgumentException(name + " must be non-negative");
    }
    return value;
  }

  /** Builder whose resource defaults preserve pre-budget behavior; permit acquisition is fail-fast. */
  public static final class Builder {

    private long maxConcurrentQueries = UNLIMITED;
    private long maxPendingQueries = UNLIMITED;
    private long maxOpenSnapshots = UNLIMITED;
    private long acquireTimeoutMs;
    private long deadlineMs = UNLIMITED;
    private long maxQueriesPerBatch = UNLIMITED;
    private long batchDeadlineMs = UNLIMITED;
    private long maxLogicalReadsPerRequest = UNLIMITED;
    private long maxBackendReadsPerRequest = UNLIMITED;
    private long maxBackendValueBytes = UNLIMITED;
    private long maxBackendReadBytesPerRequest = UNLIMITED;
    private int maxCachedEntries = DEFAULT_MAX_CACHED_ENTRIES;
    private long maxCachedBytes = DEFAULT_MAX_CACHED_BYTES;
    private long maxVmSteps = UNLIMITED;
    private long maxVmOverlayBytes = UNLIMITED;
    private long maxResponseBytes = UNLIMITED;

    public Builder() {
    }

    private Builder(ArchiveQueryLimits limits) {
      maxConcurrentQueries = limits.maxConcurrentQueries;
      maxPendingQueries = limits.maxPendingQueries;
      maxOpenSnapshots = limits.maxOpenSnapshots;
      acquireTimeoutMs = limits.acquireTimeoutMs;
      deadlineMs = limits.deadlineMs;
      maxQueriesPerBatch = limits.maxQueriesPerBatch;
      batchDeadlineMs = limits.batchDeadlineMs;
      maxLogicalReadsPerRequest = limits.maxLogicalReadsPerRequest;
      maxBackendReadsPerRequest = limits.maxBackendReadsPerRequest;
      maxBackendValueBytes = limits.maxBackendValueBytes;
      maxBackendReadBytesPerRequest = limits.maxBackendReadBytesPerRequest;
      maxCachedEntries = limits.maxCachedEntries;
      maxCachedBytes = limits.maxCachedBytes;
      maxVmSteps = limits.maxVmSteps;
      maxVmOverlayBytes = limits.maxVmOverlayBytes;
      maxResponseBytes = limits.maxResponseBytes;
    }

    public Builder maxConcurrentQueries(long value) {
      maxConcurrentQueries = value;
      return this;
    }

    public Builder maxPendingQueries(long value) {
      maxPendingQueries = value;
      return this;
    }

    public Builder maxOpenSnapshots(long value) {
      maxOpenSnapshots = value;
      return this;
    }

    public Builder acquireTimeoutMs(long value) {
      acquireTimeoutMs = value;
      return this;
    }

    public Builder deadlineMs(long value) {
      deadlineMs = value;
      return this;
    }

    public Builder maxQueriesPerBatch(long value) {
      maxQueriesPerBatch = value;
      return this;
    }

    public Builder batchDeadlineMs(long value) {
      batchDeadlineMs = value;
      return this;
    }

    public Builder maxLogicalReadsPerRequest(long value) {
      maxLogicalReadsPerRequest = value;
      return this;
    }

    public Builder maxBackendReadsPerRequest(long value) {
      maxBackendReadsPerRequest = value;
      return this;
    }

    public Builder maxBackendValueBytes(long value) {
      maxBackendValueBytes = value;
      return this;
    }

    public Builder maxBackendReadBytesPerRequest(long value) {
      maxBackendReadBytesPerRequest = value;
      return this;
    }

    public Builder maxCachedEntries(int value) {
      maxCachedEntries = value;
      return this;
    }

    public Builder maxCachedBytes(long value) {
      maxCachedBytes = value;
      return this;
    }

    public Builder maxVmSteps(long value) {
      maxVmSteps = value;
      return this;
    }

    public Builder maxVmOverlayBytes(long value) {
      maxVmOverlayBytes = value;
      return this;
    }

    public Builder maxResponseBytes(long value) {
      maxResponseBytes = value;
      return this;
    }

    public ArchiveQueryLimits build() {
      return new ArchiveQueryLimits(this);
    }
  }
}
