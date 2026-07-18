package org.tron.core.archive.unified;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.txnum.ArchiveBlockRange;

/** Exact on-disk identity for the first unified archive layout. */
public final class UnifiedArchiveManifest {

  private static final char[] HEX = "0123456789abcdef".toCharArray();
  private static final byte[] KEY = "manifest".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] PUBLISHED_CURSOR_KEY =
      "published-cursor".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] LAYOUT_SCHEMA_PREFIX =
      "tron-archive-unified|layout=UNIFIED_V1|layout-schema="
          .getBytes(StandardCharsets.US_ASCII);
  private static final byte[] CURRENT_LAYOUT_SCHEMA_PREFIX =
      "tron-archive-unified|layout=UNIFIED_V1|layout-schema=6|"
          .getBytes(StandardCharsets.US_ASCII);
  private static final byte[] VALUE_PREFIX =
      ("tron-archive-unified|layout=UNIFIED_V1|layout-schema=6"
          + "|column-families=meta,inflight,index,latest,history,changeset,temporal-payload,"
          + "block-marker,commitment"
          + "|archive-schema=").getBytes(StandardCharsets.US_ASCII);
  private static final int HEX_CHECKSUM_LENGTH = ArchiveBlockRange.SCHEMA_CHECKSUM_LENGTH * 2;

  private UnifiedArchiveManifest() {
  }

  public static byte[] key() {
    return Arrays.copyOf(KEY, KEY.length);
  }

  public static byte[] publishedCursorKey() {
    return Arrays.copyOf(PUBLISHED_CURSOR_KEY, PUBLISHED_CURSOR_KEY.length);
  }

  static byte[] value(byte[] schemaChecksum) {
    requireSchemaChecksum(schemaChecksum);
    byte[] encoded = new byte[VALUE_PREFIX.length + HEX_CHECKSUM_LENGTH];
    System.arraycopy(VALUE_PREFIX, 0, encoded, 0, VALUE_PREFIX.length);
    for (int i = 0; i < schemaChecksum.length; i++) {
      int unsigned = schemaChecksum[i] & 0xff;
      encoded[VALUE_PREFIX.length + i * 2] = (byte) HEX[unsigned >>> 4];
      encoded[VALUE_PREFIX.length + i * 2 + 1] = (byte) HEX[unsigned & 0x0f];
    }
    return encoded;
  }

  static void validate(byte[] persisted, byte[] expectedSchemaChecksum) {
    requireSchemaChecksum(expectedSchemaChecksum);
    if (persisted == null) {
      throw new ArchiveException("UNIFIED_V1 archive is missing its manifest");
    }
    if (Arrays.equals(persisted, value(expectedSchemaChecksum))) {
      return;
    }
    if (startsWith(persisted, LAYOUT_SCHEMA_PREFIX)
        && !startsWith(persisted, CURRENT_LAYOUT_SCHEMA_PREFIX)) {
      throw new ArchiveException(
          "UNIFIED_V1 archive layout schema mismatch; expected layout-schema=6");
    }
    if (hasValidShape(persisted)) {
      throw new ArchiveException("UNIFIED_V1 archive schema checksum mismatch");
    }
    throw new ArchiveException("UNIFIED_V1 archive manifest mismatch");
  }

  static void requireSchemaChecksum(byte[] schemaChecksum) {
    if (schemaChecksum == null
        || schemaChecksum.length != ArchiveBlockRange.SCHEMA_CHECKSUM_LENGTH) {
      throw new ArchiveException("UNIFIED_V1 archive schema checksum must be 32 bytes");
    }
  }

  private static boolean hasValidShape(byte[] value) {
    if (value.length != VALUE_PREFIX.length + HEX_CHECKSUM_LENGTH
        || !startsWith(value, VALUE_PREFIX)) {
      return false;
    }
    for (int i = VALUE_PREFIX.length; i < value.length; i++) {
      byte current = value[i];
      if (!((current >= '0' && current <= '9') || (current >= 'a' && current <= 'f'))) {
        return false;
      }
    }
    return true;
  }

  private static boolean startsWith(byte[] value, byte[] prefix) {
    if (value.length < prefix.length) {
      return false;
    }
    for (int i = 0; i < prefix.length; i++) {
      if (value[i] != prefix[i]) {
        return false;
      }
    }
    return true;
  }
}
