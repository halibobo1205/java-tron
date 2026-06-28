package org.tron.core.services.jsonrpc;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.tron.common.utils.ByteArray;
import org.tron.core.archive.reader.ArchiveReadResult;
import org.tron.core.archive.reader.ArchiveStatePoint;
import org.tron.core.archive.reader.ArchiveStateReader;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.ContractCapsule;
import org.tron.core.store.VmDynamicProperties;

/**
 * The archive-backed config view reconstructs the result-affecting hard-fork flags at the target
 * block: an archived (proposal-written) value wins; a MISSING value is the in-memory default when
 * the archive covers genesis, and degrades to the latest baseline only for a mid-chain archive.
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
  public void missingFlagFallsBackToLatestWhenMidChain() throws Exception {
    // A mid-chain archive cannot prove a pre-coverage activation did not happen, so MISSING
    // degrades to the latest baseline (correct for the long-activated flags that dominate it).
    FakeReader reader = new FakeReader();                // MISSING
    VmDynamicProperties latest = mock(VmDynamicProperties.class);
    when(latest.getAllowTvmOsaka()).thenReturn(1L);

    HistoricalArchiveVmDynamicProperties view =
        new HistoricalArchiveVmDynamicProperties(latest, ENERGY_FEE, reader, false);

    assertEquals(1L, view.getAllowTvmOsaka());
  }

  @Test
  public void energyFeeIsHistoricalAndUnreconstructedParamsDelegate() throws Exception {
    FakeReader reader = new FakeReader();
    VmDynamicProperties latest = mock(VmDynamicProperties.class);
    when(latest.getAllowStrictMath()).thenReturn(1L);

    HistoricalArchiveVmDynamicProperties view =
        new HistoricalArchiveVmDynamicProperties(latest, ENERGY_FEE, reader, true);

    assertEquals(ENERGY_FEE, view.getEnergyFee());      // inherited historical fee
    // strict-math does not change a constant-call result, so it is NOT reconstructed -> latest.
    assertEquals(1L, view.getAllowStrictMath());
  }

  @Test
  public void freezeV2OpcodeGateReconstructsFromUnfreezeDelayDays() throws Exception {
    // allowTvmFreezeV2 = (UNFREEZE_DELAY_DAYS > 0) gates FREEZEBALANCEV2 / DELEGATERESOURCE. The
    // key is unrooted but kept in FULL_HISTORY, so it must reconstruct, not silently use latest.
    FakeReader present = new FakeReader();
    present.put("UNFREEZE_DELAY_DAYS", 14L);            // activated as of this block
    VmDynamicProperties latestOff = mock(VmDynamicProperties.class);
    when(latestOff.supportUnfreezeDelay()).thenReturn(false);
    assertEquals(true,
        new HistoricalArchiveVmDynamicProperties(latestOff, ENERGY_FEE, present, true)
            .supportUnfreezeDelay());

    // Absent on a mid-chain archive -> latest fallback (cannot prove a pre-coverage activation).
    FakeReader missing = new FakeReader();
    VmDynamicProperties latestOn = mock(VmDynamicProperties.class);
    when(latestOn.supportUnfreezeDelay()).thenReturn(true);
    assertEquals(true,
        new HistoricalArchiveVmDynamicProperties(latestOn, ENERGY_FEE, missing, false)
            .supportUnfreezeDelay());
  }

  /** Serves configured DYNAMIC_PROPERTIES values by key; everything else MISSING. */
  private static final class FakeReader implements ArchiveStateReader {
    private final Map<String, byte[]> props = new HashMap<>();

    void put(String key, long value) {
      props.put(key, ByteArray.fromLong(value));
    }

    public ArchiveReadResult<byte[]> getDynamicProperty(byte[] key) {
      byte[] value = props.get(new String(key, StandardCharsets.US_ASCII));
      return value == null ? ArchiveReadResult.missing() : ArchiveReadResult.present(value);
    }

    public ArchiveStatePoint getPoint() {
      return null;
    }

    public ArchiveReadResult<AccountCapsule> getAccount(byte[] address) {
      return ArchiveReadResult.missing();
    }

    public ArchiveReadResult<ContractCapsule> getContract(byte[] address) {
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
