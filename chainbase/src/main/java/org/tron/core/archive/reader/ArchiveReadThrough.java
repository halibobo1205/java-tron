package org.tron.core.archive.reader;

import java.util.Optional;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;

/**
 * Controlled fallback for archive keys that have no temporal row at a covered point. This is used
 * for mid-chain archive activation: keys that existed before archive coverage and never changed
 * during coverage have no history row, so the service can supply a guarded live-store baseline.
 */
@FunctionalInterface
public interface ArchiveReadThrough {

  ArchiveReadThrough NONE = (domain, canonicalKey, point) -> Optional.empty();

  Optional<DomainValue> read(ArchiveDomain domain, byte[] canonicalKey,
      ArchiveStatePoint point) throws ArchiveReaderException;
}
