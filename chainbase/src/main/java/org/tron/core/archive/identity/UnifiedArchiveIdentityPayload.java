package org.tron.core.archive.identity;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import org.tron.common.math.StrictMathWrapper;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.ArchivePersistentStateCorruptionException;
import org.tron.core.archive.UnifiedArchiveInFlightStore;
import org.tron.core.archive.domain.ArchiveDomainCatalog;
import org.tron.core.archive.txnum.UnifiedArchiveTxNumIndex;
import org.tron.core.archive.unified.UnifiedArchiveDb;

/** Crash-resumable initializer and verifier for an empty UNIFIED_V1 archive payload. */
public final class UnifiedArchiveIdentityPayload implements ArchiveIdentityPayload {

  public static final String DATABASE_DIRECTORY = "unified";

  private final ArchiveDomainCatalog catalog;
  private final byte[] schemaChecksum;

  public UnifiedArchiveIdentityPayload(ArchiveDomainCatalog catalog, byte[] schemaChecksum) {
    if (catalog == null) {
      throw new NullPointerException("catalog");
    }
    this.catalog = catalog;
    this.schemaChecksum = Arrays.copyOf(schemaChecksum, schemaChecksum.length);
  }

  public static Path databasePath(Path archiveRoot) {
    return archiveRoot.resolve(DATABASE_DIRECTORY);
  }

  @Override
  public void bindAndSync(Path archiveRoot, ArchiveIdentity identity) throws IOException {
    requireEmptyIdentity(identity);
    Path databasePath = databasePath(archiveRoot);
    try (UnifiedArchiveDb db = UnifiedArchiveDb.initializeOrResumeEmpty(
        databasePath, schemaChecksum)) {
      requireEmpty(db);
    }
    ArchiveIdentityFileStore.forceDirectory(databasePath);
    ArchiveIdentityFileStore.forceDirectory(archiveRoot);
  }

  @Override
  public void verifyForActivation(Path archiveRoot, ArchiveIdentity identity) throws IOException {
    requireEmptyIdentity(identity);
    try (UnifiedArchiveDb db = UnifiedArchiveDb.open(
        databasePath(archiveRoot), schemaChecksum)) {
      requireEmpty(db);
    }
  }

  /** Reads persisted coverage without creating or upgrading the unified database. */
  static long inspectFloor(Path archiveRoot, ArchiveDomainCatalog catalog,
      byte[] schemaChecksum) {
    return inspectFloor(archiveRoot, catalog, schemaChecksum, Long.MAX_VALUE);
  }

  /** Reads persisted coverage while bounding any journal value materialized during inspection. */
  static long inspectFloor(Path archiveRoot, ArchiveDomainCatalog catalog,
      byte[] schemaChecksum, long maxEncodedBlockBytes) {
    return inspectFloor(archiveRoot, catalog, schemaChecksum,
        maxEncodedBlockBytes, Long.MAX_VALUE);
  }

  /** Reads persisted coverage while bounding journal byte and record materialization. */
  static long inspectFloor(Path archiveRoot, ArchiveDomainCatalog catalog,
      byte[] schemaChecksum, long maxEncodedBlockBytes, long maxRecordsPerBlock) {
    return inspectFloor(archiveRoot, catalog, schemaChecksum,
        maxEncodedBlockBytes, maxRecordsPerBlock, Integer.MAX_VALUE);
  }

  /** Reads persisted coverage while bounding aggregate journal materialization. */
  public static long inspectFloor(Path archiveRoot, ArchiveDomainCatalog catalog,
      byte[] schemaChecksum, long maxEncodedBlockBytes, long maxRecordsPerBlock,
      int maxBlocks) {
    return inspectFloor(archiveRoot, catalog, schemaChecksum,
        maxEncodedBlockBytes, maxRecordsPerBlock, maxBlocks, false);
  }

  /**
   * Inspects an already authenticated payload and marks persistent corruption on the same DB
   * handle that detected it, before that handle is closed.
   */
  public static long inspectAuthenticatedFloor(Path archiveRoot, ArchiveDomainCatalog catalog,
      byte[] schemaChecksum, long maxEncodedBlockBytes, long maxRecordsPerBlock,
      int maxBlocks) {
    return inspectFloor(archiveRoot, catalog, schemaChecksum,
        maxEncodedBlockBytes, maxRecordsPerBlock, maxBlocks, true);
  }

  private static long inspectFloor(Path archiveRoot, ArchiveDomainCatalog catalog,
      byte[] schemaChecksum, long maxEncodedBlockBytes, long maxRecordsPerBlock,
      int maxBlocks, boolean markPersistentCorruption) {
    UnifiedArchiveDb db = UnifiedArchiveDb.open(databasePath(archiveRoot), schemaChecksum);
    UnifiedArchiveTxNumIndex index = null;
    Throwable bodyFailure = null;
    try {
      index = new UnifiedArchiveTxNumIndex(db, schemaChecksum, false, true);
      long persistedFloor = index.getFirstArchivedBlock();
      if (persistedFloor >= 0) {
        return persistedFloor;
      }
      UnifiedArchiveInFlightStore inFlight =
          new UnifiedArchiveInFlightStore(
              db, catalog, maxEncodedBlockBytes, maxRecordsPerBlock, maxBlocks);
      long[] firstJournal = {Long.MAX_VALUE};
      inFlight.forEachBlock(block -> firstJournal[0] = StrictMathWrapper.min(
          firstJournal[0], block.getRange().getBlockNum()));
      return firstJournal[0] == Long.MAX_VALUE ? 0L : firstJournal[0];
    } catch (RuntimeException | Error e) {
      bodyFailure = e;
      if (markPersistentCorruption
          && e instanceof ArchivePersistentStateCorruptionException) {
        markRepairRequired(db, (ArchivePersistentStateCorruptionException) e);
      }
      throw e;
    } finally {
      Throwable closeFailure = close(index == null ? db : index, bodyFailure);
      if (bodyFailure == null && closeFailure != null) {
        rethrowCloseFailure(closeFailure);
      }
    }
  }

  private static void markRepairRequired(UnifiedArchiveDb db,
      ArchivePersistentStateCorruptionException failure) {
    try {
      String reason = failure.getMessage() == null
          ? failure.getClass().getSimpleName() : failure.getMessage();
      reason = UnifiedArchiveTxNumIndex.boundRepairReason(reason);
      UnifiedArchiveTxNumIndex.markRepairRequired(db, reason);
    } catch (RuntimeException | Error markerFailure) {
      if (markerFailure != failure) {
        try {
          failure.addSuppressed(markerFailure);
        } catch (Throwable ignored) {
          // Preserve the corruption as the primary startup failure.
        }
      }
    }
  }

  private static void requireEmptyIdentity(ArchiveIdentity identity)
      throws ArchiveIdentityException {
    if (identity.getFloor() != 0L) {
      throw new ArchiveIdentityException(
          "new UNIFIED_V1 archive identity floor must be zero");
    }
  }

  private static void requireEmpty(UnifiedArchiveDb db) throws ArchiveIdentityException {
    if (db.hasArchiveData()) {
      throw new ArchiveIdentityException(
          "new UNIFIED_V1 archive identity payload must be empty before activation");
    }
  }

  private static Throwable close(AutoCloseable resource, Throwable failure) {
    try {
      resource.close();
    } catch (Throwable closeFailure) {
      if (!(closeFailure instanceof RuntimeException) && !(closeFailure instanceof Error)) {
        closeFailure =
            new ArchiveException("UNIFIED_V1 identity payload close failed", closeFailure);
      }
      if (failure == null) {
        return closeFailure;
      }
      if (failure != closeFailure) {
        try {
          failure.addSuppressed(closeFailure);
        } catch (Throwable ignored) {
          // Preserve the inspection failure even if suppression itself is unavailable.
        }
      }
    }
    return failure;
  }

  private static void rethrowCloseFailure(Throwable failure) {
    if (failure instanceof RuntimeException) {
      throw (RuntimeException) failure;
    }
    if (failure instanceof Error) {
      throw (Error) failure;
    }
    throw new ArchiveException("UNIFIED_V1 identity payload close failed", failure);
  }
}
