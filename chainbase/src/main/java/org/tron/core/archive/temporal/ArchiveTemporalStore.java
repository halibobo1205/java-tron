package org.tron.core.archive.temporal;

import java.util.List;
import java.util.Optional;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.txnum.ArchiveBlockRange;

/**
 * Temporal (Erigon-v3) store of domain state changes keyed by txNum. Each captured
 * {@link ArchiveChangeRecord} versions a (domain, canonicalKey) change at its txNum: its
 * {@code prevValue} (the value BEFORE the change, Erigon {@code AddPrevValue}) goes to the
 * txNum-versioned history and its {@code value} (the value AFTER) goes to latest.
 *
 * <p>L5 ships the in-memory reference implementation; the RocksDB-backed implementation (latest /
 * history / changeset in one column family) is the persistent one. This interface is the read/write
 * contract both share, and the two MUST stay observationally identical.
 */
public interface ArchiveTemporalStore {

  /** Record a domain change at its txNum (prevValue -> history, value -> latest). */
  void putChange(ArchiveChangeRecord record);

  /** Record a batch of domain changes atomically when the implementation supports it. */
  default void putChanges(List<ArchiveChangeRecord> records) {
    for (ArchiveChangeRecord record : records) {
      putChange(record);
    }
  }

  /**
   * Record all changes for a committed block. Persistent stores may write a per-block commit marker
   * with the same batch so startup can reject an index range whose temporal rows never landed.
   */
  default void putBlockChanges(ArchiveBlockRange range, List<ArchiveChangeRecord> records) {
    putChanges(records);
  }

  /** Validate that a persistent temporal store has durably applied {@code range}. */
  default void validateCommittedBlock(ArchiveBlockRange range) {
  }

  /**
   * The value of {@code (domain, key)} as of {@code txNum} (inclusive-after): the value set by the
   * most recent change whose {@code change.txNum <= txNum} -- equivalently, the value at the END of
   * txNum {@code txNum}. "Before-tx" reads use {@code getAsOf(domain, key, txNum - 1)}.
   *
   * <p>Under the Erigon prev-value model this is the prevValue of the first change with
   * {@code change.txNum > txNum} (whose pre-change value is exactly the value as of {@code txNum});
   * or, when the key has no change after {@code txNum}, its {@code latest} value (it has not
   * changed since, so latest == the value then -- "fall-to-latest"). Empty only when the key has no
   * change after {@code txNum} AND no latest value (never captured). A returned tombstone
   * ({@link DomainValue#isDeleted()}) means the key was absent / deleted as of {@code txNum}.
   *
   * <p>This preserves the prior floor-model contract for every key with a captured change at or
   * before {@code txNum}; it additionally serves a query that falls before the key's first captured
   * change by returning that change's prevValue (mid-chain back-resolution) instead of empty.
   */
  Optional<DomainValue> getAsOf(ArchiveDomain domain, byte[] canonicalKey, long txNum);

  /** The most recent value of {@code (domain, key)} across all txNums; empty if never written.
   * May be a tombstone when the most recent change was a delete. */
  Optional<DomainValue> latest(ArchiveDomain domain, byte[] canonicalKey);

  /**
   * Drop every change with {@code txNum >= fromTxNum} and restore each affected key's latest value
   * to the prevValue of its smallest dropped change (= that key's value at the end of
   * {@code fromTxNum - 1}). Used when a committed block is reverted (fork switch / eraseBlock) so
   * the store never retains rolled-back state.
   */
  void unwind(long fromTxNum);
}
