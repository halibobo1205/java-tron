package org.tron.core.archive.txnum;

import org.tron.core.archive.ArchiveSource;

/**
 * Immutable txNum range of a committed block. Even an empty block carries a prepare and a
 * finalize system txNum, so {@code firstTxNum <= lastTxNum} always holds.
 */
public final class ArchiveBlockRange {

  private final long blockNum;
  private final long firstTxNum;
  private final long lastTxNum;
  private final long prepareTxNum;
  private final long finalizeTxNum;
  private final int userTxCount;
  private final ArchiveSource source;

  public ArchiveBlockRange(long blockNum, long firstTxNum, long lastTxNum, long prepareTxNum,
      long finalizeTxNum, int userTxCount, ArchiveSource source) {
    this.blockNum = blockNum;
    this.firstTxNum = firstTxNum;
    this.lastTxNum = lastTxNum;
    this.prepareTxNum = prepareTxNum;
    this.finalizeTxNum = finalizeTxNum;
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

  public int getUserTxCount() {
    return userTxCount;
  }

  public ArchiveSource getSource() {
    return source;
  }
}
