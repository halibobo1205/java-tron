package org.tron.core.archive;

/** Durable archive state failed an integrity invariant and requires operator repair. */
public class ArchivePersistentStateCorruptionException extends ArchiveException {

  private static final long serialVersionUID = 1L;

  public ArchivePersistentStateCorruptionException(String message) {
    super(message);
  }

  public ArchivePersistentStateCorruptionException(String message, Throwable cause) {
    super(message, cause);
  }
}
