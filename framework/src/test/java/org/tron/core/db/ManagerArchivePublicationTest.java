package org.tron.core.db;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.tron.common.utils.ReflectUtils;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.ChainBaseManager;
import org.tron.core.archive.ArchiveService;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.BlockCapsule.BlockId;
import org.tron.core.store.DynamicPropertiesStore;

public class ManagerArchivePublicationTest {

  @Test
  public void capsPublishedArchiveAtRecoverableCanonicalHead() throws Exception {
    Manager manager = new Manager();
    ArchiveService archiveService = mock(ArchiveService.class);
    RevokingDatabase revokingStore = mock(RevokingDatabase.class);
    ChainBaseManager chainBaseManager = mock(ChainBaseManager.class);
    DynamicPropertiesStore dynamicPropertiesStore = mock(DynamicPropertiesStore.class);
    BlockCapsule currentBlock = mock(BlockCapsule.class);
    BlockId recoverableBlockId = new BlockId(Sha256Hash.ZERO_HASH, 218L);

    when(archiveService.isEnabled()).thenReturn(true);
    when(archiveService.requiresPublishTargetHash()).thenReturn(true);
    when(chainBaseManager.getDynamicPropertiesStore()).thenReturn(dynamicPropertiesStore);
    when(chainBaseManager.getBlockIdByNum(218L)).thenReturn(recoverableBlockId);
    when(dynamicPropertiesStore.getLatestBlockHeaderNumber()).thenReturn(221L);
    when(dynamicPropertiesStore.getLatestSolidifiedBlockNum()).thenReturn(221L);
    when(revokingStore.size()).thenReturn(2);
    when(revokingStore.getPendingFlushCount()).thenReturn(1);
    when(currentBlock.getNum()).thenReturn(221L);

    ReflectUtils.setFieldValue(manager, "archiveService", archiveService);
    ReflectUtils.setFieldValue(manager, "revokingStore", revokingStore);
    ReflectUtils.setFieldValue(manager, "chainBaseManager", chainBaseManager);

    manager.publishArchiveSolidifiedOrFailStop(currentBlock, "test");

    verify(archiveService).requestPublishSolidifiedBlocks(
        218L, recoverableBlockId.getBytes());
  }
}
