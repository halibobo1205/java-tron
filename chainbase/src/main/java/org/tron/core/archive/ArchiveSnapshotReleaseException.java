package org.tron.core.archive;

/** Native snapshot release returned with an unknown ownership outcome; restart is required. */
public final class ArchiveSnapshotReleaseException extends ArchiveException {

  private static final long serialVersionUID = 1L;
  private static final int MAX_CAUSE_DEPTH = 32;

  public ArchiveSnapshotReleaseException(String message, Throwable cause) {
    super(message, cause);
  }

  /** Finds this marker through ordinary causes and cleanup-suppressed failures. */
  public static boolean contains(Throwable failure) {
    return contains(failure, MAX_CAUSE_DEPTH);
  }

  private static boolean contains(Throwable failure, int remainingDepth) {
    if (failure == null || remainingDepth == 0) {
      return false;
    }
    if (failure instanceof ArchiveSnapshotReleaseException) {
      return true;
    }
    if (contains(failure.getCause(), remainingDepth - 1)) {
      return true;
    }
    for (Throwable suppressed : failure.getSuppressed()) {
      if (contains(suppressed, remainingDepth - 1)) {
        return true;
      }
    }
    return false;
  }
}
