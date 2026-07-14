package org.tron.core.archive.identity;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import org.tron.core.archive.ArchiveException;
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
  public static long inspectFloor(Path archiveRoot, ArchiveDomainCatalog catalog,
      byte[] schemaChecksum) {
    UnifiedArchiveDb db = UnifiedArchiveDb.open(databasePath(archiveRoot), schemaChecksum);
    UnifiedArchiveTxNumIndex index = null;
    RuntimeException bodyFailure = null;
    try {
      index = new UnifiedArchiveTxNumIndex(db, schemaChecksum, false, true);
      long persistedFloor = index.getFirstArchivedBlock();
      if (persistedFloor >= 0) {
        return persistedFloor;
      }
      UnifiedArchiveInFlightStore inFlight = new UnifiedArchiveInFlightStore(db, catalog);
      long[] firstJournal = {Long.MAX_VALUE};
      inFlight.forEachBlock(block -> firstJournal[0] = Math.min(
          firstJournal[0], block.getRange().getBlockNum()));
      return firstJournal[0] == Long.MAX_VALUE ? 0L : firstJournal[0];
    } catch (RuntimeException e) {
      bodyFailure = e;
      throw e;
    } finally {
      RuntimeException closeFailure = close(index == null ? db : index, bodyFailure);
      if (bodyFailure == null && closeFailure != null) {
        throw closeFailure;
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

  private static RuntimeException close(AutoCloseable resource, RuntimeException failure) {
    try {
      resource.close();
    } catch (Exception e) {
      RuntimeException closeFailure = e instanceof RuntimeException
          ? (RuntimeException) e
          : new ArchiveException("UNIFIED_V1 identity payload close failed", e);
      if (failure == null) {
        return closeFailure;
      }
      failure.addSuppressed(closeFailure);
    }
    return failure;
  }
}
