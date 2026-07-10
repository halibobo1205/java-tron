package org.tron.core.db;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mockStatic;

import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.tron.common.BaseMethodTest;
import org.tron.common.arch.Arch;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.utils.ByteArray;
import org.tron.core.archive.ArchiveService;
import org.tron.core.archive.DefaultArchiveService;
import org.tron.core.archive.reader.ArchiveReadResult;
import org.tron.core.archive.reader.ArchiveStatePoint;
import org.tron.core.archive.reader.ArchiveStateReader;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.services.jsonrpc.HistoricalArchiveVmDynamicProperties;

public class ManagerGenesisArchiveLifecycleTest extends BaseMethodTest {

  private MockedStatic<Arch> arch;
  private DefaultArchiveService archiveService;

  @Override
  protected void beforeContext() {
    arch = mockStatic(Arch.class);
    arch.when(Arch::isArm64).thenReturn(true);
    CommonParameter.getInstance().getStorage().getArchive().setEnable(true);
  }

  @Override
  protected void beforeDestroy() {
    if (arch != null) {
      arch.close();
    }
  }

  @Override
  protected void afterInit() {
    ArchiveService service = context.getBean(ArchiveService.class);
    assertTrue(service.isEnabled());
    archiveService = (DefaultArchiveService) service;
  }

  @Test
  public void initGenesisArchivesConstructorSeededVmDynamicProperties() throws Exception {
    BlockCapsule genesis = chainBaseManager.getGenesisBlock();
    ArchiveBlockRange range = archiveService.getTxNumIndex().getBlockRange(0)
        .orElseThrow(() -> new AssertionError("archive has no committed range for genesis"));
    ArchiveStateReader reader = archiveService.getReaderFactory().open(ArchiveStatePoint.blockEnd(
        0, genesis.getBlockId().getBytes(), range.getFinalizeTxNum()));

    HistoricalArchiveVmDynamicProperties.validateGenesisArchiveRows(
        chainBaseManager.getDynamicPropertiesStore(), reader);
    assertArchivedLong(reader, "ALLOW_CREATION_OF_CONTRACTS",
        chainBaseManager.getDynamicPropertiesStore().getAllowCreationOfContracts());
    assertArchivedLong(reader, "MAINTENANCE_TIME_INTERVAL",
        chainBaseManager.getDynamicPropertiesStore().getMaintenanceTimeInterval());
    assertArchivedLong(reader, "ALLOW_DYNAMIC_ENERGY",
        chainBaseManager.getDynamicPropertiesStore().getAllowDynamicEnergy());
    assertArchivedLong(reader, "ENERGY_FEE",
        chainBaseManager.getDynamicPropertiesStore().getEnergyFee());
    assertArchivedLong(reader, "latest_block_header_timestamp", genesis.getTimeStamp());
    assertArchivedLong(reader, "TRANSACTION_FEE",
        chainBaseManager.getDynamicPropertiesStore().getTransactionFee());
    assertArchivedLong(reader, "NEXT_MAINTENANCE_TIME",
        chainBaseManager.getDynamicPropertiesStore().getNextMaintenanceTime());
    assertArchivedLong(reader, "ALLOW_TVM_PRAGUE",
        chainBaseManager.getDynamicPropertiesStore().getAllowTvmPrague());
    assertArchivedPresent(reader, "ACTIVE_DEFAULT_OPERATIONS");
    assertArchivedBlackholeAccount(reader);
  }

  private static void assertArchivedLong(ArchiveStateReader reader, String key, long expected)
      throws Exception {
    ArchiveReadResult<byte[]> result =
        reader.getDynamicProperty(key.getBytes(StandardCharsets.US_ASCII));
    assertTrue("expected archived dynamic property " + key, result.isPresent());
    assertEquals(expected, ByteArray.toLong(result.getValue()));
  }

  private static void assertArchivedPresent(ArchiveStateReader reader, String key)
      throws Exception {
    ArchiveReadResult<byte[]> result =
        reader.getDynamicProperty(key.getBytes(StandardCharsets.US_ASCII));
    assertTrue("expected archived dynamic property " + key, result.isPresent());
  }

  private void assertArchivedBlackholeAccount(ArchiveStateReader reader) throws Exception {
    AccountCapsule liveBlackhole = chainBaseManager.getAccountStore().getBlackhole();
    ArchiveReadResult<AccountCapsule> archivedBlackhole =
        reader.getAccount(liveBlackhole.getAddress().toByteArray());
    assertTrue("expected archived blackhole account", archivedBlackhole.isPresent());
    assertArrayEquals(liveBlackhole.getData(), archivedBlackhole.getValue().getData());
  }
}
