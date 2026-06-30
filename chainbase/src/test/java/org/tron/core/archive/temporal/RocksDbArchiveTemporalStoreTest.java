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
import java.util.Collections;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
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
    store.close();
    deleteRecursively(dir.toFile());
  }

  private static ArchiveChangeRecord change(long txNum, DomainValue prev, DomainValue value) {
    return rec(txNum, ArchiveDomain.ACCOUNT, KEY, prev, value);
  }

  private static ArchiveChangeRecord rec(long txNum, ArchiveDomain domain, byte[] key,
      DomainValue prev, DomainValue value) {
    return new ArchiveChangeRecord(
        new ArchiveTxPosition(txNum, 1, ArchivePhase.USER_TX, ArchiveSource.NORMAL, 0, null),
        domain, key, prev, value);
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
  public void blockCommitMarkerSurvivesRestartAndValidatesRange() {
    ArchiveBlockRange range = new ArchiveBlockRange(
        3, 10, 11, 10, 11, 0, ArchiveSource.NORMAL);
    store.putBlockChanges(range, Collections.emptyList());
    store.validateCommittedBlock(range);

    store.close();
    store = new RocksDbArchiveTemporalStore(dir.toString());
    store.validateCommittedBlock(range);

    ArchiveBlockRange mismatched = new ArchiveBlockRange(
        3, 10, 12, 10, 11, 0, ArchiveSource.NORMAL);
    assertThrows(ArchiveException.class, () -> store.validateCommittedBlock(mismatched));
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
