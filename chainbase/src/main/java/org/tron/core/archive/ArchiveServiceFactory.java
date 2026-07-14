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
import org.tron.core.archive.identity.ArchiveIdentity;
import org.tron.core.archive.identity.ArchiveIdentityClaim;
import org.tron.core.archive.identity.ArchiveIdentityException;
import org.tron.core.archive.identity.ArchiveIdentityProtocol;
import org.tron.core.archive.identity.ArchiveIdentityState;
import org.tron.core.archive.identity.LegacyArchiveIdentityPayload;
import org.tron.core.archive.identity.UnifiedArchiveIdentityPayload;
import org.tron.core.archive.query.ArchiveQueryLimits;
import org.tron.core.archive.reader.ArchiveReadThrough;
import org.tron.core.archive.temporal.InMemoryArchiveTemporalStore;
import org.tron.core.archive.temporal.RocksDbArchiveTemporalStore;
import org.tron.core.archive.temporal.UnifiedArchiveTemporalStore;
import org.tron.core.archive.txnum.InMemoryArchiveTxNumIndex;
import org.tron.core.archive.txnum.PersistentArchiveTxNumIndex;
import org.tron.core.archive.txnum.RocksDbArchiveBlockRangeStore;
import org.tron.core.archive.txnum.UnifiedArchiveTxNumIndex;
import org.tron.core.archive.unified.UnifiedArchiveDb;
import org.tron.core.capsule.utils.BlockUtil;
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
  private static final String LEGACY_LAYOUT = StorageConfig.ArchiveConfig.DbConfig.LEGACY_V1;
  private static final String UNIFIED_LAYOUT = StorageConfig.ArchiveConfig.DbConfig.UNIFIED_V1;
  private static final String AUTO_LAYOUT = StorageConfig.ArchiveConfig.DbConfig.AUTO;

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
    if (archiveDir != null && config.getTemporal().isEnable()) {
      Path archivePath = Paths.get(archiveDir).toAbsolutePath().normalize();
      boolean identityAdoption = false;
      boolean canonicalHasBlocks = chainBaseManager != null && chainBaseManager.hasBlocks();
      String layout;
      try {
        layout = resolveArchiveLayout(config, archivePath);
        validateArchiveRootBeforeOpen(archivePath, canonicalHasBlocks, layout);
        if (identityAnchorDirectory != null) {
          identityAdoption = validateOrInitializeIdentity(config, archivePath,
              identityAnchorDirectory,
              canonicalHasBlocks, catalog, schemaChecksum, layout);
        } else if (UNIFIED_LAYOUT.equals(layout)) {
          validateUnanchoredUnifiedInitialization(config, archivePath, canonicalHasBlocks);
        }
        Files.createDirectories(archivePath);
      } catch (ArchiveIdentityException e) {
        throw new ArchiveException("archive identity validation failed: " + e.getMessage(), e);
      } catch (IOException e) {
        throw new ArchiveException("failed to create archive directory " + archiveDir, e);
      }
      if (UNIFIED_LAYOUT.equals(layout)) {
        return openUnifiedArchive(config, archivePath, readThrough, registry, catalog,
            schemaChecksum, queryLimits, publisherConfig);
      }
      RocksDbArchiveTemporalStore temporalStore = null;
      RocksDbArchiveInFlightStore inFlightStore = null;
      RocksDbArchiveBlockRangeStore blockRangeStore = null;
      PersistentArchiveTxNumIndex txNumIndex = null;
      try {
        boolean fullStartupScrub = config.getDb().isFullScrubOnStartup();
        boolean allowStoreInitialize = identityAnchorDirectory == null;
        blockRangeStore =
            new RocksDbArchiveBlockRangeStore(
                archivePath.resolve("index").toString(), fullStartupScrub,
                allowStoreInitialize, false);
        boolean recoveryScrub = fullStartupScrub || identityAdoption
            || blockRangeStore.hasRepairRequired();
        if (recoveryScrub && !fullStartupScrub) {
          blockRangeStore.validateFullKeyspace();
        }
        temporalStore = new RocksDbArchiveTemporalStore(
            archivePath.resolve("temporal").toString(), recoveryScrub,
            allowStoreInitialize, false);
        inFlightStore = new RocksDbArchiveInFlightStore(
            archivePath.resolve("inflight").toString(), catalog,
            recoveryScrub, allowStoreInitialize, false);
        txNumIndex = new PersistentArchiveTxNumIndex(
            blockRangeStore, schemaChecksum, recoveryScrub, true);
        if (!blockRangeStore.getLastRange().isPresent()
            && temporalStore.hasDataBeyondManifest()) {
          throw new ArchiveException(
              "archive temporal store is non-empty but block-range index is empty");
        }
        RocksDbArchiveBlockRangeStore committedIndex = blockRangeStore;
        RocksDbArchiveTemporalStore committedTemporal = temporalStore;
        Runnable startupValidator = () -> {
          committedTemporal.validateStartupTail(committedIndex.getLastRange());
          if (recoveryScrub) {
            committedTemporal.validateCommitMarkersCovered(
                blockNum -> committedIndex.getRange(blockNum).isPresent());
            committedIndex.validateCommittedRanges(committedTemporal::validateCommittedBlock);
            committedTemporal.validateTxNumsCovered(committedIndex::hasCommittedTxNum);
            committedTemporal.validateDomainRows(catalog);
          }
        };
        return new DefaultArchiveService(true, txNumIndex,
            ArchiveExecutionContextHolder.get(), temporalStore, inFlightStore, registry,
            catalog, readThrough, ArchiveLifecycle.Phase.RECOVERING, queryLimits, publisherConfig,
            startupValidator);
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
        new InMemoryArchiveInFlightStore(), registry, catalog, readThrough,
        ArchiveLifecycle.Phase.RECOVERING, queryLimits, publisherConfig, () -> {
        });
  }

  private static boolean validateOrInitializeIdentity(StorageConfig.ArchiveConfig config,
      Path archivePath, Path anchorDirectory, boolean canonicalHasBlocks,
      ArchiveDomainCatalog catalog, byte[] schemaChecksum, String layout) throws IOException {
    if (UNIFIED_LAYOUT.equals(layout)) {
      return validateOrInitializeUnifiedIdentity(config, archivePath, anchorDirectory,
          canonicalHasBlocks, catalog, schemaChecksum);
    }
    if (!LEGACY_LAYOUT.equals(layout)) {
      throw new ArchiveException("unsupported archive layout " + layout);
    }
    String chainId = BlockUtil.newGenesisBlockCapsule().getBlockId().toString();
    String schema = ByteArray.toHexString(schemaChecksum);
    Path rootIdentity = ArchiveIdentityProtocol.rootIdentityPath(archivePath);
    boolean rootIdentityExists = Files.exists(rootIdentity, LinkOption.NOFOLLOW_LINKS);
    boolean initialize = config.getIdentity() != null && config.getIdentity().isInitialize();
    boolean adoptLegacy = config.getIdentity() != null
        && config.getIdentity().isAdoptLegacy();
    if (initialize && adoptLegacy) {
      throw new ArchiveException(
          "archive identity initialize and adoptLegacy cannot both be true");
    }

    if (canonicalHasBlocks) {
      long actualFloor = LegacyArchiveIdentityPayload.inspectFloor(
          archivePath, catalog, schemaChecksum);
      ArchiveIdentityProtocol protocol = new ArchiveIdentityProtocol(
          LegacyArchiveIdentityPayload.forAdoption(catalog, schemaChecksum));
      if (!adoptLegacy) {
        if (!rootIdentityExists) {
          throw new ArchiveException("archive identity is missing; set "
              + "storage.archive.identity.adoptLegacy=true once for a complete legacy archive");
        }
        protocol.validateActive(
            anchorDirectory, archivePath, chainId, schema, LEGACY_LAYOUT, actualFloor);
        return false;
      }
      Optional<ArchiveIdentityClaim> persisted = protocol.findResumableClaim(
          anchorDirectory, archivePath, chainId, schema, LEGACY_LAYOUT, actualFloor);
      ArchiveIdentityClaim claim = persisted.orElseGet(() -> ArchiveIdentityClaim.create(
          chainId, schema, LEGACY_LAYOUT, archivePath, actualFloor));
      if (!persisted.isPresent()) {
        // A corrupt legacy archive must not leave even PREPARED identity metadata behind. The
        // protocol performs the same read-only scrub again before ACTIVE so crash-resume remains
        // self-contained, but this first pass establishes claimability before any durable claim.
        LegacyArchiveIdentityPayload.forAdoption(catalog, schemaChecksum)
            .verifyForActivation(archivePath,
                new ArchiveIdentity(claim, ArchiveIdentityState.BOUND));
        protocol.adopt(anchorDirectory, claim);
      }
      protocol.resumeAdoption(anchorDirectory, claim);
      return true;
    }

    if (adoptLegacy) {
      throw new ArchiveException(
          "archive identity adoptLegacy requires a non-empty canonical database");
    }
    ArchiveIdentityProtocol protocol = new ArchiveIdentityProtocol(
        new LegacyArchiveIdentityPayload(catalog, schemaChecksum));
    if (!initialize) {
      if (!rootIdentityExists) {
        throw new ArchiveException("archive identity is missing; set "
            + "storage.archive.identity.initialize=true only for a new empty archive");
      }
      long actualFloor = LegacyArchiveIdentityPayload.inspectFloor(
          archivePath, catalog, schemaChecksum);
      protocol.validateActive(
          anchorDirectory, archivePath, chainId, schema, LEGACY_LAYOUT, actualFloor);
      return false;
    }
    Optional<ArchiveIdentityClaim> persisted = protocol.findResumableClaim(
        anchorDirectory, archivePath, chainId, schema, LEGACY_LAYOUT, 0L);
    ArchiveIdentityClaim claim = persisted.orElseGet(() -> ArchiveIdentityClaim.create(
        chainId, schema, LEGACY_LAYOUT, archivePath, 0L));
    if (!persisted.isPresent()) {
      protocol.init(anchorDirectory, claim);
    }
    protocol.resume(anchorDirectory, claim);
    return false;
  }

  private static boolean validateOrInitializeUnifiedIdentity(
      StorageConfig.ArchiveConfig config, Path archivePath, Path anchorDirectory,
      boolean canonicalHasBlocks, ArchiveDomainCatalog catalog, byte[] schemaChecksum)
      throws IOException {
    String chainId = BlockUtil.newGenesisBlockCapsule().getBlockId().toString();
    String schema = ByteArray.toHexString(schemaChecksum);
    boolean initialize = config.getIdentity() != null && config.getIdentity().isInitialize();
    boolean adoptLegacy = config.getIdentity() != null
        && config.getIdentity().isAdoptLegacy();
    if (adoptLegacy) {
      throw new ArchiveException(
          "storage.archive.identity.adoptLegacy cannot target UNIFIED_V1");
    }

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
      return false;
    }

    Optional<ArchiveIdentityClaim> persisted = protocol.findResumableClaim(
        anchorDirectory, archivePath, chainId, schema, UNIFIED_LAYOUT, 0L);
    ArchiveIdentityClaim claim = persisted.orElseGet(() -> ArchiveIdentityClaim.create(
        chainId, schema, UNIFIED_LAYOUT, archivePath, 0L));
    if (!persisted.isPresent()) {
      protocol.init(anchorDirectory, claim);
    }
    protocol.resume(anchorDirectory, claim);
    return false;
  }

  private static void validateUnanchoredUnifiedInitialization(
      StorageConfig.ArchiveConfig config, Path archivePath, boolean canonicalHasBlocks) {
    if (config.getIdentity() != null && config.getIdentity().isAdoptLegacy()) {
      throw new ArchiveException(
          "storage.archive.identity.adoptLegacy cannot target UNIFIED_V1");
    }
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

  private static String resolveArchiveLayout(StorageConfig.ArchiveConfig config,
      Path archivePath) throws IOException {
    if (config.getDb() == null) {
      throw new ArchiveException("storage.archive.db must not be null");
    }
    String configured = config.getDb().getLayout();
    if (!AUTO_LAYOUT.equals(configured)) {
      if (LEGACY_LAYOUT.equals(configured) || UNIFIED_LAYOUT.equals(configured)) {
        return configured;
      }
      throw new ArchiveException("unsupported archive layout " + configured);
    }
    if (!Files.isDirectory(archivePath, LinkOption.NOFOLLOW_LINKS)) {
      throw new ArchiveException(
          "storage.archive.db.layout=AUTO requires an existing registered archive root");
    }
    if (!Files.isRegularFile(ArchiveIdentityProtocol.rootIdentityPath(archivePath),
        LinkOption.NOFOLLOW_LINKS)) {
      throw new ArchiveException(
          "storage.archive.db.layout=AUTO requires an ACTIVE root identity");
    }
    ArchiveIdentity identity = ArchiveIdentityProtocol.inspectRootIdentity(archivePath);
    if (identity.getState() != ArchiveIdentityState.ACTIVE) {
      throw new ArchiveException(
          "storage.archive.db.layout=AUTO requires an ACTIVE root identity");
    }
    String detected = identity.getLayout();
    if (!LEGACY_LAYOUT.equals(detected) && !UNIFIED_LAYOUT.equals(detected)) {
      throw new ArchiveException("archive root identity uses unsupported layout " + detected);
    }
    return detected;
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
   * Classifies the root before any RocksDB is opened with create-if-missing.
   *
   * <p>An existing canonical chain may only reopen a complete legacy layout. A missing, empty, or
   * partial root is treated as a lost/wrong mount and fails closed instead of creating a second
   * archive at the configured path. A genuinely empty canonical database may create or resume a
   * partially-created root because genesis has not yet committed any state.
   */
  static void validateArchiveRootBeforeOpen(Path archivePath, boolean canonicalHasBlocks)
      throws IOException {
    validateArchiveRootBeforeOpen(archivePath, canonicalHasBlocks, LEGACY_LAYOUT);
  }

  static void validateArchiveRootBeforeOpen(Path archivePath, boolean canonicalHasBlocks,
      String layout) throws IOException {
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
    if (LEGACY_LAYOUT.equals(layout)) {
      requireLegacyDirectory(archivePath.resolve("temporal"));
      requireLegacyDirectory(archivePath.resolve("index"));
      requireLegacyDirectory(archivePath.resolve("inflight"));
      return;
    }
    if (UNIFIED_LAYOUT.equals(layout)) {
      requireUnifiedDirectory(UnifiedArchiveIdentityPayload.databasePath(archivePath));
      return;
    }
    throw new ArchiveException("unsupported archive layout " + layout);
  }

  private static void requireLegacyDirectory(Path path) throws IOException {
    final BasicFileAttributes attributes;
    try {
      attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    } catch (NoSuchFileException e) {
      throw new ArchiveException("archive legacy layout is incomplete; missing " + path, e);
    }
    if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
      throw new ArchiveException("archive legacy layout path is not a real directory: " + path);
    }
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
