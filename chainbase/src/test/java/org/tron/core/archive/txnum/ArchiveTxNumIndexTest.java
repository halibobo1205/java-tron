package org.tron.core.archive.txnum;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveSource;

public class ArchiveTxNumIndexTest {

  private static final byte[] TX_A = new byte[] {1, 2, 3};
  private static final byte[] TX_B = new byte[] {4, 5, 6};

  private static long commitTwoUserTxBlock(ArchiveTxNumIndex idx, long blockNum) {
    idx.beginBlock(blockNum, ArchiveSource.NORMAL);
    idx.allocateSystemTx(blockNum, ArchivePhase.BLOCK_PREPARE);
    idx.allocateUserTx(blockNum, 0, TX_A);
    idx.allocateUserTx(blockNum, 1, TX_B);
    idx.allocateSystemTx(blockNum, ArchivePhase.BLOCK_FINALIZE);
    return idx.commitBlock(blockNum, 2).getLastTxNum();
  }

  @Test
  public void commitBuildsRangeAndLookups() {
    ArchiveTxNumIndex idx = new InMemoryArchiveTxNumIndex();
    idx.beginBlock(1, ArchiveSource.NORMAL);
    assertEquals(0, idx.allocateSystemTx(1, ArchivePhase.BLOCK_PREPARE).getTxNum());
    assertEquals(1, idx.allocateUserTx(1, 0, TX_A).getTxNum());
    assertEquals(2, idx.allocateUserTx(1, 1, TX_B).getTxNum());
    assertEquals(3, idx.allocateSystemTx(1, ArchivePhase.BLOCK_FINALIZE).getTxNum());

    ArchiveBlockRange range = idx.commitBlock(1, 2);
    assertEquals(1, range.getBlockNum());
    assertEquals(0, range.getFirstTxNum());
    assertEquals(3, range.getLastTxNum());
    assertEquals(0, range.getPrepareTxNum());
    assertEquals(3, range.getFinalizeTxNum());
    assertEquals(2, range.getUserTxCount());
    assertEquals(ArchiveSource.NORMAL, range.getSource());

    assertTrue(idx.getBlockRange(1).isPresent());
    ArchiveTxPosition userPos = idx.getPosition(1).orElseThrow(AssertionError::new);
    assertEquals(ArchivePhase.USER_TX, userPos.getPhase());
    assertEquals(0, userPos.getTxIndex());
    assertArrayEquals(TX_A, userPos.getTxId());
    assertEquals(1, idx.findTxNumByBlockAndIndex(1, 0).getAsLong());
    assertEquals(2, idx.findTxNumByBlockAndIndex(1, 1).getAsLong());
    assertEquals(1, idx.findTxNumByTxId(TX_A).getAsLong());
    assertEquals(2, idx.findTxNumByTxId(TX_B).getAsLong());
    // System tx carries no txId / index lookup.
    assertEquals(-1, idx.getPosition(0).orElseThrow(AssertionError::new).getTxIndex());
    assertFalse(idx.findTxNumByBlockAndIndex(1, 5).isPresent());
  }

  @Test
  public void txNumIsMonotonicAcrossBlocks() {
    ArchiveTxNumIndex idx = new InMemoryArchiveTxNumIndex();
    assertEquals(3, commitTwoUserTxBlock(idx, 1));
    // Empty block 2 still consumes a prepare + finalize txNum (4, 5).
    idx.beginBlock(2, ArchiveSource.NORMAL);
    assertEquals(4, idx.allocateSystemTx(2, ArchivePhase.BLOCK_PREPARE).getTxNum());
    assertEquals(5, idx.allocateSystemTx(2, ArchivePhase.BLOCK_FINALIZE).getTxNum());
    ArchiveBlockRange range = idx.commitBlock(2, 0);
    assertEquals(4, range.getFirstTxNum());
    assertEquals(5, range.getLastTxNum());
    assertEquals(0, range.getUserTxCount());
  }

  @Test
  public void abortDiscardsPendingAndReusesTxNums() {
    ArchiveTxNumIndex idx = new InMemoryArchiveTxNumIndex();
    idx.beginBlock(1, ArchiveSource.NORMAL);
    idx.allocateSystemTx(1, ArchivePhase.BLOCK_PREPARE);
    idx.allocateUserTx(1, 0, TX_A);
    idx.abortBlock(1);

    assertFalse(idx.getBlockRange(1).isPresent());
    assertFalse(idx.findTxNumByTxId(TX_A).isPresent());
    // Aborted txNums are reclaimed: a fresh block starts again at 0.
    idx.beginBlock(1, ArchiveSource.NORMAL);
    assertEquals(0, idx.allocateSystemTx(1, ArchivePhase.BLOCK_PREPARE).getTxNum());
  }

  @Test
  public void unwindRemovesCommittedHeadAndRewindsTxNum() {
    ArchiveTxNumIndex idx = new InMemoryArchiveTxNumIndex();
    commitTwoUserTxBlock(idx, 1);
    idx.beginBlock(2, ArchiveSource.NORMAL);
    idx.allocateSystemTx(2, ArchivePhase.BLOCK_PREPARE);
    idx.allocateSystemTx(2, ArchivePhase.BLOCK_FINALIZE);
    idx.commitBlock(2, 0);

    idx.unwindBlock(2);
    assertFalse(idx.getBlockRange(2).isPresent());
    assertFalse(idx.getPosition(4).isPresent());
    assertTrue(idx.getBlockRange(1).isPresent());
    // Re-applying block 2 reuses the freed txNums.
    idx.beginBlock(2, ArchiveSource.REPLAY);
    assertEquals(4, idx.allocateSystemTx(2, ArchivePhase.BLOCK_PREPARE).getTxNum());
  }

  @Test
  public void unwindNonHeadBlockRejectedWithoutChangingIndex() {
    ArchiveTxNumIndex idx = new InMemoryArchiveTxNumIndex();
    commitTwoUserTxBlock(idx, 1);
    commitTwoUserTxBlock(idx, 2);

    ArchiveException ex = assertThrows(ArchiveException.class, () -> idx.unwindBlock(1));
    assertTrue(ex.getMessage().contains("not archive head"));
    assertTrue(idx.getBlockRange(1).isPresent());
    assertTrue(idx.getBlockRange(2).isPresent());
    assertEquals(5, idx.findTxNumByTxId(TX_A).getAsLong());
  }

  @Test
  public void duplicateBeginBlockRejected() {
    ArchiveTxNumIndex idx = new InMemoryArchiveTxNumIndex();
    idx.beginBlock(1, ArchiveSource.NORMAL);
    assertThrows(ArchiveException.class, () -> idx.beginBlock(2, ArchiveSource.NORMAL));
  }

  @Test
  public void allocateWithoutPendingRejected() {
    ArchiveTxNumIndex idx = new InMemoryArchiveTxNumIndex();
    assertThrows(ArchiveException.class,
        () -> idx.allocateSystemTx(1, ArchivePhase.BLOCK_PREPARE));
  }

  @Test
  public void commitWithoutPrepareOrFinalizeRejected() {
    ArchiveTxNumIndex idx = new InMemoryArchiveTxNumIndex();
    idx.beginBlock(1, ArchiveSource.NORMAL);
    idx.allocateUserTx(1, 0, TX_A);
    assertThrows(ArchiveException.class, () -> idx.commitBlock(1, 1));
  }

  @Test
  public void commitWithWrongUserTxCountRejected() {
    ArchiveTxNumIndex idx = new InMemoryArchiveTxNumIndex();
    idx.beginBlock(1, ArchiveSource.NORMAL);
    idx.allocateSystemTx(1, ArchivePhase.BLOCK_PREPARE);
    idx.allocateUserTx(1, 0, TX_A);
    idx.allocateSystemTx(1, ArchivePhase.BLOCK_FINALIZE);
    assertThrows(ArchiveException.class, () -> idx.commitBlock(1, 5));
  }

  @Test
  public void unwindUncommittedBlockRejected() {
    ArchiveTxNumIndex idx = new InMemoryArchiveTxNumIndex();
    assertThrows(ArchiveException.class, () -> idx.unwindBlock(99));
  }
}
