package org.tron.core.archive;

/** Configured journal capacity was exceeded without proving persistent corruption. */
final class ArchiveJournalLimitException extends ArchiveException {

  private static final long serialVersionUID = 1L;

  ArchiveJournalLimitException(String message) {
    super(message);
  }
}
