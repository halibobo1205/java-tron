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
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import org.tron.common.math.StrictMathWrapper;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.db2.common.WrappedByteArray;

/**
 * In-memory reference {@link ArchiveTemporalStore} for the Erigon-v3 prev-value model: per
 * (domain, key) it keeps a txNum-ordered map of pre-change values (history) plus the latest value.
 * {@code getAsOf} is a forward {@code higherEntry} (first change after txNum) with fall-to-latest,
 * and {@code latest} a direct lookup. Not persistent and not pruned -- it defines the temporal
 * contract; the RocksDB implementation supersedes it for real nodes and must stay identical.
 *
 * <p>Single-writer: fed from the block-apply thread (the capture buffer drained at commit). Reads
 * may run concurrently; callers needing that should wrap or use the persistent implementation.
 */
public final class InMemoryArchiveTemporalStore implements ArchiveTemporalStore {

  private static final Comparator<ArchiveChangeRecord> RECORD_ORDER =
      InMemoryArchiveTemporalStore::compareRecords;

  /** Per (domain, key) state: txNum -> pre-change value (history), plus the current value. */
  private static final class KeyState {
    private final NavigableMap<Long, DomainValue> history = new TreeMap<>();
    private DomainValue latest;
  }

  private final Map<ArchiveDomain, Map<WrappedByteArray, KeyState>>
      byDomain = new EnumMap<>(ArchiveDomain.class);

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
    return left.isDeleted() == right.isDeleted()
        && Arrays.equals(left.getValue(), right.getValue());
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
    if (txNum < 0) {
      throw new ArchiveException("archive temporal txNum must be non-negative");
    }
    KeyState state = stateOf(domain, canonicalKey);
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

  @Override
  public Optional<DomainValue> latest(ArchiveDomain domain, byte[] canonicalKey) {
    KeyState state = stateOf(domain, canonicalKey);
    return Optional.ofNullable(state == null ? null : state.latest);
  }

  @Override
  public void unwind(long fromTxNum) {
    if (fromTxNum < 0) {
      throw new ArchiveException("archive temporal txNum must be non-negative");
    }
    if (fromTxNum == 0) {
      byDomain.clear();
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

  private KeyState stateOf(ArchiveDomain domain, byte[] canonicalKey) {
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
