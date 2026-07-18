package org.tron.core.archive.codec;

import java.util.Arrays;

/**
 * Canonical domain value: either a present value or a typed tombstone (delete). A tombstone is
 * {@code deleted=true}, NOT an empty byte array - empty bytes are a valid present value.
 */
public final class DomainValue {

  private static final byte[] EMPTY = new byte[0];

  private final boolean deleted;
  private final byte[] value;

  private DomainValue(boolean deleted, byte[] value) {
    this.deleted = deleted;
    this.value = (value == null) ? EMPTY : Arrays.copyOf(value, value.length);
  }

  private DomainValue(boolean deleted, byte[] value, int offset, int length) {
    this.deleted = deleted;
    this.value = Arrays.copyOfRange(value, offset, offset + length);
  }

  public static DomainValue present(byte[] value) {
    return new DomainValue(false, value);
  }

  /** Copies one range directly into the immutable backing array without an intermediate slice. */
  public static DomainValue present(byte[] value, int offset, int length) {
    if (value == null || offset < 0 || length < 0 || offset > value.length - length) {
      throw new IndexOutOfBoundsException("domain value range is invalid");
    }
    return new DomainValue(false, value, offset, length);
  }

  public static DomainValue tombstone() {
    return new DomainValue(true, EMPTY);
  }

  public boolean isDeleted() {
    return deleted;
  }

  public byte[] getValue() {
    return Arrays.copyOf(value, value.length);
  }

  /** Allocation-free comparison for archive hot paths that already own both immutable values. */
  public boolean contentEquals(DomainValue other) {
    return other != null && deleted == other.deleted && Arrays.equals(value, other.value);
  }

  /** Encoded payload length without exposing the immutable backing array. */
  public int size() {
    return value.length;
  }

  /** Copies the immutable value into caller-owned storage without an intermediate array. */
  public void copyValueTo(byte[] target, int offset) {
    if (target == null) {
      throw new NullPointerException("target");
    }
    if (offset < 0 || offset > target.length - value.length) {
      throw new IndexOutOfBoundsException("domain value copy does not fit target");
    }
    System.arraycopy(value, 0, target, offset, value.length);
  }
}
