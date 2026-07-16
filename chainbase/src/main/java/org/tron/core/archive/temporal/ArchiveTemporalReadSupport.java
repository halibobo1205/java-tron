package org.tron.core.archive.temporal;

import java.util.Optional;
import org.rocksdb.RocksIterator;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.ArchiveRocksIterators;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;

/** Shared inclusive-after lookup semantics for temporal stores and snapshots. */
final class ArchiveTemporalReadSupport {

  private ArchiveTemporalReadSupport() {
  }

  static Optional<DomainValue> getAsOf(ArchiveDomain domain, byte[] canonicalKey, long txNum,
      RocksIterator history, LatestLookup latestLookup, String operation) {
    if (txNum < 0) {
      throw new ArchiveException("archive temporal txNum must be non-negative");
    }
    if (txNum != Long.MAX_VALUE) {
      byte[] prefix = ArchiveTemporalCodec.historyPrefix(domain, canonicalKey);
      history.seek(ArchiveTemporalCodec.historyKey(domain, canonicalKey, txNum + 1L));
      ArchiveRocksIterators.requireOk(history, operation + ": history seek");
      if (history.isValid() && ArchiveTemporalCodec.startsWith(history.key(), prefix)) {
        return Optional.of(ArchiveTemporalCodec.decodeValue(history.value()));
      }
    }
    byte[] latest = latestLookup.get(ArchiveTemporalCodec.latestKey(domain, canonicalKey));
    return latest == null ? Optional.empty()
        : Optional.of(ArchiveTemporalCodec.decodeValue(latest));
  }

  @FunctionalInterface
  interface LatestLookup {

    byte[] get(byte[] key);
  }
}
