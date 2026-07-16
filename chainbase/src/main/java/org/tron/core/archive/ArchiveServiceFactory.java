package org.tron.core.archive;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;
import org.tron.common.arch.Arch;
import org.tron.common.utils.ByteArray;
import org.tron.core.ChainBaseManager;
import org.tron.core.archive.domain.ArchiveDomainCatalog;
import org.tron.core.archive.domain.ArchiveDomainRegistry;
import org.tron.core.archive.domain.ArchiveSchemaChecksum;
import org.tron.core.archive.domain.DefaultArchiveDomainCatalog;
import org.tron.core.archive.domain.DefaultArchiveDomainRegistry;
import org.tron.core.archive.identity.ArchiveIdentityClaim;
import org.tron.core.archive.identity.ArchiveIdentityException;
import org.tron.core.archive.identity.ArchiveIdentityProtocol;
import org.tron.core.archive.identity.UnifiedArchiveIdentityPayload;
import org.tron.core.archive.query.ArchiveQueryLimits;
import org.tron.core.archive.reader.ArchiveReadThrough;
import org.tron.core.archive.temporal.UnifiedArchiveTemporalStore;
import org.tron.core.archive.txnum.UnifiedArchiveTxNumIndex;
import org.tron.core.archive.unified.UnifiedArchiveDb;
import org.tron.core.capsule.utils.BlockUtil;
import org.tron.core.config.args.StorageConfig;

/**
 * Builds the {@link ArchiveService} for the current configuration. Disabled config returns the
 * shared {@link NoopArchiveService}; enabled config returns a {@link DefaultArchiveService}.
 *
 * <p>An enabled archive requires a directory and always uses the single UNIFIED_V1 RocksDB under
 * that root. In-memory implementations are independent test oracles and are never factory output.
 */
public final class ArchiveServiceFactory {

  private static final String SUPPORTED_COVERAGE = "TVM_STATE_ONLY";
  private static final String UNIFIED_LAYOUT = "UNIFIED_V1";

  private ArchiveServiceFactory() {
  }

  public static ArchiveService create(StorageConfig.ArchiveConfig config) {
    return create(config, null, ArchiveReadThrough.NONE, null, null);
  }

  public static ArchiveService create(StorageConfig.ArchiveConfig config, String archiveDir) {
    return create(config, archiveDir, ArchiveReadThrough.NONE, null, null);
  }

  public static ArchiveService create(StorageConfig.ArchiveConfig config, String archiveDir,
      ChainBaseManager chainBaseManager) {
    return create(config, archiveDir, ArchiveReadThrough.NONE, chainBaseManager, null);
  }

  /** Production entry point with an external identity anchor under the canonical output root. */
  public static ArchiveService create(StorageConfig.ArchiveConfig config, String archiveDir,
      ChainBaseManager chainBaseManager, Path identityAnchorDirectory) {
    if (identityAnchorDirectory == null) {
      throw new NullPointerException("identityAnchorDirectory");
    }
    return create(config, archiveDir, ArchiveReadThrough.NONE, chainBaseManager,
        identityAnchorDirectory.toAbsolutePath().normalize());
  }

  private static ArchiveService create(StorageConfig.ArchiveConfig config, String archiveDir,
      ArchiveReadThrough readThrough, ChainBaseManager chainBaseManager,
      Path identityAnchorDirectory) {
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
    ArchiveQueryLimits queryLimits = queryLimits(config.getQuery());
    ArchivePublisherConfig publisherConfig = publisherConfig(config.getPublisher());
    if (archiveDir == null || archiveDir.trim().isEmpty()) {
      throw new ArchiveException(
          "archive-enabled service requires a UNIFIED_V1 archive directory");
    }
    Path archivePath = Paths.get(archiveDir).toAbsolutePath().normalize();
    boolean canonicalHasBlocks = chainBaseManager != null && chainBaseManager.hasBlocks();
    try {
      validateArchiveRootBeforeOpen(archivePath, canonicalHasBlocks);
      if (identityAnchorDirectory != null) {
        validateOrInitializeIdentity(config, archivePath, identityAnchorDirectory,
            canonicalHasBlocks, catalog, schemaChecksum);
      } else {
        validateUnanchoredUnifiedInitialization(config, archivePath, canonicalHasBlocks);
      }
      Files.createDirectories(archivePath);
    } catch (ArchiveIdentityException e) {
      throw new ArchiveException("archive identity validation failed: " + e.getMessage(), e);
    } catch (IOException e) {
      throw new ArchiveException("failed to create archive directory " + archiveDir, e);
    }
    return openUnifiedArchive(config, archivePath, readThrough, registry, catalog,
        schemaChecksum, queryLimits, publisherConfig);
  }

  private static void validateOrInitializeIdentity(
      StorageConfig.ArchiveConfig config, Path archivePath, Path anchorDirectory,
      boolean canonicalHasBlocks, ArchiveDomainCatalog catalog, byte[] schemaChecksum)
      throws IOException {
    String chainId = BlockUtil.newGenesisBlockCapsule().getBlockId().toString();
    String schema = ByteArray.toHexString(schemaChecksum);
    boolean initialize = config.getIdentity() != null && config.getIdentity().isInitialize();

    Path rootIdentity = ArchiveIdentityProtocol.rootIdentityPath(archivePath);
    boolean rootIdentityExists = Files.exists(rootIdentity, LinkOption.NOFOLLOW_LINKS);
    UnifiedArchiveIdentityPayload payload =
        new UnifiedArchiveIdentityPayload(catalog, schemaChecksum);
    ArchiveIdentityProtocol protocol = new ArchiveIdentityProtocol(payload);

    if (canonicalHasBlocks || !initialize) {
      if (!rootIdentityExists) {
        String remedy = canonicalHasBlocks
            ? "restore the registered UNIFIED_V1 archive; automatic migration is disabled"
            : "set storage.archive.identity.initialize=true only for a new empty archive";
        throw new ArchiveException("archive identity is missing; " + remedy);
      }
      long actualFloor = UnifiedArchiveIdentityPayload.inspectFloor(
          archivePath, catalog, schemaChecksum);
      protocol.validateActive(
          anchorDirectory, archivePath, chainId, schema, UNIFIED_LAYOUT, actualFloor);
      return;
    }

    Optional<ArchiveIdentityClaim> persisted = protocol.findResumableClaim(
        anchorDirectory, archivePath, chainId, schema, UNIFIED_LAYOUT, 0L);
    ArchiveIdentityClaim claim = persisted.orElseGet(() -> ArchiveIdentityClaim.create(
        chainId, schema, UNIFIED_LAYOUT, archivePath, 0L));
    if (!persisted.isPresent()) {
      protocol.init(anchorDirectory, claim);
    }
    protocol.resume(anchorDirectory, claim);
  }

  private static void validateUnanchoredUnifiedInitialization(
      StorageConfig.ArchiveConfig config, Path archivePath, boolean canonicalHasBlocks) {
    Path databasePath = UnifiedArchiveIdentityPayload.databasePath(archivePath);
    if (Files.exists(databasePath, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    if (canonicalHasBlocks) {
      throw new ArchiveException(
          "refusing to create UNIFIED_V1 for a non-empty canonical database");
    }
    if (config.getIdentity() == null || !config.getIdentity().isInitialize()) {
      throw new ArchiveException("new UNIFIED_V1 archive requires explicit "
          + "storage.archive.identity.initialize=true");
    }
  }

  private static ArchiveService openUnifiedArchive(StorageConfig.ArchiveConfig config,
      Path archivePath, ArchiveReadThrough readThrough, ArchiveDomainRegistry registry,
      ArchiveDomainCatalog catalog, byte[] schemaChecksum, ArchiveQueryLimits queryLimits,
      ArchivePublisherConfig publisherConfig) {
    Path databasePath = UnifiedArchiveIdentityPayload.databasePath(archivePath);
    UnifiedArchiveDb db = null;
    UnifiedArchiveTxNumIndex txNumIndex = null;
    try {
      db = Files.exists(databasePath, LinkOption.NOFOLLOW_LINKS)
          ? UnifiedArchiveDb.open(databasePath, schemaChecksum)
          : UnifiedArchiveDb.initialize(databasePath, schemaChecksum);
      boolean fullStartupScrub = config.getDb().isFullScrubOnStartup();
      txNumIndex = new UnifiedArchiveTxNumIndex(
          db, schemaChecksum, false, true);
      boolean recoveryScrub = fullStartupScrub || txNumIndex.hasRepairRequired();
      UnifiedArchiveTemporalStore temporalStore =
          new UnifiedArchiveTemporalStore(db, catalog);
      UnifiedArchiveInFlightStore inFlightStore =
          new UnifiedArchiveInFlightStore(db, catalog);
      UnifiedArchiveBackend backend =
          new UnifiedArchiveBackend(db, txNumIndex, temporalStore);
      Runnable startupValidator = () -> backend.validateStartup(recoveryScrub, true);
      return new DefaultArchiveService(true, txNumIndex,
          ArchiveExecutionContextHolder.get(), temporalStore, inFlightStore, registry,
          catalog, readThrough, ArchiveLifecycle.Phase.RECOVERING, queryLimits, publisherConfig,
          startupValidator, backend);
    } catch (RuntimeException e) {
      if (txNumIndex == null) {
        closeOnFailure(db, e);
      } else {
        closeOnFailure(txNumIndex, e);
      }
      throw e;
    }
  }

  /**
   * Validates the Unified root before RocksDB is opened with create-if-missing.
   *
   * <p>A missing, empty, or partial root for a non-empty canonical chain is treated as a lost or
   * wrong mount and fails closed instead of creating a second archive. A genuinely empty canonical
   * database may create or resume a partially-created root before genesis commits state.
   */
  static void validateArchiveRootBeforeOpen(Path archivePath, boolean canonicalHasBlocks)
      throws IOException {
    BasicFileAttributes attributes;
    try {
      attributes = Files.readAttributes(
          archivePath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    } catch (NoSuchFileException e) {
      if (canonicalHasBlocks) {
        throw new ArchiveException(
            "refusing to create a missing archive root for a non-empty canonical database: "
                + archivePath);
      }
      return;
    }
    if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
      throw new ArchiveException("archive root is not a real directory: " + archivePath);
    }

    boolean empty;
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(archivePath)) {
      empty = !entries.iterator().hasNext();
    }
    if (!canonicalHasBlocks) {
      return;
    }
    if (empty) {
      throw new ArchiveException(
          "refusing to initialize an empty archive root for a non-empty canonical database: "
              + archivePath);
    }
    requireUnifiedDirectory(UnifiedArchiveIdentityPayload.databasePath(archivePath));
  }

  private static void requireUnifiedDirectory(Path path) throws IOException {
    final BasicFileAttributes attributes;
    try {
      attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    } catch (NoSuchFileException e) {
      throw new ArchiveException("archive UNIFIED_V1 layout is incomplete; missing " + path, e);
    }
    if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
      throw new ArchiveException("archive UNIFIED_V1 path is not a real directory: " + path);
    }
  }

  private static ArchivePublisherConfig publisherConfig(
      StorageConfig.ArchiveConfig.PublisherConfig config) {
    if (config == null) {
      throw new ArchiveException("storage.archive.publisher must not be null");
    }
    return new ArchivePublisherConfig(config.isAsync(), config.isBackpressure(),
        config.getSoftInFlightBlocks(), config.getHardInFlightBlocks(),
        config.getSoftInFlightBytes(), config.getHardInFlightBytes(),
        config.getSoftInFlightRecords(), config.getHardInFlightRecords(),
        config.getSoftMinFreeBytes(), config.getHardMinFreeBytes(),
        config.getBackpressureTimeoutMs());
  }

  private static ArchiveQueryLimits queryLimits(StorageConfig.ArchiveConfig.QueryConfig config) {
    if (config == null) {
      throw new ArchiveException("storage.archive.query must not be null");
    }
    return ArchiveQueryLimits.builder()
        .maxConcurrentQueries(config.getMaxConcurrentQueries())
        .maxPendingQueries(config.getMaxPendingQueries())
        .maxOpenSnapshots(config.getMaxOpenSnapshots())
        .acquireTimeoutMs(config.getAcquireTimeoutMs())
        .deadlineMs(config.getDeadlineMs())
        .maxQueriesPerBatch(config.getMaxQueriesPerBatch())
        .batchDeadlineMs(config.getBatchDeadlineMs())
        .maxLogicalReadsPerRequest(config.getMaxLogicalReadsPerRequest())
        .maxBackendReadsPerRequest(config.getMaxBackendReadsPerRequest())
        .maxCachedEntries(config.getMaxCachedEntries())
        .maxCachedBytes(config.getMaxCachedBytes())
        .maxVmSteps(config.getMaxTraceSteps())
        .maxTraceBytes(config.getMaxTraceBytes())
        .maxRetainedTraceBytes(config.getMaxRetainedTraceBytes())
        .maxResponseBytes(config.getMaxTraceResponseBytes())
        .build();
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
    if (config.getCommitment() != null && config.getCommitment().isEnable()) {
      throw new ArchiveException("storage.archive.commitment.enable is not supported in P0");
    }
    if (config.getCommitment() != null && config.getCommitment().isPersistTxRoots()) {
      throw new ArchiveException(
          "storage.archive.commitment.persistTxRoots cannot be true in P0");
    }
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
