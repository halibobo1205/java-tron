package org.tron.core.archive.temporal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
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
    return changeAt(txNum, 1, key, prev, value);
  }

  private static ArchiveChangeRecord changeAt(long txNum, long blockNum, byte[] key,
      DomainValue prev, DomainValue value) {
    return new ArchiveChangeRecord(
        new ArchiveTxPosition(txNum, blockNum, ArchivePhase.USER_TX, ArchiveSource.NORMAL, 0, null),
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
  public void putBlockChangesRejectsDuplicateChangesetWithoutPartialWrite() {
    ArchiveBlockRange range = new ArchiveBlockRange(
        1, 10, 12, 10, 12, new byte[32], 1, ArchiveSource.NORMAL);

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> store.putBlockChanges(range, Arrays.asList(
            change(11, KEY, tomb(), val(1)),
            change(11, KEY, val(1), val(2)))));

    assertTrue(ex.getMessage().contains("duplicate changeset row"));
    assertFalse(store.latest(ArchiveDomain.ACCOUNT, KEY).isPresent());
    assertEquals(0, store.changeCount());
  }

  @Test
  public void putBlockChangesOrdersSameKeyBeforeWritingLatest() {
    ArchiveBlockRange range = new ArchiveBlockRange(
        1, 10, 11, 10, 11, new byte[32], 1, ArchiveSource.NORMAL);

    store.putBlockChanges(range, Arrays.asList(
        change(11, KEY, val(0x0A), val(0x0B)),
        change(10, KEY, tomb(), val(0x0A))));

    assertArrayEquals(new byte[] {0x0A}, asOf(10));
    assertArrayEquals(new byte[] {0x0B}, asOf(11));
    assertArrayEquals(new byte[] {0x0B}, store.latest(ArchiveDomain.ACCOUNT, KEY)
        .get().getValue());
  }

  @Test
  public void putBlockChangesRejectsBrokenPrevValueChainWithoutPartialWrite() {
    ArchiveBlockRange range = new ArchiveBlockRange(
        1, 10, 11, 10, 11, new byte[32], 1, ArchiveSource.NORMAL);

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> store.putBlockChanges(range, Arrays.asList(
            change(10, KEY, tomb(), val(0x0A)),
            change(11, KEY, val(0x7F), val(0x0B)))));

    assertTrue(ex.getMessage().contains("prev-value chain"));
    assertFalse(store.latest(ArchiveDomain.ACCOUNT, KEY).isPresent());
    assertEquals(0, store.changeCount());
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
  public void unwindCreatedKeyRestoresTombstoneWhenNoOlderHistory() {
    // created at tx8; unwinding tx8 restores latest to the pre-change tombstone.
    store.putChange(change(8, KEY, tomb(), val(0x0B)));
    store.unwind(8);
    assertTrue(store.latest(ArchiveDomain.ACCOUNT, KEY).get().isDeleted());
    assertTrue(store.getAsOf(ArchiveDomain.ACCOUNT, KEY, 100).get().isDeleted());
    assertEquals(0, store.changeCount());
  }

  @Test
  public void unwindFromZeroClearsLatestOnlyResidue() {
    store.putChange(change(8, KEY, val(0x0A), val(0x0B)));
    store.unwind(8);
    assertArrayEquals(new byte[] {0x0A}, store.latest(ArchiveDomain.ACCOUNT, KEY).get()
        .getValue());
    assertEquals(0, store.changeCount());

    store.unwind(0);

    assertFalse(store.latest(ArchiveDomain.ACCOUNT, KEY).isPresent());
    assertFalse(store.getAsOf(ArchiveDomain.ACCOUNT, KEY, Long.MAX_VALUE).isPresent());
    assertEquals(0, store.changeCount());
  }

  @Test
  public void unwindRejectsNegativeTxNum() {
    assertThrows(ArchiveException.class, () -> store.unwind(-1));
  }

  @Test
  public void directUnwindRejectsCommittedRangeAndKeepsState() {
    ArchiveBlockRange range = new ArchiveBlockRange(
        3, 10, 11, 10, 11, new byte[32], 0, ArchiveSource.NORMAL);
    store.putBlockChanges(range,
        Arrays.asList(changeAt(10, 3, KEY, tomb(), val(0x0A))));

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> store.unwind(10));

    assertTrue(failure.getMessage().contains("unwindBlock"));
    assertArrayEquals(new byte[] {0x0A},
        store.latest(ArchiveDomain.ACCOUNT, KEY).get().getValue());
    assertEquals(1, store.changeCount());
  }

  @Test
  public void unwindBlockRejectsNonHeadBlockAndKeepsState() {
    // Parity with UnifiedArchiveTemporalStore.unwindBlock: only the temporal head block may be
    // unwound. The unbounded interface default would unwind(firstTxNum) and silently discard the
    // higher head block too; the override must instead reject the non-head range, state intact.
    ArchiveBlockRange first = new ArchiveBlockRange(
        3, 10, 11, 10, 11, new byte[32], 0, ArchiveSource.NORMAL);
    ArchiveBlockRange second = new ArchiveBlockRange(
        4, 12, 13, 12, 13, new byte[32], 0, ArchiveSource.NORMAL);
    store.putBlockChanges(first, Arrays.asList(changeAt(10, 3, KEY, tomb(), val(0x0A))));
    store.putBlockChanges(second, Arrays.asList(changeAt(12, 4, KEY, val(0x0A), val(0x0B))));

    ArchiveException ex = assertThrows(ArchiveException.class, () -> store.unwindBlock(first));

    assertTrue(ex.getMessage().contains("not temporal head"));
    assertArrayEquals(new byte[] {0x0B}, store.latest(ArchiveDomain.ACCOUNT, KEY).get().getValue());
    assertArrayEquals(new byte[] {0x0B}, asOf(100));
    assertEquals(2, store.changeCount());
  }

  @Test
  public void unwindBlockUnwindsHeadBlockAndRevertsLatest() {
    // The legitimate head-first path is unchanged: unwinding the head block reverts latest to the
    // prior block's value and drops only the head block's history row.
    ArchiveBlockRange first = new ArchiveBlockRange(
        3, 10, 11, 10, 11, new byte[32], 0, ArchiveSource.NORMAL);
    ArchiveBlockRange second = new ArchiveBlockRange(
        4, 12, 13, 12, 13, new byte[32], 0, ArchiveSource.NORMAL);
    store.putBlockChanges(first, Arrays.asList(changeAt(10, 3, KEY, tomb(), val(0x0A))));
    store.putBlockChanges(second, Arrays.asList(changeAt(12, 4, KEY, val(0x0A), val(0x0B))));

    store.unwindBlock(second);

    assertArrayEquals(new byte[] {0x0A}, store.latest(ArchiveDomain.ACCOUNT, KEY).get().getValue());
    assertArrayEquals(new byte[] {0x0A}, asOf(100));
    assertEquals(1, store.changeCount());
  }

  @Test
  public void unwindBlockRejectsNonHeadWhenHeadBlockIsEmpty() {
    // An empty head block leaves no history row; the head guard must still recognise it via the
    // committed-block set and reject unwinding the lower block, matching Unified block markers.
    ArchiveBlockRange b3 = new ArchiveBlockRange(
        3, 10, 11, 10, 11, new byte[32], 0, ArchiveSource.NORMAL);
    ArchiveBlockRange b4empty = new ArchiveBlockRange(
        4, 12, 13, 12, 13, new byte[32], 0, ArchiveSource.NORMAL);
    store.putBlockChanges(b3, Arrays.asList(changeAt(10, 3, KEY, tomb(), val(0x0A))));
    store.putBlockChanges(b4empty, Collections.emptyList());   // empty head block, no history row

    ArchiveException ex = assertThrows(ArchiveException.class, () -> store.unwindBlock(b3));

    assertTrue(ex.getMessage().contains("not temporal head"));
    assertArrayEquals(new byte[] {0x0A}, store.latest(ArchiveDomain.ACCOUNT, KEY).get().getValue());
  }

  @Test
  public void getAsOfRejectsNegativeTxNum() {
    store.putChange(change(5, KEY, tomb(), val(1)));

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> store.getAsOf(ArchiveDomain.ACCOUNT, KEY, -1));

    assertTrue(ex.getMessage().contains("non-negative"));
  }
}
