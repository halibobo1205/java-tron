package org.tron.core.archive.reader;

import org.rocksdb.RocksDBException;
import org.rocksdb.Status;
import org.tron.core.archive.ArchivePersistentStateCorruptionException;
import org.tron.core.archive.ArchiveStorageAccessException;

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
    CORRUPT_INDEX,
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

  public boolean isIntegrityFailure() {
    return reason == Reason.CODEC_ERROR
        || reason == Reason.CORRUPT_VALUE
        || reason == Reason.CORRUPT_INDEX;
  }

  /**
   * Classifies a storage exception without treating an ordinary RocksDB I/O failure as persistent
   * corruption. Explicit archive corruption wrappers take precedence over a nested diagnostic I/O
   * cause; structural archive failures without a native cause remain integrity failures.
   */
  public static Reason reasonForStorageFailure(Throwable failure, Reason integrityReason) {
    boolean storageAccessFailure = false;
    Throwable current = failure;
    while (current != null) {
      if (current instanceof ArchivePersistentStateCorruptionException) {
        return integrityReason;
      }
      if (current instanceof RocksDBException) {
        Status status = ((RocksDBException) current).getStatus();
        return status != null && status.getCode() == Status.Code.Corruption
            ? integrityReason : Reason.INTERNAL_IO;
      }
      if (current instanceof ArchiveStorageAccessException) {
        storageAccessFailure = true;
      }
      Throwable cause = current.getCause();
      if (cause == current) {
        break;
      }
      current = cause;
    }
    return storageAccessFailure ? Reason.INTERNAL_IO : integrityReason;
  }
}
