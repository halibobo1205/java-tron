package org.tron.core.archive.temporal;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
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
  public Optional<DomainValue> getAsOf(ArchiveDomain domain, byte[] canonicalKey, long txNum) {
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
    for (Map<WrappedByteArray, KeyState> domainMap : byDomain.values()) {
      Iterator<KeyState> states = domainMap.values().iterator();
      while (states.hasNext()) {
        KeyState state = states.next();
        SortedMap<Long, DomainValue> dropped = state.history.tailMap(fromTxNum);
        if (!dropped.isEmpty()) {
          // Restore latest to the smallest dropped change's pre-value = value at end of
          // (fromTxNum - 1); independent of any surviving (older) history.
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
}
