package org.tron.core.archive.reader;

/**
 * Output of resolving a JSON-RPC block selector: either {@code latest} (serve from the live state,
 * not the archive) or a concrete archive {@link ArchiveStatePoint}. Keeping the two modes explicit
 * stops the historical path from silently falling back to latest.
 */
public final class ResolvedArchiveStatePoint {

  public enum Mode {
    LATEST,
    ARCHIVE
  }

  private static final ResolvedArchiveStatePoint LATEST =
      new ResolvedArchiveStatePoint(Mode.LATEST, null);

  private final Mode mode;
  private final ArchiveStatePoint point;

  private ResolvedArchiveStatePoint(Mode mode, ArchiveStatePoint point) {
    this.mode = mode;
    this.point = point;
  }

  public static ResolvedArchiveStatePoint latest() {
    return LATEST;
  }

  public static ResolvedArchiveStatePoint archive(ArchiveStatePoint point) {
    if (point == null) {
      throw new IllegalArgumentException("archive resolution requires a non-null state point");
    }
    return new ResolvedArchiveStatePoint(Mode.ARCHIVE, point);
  }

  public Mode getMode() {
    return mode;
  }

  public boolean isLatest() {
    return mode == Mode.LATEST;
  }

  /** The archive state point; valid only when {@code mode == ARCHIVE}. */
  public ArchiveStatePoint getPoint() {
    if (mode != Mode.ARCHIVE) {
      throw new IllegalStateException("no archive state point in mode " + mode);
    }
    return point;
  }
}
