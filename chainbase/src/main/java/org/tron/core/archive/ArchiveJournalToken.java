package org.tron.core.archive;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.UUID;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.archive.txnum.ArchiveCoordinates;

/** Identity of one durable in-flight journal generation. */
public final class ArchiveJournalToken {

  public static final int GENERATION_NONCE_LENGTH = 16;

  private final long blockNum;
  private final byte[] blockHash;
  private final byte[] generationNonce;
  private final byte[] schemaChecksum;

  public ArchiveJournalToken(long blockNum, byte[] blockHash, byte[] generationNonce,
      byte[] schemaChecksum) {
    ArchiveCoordinates.requireBlockNum(blockNum, "archive journal block number");
    requireLength(blockHash, ArchiveBlockRange.BLOCK_HASH_LENGTH, "block hash");
    requireLength(generationNonce, GENERATION_NONCE_LENGTH, "generation nonce");
    requireLength(schemaChecksum, ArchiveBlockRange.SCHEMA_CHECKSUM_LENGTH, "schema checksum");
    this.blockNum = blockNum;
    this.blockHash = Arrays.copyOf(blockHash, blockHash.length);
    this.generationNonce = Arrays.copyOf(generationNonce, generationNonce.length);
    this.schemaChecksum = Arrays.copyOf(schemaChecksum, schemaChecksum.length);
  }

  public static ArchiveJournalToken create(ArchiveBlockRange range) {
    UUID generation = UUID.randomUUID();
    byte[] nonce = ByteBuffer.allocate(GENERATION_NONCE_LENGTH)
        .putLong(generation.getMostSignificantBits())
        .putLong(generation.getLeastSignificantBits())
        .array();
    return new ArchiveJournalToken(range.getBlockNum(), range.getBlockHash(), nonce,
        range.getSchemaChecksum());
  }

  public long getBlockNum() {
    return blockNum;
  }

  public byte[] getBlockHash() {
    return Arrays.copyOf(blockHash, blockHash.length);
  }

  public byte[] getGenerationNonce() {
    return Arrays.copyOf(generationNonce, generationNonce.length);
  }

  public byte[] getSchemaChecksum() {
    return Arrays.copyOf(schemaChecksum, schemaChecksum.length);
  }

  public boolean matches(ArchiveBlockRange range) {
    return range != null
        && blockNum == range.getBlockNum()
        && Arrays.equals(blockHash, range.getBlockHash())
        && Arrays.equals(schemaChecksum, range.getSchemaChecksum());
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof ArchiveJournalToken)) {
      return false;
    }
    ArchiveJournalToken other = (ArchiveJournalToken) obj;
    return blockNum == other.blockNum
        && Arrays.equals(blockHash, other.blockHash)
        && Arrays.equals(generationNonce, other.generationNonce)
        && Arrays.equals(schemaChecksum, other.schemaChecksum);
  }

  @Override
  public int hashCode() {
    int result = Long.hashCode(blockNum);
    result = 31 * result + Arrays.hashCode(blockHash);
    result = 31 * result + Arrays.hashCode(generationNonce);
    result = 31 * result + Arrays.hashCode(schemaChecksum);
    return result;
  }

  private static void requireLength(byte[] value, int expected, String name) {
    if (value == null || value.length != expected) {
      throw new ArchiveException("archive journal " + name + " must be " + expected + " bytes");
    }
  }
}
