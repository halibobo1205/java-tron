package org.tron.core.archive.reader;

/**
 * A failure during a historical archive read that is NOT a normal missing/tombstone outcome -- a
 * codec/corruption/IO problem or an unsupported domain. The {@link Reason} drives the JSON-RPC
 * mapping (the framework adapter renders these as internal errors, never as a zero/empty value).
 */
public class ArchiveReaderException extends Exception {

  public enum Reason {
    ARCHIVE_DISABLED,
    HISTORY_UNAVAILABLE,
    DOMAIN_UNSUPPORTED,
    CODEC_ERROR,
    CORRUPT_VALUE,
    INTERNAL_IO
  }

  private final Reason reason;

  public ArchiveReaderException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public ArchiveReaderException(Reason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = reason;
  }

  public Reason getReason() {
    return reason;
  }
}
