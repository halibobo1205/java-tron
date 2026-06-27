package org.tron.core.archive.temporal;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.db2.common.WrappedByteArray;

/**
 * In-memory reference {@link ArchiveTemporalStore}: per (domain, key) it keeps a txNum-ordered map
 * of values, so {@code getAsOf} is a {@code floorEntry} and {@code latest} a {@code lastEntry}.
 * Not persistent and not pruned -- it defines the temporal contract; the RocksDB implementation
 * (latest / history / changeset column families) supersedes it for real nodes.
 *
 * <p>Single-writer: fed from the block-apply thread (the capture buffer drained at commit). Reads
 * may run concurrently; callers needing that should wrap or use the persistent implementation.
 */
public final class InMemoryArchiveTemporalStore implements ArchiveTemporalStore {

  private final Map<ArchiveDomain, Map<WrappedByteArray, NavigableMap<Long, DomainValue>>>
      byDomain = new EnumMap<>(ArchiveDomain.class);

  @Override
  public void putChange(ArchiveChangeRecord record) {
    Map<WrappedByteArray, NavigableMap<Long, DomainValue>> domainMap =
        byDomain.computeIfAbsent(record.getDomain(), d -> new HashMap<>());
    NavigableMap<Long, DomainValue> history = domainMap.computeIfAbsent(
        WrappedByteArray.of(record.getCanonicalKey()), k -> new TreeMap<>());
    history.put(record.getTxNum(), record.getValue());
  }

  @Override
  public Optional<DomainValue> getAsOf(ArchiveDomain domain, byte[] canonicalKey, long txNum) {
    NavigableMap<Long, DomainValue> history = historyOf(domain, canonicalKey);
    if (history == null) {
      return Optional.empty();
    }
    Map.Entry<Long, DomainValue> entry = history.floorEntry(txNum);
    return Optional.ofNullable(entry == null ? null : entry.getValue());
  }

  @Override
  public Optional<DomainValue> latest(ArchiveDomain domain, byte[] canonicalKey) {
    NavigableMap<Long, DomainValue> history = historyOf(domain, canonicalKey);
    if (history == null || history.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(history.lastEntry().getValue());
  }

  @Override
  public void unwind(long fromTxNum) {
    for (Map<WrappedByteArray, NavigableMap<Long, DomainValue>> domainMap : byDomain.values()) {
      Iterator<NavigableMap<Long, DomainValue>> histories = domainMap.values().iterator();
      while (histories.hasNext()) {
        NavigableMap<Long, DomainValue> history = histories.next();
        history.tailMap(fromTxNum, true).clear(); // latest() is derived (lastEntry); it self-heals
        if (history.isEmpty()) {
          histories.remove();
        }
      }
    }
  }

  /** Total number of txNum change entries across all domains/keys; for diagnostics and tests. */
  public int changeCount() {
    int count = 0;
    for (Map<WrappedByteArray, NavigableMap<Long, DomainValue>> domainMap : byDomain.values()) {
      for (NavigableMap<Long, DomainValue> history : domainMap.values()) {
        count += history.size();
      }
    }
    return count;
  }

  private NavigableMap<Long, DomainValue> historyOf(ArchiveDomain domain, byte[] canonicalKey) {
    Map<WrappedByteArray, NavigableMap<Long, DomainValue>> domainMap = byDomain.get(domain);
    return (domainMap == null) ? null : domainMap.get(WrappedByteArray.of(canonicalKey));
  }
}
