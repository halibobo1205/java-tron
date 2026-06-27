package org.tron.core.archive;

import org.tron.core.config.args.StorageConfig;

/**
 * Builds the {@link ArchiveService} for the current configuration.
 *
 * <p>L1 deliberately refuses real enablement: it parses {@code storage.archive.enable = true}
 * but throws instead of returning a half-built service, so the node never runs in a
 * "looks enabled but silently no-op" state. Later landings (L2+) relax this once a real
 * implementation exists.
 */
public final class ArchiveServiceFactory {

  private ArchiveServiceFactory() {
  }

  public static ArchiveService create(StorageConfig.ArchiveConfig config) {
    if (config == null || !config.isEnable()) {
      return NoopArchiveService.INSTANCE;
    }
    throw new ArchiveException(
        "storage.archive.enable=true requires an archive implementation from a later landing");
  }
}
