package org.tron.core.archive.reader;

import java.util.Arrays;

/**
 * The exact temporal coordinate a historical read resolves to, centralizing the txNum semantics so
 * RPC callers never compute {@code +1} / before-after themselves (decision 3: getAsOf is
 * inclusive-after). L6 historical getters only create {@link Kind#BLOCK_END}; the other kinds are
 * reserved for finer-grained (per-tx) reads in a later layer.
 */
public final class ArchiveStatePoint {

  public enum Kind {
    /** State after a block's finalize completed: {@code getAsOf(..., finalizeTxNum)}. */
    BLOCK_END,
    /** Reserved: state just before a user tx executed. */
    TX_BEFORE,
    /** Reserved: state just after a user tx executed. */
    TX_AFTER,
    /** Reserved: state after a block system/finalize tx. */
    SYSTEM_AFTER
  }

  private final Kind kind;
  private final long blockNum;
  private final byte[] blockHash;
  private final long txNum;

  private ArchiveStatePoint(Kind kind, long blockNum, byte[] blockHash, long txNum) {
    if (blockNum < 0 || txNum < 0) {
      throw new IllegalArgumentException("blockNum and txNum must be non-negative");
    }
    this.kind = kind;
    this.blockNum = blockNum;
    this.blockHash = (blockHash == null) ? null : Arrays.copyOf(blockHash, blockHash.length);
    this.txNum = txNum;
  }

  /** State at the end of block {@code blockNum} (after its finalize tx at finalizeTxNum). */
  public static ArchiveStatePoint blockEnd(long blockNum, byte[] blockHash, long finalizeTxNum) {
    return new ArchiveStatePoint(Kind.BLOCK_END, blockNum, blockHash, finalizeTxNum);
  }

  /**
   * State immediately before a transaction in {@code blockNum}, read with the caller's as-of txNum.
   */
  public static ArchiveStatePoint txBefore(long blockNum, byte[] blockHash, long asOfTxNum) {
    return new ArchiveStatePoint(Kind.TX_BEFORE, blockNum, blockHash, asOfTxNum);
  }

  public Kind getKind() {
    return kind;
  }

  public long getBlockNum() {
    return blockNum;
  }

  public byte[] getBlockHash() {
    return (blockHash == null) ? null : Arrays.copyOf(blockHash, blockHash.length);
  }

  public long getTxNum() {
    return txNum;
  }
}
