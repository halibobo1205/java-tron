package org.tron.core.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import org.junit.Test;
import org.tron.common.utils.ReflectUtils;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.ChainBaseManager;
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveService;
import org.tron.core.archive.ArchiveSource;
import org.tron.core.archive.DefaultArchiveService;
import org.tron.core.archive.temporal.InMemoryArchiveTemporalStore;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.exception.TronError;
import org.tron.core.store.DynamicPropertiesStore;

public class ManagerGenesisArchiveTest {

  @Test
  public void initGenesisDefersInFlightReconcileUntilCanonicalHeadIsKnown() {
    Manager manager = new Manager();
    ChainBaseManager chainBaseManager = mock(ChainBaseManager.class);
    ArchiveService archiveService = mock(ArchiveService.class);
    DynamicPropertiesStore dynamicPropertiesStore = mock(DynamicPropertiesStore.class);
    BlockCapsule genesis = new BlockCapsule(0L, Sha256Hash.ZERO_HASH, 0L, ByteString.EMPTY);
    when(chainBaseManager.getGenesisBlock()).thenReturn(genesis);
    when(chainBaseManager.containBlock(genesis.getBlockId())).thenReturn(true);
    when(chainBaseManager.getDynamicPropertiesStore()).thenReturn(dynamicPropertiesStore);
    when(dynamicPropertiesStore.getLatestBlockHeaderNumber()).thenReturn(12L);
    when(archiveService.isEnabled()).thenReturn(true);
    ReflectUtils.setFieldValue(manager, "chainBaseManager", chainBaseManager);
    ReflectUtils.setFieldValue(manager, "archiveService", archiveService);

    manager.initGenesis();

    verify(archiveService, never()).reconcileInFlightOnStartup(anyLong(), anyLong(), any());
  }

  @Test
  public void genesisArchiveCoverageRejectsExistingGenesisWithoutArchiveBlockZero() {
    Manager manager = new Manager();
    ChainBaseManager chainBaseManager = mock(ChainBaseManager.class);
    ArchiveService archiveService = mock(ArchiveService.class);
    DynamicPropertiesStore dynamicPropertiesStore = mock(DynamicPropertiesStore.class);
    BlockCapsule genesis = new BlockCapsule(0L, Sha256Hash.ZERO_HASH, 0L, ByteString.EMPTY);
    when(chainBaseManager.getGenesisBlock()).thenReturn(genesis);
    when(chainBaseManager.containBlock(genesis.getBlockId())).thenReturn(true);
    when(chainBaseManager.getDynamicPropertiesStore()).thenReturn(dynamicPropertiesStore);
    when(dynamicPropertiesStore.getLatestBlockHeaderNumber()).thenReturn(0L);
    when(archiveService.isEnabled()).thenReturn(true);
    when(archiveService.hasCommittedBlock(0L)).thenReturn(false);
    ReflectUtils.setFieldValue(manager, "chainBaseManager", chainBaseManager);
    ReflectUtils.setFieldValue(manager, "archiveService", archiveService);

    TronError error = assertThrows(TronError.class, manager::validateGenesisArchiveCoverage);

    assertEquals(TronError.ErrCode.GENESIS_BLOCK_INIT, error.getErrCode());
    assertTrue(error.getMessage().contains("genesis block was not captured"));
  }

  @Test
  public void genesisArchiveCoverageRejectsMissingVmDynamicRows() {
    Manager manager = new Manager();
    ChainBaseManager chainBaseManager = mock(ChainBaseManager.class);
    DynamicPropertiesStore dynamicPropertiesStore = mock(DynamicPropertiesStore.class);
    BlockCapsule genesis = new BlockCapsule(0L, Sha256Hash.ZERO_HASH, 0L, ByteString.EMPTY);
    DefaultArchiveService archiveService =
        new DefaultArchiveService(true, new InMemoryArchiveTemporalStore());
    archiveService.getTxNumIndex().beginBlock(0L, ArchiveSource.NORMAL);
    archiveService.getTxNumIndex().allocateSystemTx(0L, ArchivePhase.BLOCK_PREPARE);
    archiveService.getTxNumIndex().allocateSystemTx(0L, ArchivePhase.BLOCK_FINALIZE);
    archiveService.getTxNumIndex().commitBlock(0L, genesis.getBlockId().getBytes(), 0);
    try {
      when(chainBaseManager.getGenesisBlock()).thenReturn(genesis);
      when(chainBaseManager.containBlock(genesis.getBlockId())).thenReturn(true);
      when(chainBaseManager.getDynamicPropertiesStore()).thenReturn(dynamicPropertiesStore);
      when(dynamicPropertiesStore.getLatestBlockHeaderNumber()).thenReturn(0L);
      ReflectUtils.setFieldValue(manager, "chainBaseManager", chainBaseManager);
      ReflectUtils.setFieldValue(manager, "archiveService", archiveService);

      TronError error = assertThrows(TronError.class, manager::validateGenesisArchiveCoverage);

      assertEquals(TronError.ErrCode.GENESIS_BLOCK_INIT, error.getErrCode());
      assertTrue(error.getMessage().contains("genesis VM dynamic properties are incomplete"));
    } finally {
      archiveService.close();
    }
  }

  @Test
  public void genesisArchiveCoverageRejectsMissingVmRowsAfterHeadAdvances() {
    Manager manager = new Manager();
    ChainBaseManager chainBaseManager = mock(ChainBaseManager.class);
    DynamicPropertiesStore dynamicPropertiesStore = mock(DynamicPropertiesStore.class);
    BlockCapsule genesis = new BlockCapsule(0L, Sha256Hash.ZERO_HASH, 0L, ByteString.EMPTY);
    DefaultArchiveService archiveService =
        new DefaultArchiveService(true, new InMemoryArchiveTemporalStore());
    archiveService.getTxNumIndex().beginBlock(0L, ArchiveSource.NORMAL);
    archiveService.getTxNumIndex().allocateSystemTx(0L, ArchivePhase.BLOCK_PREPARE);
    archiveService.getTxNumIndex().allocateSystemTx(0L, ArchivePhase.BLOCK_FINALIZE);
    archiveService.getTxNumIndex().commitBlock(0L, genesis.getBlockId().getBytes(), 0);
    try {
      when(chainBaseManager.getGenesisBlock()).thenReturn(genesis);
      when(chainBaseManager.containBlock(genesis.getBlockId())).thenReturn(true);
      when(chainBaseManager.getDynamicPropertiesStore()).thenReturn(dynamicPropertiesStore);
      when(dynamicPropertiesStore.getLatestBlockHeaderNumber()).thenReturn(1L);
      ReflectUtils.setFieldValue(manager, "chainBaseManager", chainBaseManager);
      ReflectUtils.setFieldValue(manager, "archiveService", archiveService);

      TronError error = assertThrows(TronError.class, manager::validateGenesisArchiveCoverage);

      assertEquals(TronError.ErrCode.GENESIS_BLOCK_INIT, error.getErrCode());
      assertTrue(error.getMessage().contains("genesis VM dynamic properties are incomplete"));
    } finally {
      archiveService.close();
    }
  }
}
