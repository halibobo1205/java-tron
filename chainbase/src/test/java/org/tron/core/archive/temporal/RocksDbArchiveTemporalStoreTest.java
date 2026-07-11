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
import org.tron.core.archive.domain.DefaultArchiveDomainCatalog;
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

  private static byte[] accountKey(byte lastByte) {
    byte[] key = new byte[21];
    key[0] = 0x41;
    key[20] = lastByte;
    return key;
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
    assertArrayEquals(new byte[] {0x0A}, store.latest(ArchiveDomain.ACCOUNT, KEY).get()
        .getValue());

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
  public void blockCommitMarkerDigestUsesPersistedChangesetOrder() {
    ArchiveBlockRange range = new ArchiveBlockRange(
        3, 10, 12, 10, 12, blockHash(3), 1, ArchiveSource.NORMAL);
    byte[] keyA = accountKey((byte) 0x01);
    byte[] keyB = accountKey((byte) 0x02);
    ArchiveChangeRecord recordB = rec(11, range.getBlockNum(), range.getSource(),
        ArchiveDomain.ACCOUNT, keyB, DomainValue.tombstone(),
        DomainValue.present(new byte[] {0x0B}));
    ArchiveChangeRecord recordA = rec(11, range.getBlockNum(), range.getSource(),
        ArchiveDomain.ACCOUNT, keyA, DomainValue.tombstone(),
        DomainValue.present(new byte[] {0x0A}));

    store.putBlockChanges(range, Arrays.asList(recordB, recordA));
    store.validateCommittedBlock(range);

    store.close();
    store = new RocksDbArchiveTemporalStore(dir.toString());
    store.validateCommittedBlock(range);
  }

  @Test
  public void putBlockChangesOrdersSameKeyBeforeWritingLatest() {
    ArchiveBlockRange range = new ArchiveBlockRange(
        3, 10, 11, 10, 11, blockHash(3), 1, ArchiveSource.NORMAL);
    ArchiveChangeRecord later = rec(11, range.getBlockNum(), range.getSource(),
        ArchiveDomain.ACCOUNT, KEY, DomainValue.present(new byte[] {0x0A}),
        DomainValue.present(new byte[] {0x0B}));
    ArchiveChangeRecord earlier = rec(10, range.getBlockNum(), range.getSource(),
        ArchiveDomain.ACCOUNT, KEY, DomainValue.tombstone(),
        DomainValue.present(new byte[] {0x0A}));

    store.putBlockChanges(range, Arrays.asList(later, earlier));

    store.validateCommittedBlock(range);
    assertArrayEquals(new byte[] {0x0A},
        store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 10).get().getValue());
    assertArrayEquals(new byte[] {0x0B},
        store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 11).get().getValue());
    assertArrayEquals(new byte[] {0x0B}, store.latest(ArchiveDomain.ACCOUNT, KEY)
        .get().getValue());
  }

  @Test
  public void putBlockChangesRejectsBrokenPrevValueChainWithoutPartialWrite() {
    ArchiveBlockRange range = new ArchiveBlockRange(
        3, 10, 11, 10, 11, blockHash(3), 1, ArchiveSource.NORMAL);
    ArchiveChangeRecord earlier = rec(10, range.getBlockNum(), range.getSource(),
        ArchiveDomain.ACCOUNT, KEY, DomainValue.tombstone(),
        DomainValue.present(new byte[] {0x0A}));
    ArchiveChangeRecord later = rec(11, range.getBlockNum(), range.getSource(),
        ArchiveDomain.ACCOUNT, KEY, DomainValue.present(new byte[] {0x7F}),
        DomainValue.present(new byte[] {0x0B}));

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> store.putBlockChanges(range, Arrays.asList(earlier, later)));

    assertTrue(ex.getMessage().contains("prev-value chain"));
    assertFalse(store.latest(ArchiveDomain.ACCOUNT, KEY).isPresent());
    assertThrows(ArchiveException.class, () -> store.validateCommittedBlock(range));
  }

  @Test
  public void legacyBlockCommitMarkerWithoutHashFailsClosed() throws Exception {
    ArchiveBlockRange range = new ArchiveBlockRange(
        3, 10, 11, 10, 11, blockHash(3), 0, ArchiveSource.NORMAL);
    byte[] legacyMarker = Arrays.copyOf(ArchiveTemporalCodec.encodeBlockCommit(
        range, 0, new byte[ArchiveTemporalCodec.blockCommitDigestLength()]), 32);
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
  public void blockCommitMarkerKeyValueMismatchFailsClosedOnOpen() throws Exception {
    ArchiveBlockRange range = new ArchiveBlockRange(
        4, 10, 11, 10, 11, blockHash(4), 0, ArchiveSource.NORMAL);
    store.close();
    store = null;
    try (Options options = new Options().setCreateIfMissing(true);
        RocksDB db = RocksDB.open(options, dir.toString());
        WriteBatch batch = new WriteBatch();
        WriteOptions writeOptions = new WriteOptions()) {
      batch.put(ArchiveTemporalCodec.blockCommitKey(3),
          ArchiveTemporalCodec.encodeBlockCommit(
              range, 0, new byte[ArchiveTemporalCodec.blockCommitDigestLength()]));
      db.write(writeOptions, batch);
    }

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> new RocksDbArchiveTemporalStore(dir.toString()));
    assertTrue(ex.getMessage().contains("key/value mismatch"));
  }

  @Test
  public void blockCommitMarkerRejectsNegativeBlockNumber() {
    ArchiveBlockRange range = new ArchiveBlockRange(
        -1, 10, 11, 10, 11, blockHash(4), 0, ArchiveSource.NORMAL);

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> store.putBlockChanges(range, Collections.emptyList()));
    assertTrue(ex.getMessage().contains("negative"));
  }

  @Test
  public void validateCommittedBlockRejectsDeletedHistoryRow() throws Exception {
    ArchiveBlockRange range = new ArchiveBlockRange(
        3, 10, 12, 10, 12, blockHash(3), 1, ArchiveSource.NORMAL);
    store.putBlockChanges(range, Collections.singletonList(changeInRange(
        range, 11, DomainValue.tombstone(), DomainValue.present(new byte[] {0x0B}))));
    store.close();
    try (Options options = new Options().setCreateIfMissing(false);
        RocksDB db = RocksDB.open(options, dir.toString());
        WriteBatch batch = new WriteBatch();
        WriteOptions writeOptions = new WriteOptions()) {
      batch.delete(ArchiveTemporalCodec.historyKey(ArchiveDomain.ACCOUNT, KEY, 11));
      db.write(writeOptions, batch);
    }
    store = new RocksDbArchiveTemporalStore(dir.toString());

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> store.validateCommittedBlock(range));

    assertTrue(ex.getMessage().contains("history missing"));
  }

  @Test
  public void validateCommittedBlockRejectsDeletedHistoryAndChangesetRows() throws Exception {
    ArchiveBlockRange range = new ArchiveBlockRange(
        3, 10, 12, 10, 12, blockHash(3), 1, ArchiveSource.NORMAL);
    store.putBlockChanges(range, Collections.singletonList(changeInRange(
        range, 11, DomainValue.tombstone(), DomainValue.present(new byte[] {0x0B}))));
    store.close();
    try (Options options = new Options().setCreateIfMissing(false);
        RocksDB db = RocksDB.open(options, dir.toString());
        WriteBatch batch = new WriteBatch();
        WriteOptions writeOptions = new WriteOptions()) {
      batch.delete(ArchiveTemporalCodec.historyKey(ArchiveDomain.ACCOUNT, KEY, 11));
      batch.delete(ArchiveTemporalCodec.changesetKey(11, ArchiveDomain.ACCOUNT, KEY));
      db.write(writeOptions, batch);
    }
    store = new RocksDbArchiveTemporalStore(dir.toString());

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> store.validateCommittedBlock(range));

    assertTrue(ex.getMessage().contains("commit marker"));
  }

  @Test
  public void validateCommittedBlockRejectsTamperedChangesetValue() throws Exception {
    ArchiveBlockRange range = new ArchiveBlockRange(
        3, 10, 12, 10, 12, blockHash(3), 1, ArchiveSource.NORMAL);
    store.putBlockChanges(range, Collections.singletonList(changeInRange(
        range, 11, DomainValue.tombstone(), DomainValue.present(new byte[] {0x0B}))));
    store.close();
    try (Options options = new Options().setCreateIfMissing(false);
        RocksDB db = RocksDB.open(options, dir.toString());
        WriteBatch batch = new WriteBatch();
        WriteOptions writeOptions = new WriteOptions()) {
      batch.put(ArchiveTemporalCodec.changesetKey(11, ArchiveDomain.ACCOUNT, KEY),
          ArchiveTemporalCodec.encodeValue(DomainValue.present(new byte[] {0x0C})));
      db.write(writeOptions, batch);
    }
    store = new RocksDbArchiveTemporalStore(dir.toString());

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> store.validateCommittedBlock(range));

    assertTrue(ex.getMessage().contains("commit marker"));
  }

  @Test
  public void getAsOfRejectsNegativeTxNum() {
    store.putChange(change(5, DomainValue.tombstone(), DomainValue.present(new byte[] {1})));

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> store.getAsOf(ArchiveDomain.ACCOUNT, KEY, -1));

    assertTrue(ex.getMessage().contains("non-negative"));
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

    assertTrue(store.latest(ArchiveDomain.ACCOUNT, KEY).get().isDeleted());
    assertArrayEquals(new byte[] {0x0C},
        store.latest(ArchiveDomain.ACCOUNT, laterKey).get().getValue());
    assertArrayEquals(new byte[] {0x0C},
        store.getAsOf(ArchiveDomain.ACCOUNT, laterKey, 100).get().getValue());
  }

  @Test
  public void unwindBlockRejectsNonHeadBlockAndKeepsLatest() {
    ArchiveBlockRange first = new ArchiveBlockRange(
        3, 10, 11, 10, 11, blockHash(3), 0, ArchiveSource.NORMAL);
    ArchiveBlockRange second = new ArchiveBlockRange(
        4, 12, 13, 12, 13, blockHash(4), 0, ArchiveSource.NORMAL);
    store.putBlockChanges(first, Collections.singletonList(
        changeInRange(first, 10, DomainValue.tombstone(),
            DomainValue.present(new byte[] {0x0A}))));
    store.putBlockChanges(second, Collections.singletonList(
        changeInRange(second, 12, DomainValue.present(new byte[] {0x0A}),
            DomainValue.present(new byte[] {0x0B}))));

    ArchiveException ex = assertThrows(ArchiveException.class, () -> store.unwindBlock(first));

    assertTrue(ex.getMessage().contains("not temporal head"));
    assertArrayEquals(new byte[] {0x0B},
        store.latest(ArchiveDomain.ACCOUNT, KEY).get().getValue());
    assertArrayEquals(new byte[] {0x0B},
        store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 100).get().getValue());
    store.validateCommittedBlock(first);
    store.validateCommittedBlock(second);
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
    assertTrue(store.latest(ArchiveDomain.ACCOUNT, KEY).get().isDeleted());
    assertTrue(store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 100).get().isDeleted());
  }

  @Test
  public void unwindDroppingTwoChangesRestoresSmallestPrevAndSurvivesRestart() {
    // Three changes; unwind(8) drops BOTH tx8 and tx12 for KEY (the multi-drop path the single-drop
    // test above never exercises), while tx5 (older than fromTxNum) is retained.
    store.putChange(change(5, DomainValue.tombstone(), DomainValue.present(new byte[] {0x0A})));
    store.putChange(change(8, DomainValue.present(new byte[] {0x0A}),
        DomainValue.present(new byte[] {0x0B})));
    store.putChange(change(12, DomainValue.present(new byte[] {0x0B}),
        DomainValue.present(new byte[] {0x0C})));
    store.unwind(8);
    // latest is the SMALLEST dropped change's pre-value (tx8's 0x0A), NOT tx12's 0x0B/0x0C.
    assertArrayEquals(new byte[] {0x0A}, store.latest(ArchiveDomain.ACCOUNT, KEY).get().getValue());
    assertArrayEquals(new byte[] {0x0A},
        store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 100).get().getValue());
    // both tx8 and tx12 history rows are gone: as-of 8 falls through to latest.
    assertArrayEquals(new byte[] {0x0A},
        store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 8).get().getValue());
    // tx5's tombstone-prev history survives (proves the retained older row is untouched).
    assertTrue(store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 4).get().isDeleted());

    // The restore + deletions persist across a restart, and because the key is anchored by retained
    // history (tx5), NO latest-baseline marker is written -- validateTxNumsCovered must not throw.
    store.close();
    store = new RocksDbArchiveTemporalStore(dir.toString());
    store.validateTxNumsCovered(txNum -> true);
    assertArrayEquals(new byte[] {0x0A}, store.latest(ArchiveDomain.ACCOUNT, KEY).get().getValue());
    assertArrayEquals(new byte[] {0x0A},
        store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 100).get().getValue());
  }

  @Test
  public void unwindFirstMidChainChangeKeepsLatestOnlyBaselineAcrossRestart() {
    store.putChange(change(8, DomainValue.present(new byte[] {0x0A}),
        DomainValue.present(new byte[] {0x0B})));
    store.unwind(8);
    store.close();
    store = new RocksDbArchiveTemporalStore(dir.toString());

    store.validateTxNumsCovered(txNum -> true);
    assertArrayEquals(new byte[] {0x0A}, store.latest(ArchiveDomain.ACCOUNT, KEY).get()
        .getValue());
    assertArrayEquals(new byte[] {0x0A},
        store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 100).get().getValue());
  }

  @Test
  public void unwindZeroDeletesBlockCommitMarkers() {
    ArchiveBlockRange emptyRange = new ArchiveBlockRange(
        100, 0, 1, 0, 1, blockHash(100), 0, ArchiveSource.NORMAL);
    ArchiveBlockRange rangeWithChange = new ArchiveBlockRange(
        101, 2, 3, 2, 3, blockHash(101), 0, ArchiveSource.NORMAL);
    store.putBlockChanges(emptyRange, Collections.emptyList());
    store.putBlockChanges(rangeWithChange, Collections.singletonList(
        changeInRange(rangeWithChange, 2, DomainValue.present(new byte[] {0x0A}),
            DomainValue.present(new byte[] {0x0B}))));

    store.unwind(0);

    assertFalse(store.hasDataBeyondManifest());
    store.validateCommitMarkersCovered(blockNum -> false);
    assertThrows(ArchiveException.class, () -> store.validateCommittedBlock(emptyRange));
    assertThrows(ArchiveException.class, () -> store.validateCommittedBlock(rangeWithChange));
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
      batch.put(ArchiveTemporalCodec.changesetKey(8, ArchiveDomain.ACCOUNT, KEY),
          ArchiveTemporalCodec.encodeValue(DomainValue.present(new byte[] {0x0B})));
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
  public void validateDomainRowsRejectsInvalidCanonicalKey() {
    store.putChange(change(5, DomainValue.tombstone(), DomainValue.tombstone()));

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> store.validateDomainRows(new DefaultArchiveDomainCatalog()));

    assertTrue(ex.getMessage().contains("tron-address21-v1"));
  }

  @Test
  public void validateDomainRowsRejectsInvalidCanonicalValue() throws Exception {
    byte[] address = new byte[21];
    address[0] = 0x41;
    store.close();
    try (Options options = new Options().setCreateIfMissing(true);
        RocksDB db = RocksDB.open(options, dir.toString());
        WriteBatch batch = new WriteBatch();
        WriteOptions writeOptions = new WriteOptions()) {
      batch.put(ArchiveTemporalCodec.latestKey(ArchiveDomain.ACCOUNT, address),
          ArchiveTemporalCodec.encodeValue(DomainValue.present(new byte[] {1})));
      db.write(writeOptions, batch);
    }
    store = new RocksDbArchiveTemporalStore(dir.toString());

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> store.validateDomainRows(new DefaultArchiveDomainCatalog()));

    assertTrue(ex.getMessage().contains("account-capsule-canonical-v1"));
  }

  @Test
  public void validateDomainRowsRejectsExcludedDynamicProperty() throws Exception {
    byte[] key = "state_flag".getBytes(StandardCharsets.US_ASCII);
    store.close();
    try (Options options = new Options().setCreateIfMissing(true);
        RocksDB db = RocksDB.open(options, dir.toString());
        WriteBatch batch = new WriteBatch();
        WriteOptions writeOptions = new WriteOptions()) {
      batch.put(ArchiveTemporalCodec.latestKey(ArchiveDomain.DYNAMIC_PROPERTIES, key),
          ArchiveTemporalCodec.encodeValue(DomainValue.present(new byte[] {1})));
      db.write(writeOptions, batch);
    }
    store = new RocksDbArchiveTemporalStore(dir.toString());

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> store.validateDomainRows(new DefaultArchiveDomainCatalog()));

    assertTrue(ex.getMessage().contains("dynamic property is not archived"));
  }

  @Test
  public void historyWithNegativeTxNumFailsClosedOnOpen() throws Exception {
    store.close();
    try (Options options = new Options().setCreateIfMissing(true);
        RocksDB db = RocksDB.open(options, dir.toString());
        WriteBatch batch = new WriteBatch();
        WriteOptions writeOptions = new WriteOptions()) {
      batch.put(rawHistoryKey(-1),
          ArchiveTemporalCodec.encodeValue(DomainValue.present(new byte[] {0x0B})));
      db.write(writeOptions, batch);
    }
    store = null;

    assertThrows(ArchiveException.class, () -> new RocksDbArchiveTemporalStore(dir.toString()));
  }

  @Test
  public void changesetWithNegativeTxNumFailsClosedOnOpen() throws Exception {
    store.close();
    try (Options options = new Options().setCreateIfMissing(true);
        RocksDB db = RocksDB.open(options, dir.toString());
        WriteBatch batch = new WriteBatch();
        WriteOptions writeOptions = new WriteOptions()) {
      batch.put(rawChangesetKey(-1), new byte[0]);
      db.write(writeOptions, batch);
    }
    store = null;

    assertThrows(ArchiveException.class, () -> new RocksDbArchiveTemporalStore(dir.toString()));
  }

  @Test
  public void latestWithUnknownDomainIdFailsClosedOnOpen() throws Exception {
    store.close();
    try (Options options = new Options().setCreateIfMissing(true);
        RocksDB db = RocksDB.open(options, dir.toString());
        WriteBatch batch = new WriteBatch();
        WriteOptions writeOptions = new WriteOptions()) {
      batch.put(rawLatestKey(0x7fff),
          ArchiveTemporalCodec.encodeValue(DomainValue.present(new byte[] {0x0B})));
      db.write(writeOptions, batch);
    }
    store = null;

    assertThrows(ArchiveException.class, () -> new RocksDbArchiveTemporalStore(dir.toString()));
  }

  @Test
  public void historyWithUnknownDomainIdFailsClosedOnOpen() throws Exception {
    store.close();
    try (Options options = new Options().setCreateIfMissing(true);
        RocksDB db = RocksDB.open(options, dir.toString());
        WriteBatch batch = new WriteBatch();
        WriteOptions writeOptions = new WriteOptions()) {
      batch.put(rawHistoryKey(8, 0x7fff),
          ArchiveTemporalCodec.encodeValue(DomainValue.present(new byte[] {0x0B})));
      db.write(writeOptions, batch);
    }
    store = null;

    assertThrows(ArchiveException.class, () -> new RocksDbArchiveTemporalStore(dir.toString()));
  }

  @Test
  public void changesetWithUnknownDomainIdFailsClosedOnOpen() throws Exception {
    store.close();
    try (Options options = new Options().setCreateIfMissing(true);
        RocksDB db = RocksDB.open(options, dir.toString());
        WriteBatch batch = new WriteBatch();
        WriteOptions writeOptions = new WriteOptions()) {
      batch.put(rawChangesetKey(8, 0x7fff), new byte[0]);
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
  public void validateTxNumsCoveredRejectsUnanchoredLatestOnlyRow() throws Exception {
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

    assertTrue(ex.getMessage().contains("baseline marker"));
  }

  @Test
  public void validateTxNumsCoveredAllowsLatestOnlyUnwindBaseline() {
    ArchiveBlockRange first = new ArchiveBlockRange(
        100, 0, 1, 0, 1, blockHash(100), 0, ArchiveSource.NORMAL);
    ArchiveBlockRange later = new ArchiveBlockRange(
        101, 2, 3, 2, 3, blockHash(101), 0, ArchiveSource.NORMAL);
    store.putBlockChanges(first, Collections.emptyList());
    store.putBlockChanges(later, Collections.singletonList(
        changeInRange(later, 2, DomainValue.present(new byte[] {0x0B}),
            DomainValue.present(new byte[] {0x0C}))));

    store.unwindBlock(later);
    store.close();
    store = new RocksDbArchiveTemporalStore(dir.toString());

    store.validateTxNumsCovered(txNum -> true);
    assertArrayEquals(new byte[] {0x0B}, store.latest(ArchiveDomain.ACCOUNT, KEY).get()
        .getValue());
  }

  @Test
  public void putChangeClearsPriorLatestOnlyUnwindBaselineMarker() {
    ArchiveBlockRange first = new ArchiveBlockRange(
        100, 0, 1, 0, 1, blockHash(100), 0, ArchiveSource.NORMAL);
    ArchiveBlockRange later = new ArchiveBlockRange(
        101, 2, 3, 2, 3, blockHash(101), 0, ArchiveSource.NORMAL);
    store.putBlockChanges(first, Collections.emptyList());
    store.putBlockChanges(later, Collections.singletonList(
        changeInRange(later, 2, DomainValue.present(new byte[] {0x0B}),
            DomainValue.present(new byte[] {0x0C}))));
    store.unwindBlock(later);

    store.putChange(change(4, DomainValue.present(new byte[] {0x0B}),
        DomainValue.present(new byte[] {0x0D})));
    store.close();
    store = new RocksDbArchiveTemporalStore(dir.toString());

    store.validateTxNumsCovered(txNum -> true);
    assertArrayEquals(new byte[] {0x0D}, store.latest(ArchiveDomain.ACCOUNT, KEY).get()
        .getValue());
  }

  @Test
  public void validateTxNumsCoveredRejectsOrphanLatestBaselineMarker() throws Exception {
    store.close();
    try (Options options = new Options().setCreateIfMissing(false);
        RocksDB db = RocksDB.open(options, dir.toString());
        WriteBatch batch = new WriteBatch();
        WriteOptions writeOptions = new WriteOptions()) {
      batch.put(ArchiveTemporalCodec.latestBaselineKey(ArchiveDomain.ACCOUNT, KEY),
          ArchiveTemporalCodec.encodeValue(DomainValue.present(new byte[] {0x0B})));
      db.write(writeOptions, batch);
    }
    store = new RocksDbArchiveTemporalStore(dir.toString());

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> store.validateTxNumsCovered(txNum -> true));

    assertTrue(ex.getMessage().contains("baseline has no latest"));
  }

  @Test
  public void validateTxNumsCoveredRejectsHistoryWithoutLatest() throws Exception {
    store.putChange(change(8, DomainValue.tombstone(), DomainValue.present(new byte[] {0x0B})));
    store.close();
    try (Options options = new Options().setCreateIfMissing(false);
        RocksDB db = RocksDB.open(options, dir.toString());
        WriteBatch batch = new WriteBatch();
        WriteOptions writeOptions = new WriteOptions()) {
      batch.delete(ArchiveTemporalCodec.latestKey(ArchiveDomain.ACCOUNT, KEY));
      db.write(writeOptions, batch);
    }
    store = new RocksDbArchiveTemporalStore(dir.toString());

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> store.validateTxNumsCovered(txNum -> true));

    assertTrue(ex.getMessage().contains("latest missing"));
  }

  @Test
  public void validateTxNumsCoveredRejectsLatestValueMismatch() throws Exception {
    store.putChange(change(8, DomainValue.tombstone(), DomainValue.present(new byte[] {0x0B})));
    store.close();
    try (Options options = new Options().setCreateIfMissing(false);
        RocksDB db = RocksDB.open(options, dir.toString());
        WriteBatch batch = new WriteBatch();
        WriteOptions writeOptions = new WriteOptions()) {
      batch.put(ArchiveTemporalCodec.latestKey(ArchiveDomain.ACCOUNT, KEY),
          ArchiveTemporalCodec.encodeValue(DomainValue.present(new byte[] {0x0C})));
      db.write(writeOptions, batch);
    }
    store = new RocksDbArchiveTemporalStore(dir.toString());

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> store.validateTxNumsCovered(txNum -> true));

    assertTrue(ex.getMessage().contains("latest value mismatch"));
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

  private static byte[] rawHistoryKey(long txNum) {
    return rawHistoryKey(txNum, ArchiveDomain.ACCOUNT.getId());
  }

  private static byte[] rawHistoryKey(long txNum, int domainId) {
    byte[] key = new byte[16];
    key[0] = ArchiveTemporalCodec.HISTORY_PREFIX;
    putDomainId(key, 1, domainId);
    key[6] = 1;
    key[7] = KEY[0];
    putLong(key, 8, txNum);
    return key;
  }

  private static byte[] rawChangesetKey(long txNum) {
    return rawChangesetKey(txNum, ArchiveDomain.ACCOUNT.getId());
  }

  private static byte[] rawChangesetKey(long txNum, int domainId) {
    byte[] key = new byte[16];
    key[0] = ArchiveTemporalCodec.CHANGESET_PREFIX;
    putLong(key, 1, txNum);
    putDomainId(key, 9, domainId);
    key[14] = 1;
    key[15] = KEY[0];
    return key;
  }

  private static byte[] rawLatestKey(int domainId) {
    byte[] key = new byte[8];
    key[0] = ArchiveTemporalCodec.LATEST_PREFIX;
    putDomainId(key, 1, domainId);
    key[6] = 1;
    key[7] = KEY[0];
    return key;
  }

  private static void putDomainId(byte[] key, int offset, int domainId) {
    key[offset] = (byte) (domainId >>> 8);
    key[offset + 1] = (byte) domainId;
  }

  private static void putLong(byte[] key, int offset, long value) {
    for (int i = Long.BYTES - 1; i >= 0; i--) {
      key[offset + i] = (byte) value;
      value >>>= Byte.SIZE;
    }
  }
}
