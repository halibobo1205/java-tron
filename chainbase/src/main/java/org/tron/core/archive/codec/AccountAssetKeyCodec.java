package org.tron.core.archive.codec;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.tron.core.archive.ArchiveException;

/** Canonical ACCOUNT_ASSET key: 21-byte account address followed by a positive decimal asset ID. */
public final class AccountAssetKeyCodec implements CanonicalKeyCodec {

  private static final String CODEC_ID = "tron-account-asset-key-v2";
  public static final int ADDRESS_LEN = 21;
  public static final int MAX_ASSET_ID_LEN = 19;
  private final String codecId;

  public AccountAssetKeyCodec() {
    this.codecId = CODEC_ID;
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
    validateAssetId(canonicalKey, ADDRESS_LEN, canonicalKey.length - ADDRESS_LEN);
  }

  /** Decodes one canonical positive-long TRC10 ID without permitting reader/codec drift. */
  public static String decodeAssetId(byte[] assetId) {
    if (assetId == null) {
      throw invalidAssetId();
    }
    validateAssetId(assetId, 0, assetId.length);
    return new String(assetId, StandardCharsets.US_ASCII);
  }

  private static void validateAssetId(byte[] bytes, int offset, int length) {
    if (length == 0 || length > MAX_ASSET_ID_LEN || bytes[offset] == '0') {
      throw invalidAssetId();
    }
    long parsed = 0L;
    for (int i = offset; i < offset + length; i++) {
      int digit = bytes[i] - '0';
      if (digit < 0 || digit > 9
          || parsed > (Long.MAX_VALUE - digit) / 10L) {
        throw invalidAssetId();
      }
      parsed = parsed * 10L + digit;
    }
    if (parsed <= 0L) {
      throw invalidAssetId();
    }
  }

  private static ArchiveException invalidAssetId() {
    return new ArchiveException(
        CODEC_ID + ": assetId must be a canonical positive long");
  }
}
