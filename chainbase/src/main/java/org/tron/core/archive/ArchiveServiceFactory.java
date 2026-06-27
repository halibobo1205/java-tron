package org.tron.core.archive;

import org.tron.core.config.args.StorageConfig;

/**
 * Builds the {@link ArchiveService} for the current configuration. Disabled config returns the
 * shared {@link NoopArchiveService}; enabled config returns a {@link DefaultArchiveService}.
 *
 * <p>At L2 the enabled service only allocates the in-memory txNum coordinate (no persistence).
 * The "x86 archive disabled in P0" rule is enforced at L5, where the RocksDB-backed persistence
 * module is absent from the x86 build and enablement is rejected there.
 */
public final class ArchiveServiceFactory {

  private ArchiveServiceFactory() {
  }

  public static ArchiveService create(StorageConfig.ArchiveConfig config) {
    if (config == null || !config.isEnable()) {
      return NoopArchiveService.INSTANCE;
    }
    return new DefaultArchiveService(true);
  }
}
