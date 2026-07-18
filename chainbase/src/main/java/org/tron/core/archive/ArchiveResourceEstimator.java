package org.tron.core.archive;

/** Shared saturated estimates for archive capture, backlog, and publication admission. */
public final class ArchiveResourceEstimator {

  private static final long RECORD_RETAINED_OVERHEAD_BYTES = 640L;
  private static final long IN_FLIGHT_BLOCK_BASE_BYTES = 512L;
  private static final long TX_POSITION_RETAINED_BYTES = 640L;
  private static final long TEMPORAL_RECORD_OVERHEAD_BYTES = 2_048L;
  private static final long JOURNAL_RECORD_OVERHEAD_BYTES = 160L;
  private static final long RETAINED_KEY_COPIES = 2L;
  private static final long TEMPORAL_KEY_WORKING_SET = 20L;
  private static final long TEMPORAL_VALUE_WORKING_SET = 8L;

  private ArchiveResourceEstimator() {
  }

  /** Per-block list/index baseline retained from beginBlock until journal ownership transfer. */
  public static long estimatedInFlightBlockBaseBytes() {
    return IN_FLIGHT_BLOCK_BASE_BYTES;
  }

  /** One tx-position plus pending and committed execution-index lookup entries. */
  public static long estimatedTxPositionRetainedBytes() {
    return TX_POSITION_RETAINED_BYTES;
  }

  public static long estimatedPositionSetRetainedBytes(int positionCount) {
    if (positionCount < 0) {
      throw new IllegalArgumentException("archive position count must be non-negative");
    }
    return addSaturated(IN_FLIGHT_BLOCK_BASE_BYTES,
        multiplySaturated(positionCount, TX_POSITION_RETAINED_BYTES));
  }

  /** In-memory record plus the keyed lookup entries retained until solidification. */
  public static long estimatedRecordRetainedBytes(
      int keyBytes, int prevValueBytes, int valueBytes) {
    requireNonNegative(keyBytes, prevValueBytes, valueBytes);
    long bytes = addSaturated(
        RECORD_RETAINED_OVERHEAD_BYTES, multiplySaturated(keyBytes, RETAINED_KEY_COPIES));
    return addSaturated(bytes, addSaturated(prevValueBytes, valueBytes));
  }

  /** Java/native working set reserved before temporal publication prepares any value copies. */
  public static long estimatedTemporalPreparationBytes(
      int keyBytes, int prevValueBytes, int valueBytes) {
    requireNonNegative(keyBytes, prevValueBytes, valueBytes);
    long keyWorkingSet = multiplySaturated(keyBytes, TEMPORAL_KEY_WORKING_SET);
    long valueWorkingSet = multiplySaturated(
        addSaturated(prevValueBytes, valueBytes), TEMPORAL_VALUE_WORKING_SET);
    return addSaturated(
        TEMPORAL_RECORD_OVERHEAD_BYTES, addSaturated(keyWorkingSet, valueWorkingSet));
  }

  /**
   * Pre-normalization gate for one raw capture. It reserves the retained record, temporal staging,
   * and its encoded journal contribution before a codec parses or copies the supplied value.
   */
  public static long estimatedRawRecordPipelineBytes(
      int keyBytes, int prevValueBytes, int valueBytes) {
    long retained = estimatedRecordRetainedBytes(keyBytes, prevValueBytes, valueBytes);
    long temporal = estimatedTemporalPreparationBytes(keyBytes, prevValueBytes, valueBytes);
    long journal = addSaturated(
        JOURNAL_RECORD_OVERHEAD_BYTES,
        addSaturated(keyBytes, addSaturated(prevValueBytes, valueBytes)));
    return addSaturated(retained, addSaturated(temporal, journal));
  }

  public static long addSaturated(long left, long right) {
    if (left < 0L || right < 0L) {
      throw new IllegalArgumentException("archive resource values must be non-negative");
    }
    return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
  }

  public static long multiplySaturated(long left, long right) {
    if (left < 0L || right < 0L) {
      throw new IllegalArgumentException("archive resource values must be non-negative");
    }
    return left != 0L && right > Long.MAX_VALUE / left ? Long.MAX_VALUE : left * right;
  }

  private static void requireNonNegative(int keyBytes, int prevValueBytes, int valueBytes) {
    if (keyBytes < 0 || prevValueBytes < 0 || valueBytes < 0) {
      throw new IllegalArgumentException("archive record byte sizes must be non-negative");
    }
  }
}
