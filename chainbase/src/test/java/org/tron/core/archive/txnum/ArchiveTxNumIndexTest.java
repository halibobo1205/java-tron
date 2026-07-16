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

  private static final byte[] TX_A = txId(1);
  private static final byte[] TX_B = txId(2);
  private static final byte[] HASH_A = blockHash(7);
  private static final byte[] HASH_B = blockHash(9);

  private static long commitTwoUserTxBlock(ArchiveTxNumIndex idx, long blockNum) {
    byte[] txA = txId((int) blockNum * 2 - 1);
    byte[] txB = txId((int) blockNum * 2);
    idx.beginBlock(blockNum, ArchiveSource.NORMAL);
    idx.allocateSystemTx(blockNum, ArchivePhase.BLOCK_PREPARE);
    idx.allocateUserTx(blockNum, 0, txA);
    idx.allocateUserTx(blockNum, 1, txB);
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
  public void txIdLookupRejectsMalformedNonEmptyTxId() {
    ArchiveTxNumIndex idx = new InMemoryArchiveTxNumIndex();

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> idx.findTxNumByTxId(new byte[] {1}));

    assertTrue(ex.getMessage().contains("32-byte txId"));
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
  public void duplicatePrepareCannotPublishInMemoryRange() {
    ArchiveTxNumIndex idx = new InMemoryArchiveTxNumIndex();
    idx.beginBlock(1, ArchiveSource.NORMAL);
    idx.allocateSystemTx(1, ArchivePhase.BLOCK_PREPARE);
    idx.allocateSystemTx(1, ArchivePhase.BLOCK_PREPARE);
    idx.allocateSystemTx(1, ArchivePhase.BLOCK_FINALIZE);

    ArchiveException ex = assertThrows(ArchiveException.class, () -> idx.commitBlock(1, 0));

    assertTrue(ex.getMessage().contains("exactly one prepare"));
    assertFalse(idx.getBlockRange(1).isPresent());
  }

  @Test
  public void userTxAfterFinalizeCannotPublishInMemoryRange() {
    ArchiveTxNumIndex idx = new InMemoryArchiveTxNumIndex();
    idx.beginBlock(1, ArchiveSource.NORMAL);
    idx.allocateSystemTx(1, ArchivePhase.BLOCK_PREPARE);
    idx.allocateSystemTx(1, ArchivePhase.BLOCK_FINALIZE);
    idx.allocateUserTx(1, 0, TX_A);

    ArchiveException ex = assertThrows(ArchiveException.class, () -> idx.commitBlock(1, 1));

    assertTrue(ex.getMessage().contains("finalize txNum must be last"));
    assertFalse(idx.getBlockRange(1).isPresent());
  }

  @Test
  public void duplicateUserTxIdInPendingBlockCannotPublishInMemoryRange() {
    ArchiveTxNumIndex idx = new InMemoryArchiveTxNumIndex();
    idx.beginBlock(1, ArchiveSource.NORMAL);
    idx.allocateSystemTx(1, ArchivePhase.BLOCK_PREPARE);
    idx.allocateUserTx(1, 0, TX_A);
    idx.allocateUserTx(1, 1, TX_A);
    idx.allocateSystemTx(1, ArchivePhase.BLOCK_FINALIZE);

    ArchiveException ex = assertThrows(ArchiveException.class, () -> idx.commitBlock(1, 2));

    assertTrue(ex.getMessage().contains("duplicate txId"));
    assertFalse(idx.getBlockRange(1).isPresent());
  }

  @Test
  public void duplicateCommittedTxIdCannotPublishInMemoryRange() {
    ArchiveTxNumIndex idx = new InMemoryArchiveTxNumIndex();
    commitTwoUserTxBlock(idx, 1);
    idx.beginBlock(2, ArchiveSource.NORMAL);
    idx.allocateSystemTx(2, ArchivePhase.BLOCK_PREPARE);
    idx.allocateUserTx(2, 0, TX_A);
    idx.allocateSystemTx(2, ArchivePhase.BLOCK_FINALIZE);

    ArchiveException ex = assertThrows(ArchiveException.class, () -> idx.commitBlock(2, 1));

    assertTrue(ex.getMessage().contains("duplicate txId already committed"));
    assertFalse(idx.getBlockRange(2).isPresent());
  }

  @Test
  public void firstBlockMayStartMidChainButNextCommitMustBeContiguous() {
    ArchiveTxNumIndex idx = new InMemoryArchiveTxNumIndex();
    commitTwoUserTxBlock(idx, 10);

    idx.beginBlock(12, ArchiveSource.NORMAL);
    idx.allocateSystemTx(12, ArchivePhase.BLOCK_PREPARE);
    idx.allocateSystemTx(12, ArchivePhase.BLOCK_FINALIZE);

    ArchiveException ex = assertThrows(ArchiveException.class, () -> idx.commitBlock(12, 0));
    assertTrue(ex.getMessage().contains("non-contiguous archive block range"));
    assertFalse(idx.getBlockRange(12).isPresent());
  }

  @Test
  public void duplicateCommittedBlockRejected() {
    ArchiveTxNumIndex idx = new InMemoryArchiveTxNumIndex();
    commitTwoUserTxBlock(idx, 10);

    idx.beginBlock(10, ArchiveSource.NORMAL);
    idx.allocateSystemTx(10, ArchivePhase.BLOCK_PREPARE);
    idx.allocateSystemTx(10, ArchivePhase.BLOCK_FINALIZE);

    ArchiveException ex = assertThrows(ArchiveException.class, () -> idx.commitBlock(10, 0));
    assertTrue(ex.getMessage().contains("already committed"));
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
  public void discardPublishedPrefixRetainsInFlightTailAndUnwindFloor() {
    InMemoryArchiveTxNumIndex idx = new InMemoryArchiveTxNumIndex();
    commitTwoUserTxBlock(idx, 5);
    ArchiveBlockRange first = idx.getBlockRange(5).orElseThrow(AssertionError::new);
    ArchiveBlockRange second = commitEmptyBlock(idx, 6);
    ArchiveBlockRange tail = commitEmptyBlock(idx, 7);

    idx.discardBlocksThrough(6);

    assertFalse(idx.getBlockRange(5).isPresent());
    assertFalse(idx.getBlockRange(6).isPresent());
    assertFalse(idx.getPosition(first.getFirstTxNum()).isPresent());
    assertFalse(idx.getPosition(second.getLastTxNum()).isPresent());
    assertFalse(idx.findTxNumByBlockAndIndex(5, 0).isPresent());
    assertFalse(idx.findTxNumByTxId(txId(9)).isPresent());
    assertTrue(idx.getBlockRange(7).isPresent());
    assertTrue(idx.getPosition(tail.getFirstTxNum()).isPresent());

    idx.unwindBlock(7);
    ArchiveBlockRange replacement = commitEmptyBlock(idx, 7);
    assertEquals(tail.getFirstTxNum(), replacement.getFirstTxNum());
    idx.discardBlocksThrough(7);
    assertFalse(idx.getBlockRange(7).isPresent());
    assertEquals(replacement.getLastTxNum() + 1, commitEmptyBlock(idx, 8).getFirstTxNum());
  }

  @Test
  public void unwindBackToEmptyClearsFirstArchivedBlock() {
    ArchiveTxNumIndex idx = new InMemoryArchiveTxNumIndex();
    commitTwoUserTxBlock(idx, 7);
    idx.unwindBlock(7);
    assertEquals(-1L, idx.getFirstArchivedBlock());

    ArchiveBlockRange range = commitEmptyBlock(idx, 9);
    assertEquals(0, range.getFirstTxNum());
    assertEquals(9, idx.getFirstArchivedBlock());
  }

  @Test
  public void validateCanonicalHeadAllowsEmptyArchive() {
    ArchiveTxNumIndex idx = new InMemoryArchiveTxNumIndex();

    idx.validateCanonicalHead(99, HASH_A);
  }

  @Test
  public void validateCanonicalHeadChecksBlockAndHash() {
    ArchiveTxNumIndex idx = new InMemoryArchiveTxNumIndex();
    commitEmptyBlock(idx, 10, HASH_A);

    idx.validateCanonicalHead(10, HASH_A);
    assertThrows(ArchiveException.class, () -> idx.validateCanonicalHead(9, HASH_A));
    assertThrows(ArchiveException.class, () -> idx.validateCanonicalHead(11, HASH_A));
    assertThrows(ArchiveException.class, () -> idx.validateCanonicalHead(10, HASH_B));
  }

  @Test
  public void validateCanonicalHeadRejectsCommittedRangeWithoutBlockHash() {
    ArchiveTxNumIndex idx = new InMemoryArchiveTxNumIndex();
    commitEmptyBlock(idx, 10);

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> idx.validateCanonicalHead(10, HASH_A));
    assertTrue(ex.getMessage().contains("32-byte block hash"));
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
    assertEquals(5, idx.findTxNumByTxId(txId(3)).getAsLong());
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
  public void userTxRequiresFullTxId() {
    ArchiveTxNumIndex idx = new InMemoryArchiveTxNumIndex();
    idx.beginBlock(1, ArchiveSource.NORMAL);

    assertThrows(ArchiveException.class, () -> idx.allocateUserTx(1, 0, null));
    assertThrows(ArchiveException.class, () -> idx.allocateUserTx(1, 0, new byte[] {1}));
  }

  @Test
  public void negativeBlockNumberRejected() {
    ArchiveTxNumIndex idx = new InMemoryArchiveTxNumIndex();
    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> idx.beginBlock(-1, ArchiveSource.NORMAL));
    assertTrue(ex.getMessage().contains("non-negative"));
    assertThrows(ArchiveException.class, () -> idx.getBlockRange(-1));
  }

  @Test
  public void negativeSeedCursorRejected() {
    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> new InMemoryArchiveTxNumIndex(-1));
    assertTrue(ex.getMessage().contains("non-negative"));
  }

  @Test
  public void allocatorAllowsTerminalCursorButNeverWraps() {
    InMemoryArchiveTxNumIndex idx = new InMemoryArchiveTxNumIndex(Long.MAX_VALUE - 2L);
    idx.beginBlock(1L, ArchiveSource.NORMAL);
    assertEquals(Long.MAX_VALUE - 2L,
        idx.allocateSystemTx(1L, ArchivePhase.BLOCK_PREPARE).getTxNum());
    assertEquals(Long.MAX_VALUE - 1L,
        idx.allocateSystemTx(1L, ArchivePhase.BLOCK_FINALIZE).getTxNum());

    ArchiveBlockRange terminal = idx.commitBlock(1L, 0);

    assertEquals(Long.MAX_VALUE - 1L, terminal.getLastTxNum());
    assertEquals(Long.MAX_VALUE, idx.getNextTxNum());
    idx.beginBlock(2L, ArchiveSource.NORMAL);
    ArchiveException exhausted = assertThrows(ArchiveException.class,
        () -> idx.allocateSystemTx(2L, ArchivePhase.BLOCK_PREPARE));
    assertTrue(exhausted.getMessage().contains("exhausted"));
    assertEquals(Long.MAX_VALUE, idx.getNextTxNum());
  }

  @Test
  public void reservedMaximumBlockNumberIsRejected() {
    ArchiveTxNumIndex idx = new InMemoryArchiveTxNumIndex();

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> idx.beginBlock(Long.MAX_VALUE, ArchiveSource.NORMAL));

    assertTrue(failure.getMessage().contains("maximum"));
  }

  @Test
  public void negativeReadCoordinatesRejected() {
    ArchiveTxNumIndex idx = new InMemoryArchiveTxNumIndex();

    assertThrows(ArchiveException.class, () -> idx.getBlockRange(-1));
    assertThrows(ArchiveException.class, () -> idx.getPosition(-1));
    assertThrows(ArchiveException.class, () -> idx.findTxNumByBlockAndIndex(-1, 0));
    assertThrows(ArchiveException.class, () -> idx.validateCanonicalHead(-1, HASH_A));
    assertThrows(ArchiveException.class, () -> idx.unwindBlock(-1));
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

  private static ArchiveBlockRange commitEmptyBlock(ArchiveTxNumIndex idx, long blockNum) {
    return commitEmptyBlock(idx, blockNum, new byte[0]);
  }

  private static ArchiveBlockRange commitEmptyBlock(ArchiveTxNumIndex idx, long blockNum,
      byte[] blockHash) {
    idx.beginBlock(blockNum, ArchiveSource.NORMAL);
    idx.allocateSystemTx(blockNum, ArchivePhase.BLOCK_PREPARE);
    idx.allocateSystemTx(blockNum, ArchivePhase.BLOCK_FINALIZE);
    return idx.commitBlock(blockNum, blockHash, 0);
  }

  private static byte[] blockHash(int seed) {
    byte[] hash = new byte[ArchiveBlockRange.BLOCK_HASH_LENGTH];
    hash[ArchiveBlockRange.BLOCK_HASH_LENGTH - 1] = (byte) seed;
    return hash;
  }

  private static byte[] txId(int seed) {
    byte[] txId = new byte[ArchiveBlockRangeCodec.TX_ID_LENGTH];
    txId[txId.length - 1] = (byte) seed;
    return txId;
  }
}
