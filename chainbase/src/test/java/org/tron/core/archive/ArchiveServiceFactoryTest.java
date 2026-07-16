package org.tron.core.archive;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;
import org.tron.common.arch.Arch;
import org.tron.core.ChainBaseManager;
import org.tron.core.archive.identity.ArchiveIdentityProtocol;
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
  public void nonEmptyCanonicalDatabaseAcceptsCompleteUnifiedRoot() throws Exception {
    Path root = Files.createDirectory(
        temporaryFolder.getRoot().toPath().resolve("complete"));
    Files.createDirectory(root.resolve("unified"));

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
