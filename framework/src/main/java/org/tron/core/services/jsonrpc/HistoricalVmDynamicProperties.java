package org.tron.core.services.jsonrpc;

import org.tron.core.store.VmDynamicProperties;

/**
 * Historical {@link VmDynamicProperties} view for an archived {@code eth_call}. Every protocol flag
 * / limit delegates to the latest store -- the hard-fork flags are near-monotonic, so for a recent
 * block latest equals historical -- but {@link #getEnergyFee()} returns the energy price in effect
 * at the target block.
 *
 * <p>That price is reconstructed by {@link HistoricalEthCallSupport} from the live
 * {@code EnergyPriceHistory}: a complete, time-keyed {@code "<activationTime>:<price>,..."} record
 * that every {@code ENERGY_FEE} proposal appends to (see {@code ProposalService}). Because the
 * history accumulates from genesis in the latest store, the historical price needs no archive read
 * and has no baseline gap -- so {@code BASEFEE} / {@code GASPRICE} and energy pricing replay with
 * the value that was actually in force then, not the current one.
 *
 * <p>{@link HistoricalArchiveVmDynamicProperties} extends this to additionally reconstruct the
 * result-affecting hard-fork flags from the archive; the flags it does not override keep this
 * class's latest-store baseline.
 */
class HistoricalVmDynamicProperties implements VmDynamicProperties {

  private static final long DEFAULT_ENERGY_FEE = 100L;

  private final VmDynamicProperties latest;
  private final long energyFee;

  HistoricalVmDynamicProperties(VmDynamicProperties latest, long energyFee) {
    this.latest = latest;
    this.energyFee = energyFee;
  }

  @Override
  public long getEnergyFee() {
    return energyFee;
  }

  static long resolveHistoricalEnergyFee(long timestamp, String energyPriceHistory) {
    long historicalEnergyFee = JsonRpcApiUtil.parseEnergyFee(timestamp, energyPriceHistory);
    return historicalEnergyFee == -1 ? DEFAULT_ENERGY_FEE : historicalEnergyFee;
  }

  // ---- Everything below is the latest-store baseline (documented above). ----

  @Override
  public long getLatestBlockHeaderNumber() {
    return latest.getLatestBlockHeaderNumber();
  }

  @Override
  public long getCurrentCycleNumber() {
    return latest.getCurrentCycleNumber();
  }

  @Override
  public long getTotalNetLimit() {
    return latest.getTotalNetLimit();
  }

  @Override
  public long getTotalNetWeight() {
    return latest.getTotalNetWeight();
  }

  @Override
  public long getTotalEnergyCurrentLimit() {
    return latest.getTotalEnergyCurrentLimit();
  }

  @Override
  public long getTotalEnergyWeight() {
    return latest.getTotalEnergyWeight();
  }

  @Override
  public long getTotalTronPowerWeight() {
    return latest.getTotalTronPowerWeight();
  }

  @Override
  public boolean supportVM() {
    return latest.supportVM();
  }

  @Override
  public boolean supportUnfreezeDelay() {
    return latest.supportUnfreezeDelay();
  }

  @Override
  public long getUnfreezeDelayDays() {
    return latest.getUnfreezeDelayDays();
  }

  @Override
  public long getAllowNewResourceModel() {
    return latest.getAllowNewResourceModel();
  }

  @Override
  public boolean supportAllowNewResourceModel() {
    return latest.supportAllowNewResourceModel();
  }

  @Override
  public long getMaxFeeLimit() {
    return latest.getMaxFeeLimit();
  }

  @Override
  public long getMaxCpuTimeOfOneTx() {
    return latest.getMaxCpuTimeOfOneTx();
  }

  @Override
  public long getAllowMultiSign() {
    return latest.getAllowMultiSign();
  }

  @Override
  public long getAllowTvmTransferTrc10() {
    return latest.getAllowTvmTransferTrc10();
  }

  @Override
  public long getAllowTvmConstantinople() {
    return latest.getAllowTvmConstantinople();
  }

  @Override
  public long getAllowTvmSolidity059() {
    return latest.getAllowTvmSolidity059();
  }

  @Override
  public long getAllowShieldedTRC20Transaction() {
    return latest.getAllowShieldedTRC20Transaction();
  }

  @Override
  public long getAllowTvmIstanbul() {
    return latest.getAllowTvmIstanbul();
  }

  @Override
  public long getAllowTvmFreeze() {
    return latest.getAllowTvmFreeze();
  }

  @Override
  public long getAllowTvmVote() {
    return latest.getAllowTvmVote();
  }

  @Override
  public long getAllowTvmLondon() {
    return latest.getAllowTvmLondon();
  }

  @Override
  public long getAllowTvmCompatibleEvm() {
    return latest.getAllowTvmCompatibleEvm();
  }

  @Override
  public long getAllowHigherLimitForMaxCpuTimeOfOneTx() {
    return latest.getAllowHigherLimitForMaxCpuTimeOfOneTx();
  }

  @Override
  public long getAllowOptimizedReturnValueOfChainId() {
    return latest.getAllowOptimizedReturnValueOfChainId();
  }

  @Override
  public long getAllowDynamicEnergy() {
    return latest.getAllowDynamicEnergy();
  }

  @Override
  public long getDynamicEnergyThreshold() {
    return latest.getDynamicEnergyThreshold();
  }

  @Override
  public long getDynamicEnergyIncreaseFactor() {
    return latest.getDynamicEnergyIncreaseFactor();
  }

  @Override
  public long getDynamicEnergyMaxFactor() {
    return latest.getDynamicEnergyMaxFactor();
  }

  @Override
  public long getAllowTvmShangHai() {
    return latest.getAllowTvmShangHai();
  }

  @Override
  public long getAllowEnergyAdjustment() {
    return latest.getAllowEnergyAdjustment();
  }

  @Override
  public long getAllowStrictMath() {
    return latest.getAllowStrictMath();
  }

  @Override
  public long getAllowTvmCancun() {
    return latest.getAllowTvmCancun();
  }

  @Override
  public long getConsensusLogicOptimization() {
    return latest.getConsensusLogicOptimization();
  }

  @Override
  public long getAllowTvmBlob() {
    return latest.getAllowTvmBlob();
  }

  @Override
  public long getAllowTvmSelfdestructRestriction() {
    return latest.getAllowTvmSelfdestructRestriction();
  }

  @Override
  public long getAllowTvmOsaka() {
    return latest.getAllowTvmOsaka();
  }

  @Override
  public long getAllowHardenResourceCalculation() {
    return latest.getAllowHardenResourceCalculation();
  }
}
