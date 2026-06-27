package org.tron.core.archive;

import org.tron.core.archive.temporal.ArchiveTemporalStore;
import org.tron.core.archive.temporal.InMemoryArchiveTemporalStore;
import org.tron.core.archive.temporal.RocksDbArchiveTemporalStore;
import org.tron.core.config.args.StorageConfig;

/**
 * Builds the {@link ArchiveService} for the current configuration. Disabled config returns the
 * shared {@link NoopArchiveService}; enabled config returns a {@link DefaultArchiveService} backed
 * by a temporal store.
 *
 * <p>The persistent temporal store ({@link RocksDbArchiveTemporalStore}) uses a single column
 * family + key prefixes, so it runs on the RocksDB shipped for both architectures. It is selected
 * when a db path is supplied and {@code storage.archive.temporal.enable} is set; otherwise an
 * in-memory temporal store is used (tests / non-persistent runs).
 */
public final class ArchiveServiceFactory {

  private ArchiveServiceFactory() {
  }

  public static ArchiveService create(StorageConfig.ArchiveConfig config) {
    return create(config, null);
  }

  public static ArchiveService create(StorageConfig.ArchiveConfig config, String archiveDbPath) {
    if (config == null || !config.isEnable()) {
      return NoopArchiveService.INSTANCE;
    }
    boolean persistent = archiveDbPath != null && config.getTemporal().isEnable();
    ArchiveTemporalStore temporalStore = persistent
        ? new RocksDbArchiveTemporalStore(archiveDbPath)
        : new InMemoryArchiveTemporalStore();
    return new DefaultArchiveService(true, temporalStore);
  }
}
