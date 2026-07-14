package org.tron.core.archive.identity;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Immutable fields shared by the external anchor and archive-root identity. */
public final class ArchiveIdentityClaim {

  public static final int RESUME_NONCE_LENGTH = 32;

  private static final SecureRandom RANDOM = new SecureRandom();

  private final UUID uuid;
  private final byte[] resumeNonce;
  private final String chainId;
  private final String schema;
  private final String layout;
  private final Path finalPath;
  private final long floor;

  public ArchiveIdentityClaim(UUID uuid, byte[] resumeNonce, String chainId, String schema,
      String layout, Path finalPath, long floor) {
    this.uuid = Objects.requireNonNull(uuid, "uuid");
    if (resumeNonce == null || resumeNonce.length != RESUME_NONCE_LENGTH) {
      throw new IllegalArgumentException(
          "resume nonce must be exactly " + RESUME_NONCE_LENGTH + " bytes");
    }
    this.resumeNonce = Arrays.copyOf(resumeNonce, resumeNonce.length);
    this.chainId = requireCanonicalText(chainId, "chainId");
    this.schema = requireCanonicalText(schema, "schema");
    this.layout = requireCanonicalText(layout, "layout");
    this.finalPath = requireCanonicalAbsolutePath(finalPath);
    if (floor < 0) {
      throw new IllegalArgumentException("floor must be non-negative");
    }
    this.floor = floor;
  }

  /** Creates a claim with a random UUID and a cryptographically random resume nonce. */
  public static ArchiveIdentityClaim create(String chainId, String schema, String layout,
      Path finalPath, long floor) {
    byte[] resumeNonce = new byte[RESUME_NONCE_LENGTH];
    RANDOM.nextBytes(resumeNonce);
    return new ArchiveIdentityClaim(UUID.randomUUID(), resumeNonce, chainId, schema, layout,
        finalPath, floor);
  }

  public UUID getUuid() {
    return uuid;
  }

  public byte[] getResumeNonce() {
    return Arrays.copyOf(resumeNonce, resumeNonce.length);
  }

  public String getChainId() {
    return chainId;
  }

  public String getSchema() {
    return schema;
  }

  public String getLayout() {
    return layout;
  }

  public Path getFinalPath() {
    return finalPath;
  }

  public long getFloor() {
    return floor;
  }

  @Override
  public boolean equals(Object object) {
    if (this == object) {
      return true;
    }
    if (!(object instanceof ArchiveIdentityClaim)) {
      return false;
    }
    ArchiveIdentityClaim other = (ArchiveIdentityClaim) object;
    return floor == other.floor
        && uuid.equals(other.uuid)
        && Arrays.equals(resumeNonce, other.resumeNonce)
        && chainId.equals(other.chainId)
        && schema.equals(other.schema)
        && layout.equals(other.layout)
        && finalPath.equals(other.finalPath);
  }

  @Override
  public int hashCode() {
    int result = Objects.hash(uuid, chainId, schema, layout, finalPath, floor);
    return 31 * result + Arrays.hashCode(resumeNonce);
  }

  @Override
  public String toString() {
    return "ArchiveIdentityClaim{"
        + "uuid=" + uuid
        + ", chainId='" + chainId + '\''
        + ", schema='" + schema + '\''
        + ", layout='" + layout + '\''
        + ", finalPath=" + finalPath
        + ", floor=" + floor
        + '}';
  }

  private static String requireCanonicalText(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isEmpty() || !value.equals(value.trim())) {
      throw new IllegalArgumentException(field + " must be non-empty canonical text");
    }
    return value;
  }

  private static Path requireCanonicalAbsolutePath(Path path) {
    Objects.requireNonNull(path, "finalPath");
    if (!path.isAbsolute()) {
      throw new IllegalArgumentException("finalPath must be absolute");
    }
    Path normalized = path.normalize();
    if (!normalized.equals(path)) {
      throw new IllegalArgumentException("finalPath must be normalized");
    }
    return normalized;
  }
}
