package org.tron.core.archive.codec;

import com.google.common.primitives.Longs;
import org.tron.core.archive.ArchiveException;

/** Canonical int64 balance codec; zero is encoded as tombstone, never present-zero. */
public final class Int64BalanceValueCodec implements CanonicalValueCodec {

  private final String codecId;

  public Int64BalanceValueCodec(String codecId) {
    this.codecId = codecId;
  }

  @Override
  public String codecId() {
    return codecId;
  }

  @Override
  public DomainValue normalizePut(byte[] value) {
    requireInt64(value, "put value");
    return Longs.fromByteArray(value) == 0L ? DomainValue.tombstone() : DomainValue.present(value);
  }

  @Override
  public DomainValue normalizeDelete() {
    return DomainValue.tombstone();
  }

  @Override
  public void validate(DomainValue value) {
    if (value == null) {
      throw new ArchiveException(codecId + ": DomainValue must not be null");
    }
    if (!value.isDeleted()) {
      byte[] bytes = value.getValue();
      requireInt64(bytes, "present value");
      if (Longs.fromByteArray(bytes) == 0L) {
        throw new ArchiveException(codecId + ": present value must not be zero");
      }
    }
  }

  private void requireInt64(byte[] value, String what) {
    if (value == null || value.length != Long.BYTES) {
      throw new ArchiveException(codecId + ": " + what + " must be 8 bytes");
    }
  }
}
