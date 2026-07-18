package org.tron.core.archive;

/** Local archive storage access failed without proving persistent state corruption. */
public final class ArchiveStorageAccessException extends ArchiveException {

  private static final long serialVersionUID = 1L;

  public ArchiveStorageAccessException(String message) {
    super(message);
  }

  public ArchiveStorageAccessException(String message, Throwable cause) {
    super(message, cause);
  }
}
