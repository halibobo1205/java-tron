package org.tron.core.archive;

import java.util.Arrays;
import org.tron.core.archive.txnum.ArchiveBlockRange;

/** Immutable, canonical-epoch-bound request to publish through a solidified block. */
public final class ArchivePublishTarget {

  private final long blockNum;
  private final byte[] blockHash;
  private final long canonicalEpoch;

  public ArchivePublishTarget(long blockNum, byte[] blockHash, long canonicalEpoch) {
    if (blockNum < 0) {
      throw new IllegalArgumentException("blockNum must be non-negative");
    }
    if (blockHash == null || blockHash.length != ArchiveBlockRange.BLOCK_HASH_LENGTH) {
      throw new IllegalArgumentException(
          "blockHash must be " + ArchiveBlockRange.BLOCK_HASH_LENGTH + " bytes");
    }
    if (canonicalEpoch < 0) {
      throw new IllegalArgumentException("canonicalEpoch must be non-negative");
    }
    this.blockNum = blockNum;
    this.blockHash = Arrays.copyOf(blockHash, blockHash.length);
    this.canonicalEpoch = canonicalEpoch;
  }

  public long getBlockNum() {
    return blockNum;
  }

  public byte[] getBlockHash() {
    return Arrays.copyOf(blockHash, blockHash.length);
  }

  public long getCanonicalEpoch() {
    return canonicalEpoch;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ArchivePublishTarget)) {
      return false;
    }
    ArchivePublishTarget that = (ArchivePublishTarget) other;
    return blockNum == that.blockNum
        && canonicalEpoch == that.canonicalEpoch
        && Arrays.equals(blockHash, that.blockHash);
  }

  @Override
  public int hashCode() {
    int result = Long.hashCode(blockNum);
    result = 31 * result + Arrays.hashCode(blockHash);
    return 31 * result + Long.hashCode(canonicalEpoch);
  }
}
