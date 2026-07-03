package org.tron.core.archive.codec;

import java.util.Arrays;
import org.tron.core.archive.ArchiveException;

/** Canonical ACCOUNT_ASSET key: 21-byte account address followed by non-empty assetId bytes. */
public final class AccountAssetKeyCodec implements CanonicalKeyCodec {

  private static final int ADDRESS_LEN = 21;
  private final String codecId;

  public AccountAssetKeyCodec() {
    this.codecId = "tron-account-asset-key-v2";
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
    if (canonicalKey == null || canonicalKey.length <= ADDRESS_LEN) {
      throw new ArchiveException(codecId + ": key must be address(21) plus assetId");
    }
  }
}
