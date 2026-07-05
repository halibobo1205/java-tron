package org.tron.core.archive.txnum;

import java.util.Arrays;
import org.tron.core.archive.ArchiveSource;

/**
 * Immutable txNum range of a committed block. Even an empty block carries a prepare and a
 * finalize system txNum, so {@code firstTxNum <= lastTxNum} always holds.
 */
public final class ArchiveBlockRange {

  public static final int BLOCK_HASH_LENGTH = 32;
  public static final int SCHEMA_CHECKSUM_LENGTH = 32;

  private final long blockNum;
  private final long firstTxNum;
  private final long lastTxNum;
  private final long prepareTxNum;
  private final long finalizeTxNum;
  private final byte[] blockHash;
  private final byte[] schemaChecksum;
  private final int userTxCount;
  private final ArchiveSource source;

  public ArchiveBlockRange(long blockNum, long firstTxNum, long lastTxNum, long prepareTxNum,
      long finalizeTxNum, int userTxCount, ArchiveSource source) {
    this(blockNum, firstTxNum, lastTxNum, prepareTxNum, finalizeTxNum, new byte[0],
        userTxCount, source);
  }

  public ArchiveBlockRange(long blockNum, long firstTxNum, long lastTxNum, long prepareTxNum,
      long finalizeTxNum, byte[] blockHash, int userTxCount, ArchiveSource source) {
    this(blockNum, firstTxNum, lastTxNum, prepareTxNum, finalizeTxNum, blockHash,
        userTxCount, source, new byte[0]);
  }

  public ArchiveBlockRange(long blockNum, long firstTxNum, long lastTxNum, long prepareTxNum,
      long finalizeTxNum, byte[] blockHash, int userTxCount, ArchiveSource source,
      byte[] schemaChecksum) {
    this.blockNum = blockNum;
    this.firstTxNum = firstTxNum;
    this.lastTxNum = lastTxNum;
    this.prepareTxNum = prepareTxNum;
    this.finalizeTxNum = finalizeTxNum;
    this.blockHash = blockHash == null ? new byte[0] : Arrays.copyOf(blockHash, blockHash.length);
    this.schemaChecksum = schemaChecksum == null
        ? new byte[0]
        : Arrays.copyOf(schemaChecksum, schemaChecksum.length);
    this.userTxCount = userTxCount;
    this.source = source;
  }

  public long getBlockNum() {
    return blockNum;
  }

  public long getFirstTxNum() {
    return firstTxNum;
  }

  public long getLastTxNum() {
    return lastTxNum;
  }

  public long getPrepareTxNum() {
    return prepareTxNum;
  }

  public long getFinalizeTxNum() {
    return finalizeTxNum;
  }

  public byte[] getBlockHash() {
    return Arrays.copyOf(blockHash, blockHash.length);
  }

  public byte[] getSchemaChecksum() {
    return Arrays.copyOf(schemaChecksum, schemaChecksum.length);
  }

  public int getUserTxCount() {
    return userTxCount;
  }

  public ArchiveSource getSource() {
    return source;
  }
}
