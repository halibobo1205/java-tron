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
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.tron.common.utils.ReflectUtils;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveSource;

public class PersistentArchiveTxNumIndexTest {

  private static final byte[] TX_A = txId(1);
  private static final byte[] TX_B = txId(2);
  private static final byte[] HASH_A = blockHash(4);
  private static final byte[] HASH_B = blockHash(6);
  private static final byte[] SCHEMA_CHECKSUM = checksum(9);

  private Path dir;
  private RocksDbArchiveBlockRangeStore store;
  private PersistentArchiveTxNumIndex index;

  @Before
  public void setUp() throws IOException {
    dir = Files.createTempDirectory("archive-txnum-test");
    store = new RocksDbArchiveBlockRangeStore(dir.toString());
    index = persistent(store);
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

  private static PersistentArchiveTxNumIndex persistent(RocksDbArchiveBlockRangeStore store) {
    return persistent(store, SCHEMA_CHECKSUM);
  }

  private static PersistentArchiveTxNumIndex persistent(RocksDbArchiveBlockRangeStore store,
      byte[] schemaChecksum) {
    return new PersistentArchiveTxNumIndex(store, schemaChecksum);
  }

  private static ArchiveBlockRange withChecksum(ArchiveBlockRange range) {
    return new ArchiveBlockRange(range.getBlockNum(), range.getFirstTxNum(),
        range.getLastTxNum(), range.getPrepareTxNum(), range.getFinalizeTxNum(),
        range.getBlockHash(), range.getUserTxCount(), range.getSource(), SCHEMA_CHECKSUM);
  }

  private static ArchiveTxPosition withBlockHash(ArchiveTxPosition position, byte[] blockHash) {
    return new ArchiveTxPosition(position.getTxNum(), position.getBlockNum(),
        position.getPhase(), position.getSource(), position.getTxIndex(), position.getTxId(),
        blockHash);
  }

  @Test
  public void commitPersistsRangeAndCursorAcrossRestart() {
    ArchiveBlockRange r1 = pushBlock(1);
    ArchiveBlockRange r2 = pushBlock(2);
    // Restart: a fresh index over the same store sees the committed ranges + restored cursor.
    index.close();
    store = new RocksDbArchiveBlockRangeStore(dir.toString());
    index = persistent(store);
    assertEquals(r1.getFinalizeTxNum(), index.getBlockRange(1).get().getFinalizeTxNum());
    assertEquals(r2.getFinalizeTxNum(), index.getBlockRange(2).get().getFinalizeTxNum());
    // Cursor restored: the next block continues from block 2's end, no txNum collision.
    ArchiveBlockRange r3 = pushBlock(3);
    assertEquals(r2.getLastTxNum() + 1, r3.getFirstTxNum());
  }

  @Test
  public void commitDropsTransientDelegateRowsAfterPersistence() {
    ArchiveBlockRange range = pushBlockWithUserTx(1, TX_A);

    InMemoryArchiveTxNumIndex delegate = ReflectUtils.getFieldValue(index, "inner");

    assertFalse(delegate.getBlockRange(1).isPresent());
    assertFalse(delegate.getPosition(range.getFirstTxNum()).isPresent());
    assertFalse(delegate.findTxNumByBlockAndIndex(1, 0).isPresent());
    assertFalse(delegate.findTxNumByTxId(TX_A).isPresent());
    assertEquals(range.getLastTxNum() + 1, delegate.getNextTxNum());
    assertTrue(index.getBlockRange(1).isPresent());
    assertTrue(index.findTxNumByTxId(TX_A).isPresent());
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
  public void restartWithMissingPositionRowFailsClosed() throws Exception {
    ArchiveBlockRange corruptRange = new ArchiveBlockRange(
        1, 0, 1, 0, 1, blockHash(1), 0, ArchiveSource.NORMAL);
    index.close();
    index = null;
    store = null;
    putRawRange(corruptRange, 2, 1);
    store = new RocksDbArchiveBlockRangeStore(dir.toString());
    try {
      ArchiveException ex = assertThrows(ArchiveException.class,
          () -> persistent(store));
      assertTrue(ex.getMessage().contains("tx-position missing"));
    } finally {
      store.close();
    }
  }

  @Test
  public void storeRejectsMismatchedPositionRow() {
    ArchiveBlockRange corruptRange = new ArchiveBlockRange(
        1, 0, 1, 0, 1, blockHash(1), 0, ArchiveSource.NORMAL);

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> store.commitRange(withChecksum(corruptRange), 2, Arrays.asList(
            new ArchiveTxPosition(0, 99, ArchivePhase.USER_TX, ArchiveSource.NORMAL, 0, TX_A),
            new ArchiveTxPosition(1, 1, ArchivePhase.BLOCK_FINALIZE, ArchiveSource.NORMAL, -1,
                null))));

    assertTrue(ex.getMessage().contains("tx-position mismatch"));
    assertFalse(store.getRange(1).isPresent());
  }

  @Test
  public void storeRejectsUserPositionWithoutFullTxId() {
    ArchiveBlockRange corruptRange = new ArchiveBlockRange(
        1, 0, 2, 0, 2, blockHash(1), 1, ArchiveSource.NORMAL);

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> store.commitRange(withChecksum(corruptRange), 3, Arrays.asList(
            new ArchiveTxPosition(0, 1, ArchivePhase.BLOCK_PREPARE, ArchiveSource.NORMAL, -1,
                null),
            new ArchiveTxPosition(1, 1, ArchivePhase.USER_TX, ArchiveSource.NORMAL, 0, null),
            new ArchiveTxPosition(2, 1, ArchivePhase.BLOCK_FINALIZE, ArchiveSource.NORMAL, -1,
                null))));

    assertTrue(ex.getMessage().contains("32-byte txId"));
    assertFalse(store.getRange(1).isPresent());
  }

  @Test
  public void missingBlockIsEmpty() {
    assertFalse(index.getBlockRange(99).isPresent());
  }

  @Test
  public void negativeReadCoordinatesRejected() {
    assertThrows(ArchiveException.class, () -> index.getBlockRange(-1));
    assertThrows(ArchiveException.class, () -> index.getPosition(-1));
    assertThrows(ArchiveException.class, () -> index.findTxNumByBlockAndIndex(-1, 0));
    assertThrows(ArchiveException.class, () -> index.validateCanonicalHead(-1, HASH_A));
    assertThrows(ArchiveException.class, () -> index.unwindBlock(-1));
  }

  @Test
  public void storeInstallsSchemaManifestOnEmptyDb() throws Exception {
    index.close();
    index = null;
    store = null;

    try (Options options = new Options().setCreateIfMissing(true);
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
    try (Options options = new Options().setCreateIfMissing(true);
        RocksDB rawDb = RocksDB.open(options, dir.toString())) {
      rawDb.put(ArchiveBlockRangeCodec.manifestKey(), new byte[] {1});
    }

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> new RocksDbArchiveBlockRangeStore(dir.toString()));

    assertTrue(ex.getMessage().contains("manifest mismatch"));
  }

  @Test
  public void storeRejectsNonEmptySchemaOneStoreWithoutValueMigration() throws Exception {
    index.close();
    index = null;
    store = null;
    writeLegacyTxNumFixture(legacySchemaOneManifest());

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> new RocksDbArchiveBlockRangeStore(dir.toString()));

    assertTrue(ex.getMessage().contains("requires rebuild or resync"));
  }

  @Test
  public void storeRejectsNonEmptySchemaTwoStoreWithoutValueMigration() throws Exception {
    index.close();
    index = null;
    store = null;
    writeLegacyTxNumFixture(legacySchemaTwoManifest());

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> new RocksDbArchiveBlockRangeStore(dir.toString()));

    assertTrue(ex.getMessage().contains("requires rebuild or resync"));
  }

  @Test
  public void storeUpgradesManifestOnlySchemaOneStore() throws Exception {
    verifyManifestOnlyLegacyStoreUpgrades(ArchiveBlockRangeCodec.legacyManifestKey(),
        legacySchemaOneManifest());
  }

  @Test
  public void storeUpgradesManifestOnlySchemaTwoStore() throws Exception {
    verifyManifestOnlyLegacyStoreUpgrades(ArchiveBlockRangeCodec.legacyManifestKey(),
        legacySchemaTwoManifest());
  }

  @Test
  public void storeUpgradesManifestOnlySchemaThreeStore() throws Exception {
    verifyManifestOnlyLegacyStoreUpgrades(ArchiveBlockRangeCodec.manifestKey(),
        legacySchemaThreeManifest());
  }

  @Test
  public void storeRejectsNonEmptySchemaThreeStoreWithoutValueMigration() throws Exception {
    index.close();
    index = null;
    store = null;
    deleteRecursively(dir.toFile());
    Files.createDirectories(dir);
    try (Options options = new Options().setCreateIfMissing(true);
        RocksDB rawDb = RocksDB.open(options, dir.toString())) {
      rawDb.put(ArchiveBlockRangeCodec.manifestKey(), legacySchemaThreeManifest());
      rawDb.put(ArchiveBlockRangeCodec.CURSOR_KEY, ArchiveBlockRangeCodec.encodeCursor(0));
    }

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> new RocksDbArchiveBlockRangeStore(dir.toString()));

    assertTrue(ex.getMessage().contains("schema=3 requires rebuild or resync"));
  }

  @Test
  public void storeRejectsCurrentManifestWithLegacyRows() throws Exception {
    index.close();
    index = null;
    store = null;
    try (Options options = new Options().setCreateIfMissing(false);
        RocksDB rawDb = RocksDB.open(options, dir.toString())) {
      rawDb.put(ArchiveBlockRangeCodec.legacyManifestKey(), legacySchemaOneManifest());
    }

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> new RocksDbArchiveBlockRangeStore(dir.toString()));

    assertTrue(ex.getMessage().contains("legacy schema row"));
  }

  @Test
  public void storeRejectsCurrentManifestWithUnknownPrefixRows() throws Exception {
    index.close();
    index = null;
    store = null;
    try (Options options = new Options().setCreateIfMissing(false);
        RocksDB rawDb = RocksDB.open(options, dir.toString())) {
      rawDb.put(new byte[] {0x7f}, new byte[] {1});
    }

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> new RocksDbArchiveBlockRangeStore(dir.toString()));

    assertTrue(ex.getMessage().contains("unknown key prefix"));
  }

  @Test
  public void storeRejectsCurrentManifestWithCorruptCursorValue() throws Exception {
    index.close();
    index = null;
    store = null;
    putRawTxNumRow(ArchiveBlockRangeCodec.CURSOR_KEY, new byte[] {1});

    assertStoreOpenFails("cursor value");
  }

  @Test
  public void storeRejectsCurrentManifestWithNegativeCursorValue() throws Exception {
    index.close();
    index = null;
    store = null;
    putRawTxNumRow(ArchiveBlockRangeCodec.CURSOR_KEY, rawLong(-1));

    assertStoreOpenFails("cursor value");
  }

  @Test
  public void storeRejectsCurrentManifestWithCorruptFirstBlockValue() throws Exception {
    index.close();
    index = null;
    store = null;
    putRawTxNumRow(ArchiveBlockRangeCodec.FIRST_BLOCK_KEY, new byte[] {1});

    assertStoreOpenFails("first-block value");
  }

  @Test
  public void storeRejectsCurrentManifestWithNegativeFirstBlockValue() throws Exception {
    index.close();
    index = null;
    store = null;
    putRawTxNumRow(ArchiveBlockRangeCodec.FIRST_BLOCK_KEY, rawLong(-1));

    assertStoreOpenFails("first-block value");
  }

  @Test
  public void storeRejectsCurrentManifestWithCorruptRepairMarkerValue() throws Exception {
    index.close();
    index = null;
    store = null;
    putRawTxNumRow(ArchiveBlockRangeCodec.REPAIR_REQUIRED_KEY,
        new byte[] {(byte) 0xc3, 0x28});

    assertStoreOpenFails("repair marker value");
  }

  @Test
  public void storeRejectsCurrentManifestWithCorruptRangeValue() throws Exception {
    index.close();
    index = null;
    store = null;
    putRawTxNumRow(ArchiveBlockRangeCodec.rangeKey(1), new byte[] {1});

    assertStoreOpenFails("block range value");
  }

  @Test
  public void storeRejectsCurrentManifestWithNegativeRangeKey() throws Exception {
    index.close();
    index = null;
    store = null;
    byte[] key = new byte[1 + Long.BYTES];
    key[0] = ArchiveBlockRangeCodec.TXNUM_BLOCK_PREFIX;
    putLong(key, 1, -1L);
    ArchiveBlockRange range = new ArchiveBlockRange(
        1, 0, 1, 0, 1, blockHash(1), 0, ArchiveSource.NORMAL);
    putRawTxNumRow(key, ArchiveBlockRangeCodec.encodeRange(withChecksum(range)));

    assertStoreOpenFails("block number");
  }

  @Test
  public void storeRejectsCurrentManifestWithNegativeRangeTxNum() throws Exception {
    index.close();
    index = null;
    store = null;
    ArchiveBlockRange range = new ArchiveBlockRange(
        1, 0, 1, 0, 1, blockHash(1), 0, ArchiveSource.NORMAL);
    byte[] encoded = ArchiveBlockRangeCodec.encodeRange(withChecksum(range));
    putLong(encoded, 1 + 4 * Long.BYTES, -1L);
    putRawTxNumRow(ArchiveBlockRangeCodec.rangeKey(1), encoded);

    ArchiveException ex = assertThrows(ArchiveException.class, () -> {
      try (RocksDbArchiveBlockRangeStore corruptedStore =
          new RocksDbArchiveBlockRangeStore(dir.toString())) {
        persistent(corruptedStore);
      }
    });
    assertTrue(ex.getMessage(), ex.getMessage().contains("non-negative"));
  }

  @Test
  public void storeRejectsCurrentManifestWithCorruptPositionValue() throws Exception {
    index.close();
    index = null;
    store = null;
    putRawTxNumRow(ArchiveBlockRangeCodec.positionKey(0), new byte[] {1});

    assertStoreOpenFails("tx-position value");
  }

  @Test
  public void storeRejectsCurrentManifestWithUserPositionMissingTxId() throws Exception {
    index.close();
    index = null;
    store = null;
    ArchiveBlockRange range = new ArchiveBlockRange(
        1, 0, 2, 0, 2, blockHash(1), 1, ArchiveSource.NORMAL);
    byte[] encoded = ArchiveBlockRangeCodec.encodePosition(new ArchiveTxPosition(
        1, 1, ArchivePhase.USER_TX, ArchiveSource.NORMAL, 0, TX_A, blockHash(1)));
    putInt(encoded, 23, 0);
    byte[] truncated = Arrays.copyOf(encoded, encoded.length - TX_A.length);
    putRawTxNumRow(ArchiveBlockRangeCodec.rangeKey(1),
        ArchiveBlockRangeCodec.encodeRange(withChecksum(range)));
    putRawTxNumRow(ArchiveBlockRangeCodec.CURSOR_KEY, ArchiveBlockRangeCodec.encodeCursor(3));
    putRawTxNumRow(ArchiveBlockRangeCodec.FIRST_BLOCK_KEY,
        ArchiveBlockRangeCodec.encodeFirstBlock(1));
    putRawTxNumRow(ArchiveBlockRangeCodec.positionKey(0),
        ArchiveBlockRangeCodec.encodePosition(new ArchiveTxPosition(
            0, 1, ArchivePhase.BLOCK_PREPARE, ArchiveSource.NORMAL, -1, null, blockHash(1))));
    putRawTxNumRow(ArchiveBlockRangeCodec.positionKey(1), truncated);
    putRawTxNumRow(ArchiveBlockRangeCodec.positionKey(2),
        ArchiveBlockRangeCodec.encodePosition(new ArchiveTxPosition(
            2, 1, ArchivePhase.BLOCK_FINALIZE, ArchiveSource.NORMAL, -1, null, blockHash(1))));

    assertStoreOpenFails("32-byte txId");
  }

  @Test
  public void storeRejectsCurrentManifestWithCorruptTxIdValue() throws Exception {
    index.close();
    index = null;
    store = null;
    putRawTxNumRow(ArchiveBlockRangeCodec.txIdKey(TX_A), new byte[] {1});

    assertStoreOpenFails("cursor value");
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
    index = persistent(store);
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
    store.commitRange(withChecksum(first), 2);
    ArchiveBlockRange gap = new ArchiveBlockRange(
        12, 2, 3, 2, 3, blockHash(12), 0, ArchiveSource.NORMAL);

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> store.commitRange(withChecksum(gap), 4));
    assertTrue(ex.getMessage().contains("non-contiguous archive block range"));
    assertFalse(store.getRange(12).isPresent());
  }

  @Test
  public void storeRejectsDirectNonContiguousTxNumCommit() {
    ArchiveBlockRange first = new ArchiveBlockRange(
        10, 0, 1, 0, 1, blockHash(10), 0, ArchiveSource.NORMAL);
    store.commitRange(withChecksum(first), 2);
    ArchiveBlockRange gap = new ArchiveBlockRange(
        11, 4, 5, 4, 5, blockHash(11), 0, ArchiveSource.NORMAL);

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> store.commitRange(withChecksum(gap), 6));
    assertTrue(ex.getMessage().contains("non-contiguous archive txNum range"));
    assertFalse(store.getRange(11).isPresent());
  }

  @Test
  public void storeRejectsDirectNonHeadUnwind() {
    ArchiveBlockRange first = new ArchiveBlockRange(
        10, 0, 1, 0, 1, blockHash(10), 0, ArchiveSource.NORMAL);
    ArchiveBlockRange second = new ArchiveBlockRange(
        11, 2, 3, 2, 3, blockHash(11), 0, ArchiveSource.NORMAL);
    store.commitRange(withChecksum(first), 2);
    store.commitRange(withChecksum(second), 4);

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> store.unwindRange(withChecksum(first), 0));

    assertTrue(ex.getMessage().contains("not archive head"));
    assertTrue(store.getRange(10).isPresent());
    assertTrue(store.getRange(11).isPresent());
  }

  @Test
  public void storeRejectsRangeWhoseSpanDoesNotMatchUserTxCount() {
    ArchiveBlockRange corruptRange = new ArchiveBlockRange(
        1, 0, 1, 0, 1, blockHash(1), 1, ArchiveSource.NORMAL);

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> store.commitRange(withChecksum(corruptRange), 2));
    assertTrue(ex.getMessage().contains("txNum span"));
    assertFalse(store.getRange(1).isPresent());
  }

  @Test
  public void storeRejectsRangeWithoutBlockHash() {
    ArchiveBlockRange corruptRange = new ArchiveBlockRange(
        1, 0, 1, 0, 1, 0, ArchiveSource.NORMAL);

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> store.commitRange(withChecksum(corruptRange), 2));
    assertTrue(ex.getMessage().contains("32-byte block hash"));
    assertFalse(store.getRange(1).isPresent());
  }

  @Test
  public void storeRejectsRangeWithoutSchemaChecksum() {
    ArchiveBlockRange corruptRange = new ArchiveBlockRange(
        1, 0, 1, 0, 1, blockHash(1), 0, ArchiveSource.NORMAL);

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> store.commitRange(corruptRange, 2));
    assertTrue(ex.getMessage().contains("32-byte schema checksum"));
    assertFalse(store.getRange(1).isPresent());
  }

  @Test
  public void storeRejectsNegativeBlockNumber() {
    ArchiveBlockRange corruptRange = new ArchiveBlockRange(
        -1, 0, 1, 0, 1, blockHash(1), 0, ArchiveSource.NORMAL);

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> store.commitRange(withChecksum(corruptRange), 2));
    assertTrue(ex.getMessage().contains("negative block number"));
    assertThrows(ArchiveException.class, () -> store.getRange(-1));
  }

  @Test
  public void commitRejectsMismatchedSchemaChecksum() {
    index.beginBlock(1, ArchiveSource.NORMAL);
    index.allocateSystemTx(1, ArchivePhase.BLOCK_PREPARE);
    index.allocateSystemTx(1, ArchivePhase.BLOCK_FINALIZE);

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> index.commitBlock(1, blockHash(1), 0, checksum(10)));

    assertTrue(ex.getMessage().contains("schema checksum mismatch"));
    assertFalse(index.getBlockRange(1).isPresent());
    index.abortBlock(1);
  }

  @Test
  public void restartRejectsMismatchedSchemaChecksum() {
    pushBlock(1);
    index.close();
    index = null;
    store = new RocksDbArchiveBlockRangeStore(dir.toString());
    try {
      ArchiveException ex = assertThrows(ArchiveException.class,
          () -> persistent(store, checksum(10)));
      assertTrue(ex.getMessage().contains("schema checksum mismatch"));
    } finally {
      store.close();
      store = null;
    }
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
          () -> persistent(reopenedStore));
      assertTrue(ex.getMessage().contains("non-contiguous archive block range"));
    } finally {
      reopenedStore.close();
    }
  }

  @Test
  public void restartWithCursorButNoCommittedRangeFailsClosed() throws Exception {
    index.close();
    index = null;
    store = null;

    // A non-zero committed cursor with no range/first-block rows at all.
    putRawTxNumRow(ArchiveBlockRangeCodec.CURSOR_KEY, ArchiveBlockRangeCodec.encodeCursor(5));

    RocksDbArchiveBlockRangeStore reopenedStore =
        new RocksDbArchiveBlockRangeStore(dir.toString());
    try {
      ArchiveException ex = assertThrows(ArchiveException.class,
          () -> persistent(reopenedStore));
      assertTrue(ex.getMessage().contains("cursor 5 exists without a committed block range"));
    } finally {
      reopenedStore.close();
    }
  }

  @Test
  public void restartWithCursorInconsistentWithLastRangeFailsClosed() throws Exception {
    index.close();
    index = null;
    store = null;

    ArchiveBlockRange range = new ArchiveBlockRange(
        1, 0, 1, 0, 1, blockHash(1), 0, ArchiveSource.NORMAL);
    // Range's true cursor is 2, but persist a present-yet-inconsistent cursor value.
    putRawRange(range, 5, 1);

    RocksDbArchiveBlockRangeStore reopenedStore =
        new RocksDbArchiveBlockRangeStore(dir.toString());
    try {
      ArchiveException ex = assertThrows(ArchiveException.class,
          () -> persistent(reopenedStore));
      assertTrue(ex.getMessage().contains("does not match last committed range cursor"));
    } finally {
      reopenedStore.close();
    }
  }

  @Test
  public void restartWithCommittedRangeButNoFirstBlockMarkerFailsClosed() throws Exception {
    index.close();
    index = null;
    store = null;

    ArchiveBlockRange range = new ArchiveBlockRange(
        1, 0, 1, 0, 1, blockHash(1), 0, ArchiveSource.NORMAL);
    // Valid range + matching cursor (2) but omit the first-block marker.
    putRawRange(range, 2);

    RocksDbArchiveBlockRangeStore reopenedStore =
        new RocksDbArchiveBlockRangeStore(dir.toString());
    try {
      ArchiveException ex = assertThrows(ArchiveException.class,
          () -> persistent(reopenedStore));
      assertTrue(ex.getMessage()
          .contains("first-block marker is inconsistent with committed range"));
    } finally {
      reopenedStore.close();
    }
  }

  @Test
  public void restartWithRangeStoredUnderWrongBlockKeyFailsClosed() throws Exception {
    index.close();
    index = null;
    store = null;

    // Encoded value is block 2, but stored under block 1's range key.
    ArchiveBlockRange valueForBlock2 = new ArchiveBlockRange(
        2, 2, 3, 2, 3, blockHash(2), 0, ArchiveSource.NORMAL);
    putRawTxNumRow(ArchiveBlockRangeCodec.rangeKey(1),
        ArchiveBlockRangeCodec.encodeRange(withChecksum(valueForBlock2)));
    putRawTxNumRow(ArchiveBlockRangeCodec.CURSOR_KEY, ArchiveBlockRangeCodec.encodeCursor(4));
    putRawTxNumRow(ArchiveBlockRangeCodec.FIRST_BLOCK_KEY,
        ArchiveBlockRangeCodec.encodeFirstBlock(1));

    RocksDbArchiveBlockRangeStore reopenedStore =
        new RocksDbArchiveBlockRangeStore(dir.toString());
    try {
      ArchiveException ex = assertThrows(ArchiveException.class,
          () -> persistent(reopenedStore));
      assertTrue(ex.getMessage().contains("block range key does not match encoded block"));
    } finally {
      reopenedStore.close();
    }
  }

  @Test
  public void restartWithFirstBlockMarkerAboveFirstRangeFailsClosed() throws Exception {
    pushBlock(1);
    pushBlock(2);
    index.close();
    index = null;
    store = null;

    // Two contiguous ranges (blocks 1,2) but first-block marker points at block 2.
    putRawTxNumRow(ArchiveBlockRangeCodec.FIRST_BLOCK_KEY,
        ArchiveBlockRangeCodec.encodeFirstBlock(2));

    RocksDbArchiveBlockRangeStore reopenedStore =
        new RocksDbArchiveBlockRangeStore(dir.toString());
    try {
      ArchiveException ex = assertThrows(ArchiveException.class,
          () -> persistent(reopenedStore));
      assertTrue(ex.getMessage().contains("does not match first committed range block"));
    } finally {
      reopenedStore.close();
    }
  }

  @Test
  public void restartWithMissingTxIdIndexForCommittedUserTxFailsClosed() throws Exception {
    pushBlockWithUserTx(1, TX_A);
    index.close();
    index = null;
    store = null;

    try (Options options = new Options().setCreateIfMissing(false);
        RocksDB rawDb = RocksDB.open(options, dir.toString())) {
      rawDb.delete(ArchiveBlockRangeCodec.txIdKey(TX_A));
    }

    RocksDbArchiveBlockRangeStore reopenedStore =
        new RocksDbArchiveBlockRangeStore(dir.toString());
    try {
      ArchiveException ex = assertThrows(ArchiveException.class,
          () -> persistent(reopenedStore));
      assertTrue(ex.getMessage().contains("txId index missing for committed txNum"));
    } finally {
      reopenedStore.close();
    }
  }

  @Test
  public void validateCanonicalHeadRejectsMalformedCanonicalHash() {
    pushBlock(10, HASH_A);

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> index.validateCanonicalHead(10, new byte[5]));
    assertTrue(ex.getMessage().contains("canonical head block requires a 32-byte block hash"));
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
          () -> persistent(reopenedStore));
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
          () -> persistent(reopenedStore));
      assertTrue(ex.getMessage().contains("first-block marker"));
    } finally {
      reopenedStore.close();
    }
  }

  @Test
  public void storeRejectsSwappedUserTxPositionOrder() {
    ArchiveBlockRange corruptRange = new ArchiveBlockRange(
        1, 0, 3, 0, 3, blockHash(1), 2, ArchiveSource.NORMAL);

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> store.commitRange(withChecksum(corruptRange), 4, Arrays.asList(
            new ArchiveTxPosition(0, 1, ArchivePhase.BLOCK_PREPARE, ArchiveSource.NORMAL, -1,
                null),
            new ArchiveTxPosition(1, 1, ArchivePhase.USER_TX, ArchiveSource.NORMAL, 1, TX_B),
            new ArchiveTxPosition(2, 1, ArchivePhase.USER_TX, ArchiveSource.NORMAL, 0, TX_A),
            new ArchiveTxPosition(3, 1, ArchivePhase.BLOCK_FINALIZE, ArchiveSource.NORMAL, -1,
                null))));

    assertTrue(ex.getMessage().contains("user tx-position mismatch"));
  }

  @Test
  public void storeRejectsSystemPositionTxId() {
    ArchiveBlockRange corruptRange = new ArchiveBlockRange(
        1, 0, 1, 0, 1, blockHash(1), 0, ArchiveSource.NORMAL);

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> store.commitRange(withChecksum(corruptRange), 2, Arrays.asList(
            new ArchiveTxPosition(0, 1, ArchivePhase.BLOCK_PREPARE, ArchiveSource.NORMAL, -1,
                TX_A),
            new ArchiveTxPosition(1, 1, ArchivePhase.BLOCK_FINALIZE, ArchiveSource.NORMAL, -1,
                null))));

    assertTrue(ex.getMessage().contains("prepare tx-position mismatch"));
  }

  @Test
  public void storeRejectsDuplicateTxIdsInSameCommit() {
    ArchiveBlockRange range = new ArchiveBlockRange(
        1, 0, 3, 0, 3, blockHash(1), 2, ArchiveSource.NORMAL);

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> store.commitRange(withChecksum(range), 4, Arrays.asList(
            new ArchiveTxPosition(0, 1, ArchivePhase.BLOCK_PREPARE, ArchiveSource.NORMAL, -1,
                null),
            new ArchiveTxPosition(1, 1, ArchivePhase.USER_TX, ArchiveSource.NORMAL, 0, TX_A),
            new ArchiveTxPosition(2, 1, ArchivePhase.USER_TX, ArchiveSource.NORMAL, 1, TX_A),
            new ArchiveTxPosition(3, 1, ArchivePhase.BLOCK_FINALIZE, ArchiveSource.NORMAL, -1,
                null))));

    assertTrue(ex.getMessage().contains("duplicate txId"));
    assertFalse(store.getRange(1).isPresent());
  }

  @Test
  public void storeRejectsDuplicateTxIdAlreadyCommitted() {
    ArchiveBlockRange first = new ArchiveBlockRange(
        1, 0, 2, 0, 2, blockHash(1), 1, ArchiveSource.NORMAL);
    store.commitRange(withChecksum(first), 3, Arrays.asList(
        new ArchiveTxPosition(0, 1, ArchivePhase.BLOCK_PREPARE, ArchiveSource.NORMAL, -1, null),
        new ArchiveTxPosition(1, 1, ArchivePhase.USER_TX, ArchiveSource.NORMAL, 0, TX_A),
        new ArchiveTxPosition(2, 1, ArchivePhase.BLOCK_FINALIZE, ArchiveSource.NORMAL, -1,
            null)));
    ArchiveBlockRange second = new ArchiveBlockRange(
        2, 3, 5, 3, 5, blockHash(2), 1, ArchiveSource.NORMAL);

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> store.commitRange(withChecksum(second), 6, Arrays.asList(
            new ArchiveTxPosition(3, 2, ArchivePhase.BLOCK_PREPARE, ArchiveSource.NORMAL, -1,
                null),
            new ArchiveTxPosition(4, 2, ArchivePhase.USER_TX, ArchiveSource.NORMAL, 0, TX_A),
            new ArchiveTxPosition(5, 2, ArchivePhase.BLOCK_FINALIZE, ArchiveSource.NORMAL, -1,
                null))));

    assertTrue(ex.getMessage().contains("duplicate txId already committed"));
    assertFalse(store.getRange(2).isPresent());
  }

  @Test
  public void restartWithOrphanPositionFailsClosed() throws Exception {
    ArchiveBlockRange range = new ArchiveBlockRange(
        1, 0, 1, 0, 1, blockHash(1), 0, ArchiveSource.NORMAL);
    store.commitRange(withChecksum(range), 2, Arrays.asList(
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
      ArchiveException ex = assertThrows(ArchiveException.class,
          () -> persistent(reopenedStore));
      assertTrue(ex.getMessage().contains("orphan"));
    } finally {
      reopenedStore.close();
    }
  }

  @Test
  public void restartWithOrphanTxIdIndexFailsClosed() throws Exception {
    pushBlock(1);
    index.close();
    index = null;
    store = null;
    putRawTxId(TX_B, 99);
    RocksDbArchiveBlockRangeStore reopenedStore =
        new RocksDbArchiveBlockRangeStore(dir.toString());

    try {
      ArchiveException ex = assertThrows(ArchiveException.class,
          () -> persistent(reopenedStore));
      assertTrue(ex.getMessage().contains("orphan"));
    } finally {
      reopenedStore.close();
    }
  }

  @Test
  public void txIdLookupRejectsOrphanTxIdIndex() throws Exception {
    pushBlock(1);
    index.close();
    index = null;
    store = null;
    putRawTxId(TX_B, 99);
    store = new RocksDbArchiveBlockRangeStore(dir.toString());

    try {
      ArchiveException ex = assertThrows(ArchiveException.class,
          () -> store.findTxNumByTxId(TX_B));

      assertTrue(ex.getMessage().contains("orphan"));
    } finally {
      store.close();
      store = null;
    }
  }

  @Test
  public void blockIndexLookupRejectsMissingPositionRow() throws Exception {
    ArchiveBlockRange range = pushBlockWithUserTx(10, TX_A);
    long userTxNum = range.getPrepareTxNum() + 1;
    index.close();
    index = null;
    store = null;
    deleteRawPosition(userTxNum);
    store = new RocksDbArchiveBlockRangeStore(dir.toString());

    try {
      ArchiveException ex = assertThrows(ArchiveException.class,
          () -> store.findTxNumByBlockAndIndex(10, 0));
      assertTrue(ex.getMessage().contains("orphan"));
    } finally {
      store.close();
      store = null;
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
    index = persistent(store);

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
    index = persistent(store);
    assertEquals(userTxNum, index.findTxNumByBlockAndIndex(10, 0).getAsLong());
  }

  @Test
  public void unwindAfterRestartRemovesPersistedPositionAndTxIdIndexes() {
    ArchiveBlockRange range = pushBlockWithUserTx(10, TX_A);
    long userTxNum = range.getPrepareTxNum() + 1;

    index.close();
    store = new RocksDbArchiveBlockRangeStore(dir.toString());
    index = persistent(store);

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
          ArchiveBlockRangeCodec.encodeRange(withChecksum(range)));
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
      ArchiveTxPosition persistedPosition = position.getBlockHash().length == 0
          ? withBlockHash(position, blockHash(position.getBlockNum()))
          : position;
      rawDb.put(ArchiveBlockRangeCodec.positionKey(position.getTxNum()),
          ArchiveBlockRangeCodec.encodePosition(persistedPosition));
    }
  }

  private void putRawTxId(byte[] txId, long txNum) throws RocksDBException {
    RocksDB.loadLibrary();
    try (Options options = new Options().setCreateIfMissing(false);
        RocksDB rawDb = RocksDB.open(options, dir.toString())) {
      rawDb.put(ArchiveBlockRangeCodec.txIdKey(txId), ArchiveBlockRangeCodec.encodeCursor(txNum));
    }
  }

  private void deleteRawPosition(long txNum) throws RocksDBException {
    RocksDB.loadLibrary();
    try (Options options = new Options().setCreateIfMissing(false);
        RocksDB rawDb = RocksDB.open(options, dir.toString())) {
      rawDb.delete(ArchiveBlockRangeCodec.positionKey(txNum));
    }
  }

  private void putRawTxNumRow(byte[] key, byte[] value) throws RocksDBException {
    RocksDB.loadLibrary();
    try (Options options = new Options().setCreateIfMissing(false);
        RocksDB rawDb = RocksDB.open(options, dir.toString())) {
      rawDb.put(key, value);
    }
  }

  private void assertStoreOpenFails(String expectedMessage) {
    ArchiveException ex = assertThrows(ArchiveException.class, () -> {
      try (RocksDbArchiveBlockRangeStore ignored =
          new RocksDbArchiveBlockRangeStore(dir.toString())) {
      }
    });
    assertTrue(ex.getMessage().contains(expectedMessage));
  }

  private void verifyManifestOnlyLegacyStoreUpgrades(byte[] manifestKey, byte[] manifest)
      throws IOException, RocksDBException {
    index.close();
    index = null;
    store = null;
    deleteRecursively(dir.toFile());
    Files.createDirectories(dir);
    try (Options options = new Options().setCreateIfMissing(true);
        RocksDB rawDb = RocksDB.open(options, dir.toString())) {
      rawDb.put(manifestKey, manifest);
    }

    store = new RocksDbArchiveBlockRangeStore(dir.toString());
    store.close();
    store = null;
    try (Options options = new Options().setCreateIfMissing(false);
        RocksDB rawDb = RocksDB.open(options, dir.toString())) {
      assertArrayEquals(ArchiveBlockRangeCodec.manifestValue(),
          rawDb.get(ArchiveBlockRangeCodec.manifestKey()));
    }
  }

  private ArchiveBlockRange writeLegacyTxNumFixture(byte[] legacyManifest) throws IOException,
      RocksDBException {
    deleteRecursively(dir.toFile());
    Files.createDirectories(dir);
    ArchiveBlockRange range = new ArchiveBlockRange(
        10, 0, 2, 0, 2, blockHash(10), 1, ArchiveSource.NORMAL);
    ArchiveTxPosition prepare = new ArchiveTxPosition(
        0, 10, ArchivePhase.BLOCK_PREPARE, ArchiveSource.NORMAL, -1, null);
    ArchiveTxPosition user = new ArchiveTxPosition(
        1, 10, ArchivePhase.USER_TX, ArchiveSource.NORMAL, 0, TX_A);
    ArchiveTxPosition finalize = new ArchiveTxPosition(
        2, 10, ArchivePhase.BLOCK_FINALIZE, ArchiveSource.NORMAL, -1, null);
    try (Options options = new Options().setCreateIfMissing(true);
        RocksDB rawDb = RocksDB.open(options, dir.toString())) {
      rawDb.put(ArchiveBlockRangeCodec.legacyManifestKey(), legacyManifest);
      rawDb.put(ArchiveBlockRangeCodec.legacyRangeKey(range.getBlockNum()),
          new byte[] {1});
      rawDb.put(ArchiveBlockRangeCodec.LEGACY_CURSOR_KEY,
          ArchiveBlockRangeCodec.encodeCursor(range.getLastTxNum() + 1));
      rawDb.put(ArchiveBlockRangeCodec.LEGACY_FIRST_BLOCK_KEY,
          ArchiveBlockRangeCodec.encodeFirstBlock(range.getBlockNum()));
      rawDb.put(ArchiveBlockRangeCodec.legacyPositionKey(prepare.getTxNum()),
          new byte[] {1});
      rawDb.put(ArchiveBlockRangeCodec.legacyPositionKey(user.getTxNum()),
          new byte[] {1});
      rawDb.put(ArchiveBlockRangeCodec.legacyPositionKey(finalize.getTxNum()),
          new byte[] {1});
      rawDb.put(ArchiveBlockRangeCodec.legacyTxIdKey(TX_A),
          ArchiveBlockRangeCodec.encodeCursor(user.getTxNum()));
      rawDb.put(legacyBlockIndexKey(range.getBlockNum(), 0),
          ArchiveBlockRangeCodec.encodeCursor(user.getTxNum()));
    }
    return range;
  }

  private static byte[] legacySchemaOneManifest() {
    return "tron-archive-txnum|schema=1|model=range-position-index-v1|prefix=legacy-0x00-0x06"
        .getBytes(StandardCharsets.US_ASCII);
  }

  private static byte[] legacySchemaTwoManifest() {
    return "tron-archive-txnum|schema=2|model=range-position-txid-v1|block-index=derived"
        .getBytes(StandardCharsets.US_ASCII);
  }

  private static byte[] legacySchemaThreeManifest() {
    return "tron-archive-txnum|schema=3|keys=l5-txnum-v1|values=range-position-v2"
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

  private static byte[] rawLong(long value) {
    byte[] bytes = new byte[Long.BYTES];
    putLong(bytes, 0, value);
    return bytes;
  }

  private static byte[] blockHash(long seed) {
    byte[] hash = new byte[ArchiveBlockRange.BLOCK_HASH_LENGTH];
    hash[ArchiveBlockRange.BLOCK_HASH_LENGTH - 1] = (byte) seed;
    return hash;
  }

  private static byte[] txId(int seed) {
    byte[] txId = new byte[ArchiveBlockRangeCodec.TX_ID_LENGTH];
    txId[txId.length - 1] = (byte) seed;
    return txId;
  }

  private static byte[] checksum(int seed) {
    byte[] checksum = new byte[ArchiveBlockRange.SCHEMA_CHECKSUM_LENGTH];
    checksum[ArchiveBlockRange.SCHEMA_CHECKSUM_LENGTH - 1] = (byte) seed;
    return checksum;
  }
}
