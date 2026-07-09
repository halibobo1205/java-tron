package org.tron.core.archive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Optional;
import org.tron.common.arch.Arch;
import org.tron.core.archive.domain.ArchiveDomainCatalog;
import org.tron.core.archive.domain.ArchiveDomainRegistry;
import org.tron.core.archive.domain.ArchiveSchemaChecksum;
import org.tron.core.archive.domain.DefaultArchiveDomainCatalog;
import org.tron.core.archive.domain.DefaultArchiveDomainRegistry;
import org.tron.core.archive.temporal.InMemoryArchiveTemporalStore;
import org.tron.core.archive.temporal.RocksDbArchiveTemporalStore;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.archive.txnum.ArchiveTxPosition;
import org.tron.core.archive.txnum.InMemoryArchiveTxNumIndex;
import org.tron.core.archive.txnum.PersistentArchiveTxNumIndex;
import org.tron.core.archive.txnum.RocksDbArchiveBlockRangeStore;
import org.tron.core.config.args.StorageConfig;

/**
 * Builds the {@link ArchiveService} for the current configuration. Disabled config returns the
 * shared {@link NoopArchiveService}; enabled config returns a {@link DefaultArchiveService}.
 *
 * <p>When an archive directory is supplied and {@code storage.archive.temporal.enable} is set, the
 * service is backed by persistent RocksDB stores under that directory ({@code temporal/} for the
 * state history, {@code index/} for the reader-visible block-to-txNum index, {@code inflight/} for
 * committed but not-yet-solidified blocks), all surviving restart. Otherwise in-memory stores are
 * used (tests / non-persistent runs).
 */
public final class ArchiveServiceFactory {

  private static final String SUPPORTED_COVERAGE = "TVM_STATE_ONLY";

  private ArchiveServiceFactory() {
  }

  public static ArchiveService create(StorageConfig.ArchiveConfig config) {
    return create(config, null);
  }

  public static ArchiveService create(StorageConfig.ArchiveConfig config, String archiveDir) {
    if (config == null || !config.isEnable()) {
      return NoopArchiveService.INSTANCE;
    }
    validateSupportedConfig(config);
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
    ArchiveDomainRegistry registry = new DefaultArchiveDomainRegistry();
    ArchiveDomainCatalog catalog = new DefaultArchiveDomainCatalog();
    byte[] schemaChecksum = ArchiveSchemaChecksum.of(registry, catalog);
    if (archiveDir != null && config.getTemporal().isEnable()) {
      Path archivePath = Paths.get(archiveDir);
      try {
        Files.createDirectories(archivePath);
      } catch (IOException e) {
        throw new ArchiveException("failed to create archive directory " + archiveDir, e);
      }
      RocksDbArchiveTemporalStore temporalStore = null;
      RocksDbArchiveInFlightStore inFlightStore = null;
      RocksDbArchiveBlockRangeStore blockRangeStore = null;
      PersistentArchiveTxNumIndex txNumIndex = null;
      try {
        temporalStore = new RocksDbArchiveTemporalStore(
            archivePath.resolve("temporal").toString());
        inFlightStore = new RocksDbArchiveInFlightStore(
            archivePath.resolve("inflight").toString(), catalog);
        blockRangeStore =
            new RocksDbArchiveBlockRangeStore(archivePath.resolve("index").toString());
        txNumIndex = new PersistentArchiveTxNumIndex(blockRangeStore, schemaChecksum);
        if (!blockRangeStore.getLastRange().isPresent()
            && temporalStore.hasDataBeyondManifest()) {
          throw new ArchiveException(
              "archive temporal store is non-empty but block-range index is empty");
        }
        recoverPublishedInFlightBlocks(inFlightStore, blockRangeStore, temporalStore);
        RocksDbArchiveBlockRangeStore committedIndex = blockRangeStore;
        temporalStore.validateCommitMarkersCovered(
            blockNum -> committedIndex.getRange(blockNum).isPresent());
        blockRangeStore.validateCommittedRanges(temporalStore::validateCommittedBlock);
        temporalStore.validateTxNumsCovered(committedIndex::hasCommittedTxNum);
        temporalStore.validateDomainRows(catalog);
        return new DefaultArchiveService(true, txNumIndex,
            ArchiveExecutionContextHolder.get(), temporalStore, inFlightStore, registry, catalog);
      } catch (RuntimeException e) {
        closeOnFailure(inFlightStore, e);
        closeOnFailure(temporalStore, e);
        if (txNumIndex == null) {
          closeOnFailure(blockRangeStore, e);
        } else {
          closeOnFailure(txNumIndex, e);
        }
        throw e;
      }
    }
    return new DefaultArchiveService(true, new InMemoryArchiveTxNumIndex(),
        ArchiveExecutionContextHolder.get(), new InMemoryArchiveTemporalStore(),
        registry, catalog);
  }

  private static void validateSupportedConfig(StorageConfig.ArchiveConfig config) {
    if (!SUPPORTED_COVERAGE.equals(config.getCoverage())) {
      throw new ArchiveException(
          "storage.archive.coverage supports only " + SUPPORTED_COVERAGE + " in P0");
    }
    if (!config.isWarnUnclassifiedStoreWrites()) {
      throw new ArchiveException(
          "storage.archive.warnUnclassifiedStoreWrites cannot be false in P0");
    }
  }

  private static void recoverPublishedInFlightBlocks(RocksDbArchiveInFlightStore inFlightStore,
      RocksDbArchiveBlockRangeStore blockRangeStore, RocksDbArchiveTemporalStore temporalStore) {
    for (ArchiveInFlightBlock block : inFlightStore.loadBlocks()) {
      ArchiveBlockRange journalRange = block.getRange();
      Optional<ArchiveBlockRange> committed = blockRangeStore.getRange(journalRange.getBlockNum());
      if (!committed.isPresent()) {
        continue;
      }
      ArchiveBlockRange committedRange = committed.get();
      if (!sameRange(journalRange, committedRange)) {
        throw new ArchiveException("archive in-flight block does not match published range "
            + journalRange.getBlockNum());
      }
      validateJournalPositionsMatchIndex(block, blockRangeStore);
      try {
        temporalStore.validateCommittedBlock(committedRange);
      } catch (RuntimeException missingTemporalCommit) {
        if (temporalStore.hasCommitMarker(committedRange.getBlockNum())
            || temporalStore.hasRowsInRange(committedRange)) {
          throw missingTemporalCommit;
        }
        temporalStore.validateLatestRowsAnchored();
        temporalStore.putBlockChanges(committedRange, block.getRecords());
        temporalStore.validateCommittedBlock(committedRange);
      }
      deletePublishedInFlightBlock(inFlightStore, journalRange.getBlockNum());
    }
  }

  private static void deletePublishedInFlightBlock(RocksDbArchiveInFlightStore inFlightStore,
      long blockNum) {
    try {
      inFlightStore.deleteBlock(blockNum);
    } catch (RuntimeException deleteFailure) {
      // The published index and temporal marker are already durable. Keep the journal row so a
      // later startup can retry cleanup instead of refusing to start a consistent archive.
    }
  }

  private static void validateJournalPositionsMatchIndex(ArchiveInFlightBlock block,
      RocksDbArchiveBlockRangeStore blockRangeStore) {
    for (ArchiveTxPosition journalPosition : block.getPositions()) {
      ArchiveTxPosition committedPosition = blockRangeStore.getPosition(journalPosition.getTxNum())
          .orElseThrow(() -> new ArchiveException(
              "archive in-flight position is missing from published index txNum "
                  + journalPosition.getTxNum()));
      if (!samePosition(journalPosition, committedPosition)) {
        throw new ArchiveException("archive in-flight position does not match published index "
            + journalPosition.getTxNum());
      }
    }
  }

  private static boolean samePosition(ArchiveTxPosition left, ArchiveTxPosition right) {
    return left.getTxNum() == right.getTxNum()
        && left.getBlockNum() == right.getBlockNum()
        && left.getPhase() == right.getPhase()
        && left.getSource() == right.getSource()
        && left.getTxIndex() == right.getTxIndex()
        && Arrays.equals(left.getTxId(), right.getTxId())
        && (left.getBlockHash().length == 0 || Arrays.equals(left.getBlockHash(),
            right.getBlockHash()));
  }

  private static boolean sameRange(ArchiveBlockRange left, ArchiveBlockRange right) {
    return left.getBlockNum() == right.getBlockNum()
        && left.getFirstTxNum() == right.getFirstTxNum()
        && left.getLastTxNum() == right.getLastTxNum()
        && left.getPrepareTxNum() == right.getPrepareTxNum()
        && left.getFinalizeTxNum() == right.getFinalizeTxNum()
        && left.getUserTxCount() == right.getUserTxCount()
        && left.getSource() == right.getSource()
        && Arrays.equals(left.getBlockHash(), right.getBlockHash())
        && Arrays.equals(left.getSchemaChecksum(), right.getSchemaChecksum());
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
