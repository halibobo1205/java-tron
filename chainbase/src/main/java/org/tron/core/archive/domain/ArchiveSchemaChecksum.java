package org.tron.core.archive.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.tron.core.archive.ArchiveException;

/** Deterministic fingerprint for archive data compatibility across capture and codecs. */
public final class ArchiveSchemaChecksum {

  private static final byte[] PREFIX =
      "tron-archive-schema-checksum-v1".getBytes(StandardCharsets.US_ASCII);
  private static final int CHECKSUM_LENGTH = 32;

  private ArchiveSchemaChecksum() {
  }

  public static byte[] of(ArchiveDomainRegistry registry, ArchiveDomainCatalog catalog) {
    return combine(registry.checksum(), catalog.checksum());
  }

  public static byte[] combine(byte[] registryChecksum, byte[] catalogChecksum) {
    requireChecksum(registryChecksum, "archive registry checksum");
    requireChecksum(catalogChecksum, "archive catalog checksum");
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(PREFIX);
      digest.update(registryChecksum);
      digest.update(catalogChecksum);
      return digest.digest();
    } catch (NoSuchAlgorithmException e) {
      throw new ArchiveException("SHA-256 unavailable for archive schema checksum", e);
    }
  }

  private static void requireChecksum(byte[] checksum, String what) {
    if (checksum == null || checksum.length != CHECKSUM_LENGTH) {
      throw new ArchiveException(what + " must be a 32-byte checksum");
    }
  }
}
