package org.tron.core.archive.temporal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveSource;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.txnum.ArchiveTxPosition;

public class InMemoryArchiveTemporalStoreTest {

  private final InMemoryArchiveTemporalStore store = new InMemoryArchiveTemporalStore();
  private static final byte[] KEY = "k".getBytes();

  private static ArchiveChangeRecord change(long txNum, byte[] key, DomainValue value) {
    return new ArchiveChangeRecord(
        new ArchiveTxPosition(txNum, 1, ArchivePhase.USER_TX, ArchiveSource.NORMAL, 0, null),
        ArchiveDomain.ACCOUNT, key, value);
  }

  @Test
  public void getAsOfIsInclusiveFloorByTxNum() {
    store.putChange(change(5, KEY, DomainValue.present(new byte[] {0x0A})));
    store.putChange(change(8, KEY, DomainValue.present(new byte[] {0x0B})));
    assertFalse(store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 4).isPresent()); // before first write
    assertArrayEquals(new byte[] {0x0A},
        store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 5).get().getValue());
    assertArrayEquals(new byte[] {0x0A},
        store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 7).get().getValue());
    assertArrayEquals(new byte[] {0x0B},
        store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 8).get().getValue());
    assertArrayEquals(new byte[] {0x0B},
        store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 100).get().getValue());
  }

  @Test
  public void latestReturnsHighestTxNum() {
    store.putChange(change(5, KEY, DomainValue.present(new byte[] {1})));
    store.putChange(change(8, KEY, DomainValue.present(new byte[] {2})));
    assertArrayEquals(new byte[] {2}, store.latest(ArchiveDomain.ACCOUNT, KEY).get().getValue());
  }

  @Test
  public void tombstoneIsReturnedAsDeleted() {
    store.putChange(change(5, KEY, DomainValue.present(new byte[] {1})));
    store.putChange(change(9, KEY, DomainValue.tombstone()));
    assertFalse(store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 8).get().isDeleted());
    assertTrue(store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 9).get().isDeleted());
    assertTrue(store.latest(ArchiveDomain.ACCOUNT, KEY).get().isDeleted());
  }

  @Test
  public void unknownKeyAndDomainAreIsolated() {
    assertFalse(store.latest(ArchiveDomain.ACCOUNT, "nope".getBytes()).isPresent());
    store.putChange(change(5, KEY, DomainValue.present(new byte[] {1})));
    assertFalse(store.getAsOf(ArchiveDomain.CODE, KEY, 5).isPresent()); // different domain
  }
}
