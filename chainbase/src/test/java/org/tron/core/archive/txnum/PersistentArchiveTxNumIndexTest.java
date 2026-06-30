package org.tron.core.archive.txnum;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveSource;

public class PersistentArchiveTxNumIndexTest {

  private static final byte[] TX_A = new byte[] {0x01, 0x02, 0x03};

  private Path dir;
  private RocksDbArchiveBlockRangeStore store;
  private PersistentArchiveTxNumIndex index;

  @Before
  public void setUp() throws IOException {
    dir = Files.createTempDirectory("archive-txnum-test");
    store = new RocksDbArchiveBlockRangeStore(dir.toString());
    index = new PersistentArchiveTxNumIndex(store);
  }

  @After
  public void tearDown() {
    index.close();
    deleteRecursively(dir.toFile());
  }

  private ArchiveBlockRange pushBlock(long blockNum) {
    index.beginBlock(blockNum, ArchiveSource.NORMAL);
    index.allocateSystemTx(blockNum, ArchivePhase.BLOCK_PREPARE);
    index.allocateSystemTx(blockNum, ArchivePhase.BLOCK_FINALIZE);
    return index.commitBlock(blockNum, 0);
  }

  private ArchiveBlockRange pushBlockWithUserTx(long blockNum, byte[] txId) {
    index.beginBlock(blockNum, ArchiveSource.NORMAL);
    index.allocateSystemTx(blockNum, ArchivePhase.BLOCK_PREPARE);
    index.allocateUserTx(blockNum, 0, txId);
    index.allocateSystemTx(blockNum, ArchivePhase.BLOCK_FINALIZE);
    return index.commitBlock(blockNum, 1);
  }

  @Test
  public void commitPersistsRangeAndCursorAcrossRestart() {
    ArchiveBlockRange r1 = pushBlock(1);
    ArchiveBlockRange r2 = pushBlock(2);
    // Restart: a fresh index over the same store sees the committed ranges + restored cursor.
    index.close();
    store = new RocksDbArchiveBlockRangeStore(dir.toString());
    index = new PersistentArchiveTxNumIndex(store);
    assertEquals(r1.getFinalizeTxNum(), index.getBlockRange(1).get().getFinalizeTxNum());
    assertEquals(r2.getFinalizeTxNum(), index.getBlockRange(2).get().getFinalizeTxNum());
    // Cursor restored: the next block continues from block 2's end, no txNum collision.
    ArchiveBlockRange r3 = pushBlock(3);
    assertEquals(r2.getLastTxNum() + 1, r3.getFirstTxNum());
  }

  @Test
  public void unwindRemovesPersistedRangeAndRewindsCursor() {
    ArchiveBlockRange r1 = pushBlock(1);
    pushBlock(2);
    index.unwindBlock(2);
    assertFalse(index.getBlockRange(2).isPresent());
    assertTrue(index.getBlockRange(1).isPresent());
    // Cursor rewound: re-pushing block 2 reuses the freed txNums.
    ArchiveBlockRange r2b = pushBlock(2);
    assertEquals(r1.getLastTxNum() + 1, r2b.getFirstTxNum());
  }

  @Test
  public void missingBlockIsEmpty() {
    assertFalse(index.getBlockRange(99).isPresent());
  }

  @Test
  public void firstArchivedBlockIsLowestCommittedAndSurvivesRestart() {
    assertEquals(RocksDbArchiveBlockRangeStore.NO_FIRST_BLOCK, index.getFirstArchivedBlock());
    pushBlock(7); // a mid-chain start: the first commit records 7 as the coverage floor
    pushBlock(8);
    assertEquals(7, index.getFirstArchivedBlock());
    // Restart: the floor survives and a later commit must NOT overwrite it.
    index.close();
    store = new RocksDbArchiveBlockRangeStore(dir.toString());
    index = new PersistentArchiveTxNumIndex(store);
    assertEquals(7, index.getFirstArchivedBlock());
    pushBlock(9);
    assertEquals(7, index.getFirstArchivedBlock());
  }

  @Test
  public void txPositionAndTxIdLookupSurviveRestart() {
    ArchiveBlockRange range = pushBlockWithUserTx(10, TX_A);
    long userTxNum = range.getPrepareTxNum() + 1;
    assertEquals(userTxNum, index.findTxNumByTxId(TX_A).getAsLong());
    assertEquals(userTxNum, index.findTxNumByBlockAndIndex(10, 0).getAsLong());

    index.close();
    store = new RocksDbArchiveBlockRangeStore(dir.toString());
    index = new PersistentArchiveTxNumIndex(store);

    assertEquals(userTxNum, index.findTxNumByTxId(TX_A).getAsLong());
    assertEquals(userTxNum, index.findTxNumByBlockAndIndex(10, 0).getAsLong());
    ArchiveTxPosition position = index.getPosition(userTxNum).get();
    assertEquals(10, position.getBlockNum());
    assertEquals(ArchivePhase.USER_TX, position.getPhase());
    assertEquals(0, position.getTxIndex());
    assertArrayEquals(TX_A, position.getTxId());
  }

  @Test
  public void unwindAfterRestartRemovesPersistedTxIndexes() {
    ArchiveBlockRange range = pushBlockWithUserTx(10, TX_A);
    long userTxNum = range.getPrepareTxNum() + 1;

    index.close();
    store = new RocksDbArchiveBlockRangeStore(dir.toString());
    index = new PersistentArchiveTxNumIndex(store);

    index.unwindBlock(10);
    assertFalse(index.getBlockRange(10).isPresent());
    assertFalse(index.getPosition(userTxNum).isPresent());
    assertFalse(index.findTxNumByTxId(TX_A).isPresent());
    assertFalse(index.findTxNumByBlockAndIndex(10, 0).isPresent());
    ArchiveBlockRange recommitted = pushBlock(10);
    assertEquals(range.getFirstTxNum(), recommitted.getFirstTxNum());
  }

  private static void deleteRecursively(File f) {
    File[] children = f.listFiles();
    if (children != null) {
      for (File c : children) {
        deleteRecursively(c);
      }
    }
    f.delete();
  }
}
