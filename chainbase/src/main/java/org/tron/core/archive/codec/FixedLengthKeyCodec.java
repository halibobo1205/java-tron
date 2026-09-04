package org.tron.core.archive.codec;

import java.util.Arrays;
import org.tron.core.archive.ArchiveException;

/**
 * Canonical key codec for a domain whose store key is a fixed-length opaque byte string, e.g. an
 * 8-byte big-endian id (EXCHANGE/PROPOSAL) or a 32-byte order-id hash (MARKET_ORDER).
 */
public final class FixedLengthKeyCodec implements CanonicalKeyCodec {

  private final String codecId;
  private final int length;

  public FixedLengthKeyCodec(String codecId, int length) {
    this.codecId = codecId;
    this.length = length;
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
    if (canonicalKey == null || canonicalKey.length != length) {
      throw new ArchiveException(codecId + ": key must be " + length + " bytes, got "
          + (canonicalKey == null ? "null" : canonicalKey.length));
    }
  }
}
