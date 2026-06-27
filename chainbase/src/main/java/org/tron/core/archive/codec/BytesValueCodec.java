package org.tron.core.archive.codec;

import org.tron.core.archive.ArchiveException;

/**
 * Canonical value codec that treats the value as opaque bytes (clone + typed tombstone). Suitable
 * for non-protobuf domains (CODE bytecode, CONTRACT_STORAGE word). Proto-valued domains (ACCOUNT)
 * need a canonicalizing codec (decision 4) layered on top — that is a follow-on, not this codec.
 */
public final class BytesValueCodec implements CanonicalValueCodec {

  private final String codecId;
  private final boolean allowEmptyValue;

  public BytesValueCodec(String codecId, boolean allowEmptyValue) {
    this.codecId = codecId;
    this.allowEmptyValue = allowEmptyValue;
  }

  @Override
  public String codecId() {
    return codecId;
  }

  @Override
  public DomainValue normalizePut(byte[] value) {
    if (value == null) {
      throw new ArchiveException(codecId + ": put value must not be null (use delete for absence)");
    }
    if (!allowEmptyValue && value.length == 0) {
      throw new ArchiveException(codecId + ": empty value not allowed; use delete/tombstone");
    }
    return DomainValue.present(value);
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
    if (!value.isDeleted() && !allowEmptyValue && value.getValue().length == 0) {
      throw new ArchiveException(codecId + ": present value must not be empty");
    }
  }
}
