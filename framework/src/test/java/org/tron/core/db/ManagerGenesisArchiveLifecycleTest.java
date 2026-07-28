package org.tron.core.db;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.tron.common.BaseMethodTest;
import org.tron.common.application.ApplicationFactory;
import org.tron.common.application.TronApplicationContext;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.utils.ByteArray;
import org.tron.core.archive.ArchiveService;
import org.tron.core.archive.ArchiveServiceTestAccess;
import org.tron.core.archive.DefaultArchiveService;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.reader.ArchiveReadResult;
import org.tron.core.archive.reader.ArchiveStateReader;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.config.DefaultConfig;
import org.tron.core.db2.core.SnapshotManager;
import org.tron.core.services.jsonrpc.HistoricalArchiveVmDynamicProperties;

public class ManagerGenesisArchiveLifecycleTest extends BaseMethodTest {

  private DefaultArchiveService archiveService;
  private boolean archiveEnabledBefore;
  private boolean identityInitializeBefore;
  private boolean needToUpdateAssetBefore;
  private long allowTvmConstantinopleBefore;

  @Override
  protected void beforeContext() {
    archiveEnabledBefore = CommonParameter.getInstance().getStorage().getArchive().isEnable();
    identityInitializeBefore = CommonParameter.getInstance().getStorage().getArchive()
        .getIdentity().isInitialize();
    needToUpdateAssetBefore = CommonParameter.getInstance().isNeedToUpdateAsset();
    allowTvmConstantinopleBefore = CommonParameter.getInstance().getAllowTvmConstantinople();
    CommonParameter.getInstance().getStorage().getArchive().setEnable(true);
    CommonParameter.getInstance().getStorage().getArchive().getIdentity().setInitialize(true);
    CommonParameter.getInstance().setNeedToUpdateAsset(true);
    CommonParameter.getInstance().setAllowTvmConstantinople(1);
  }

  @Override
  protected void beforeDestroy() {
    CommonParameter.getInstance().getStorage().getArchive().setEnable(archiveEnabledBefore);
    CommonParameter.getInstance().getStorage().getArchive().getIdentity()
        .setInitialize(identityInitializeBefore);
    CommonParameter.getInstance().setNeedToUpdateAsset(needToUpdateAssetBefore);
    CommonParameter.getInstance().setAllowTvmConstantinople(allowTvmConstantinopleBefore);
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
    assertTrue(chainBaseManager.getDynamicPropertiesStore()
        .isArchiveGenesisCommitComplete(genesis.getBlockId().getByteString()));
    assertEquals(0L, archiveService.getFirstArchivedBlock());
    try (ArchiveStateReader reader = archiveService.openBlockEndReader(
        0, genesis.getBlockId().getBytes())) {
      assertTrue(reader.isGenesisComplete());
      HistoricalArchiveVmDynamicProperties.validateGenesisArchiveRows(reader);
      assertArchivedLong(reader, "ALLOW_CREATION_OF_CONTRACTS",
          chainBaseManager.getDynamicPropertiesStore().getAllowCreationOfContracts());
      assertArchivedLong(reader, "MAINTENANCE_TIME_INTERVAL",
          chainBaseManager.getDynamicPropertiesStore().getMaintenanceTimeInterval());
      assertArchivedLong(reader, "ALLOW_DYNAMIC_ENERGY",
          chainBaseManager.getDynamicPropertiesStore().getAllowDynamicEnergy());
      assertArchivedLong(reader, "ENERGY_FEE",
          chainBaseManager.getDynamicPropertiesStore().getEnergyFee());
      assertArchivedInternalBytes("ENERGY_PRICE_HISTORY",
          ByteArray.fromString(
              chainBaseManager.getDynamicPropertiesStore().getEnergyPriceHistory()));
      assertArchivedInternalBytes("BANDWIDTH_PRICE_HISTORY",
          ByteArray.fromString(
              chainBaseManager.getDynamicPropertiesStore().getBandwidthPriceHistory()));
      assertArchivedLong(reader, "latest_block_header_timestamp", genesis.getTimeStamp());
      assertArchivedLong(reader, "TRANSACTION_FEE",
          chainBaseManager.getDynamicPropertiesStore().getTransactionFee());
      assertArchivedLong(reader, "NEXT_MAINTENANCE_TIME",
          chainBaseManager.getDynamicPropertiesStore().getNextMaintenanceTime());
      assertArchivedLong(reader, "ALLOW_TVM_PRAGUE",
          chainBaseManager.getDynamicPropertiesStore().getAllowTvmPrague());
      assertArchivedLong(reader, "ALLOW_TVM_CONSTANTINOPLE", 1L);
      assertArchivedBytes(reader, "AVAILABLE_CONTRACT_TYPE",
          chainBaseManager.getDynamicPropertiesStore().getAvailableContractType());
      assertArchivedBytes(reader, "ACTIVE_DEFAULT_OPERATIONS",
          chainBaseManager.getDynamicPropertiesStore().getActiveDefaultOperations());
      assertArchivedBlackholeAccount(reader);
    }
  }

  @Test
  public void genesisCanonicalAndArchiveSurviveRestartBeforeBlockOne() throws Exception {
    BlockCapsule genesis = chainBaseManager.getGenesisBlock();
    assertEquals("genesis must not remain only in the revoking snapshot stack", 0,
        context.getBean(SnapshotManager.class).size());

    context.close();
    context = new TronApplicationContext(DefaultConfig.class);
    appT = ApplicationFactory.create(context);
    dbManager = context.getBean(Manager.class);
    chainBaseManager = context.getBean(org.tron.core.ChainBaseManager.class);
    archiveService = (DefaultArchiveService) context.getBean(ArchiveService.class);

    assertTrue(chainBaseManager.hasBlocks());
    assertTrue(chainBaseManager.containBlock(genesis.getBlockId()));
    assertTrue(chainBaseManager.getDynamicPropertiesStore()
        .isArchiveGenesisCommitComplete(genesis.getBlockId().getByteString()));
    assertEquals(0L, archiveService.getFirstArchivedBlock());
    try (ArchiveStateReader reader = archiveService.openBlockEndReader(
        0L, genesis.getBlockId().getBytes())) {
      assertTrue(reader.isGenesisComplete());
      assertArchivedLong(reader, "latest_block_header_timestamp", genesis.getTimeStamp());
    }
  }

  private static void assertArchivedLong(ArchiveStateReader reader, String key, long expected)
      throws Exception {
    ArchiveReadResult<byte[]> result =
        reader.getDynamicProperty(key.getBytes(StandardCharsets.US_ASCII));
    assertTrue("expected archived dynamic property " + key, result.isPresent());
    assertEquals(expected, ByteArray.toLong(result.getValue()));
  }

  private static void assertArchivedBytes(
      ArchiveStateReader reader, String key, byte[] expected) throws Exception {
    ArchiveReadResult<byte[]> result =
        reader.getDynamicProperty(key.getBytes(StandardCharsets.US_ASCII));
    assertTrue("expected archived dynamic property " + key, result.isPresent());
    assertArrayEquals(expected, result.getValue());
  }

  private void assertArchivedInternalBytes(String key, byte[] expected) {
    ArchiveBlockRange genesisRange = archiveService.getCommittedBlockRange(0L)
        .orElseThrow(() -> new AssertionError("missing archive genesis range"));
    DomainValue value = ArchiveServiceTestAccess.temporalStore(archiveService).getAsOf(
            ArchiveDomain.DYNAMIC_PROPERTIES, key.getBytes(StandardCharsets.US_ASCII),
            genesisRange.getLastTxNum())
        .orElseThrow(() -> new AssertionError("missing archived dynamic property " + key));
    assertFalse("expected present archived dynamic property " + key, value.isDeleted());
    assertArrayEquals(expected, value.getValue());
  }

  private void assertArchivedBlackholeAccount(ArchiveStateReader reader) throws Exception {
    AccountCapsule liveBlackhole = chainBaseManager.getAccountStore().getBlackhole();
    ArchiveReadResult<AccountCapsule> archivedBlackhole =
        reader.getAccount(liveBlackhole.getAddress().toByteArray());
    assertTrue("expected archived blackhole account", archivedBlackhole.isPresent());
    assertArrayEquals(liveBlackhole.getData(), archivedBlackhole.getValue().getData());
  }
}
