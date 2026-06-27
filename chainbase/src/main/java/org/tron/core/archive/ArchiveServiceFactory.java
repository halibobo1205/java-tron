package org.tron.core.archive;

import java.nio.file.Paths;
import org.tron.core.archive.temporal.ArchiveTemporalStore;
import org.tron.core.archive.temporal.InMemoryArchiveTemporalStore;
import org.tron.core.archive.temporal.RocksDbArchiveTemporalStore;
import org.tron.core.archive.txnum.PersistentArchiveTxNumIndex;
import org.tron.core.archive.txnum.RocksDbArchiveBlockRangeStore;
import org.tron.core.config.args.StorageConfig;

/**
 * Builds the {@link ArchiveService} for the current configuration. Disabled config returns the
 * shared {@link NoopArchiveService}; enabled config returns a {@link DefaultArchiveService}.
 *
 * <p>When an archive directory is supplied and {@code storage.archive.temporal.enable} is set, the
 * service is backed by persistent RocksDB stores under that directory ({@code temporal/} for the
 * state history, {@code index/} for the block-to-txNum index), both surviving restart and running
 * on the RocksDB shipped for either architecture. Otherwise in-memory stores are used (tests /
 * non-persistent runs).
 */
public final class ArchiveServiceFactory {

  private ArchiveServiceFactory() {
  }

  public static ArchiveService create(StorageConfig.ArchiveConfig config) {
    return create(config, null);
  }

  public static ArchiveService create(StorageConfig.ArchiveConfig config, String archiveDir) {
    if (config == null || !config.isEnable()) {
      return NoopArchiveService.INSTANCE;
    }
    if (archiveDir != null && config.getTemporal().isEnable()) {
      ArchiveTemporalStore temporalStore = new RocksDbArchiveTemporalStore(
          Paths.get(archiveDir, "temporal").toString());
      PersistentArchiveTxNumIndex txNumIndex = new PersistentArchiveTxNumIndex(
          new RocksDbArchiveBlockRangeStore(Paths.get(archiveDir, "index").toString()));
      return new DefaultArchiveService(true, txNumIndex,
          ArchiveExecutionContextHolder.get(), temporalStore);
    }
    return new DefaultArchiveService(true, new InMemoryArchiveTemporalStore());
  }
}
