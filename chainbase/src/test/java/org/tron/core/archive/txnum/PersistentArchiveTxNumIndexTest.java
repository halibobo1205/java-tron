package org.tron.core.archive.txnum;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveSource;

public class PersistentArchiveTxNumIndexTest {

  private static final byte[] TX_A = new byte[] {0x01, 0x02, 0x03};
  private static final byte[] TX_B = new byte[] {0x04, 0x05, 0x06};
  private static final byte[] HASH_A = blockHash(4);
  private static final byte[] HASH_B = blockHash(6);

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
    if (index != null) {
      index.close();
    }
    deleteRecursively(dir.toFile());
  }

  private ArchiveBlockRange pushBlock(long blockNum) {
    return pushBlock(blockNum, blockHash(blockNum));
  }

  private ArchiveBlockRange pushBlock(long blockNum, byte[] blockHash) {
    index.beginBlock(blockNum, ArchiveSource.NORMAL);
    index.allocateSystemTx(blockNum, ArchivePhase.BLOCK_PREPARE);
    index.allocateSystemTx(blockNum, ArchivePhase.BLOCK_FINALIZE);
    return index.commitBlock(blockNum, blockHash, 0);
  }

  private ArchiveBlockRange pushBlockWithUserTx(long blockNum, byte[] txId) {
    index.beginBlock(blockNum, ArchiveSource.NORMAL);
    index.allocateSystemTx(blockNum, ArchivePhase.BLOCK_PREPARE);
    index.allocateUserTx(blockNum, 0, txId);
    index.allocateSystemTx(blockNum, ArchivePhase.BLOCK_FINALIZE);
    return index.commitBlock(blockNum, blockHash(blockNum), 1);
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
  public void unwindNonHeadBlockRejectedWithoutRewindingCursor() {
    pushBlock(1);
    ArchiveBlockRange r2 = pushBlock(2);

    assertThrows(ArchiveException.class, () -> index.unwindBlock(1));
    assertTrue(index.getBlockRange(1).isPresent());
    assertTrue(index.getBlockRange(2).isPresent());
    ArchiveBlockRange r3 = pushBlock(3);
    assertEquals(r2.getLastTxNum() + 1, r3.getFirstTxNum());
  }

  @Test
  public void restartWithMissingPositionRowFailsClosed() {
    ArchiveBlockRange corruptRange = new ArchiveBlockRange(
        1, 0, 1, 0, 1, blockHash(1), 0, ArchiveSource.NORMAL);
    store.commitRange(corruptRange, 2, Collections.emptyList());
    index.close();
    index = null;
    store = new RocksDbArchiveBlockRangeStore(dir.toString());
    try {
      ArchiveException ex = assertThrows(ArchiveException.class,
          () -> new PersistentArchiveTxNumIndex(store));
      assertTrue(ex.getMessage().contains("tx-position missing"));
    } finally {
      store.close();
    }
  }

  @Test
  public void restartWithMismatchedPositionRowFailsClosed() {
    ArchiveBlockRange corruptRange = new ArchiveBlockRange(
        1, 0, 1, 0, 1, blockHash(1), 0, ArchiveSource.NORMAL);
    store.commitRange(corruptRange, 2, Arrays.asList(
        new ArchiveTxPosition(0, 99, ArchivePhase.USER_TX, ArchiveSource.NORMAL, 0, TX_A),
        new ArchiveTxPosition(1, 1, ArchivePhase.BLOCK_FINALIZE, ArchiveSource.NORMAL, -1, null)));
    index.close();
    index = null;
    store = new RocksDbArchiveBlockRangeStore(dir.toString());
    try {
      ArchiveException ex = assertThrows(ArchiveException.class,
          () -> new PersistentArchiveTxNumIndex(store));
      assertTrue(ex.getMessage().contains("tx-position mismatch"));
    } finally {
      store.close();
    }
  }

  @Test
  public void missingBlockIsEmpty() {
    assertFalse(index.getBlockRange(99).isPresent());
  }

  @Test
  public void storeInstallsSchemaManifestOnEmptyDb() throws Exception {
    index.close();
    index = null;
    store = null;

    try (Options options = new Options().setCreateIfMissing(false);
        RocksDB rawDb = RocksDB.open(options, dir.toString())) {
      assertArrayEquals(ArchiveBlockRangeCodec.manifestValue(),
          rawDb.get(ArchiveBlockRangeCodec.manifestKey()));
    }
  }

  @Test
  public void storeRejectsNonEmptyDbWithoutManifest() throws Exception {
    index.close();
    index = null;
    store = null;
    deleteRecursively(dir.toFile());
    Files.createDirectories(dir);
    try (Options options = new Options().setCreateIfMissing(true);
        RocksDB rawDb = RocksDB.open(options, dir.toString())) {
      rawDb.put(ArchiveBlockRangeCodec.CURSOR_KEY, ArchiveBlockRangeCodec.encodeCursor(0));
    }

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> new RocksDbArchiveBlockRangeStore(dir.toString()));

    assertTrue(ex.getMessage().contains("non-empty but missing manifest"));
  }

  @Test
  public void storeRejectsManifestMismatch() throws Exception {
    index.close();
    index = null;
    store = null;
    try (Options options = new Options().setCreateIfMissing(false);
        RocksDB rawDb = RocksDB.open(options, dir.toString())) {
      rawDb.put(ArchiveBlockRangeCodec.manifestKey(), new byte[] {1});
    }

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> new RocksDbArchiveBlockRangeStore(dir.toString()));

    assertTrue(ex.getMessage().contains("manifest mismatch"));
  }

  @Test
  public void storeMigratesSchemaOneManifestAndDeletesLegacyBlockIndexRows() throws Exception {
    ArchiveBlockRange range = pushBlockWithUserTx(10, TX_A);
    long userTxNum = range.getPrepareTxNum() + 1;
    index.close();
    index = null;
    store = null;
    try (Options options = new Options().setCreateIfMissing(false);
        RocksDB rawDb = RocksDB.open(options, dir.toString())) {
      rawDb.put(ArchiveBlockRangeCodec.manifestKey(),
          legacySchemaOneManifest());
      rawDb.put(legacyBlockIndexKey(10, 0), ArchiveBlockRangeCodec.encodeCursor(userTxNum));
    }

    store = new RocksDbArchiveBlockRangeStore(dir.toString());
    index = new PersistentArchiveTxNumIndex(store);

    assertEquals(userTxNum, index.findTxNumByBlockAndIndex(10, 0).getAsLong());
    assertEquals(userTxNum, index.findTxNumByTxId(TX_A).getAsLong());
    index.close();
    index = null;
    store = null;
    try (Options options = new Options().setCreateIfMissing(false);
        RocksDB rawDb = RocksDB.open(options, dir.toString());
        RocksIterator it = rawDb.newIterator()) {
      assertArrayEquals(ArchiveBlockRangeCodec.manifestValue(),
          rawDb.get(ArchiveBlockRangeCodec.manifestKey()));
      it.seek(new byte[] {ArchiveBlockRangeCodec.LEGACY_BLOCK_INDEX_PREFIX});
      assertFalse(it.isValid() && it.key()[0] == ArchiveBlockRangeCodec.LEGACY_BLOCK_INDEX_PREFIX);
    }
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
  public void unwindBackToEmptyClearsFirstArchivedBlockMarker() {
    pushBlock(7);
    index.unwindBlock(7);
    assertEquals(RocksDbArchiveBlockRangeStore.NO_FIRST_BLOCK, index.getFirstArchivedBlock());

    ArchiveBlockRange range = pushBlock(9);
    assertEquals(0, range.getFirstTxNum());
    assertEquals(9, index.getFirstArchivedBlock());
  }

  @Test
  public void firstBlockMayStartMidChainButNextCommitMustBeContiguous() {
    pushBlock(10);

    index.beginBlock(12, ArchiveSource.NORMAL);
    index.allocateSystemTx(12, ArchivePhase.BLOCK_PREPARE);
    index.allocateSystemTx(12, ArchivePhase.BLOCK_FINALIZE);

    ArchiveException ex = assertThrows(ArchiveException.class, () -> index.commitBlock(12, 0));
    assertTrue(ex.getMessage().contains("non-contiguous archive block range"));
    assertFalse(index.getBlockRange(12).isPresent());
    index.abortBlock(12);

    ArchiveBlockRange range11 = pushBlock(11);
    assertEquals(2, range11.getFirstTxNum());
  }

  @Test
  public void storeRejectsDirectNonContiguousCommit() {
    ArchiveBlockRange first = new ArchiveBlockRange(
        10, 0, 1, 0, 1, blockHash(10), 0, ArchiveSource.NORMAL);
    store.commitRange(first, 2);
    ArchiveBlockRange gap = new ArchiveBlockRange(
        12, 2, 3, 2, 3, blockHash(12), 0, ArchiveSource.NORMAL);

    ArchiveException ex = assertThrows(ArchiveException.class, () -> store.commitRange(gap, 4));
    assertTrue(ex.getMessage().contains("non-contiguous archive block range"));
    assertFalse(store.getRange(12).isPresent());
  }

  @Test
  public void storeRejectsDirectNonContiguousTxNumCommit() {
    ArchiveBlockRange first = new ArchiveBlockRange(
        10, 0, 1, 0, 1, blockHash(10), 0, ArchiveSource.NORMAL);
    store.commitRange(first, 2);
    ArchiveBlockRange gap = new ArchiveBlockRange(
        11, 4, 5, 4, 5, blockHash(11), 0, ArchiveSource.NORMAL);

    ArchiveException ex = assertThrows(ArchiveException.class, () -> store.commitRange(gap, 6));
    assertTrue(ex.getMessage().contains("non-contiguous archive txNum range"));
    assertFalse(store.getRange(11).isPresent());
  }

  @Test
  public void storeRejectsRangeWhoseSpanDoesNotMatchUserTxCount() {
    ArchiveBlockRange corruptRange = new ArchiveBlockRange(
        1, 0, 1, 0, 1, blockHash(1), 1, ArchiveSource.NORMAL);

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> store.commitRange(corruptRange, 2));
    assertTrue(ex.getMessage().contains("txNum span"));
    assertFalse(store.getRange(1).isPresent());
  }

  @Test
  public void storeRejectsRangeWithoutBlockHash() {
    ArchiveBlockRange corruptRange = new ArchiveBlockRange(
        1, 0, 1, 0, 1, 0, ArchiveSource.NORMAL);

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> store.commitRange(corruptRange, 2));
    assertTrue(ex.getMessage().contains("32-byte block hash"));
    assertFalse(store.getRange(1).isPresent());
  }

  @Test
  public void validateCanonicalHeadAllowsEmptyArchive() {
    index.validateCanonicalHead(99, HASH_A);
    store.validateCanonicalHead(99, HASH_A);
  }

  @Test
  public void validateCanonicalHeadChecksBlockAndHash() {
    pushBlock(10, HASH_A);

    index.validateCanonicalHead(10, HASH_A);
    store.validateCanonicalHead(10, HASH_A);
    assertThrows(ArchiveException.class, () -> index.validateCanonicalHead(9, HASH_A));
    assertThrows(ArchiveException.class, () -> index.validateCanonicalHead(11, HASH_A));
    assertThrows(ArchiveException.class, () -> index.validateCanonicalHead(10, HASH_B));
  }

  @Test
  public void restartWithGappedRangesFailsClosed() throws Exception {
    pushBlock(1);
    index.close();
    index = null;
    store = null;

    ArchiveBlockRange gap = new ArchiveBlockRange(
        3, 2, 3, 2, 3, blockHash(3), 0, ArchiveSource.NORMAL);
    putRawRange(gap, 4);

    RocksDbArchiveBlockRangeStore reopenedStore =
        new RocksDbArchiveBlockRangeStore(dir.toString());
    try {
      ArchiveException ex = assertThrows(ArchiveException.class,
          () -> new PersistentArchiveTxNumIndex(reopenedStore));
      assertTrue(ex.getMessage().contains("non-contiguous archive block range"));
    } finally {
      reopenedStore.close();
    }
  }

  @Test
  public void restartWithCorruptRangeShapeFailsClosed() throws Exception {
    index.close();
    index = null;
    store = null;

    ArchiveBlockRange corruptRange = new ArchiveBlockRange(
        1, 0, 1, 0, 1, blockHash(1), 1, ArchiveSource.NORMAL);
    putRawRange(corruptRange, 2, 1);

    RocksDbArchiveBlockRangeStore reopenedStore =
        new RocksDbArchiveBlockRangeStore(dir.toString());
    try {
      ArchiveException ex = assertThrows(ArchiveException.class,
          () -> new PersistentArchiveTxNumIndex(reopenedStore));
      assertTrue(ex.getMessage().contains("txNum span"));
    } finally {
      reopenedStore.close();
    }
  }

  @Test
  public void restartWithFirstBlockMarkerButNoRangeFailsClosed() throws Exception {
    index.close();
    index = null;
    store = null;

    putRawFirstBlockOnly(0);

    RocksDbArchiveBlockRangeStore reopenedStore =
        new RocksDbArchiveBlockRangeStore(dir.toString());
    try {
      ArchiveException ex = assertThrows(ArchiveException.class,
          () -> new PersistentArchiveTxNumIndex(reopenedStore));
      assertTrue(ex.getMessage().contains("first-block marker"));
    } finally {
      reopenedStore.close();
    }
  }

  @Test
  public void restartWithSwappedUserTxPositionOrderFailsClosed() {
    ArchiveBlockRange corruptRange = new ArchiveBlockRange(
        1, 0, 3, 0, 3, blockHash(1), 2, ArchiveSource.NORMAL);
    store.commitRange(corruptRange, 4, Arrays.asList(
        new ArchiveTxPosition(0, 1, ArchivePhase.BLOCK_PREPARE, ArchiveSource.NORMAL, -1, null),
        new ArchiveTxPosition(1, 1, ArchivePhase.USER_TX, ArchiveSource.NORMAL, 1, TX_B),
        new ArchiveTxPosition(2, 1, ArchivePhase.USER_TX, ArchiveSource.NORMAL, 0, TX_A),
        new ArchiveTxPosition(3, 1, ArchivePhase.BLOCK_FINALIZE, ArchiveSource.NORMAL, -1, null)));
    index.close();
    index = null;
    store = new RocksDbArchiveBlockRangeStore(dir.toString());
    try {
      ArchiveException ex = assertThrows(ArchiveException.class,
          () -> new PersistentArchiveTxNumIndex(store));
      assertTrue(ex.getMessage().contains("user tx-position order"));
    } finally {
      store.close();
    }
  }

  @Test
  public void restartWithSystemPositionTxIdFailsClosed() {
    ArchiveBlockRange corruptRange = new ArchiveBlockRange(
        1, 0, 1, 0, 1, blockHash(1), 0, ArchiveSource.NORMAL);
    store.commitRange(corruptRange, 2, Arrays.asList(
        new ArchiveTxPosition(0, 1, ArchivePhase.BLOCK_PREPARE, ArchiveSource.NORMAL, -1, TX_A),
        new ArchiveTxPosition(1, 1, ArchivePhase.BLOCK_FINALIZE, ArchiveSource.NORMAL, -1, null)));
    index.close();
    index = null;
    store = new RocksDbArchiveBlockRangeStore(dir.toString());
    try {
      ArchiveException ex = assertThrows(ArchiveException.class,
          () -> new PersistentArchiveTxNumIndex(store));
      assertTrue(ex.getMessage().contains("prepare tx-position mismatch"));
    } finally {
      store.close();
    }
  }

  @Test
  public void stalePositionOutsideCommittedRangesIsNotCommittedTxNum() throws Exception {
    ArchiveBlockRange range = new ArchiveBlockRange(
        1, 0, 1, 0, 1, blockHash(1), 0, ArchiveSource.NORMAL);
    store.commitRange(range, 2, Arrays.asList(
        new ArchiveTxPosition(0, 1, ArchivePhase.BLOCK_PREPARE, ArchiveSource.NORMAL, -1, null),
        new ArchiveTxPosition(1, 1, ArchivePhase.BLOCK_FINALIZE, ArchiveSource.NORMAL, -1, null)));

    index.close();
    index = null;
    store = null;
    putRawPosition(new ArchiveTxPosition(
        99, 99, ArchivePhase.USER_TX, ArchiveSource.NORMAL, 0, TX_A));
    RocksDbArchiveBlockRangeStore reopenedStore =
        new RocksDbArchiveBlockRangeStore(dir.toString());

    try {
      assertFalse(reopenedStore.hasCommittedTxNum(99));
    } finally {
      reopenedStore.close();
    }
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
  public void blockAndIndexLookupIsDerivedWithoutPersistedBlockIndexRows() throws Exception {
    ArchiveBlockRange range = pushBlockWithUserTx(10, TX_A);
    long userTxNum = range.getPrepareTxNum() + 1;

    assertEquals(userTxNum, index.findTxNumByBlockAndIndex(10, 0).getAsLong());
    index.close();
    index = null;
    store = null;

    try (Options options = new Options().setCreateIfMissing(false);
        RocksDB rawDb = RocksDB.open(options, dir.toString());
        RocksIterator it = rawDb.newIterator()) {
      it.seek(new byte[] {ArchiveBlockRangeCodec.LEGACY_BLOCK_INDEX_PREFIX});
      assertFalse(it.isValid() && it.key()[0] == ArchiveBlockRangeCodec.LEGACY_BLOCK_INDEX_PREFIX);
    }

    store = new RocksDbArchiveBlockRangeStore(dir.toString());
    index = new PersistentArchiveTxNumIndex(store);
    assertEquals(userTxNum, index.findTxNumByBlockAndIndex(10, 0).getAsLong());
  }

  @Test
  public void unwindAfterRestartRemovesPersistedPositionAndTxIdIndexes() {
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

  private void putRawRange(ArchiveBlockRange range, long cursor) throws RocksDBException {
    putRawRange(range, cursor, null);
  }

  private void putRawRange(ArchiveBlockRange range, long cursor, Integer firstBlock)
      throws RocksDBException {
    RocksDB.loadLibrary();
    try (Options options = new Options().setCreateIfMissing(false);
        RocksDB rawDb = RocksDB.open(options, dir.toString())) {
      rawDb.put(ArchiveBlockRangeCodec.rangeKey(range.getBlockNum()),
          ArchiveBlockRangeCodec.encodeRange(range));
      rawDb.put(ArchiveBlockRangeCodec.CURSOR_KEY, ArchiveBlockRangeCodec.encodeCursor(cursor));
      if (firstBlock != null) {
        rawDb.put(ArchiveBlockRangeCodec.FIRST_BLOCK_KEY,
            ArchiveBlockRangeCodec.encodeFirstBlock(firstBlock));
      }
    }
  }

  private void putRawFirstBlockOnly(long firstBlock) throws RocksDBException {
    RocksDB.loadLibrary();
    try (Options options = new Options().setCreateIfMissing(false);
        RocksDB rawDb = RocksDB.open(options, dir.toString())) {
      rawDb.put(ArchiveBlockRangeCodec.CURSOR_KEY, ArchiveBlockRangeCodec.encodeCursor(0));
      rawDb.put(ArchiveBlockRangeCodec.FIRST_BLOCK_KEY,
          ArchiveBlockRangeCodec.encodeFirstBlock(firstBlock));
    }
  }

  private void putRawPosition(ArchiveTxPosition position) throws RocksDBException {
    RocksDB.loadLibrary();
    try (Options options = new Options().setCreateIfMissing(false);
        RocksDB rawDb = RocksDB.open(options, dir.toString())) {
      rawDb.put(ArchiveBlockRangeCodec.positionKey(position.getTxNum()),
          ArchiveBlockRangeCodec.encodePosition(position));
    }
  }

  private static byte[] legacySchemaOneManifest() {
    return "tron-archive-txnum|schema=1|model=range-position-index-v1|prefix=legacy-0x00-0x06"
        .getBytes(StandardCharsets.US_ASCII);
  }

  private static byte[] legacyBlockIndexKey(long blockNum, int txIndex) {
    byte[] key = new byte[13];
    key[0] = ArchiveBlockRangeCodec.LEGACY_BLOCK_INDEX_PREFIX;
    putLong(key, 1, blockNum);
    putInt(key, 9, txIndex);
    return key;
  }

  private static void putLong(byte[] key, int offset, long value) {
    for (int i = Long.BYTES - 1; i >= 0; i--) {
      key[offset + i] = (byte) value;
      value >>>= Byte.SIZE;
    }
  }

  private static void putInt(byte[] key, int offset, int value) {
    for (int i = Integer.BYTES - 1; i >= 0; i--) {
      key[offset + i] = (byte) value;
      value >>>= Byte.SIZE;
    }
  }

  private static byte[] blockHash(long seed) {
    byte[] hash = new byte[ArchiveBlockRange.BLOCK_HASH_LENGTH];
    hash[ArchiveBlockRange.BLOCK_HASH_LENGTH - 1] = (byte) seed;
    return hash;
  }
}
