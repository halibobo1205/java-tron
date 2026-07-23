package org.tron.core.archive;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.tron.core.archive.unified.UnifiedArchiveTestMaintenance.write;

import com.google.protobuf.ByteString;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;
import org.tron.common.arch.Arch;
import org.tron.core.ChainBaseManager;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.domain.ArchiveDomainCatalog;
import org.tron.core.archive.domain.ArchiveDomainRegistry;
import org.tron.core.archive.domain.ArchiveSchemaChecksum;
import org.tron.core.archive.domain.DefaultArchiveDomainCatalog;
import org.tron.core.archive.domain.DefaultArchiveDomainRegistry;
import org.tron.core.archive.identity.ArchiveIdentityProtocol;
import org.tron.core.archive.temporal.UnifiedArchiveTemporalStore;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.archive.txnum.ArchiveTxPosition;
import org.tron.core.archive.txnum.UnifiedArchiveTxNumIndex;
import org.tron.core.archive.unified.UnifiedArchiveColumnFamily;
import org.tron.core.archive.unified.UnifiedArchiveDb;
import org.tron.core.archive.unified.UnifiedArchiveMaintenanceBatch;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.utils.BlockUtil;
import org.tron.core.config.args.StorageConfig;
import org.tron.protos.Protocol.Account;

public class ArchiveServiceFactoryTest {

  @Rule
  public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void directServiceConstructorRejectsPersistentTemporalStore() {
    UnifiedArchiveTemporalStore temporalStore = mock(UnifiedArchiveTemporalStore.class);

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> new DefaultArchiveService(true, temporalStore));

    assertTrue(failure.getMessage().contains("must be constructed by ArchiveServiceFactory"));
  }

  @Test
  public void nonEmptyCanonicalDatabaseRejectsMissingEmptyAndPartialRoots() throws Exception {
    Path base = temporaryFolder.getRoot().toPath();
    Path missing = base.resolve("missing");
    Path empty = Files.createDirectory(base.resolve("empty"));
    Path partial = Files.createDirectory(base.resolve("partial"));
    Files.createDirectory(partial.resolve("temporal"));

    assertThrows(ArchiveException.class,
        () -> ArchiveServiceFactory.validateArchiveRootBeforeOpen(missing, true));
    assertThrows(ArchiveException.class,
        () -> ArchiveServiceFactory.validateArchiveRootBeforeOpen(empty, true));
    assertThrows(ArchiveException.class,
        () -> ArchiveServiceFactory.validateArchiveRootBeforeOpen(partial, true));
  }

  @Test
  public void nonEmptyCanonicalDatabaseAcceptsCompleteUnifiedRoot() throws Exception {
    Path root = Files.createDirectory(
        temporaryFolder.getRoot().toPath().resolve("complete"));
    Files.createDirectory(root.resolve("unified"));

    ArchiveServiceFactory.validateArchiveRootBeforeOpen(root, true);
  }

  @Test
  public void openExistingModeNeverInitializesMissingUnifiedDatabase() {
    Path databasePath = temporaryFolder.getRoot().toPath()
        .resolve("missing-strict-open-root").resolve("unified");
    byte[] schemaChecksum = ArchiveSchemaChecksum.of(
        new DefaultArchiveDomainRegistry(), new DefaultArchiveDomainCatalog());

    assertThrows(ArchiveException.class,
        () -> ArchiveServiceFactory.openUnifiedDatabase(
            databasePath, schemaChecksum, ArchiveServiceFactory.UnifiedOpenMode.OPEN_EXISTING));

    assertFalse(Files.exists(databasePath));
  }

  @Test
  public void emptyCanonicalDatabaseMayCreateOrResumeRoot() throws Exception {
    Path base = temporaryFolder.getRoot().toPath();
    Path missing = base.resolve("fresh-missing");
    Path partial = Files.createDirectory(base.resolve("fresh-partial"));
    Files.createDirectory(partial.resolve("temporal"));

    ArchiveServiceFactory.validateArchiveRootBeforeOpen(missing, false);
    ArchiveServiceFactory.validateArchiveRootBeforeOpen(partial, false);
  }

  @Test
  public void factoryPreservesIdentityValidationReason() throws Exception {
    Path base = temporaryFolder.getRoot().toPath();
    Path root = Files.createDirectory(base.resolve("invalid-identity-root"));
    Path anchors = Files.createDirectory(base.resolve("identity-anchors"));
    Files.write(ArchiveIdentityProtocol.rootIdentityPath(root), new byte[0]);
    StorageConfig.ArchiveConfig config = new StorageConfig.ArchiveConfig();
    config.setEnable(true);
    config.getIdentity().setInitialize(true);
    ChainBaseManager chainBaseManager = mock(ChainBaseManager.class);
    when(chainBaseManager.hasBlocks()).thenReturn(false);
    BlockCapsule genesis = mock(BlockCapsule.class);
    BlockCapsule.BlockId genesisId = mock(BlockCapsule.BlockId.class);
    when(genesis.getBlockId()).thenReturn(genesisId);
    when(genesisId.toString()).thenReturn("test-chain-id");

    try (MockedStatic<Arch> arch = mockStatic(Arch.class);
         MockedStatic<BlockUtil> blockUtil = mockStatic(BlockUtil.class)) {
      arch.when(Arch::isArm64).thenReturn(true);
      blockUtil.when(BlockUtil::newGenesisBlockCapsule).thenReturn(genesis);
      ArchiveException failure = assertThrows(ArchiveException.class,
          () -> ArchiveServiceFactory.create(
              config, root.toString(), chainBaseManager, anchors));

      assertTrue(failure.getMessage().contains("archive identity validation failed"));
      assertTrue(failure.getMessage().contains("invalid size"));
      assertTrue(failure.getMessage().contains("backup compatible with this build"));
      assertTrue(failure.getMessage().contains("rebuild canonical and archive together"));
    }
  }

  @Test
  public void factoryInitializesAndReopensUnifiedArchive() throws Exception {
    Path base = temporaryFolder.getRoot().toPath();
    Path root = base.resolve("new-unified");
    Path anchors = Files.createDirectory(base.resolve("unified-anchors"));
    StorageConfig.ArchiveConfig config = archiveConfig();
    config.getIdentity().setInitialize(true);
    ChainBaseManager chainBaseManager = mock(ChainBaseManager.class);
    when(chainBaseManager.hasBlocks()).thenReturn(false);
    BlockCapsule genesis = mock(BlockCapsule.class);
    BlockCapsule.BlockId genesisId = mock(BlockCapsule.BlockId.class);
    when(genesis.getBlockId()).thenReturn(genesisId);
    when(genesisId.toString()).thenReturn("unified-test-chain");

    try (MockedStatic<Arch> arch = mockStatic(Arch.class);
         MockedStatic<BlockUtil> blockUtil = mockStatic(BlockUtil.class)) {
      arch.when(Arch::isArm64).thenReturn(true);
      blockUtil.when(BlockUtil::newGenesisBlockCapsule).thenReturn(genesis);
      ArchiveService service = ArchiveServiceFactory.create(
          config, root.toString(), chainBaseManager, anchors);
      try {
        completeRecovery(service);
      } finally {
        service.close();
      }

      assertTrue(Files.isDirectory(root.resolve("unified")));
      assertTrue(Files.notExists(root.resolve("temporal")));
      assertTrue(Files.notExists(root.resolve("index")));
      assertTrue(Files.notExists(root.resolve("inflight")));

      config.getIdentity().setInitialize(false);
      service = ArchiveServiceFactory.create(
          config, root.toString(), chainBaseManager, anchors);
      try {
        completeRecovery(service);
      } finally {
        service.close();
      }
    }
  }

  @Test
  public void factoryInitializesFreshUnanchoredArchiveOnlyWhenExplicitlyRequested()
      throws Exception {
    Path root = temporaryFolder.getRoot().toPath().resolve("new-unanchored-unified");
    StorageConfig.ArchiveConfig config = archiveConfig();
    config.getIdentity().setInitialize(true);
    ChainBaseManager chainBaseManager = mock(ChainBaseManager.class);
    when(chainBaseManager.hasBlocks()).thenReturn(false);

    try (MockedStatic<Arch> arch = mockStatic(Arch.class)) {
      arch.when(Arch::isArm64).thenReturn(true);
      ArchiveService service = ArchiveServiceFactory.create(
          config, root.toString(), chainBaseManager);
      try {
        completeRecovery(service);
      } finally {
        service.close();
      }
    }

    assertTrue(Files.isDirectory(root.resolve("unified")));
  }

  @Test
  public void enabledArchiveRejectsUnlimitedQueryDeadline() {
    StorageConfig.ArchiveConfig config = archiveConfig();
    config.getQuery().setDeadlineMs(-1L);

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> ArchiveServiceFactory.create(
            config, temporaryFolder.getRoot().getAbsolutePath(), null,
            temporaryFolder.getRoot().toPath()));

    assertTrue(failure.getMessage().contains("query.deadlineMs must be finite"));
  }

  @Test
  public void enabledArchiveRejectsDeadlineOutsideFiniteNanosecondRange() {
    StorageConfig.ArchiveConfig config = archiveConfig();
    config.getQuery().setDeadlineMs(Long.MAX_VALUE);

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> ArchiveServiceFactory.create(
            config, temporaryFolder.getRoot().getAbsolutePath(), null,
            temporaryFolder.getRoot().toPath()));

    assertTrue(failure.getMessage().contains("query.deadlineMs exceeds"));
    assertTrue(failure.getMessage().contains("finite nanosecond range"));
  }

  @Test
  public void enabledArchiveRejectsUnlimitedAllocationDrivingQueryLimit() {
    StorageConfig.ArchiveConfig config = archiveConfig();
    config.getQuery().setMaxBackendValueBytes(-1L);

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> ArchiveServiceFactory.create(
            config, temporaryFolder.getRoot().getAbsolutePath(), null,
            temporaryFolder.getRoot().toPath()));

    assertTrue(failure.getMessage().contains("query.maxBackendValueBytes must be finite"));
  }

  @Test
  public void enabledArchiveRejectsUnlimitedQueryAdmissionWait() {
    StorageConfig.ArchiveConfig config = archiveConfig();
    config.getQuery().setAcquireTimeoutMs(-1L);

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> ArchiveServiceFactory.create(
            config, temporaryFolder.getRoot().getAbsolutePath(), null,
            temporaryFolder.getRoot().toPath()));

    assertTrue(failure.getMessage().contains("query.acquireTimeoutMs must be finite"));
  }

  @Test
  public void enabledArchiveRejectsUnlimitedVmOverlayAllocation() {
    StorageConfig.ArchiveConfig config = archiveConfig();
    config.getQuery().setMaxVmOverlayBytes(-1L);

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> ArchiveServiceFactory.create(
            config, temporaryFolder.getRoot().getAbsolutePath(), null,
            temporaryFolder.getRoot().toPath()));

    assertTrue(failure.getMessage().contains("query.maxVmOverlayBytes must be finite"));
  }

  @Test
  public void enabledArchiveRejectsQueryValueLimitAboveStoredPayloadCeiling() {
    StorageConfig.ArchiveConfig config = archiveConfig();
    config.getQuery().setMaxBackendValueBytes(
        UnifiedArchiveTemporalStore.maxStoredPayloadBytes() + 1L);

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> ArchiveServiceFactory.create(
            config, temporaryFolder.getRoot().getAbsolutePath(), null,
            temporaryFolder.getRoot().toPath()));

    assertTrue(failure.getMessage().contains("stored-value limit"));
  }

  @Test
  public void enabledArchiveRejectsPublisherLimitAboveTemporalFormatCeiling() {
    StorageConfig.ArchiveConfig config = archiveConfig();
    config.getPublisher().setHardInFlightBytes(256L * 1024L * 1024L + 1L);

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> ArchiveServiceFactory.create(
            config, temporaryFolder.getRoot().getAbsolutePath(), null,
            temporaryFolder.getRoot().toPath()));

    assertTrue(failure.getMessage().contains("single-value format limit"));
  }

  @Test
  public void factoryPersistsRepairMarkerForJournalCorruption() throws Exception {
    Path root = Files.createDirectory(
        temporaryFolder.getRoot().toPath().resolve("corrupt-journal-root"));
    ArchiveDomainRegistry registry = new DefaultArchiveDomainRegistry();
    ArchiveDomainCatalog catalog = new DefaultArchiveDomainCatalog();
    byte[] schemaChecksum = ArchiveSchemaChecksum.of(registry, catalog);
    Path databasePath = root.resolve("unified");
    try (UnifiedArchiveDb db = UnifiedArchiveDb.initialize(databasePath, schemaChecksum)) {
      db.putJournalDurably(
          ArchiveInFlightCodec.acknowledgementKey(0L), new byte[] {1});
    }
    StorageConfig.ArchiveConfig config = archiveConfig();
    ChainBaseManager chainBaseManager = mock(ChainBaseManager.class);
    when(chainBaseManager.hasBlocks()).thenReturn(false);

    try (MockedStatic<Arch> arch = mockStatic(Arch.class)) {
      arch.when(Arch::isArm64).thenReturn(true);
      assertThrows(ArchiveJournalCorruptionException.class,
          () -> ArchiveServiceFactory.create(
              config, root.toString(), chainBaseManager));
    }

    UnifiedArchiveDb reopened = UnifiedArchiveDb.open(databasePath, schemaChecksum);
    UnifiedArchiveTxNumIndex index = null;
    try {
      index = new UnifiedArchiveTxNumIndex(reopened, schemaChecksum, false, true);
      assertTrue(index.hasRepairRequired());
    } finally {
      if (index == null) {
        reopened.close();
      } else {
        index.close();
      }
    }
  }

  @Test
  public void anchoredFactoryPersistsRepairMarkerForJournalCorruptionDuringFloorInspection()
      throws Exception {
    Path base = temporaryFolder.getRoot().toPath();
    Path root = base.resolve("anchored-corrupt-journal-root");
    Path anchors = Files.createDirectory(base.resolve("anchored-corrupt-journal-anchors"));
    StorageConfig.ArchiveConfig config = archiveConfig();
    config.getIdentity().setInitialize(true);
    ChainBaseManager chainBaseManager = mock(ChainBaseManager.class);
    when(chainBaseManager.hasBlocks()).thenReturn(false);
    BlockCapsule genesis = mock(BlockCapsule.class);
    BlockCapsule.BlockId genesisId = mock(BlockCapsule.BlockId.class);
    when(genesis.getBlockId()).thenReturn(genesisId);
    when(genesisId.toString()).thenReturn("anchored-corruption-test-chain");

    ArchiveDomainRegistry registry = new DefaultArchiveDomainRegistry();
    ArchiveDomainCatalog catalog = new DefaultArchiveDomainCatalog();
    byte[] schemaChecksum = ArchiveSchemaChecksum.of(registry, catalog);
    Path databasePath = root.resolve("unified");
    try (MockedStatic<Arch> arch = mockStatic(Arch.class);
         MockedStatic<BlockUtil> blockUtil = mockStatic(BlockUtil.class)) {
      arch.when(Arch::isArm64).thenReturn(true);
      blockUtil.when(BlockUtil::newGenesisBlockCapsule).thenReturn(genesis);
      ArchiveService service = ArchiveServiceFactory.create(
          config, root.toString(), chainBaseManager, anchors);
      try {
        completeRecovery(service);
      } finally {
        service.close();
      }

      try (UnifiedArchiveDb db = UnifiedArchiveDb.open(databasePath, schemaChecksum)) {
        db.putJournalDurably(
            ArchiveInFlightCodec.acknowledgementKey(0L), new byte[] {1});
      }
      config.getIdentity().setInitialize(false);
      when(chainBaseManager.hasBlocks()).thenReturn(true);

      ArchiveJournalCorruptionException failure = assertThrows(
          ArchiveJournalCorruptionException.class,
          () -> ArchiveServiceFactory.create(
              config, root.toString(), chainBaseManager, anchors));
      assertTrue(failure.getMessage().contains("orphan lifecycle row"));
    }

    try (UnifiedArchiveDb db = UnifiedArchiveDb.open(databasePath, schemaChecksum)) {
      assertTrue(UnifiedArchiveTxNumIndex.hasRepairRequired(db));
    }
  }

  @Test
  public void factoryPersistsRepairMarkerForProofValidJournalSemanticMismatch()
      throws Exception {
    Path root = Files.createDirectory(
        temporaryFolder.getRoot().toPath().resolve("semantic-journal-root"));
    ArchiveDomainRegistry registry = new DefaultArchiveDomainRegistry();
    ArchiveDomainCatalog catalog = new DefaultArchiveDomainCatalog();
    byte[] schemaChecksum = ArchiveSchemaChecksum.of(registry, catalog);
    Path databasePath = root.resolve("unified");
    try (UnifiedArchiveDb db = UnifiedArchiveDb.initialize(databasePath, schemaChecksum)) {
      UnifiedArchiveInFlightStore journals = new UnifiedArchiveInFlightStore(db, catalog);
      ArchiveInFlightBlock first = journalBlock(
          0L, schemaChecksum, DomainValue.tombstone(), accountValue(1L));
      ArchiveInFlightBlock second = journalBlock(
          1L, schemaChecksum, accountValue(9L), accountValue(2L));
      journals.putBlock(first);
      journals.acknowledgeBlock(first.getJournalToken());
      journals.putBlock(second);
      journals.acknowledgeBlock(second.getJournalToken());
    }
    StorageConfig.ArchiveConfig config = archiveConfig();
    ChainBaseManager chainBaseManager = mock(ChainBaseManager.class);
    when(chainBaseManager.hasBlocks()).thenReturn(false);

    try (MockedStatic<Arch> arch = mockStatic(Arch.class)) {
      arch.when(Arch::isArm64).thenReturn(true);
      ArchivePersistentStateCorruptionException failure = assertThrows(
          ArchivePersistentStateCorruptionException.class,
          () -> ArchiveServiceFactory.create(config, root.toString(), chainBaseManager));
      assertTrue(failure.getCause().getMessage().contains("prev-value chain mismatch"));
    }

    UnifiedArchiveDb reopened = UnifiedArchiveDb.open(databasePath, schemaChecksum);
    UnifiedArchiveTxNumIndex index = null;
    try {
      index = new UnifiedArchiveTxNumIndex(reopened, schemaChecksum, false, true);
      assertTrue(index.hasRepairRequired());
    } finally {
      if (index == null) {
        reopened.close();
      } else {
        index.close();
      }
    }
  }

  @Test
  public void factoryMarksRepairForProofBoundPayloadLengthCorruption() throws Exception {
    Path root = Files.createDirectory(
        temporaryFolder.getRoot().toPath().resolve("payload-length-corruption-root"));
    ArchiveDomainRegistry registry = new DefaultArchiveDomainRegistry();
    ArchiveDomainCatalog catalog = new DefaultArchiveDomainCatalog();
    byte[] schemaChecksum = ArchiveSchemaChecksum.of(registry, catalog);
    Path databasePath = root.resolve("unified");
    try (UnifiedArchiveDb db = UnifiedArchiveDb.initialize(databasePath, schemaChecksum)) {
      UnifiedArchiveInFlightStore journals = new UnifiedArchiveInFlightStore(db, catalog);
      ArchiveInFlightBlock block = journalBlock(
          0L, schemaChecksum, DomainValue.tombstone(), accountValue(1L));
      journals.putBlock(block);
      byte[] journalKey = ArchiveInFlightCodec.blockKey(0L);
      byte[] payload = db.get(UnifiedArchiveColumnFamily.INFLIGHT, journalKey);
      byte[] proof = db.get(UnifiedArchiveColumnFamily.INFLIGHT,
          ArchiveInFlightCodec.tokenKey(0L));
      journals.deleteBlock(0L);
      db.putJournalBlockDurably(
          journalKey, Arrays.copyOf(payload, payload.length + 1),
          ArchiveInFlightCodec.tokenKey(0L), proof,
          ArchiveInFlightCodec.acknowledgementKey(0L), null);
    }
    ChainBaseManager chainBaseManager = mock(ChainBaseManager.class);
    when(chainBaseManager.hasBlocks()).thenReturn(false);

    try (MockedStatic<Arch> arch = mockStatic(Arch.class)) {
      arch.when(Arch::isArm64).thenReturn(true);
      assertThrows(ArchiveJournalCorruptionException.class,
          () -> ArchiveServiceFactory.create(
              archiveConfig(), root.toString(), chainBaseManager));
    }

    try (UnifiedArchiveDb reopened = UnifiedArchiveDb.open(databasePath, schemaChecksum)) {
      assertTrue(UnifiedArchiveTxNumIndex.hasRepairRequired(reopened));
    }
  }

  @Test
  public void factoryRejectsInflatedProofLengthBeforeMaterializingClaimedBytes()
      throws Exception {
    Path root = Files.createDirectory(
        temporaryFolder.getRoot().toPath().resolve("inflated-proof-length-root"));
    ArchiveDomainRegistry registry = new DefaultArchiveDomainRegistry();
    ArchiveDomainCatalog catalog = new DefaultArchiveDomainCatalog();
    byte[] schemaChecksum = ArchiveSchemaChecksum.of(registry, catalog);
    Path databasePath = root.resolve("unified");
    int actualPayloadBytes;
    try (UnifiedArchiveDb db = UnifiedArchiveDb.initialize(databasePath, schemaChecksum)) {
      UnifiedArchiveInFlightStore journals = new UnifiedArchiveInFlightStore(db, catalog);
      ArchiveInFlightBlock block = journalBlock(
          0L, schemaChecksum, DomainValue.tombstone(), accountValue(1L));
      journals.putBlock(block);
      byte[] journalKey = ArchiveInFlightCodec.blockKey(0L);
      byte[] payload = db.get(UnifiedArchiveColumnFamily.INFLIGHT, journalKey);
      actualPayloadBytes = payload.length;
      byte[] proofBytes = db.get(UnifiedArchiveColumnFamily.INFLIGHT,
          ArchiveInFlightCodec.tokenKey(0L));
      ArchiveJournalProof proof = ArchiveInFlightCodec.decodeProof(proofBytes);
      ArchiveJournalProof inflated = new ArchiveJournalProof(
          proof.getToken(), 128L * 1024 * 1024, proof.getPayloadDigest());
      journals.deleteBlock(0L);
      db.putJournalBlockDurably(
          journalKey, payload,
          ArchiveInFlightCodec.tokenKey(0L), ArchiveInFlightCodec.encodeProof(inflated),
          ArchiveInFlightCodec.acknowledgementKey(0L), null);
    }
    ChainBaseManager chainBaseManager = mock(ChainBaseManager.class);
    when(chainBaseManager.hasBlocks()).thenReturn(false);

    try (MockedStatic<Arch> arch = mockStatic(Arch.class)) {
      arch.when(Arch::isArm64).thenReturn(true);
      ArchiveJournalCorruptionException failure = assertThrows(
          ArchiveJournalCorruptionException.class,
          () -> ArchiveServiceFactory.create(
              archiveConfig(), root.toString(), chainBaseManager));
      assertTrue(failure.getMessage().contains("length mismatch"));
      assertTrue(failure.getMessage().contains("actualBytes=" + actualPayloadBytes));
    }

    try (UnifiedArchiveDb reopened = UnifiedArchiveDb.open(databasePath, schemaChecksum)) {
      assertTrue(UnifiedArchiveTxNumIndex.hasRepairRequired(reopened));
    }
  }

  @Test
  public void factoryDoesNotMarkRepairWhenValidJournalExceedsNewConfiguration()
      throws Exception {
    Path root = Files.createDirectory(
        temporaryFolder.getRoot().toPath().resolve("journal-limit-root"));
    ArchiveDomainRegistry registry = new DefaultArchiveDomainRegistry();
    ArchiveDomainCatalog catalog = new DefaultArchiveDomainCatalog();
    byte[] schemaChecksum = ArchiveSchemaChecksum.of(registry, catalog);
    Path databasePath = root.resolve("unified");
    long encodedBytes;
    try (UnifiedArchiveDb db = UnifiedArchiveDb.initialize(databasePath, schemaChecksum)) {
      UnifiedArchiveInFlightStore journals = new UnifiedArchiveInFlightStore(db, catalog);
      ArchiveInFlightBlock block = journalBlock(
          0L, schemaChecksum, DomainValue.tombstone(), accountValueWithName(16 * 1024));
      journals.putBlock(block);
      encodedBytes = ArchiveInFlightCodec.encodedBlockSize(block);
    }
    StorageConfig.ArchiveConfig config = archiveConfig();
    config.getPublisher().setSoftInFlightBytes(encodedBytes - 1L);
    config.getPublisher().setHardInFlightBytes(encodedBytes - 1L);
    ChainBaseManager chainBaseManager = mock(ChainBaseManager.class);
    when(chainBaseManager.hasBlocks()).thenReturn(false);

    try (MockedStatic<Arch> arch = mockStatic(Arch.class)) {
      arch.when(Arch::isArm64).thenReturn(true);
      assertThrows(ArchiveJournalLimitException.class,
          () -> ArchiveServiceFactory.create(config, root.toString(), chainBaseManager));
    }

    try (UnifiedArchiveDb reopened = UnifiedArchiveDb.open(databasePath, schemaChecksum)) {
      assertFalse(UnifiedArchiveTxNumIndex.hasRepairRequired(reopened));
    }
  }

  @Test
  public void factoryMarksRepairWhenMiddlePublishedRangeIsMissing() throws Exception {
    Path root = Files.createDirectory(
        temporaryFolder.getRoot().toPath().resolve("missing-middle-range-root"));
    ArchiveDomainRegistry registry = new DefaultArchiveDomainRegistry();
    ArchiveDomainCatalog catalog = new DefaultArchiveDomainCatalog();
    byte[] schemaChecksum = ArchiveSchemaChecksum.of(registry, catalog);
    Path databasePath = root.resolve("unified");
    UnifiedArchiveDb db = UnifiedArchiveDb.initialize(databasePath, schemaChecksum);
    try (UnifiedArchiveTxNumIndex index = new UnifiedArchiveTxNumIndex(
        db, schemaChecksum, false, true)) {
      UnifiedArchiveTemporalStore temporal = new UnifiedArchiveTemporalStore(db, catalog);
      UnifiedArchiveInFlightStore journals = new UnifiedArchiveInFlightStore(db, catalog);
      UnifiedArchiveBackend backend = new UnifiedArchiveBackend(db, index, temporal);
      DomainValue previous = DomainValue.tombstone();
      for (long blockNum = 0L; blockNum < 3L; blockNum++) {
        DomainValue current = accountValue(blockNum + 1L);
        ArchiveInFlightBlock block = journalBlock(
            blockNum, schemaChecksum, previous, current);
        journals.putBlock(block);
        journals.acknowledgeBlock(block.getJournalToken());
        backend.publishBlock(block.withJournalState(
            ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED));
        previous = current;
      }
    }
    try (UnifiedArchiveDb reopened = UnifiedArchiveDb.open(databasePath, schemaChecksum)) {
      byte[] missingRangeKey = ByteBuffer.allocate(1 + Long.BYTES)
          .put((byte) 0x10)
          .putLong(1L)
          .array();
      write(reopened, new UnifiedArchiveMaintenanceBatch().delete(
          UnifiedArchiveColumnFamily.INDEX, missingRangeKey));
    }
    ChainBaseManager chainBaseManager = mock(ChainBaseManager.class);
    when(chainBaseManager.hasBlocks()).thenReturn(false);

    try (MockedStatic<Arch> arch = mockStatic(Arch.class)) {
      arch.when(Arch::isArm64).thenReturn(true);
      assertThrows(ArchivePersistentStateCorruptionException.class,
          () -> ArchiveServiceFactory.create(
              archiveConfig(), root.toString(), chainBaseManager));
    }

    try (UnifiedArchiveDb reopened = UnifiedArchiveDb.open(databasePath, schemaChecksum)) {
      assertTrue(UnifiedArchiveTxNumIndex.hasRepairRequired(reopened));
    }
  }

  private static ArchiveInFlightBlock journalBlock(long blockNum, byte[] schemaChecksum,
      DomainValue previous, DomainValue current) {
    long firstTxNum = blockNum * 2L;
    byte[] blockHash = new byte[ArchiveBlockRange.BLOCK_HASH_LENGTH];
    blockHash[blockHash.length - 1] = (byte) (blockNum + 1L);
    ArchiveBlockRange range = new ArchiveBlockRange(
        blockNum, firstTxNum, firstTxNum + 1L, firstTxNum, firstTxNum + 1L,
        blockHash, 0, ArchiveSource.NORMAL, schemaChecksum);
    ArchiveTxPosition prepare = new ArchiveTxPosition(
        firstTxNum, blockNum, ArchivePhase.BLOCK_PREPARE, ArchiveSource.NORMAL, -1, null);
    ArchiveTxPosition finalize = new ArchiveTxPosition(
        firstTxNum + 1L, blockNum, ArchivePhase.BLOCK_FINALIZE,
        ArchiveSource.NORMAL, -1, null);
    ArchiveChangeRecord record = new ArchiveChangeRecord(
        prepare, ArchiveDomain.ACCOUNT, new byte[21], previous, current);
    return new ArchiveInFlightBlock(
        range, Arrays.asList(prepare, finalize), Collections.singletonList(record));
  }

  private static DomainValue accountValue(long balance) {
    return DomainValue.present(Account.newBuilder().setBalance(balance).build().toByteArray());
  }

  private static DomainValue accountValueWithName(int bytes) {
    return DomainValue.present(Account.newBuilder()
        .setAccountName(ByteString.copyFrom(new byte[bytes]))
        .build()
        .toByteArray());
  }

  private static StorageConfig.ArchiveConfig archiveConfig() {
    StorageConfig.ArchiveConfig config = new StorageConfig.ArchiveConfig();
    config.setEnable(true);
    config.getPublisher().setSoftMinFreeBytes(0L);
    config.getPublisher().setHardMinFreeBytes(0L);
    return config;
  }

  private static void completeRecovery(ArchiveService service) {
    try (ArchiveWorkLease recovery = service.acquireRecoveryLease()) {
      recovery.start();
      service.completeRecovery();
    }
  }
}
