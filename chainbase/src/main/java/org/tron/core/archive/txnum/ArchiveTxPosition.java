package org.tron.core.archive.txnum;

import java.util.Arrays;
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveSource;

/**
 * Immutable coordinate of a single logical state transition: its global {@code txNum} plus the
 * block / phase / source / tx position it belongs to. {@code txId} is empty for system phases.
 */
public final class ArchiveTxPosition {

  private final long txNum;
  private final long blockNum;
  private final ArchivePhase phase;
  private final ArchiveSource source;
  private final int txIndex;
  private final byte[] txId;
  private final byte[] blockHash;

  public ArchiveTxPosition(long txNum, long blockNum, ArchivePhase phase, ArchiveSource source,
      int txIndex, byte[] txId) {
    this(txNum, blockNum, phase, source, txIndex, txId, new byte[0]);
  }

  public ArchiveTxPosition(long txNum, long blockNum, ArchivePhase phase, ArchiveSource source,
      int txIndex, byte[] txId, byte[] blockHash) {
    this.txNum = txNum;
    this.blockNum = blockNum;
    this.phase = phase;
    this.source = source;
    this.txIndex = txIndex;
    this.txId = (txId == null) ? new byte[0] : Arrays.copyOf(txId, txId.length);
    this.blockHash = (blockHash == null) ? new byte[0] : Arrays.copyOf(blockHash,
        blockHash.length);
  }

  public long getTxNum() {
    return txNum;
  }

  public long getBlockNum() {
    return blockNum;
  }

  public ArchivePhase getPhase() {
    return phase;
  }

  public ArchiveSource getSource() {
    return source;
  }

  public int getTxIndex() {
    return txIndex;
  }

  public byte[] getTxId() {
    return Arrays.copyOf(txId, txId.length);
  }

  public byte[] getBlockHash() {
    return Arrays.copyOf(blockHash, blockHash.length);
  }
}
