package org.tron.core.archive.temporal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveSource;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.txnum.ArchiveTxPosition;

/**
 * Erigon-v3 prev-value model: a change at txNum carries the value BEFORE it (-&gt; history) and the
 * value AFTER it (-&gt; latest). {@code getAsOf(T)} returns the value at the END of txNum T (the
 * prior floor-model contract): "first change after T -&gt; its prev-value, else latest".
 */
public class InMemoryArchiveTemporalStoreTest {

  private final InMemoryArchiveTemporalStore store = new InMemoryArchiveTemporalStore();
  private static final byte[] KEY = "k".getBytes();

  private static DomainValue tomb() {
    return DomainValue.tombstone();
  }

  private static DomainValue val(int b) {
    return DomainValue.present(new byte[] {(byte) b});
  }

  private static ArchiveChangeRecord change(long txNum, byte[] key, DomainValue prev,
      DomainValue value) {
    return new ArchiveChangeRecord(
        new ArchiveTxPosition(txNum, 1, ArchivePhase.USER_TX, ArchiveSource.NORMAL, 0, null),
        ArchiveDomain.ACCOUNT, key, prev, value);
  }

  private byte[] asOf(long txNum) {
    return store.getAsOf(ArchiveDomain.ACCOUNT, KEY, txNum).get().getValue();
  }

  @Test
  public void getAsOfReturnsValueAtEndOfTxNum() {
    // key created at tx5 (absent -> 0x0A), then 0x0A -> 0x0B at tx8.
    store.putChange(change(5, KEY, tomb(), val(0x0A)));
    store.putChange(change(8, KEY, val(0x0A), val(0x0B)));
    // before the key existed: absent (tombstone), NOT the live/latest value.
    assertTrue(store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 4).get().isDeleted());
    // value at end of tx5..tx7 is 0x0A; at end of tx8 onward is 0x0B (same as the floor model).
    assertArrayEquals(new byte[] {0x0A}, asOf(5));
    assertArrayEquals(new byte[] {0x0A}, asOf(7));
    assertArrayEquals(new byte[] {0x0B}, asOf(8));
    assertArrayEquals(new byte[] {0x0B}, asOf(100));
  }

  @Test
  public void latestReturnsValueAfterLastChange() {
    store.putChange(change(5, KEY, tomb(), val(1)));
    store.putChange(change(8, KEY, val(1), val(2)));
    assertArrayEquals(new byte[] {2}, store.latest(ArchiveDomain.ACCOUNT, KEY).get().getValue());
  }

  @Test
  public void sameValueWriteSeedsLatestAndHistoryCoverage() {
    store.putChange(change(5, KEY, val(1), val(1)));
    assertArrayEquals(new byte[] {1}, store.latest(ArchiveDomain.ACCOUNT, KEY).get().getValue());
    assertArrayEquals(new byte[] {1}, asOf(5));
    assertEquals(1, store.changeCount());

    store.putChange(change(6, KEY, tomb(), tomb()));
    assertTrue(store.latest(ArchiveDomain.ACCOUNT, KEY).get().isDeleted());
    assertTrue(store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 6).get().isDeleted());
    assertEquals(2, store.changeCount());
  }

  @Test
  public void fallToLatestWhenNoChangeAfterQuery() {
    // created at tx5 and never changed again: every query at/after tx5 falls through to latest.
    store.putChange(change(5, KEY, tomb(), val(0x0A)));
    assertArrayEquals(new byte[] {0x0A}, asOf(5));
    assertArrayEquals(new byte[] {0x0A}, asOf(100));
    assertTrue(store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 4).get().isDeleted()); // before creation
  }

  @Test
  public void midChainFirstCapturedChangeServesPrevValueBeforeCoverage() {
    // The key existed before archive coverage as 0x30; the first captured change moves it to 0x31.
    store.putChange(change(6, KEY, val(0x30), val(0x31)));
    assertArrayEquals(new byte[] {0x30}, asOf(0));
    assertArrayEquals(new byte[] {0x30}, asOf(5));
    assertArrayEquals(new byte[] {0x31}, asOf(6));
    assertArrayEquals(new byte[] {0x31}, asOf(100));
    assertFalse(store.getAsOf(ArchiveDomain.ACCOUNT, "gap".getBytes(), 5).isPresent());
  }

  @Test
  public void tombstoneFallsThroughAsDeleted() {
    store.putChange(change(5, KEY, tomb(), val(1)));
    store.putChange(change(9, KEY, val(1), tomb()));
    assertFalse(store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 8).get().isDeleted()); // value 1
    assertTrue(store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 9).get().isDeleted());  // deleted at tx9
    assertTrue(store.latest(ArchiveDomain.ACCOUNT, KEY).get().isDeleted());
  }

  @Test
  public void unknownKeyAndDomainAreIsolated() {
    assertFalse(store.latest(ArchiveDomain.ACCOUNT, "nope".getBytes()).isPresent());
    store.putChange(change(5, KEY, tomb(), val(1)));
    assertFalse(store.getAsOf(ArchiveDomain.CODE, KEY, 5).isPresent()); // different domain
  }

  @Test
  public void unwindRestoresLatestToPreValueOfSmallestDropped() {
    store.putChange(change(5, KEY, tomb(), val(0x0A)));
    store.putChange(change(8, KEY, val(0x0A), val(0x0B)));
    store.putChange(change(12, KEY, val(0x0B), val(0x0C)));
    store.unwind(8); // drop tx8 and tx12; latest reverts to the pre-value of tx8 = 0x0A
    assertArrayEquals(new byte[] {0x0A}, store.latest(ArchiveDomain.ACCOUNT, KEY).get().getValue());
    assertArrayEquals(new byte[] {0x0A}, asOf(100));
    assertEquals(1, store.changeCount());
  }

  @Test
  public void unwindCreatedKeyDropsLatestWhenNoOlderHistory() {
    // created at tx8; unwinding tx8 drops both history and latest so no latest-only row remains.
    store.putChange(change(8, KEY, tomb(), val(0x0B)));
    store.unwind(8);
    assertFalse(store.latest(ArchiveDomain.ACCOUNT, KEY).isPresent());
    assertFalse(store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 100).isPresent());
    assertEquals(0, store.changeCount());
  }

  @Test
  public void unwindFromZeroClearsLatestOnlyResidue() {
    store.putChange(change(8, KEY, val(0x0A), val(0x0B)));
    store.unwind(8);
    assertFalse(store.latest(ArchiveDomain.ACCOUNT, KEY).isPresent());
    assertEquals(0, store.changeCount());

    store.unwind(0);

    assertFalse(store.latest(ArchiveDomain.ACCOUNT, KEY).isPresent());
    assertFalse(store.getAsOf(ArchiveDomain.ACCOUNT, KEY, Long.MAX_VALUE).isPresent());
    assertEquals(0, store.changeCount());
  }
}
