package org.tron.core.archive.codec;

import java.util.Arrays;
import org.tron.core.archive.ArchiveException;

/**
 * Canonical key codec for DYNAMIC_PROPERTIES: a non-empty ASCII property key (e.g. {@code
 * ENERGY_FEE}). The key's root/history policy is decided separately by {@code DynamicKeyPolicy}.
 */
public final class DynamicPropertyKeyCodec implements CanonicalKeyCodec {

  @Override
  public String codecId() {
    return "tron-dynamic-property-key-v1";
  }

  @Override
  public byte[] normalize(byte[] key) {
    validate(key);
    return Arrays.copyOf(key, key.length);
  }

  @Override
  public void validate(byte[] canonicalKey) {
    if (canonicalKey == null || canonicalKey.length == 0) {
      throw new ArchiveException(codecId() + ": dynamic property key must be non-empty");
    }
    for (byte b : canonicalKey) {
      // ASCII only (signed byte >= 0 covers 0x00..0x7f); property keys are ASCII identifiers.
      if (b < 0) {
        throw new ArchiveException(codecId() + ": dynamic property key must be ASCII");
      }
    }
  }
}
