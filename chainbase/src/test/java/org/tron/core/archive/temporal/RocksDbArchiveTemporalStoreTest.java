package org.tron.core.archive.temporal;

import static org.junit.Assert.assertArrayEquals;
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
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveSource;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.archive.txnum.ArchiveTxPosition;

/**
 * RocksDB temporal store under the Erigon-v3 prev-value model; must stay observationally identical
 * to {@link InMemoryArchiveTemporalStore}. A change carries the value before it (-&gt; history) and
 * after it (-&gt; latest); {@code getAsOf(T)} returns the value at the end of txNum T.
 */
public class RocksDbArchiveTemporalStoreTest {

  private static final byte[] KEY = {7};

  private Path dir;
  private RocksDbArchiveTemporalStore store;

  @Before
  public void setUp() throws IOException {
    dir = Files.createTempDirectory("archive-temporal-test");
    store = new RocksDbArchiveTemporalStore(dir.toString());
  }

  @After
  public void tearDown() {
    if (store != null) {
      store.close();
    }
    deleteRecursively(dir.toFile());
  }

  private static ArchiveChangeRecord change(long txNum, DomainValue prev, DomainValue value) {
    return rec(txNum, ArchiveDomain.ACCOUNT, KEY, prev, value);
  }

  private static ArchiveChangeRecord rec(long txNum, ArchiveDomain domain, byte[] key,
      DomainValue prev, DomainValue value) {
    return rec(txNum, 1, ArchiveSource.NORMAL, domain, key, prev, value);
  }

  private static ArchiveChangeRecord rec(long txNum, long blockNum, ArchiveSource source,
      ArchiveDomain domain, byte[] key, DomainValue prev, DomainValue value) {
    return new ArchiveChangeRecord(
        new ArchiveTxPosition(txNum, blockNum, ArchivePhase.USER_TX, source, 0, null),
        domain, key, prev, value);
  }

  private static ArchiveChangeRecord changeInRange(ArchiveBlockRange range, long txNum,
      DomainValue prev, DomainValue value) {
    return rec(txNum, range.getBlockNum(), range.getSource(), ArchiveDomain.ACCOUNT, KEY,
        prev, value);
  }

  @Test
  public void getAsOfAndLatestPersistAcrossWrites() {
    // created at tx5 (absent -> 0x0A), then 0x0A -> 0x0B at tx8.
    store.putChange(change(5, DomainValue.tombstone(), DomainValue.present(new byte[] {0x0A})));
    store.putChange(change(8, DomainValue.present(new byte[] {0x0A}),
        DomainValue.present(new byte[] {0x0B})));
    assertTrue(store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 4).get().isDeleted()); // before creation
    assertArrayEquals(new byte[] {0x0A},
        store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 5).get().getValue());
    assertArrayEquals(new byte[] {0x0A},
        store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 7).get().getValue());
    assertArrayEquals(new byte[] {0x0B},
        store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 8).get().getValue());
    assertArrayEquals(new byte[] {0x0B}, store.latest(ArchiveDomain.ACCOUNT, KEY).get().getValue());
  }

  @Test
  public void fallToLatestWhenNoChangeAfterQuery() {
    store.putChange(change(5, DomainValue.tombstone(), DomainValue.present(new byte[] {0x42})));
    assertArrayEquals(new byte[] {0x42},
        store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 5).get().getValue());
    assertArrayEquals(new byte[] {0x42},
        store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 999).get().getValue());
  }

  @Test
  public void sameValueWriteSeedsLatestAndHistoryCoverage() {
    store.putChange(change(5, DomainValue.present(new byte[] {1}),
        DomainValue.present(new byte[] {1})));
    assertArrayEquals(new byte[] {1}, store.latest(ArchiveDomain.ACCOUNT, KEY).get().getValue());
    assertArrayEquals(new byte[] {1},
        store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 5).get().getValue());

    store.putChange(change(6, DomainValue.tombstone(), DomainValue.tombstone()));
    assertTrue(store.latest(ArchiveDomain.ACCOUNT, KEY).get().isDeleted());
    assertTrue(store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 6).get().isDeleted());
  }

  @Test
  public void sameValueWriteUnwindsCleanlyFromArchiveStart() {
    ArchiveBlockRange range = new ArchiveBlockRange(
        3, 0, 0, 0, 0, blockHash(3), 0, ArchiveSource.NORMAL);
    store.putBlockChanges(range, Collections.singletonList(
        changeInRange(range, 0, DomainValue.present(new byte[] {1}),
            DomainValue.present(new byte[] {1}))));
    assertArrayEquals(new byte[] {1}, store.latest(ArchiveDomain.ACCOUNT, KEY).get().getValue());

    store.unwindBlock(range);

    assertFalse(store.latest(ArchiveDomain.ACCOUNT, KEY).isPresent());
    assertFalse(store.getAsOf(ArchiveDomain.ACCOUNT, KEY, Long.MAX_VALUE).isPresent());
  }

  @Test
  public void unwindArchiveStartClearsLatestOnlyResidue() {
    ArchiveBlockRange first = new ArchiveBlockRange(
        100, 0, 1, 0, 1, blockHash(100), 0, ArchiveSource.NORMAL);
    ArchiveBlockRange later = new ArchiveBlockRange(
        101, 2, 3, 2, 3, blockHash(101), 0, ArchiveSource.NORMAL);
    store.putBlockChanges(first, Collections.emptyList());
    store.putBlockChanges(later, Collections.singletonList(
        changeInRange(later, 2, DomainValue.present(new byte[] {0x0A}),
            DomainValue.present(new byte[] {0x0B}))));

    store.unwindBlock(later);
    assertFalse(store.latest(ArchiveDomain.ACCOUNT, KEY).isPresent());

    store.unwindBlock(first);

    assertFalse(store.latest(ArchiveDomain.ACCOUNT, KEY).isPresent());
    assertFalse(store.getAsOf(ArchiveDomain.ACCOUNT, KEY, Long.MAX_VALUE).isPresent());
  }

  @Test
  public void midChainFirstCapturedChangeServesPrevValueBeforeCoverage() {
    // The key existed before archive coverage as 0x30; the first captured change moves it to 0x31.
    store.putChange(change(6, DomainValue.present(new byte[] {0x30}),
        DomainValue.present(new byte[] {0x31})));
    assertArrayEquals(new byte[] {0x30},
        store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 0).get().getValue());
    assertArrayEquals(new byte[] {0x30},
        store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 5).get().getValue());
    assertArrayEquals(new byte[] {0x31},
        store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 6).get().getValue());
    assertArrayEquals(new byte[] {0x31},
        store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 100).get().getValue());
    assertFalse(store.getAsOf(ArchiveDomain.ACCOUNT, new byte[] {99}, 5).isPresent());
  }

  @Test
  public void tombstoneIsPersistedAsDeleted() {
    store.putChange(change(5, DomainValue.tombstone(), DomainValue.present(new byte[] {1})));
    store.putChange(change(9, DomainValue.present(new byte[] {1}), DomainValue.tombstone()));
    assertFalse(store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 8).get().isDeleted());
    assertTrue(store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 9).get().isDeleted());
    assertTrue(store.latest(ArchiveDomain.ACCOUNT, KEY).get().isDeleted());
  }

  @Test
  public void reopenRetainsData() {
    store.putChange(change(5, DomainValue.tombstone(), DomainValue.present(new byte[] {0x42})));
    store.close();
    store = new RocksDbArchiveTemporalStore(dir.toString());
    assertArrayEquals(new byte[] {0x42}, store.latest(ArchiveDomain.ACCOUNT, KEY).get().getValue());
    assertArrayEquals(new byte[] {0x42},
        store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 100).get().getValue());
  }

  @Test
  public void manifestUsesCurrentSchemaLayoutMarker() throws Exception {
    store.close();
    store = null;
    try (Options options = new Options().setCreateIfMissing(false);
        RocksDB db = RocksDB.open(options, dir.toString())) {
      assertArrayEquals(ArchiveTemporalCodec.manifestValue(),
          db.get(ArchiveTemporalCodec.manifestKey()));
    }
  }

  @Test
  public void nonEmptyStoreWithoutManifestIsRejected() throws Exception {
    store.close();
    store = null;
    deleteRecursively(dir.toFile());
    Files.createDirectories(dir);
    try (Options options = new Options().setCreateIfMissing(true);
        RocksDB db = RocksDB.open(options, dir.toString());
        WriteBatch batch = new WriteBatch();
        WriteOptions writeOptions = new WriteOptions()) {
      batch.put(ArchiveTemporalCodec.latestKey(ArchiveDomain.ACCOUNT, KEY),
          ArchiveTemporalCodec.encodeValue(DomainValue.present(new byte[] {0x42})));
      db.write(writeOptions, batch);
    }

    assertThrows(ArchiveException.class, () -> new RocksDbArchiveTemporalStore(dir.toString()));
  }

  @Test
  public void manifestMismatchIsRejected() throws Exception {
    store.close();
    store = null;
    try (Options options = new Options().setCreateIfMissing(true);
        RocksDB db = RocksDB.open(options, dir.toString());
        WriteBatch batch = new WriteBatch();
        WriteOptions writeOptions = new WriteOptions()) {
      batch.put(ArchiveTemporalCodec.manifestKey(), "old-new-value-model".getBytes(
          StandardCharsets.US_ASCII));
      db.write(writeOptions, batch);
    }

    assertThrows(ArchiveException.class, () -> new RocksDbArchiveTemporalStore(dir.toString()));
  }

  @Test
  public void unknownExistingKeyPrefixIsRejectedOnOpen() throws Exception {
    store.close();
    store = null;
    try (Options options = new Options().setCreateIfMissing(true);
        RocksDB db = RocksDB.open(options, dir.toString());
        WriteBatch batch = new WriteBatch();
        WriteOptions writeOptions = new WriteOptions()) {
      batch.put(new byte[] {0x7f}, new byte[] {1});
      db.write(writeOptions, batch);
    }

    assertThrows(ArchiveException.class, () -> new RocksDbArchiveTemporalStore(dir.toString()));
  }

  @Test
  public void blockCommitMarkerSurvivesRestartAndValidatesRange() {
    ArchiveBlockRange range = new ArchiveBlockRange(
        3, 10, 11, 10, 11, blockHash(3), 0, ArchiveSource.NORMAL);
    store.putBlockChanges(range, Collections.emptyList());
    store.validateCommittedBlock(range);

    store.close();
    store = new RocksDbArchiveTemporalStore(dir.toString());
    store.validateCommittedBlock(range);

    ArchiveBlockRange mismatched = new ArchiveBlockRange(
        3, 10, 12, 10, 11, blockHash(3), 0, ArchiveSource.NORMAL);
    assertThrows(ArchiveException.class, () -> store.validateCommittedBlock(mismatched));

    ArchiveBlockRange mismatchedHash = new ArchiveBlockRange(
        3, 10, 11, 10, 11, blockHash(4), 0, ArchiveSource.NORMAL);
    assertThrows(ArchiveException.class, () -> store.validateCommittedBlock(mismatchedHash));
  }

  @Test
  public void legacyBlockCommitMarkerWithoutHashFailsClosed() throws Exception {
    ArchiveBlockRange range = new ArchiveBlockRange(
        3, 10, 11, 10, 11, blockHash(3), 0, ArchiveSource.NORMAL);
    byte[] legacyMarker = Arrays.copyOf(ArchiveTemporalCodec.encodeBlockCommit(range), 32);
    store.close();
    store = null;
    try (Options options = new Options().setCreateIfMissing(true);
        RocksDB db = RocksDB.open(options, dir.toString());
        WriteBatch batch = new WriteBatch();
        WriteOptions writeOptions = new WriteOptions()) {
      batch.put(ArchiveTemporalCodec.blockCommitKey(range.getBlockNum()), legacyMarker);
      db.write(writeOptions, batch);
    }
    store = null;

    assertThrows(ArchiveException.class, () -> new RocksDbArchiveTemporalStore(dir.toString()));
  }

  @Test
  public void unwindBlockDeletesCommitMarker() {
    ArchiveBlockRange range = new ArchiveBlockRange(
        3, 10, 11, 10, 11, blockHash(3), 0, ArchiveSource.NORMAL);
    store.putBlockChanges(range, Collections.emptyList());
    store.validateCommittedBlock(range);

    store.unwindBlock(range);
    assertThrows(ArchiveException.class, () -> store.validateCommittedBlock(range));
  }

  @Test
  public void unwindBlockStopsAtRangeEnd() {
    byte[] laterKey = {8};
    ArchiveBlockRange range = new ArchiveBlockRange(
        3, 10, 10, 10, 10, blockHash(3), 0, ArchiveSource.NORMAL);
    store.putBlockChanges(range, Collections.singletonList(
        changeInRange(range, 10, DomainValue.tombstone(),
            DomainValue.present(new byte[] {0x0A}))));
    store.putChange(rec(12, ArchiveDomain.ACCOUNT, laterKey, DomainValue.tombstone(),
        DomainValue.present(new byte[] {0x0C})));

    store.unwindBlock(range);

    assertFalse(store.latest(ArchiveDomain.ACCOUNT, KEY).isPresent());
    assertArrayEquals(new byte[] {0x0C},
        store.latest(ArchiveDomain.ACCOUNT, laterKey).get().getValue());
    assertArrayEquals(new byte[] {0x0C},
        store.getAsOf(ArchiveDomain.ACCOUNT, laterKey, 100).get().getValue());
  }

  @Test
  public void unknownKeyIsEmpty() {
    assertFalse(store.latest(ArchiveDomain.ACCOUNT, new byte[] {99}).isPresent());
    assertFalse(store.getAsOf(ArchiveDomain.ACCOUNT, new byte[] {99}, 5).isPresent());
  }

  @Test
  public void unwindRestoresLatestToPreValueOfSmallestDropped() {
    store.putChange(change(5, DomainValue.tombstone(), DomainValue.present(new byte[] {0x0A})));
    store.putChange(change(8, DomainValue.present(new byte[] {0x0A}),
        DomainValue.present(new byte[] {0x0B})));
    store.unwind(8); // remove tx8, restore latest to the pre-value of tx8 = 0x0A
    assertArrayEquals(new byte[] {0x0A}, store.latest(ArchiveDomain.ACCOUNT, KEY).get().getValue());
    assertArrayEquals(new byte[] {0x0A},
        store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 100).get().getValue());
    // the tx8 history entry is gone: as-of 8 now falls through to latest (0x0A)
    assertArrayEquals(new byte[] {0x0A},
        store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 8).get().getValue());
  }

  @Test
  public void unwindCreatedKeyRestoresToTombstone() {
    store.putChange(change(8, DomainValue.tombstone(), DomainValue.present(new byte[] {0x0B})));
    store.unwind(8);
    assertFalse(store.latest(ArchiveDomain.ACCOUNT, KEY).isPresent());
    assertFalse(store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 100).isPresent());
  }

  @Test
  public void unwindSurvivesRestart() {
    // created at tx5 (0x0A), 0x0A -> 0x0B at tx8; unwind tx8, then reopen: the restored latest and
    // the deleted tx8 history entry must persist across a restart (crash-safe atomic batch).
    store.putChange(change(5, DomainValue.tombstone(), DomainValue.present(new byte[] {0x0A})));
    store.putChange(change(8, DomainValue.present(new byte[] {0x0A}),
        DomainValue.present(new byte[] {0x0B})));
    store.unwind(8);
    store.close();
    store = new RocksDbArchiveTemporalStore(dir.toString());
    assertArrayEquals(new byte[] {0x0A}, store.latest(ArchiveDomain.ACCOUNT, KEY).get().getValue());
    assertArrayEquals(new byte[] {0x0A},
        store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 100).get().getValue());
    // tx8's history is gone after the restart too: as-of 8 falls through to latest (0x0A).
    assertArrayEquals(new byte[] {0x0A},
        store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 8).get().getValue());
  }

  @Test
  public void unwindFailsClosedWhenHistoryRowIsMissing() throws Exception {
    store.close();
    try (Options options = new Options().setCreateIfMissing(true);
        RocksDB db = RocksDB.open(options, dir.toString());
        WriteBatch batch = new WriteBatch();
        WriteOptions writeOptions = new WriteOptions()) {
      batch.put(ArchiveTemporalCodec.latestKey(ArchiveDomain.ACCOUNT, KEY),
          ArchiveTemporalCodec.encodeValue(DomainValue.present(new byte[] {0x0B})));
      batch.put(ArchiveTemporalCodec.changesetKey(8, ArchiveDomain.ACCOUNT, KEY), new byte[0]);
      db.write(writeOptions, batch);
    }
    store = new RocksDbArchiveTemporalStore(dir.toString());

    assertThrows(ArchiveException.class, () -> store.unwind(8));
    assertArrayEquals(new byte[] {0x0B}, store.latest(ArchiveDomain.ACCOUNT, KEY)
        .get().getValue());
  }

  @Test
  public void latestWithInvalidValueFlagFailsClosed() throws Exception {
    store.close();
    try (Options options = new Options().setCreateIfMissing(true);
        RocksDB db = RocksDB.open(options, dir.toString());
        WriteBatch batch = new WriteBatch();
        WriteOptions writeOptions = new WriteOptions()) {
      batch.put(ArchiveTemporalCodec.latestKey(ArchiveDomain.ACCOUNT, KEY),
          new byte[] {2, 0x0B});
      db.write(writeOptions, batch);
    }
    store = null;

    assertThrows(ArchiveException.class, () -> new RocksDbArchiveTemporalStore(dir.toString()));
  }

  @Test
  public void historyWithInvalidValueFlagFailsClosed() throws Exception {
    store.close();
    try (Options options = new Options().setCreateIfMissing(true);
        RocksDB db = RocksDB.open(options, dir.toString());
        WriteBatch batch = new WriteBatch();
        WriteOptions writeOptions = new WriteOptions()) {
      batch.put(ArchiveTemporalCodec.historyKey(ArchiveDomain.ACCOUNT, KEY, 8),
          new byte[] {2, 0x0B});
      db.write(writeOptions, batch);
    }
    store = null;

    assertThrows(ArchiveException.class, () -> new RocksDbArchiveTemporalStore(dir.toString()));
  }

  @Test
  public void prefixCollidingKeysDoNotCrossContaminate() {
    // keyA is a strict byte-prefix of keyB in the same domain (the variable-length-key trap).
    ArchiveDomain domain = ArchiveDomain.DYNAMIC_PROPERTIES;
    byte[] keyA = "ENERGY_FEE".getBytes(StandardCharsets.US_ASCII);
    byte[] keyB = "ENERGY_FEE_HISTORY".getBytes(StandardCharsets.US_ASCII);
    store.putChange(rec(10, domain, keyA, DomainValue.tombstone(),
        DomainValue.present(new byte[] {0x0A})));
    store.putChange(rec(12, domain, keyB, DomainValue.tombstone(),
        DomainValue.present(new byte[] {0x0B})));
    store.putChange(rec(18, domain, keyA, DomainValue.present(new byte[] {0x0A}),
        DomainValue.present(new byte[] {0x0C})));
    // getAsOf for keyA must never resolve to keyB's value: end of tx11 = 0x0A (pre-value of tx18).
    assertArrayEquals(new byte[] {0x0A}, store.getAsOf(domain, keyA, 11).get().getValue());
    // unwind(15) drops keyA@18; keyA.latest must restore to keyA@18's pre-value 0x0A, NOT keyB@12.
    store.unwind(15);
    assertArrayEquals(new byte[] {0x0A}, store.latest(domain, keyA).get().getValue());
    assertArrayEquals(new byte[] {0x0B}, store.latest(domain, keyB).get().getValue()); // untouched
  }

  @Test
  public void largeCanonicalKeyPersistsWithU32Length() {
    ArchiveDomain domain = ArchiveDomain.DYNAMIC_PROPERTIES;
    byte[] largeKey = new byte[0x10001];
    Arrays.fill(largeKey, (byte) 'A');
    largeKey[largeKey.length - 1] = (byte) 'Z';

    store.putChange(rec(10, domain, largeKey, DomainValue.tombstone(),
        DomainValue.present(new byte[] {0x0A})));
    store.putChange(rec(12, domain, largeKey, DomainValue.present(new byte[] {0x0A}),
        DomainValue.present(new byte[] {0x0B})));

    assertArrayEquals(new byte[] {0x0A}, store.getAsOf(domain, largeKey, 11).get().getValue());
    assertArrayEquals(new byte[] {0x0B}, store.latest(domain, largeKey).get().getValue());
    store.unwind(12);
    assertArrayEquals(new byte[] {0x0A}, store.latest(domain, largeKey).get().getValue());
  }

  @Test
  public void validateTxNumsCoveredRejectsHistoryWithoutCommittedPosition() {
    store.putChange(change(8, DomainValue.tombstone(), DomainValue.present(new byte[] {0x0B})));

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> store.validateTxNumsCovered(txNum -> false));

    assertTrue(ex.getMessage().contains("history txNum"));
  }

  @Test
  public void validateTxNumsCoveredRejectsChangesetWithoutHistory() throws Exception {
    store.putChange(change(8, DomainValue.tombstone(), DomainValue.present(new byte[] {0x0B})));
    store.close();
    try (Options options = new Options().setCreateIfMissing(false);
        RocksDB db = RocksDB.open(options, dir.toString());
        WriteBatch batch = new WriteBatch();
        WriteOptions writeOptions = new WriteOptions()) {
      batch.delete(ArchiveTemporalCodec.historyKey(ArchiveDomain.ACCOUNT, KEY, 8));
      db.write(writeOptions, batch);
    }
    store = new RocksDbArchiveTemporalStore(dir.toString());

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> store.validateTxNumsCovered(txNum -> true));

    assertTrue(ex.getMessage().contains("history missing"));
  }

  @Test
  public void validateTxNumsCoveredRejectsLatestWithoutHistory() throws Exception {
    store.close();
    try (Options options = new Options().setCreateIfMissing(false);
        RocksDB db = RocksDB.open(options, dir.toString());
        WriteBatch batch = new WriteBatch();
        WriteOptions writeOptions = new WriteOptions()) {
      batch.put(ArchiveTemporalCodec.latestKey(ArchiveDomain.ACCOUNT, KEY),
          ArchiveTemporalCodec.encodeValue(DomainValue.present(new byte[] {0x0B})));
      db.write(writeOptions, batch);
    }
    store = new RocksDbArchiveTemporalStore(dir.toString());

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> store.validateTxNumsCovered(txNum -> true));

    assertTrue(ex.getMessage().contains("latest has no history"));
  }

  @Test
  public void putBlockChangesRejectsRecordOutsideRange() {
    ArchiveBlockRange range = new ArchiveBlockRange(
        3, 10, 11, 10, 11, blockHash(3), 0, ArchiveSource.NORMAL);

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> store.putBlockChanges(range, Collections.singletonList(
            change(8, DomainValue.tombstone(), DomainValue.present(new byte[] {1})))));

    assertTrue(ex.getMessage().contains("outside committed block range"));
  }

  @Test
  public void unwindBlockFailsWithoutCommitMarker() {
    ArchiveBlockRange range = new ArchiveBlockRange(
        3, 10, 11, 10, 11, blockHash(3), 0, ArchiveSource.NORMAL);

    assertThrows(ArchiveException.class, () -> store.unwindBlock(range));
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

  private static byte[] blockHash(int seed) {
    byte[] hash = new byte[ArchiveBlockRange.BLOCK_HASH_LENGTH];
    hash[ArchiveBlockRange.BLOCK_HASH_LENGTH - 1] = (byte) seed;
    return hash;
  }
}
