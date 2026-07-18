package org.tron.core.archive;

/** Raised when a canonical mutation invalidates a historical snapshot before response commit. */
public final class ArchiveSnapshotInvalidatedException extends ArchiveException {

  private static final long serialVersionUID = 1L;

  public ArchiveSnapshotInvalidatedException(String message) {
    super(message);
  }
}
