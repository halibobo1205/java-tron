package org.tron.core.archive.temporal;

import java.util.Optional;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;

/**
 * Temporal (Erigon-v3 style) store of domain state changes keyed by txNum. Each captured
 * {@link ArchiveChangeRecord} is a versioned write of a (domain, canonicalKey) at its txNum; the
 * store answers historical reads by txNum.
 *
 * <p>L5 ships the in-memory reference implementation; the RocksDB-backed implementation (column
 * families for latest / history / changeset) lands in the arm64 module later. This interface is the
 * read/write contract both share.
 */
public interface ArchiveTemporalStore {

  /** Record a domain change at its txNum. */
  void putChange(ArchiveChangeRecord record);

  /**
   * The value of {@code (domain, key)} as of {@code txNum} (inclusive): the value set by the
   * most recent change whose {@code change.txNum <= txNum}. Empty if no change at or before
   * {@code txNum}. A returned tombstone ({@link DomainValue#isDeleted()}) means the key was
   * explicitly deleted then. "Before-tx" reads use {@code getAsOf(domain, key, txNum - 1)}.
   */
  Optional<DomainValue> getAsOf(ArchiveDomain domain, byte[] canonicalKey, long txNum);

  /** The most recent value of {@code (domain, key)} across all txNums; empty if never written. */
  Optional<DomainValue> latest(ArchiveDomain domain, byte[] canonicalKey);
}
