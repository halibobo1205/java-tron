package org.tron.core.archive.capture;

import java.util.Arrays;
import org.tron.common.math.StrictMathWrapper;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.txnum.ArchiveTxPosition;

/**
 * One captured domain state change at a {@link ArchiveTxPosition} (txNum + block/phase/tx): the
 * canonical key, the value BEFORE the change ({@code prevValue}) and the value AFTER it
 * ({@code value}). This is the raw, append-only capture stream the L5 temporal store turns into
 * per-domain history/latest/changesets under the Erigon-v3 model: {@code prevValue} feeds the
 * txNum-versioned history (Erigon {@code AddPrevValue}) and {@code value} feeds latest.
 *
 * <p>A {@code prevValue} that is a tombstone means the key did not exist before this change
 * (creation); a {@code value} that is a tombstone means this change is a delete.
 */
public final class ArchiveChangeRecord {

  private final ArchiveTxPosition position;
  private final ArchiveDomain domain;
  private final byte[] canonicalKey;
  private final DomainValue prevValue;
  private final DomainValue value;

  public ArchiveChangeRecord(ArchiveTxPosition position, ArchiveDomain domain,
      byte[] canonicalKey, DomainValue prevValue, DomainValue value) {
    this(position, domain, canonicalKey, prevValue, value, true);
  }

  private ArchiveChangeRecord(ArchiveTxPosition position, ArchiveDomain domain,
      byte[] canonicalKey, DomainValue prevValue, DomainValue value, boolean copyKey) {
    this.position = position;
    this.domain = domain;
    this.canonicalKey = copyKey
        ? Arrays.copyOf(canonicalKey, canonicalKey.length) : canonicalKey;
    this.prevValue = prevValue;
    this.value = value;
  }

  public ArchiveTxPosition getPosition() {
    return position;
  }

  public long getTxNum() {
    return position.getTxNum();
  }

  public ArchiveDomain getDomain() {
    return domain;
  }

  public byte[] getCanonicalKey() {
    return Arrays.copyOf(canonicalKey, canonicalKey.length);
  }

  byte[] canonicalKeyView() {
    return canonicalKey;
  }

  public int canonicalKeySize() {
    return canonicalKey.length;
  }

  /** Copies the immutable canonical key into caller-owned storage without an intermediate array. */
  public void copyCanonicalKeyTo(byte[] target, int offset) {
    if (target == null) {
      throw new NullPointerException("target");
    }
    if (offset < 0 || offset > target.length - canonicalKey.length) {
      throw new IndexOutOfBoundsException("canonical key copy does not fit target");
    }
    System.arraycopy(canonicalKey, 0, target, offset, canonicalKey.length);
  }

  /** Compares canonical keys unsigned and lexicographically without exposing or copying them. */
  public int compareCanonicalKeyTo(ArchiveChangeRecord other) {
    if (other == null) {
      throw new NullPointerException("other");
    }
    int length = StrictMathWrapper.min(canonicalKey.length, other.canonicalKey.length);
    for (int i = 0; i < length; i++) {
      int result = (canonicalKey[i] & 0xff) - (other.canonicalKey[i] & 0xff);
      if (result != 0) {
        return result;
      }
    }
    return canonicalKey.length - other.canonicalKey.length;
  }

  /** The value before this change (history / Erigon prev-value); tombstone if absent before. */
  public DomainValue getPrevValue() {
    return prevValue;
  }

  /** The value after this change (latest); tombstone if this change is a delete. */
  public DomainValue getValue() {
    return value;
  }

  public boolean isSameValue() {
    return prevValue.contentEquals(value);
  }

  ArchiveChangeRecord withValue(DomainValue newValue) {
    return new ArchiveChangeRecord(position, domain, canonicalKey, prevValue, newValue, false);
  }
}
