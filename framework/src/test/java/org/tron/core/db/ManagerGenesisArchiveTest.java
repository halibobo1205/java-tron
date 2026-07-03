package org.tron.core.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import org.junit.Test;
import org.tron.common.utils.ReflectUtils;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.ChainBaseManager;
import org.tron.core.archive.ArchiveService;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.exception.TronError;
import org.tron.core.store.DynamicPropertiesStore;

public class ManagerGenesisArchiveTest {

  @Test
  public void initGenesisRejectsExistingGenesisWithoutArchiveBlockZero() {
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

    TronError error = assertThrows(TronError.class, manager::initGenesis);

    assertEquals(TronError.ErrCode.GENESIS_BLOCK_INIT, error.getErrCode());
    assertTrue(error.getMessage().contains("genesis block was not captured"));
  }
}
