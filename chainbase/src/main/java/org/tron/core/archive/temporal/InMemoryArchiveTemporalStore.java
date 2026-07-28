package org.tron.core.archive.temporal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import org.tron.common.math.StrictMathWrapper;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.query.QueryContext;
import org.tron.core.archive.query.QueryContextHolder;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.db2.common.WrappedByteArray;

/**
 * In-memory reference {@link ArchiveTemporalStore} for the Erigon-v3 prev-value model: per
 * (domain, key) it keeps a txNum-ordered map of pre-change values (history) plus the latest value.
 * {@code getAsOf} is a forward {@code higherEntry} (first change after txNum) with fall-to-latest,
 * and {@code latest} a direct lookup. Not persistent and not pruned -- it defines the temporal
 * contract; the UNIFIED_V1 temporal adapter is used by enabled nodes and must stay identical.
 *
 * <p>Single-writer: fed from the block-apply thread (the capture buffer drained at commit). Reads
 * may run concurrently; callers needing that should wrap or use the persistent implementation.
 */
public final class InMemoryArchiveTemporalStore implements ArchiveTemporalStore {

  private static final Comparator<ArchiveChangeRecord> RECORD_ORDER =
      InMemoryArchiveTemporalStore::compareRecords;
  private static final long SCAN_RESULT_KEY_OVERHEAD_BYTES = 48L;

  /** Per (domain, key) state: txNum -> pre-change value (history), plus the current value. */
  private static final class KeyState {
    private final NavigableMap<Long, DomainValue> history = new TreeMap<>();
    private DomainValue latest;
  }

  private final Map<ArchiveDomain, Map<WrappedByteArray, KeyState>>
      byDomain = new EnumMap<>(ArchiveDomain.class);

  // Committed block numbers, including empty blocks (which leave no history row). The temporal head
  // is the maximum committed block; tracking it lets unwindBlock reject a non-head range even when
  // the true head block changed no state -- matching RocksDb's block-commit-marker head guard.
  private final NavigableSet<Long> committedBlockNums = new TreeSet<>();

  @Override
  public void putChange(ArchiveChangeRecord record) {
    Map<WrappedByteArray, KeyState> domainMap =
        byDomain.computeIfAbsent(record.getDomain(), d -> new HashMap<>());
    KeyState state = domainMap.computeIfAbsent(
        WrappedByteArray.of(record.getCanonicalKey()), k -> new KeyState());
    // Plain last-wins on both, mirroring the RocksDB batch puts so the two stores stay identical;
    // within-tx collapsing to first-prev/last-new is the drain's job (DefaultArchiveService).
    state.history.put(record.getTxNum(), record.getPrevValue());
    state.latest = record.getValue();
  }

  @Override
  public void putChanges(List<ArchiveChangeRecord> records) {
    List<ArchiveChangeRecord> ordered = orderedRecords(records);
    validatePrevValueChain(ordered);
    for (ArchiveChangeRecord record : ordered) {
      putChange(record);
    }
  }

  @Override
  public void putBlockChanges(ArchiveBlockRange range, List<ArchiveChangeRecord> records) {
    List<ArchiveChangeRecord> ordered = orderedRecords(records);
    Set<ChangeKey> changes = new HashSet<>();
    for (ArchiveChangeRecord record : ordered) {
      validateRecordInRange(range, record);
      ChangeKey key = new ChangeKey(record);
      if (!changes.add(key)) {
        throw new ArchiveException("archive temporal duplicate changeset row");
      }
    }
    validatePrevValueChain(ordered);
    for (ArchiveChangeRecord record : ordered) {
      putChange(record);
    }
    // Record the block even when it wrote no state, so an empty head block is still the head.
    committedBlockNums.add(range.getBlockNum());
  }

  private void validatePrevValueChain(List<ArchiveChangeRecord> ordered) {
    Map<WrappedByteArray, DomainValue> stagedLatest = new HashMap<>();
    for (ArchiveChangeRecord record : ordered) {
      WrappedByteArray key = latestKeyOf(record);
      DomainValue expected = stagedLatest.get(key);
      if (expected == null) {
        Optional<DomainValue> latest = latest(record.getDomain(), record.getCanonicalKey());
        expected = latest.orElse(null);
      }
      if (expected != null && !sameDomainValue(expected, record.getPrevValue())) {
        throw new ArchiveException("archive temporal prev-value chain mismatch for txNum "
            + record.getTxNum());
      }
      stagedLatest.put(key, record.getValue());
    }
  }

  private static List<ArchiveChangeRecord> orderedRecords(List<ArchiveChangeRecord> records) {
    List<ArchiveChangeRecord> ordered = new ArrayList<>(records);
    Collections.sort(ordered, RECORD_ORDER);
    return ordered;
  }

  private static WrappedByteArray latestKeyOf(ArchiveChangeRecord record) {
    return WrappedByteArray.copyOf(
        ArchiveTemporalCodec.latestKey(record.getDomain(), record.getCanonicalKey()));
  }

  private static boolean sameDomainValue(DomainValue left, DomainValue right) {
    return left.contentEquals(right);
  }

  private static int compareRecords(ArchiveChangeRecord left, ArchiveChangeRecord right) {
    int result = Long.compare(left.getTxNum(), right.getTxNum());
    if (result != 0) {
      return result;
    }
    result = Integer.compare(left.getDomain().getId(), right.getDomain().getId());
    if (result != 0) {
      return result;
    }
    return compareBytes(left.getCanonicalKey(), right.getCanonicalKey());
  }

  private static int compareBytes(byte[] left, byte[] right) {
    int length = StrictMathWrapper.min(left.length, right.length);
    for (int i = 0; i < length; i++) {
      int diff = (left[i] & 0xff) - (right[i] & 0xff);
      if (diff != 0) {
        return diff;
      }
    }
    return left.length - right.length;
  }

  @Override
  public Optional<DomainValue> getAsOf(ArchiveDomain domain, byte[] canonicalKey, long txNum) {
    return resolveAsOf(byDomain, domain, canonicalKey, txNum);
  }

  @Override
  public Optional<DomainValue> latest(ArchiveDomain domain, byte[] canonicalKey) {
    return resolveLatest(byDomain, domain, canonicalKey);
  }

  @Override
  public List<byte[]> scanLatestCanonicalKeys(ArchiveDomain domain,
      int canonicalKeyLength, byte[] canonicalPrefix) {
    return scanLatestCanonicalKeys(byDomain, domain, canonicalKeyLength, canonicalPrefix);
  }

  @Override
  public ArchiveTemporalReadView openReadView() {
    // Isolated point-in-time view over a deep copy of the in-memory state (test-only store, so the
    // copy cost is irrelevant); later store mutations create new entries and never touch the copy.
    Map<ArchiveDomain, Map<WrappedByteArray, KeyState>> snapshot = deepCopy(byDomain);
    return new ArchiveTemporalReadView() {
      @Override
      public Optional<DomainValue> getAsOf(ArchiveDomain domain, byte[] canonicalKey, long txNum) {
        return resolveAsOf(snapshot, domain, canonicalKey, txNum);
      }

      @Override
      public Optional<DomainValue> latest(ArchiveDomain domain, byte[] canonicalKey) {
        return resolveLatest(snapshot, domain, canonicalKey);
      }

      @Override
      public List<byte[]> scanLatestCanonicalKeys(ArchiveDomain domain,
          int canonicalKeyLength, byte[] canonicalPrefix) {
        return InMemoryArchiveTemporalStore.scanLatestCanonicalKeys(
            snapshot, domain, canonicalKeyLength, canonicalPrefix);
      }

      @Override
      public void close() {
      }
    };
  }

  private static Optional<DomainValue> resolveAsOf(
      Map<ArchiveDomain, Map<WrappedByteArray, KeyState>> byDomain, ArchiveDomain domain,
      byte[] canonicalKey, long txNum) {
    if (txNum < 0) {
      throw new ArchiveException("archive temporal txNum must be non-negative");
    }
    KeyState state = stateOf(byDomain, domain, canonicalKey);
    if (state == null) {
      return Optional.empty();
    }
    // The first change strictly after txNum; its pre-change value is the value as of txNum.
    Map.Entry<Long, DomainValue> next = state.history.higherEntry(txNum);
    if (next != null) {
      return Optional.of(next.getValue());
    }
    // No change after txNum: the key has not changed since, so its value then == latest.
    return Optional.ofNullable(state.latest);
  }

  private static Optional<DomainValue> resolveLatest(
      Map<ArchiveDomain, Map<WrappedByteArray, KeyState>> byDomain, ArchiveDomain domain,
      byte[] canonicalKey) {
    KeyState state = stateOf(byDomain, domain, canonicalKey);
    return Optional.ofNullable(state == null ? null : state.latest);
  }

  private static List<byte[]> scanLatestCanonicalKeys(
      Map<ArchiveDomain, Map<WrappedByteArray, KeyState>> byDomain,
      ArchiveDomain domain, int canonicalKeyLength, byte[] canonicalPrefix) {
    if (domain == null) {
      throw new NullPointerException("domain");
    }
    if (canonicalPrefix == null) {
      throw new NullPointerException("canonicalPrefix");
    }
    if (canonicalKeyLength < canonicalPrefix.length) {
      throw new IllegalArgumentException(
          "canonical key length must cover the canonical prefix");
    }
    QueryContext queryContext = QueryContextHolder.current();
    if (queryContext != null) {
      queryContext.recordBackendRead();
    }
    Map<WrappedByteArray, KeyState> domainMap = byDomain.get(domain);
    if (domainMap == null || domainMap.isEmpty()) {
      return Collections.emptyList();
    }
    List<byte[]> matches = new ArrayList<>();
    for (Map.Entry<WrappedByteArray, KeyState> entry : domainMap.entrySet()) {
      if (queryContext != null) {
        queryContext.checkDeadline();
      }
      byte[] key = entry.getKey().getBytes();
      if (entry.getValue().latest != null
          && key.length == canonicalKeyLength && startsWith(key, canonicalPrefix)) {
        if (queryContext != null) {
          queryContext.recordBackendRead();
          queryContext.recordVmOverlayBytes(SCAN_RESULT_KEY_OVERHEAD_BYTES + key.length);
        }
        matches.add(Arrays.copyOf(key, key.length));
      }
    }
    matches.sort(InMemoryArchiveTemporalStore::compareBytes);
    return matches;
  }

  private static boolean startsWith(byte[] value, byte[] prefix) {
    if (value.length < prefix.length) {
      return false;
    }
    for (int i = 0; i < prefix.length; i++) {
      if (value[i] != prefix[i]) {
        return false;
      }
    }
    return true;
  }

  private static Map<ArchiveDomain, Map<WrappedByteArray, KeyState>> deepCopy(
      Map<ArchiveDomain, Map<WrappedByteArray, KeyState>> src) {
    Map<ArchiveDomain, Map<WrappedByteArray, KeyState>> copy = new EnumMap<>(ArchiveDomain.class);
    for (Map.Entry<ArchiveDomain, Map<WrappedByteArray, KeyState>> domainEntry : src.entrySet()) {
      Map<WrappedByteArray, KeyState> domainCopy = new HashMap<>();
      for (Map.Entry<WrappedByteArray, KeyState> keyEntry : domainEntry.getValue().entrySet()) {
        KeyState original = keyEntry.getValue();
        KeyState clone = new KeyState();
        clone.history.putAll(original.history);  // DomainValue is immutable, so sharing refs is ok
        clone.latest = original.latest;
        domainCopy.put(keyEntry.getKey(), clone);
      }
      copy.put(domainEntry.getKey(), domainCopy);
    }
    return copy;
  }

  @Override
  public void unwind(long fromTxNum) {
    if (fromTxNum < 0L) {
      throw new ArchiveException("archive temporal txNum must be non-negative");
    }
    if (fromTxNum != 0L && !committedBlockNums.isEmpty()) {
      throw new ArchiveException(
          "committed archive temporal data must be unwound with unwindBlock");
    }
    unwindChanges(fromTxNum);
  }

  private void unwindChanges(long fromTxNum) {
    if (fromTxNum < 0) {
      throw new ArchiveException("archive temporal txNum must be non-negative");
    }
    if (fromTxNum == 0) {
      byDomain.clear();
      committedBlockNums.clear();
      return;
    }
    for (Map<WrappedByteArray, KeyState> domainMap : byDomain.values()) {
      Iterator<KeyState> states = domainMap.values().iterator();
      while (states.hasNext()) {
        KeyState state = states.next();
        SortedMap<Long, DomainValue> dropped = state.history.tailMap(fromTxNum);
        if (!dropped.isEmpty()) {
          // Restore to the pre-value of the smallest dropped change. A tombstone means "known
          // absent"; it is still a valid latest baseline after the history row is removed.
          state.latest = dropped.get(dropped.firstKey());
          dropped.clear();
        }
        if (state.history.isEmpty() && state.latest == null) {
          states.remove();
        }
      }
    }
  }

  @Override
  public void unwindBlock(ArchiveBlockRange range) {
    // Parity with UnifiedArchiveTemporalStore.unwindBlock: only the temporal head block may be
    // unwound. The head is the maximum COMMITTED block (committedBlockNums), NOT the max history
    // txNum -- an empty head block leaves no history row, so a txNum-only guard would miss it and
    // wrongly accept unwinding a lower block. The unbounded interface default would then silently
    // discard higher/head blocks that the fail-stop RocksDb path (validateHeadBlock) rejects with
    // state intact. Guard so the two stores stay observationally identical.
    if (!committedBlockNums.isEmpty() && committedBlockNums.last() != range.getBlockNum()) {
      throw new ArchiveException("cannot unwind archive temporal block " + range.getBlockNum()
          + ": not temporal head");
    }
    unwindChanges(range.getFirstTxNum());
    committedBlockNums.remove(range.getBlockNum());
  }

  /** Total number of txNum history entries across all domains/keys; for diagnostics and tests. */
  public int changeCount() {
    int count = 0;
    for (Map<WrappedByteArray, KeyState> domainMap : byDomain.values()) {
      for (KeyState state : domainMap.values()) {
        count += state.history.size();
      }
    }
    return count;
  }

  private static KeyState stateOf(Map<ArchiveDomain, Map<WrappedByteArray, KeyState>> byDomain,
      ArchiveDomain domain, byte[] canonicalKey) {
    Map<WrappedByteArray, KeyState> domainMap = byDomain.get(domain);
    return (domainMap == null) ? null : domainMap.get(WrappedByteArray.of(canonicalKey));
  }

  private static void validateRecordInRange(ArchiveBlockRange range, ArchiveChangeRecord record) {
    long txNum = record.getTxNum();
    if (txNum < range.getFirstTxNum() || txNum > range.getLastTxNum()) {
      throw new ArchiveException("archive temporal change txNum " + txNum
          + " is outside committed block range " + range.getBlockNum());
    }
    if (record.getPosition().getBlockNum() != range.getBlockNum()
        || record.getPosition().getSource() != range.getSource()) {
      throw new ArchiveException("archive temporal change position does not match block range "
          + range.getBlockNum());
    }
  }

  private static final class ChangeKey {

    private final long txNum;
    private final ArchiveDomain domain;
    private final WrappedByteArray canonicalKey;

    private ChangeKey(ArchiveChangeRecord record) {
      this.txNum = record.getTxNum();
      this.domain = record.getDomain();
      this.canonicalKey = WrappedByteArray.copyOf(record.getCanonicalKey());
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (!(obj instanceof ChangeKey)) {
        return false;
      }
      ChangeKey other = (ChangeKey) obj;
      return txNum == other.txNum
          && domain == other.domain
          && canonicalKey.equals(other.canonicalKey);
    }

    @Override
    public int hashCode() {
      int result = Long.hashCode(txNum);
      result = 31 * result + domain.hashCode();
      result = 31 * result + canonicalKey.hashCode();
      return result;
    }
  }
}
