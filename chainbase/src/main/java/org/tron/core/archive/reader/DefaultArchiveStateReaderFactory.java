package org.tron.core.archive.reader;

import org.tron.core.archive.domain.ArchiveDomainCatalog;
import org.tron.core.archive.temporal.ArchiveTemporalStore;

/**
 * Default {@link ArchiveStateReaderFactory}: each {@link #open} returns a lightweight
 * {@link DefaultArchiveStateReader} view over the shared temporal store + catalog at that point.
 */
public final class DefaultArchiveStateReaderFactory implements ArchiveStateReaderFactory {

  @FunctionalInterface
  public interface PointValidator {
    void validate(ArchiveStatePoint point) throws ArchiveReaderException;
  }

  private final ArchiveTemporalStore temporalStore;
  private final ArchiveDomainCatalog catalog;
  private final PointValidator pointValidator;

  DefaultArchiveStateReaderFactory(ArchiveTemporalStore temporalStore,
      ArchiveDomainCatalog catalog) {
    this(temporalStore, catalog, point -> {
    });
  }

  public DefaultArchiveStateReaderFactory(ArchiveTemporalStore temporalStore,
      ArchiveDomainCatalog catalog, PointValidator pointValidator) {
    this.temporalStore = temporalStore;
    this.catalog = catalog;
    this.pointValidator = pointValidator;
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
    pointValidator.validate(point);
    return new DefaultArchiveStateReader(temporalStore, catalog, point);
  }
}
