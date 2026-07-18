package org.tron.core.archive;

/** Durable in-flight journal evidence failed an integrity or lifecycle invariant. */
public final class ArchiveJournalCorruptionException
    extends ArchivePersistentStateCorruptionException {

  private static final long serialVersionUID = 1L;

  public ArchiveJournalCorruptionException(String message) {
    super(message);
  }

  public ArchiveJournalCorruptionException(String message, Throwable cause) {
    super(message, cause);
  }
}
