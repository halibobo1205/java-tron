package org.tron.core.archive.temporal;

import java.util.Optional;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;

/**
 * An isolated, point-in-time read view over an {@link ArchiveTemporalStore}, exposing exactly the
 * two methods the archive state reader needs. Opened via {@code openReadView()} under the archive
 * read lock, it lets a historical {@code eth_call} / {@code debug_trace} run its VM against a
 * frozen snapshot after the lock is released, so a long call no longer stalls block commit. The
 * view MUST be closed (it may hold a RocksDB snapshot that otherwise pins SST files).
 */
public interface ArchiveTemporalReadView extends AutoCloseable {

  /** As {@link ArchiveTemporalStore#getAsOf}, but against this frozen view. */
  Optional<DomainValue> getAsOf(ArchiveDomain domain, byte[] canonicalKey, long txNum);

  /** As {@link ArchiveTemporalStore#latest}, but against this frozen view. */
  Optional<DomainValue> latest(ArchiveDomain domain, byte[] canonicalKey);

  @Override
  void close();

  /**
   * A non-isolated view that delegates straight to a live store; {@code close()} is a no-op. Used
   * on the mid-chain read path, where the archive read lock (held for the reader's whole lifetime)
   * provides the isolation instead of a snapshot.
   */
  static ArchiveTemporalReadView passThrough(ArchiveTemporalStore store) {
    return new ArchiveTemporalReadView() {
      @Override
      public Optional<DomainValue> getAsOf(ArchiveDomain domain, byte[] canonicalKey, long txNum) {
        return store.getAsOf(domain, canonicalKey, txNum);
      }

      @Override
      public Optional<DomainValue> latest(ArchiveDomain domain, byte[] canonicalKey) {
        return store.latest(domain, canonicalKey);
      }

      @Override
      public void close() {
      }
    };
  }
}
