package org.tron.core.archive.identity;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.RocksDbArchiveInFlightStore;
import org.tron.core.archive.domain.ArchiveDomainCatalog;
import org.tron.core.archive.temporal.RocksDbArchiveTemporalStore;
import org.tron.core.archive.txnum.PersistentArchiveTxNumIndex;
import org.tron.core.archive.txnum.RocksDbArchiveBlockRangeStore;

/** Crash-resumable initializer for an empty LEGACY_V1 archive identity. */
public final class LegacyArchiveIdentityPayload implements ArchiveIdentityPayload {

  private final ArchiveDomainCatalog catalog;
  private final byte[] schemaChecksum;
  private final boolean requireEmpty;

  public LegacyArchiveIdentityPayload(ArchiveDomainCatalog catalog, byte[] schemaChecksum) {
    this(catalog, schemaChecksum, true);
  }

  private LegacyArchiveIdentityPayload(ArchiveDomainCatalog catalog, byte[] schemaChecksum,
      boolean requireEmpty) {
    this.catalog = catalog;
    this.schemaChecksum = Arrays.copyOf(schemaChecksum, schemaChecksum.length);
    this.requireEmpty = requireEmpty;
  }

  /** Full-scrub payload used only by explicit pre-identity legacy adoption. */
  public static LegacyArchiveIdentityPayload forAdoption(ArchiveDomainCatalog catalog,
      byte[] schemaChecksum) {
    return new LegacyArchiveIdentityPayload(catalog, schemaChecksum, false);
  }

  @Override
  public void bindAndSync(Path archiveRoot, ArchiveIdentity identity) throws IOException {
    if (requireEmpty) {
      verifyLegacyLayout(archiveRoot, identity);
    } else {
      // Adoption is fully scrubbed before the claim and again immediately before ACTIVE. Binding
      // only proves the three physical stores remain present, avoiding a third full keyspace scan.
      requireExistingLegacyStores(archiveRoot);
    }
    ArchiveIdentityFileStore.forceDirectory(archiveRoot);
  }

  @Override
  public void verifyForActivation(Path archiveRoot, ArchiveIdentity identity) throws IOException {
    verifyLegacyLayout(archiveRoot, identity);
  }

  /** Reads the persisted coverage floor without creating or upgrading any legacy store. */
  public static long inspectFloor(Path archiveRoot, ArchiveDomainCatalog catalog,
      byte[] schemaChecksum) throws IOException {
    requireExistingStore(archiveRoot.resolve("index"));
    requireExistingStore(archiveRoot.resolve("inflight"));
    RocksDbArchiveBlockRangeStore ranges = null;
    PersistentArchiveTxNumIndex index = null;
    RocksDbArchiveInFlightStore inFlight = null;
    RuntimeException bodyFailure = null;
    try {
      ranges = new RocksDbArchiveBlockRangeStore(
          archiveRoot.resolve("index").toString(), false, false, true);
      index = new PersistentArchiveTxNumIndex(ranges, schemaChecksum, false, true);
      long persistedFloor = index.getFirstArchivedBlock();
      if (persistedFloor >= 0) {
        return persistedFloor;
      }
      inFlight = new RocksDbArchiveInFlightStore(
          archiveRoot.resolve("inflight").toString(), catalog, false, false, true);
      long[] firstJournal = {Long.MAX_VALUE};
      inFlight.forEachBlock(block -> firstJournal[0] = Math.min(
          firstJournal[0], block.getRange().getBlockNum()));
      return firstJournal[0] == Long.MAX_VALUE ? 0L : firstJournal[0];
    } catch (RuntimeException e) {
      bodyFailure = e;
      throw e;
    } finally {
      RuntimeException closeFailure = close(inFlight, bodyFailure);
      closeFailure = close(index, closeFailure);
      if (index == null) {
        closeFailure = close(ranges, closeFailure);
      }
      if (bodyFailure == null && closeFailure != null) {
        throw closeFailure;
      }
    }
  }

  private void verifyLegacyLayout(Path archiveRoot, ArchiveIdentity identity) throws IOException {
    if (!requireEmpty) {
      requireExistingLegacyStores(archiveRoot);
    }
    RocksDbArchiveTemporalStore temporal = null;
    RocksDbArchiveInFlightStore inFlight = null;
    RocksDbArchiveBlockRangeStore ranges = null;
    PersistentArchiveTxNumIndex index = null;
    RuntimeException bodyFailure = null;
    try {
      temporal = new RocksDbArchiveTemporalStore(
          archiveRoot.resolve("temporal").toString(), true, requireEmpty, !requireEmpty);
      inFlight = new RocksDbArchiveInFlightStore(
          archiveRoot.resolve("inflight").toString(), catalog, true, requireEmpty, !requireEmpty);
      ranges = new RocksDbArchiveBlockRangeStore(
          archiveRoot.resolve("index").toString(), true, requireEmpty, !requireEmpty);
      index = new PersistentArchiveTxNumIndex(ranges, schemaChecksum, true, !requireEmpty);
      boolean[] hasInFlight = {false};
      long[] firstInFlight = {Long.MAX_VALUE};
      inFlight.forEachBlock(block -> {
        hasInFlight[0] = true;
        firstInFlight[0] = Math.min(firstInFlight[0], block.getRange().getBlockNum());
      });
      if (requireEmpty && (temporal.hasDataBeyondManifest() || hasInFlight[0]
          || index.getLastArchivedBlock() >= 0)) {
        throw new ArchiveIdentityException(
            "new archive identity payload must be empty before activation");
      }
      if (!requireEmpty) {
        RocksDbArchiveBlockRangeStore validatedRanges = ranges;
        RocksDbArchiveTemporalStore validatedTemporal = temporal;
        if (!ranges.getLastRange().isPresent() && !hasInFlight[0]
            && !temporal.hasDataBeyondManifest()) {
          throw new ArchiveIdentityException(
              "legacy archive identity payload must contain archived state");
        }
        if (!ranges.getLastRange().isPresent() && temporal.hasDataBeyondManifest()) {
          throw new ArchiveIdentityException(
              "archive temporal store is non-empty but block-range index is empty");
        }
        temporal.validateStartupTail(ranges.getLastRange());
        temporal.validateCommitMarkersCovered(
            blockNum -> validatedRanges.getRange(blockNum).isPresent());
        ranges.validateCommittedRanges(validatedTemporal::validateCommittedBlock);
        temporal.validateTxNumsCovered(validatedRanges::hasCommittedTxNum);
        temporal.validateDomainRows(catalog);
        long actualFloor = index.getFirstArchivedBlock();
        if (actualFloor < 0 && firstInFlight[0] != Long.MAX_VALUE) {
          actualFloor = firstInFlight[0];
        }
        if (actualFloor < 0 || actualFloor != identity.getFloor()) {
          throw new ArchiveIdentityException(
              "legacy archive identity floor does not match persisted coverage");
        }
      }
    } catch (RuntimeException e) {
      bodyFailure = e;
      throw e;
    } finally {
      RuntimeException closeFailure = close(index, bodyFailure);
      if (index == null) {
        closeFailure = close(ranges, closeFailure);
      }
      closeFailure = close(inFlight, closeFailure);
      closeFailure = close(temporal, closeFailure);
      if (bodyFailure == null && closeFailure != null) {
        throw closeFailure;
      }
    }
  }

  private static void requireExistingStore(Path storePath) throws IOException {
    if (!Files.isDirectory(storePath)) {
      throw new ArchiveIdentityException(
          "legacy archive identity payload is missing store " + storePath);
    }
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(storePath)) {
      if (!entries.iterator().hasNext()) {
        throw new ArchiveIdentityException(
            "legacy archive identity payload store is empty " + storePath);
      }
    }
  }

  private static void requireExistingLegacyStores(Path archiveRoot) throws IOException {
    requireExistingStore(archiveRoot.resolve("temporal"));
    requireExistingStore(archiveRoot.resolve("index"));
    requireExistingStore(archiveRoot.resolve("inflight"));
  }

  private static RuntimeException close(AutoCloseable resource, RuntimeException failure) {
    if (resource == null) {
      return failure;
    }
    try {
      resource.close();
    } catch (Exception e) {
      RuntimeException closeFailure = e instanceof RuntimeException
          ? (RuntimeException) e : new ArchiveException("archive identity payload close failed", e);
      if (failure == null) {
        return closeFailure;
      }
      failure.addSuppressed(closeFailure);
    }
    return failure;
  }
}
