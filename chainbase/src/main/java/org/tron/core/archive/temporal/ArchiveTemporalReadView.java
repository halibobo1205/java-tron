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
}
