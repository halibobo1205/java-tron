package org.tron.core.archive.codec;

import java.util.Arrays;
import org.tron.core.archive.ArchiveException;

/**
 * Canonical key codec for a domain whose store key is a variable-length opaque byte string with no
 * fixed shape to validate: a TRC10 token id (ASSET_ISSUE), a heterogeneous composite key
 * (DELEGATION), a from||to delegation key (DELEGATED_RESOURCE), or an address||assetId composite
 * (ACCOUNT_ASSET). Only requires the key to be non-empty.
 */
public final class RawKeyCodec implements CanonicalKeyCodec {

  private final String codecId;

  public RawKeyCodec(String codecId) {
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
    if (canonicalKey == null || canonicalKey.length == 0) {
      throw new ArchiveException(codecId + ": key must be non-empty");
    }
  }
}
