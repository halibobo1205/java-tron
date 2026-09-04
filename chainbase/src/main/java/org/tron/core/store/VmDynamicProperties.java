package org.tron.core.store;

/**
 * Read-only view of the dynamic properties the TVM consults during execution: the hard-fork /
 * proposal flags loaded into {@code VMConfig} plus the fee / limit parameters used to bound a call.
 *
 * <p>{@link DynamicPropertiesStore} implements this directly (latest state). A historical archive
 * call (L8) supplies an alternative implementation backed by the values archived at the target
 * block, so the VM sees the protocol parameters in effect then rather than the current ones.
 * Keeping this a narrow interface, instead of passing the concrete {@code DynamicPropertiesStore},
 * is what lets the archive path substitute a historical source without touching the live store.
 *
 * <p>Every getter mirrors a {@code DynamicPropertiesStore} method exactly (name + return type), so
 * the latest path is unchanged: {@code DynamicPropertiesStore} simply {@code implements} this.
 */
public interface VmDynamicProperties {

  long getLatestBlockHeaderNumber();

  long getLatestBlockHeaderTimestamp();

  long getMaintenanceTimeInterval();

  byte[] statsByVersion(int version);

  long getCurrentCycleNumber();

  long getTotalNetLimit();

  long getTotalNetWeight();

  long getTotalEnergyCurrentLimit();

  long getTotalEnergyWeight();

  long getTotalTronPowerWeight();

  boolean supportVM();

  boolean supportUnfreezeDelay();

  long getUnfreezeDelayDays();

  long getAllowNewResourceModel();

  boolean supportAllowNewResourceModel();

  default boolean supportAllowCancelAllUnfreezeV2() {
    return false;
  }

  default long getAllowNewReward() {
    return 0L;
  }

  long getEnergyFee();

  long getMaxFeeLimit();

  long getMaxCpuTimeOfOneTx();

  long getAllowMultiSign();

  long getAllowTvmTransferTrc10();

  long getAllowTvmConstantinople();

  long getAllowTvmSolidity059();

  long getAllowShieldedTRC20Transaction();

  long getAllowTvmIstanbul();

  long getAllowTvmFreeze();

  long getAllowTvmVote();

  long getAllowTvmLondon();

  long getAllowTvmCompatibleEvm();

  long getAllowHigherLimitForMaxCpuTimeOfOneTx();

  long getAllowOptimizedReturnValueOfChainId();

  long getAllowDynamicEnergy();

  long getDynamicEnergyThreshold();

  long getDynamicEnergyIncreaseFactor();

  long getDynamicEnergyMaxFactor();

  long getAllowTvmShangHai();

  long getAllowEnergyAdjustment();

  long getAllowStrictMath();

  long getAllowTvmCancun();

  long getConsensusLogicOptimization();

  long getAllowTvmBlob();

  long getAllowTvmSelfdestructRestriction();

  long getAllowTvmOsaka();

  long getAllowHardenResourceCalculation();
}
