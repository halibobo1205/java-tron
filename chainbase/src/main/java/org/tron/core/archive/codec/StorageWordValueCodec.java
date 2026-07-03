package org.tron.core.archive.codec;

import org.tron.core.archive.ArchiveException;

/** Canonical contract storage word: present values are non-zero 32-byte words. */
public final class StorageWordValueCodec implements CanonicalValueCodec {

  private static final int WORD_LEN = 32;
  private final String codecId;

  public StorageWordValueCodec() {
    this.codecId = "storage-word-v2";
  }

  @Override
  public String codecId() {
    return codecId;
  }

  @Override
  public DomainValue normalizePut(byte[] value) {
    requireWord(value, "put value");
    return isZero(value) ? DomainValue.tombstone() : DomainValue.present(value);
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
      requireWord(bytes, "present value");
      if (isZero(bytes)) {
        throw new ArchiveException(codecId + ": present value must not be zero");
      }
    }
  }

  private void requireWord(byte[] value, String what) {
    if (value == null || value.length != WORD_LEN) {
      throw new ArchiveException(codecId + ": " + what + " must be 32 bytes");
    }
  }

  private boolean isZero(byte[] value) {
    for (byte b : value) {
      if (b != 0) {
        return false;
      }
    }
    return true;
  }
}
