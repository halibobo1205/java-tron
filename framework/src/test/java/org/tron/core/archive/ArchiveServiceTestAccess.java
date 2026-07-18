package org.tron.core.archive;

import org.tron.core.archive.temporal.ArchiveTemporalStore;

/** Test-only access to package-private archive service collaborators. */
public final class ArchiveServiceTestAccess {

  private ArchiveServiceTestAccess() {
  }

  public static ArchiveTemporalStore temporalStore(DefaultArchiveService service) {
    return service.getTemporalStoreForTesting();
  }
}
