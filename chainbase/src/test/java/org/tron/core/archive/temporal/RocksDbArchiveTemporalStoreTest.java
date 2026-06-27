package org.tron.core.archive.temporal;

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
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.txnum.ArchiveTxPosition;

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

  private static ArchiveChangeRecord change(long txNum, DomainValue value) {
    return new ArchiveChangeRecord(
        new ArchiveTxPosition(txNum, 1, ArchivePhase.USER_TX, ArchiveSource.NORMAL, 0, null),
        ArchiveDomain.ACCOUNT, KEY, value);
  }

  @Test
  public void getAsOfAndLatestPersistAcrossWrites() {
    store.putChange(change(5, DomainValue.present(new byte[] {0x0A})));
    store.putChange(change(8, DomainValue.present(new byte[] {0x0B})));
    assertFalse(store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 4).isPresent());
    assertArrayEquals(new byte[] {0x0A},
        store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 5).get().getValue());
    assertArrayEquals(new byte[] {0x0A},
        store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 7).get().getValue());
    assertArrayEquals(new byte[] {0x0B},
        store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 8).get().getValue());
    assertArrayEquals(new byte[] {0x0B}, store.latest(ArchiveDomain.ACCOUNT, KEY).get().getValue());
  }

  @Test
  public void tombstoneIsPersistedAsDeleted() {
    store.putChange(change(5, DomainValue.present(new byte[] {1})));
    store.putChange(change(9, DomainValue.tombstone()));
    assertFalse(store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 8).get().isDeleted());
    assertTrue(store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 9).get().isDeleted());
    assertTrue(store.latest(ArchiveDomain.ACCOUNT, KEY).get().isDeleted());
  }

  @Test
  public void reopenRetainsData() {
    store.putChange(change(5, DomainValue.present(new byte[] {0x42})));
    store.close();
    store = new RocksDbArchiveTemporalStore(dir.toString());
    assertArrayEquals(new byte[] {0x42}, store.latest(ArchiveDomain.ACCOUNT, KEY).get().getValue());
  }

  @Test
  public void unknownKeyIsEmpty() {
    assertFalse(store.latest(ArchiveDomain.ACCOUNT, new byte[] {99}).isPresent());
    assertFalse(store.getAsOf(ArchiveDomain.ACCOUNT, new byte[] {99}, 5).isPresent());
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
