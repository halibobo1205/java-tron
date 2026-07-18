package org.tron.core.archive.txnum;

import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.ArchivePhase;

/** A transaction position and its already-validated committed block range. */
public final class ArchiveTransactionLocation {

  private final ArchiveTxPosition position;
  private final ArchiveBlockRange range;

  public ArchiveTransactionLocation(ArchiveTxPosition position, ArchiveBlockRange range) {
    if (position == null || range == null) {
      throw new NullPointerException("position/range");
    }
    if (position.getBlockNum() != range.getBlockNum()
        || position.getTxNum() < range.getFirstTxNum()
        || position.getTxNum() > range.getLastTxNum()
        || position.getSource() != range.getSource()
        || position.getPhase() != ArchivePhase.USER_TX
        || position.getTxIndex() < 0
        || position.getTxIndex() >= range.getUserTxCount()
        || position.getTxNum() != range.getFirstTxNum() + 1L + position.getTxIndex()
        || position.getTxId().length != ArchiveBlockRange.BLOCK_HASH_LENGTH) {
      throw new ArchiveException("archive transaction location is outside its block range");
    }
    this.position = position;
    this.range = range;
  }

  public ArchiveTxPosition getPosition() {
    return position;
  }

  public ArchiveBlockRange getRange() {
    return range;
  }
}
