package org.tron.core.archive;

import java.nio.file.Paths;
import org.tron.common.arch.Arch;
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
    if (!Arch.isArm64()) {
      throw new ArchiveException("archive is not supported on this build/platform");
    }
    if (config.getTxnum() == null || !config.getTxnum().isEnable()) {
      throw new ArchiveException(
          "storage.archive.txnum.enable must be true when archive is enabled");
    }
    if (config.getTemporal() == null || !config.getTemporal().isEnable()) {
      throw new ArchiveException(
          "storage.archive.temporal.enable must be true when archive is enabled");
    }
    if (archiveDir != null && config.getTemporal().isEnable()) {
      RocksDbArchiveTemporalStore temporalStore = null;
      RocksDbArchiveBlockRangeStore blockRangeStore = null;
      try {
        temporalStore = new RocksDbArchiveTemporalStore(
            Paths.get(archiveDir, "temporal").toString());
        blockRangeStore =
            new RocksDbArchiveBlockRangeStore(Paths.get(archiveDir, "index").toString());
        blockRangeStore.validateNoRepairRequired();
        if (!blockRangeStore.getLastRange().isPresent()
            && temporalStore.hasDataBeyondManifest()) {
          throw new ArchiveException(
              "archive temporal store is non-empty but block-range index is empty");
        }
        RocksDbArchiveBlockRangeStore committedIndex = blockRangeStore;
        temporalStore.validateCommitMarkersCovered(
            blockNum -> committedIndex.getRange(blockNum).isPresent());
        blockRangeStore.validateCommittedRanges(temporalStore::validateCommittedBlock);
        temporalStore.validateTxNumsCovered(committedIndex::hasCommittedTxNum);
        PersistentArchiveTxNumIndex txNumIndex = new PersistentArchiveTxNumIndex(blockRangeStore);
        return new DefaultArchiveService(true, txNumIndex,
            ArchiveExecutionContextHolder.get(), temporalStore);
      } catch (RuntimeException e) {
        closeOnFailure(temporalStore, e);
        closeOnFailure(blockRangeStore, e);
        throw e;
      }
    }
    return new DefaultArchiveService(true, new InMemoryArchiveTemporalStore());
  }

  private static void closeOnFailure(AutoCloseable resource, RuntimeException failure) {
    if (resource == null) {
      return;
    }
    try {
      resource.close();
    } catch (Exception closeFailure) {
      failure.addSuppressed(closeFailure);
    }
  }
}
