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

  public static DomainValue present(byte[] value) {
    return new DomainValue(false, value);
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
}
