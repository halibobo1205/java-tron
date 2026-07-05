package org.tron.core.services.jsonrpc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.utils.ByteArray;
import org.tron.core.archive.reader.ArchiveReadResult;
import org.tron.core.archive.reader.ArchiveReaderException;
import org.tron.core.archive.reader.ArchiveStatePoint;
import org.tron.core.archive.reader.ArchiveStateReader;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.ContractCapsule;
import org.tron.core.capsule.ContractStateCapsule;
import org.tron.core.store.VmDynamicProperties;

/**
 * The archive-backed config view reconstructs the result-affecting hard-fork flags at the target
 * block: an archived (proposal-written) value wins; a MISSING value is the in-memory default when
 * the archive covers genesis, and fails closed for a mid-chain archive.
 */
public class HistoricalArchiveVmDynamicPropertiesTest {

  private static final long ENERGY_FEE = 140L;

  @Test
  public void archivedFlagValueOverridesLatest() throws Exception {
    FakeReader reader = new FakeReader();
    reader.put("ALLOW_TVM_OSAKA", 1L);                 // proposal activated it as of this block
    VmDynamicProperties latest = mock(VmDynamicProperties.class);
    when(latest.getAllowTvmOsaka()).thenReturn(0L);    // latest disagrees -> archive must win

    HistoricalArchiveVmDynamicProperties view =
        new HistoricalArchiveVmDynamicProperties(latest, ENERGY_FEE, reader, true);

    assertEquals(1L, view.getAllowTvmOsaka());
  }

  @Test
  public void missingFlagUsesInMemoryDefaultWhenGenesisComplete() throws Exception {
    // Osaka's live default is a hard-coded 0L. A genesis-complete archive treats MISSING as that
    // default -- NOT the latest value -- the whole point: a block before activation reads 0.
    FakeReader reader = new FakeReader();                // ALLOW_TVM_OSAKA absent -> MISSING
    VmDynamicProperties latest = mock(VmDynamicProperties.class);
    when(latest.getAllowTvmOsaka()).thenReturn(1L);      // latest ON; historical block is not

    HistoricalArchiveVmDynamicProperties view =
        new HistoricalArchiveVmDynamicProperties(latest, ENERGY_FEE, reader, true);

    assertEquals(0L, view.getAllowTvmOsaka());
  }

  @Test
  public void missingFlagFailsClosedWhenMidChain() {
    // A mid-chain archive cannot prove whether a missing key changed before coverage or after the
    // queried block, so it must not use latest as a historical value.
    FakeReader reader = new FakeReader();                // MISSING
    VmDynamicProperties latest = mock(VmDynamicProperties.class);
    when(latest.getAllowTvmOsaka()).thenReturn(1L);

    assertThrows(ArchiveReaderException.class,
        () -> new HistoricalArchiveVmDynamicProperties(latest, ENERGY_FEE, reader, false));
  }

  @Test
  public void malformedFlagValueFailsClosed() {
    FakeReader reader = new FakeReader();
    reader.putRaw("ALLOW_TVM_OSAKA", new byte[] {1});
    VmDynamicProperties latest = mock(VmDynamicProperties.class);

    ArchiveReaderException e = assertThrows(ArchiveReaderException.class,
        () -> new HistoricalArchiveVmDynamicProperties(latest, ENERGY_FEE, reader, true));
    assertEquals(ArchiveReaderException.Reason.CORRUPT_VALUE, e.getReason());
  }

  @Test
  public void tombstonedFlagValueFailsClosed() {
    FakeReader reader = new FakeReader();
    reader.putTombstone("ALLOW_TVM_OSAKA");
    VmDynamicProperties latest = mock(VmDynamicProperties.class);

    ArchiveReaderException e = assertThrows(ArchiveReaderException.class,
        () -> new HistoricalArchiveVmDynamicProperties(latest, ENERGY_FEE, reader, true));
    assertEquals(ArchiveReaderException.Reason.CORRUPT_VALUE, e.getReason());
  }

  @Test
  public void energyFeeIsHistoricalAndExecutionParamsReconstruct() throws Exception {
    FakeReader reader = new FakeReader();
    reader.put("latest_block_header_timestamp", 456L);
    reader.put("MAINTENANCE_TIME_INTERVAL", 1_000L);
    reader.put("CURRENT_CYCLE_NUMBER", 7L);
    reader.put("TOTAL_NET_LIMIT", 101L);
    reader.put("TOTAL_NET_WEIGHT", 102L);
    reader.put("TOTAL_ENERGY_CURRENT_LIMIT", 103L);
    reader.put("TOTAL_ENERGY_WEIGHT", 104L);
    reader.put("TOTAL_TRON_POWER_WEIGHT", 105L);
    reader.put("ALLOW_CREATION_OF_CONTRACTS", 0L);
    reader.put("MAX_FEE_LIMIT", 100L);
    reader.put("MAX_CPU_TIME_OF_ONE_TX", 25L);
    reader.put("ALLOW_HIGHER_LIMIT_FOR_MAX_CPU_TIME_OF_ONE_TX", 0L);
    reader.put("ALLOW_DYNAMIC_ENERGY", 0L);
    reader.put("DYNAMIC_ENERGY_THRESHOLD", 11L);
    reader.put("DYNAMIC_ENERGY_INCREASE_FACTOR", 12L);
    reader.put("DYNAMIC_ENERGY_MAX_FACTOR", 13L);
    reader.put("ALLOW_ENERGY_ADJUSTMENT", 0L);
    reader.put("ALLOW_STRICT_MATH", 0L);
    reader.put("CONSENSUS_LOGIC_OPTIMIZATION", 0L);
    reader.put("ALLOW_HARDEN_RESOURCE_CALCULATION", 0L);
    reader.put("ALLOW_NEW_RESOURCE_MODEL", 1L);
    VmDynamicProperties latest = mock(VmDynamicProperties.class);
    when(latest.getCurrentCycleNumber()).thenReturn(99L);
    when(latest.getTotalNetLimit()).thenReturn(201L);
    when(latest.getTotalNetWeight()).thenReturn(202L);
    when(latest.getTotalEnergyCurrentLimit()).thenReturn(203L);
    when(latest.getTotalEnergyWeight()).thenReturn(204L);
    when(latest.getTotalTronPowerWeight()).thenReturn(205L);
    when(latest.supportVM()).thenReturn(true);
    when(latest.getMaxFeeLimit()).thenReturn(1_000L);
    when(latest.getMaxCpuTimeOfOneTx()).thenReturn(50L);
    when(latest.getAllowHigherLimitForMaxCpuTimeOfOneTx()).thenReturn(1L);
    when(latest.getAllowDynamicEnergy()).thenReturn(1L);
    when(latest.getDynamicEnergyThreshold()).thenReturn(21L);
    when(latest.getDynamicEnergyIncreaseFactor()).thenReturn(22L);
    when(latest.getDynamicEnergyMaxFactor()).thenReturn(23L);
    when(latest.getAllowEnergyAdjustment()).thenReturn(1L);
    when(latest.getAllowStrictMath()).thenReturn(1L);
    when(latest.getConsensusLogicOptimization()).thenReturn(1L);
    when(latest.getAllowHardenResourceCalculation()).thenReturn(1L);
    when(latest.getAllowNewResourceModel()).thenReturn(0L);

    HistoricalArchiveVmDynamicProperties view =
        new HistoricalArchiveVmDynamicProperties(latest, ENERGY_FEE, reader, true);

    assertEquals(ENERGY_FEE, view.getEnergyFee());      // inherited historical fee
    assertEquals(FakeReader.BLOCK_NUM, view.getLatestBlockHeaderNumber());
    assertEquals(456L, view.getLatestBlockHeaderTimestamp());
    assertEquals(1_000L, view.getMaintenanceTimeInterval());
    assertEquals(7L, view.getCurrentCycleNumber());
    assertEquals(101L, view.getTotalNetLimit());
    assertEquals(102L, view.getTotalNetWeight());
    assertEquals(103L, view.getTotalEnergyCurrentLimit());
    assertEquals(104L, view.getTotalEnergyWeight());
    assertEquals(105L, view.getTotalTronPowerWeight());
    assertEquals(false, view.supportVM());
    assertEquals(100L, view.getMaxFeeLimit());
    assertEquals(25L, view.getMaxCpuTimeOfOneTx());
    assertEquals(0L, view.getAllowHigherLimitForMaxCpuTimeOfOneTx());
    assertEquals(0L, view.getAllowDynamicEnergy());
    assertEquals(11L, view.getDynamicEnergyThreshold());
    assertEquals(12L, view.getDynamicEnergyIncreaseFactor());
    assertEquals(13L, view.getDynamicEnergyMaxFactor());
    assertEquals(0L, view.getAllowEnergyAdjustment());
    assertEquals(0L, view.getAllowStrictMath());
    assertEquals(0L, view.getConsensusLogicOptimization());
    assertEquals(0L, view.getAllowHardenResourceCalculation());
    assertEquals(1L, view.getAllowNewResourceModel());
    assertEquals(true, view.supportAllowNewResourceModel());
  }

  @Test
  public void energyFeeResolvesFromArchivedDynamicProperty() throws Exception {
    FakeReader reader = new FakeReader();
    reader.put("ENERGY_FEE", 420L);

    assertEquals(420L, HistoricalArchiveVmDynamicProperties.resolveEnergyFee(reader, true));
    assertEquals(420L, HistoricalArchiveVmDynamicProperties.resolveEnergyFee(reader, false));
  }

  @Test
  public void missingEnergyFeeUsesDefaultOnlyWhenGenesisComplete() throws Exception {
    FakeReader reader = new FakeReader();

    assertEquals(100L, HistoricalArchiveVmDynamicProperties.resolveEnergyFee(reader, true));

    ArchiveReaderException e = assertThrows(ArchiveReaderException.class,
        () -> HistoricalArchiveVmDynamicProperties.resolveEnergyFee(reader, false));
    assertEquals(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE, e.getReason());
  }

  @Test
  public void badEnergyFeeFailsClosed() {
    FakeReader malformed = new FakeReader();
    malformed.putRaw("ENERGY_FEE", new byte[] {1});
    ArchiveReaderException malformedError = assertThrows(ArchiveReaderException.class,
        () -> HistoricalArchiveVmDynamicProperties.resolveEnergyFee(malformed, true));
    assertEquals(ArchiveReaderException.Reason.CORRUPT_VALUE, malformedError.getReason());

    FakeReader tombstone = new FakeReader();
    tombstone.putTombstone("ENERGY_FEE");
    ArchiveReaderException tombstoneError = assertThrows(ArchiveReaderException.class,
        () -> HistoricalArchiveVmDynamicProperties.resolveEnergyFee(tombstone, true));
    assertEquals(ArchiveReaderException.Reason.CORRUPT_VALUE, tombstoneError.getReason());
  }

  @Test
  public void missingExecutionParamsUseDefaultsOrLatestByCoverage() throws Exception {
    FakeReader reader = new FakeReader();
    VmDynamicProperties latest = mock(VmDynamicProperties.class);
    when(latest.supportVM()).thenReturn(true);
    when(latest.getMaxFeeLimit()).thenReturn(123L);
    when(latest.getAllowStrictMath()).thenReturn(1L);

    HistoricalArchiveVmDynamicProperties genesisComplete =
        new HistoricalArchiveVmDynamicProperties(latest, ENERGY_FEE, reader, true);
    CommonParameter p = CommonParameter.getInstance();
    assertEquals(p.getAllowCreationOfContracts() == 1L, genesisComplete.supportVM());
    assertEquals(43_200_000_000L, genesisComplete.getTotalNetLimit());
    assertEquals(0L, genesisComplete.getTotalNetWeight());
    assertEquals(50_000_000_000L, genesisComplete.getTotalEnergyCurrentLimit());
    assertEquals(0L, genesisComplete.getTotalEnergyWeight());
    assertEquals(0L, genesisComplete.getTotalTronPowerWeight());
    assertEquals(1_000_000_000L, genesisComplete.getMaxFeeLimit());
    assertEquals(p.getAllowStrictMath(), genesisComplete.getAllowStrictMath());

    assertThrows(ArchiveReaderException.class,
        () -> new HistoricalArchiveVmDynamicProperties(latest, ENERGY_FEE, reader, false));
  }

  @Test
  public void genesisCompleteMissingValuesDoNotReadLatest() throws Exception {
    FakeReader reader = new FakeReader();
    VmDynamicProperties latest = mock(VmDynamicProperties.class, invocation -> {
      throw new AssertionError("latest must not be read for genesis-complete archive defaults");
    });

    HistoricalArchiveVmDynamicProperties view =
        new HistoricalArchiveVmDynamicProperties(latest, ENERGY_FEE, reader, true);

    assertEquals(FakeReader.BLOCK_NUM, view.getLatestBlockHeaderNumber());
    assertEquals(1_000_000_000L, view.getMaxFeeLimit());
    assertEquals(ENERGY_FEE, view.getEnergyFee());
  }

  @Test
  public void freezeV2OpcodeGateReconstructsFromUnfreezeDelayDays() throws Exception {
    // allowTvmFreezeV2 = (UNFREEZE_DELAY_DAYS > 0) gates FREEZEBALANCEV2 / DELEGATERESOURCE, so it
    // must reconstruct from the archive at block N, not silently use the latest activation.
    FakeReader present = new FakeReader();
    present.put("UNFREEZE_DELAY_DAYS", 14L);            // activated as of this block
    VmDynamicProperties latestOff = mock(VmDynamicProperties.class);
    when(latestOff.supportUnfreezeDelay()).thenReturn(false);
    assertEquals(true,
        new HistoricalArchiveVmDynamicProperties(latestOff, ENERGY_FEE, present, true)
            .supportUnfreezeDelay());

    // Absent on a mid-chain archive is unsafe: it may be a pre-coverage activation or a future one.
    FakeReader missing = new FakeReader();
    VmDynamicProperties latestOn = mock(VmDynamicProperties.class);
    when(latestOn.supportUnfreezeDelay()).thenReturn(true);
    assertThrows(ArchiveReaderException.class,
        () -> new HistoricalArchiveVmDynamicProperties(latestOn, ENERGY_FEE, missing, false));
  }

  @Test
  public void forkStatsReconstructFromArchiveRows() throws Exception {
    FakeReader reader = new FakeReader();
    byte[] fork471 = new byte[] {1, 1, 0};
    byte[] fork4811 = new byte[] {1, 1, 1};
    reader.putRaw("FORK_VERSION_27", fork471);
    reader.putRaw("FORK_VERSION_35", fork4811);
    VmDynamicProperties latest = mock(VmDynamicProperties.class);

    HistoricalArchiveVmDynamicProperties view =
        new HistoricalArchiveVmDynamicProperties(latest, ENERGY_FEE, reader, true);

    assertArrayEquals(fork471, view.statsByVersion(27));
    assertArrayEquals(fork4811, view.statsByVersion(35));
    fork471[0] = 0;
    assertEquals(1, view.statsByVersion(27)[0]);
  }

  @Test
  public void tombstonedForkStatsFailClosed() {
    FakeReader reader = new FakeReader();
    reader.putTombstone("FORK_VERSION_35");
    VmDynamicProperties latest = mock(VmDynamicProperties.class);

    ArchiveReaderException e = assertThrows(ArchiveReaderException.class,
        () -> new HistoricalArchiveVmDynamicProperties(latest, ENERGY_FEE, reader, true));
    assertEquals(ArchiveReaderException.Reason.CORRUPT_VALUE, e.getReason());
  }

  /** Serves configured DYNAMIC_PROPERTIES values by key; everything else MISSING. */
  private static final class FakeReader implements ArchiveStateReader {
    private static final long BLOCK_NUM = 123L;

    private final Map<String, ArchiveReadResult<byte[]>> props = new HashMap<>();
    private final ArchiveStatePoint point = ArchiveStatePoint.blockEnd(BLOCK_NUM, new byte[32], 0);

    void put(String key, long value) {
      putRaw(key, ByteArray.fromLong(value));
    }

    void putRaw(String key, byte[] value) {
      props.put(key, ArchiveReadResult.present(value));
    }

    void putTombstone(String key) {
      props.put(key, ArchiveReadResult.tombstone());
    }

    public ArchiveReadResult<byte[]> getDynamicProperty(byte[] key) {
      ArchiveReadResult<byte[]> value = props.get(new String(key, StandardCharsets.US_ASCII));
      return value == null ? ArchiveReadResult.missing() : value;
    }

    public ArchiveStatePoint getPoint() {
      return point;
    }

    public ArchiveReadResult<AccountCapsule> getAccount(byte[] address) {
      return ArchiveReadResult.missing();
    }

    public ArchiveReadResult<byte[]> getAccountAsset(byte[] address, byte[] assetId) {
      return ArchiveReadResult.missing();
    }

    public ArchiveReadResult<ContractCapsule> getContract(byte[] address) {
      return ArchiveReadResult.missing();
    }

    public ArchiveReadResult<ContractStateCapsule> getContractState(byte[] address) {
      return ArchiveReadResult.missing();
    }

    public ArchiveReadResult<byte[]> getCode(byte[] address) {
      return ArchiveReadResult.missing();
    }

    public ArchiveReadResult<byte[]> getStorage(byte[] address, byte[] slot) {
      return ArchiveReadResult.missing();
    }

    public void close() {
    }
  }
}
