package org.tron.core.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
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
import org.tron.core.archive.temporal.RocksDbArchiveTemporalStore;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.archive.txnum.PersistentArchiveTxNumIndex;
import org.tron.core.archive.txnum.RocksDbArchiveBlockRangeStore;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.utils.BlockUtil;
import org.tron.core.config.args.StorageConfig;

public class ArchiveServiceFactoryTest {

  @Rule
  public final TemporaryFolder temporaryFolder = new TemporaryFolder();

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
  public void nonEmptyCanonicalDatabaseAcceptsCompleteLegacyRoot() throws Exception {
    Path root = Files.createDirectory(
        temporaryFolder.getRoot().toPath().resolve("complete"));
    Files.createDirectory(root.resolve("temporal"));
    Files.createDirectory(root.resolve("index"));
    Files.createDirectory(root.resolve("inflight"));

    ArchiveServiceFactory.validateArchiveRootBeforeOpen(root, true);
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
  public void strictExistingStoreOpenRejectsAnEmptyLostChildMount() throws Exception {
    Path child = Files.createDirectory(
        temporaryFolder.getRoot().toPath().resolve("lost-inflight"));

    assertThrows(ArchiveException.class, () -> new RocksDbArchiveInFlightStore(
        child.toString(), new DefaultArchiveDomainCatalog(), true, false, false));
  }

  @Test
  public void legacyAdoptionUsesPersistedMidChainFloor() throws Exception {
    Path root = Files.createDirectory(
        temporaryFolder.getRoot().toPath().resolve("mid-chain"));
    ArchiveDomainRegistry registry = new DefaultArchiveDomainRegistry();
    ArchiveDomainCatalog catalog = new DefaultArchiveDomainCatalog();
    byte[] checksum = ArchiveSchemaChecksum.of(registry, catalog);
    ArchiveBlockRange range;
    try (RocksDbArchiveTemporalStore temporal = new RocksDbArchiveTemporalStore(
             root.resolve("temporal").toString());
         RocksDbArchiveInFlightStore ignored = new RocksDbArchiveInFlightStore(
             root.resolve("inflight").toString(), catalog);
         PersistentArchiveTxNumIndex index = new PersistentArchiveTxNumIndex(
             new RocksDbArchiveBlockRangeStore(root.resolve("index").toString()), checksum)) {
      index.beginBlock(42L, ArchiveSource.NORMAL);
      index.allocateSystemTx(42L, ArchivePhase.BLOCK_PREPARE);
      index.allocateSystemTx(42L, ArchivePhase.BLOCK_FINALIZE);
      range = index.commitBlock(42L, new byte[32], 0, checksum);
      temporal.putBlockChanges(range, Collections.emptyList());
    }

    assertEquals(42L, LegacyArchiveIdentityPayload.inspectFloor(root, catalog, checksum));
    ArchiveIdentityClaim correctClaim = ArchiveIdentityClaim.create(
        "chain", ByteArray.toHexString(checksum), "LEGACY_V1", root, 42L);
    LegacyArchiveIdentityPayload.forAdoption(catalog, checksum)
        .verifyForActivation(root,
            new ArchiveIdentity(correctClaim, ArchiveIdentityState.BOUND));
    ArchiveIdentityClaim wrongClaim = ArchiveIdentityClaim.create(
        "chain", ByteArray.toHexString(checksum), "LEGACY_V1", root, 0L);
    assertThrows(ArchiveIdentityException.class,
        () -> LegacyArchiveIdentityPayload.forAdoption(catalog, checksum)
            .verifyForActivation(root,
                new ArchiveIdentity(wrongClaim, ArchiveIdentityState.BOUND)));
  }

  @Test
  public void repairMarkerForcesUnknownIndexKeyDetection() throws Exception {
    Path path = temporaryFolder.getRoot().toPath().resolve("repair-index");
    try (RocksDbArchiveBlockRangeStore store =
             new RocksDbArchiveBlockRangeStore(path.toString())) {
      store.markRepairRequired("injected failure");
    }
    try (Options options = new Options().setCreateIfMissing(false);
         RocksDB db = RocksDB.open(options, path.toString())) {
      db.put(new byte[] {(byte) 0x7f}, new byte[] {1});
    }
    try (RocksDbArchiveBlockRangeStore store =
             new RocksDbArchiveBlockRangeStore(path.toString(), false, false, false)) {
      assertTrue(store.hasRepairRequired());
      assertThrows(ArchiveException.class, store::validateFullKeyspace);
    }
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
    }
  }

  @Test
  public void factoryInitializesUnifiedThenAutoReopensRegisteredLayout() throws Exception {
    Path base = temporaryFolder.getRoot().toPath();
    Path root = base.resolve("new-unified");
    Path anchors = Files.createDirectory(base.resolve("unified-anchors"));
    StorageConfig.ArchiveConfig config = unifiedConfig();
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
      config.getDb().setLayout(StorageConfig.ArchiveConfig.DbConfig.AUTO);
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
  public void autoLayoutRejectsFreshDirectory() throws Exception {
    Path root = Files.createDirectory(
        temporaryFolder.getRoot().toPath().resolve("fresh-auto"));
    StorageConfig.ArchiveConfig config = unifiedConfig();
    config.getDb().setLayout(StorageConfig.ArchiveConfig.DbConfig.AUTO);
    config.getIdentity().setInitialize(true);
    ChainBaseManager chainBaseManager = mock(ChainBaseManager.class);
    when(chainBaseManager.hasBlocks()).thenReturn(false);

    try (MockedStatic<Arch> arch = mockStatic(Arch.class)) {
      arch.when(Arch::isArm64).thenReturn(true);
      ArchiveException failure = assertThrows(ArchiveException.class,
          () -> ArchiveServiceFactory.create(
              config, root.toString(), chainBaseManager,
              temporaryFolder.getRoot().toPath()));
      assertTrue(failure.getMessage().contains("AUTO requires"));
    }
  }

  private static StorageConfig.ArchiveConfig unifiedConfig() {
    StorageConfig.ArchiveConfig config = new StorageConfig.ArchiveConfig();
    config.setEnable(true);
    config.getDb().setLayout(StorageConfig.ArchiveConfig.DbConfig.UNIFIED_V1);
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
