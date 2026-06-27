package org.tron.core.archive.reader;

import org.tron.core.archive.domain.ArchiveDomainCatalog;
import org.tron.core.archive.temporal.ArchiveTemporalStore;

/**
 * Default {@link ArchiveStateReaderFactory}: each {@link #open} returns a lightweight
 * {@link DefaultArchiveStateReader} view over the shared temporal store + catalog at that point.
 */
public final class DefaultArchiveStateReaderFactory implements ArchiveStateReaderFactory {

  private final ArchiveTemporalStore temporalStore;
  private final ArchiveDomainCatalog catalog;

  public DefaultArchiveStateReaderFactory(ArchiveTemporalStore temporalStore,
      ArchiveDomainCatalog catalog) {
    this.temporalStore = temporalStore;
    this.catalog = catalog;
  }

  @Override
  public ArchiveStateReader open(ArchiveStatePoint point) throws ArchiveReaderException {
    if (temporalStore == null) {
      throw new ArchiveReaderException(ArchiveReaderException.Reason.ARCHIVE_DISABLED,
          "archive temporal store is not available");
    }
    if (point == null) {
      throw new ArchiveReaderException(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE,
          "no resolved archive state point");
    }
    return new DefaultArchiveStateReader(temporalStore, catalog, point);
  }
}
