package org.tron.core.archive;

/**
 * Unchecked exception for Archive sidecar failures (config, lifecycle, store, commitment).
 */
public class ArchiveException extends RuntimeException {

  public ArchiveException(String message) {
    super(message);
  }

  public ArchiveException(String message, Throwable cause) {
    super(message, cause);
  }
}
