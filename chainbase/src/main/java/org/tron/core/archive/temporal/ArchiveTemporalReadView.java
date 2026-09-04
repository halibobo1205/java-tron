package org.tron.core.archive.temporal;

import java.util.List;
import java.util.Optional;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;

/**
 * An isolated, point-in-time read view over an {@link ArchiveTemporalStore}, exposing exactly the
 * two methods the archive state reader needs. Opened via {@code openReadView()} under the archive
 * read lock, it lets a historical {@code eth_call} run its VM against a frozen snapshot after the
 * lock is released, so a long call no longer stalls block commit. The view MUST be closed (it may
 * hold a RocksDB snapshot that otherwise pins SST files).
 */
public interface ArchiveTemporalReadView extends AutoCloseable {

  /** As {@link ArchiveTemporalStore#getAsOf}, but against this frozen view. */
  Optional<DomainValue> getAsOf(ArchiveDomain domain, byte[] canonicalKey, long txNum);

  /** Conservative number of backend operations used by one {@link #getAsOf} call. */
  default long getAsOfBackendReadCost() {
    return 1L;
  }

  /** As {@link ArchiveTemporalStore#latest}, but against this frozen view. */
  Optional<DomainValue> latest(ArchiveDomain domain, byte[] canonicalKey);

  /**
   * Returns known canonical keys whose length is exactly {@code canonicalKeyLength} and whose bytes
   * start with {@code canonicalPrefix}. Persistent implementations must cross-check mutable latest
   * membership against immutable first-observation evidence in the same frozen snapshot.
   */
  default List<byte[]> scanKnownCanonicalKeys(ArchiveDomain domain,
      int canonicalKeyLength, byte[] canonicalPrefix) {
    throw new ArchiveException("archive temporal canonical-key scan is unsupported");
  }

  @Override
  void close();

  /**
   * A non-isolated view that delegates straight to a live store; {@code close()} is a no-op. This
   * is retained for internal callers and tests that already own the required consistency scope;
   * public historical queries use an isolated snapshot.
   */
  static ArchiveTemporalReadView passThrough(ArchiveTemporalStore store) {
    return new ArchiveTemporalReadView() {
      @Override
      public Optional<DomainValue> getAsOf(ArchiveDomain domain, byte[] canonicalKey, long txNum) {
        return store.getAsOf(domain, canonicalKey, txNum);
      }

      @Override
      public long getAsOfBackendReadCost() {
        return store.getAsOfBackendReadCost();
      }

      @Override
      public Optional<DomainValue> latest(ArchiveDomain domain, byte[] canonicalKey) {
        return store.latest(domain, canonicalKey);
      }

      @Override
      public List<byte[]> scanKnownCanonicalKeys(ArchiveDomain domain,
          int canonicalKeyLength, byte[] canonicalPrefix) {
        return store.scanKnownCanonicalKeys(domain, canonicalKeyLength, canonicalPrefix);
      }

      @Override
      public void close() {
      }
    };
  }
}
