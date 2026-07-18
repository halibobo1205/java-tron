package org.tron.core.archive;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import org.tron.core.archive.unified.UnifiedArchiveJournalVerifier;

/**
 * Independent durable proof binding a journal lifecycle row to its exact encoded payload. The v2
 * domain also attests that full canonical validation completed before the proof was persisted.
 */
final class ArchiveJournalProof implements UnifiedArchiveJournalVerifier {

  static final int DIGEST_LENGTH = 32;

  private static final byte[] DIGEST_DOMAIN =
      "tron-archive-unified/inflight-journal-proof/canonical-v2"
          .getBytes(StandardCharsets.US_ASCII);

  private final ArchiveJournalToken token;
  private final long payloadLength;
  private final byte[] payloadDigest;

  ArchiveJournalProof(ArchiveJournalToken token, long payloadLength, byte[] payloadDigest) {
    if (token == null) {
      throw new ArchiveException("archive journal proof token is missing");
    }
    if (payloadLength <= 0L) {
      throw new ArchiveException("archive journal proof payload length must be positive");
    }
    if (payloadDigest == null || payloadDigest.length != DIGEST_LENGTH) {
      throw new ArchiveException("archive journal proof digest must be 32 bytes");
    }
    this.token = token;
    this.payloadLength = payloadLength;
    this.payloadDigest = Arrays.copyOf(payloadDigest, payloadDigest.length);
  }

  static ArchiveJournalProof create(ArchiveJournalToken token, byte[] journalKey,
      byte[] payload) {
    requireBytes(journalKey, "key");
    requireBytes(payload, "payload");
    return new ArchiveJournalProof(
        token, payload.length, digest(journalKey, payload));
  }

  ArchiveJournalToken getToken() {
    return token;
  }

  long getPayloadLength() {
    return payloadLength;
  }

  @Override
  public long expectedPayloadBytes() {
    return payloadLength;
  }

  byte[] getPayloadDigest() {
    return Arrays.copyOf(payloadDigest, payloadDigest.length);
  }

  @Override
  public void requireMatches(byte[] journalKey, byte[] payload) {
    requireBytes(journalKey, "key");
    requireBytes(payload, "payload");
    if (payloadLength != payload.length
        || !MessageDigest.isEqual(payloadDigest, digest(journalKey, payload))) {
      throw new ArchiveJournalCorruptionException(
          "UNIFIED_V1 journal payload does not match its durable proof for block "
              + token.getBlockNum());
    }
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof ArchiveJournalProof)) {
      return false;
    }
    ArchiveJournalProof other = (ArchiveJournalProof) obj;
    return payloadLength == other.payloadLength
        && token.equals(other.token)
        && MessageDigest.isEqual(payloadDigest, other.payloadDigest);
  }

  @Override
  public int hashCode() {
    int result = token.hashCode();
    result = 31 * result + Long.hashCode(payloadLength);
    result = 31 * result + Arrays.hashCode(payloadDigest);
    return result;
  }

  private static byte[] digest(byte[] journalKey, byte[] payload) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(DIGEST_DOMAIN);
      digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(journalKey.length).array());
      digest.update(journalKey);
      digest.update(ByteBuffer.allocate(Long.BYTES).putLong(payload.length).array());
      digest.update(payload);
      return digest.digest();
    } catch (NoSuchAlgorithmException e) {
      throw new ArchiveException("SHA-256 is unavailable for archive journal proof", e);
    }
  }

  private static void requireBytes(byte[] value, String what) {
    if (value == null || value.length == 0) {
      throw new ArchiveException("archive journal proof " + what + " is missing");
    }
  }
}
