package org.tron.core.archive.codec;

import java.util.Arrays;
import org.tron.core.archive.ArchiveException;

/**
 * Canonical key codec for CONTRACT_STORAGE:
 * the exact 32-byte physical key used by {@code Storage.compose}. This preserves java-tron's
 * version-dependent slot aliasing and CREATE2 storage namespaces without a second key model.
 */
public final class ContractStorageKeyCodec implements CanonicalKeyCodec {

  public static final int LENGTH = 32;

  @Override
  public String codecId() {
    return "tron-storage-physical-v2";
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
  }
}
