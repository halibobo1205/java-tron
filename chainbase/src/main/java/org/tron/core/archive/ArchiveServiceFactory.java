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
  private static final long MAX_FINITE_TIMEOUT_MS = Long.MAX_VALUE / 1_000_000L;

  enum UnifiedOpenMode {
    OPEN_EXISTING,
    INITIALIZE_NEW
  }

  private ArchiveServiceFactory() {
  }

  public static ArchiveService create(StorageConfig.ArchiveConfig config) {
    return createEnabled(config, null, null, null);
  }

  static ArchiveService create(StorageConfig.ArchiveConfig config, String archiveDir) {
    return createEnabled(config, archiveDir, null, null);
  }

  static ArchiveService create(StorageConfig.ArchiveConfig config, String archiveDir,
      ChainBaseManager chainBaseManager) {
    return createEnabled(config, archiveDir, chainBaseManager, null);
  }

  /** Production entry point with an external identity anchor under the canonical output root. */
  public static ArchiveService create(StorageConfig.ArchiveConfig config, String archiveDir,
      ChainBaseManager chainBaseManager, Path identityAnchorDirectory) {
    if (identityAnchorDirectory == null) {
      throw new NullPointerException("identityAnchorDirectory");
    }
    return createEnabled(config, archiveDir, chainBaseManager,
        identityAnchorDirectory.toAbsolutePath().normalize());
  }

  private static ArchiveService createEnabled(StorageConfig.ArchiveConfig config, String archiveDir,
      ChainBaseManager chainBaseManager, Path identityAnchorDirectory) {
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
    UnifiedOpenMode openMode;
    try {
      validateArchiveRootBeforeOpen(archivePath, canonicalHasBlocks);
      if (identityAnchorDirectory != null) {
        validateOrInitializeIdentity(config, archivePath, identityAnchorDirectory,
            canonicalHasBlocks, catalog, schemaChecksum);
        // Identity binding creates the payload before activation. Every ACTIVE/resumed identity
        // must therefore strict-open the registered DB and may never fall back to initialization.
        openMode = UnifiedOpenMode.OPEN_EXISTING;
      } else {
        openMode = validateUnanchoredUnifiedInitialization(
            config, archivePath, canonicalHasBlocks);
      }
      if (openMode == UnifiedOpenMode.INITIALIZE_NEW) {
        Files.createDirectories(archivePath);
      }
    } catch (ArchiveIdentityException e) {
      throw new ArchiveException("archive identity validation failed: " + e.getMessage()
          + "; restore a canonical/archive backup compatible with this build, or rebuild "
          + "canonical and archive together from empty directories", e);
    } catch (IOException e) {
      throw new ArchiveException("failed to create archive directory " + archiveDir, e);
    }
    return openUnifiedArchive(config, archivePath, registry, catalog,
        schemaChecksum, queryLimits, publisherConfig, openMode);
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
      protocol.withValidatedActive(
          anchorDirectory, archivePath, chainId, schema, UNIFIED_LAYOUT, active -> {
            long actualFloor = UnifiedArchiveIdentityPayload.inspectAuthenticatedFloor(
                archivePath, catalog, schemaChecksum,
                config.getPublisher().getHardInFlightBytes(),
                config.getPublisher().getHardInFlightRecords(),
                config.getPublisher().getHardInFlightBlocks());
            if (active.getFloor() != actualFloor) {
              throw new ArchiveIdentityException("archive identity active floor mismatch");
            }
            return null;
          });
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

  private static UnifiedOpenMode validateUnanchoredUnifiedInitialization(
      StorageConfig.ArchiveConfig config, Path archivePath, boolean canonicalHasBlocks) {
    Path databasePath = UnifiedArchiveIdentityPayload.databasePath(archivePath);
    if (Files.exists(databasePath, LinkOption.NOFOLLOW_LINKS)) {
      return UnifiedOpenMode.OPEN_EXISTING;
    }
    if (canonicalHasBlocks) {
      throw new ArchiveException(
          "refusing to create UNIFIED_V1 for a non-empty canonical database");
    }
    if (config.getIdentity() == null || !config.getIdentity().isInitialize()) {
      throw new ArchiveException("new UNIFIED_V1 archive requires explicit "
          + "storage.archive.identity.initialize=true");
    }
    return UnifiedOpenMode.INITIALIZE_NEW;
  }

  private static ArchiveService openUnifiedArchive(StorageConfig.ArchiveConfig config,
      Path archivePath, ArchiveDomainRegistry registry,
      ArchiveDomainCatalog catalog, byte[] schemaChecksum, ArchiveQueryLimits queryLimits,
      ArchivePublisherConfig publisherConfig, UnifiedOpenMode openMode) {
    Path databasePath = UnifiedArchiveIdentityPayload.databasePath(archivePath);
    UnifiedArchiveDb db = null;
    UnifiedArchiveTxNumIndex txNumIndex = null;
    UnifiedArchiveDb.ProductionWritePermit writePermit = null;
    try {
      db = openUnifiedDatabase(databasePath, schemaChecksum, openMode);
      writePermit = db.claimProductionWritePermit();
      boolean fullStartupScrub = config.getDb().isFullScrubOnStartup();
      txNumIndex = new UnifiedArchiveTxNumIndex(
          db, schemaChecksum, false, true, writePermit);
      boolean recoveryScrub = fullStartupScrub || txNumIndex.hasRepairRequired();
      UnifiedArchiveTemporalStore temporalStore =
          new UnifiedArchiveTemporalStore(db, catalog);
      UnifiedArchiveInFlightStore inFlightStore =
          new UnifiedArchiveInFlightStore(
              db, catalog, publisherConfig.getHardInFlightBytes(),
              publisherConfig.getHardInFlightRecords(),
              publisherConfig.getHardInFlightBlocks(), writePermit);
      UnifiedArchiveBackend backend =
          new UnifiedArchiveBackend(
              db, txNumIndex, temporalStore, publisherConfig.getHardInFlightBytes(),
              publisherConfig.getHardInFlightRecords(), writePermit);
      Runnable startupValidator = () -> backend.validateStartup(recoveryScrub, true);
      db.sealForProduction();
      return new DefaultArchiveService(true, txNumIndex,
          ArchiveExecutionContextHolder.get(), temporalStore, inFlightStore, registry,
          catalog, ArchiveLifecycle.Phase.RECOVERING, queryLimits, publisherConfig,
          startupValidator, backend);
    } catch (RuntimeException | Error e) {
      if (e instanceof ArchivePersistentStateCorruptionException && db != null) {
        try {
          String reason = e.getMessage() == null
              ? e.getClass().getSimpleName() : e.getMessage();
          reason = UnifiedArchiveTxNumIndex.boundRepairReason(reason);
          if (txNumIndex == null) {
            UnifiedArchiveTxNumIndex.markRepairRequired(db, reason, writePermit);
          } else {
            txNumIndex.markRepairRequired(reason);
          }
        } catch (RuntimeException | Error markerFailure) {
          addSuppressedSafely(e, markerFailure);
        }
      }
      if (txNumIndex == null) {
        closeOnFailure(db, e);
      } else {
        closeOnFailure(txNumIndex, e);
      }
      throw e;
    }
  }

  static UnifiedArchiveDb openUnifiedDatabase(Path databasePath, byte[] schemaChecksum,
      UnifiedOpenMode openMode) {
    if (openMode == null) {
      throw new NullPointerException("openMode");
    }
    return openMode == UnifiedOpenMode.INITIALIZE_NEW
        ? UnifiedArchiveDb.initialize(databasePath, schemaChecksum)
        : UnifiedArchiveDb.open(databasePath, schemaChecksum);
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
        config.getBackpressureTimeoutMs(), config.getPublishTimeoutMs(),
        config.getJournalTimeoutMs(), config.getRecoveryTimeoutMs());
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
        .maxBackendValueBytes(config.getMaxBackendValueBytes())
        .maxBackendReadBytesPerRequest(config.getMaxBackendReadBytesPerRequest())
        .maxCachedEntries(config.getMaxCachedEntries())
        .maxCachedBytes(config.getMaxCachedBytes())
        .maxVmSteps(config.getMaxVmSteps())
        .maxVmOverlayBytes(config.getMaxVmOverlayBytes())
        .maxResponseBytes(config.getMaxResponseBytes())
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
    if (config.getDebug() != null && config.getDebug().isEnable()) {
      throw new ArchiveException("storage.archive.debug.enable is not supported in P0");
    }
    if (config.getQuery() == null
        || ArchiveQueryLimits.isUnlimited(config.getQuery().getDeadlineMs())) {
      throw new ArchiveException(
          "storage.archive.query.deadlineMs must be finite when archive is enabled");
    }
    validateFiniteQueryLimits(config.getQuery());
    if (config.getQuery().getMaxBackendValueBytes()
        > UnifiedArchiveTemporalStore.maxStoredPayloadBytes()) {
      throw new ArchiveException("storage.archive.query.maxBackendValueBytes exceeds the "
          + "UNIFIED_V1 stored-value limit "
          + UnifiedArchiveTemporalStore.maxStoredPayloadBytes());
    }
    if (config.getPublisher() == null) {
      throw new ArchiveException("storage.archive.publisher must not be null");
    }
    long hardInFlightBytes = config.getPublisher().getHardInFlightBytes();
    if (hardInFlightBytes > UnifiedArchiveTemporalStore.maxPayloadBytes()) {
      throw new ArchiveException("storage.archive.publisher.hardInFlightBytes exceeds the "
          + "UNIFIED_V1 single-value format limit "
          + UnifiedArchiveTemporalStore.maxPayloadBytes());
    }
  }

  private static void validateFiniteQueryLimits(StorageConfig.ArchiveConfig.QueryConfig query) {
    requireFiniteQueryLimit("maxConcurrentQueries", query.getMaxConcurrentQueries());
    requireFiniteQueryLimit("maxPendingQueries", query.getMaxPendingQueries());
    requireFiniteQueryLimit("maxOpenSnapshots", query.getMaxOpenSnapshots());
    requireFiniteDurationMs("acquireTimeoutMs", query.getAcquireTimeoutMs());
    requireFiniteDurationMs("deadlineMs", query.getDeadlineMs());
    requireFiniteQueryLimit("maxQueriesPerBatch", query.getMaxQueriesPerBatch());
    requireFiniteDurationMs("batchDeadlineMs", query.getBatchDeadlineMs());
    requireFiniteQueryLimit("maxLogicalReadsPerRequest", query.getMaxLogicalReadsPerRequest());
    requireFiniteQueryLimit("maxBackendReadsPerRequest", query.getMaxBackendReadsPerRequest());
    requireFiniteQueryLimit("maxBackendValueBytes", query.getMaxBackendValueBytes());
    requireFiniteQueryLimit(
        "maxBackendReadBytesPerRequest", query.getMaxBackendReadBytesPerRequest());
    requireFiniteQueryLimit("maxVmSteps", query.getMaxVmSteps());
    requireFiniteQueryLimit("maxVmOverlayBytes", query.getMaxVmOverlayBytes());
    requireFiniteQueryLimit("maxResponseBytes", query.getMaxResponseBytes());
  }

  private static void requireFiniteQueryLimit(String name, long value) {
    if (ArchiveQueryLimits.isUnlimited(value)) {
      throw new ArchiveException(
          "storage.archive.query." + name + " must be finite when archive is enabled");
    }
  }

  private static void requireFiniteDurationMs(String name, long value) {
    requireFiniteQueryLimit(name, value);
    if (value > MAX_FINITE_TIMEOUT_MS) {
      throw new ArchiveException(
          "storage.archive.query." + name + " exceeds the finite nanosecond range "
              + MAX_FINITE_TIMEOUT_MS);
    }
  }

  private static void closeOnFailure(AutoCloseable resource, Throwable failure) {
    if (resource == null) {
      return;
    }
    try {
      resource.close();
    } catch (Throwable closeFailure) {
      addSuppressedSafely(failure, closeFailure);
    }
  }

  private static void addSuppressedSafely(Throwable primary, Throwable candidate) {
    if (primary == null || candidate == null || primary == candidate) {
      return;
    }
    try {
      primary.addSuppressed(candidate);
    } catch (Throwable ignored) {
      // Startup must preserve its primary failure even when suppression is unavailable.
    }
  }
}
