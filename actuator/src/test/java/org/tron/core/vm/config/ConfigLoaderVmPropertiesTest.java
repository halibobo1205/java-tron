package org.tron.core.vm.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.tron.core.store.VmDynamicProperties;

/**
 * Proves {@link ConfigLoader#load(VmDynamicProperties)} drives every VMConfig flag from the
 * supplied dynamic-properties view (not a hard-coded latest store). This is what lets a historical
 * archive call load the protocol parameters in effect at the target block; the latest path is
 * unchanged because {@code load(StoreFactory)} now delegates here with the live store.
 */
public class ConfigLoaderVmPropertiesTest {

  /** A VmDynamicProperties whose every long getter returns {@code v}, boolean getter {@code b}. */
  private static final class StubProps implements VmDynamicProperties {
    private final long v;
    private final boolean b;

    StubProps(long v, boolean b) {
      this.v = v;
      this.b = b;
    }

    public long getLatestBlockHeaderNumber() {
      return v;
    }

    public boolean supportVM() {
      return b;
    }

    public boolean supportUnfreezeDelay() {
      return b;
    }

    public long getEnergyFee() {
      return v;
    }

    public long getMaxFeeLimit() {
      return v;
    }

    public long getMaxCpuTimeOfOneTx() {
      return v;
    }

    public long getAllowMultiSign() {
      return v;
    }

    public long getAllowTvmTransferTrc10() {
      return v;
    }

    public long getAllowTvmConstantinople() {
      return v;
    }

    public long getAllowTvmSolidity059() {
      return v;
    }

    public long getAllowShieldedTRC20Transaction() {
      return v;
    }

    public long getAllowTvmIstanbul() {
      return v;
    }

    public long getAllowTvmFreeze() {
      return v;
    }

    public long getAllowTvmVote() {
      return v;
    }

    public long getAllowTvmLondon() {
      return v;
    }

    public long getAllowTvmCompatibleEvm() {
      return v;
    }

    public long getAllowHigherLimitForMaxCpuTimeOfOneTx() {
      return v;
    }

    public long getAllowOptimizedReturnValueOfChainId() {
      return v;
    }

    public long getAllowDynamicEnergy() {
      return v;
    }

    public long getDynamicEnergyThreshold() {
      return v;
    }

    public long getDynamicEnergyIncreaseFactor() {
      return v;
    }

    public long getDynamicEnergyMaxFactor() {
      return v;
    }

    public long getAllowTvmShangHai() {
      return v;
    }

    public long getAllowEnergyAdjustment() {
      return v;
    }

    public long getAllowStrictMath() {
      return v;
    }

    public long getAllowTvmCancun() {
      return v;
    }

    public long getConsensusLogicOptimization() {
      return v;
    }

    public long getAllowTvmBlob() {
      return v;
    }

    public long getAllowTvmSelfdestructRestriction() {
      return v;
    }

    public long getAllowTvmOsaka() {
      return v;
    }

    public long getAllowHardenResourceCalculation() {
      return v;
    }
  }

  @Test
  public void loadFromVmPropertiesDrivesEachFlagBothWays() {
    boolean prevDisable = ConfigLoader.disable;
    ConfigLoader.disable = false;
    try {
      // Active view: every flag turns on, the numeric threshold takes the supplied value.
      // isolate=false installs the global snapshot, which the VMConfig getters read by default.
      ConfigLoader.load(new StubProps(1L, true), false);
      assertTrue(VMConfig.allowTvmLondon());
      assertTrue(VMConfig.allowTvmCancun());
      assertTrue(VMConfig.allowStrictMath());
      assertTrue(VMConfig.allowTvmTransferTrc10());
      assertTrue(VMConfig.allowTvmFreezeV2()); // from supportUnfreezeDelay()
      assertEquals(1L, VMConfig.getDynamicEnergyThreshold());

      // Inactive view: the same flags turn off, proving the load is data-driven, not constant.
      ConfigLoader.load(new StubProps(0L, false), false);
      assertFalse(VMConfig.allowTvmLondon());
      assertFalse(VMConfig.allowTvmCancun());
      assertFalse(VMConfig.allowStrictMath());
      assertFalse(VMConfig.allowTvmTransferTrc10());
      assertFalse(VMConfig.allowTvmFreezeV2());
      assertEquals(0L, VMConfig.getDynamicEnergyThreshold());
    } finally {
      ConfigLoader.disable = prevDisable;
    }
  }
}
