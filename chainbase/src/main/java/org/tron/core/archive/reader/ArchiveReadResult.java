package org.tron.core.archive.reader;

/**
 * A typed historical-read outcome from the archive. Deliberately not a bare Optional:
 * the three states must stay distinct so callers can render them faithfully (decision: typed
 * tombstone three-state) -- e.g. a missing account vs a present zero account, or a tombstone
 * (explicitly deleted in archive) vs never-existed. A corrupt/codec error is never MISSING
 * (the reader throws {@code ArchiveReaderException} instead).
 *
 * @param <T> the decoded value type (raw bytes, AccountCapsule, ...)
 */
public final class ArchiveReadResult<T> {

  public enum Status {
    PRESENT,
    TOMBSTONE,
    MISSING
  }

  private static final ArchiveReadResult<?> TOMBSTONE =
      new ArchiveReadResult<>(Status.TOMBSTONE, null);
  private static final ArchiveReadResult<?> MISSING = new ArchiveReadResult<>(Status.MISSING, null);

  private final Status status;
  private final T value;

  private ArchiveReadResult(Status status, T value) {
    this.status = status;
    this.value = value;
  }

  public static <T> ArchiveReadResult<T> present(T value) {
    return new ArchiveReadResult<>(Status.PRESENT, value);
  }

  @SuppressWarnings("unchecked")
  public static <T> ArchiveReadResult<T> tombstone() {
    return (ArchiveReadResult<T>) TOMBSTONE;
  }

  @SuppressWarnings("unchecked")
  public static <T> ArchiveReadResult<T> missing() {
    return (ArchiveReadResult<T>) MISSING;
  }

  public Status getStatus() {
    return status;
  }

  public boolean isPresent() {
    return status == Status.PRESENT;
  }

  /** The decoded value; valid only when {@link #isPresent()}. */
  public T getValue() {
    if (status != Status.PRESENT) {
      throw new IllegalStateException("no value for archive read status " + status);
    }
    return value;
  }
}
