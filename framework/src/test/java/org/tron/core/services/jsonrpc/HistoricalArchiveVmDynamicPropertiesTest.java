package org.tron.core.services.jsonrpc;

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
    reader.put("CURRENT_CYCLE_NUMBER", 7L);
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
    VmDynamicProperties latest = mock(VmDynamicProperties.class);
    when(latest.getCurrentCycleNumber()).thenReturn(99L);
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

    HistoricalArchiveVmDynamicProperties view =
        new HistoricalArchiveVmDynamicProperties(latest, ENERGY_FEE, reader, true);

    assertEquals(ENERGY_FEE, view.getEnergyFee());      // inherited historical fee
    assertEquals(FakeReader.BLOCK_NUM, view.getLatestBlockHeaderNumber());
    assertEquals(7L, view.getCurrentCycleNumber());
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
