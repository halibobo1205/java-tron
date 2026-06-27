package org.tron.core.archive.codec;

import java.util.Arrays;
import org.tron.core.archive.ArchiveException;

/**
 * Canonical key codec for CONTRACT_STORAGE: {@code address(21) || slot(32) || version(1)}, where
 * version is the storage key version (0 or 1). The raw storage-row key is irreversible, so this
 * canonical key is produced by the L4 semantic hook, not from the raw store key.
 */
public final class ContractStorageKeyCodec implements CanonicalKeyCodec {

  public static final int ADDRESS_LEN = 21;
  public static final int SLOT_LEN = 32;
  public static final int LENGTH = ADDRESS_LEN + SLOT_LEN + 1; // 54

  @Override
  public String codecId() {
    return "tron-storage-semantic-v1";
  }

  @Override
  public byte[] normalize(byte[] key) {
    validate(key);
    return Arrays.copyOf(key, key.length);
  }

  @Override
  public void validate(byte[] canonicalKey) {
    if (canonicalKey == null || canonicalKey.length != LENGTH) {
      throw new ArchiveException(codecId() + ": key must be " + LENGTH + " bytes, got "
          + (canonicalKey == null ? "null" : canonicalKey.length));
    }
    int version = canonicalKey[LENGTH - 1] & 0xff;
    if (version != 0 && version != 1) {
      throw new ArchiveException(codecId() + ": storage version must be 0/1, got " + version);
    }
  }
}
