package org.tron.core.archive.txnum;

import org.tron.core.archive.ArchiveException;

/** Bounds real archive coordinates while reserving {@link Long#MAX_VALUE} for cursors/sentinels. */
public final class ArchiveCoordinates {

  public static final long MAX_COORDINATE = Long.MAX_VALUE - 1L;

  private ArchiveCoordinates() {
  }

  public static void requireBlockNum(long blockNum, String what) {
    requireCoordinate(blockNum, what);
  }

  public static void requireTxNum(long txNum, String what) {
    requireCoordinate(txNum, what);
  }

  public static void requireCursor(long cursor, String what) {
    if (cursor < 0L) {
      throw new ArchiveException(what + " must be non-negative");
    }
  }

  public static long nextCursor(long coordinate, String what) {
    requireCoordinate(coordinate, what);
    return coordinate + 1L;
  }

  private static void requireCoordinate(long value, String what) {
    if (value < 0L) {
      throw new ArchiveException(what + " must be non-negative");
    }
    if (value > MAX_COORDINATE) {
      throw new ArchiveException(
          what + " exceeds maximum real coordinate " + MAX_COORDINATE);
    }
  }
}
