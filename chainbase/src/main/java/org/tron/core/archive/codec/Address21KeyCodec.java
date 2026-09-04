package org.tron.core.archive.codec;

import java.util.Arrays;
import org.tron.core.archive.ArchiveException;

/**
 * Canonical key codec for domains keyed by a 21-byte TRON address (ACCOUNT, CONTRACT, CODE). The
 * codec id is supplied so the same logic can carry a per-domain version
 * (e.g. {@code tron-code-key-v1}).
 */
public final class Address21KeyCodec implements CanonicalKeyCodec {

  public static final int LENGTH = 21;

  private final String codecId;

  public Address21KeyCodec(String codecId) {
    this.codecId = codecId;
  }

  @Override
  public String codecId() {
    return codecId;
  }

  @Override
  public byte[] normalize(byte[] key) {
    validate(key);
    return Arrays.copyOf(key, key.length);
  }

  @Override
  public void validate(byte[] canonicalKey) {
    if (canonicalKey == null || canonicalKey.length != LENGTH) {
      throw new ArchiveException(codecId + ": key must be " + LENGTH + " bytes, got "
          + (canonicalKey == null ? "null" : canonicalKey.length));
    }
  }
}
