package org.tron.core.vm.config;

import static org.tron.common.math.Maths.ceil;

import lombok.extern.slf4j.Slf4j;
import org.tron.common.parameter.CommonParameter;
import org.tron.core.config.Parameter.ForkBlockVersionEnum;
import org.tron.core.store.StoreFactory;
import org.tron.core.store.VmDynamicProperties;

@Slf4j(topic = "VMConfigLoader")
public class ConfigLoader {

  //only for unit test
  public static boolean disable = false;

  // isolate=true: a constant call bound to a non-HEAD (solidity/PBFT) snapshot installs its
  // snapshot into a thread-local view instead of the process-wide global, so it cannot pollute
  // the flags the block-processing path reads concurrently.
  public static void load(StoreFactory storeFactory, boolean isolate) {
    load(storeFactory.getChainBaseManager().getDynamicPropertiesStore(), isolate);
  }

  /**
   * Load the VMConfig flags from a dynamic-properties view into a snapshot. The latest path passes
   * the live {@code DynamicPropertiesStore}; a historical archive call (L8) passes the parameters
   * in effect at the target block, so the same flags drive both paths. {@code isolate} routes the
   * result to the thread-local view (constant call) or the process-wide global (block path).
   */
  public static void load(VmDynamicProperties ds, boolean isolate) {
    if (!disable) {
      VMConfig.setVmTrace(CommonParameter.getInstance().isVmTrace());
      if (ds != null) {
        // The energy-limit hard fork is a process-wide global (not part of the thread-local
        // snapshot), so an isolated constant call must NOT write it from its (possibly historical,
        // pre-fork) block number -- that would pollute the consensus path. The constant call does
        // not read it: isConstantCall short-circuits the energy branches and the archive repository
        // routes storage through getStorageValue (never RepositoryImpl's deep-copy gate).
        if (!isolate) {
          VMConfig.initVmHardFork(isEnergyLimitForkActive(ds));
        }
        VMConfig.Snapshot snapshot = new VMConfig.Snapshot();
        snapshot.energyLimitHardFork = isEnergyLimitForkActive(ds);
        snapshot.allowMultiSign = ds.getAllowMultiSign() == 1;
        snapshot.allowTvmTransferTrc10 = ds.getAllowTvmTransferTrc10() == 1;
        snapshot.allowTvmConstantinople = ds.getAllowTvmConstantinople() == 1;
        snapshot.allowTvmSolidity059 = ds.getAllowTvmSolidity059() == 1;
        snapshot.allowShieldedTRC20Transaction = ds.getAllowShieldedTRC20Transaction() == 1;
        snapshot.allowTvmIstanbul = ds.getAllowTvmIstanbul() == 1;
        snapshot.allowTvmFreeze = ds.getAllowTvmFreeze() == 1;
        snapshot.allowTvmVote = ds.getAllowTvmVote() == 1;
        snapshot.allowTvmLondon = ds.getAllowTvmLondon() == 1;
        snapshot.allowTvmCompatibleEvm = ds.getAllowTvmCompatibleEvm() == 1;
        snapshot.allowHigherLimitForMaxCpuTimeOfOneTx =
            ds.getAllowHigherLimitForMaxCpuTimeOfOneTx() == 1;
        snapshot.allowTvmFreezeV2 = ds.supportUnfreezeDelay();
        snapshot.allowOptimizedReturnValueOfChainId =
            ds.getAllowOptimizedReturnValueOfChainId() == 1;
        snapshot.allowDynamicEnergy = ds.getAllowDynamicEnergy() == 1;
        snapshot.dynamicEnergyThreshold = ds.getDynamicEnergyThreshold();
        snapshot.dynamicEnergyIncreaseFactor = ds.getDynamicEnergyIncreaseFactor();
        snapshot.dynamicEnergyMaxFactor = ds.getDynamicEnergyMaxFactor();
        snapshot.allowTvmShanghai = ds.getAllowTvmShangHai() == 1;
        snapshot.allowEnergyAdjustment = ds.getAllowEnergyAdjustment() == 1;
        snapshot.allowStrictMath = ds.getAllowStrictMath() == 1;
        snapshot.allowTvmCancun = ds.getAllowTvmCancun() == 1;
        snapshot.disableJavaLangMath = ds.getConsensusLogicOptimization() == 1;
        snapshot.allowTvmBlob = ds.getAllowTvmBlob() == 1;
        snapshot.allowTvmSelfdestructRestriction =
            ds.getAllowTvmSelfdestructRestriction() == 1;
        snapshot.allowTvmOsaka = ds.getAllowTvmOsaka() == 1;
        snapshot.allowHardenResourceCalculation = ds.getAllowHardenResourceCalculation() == 1;
        snapshot.passFork471 = isForkActive(ds, ForkBlockVersionEnum.VERSION_4_7_1);
        snapshot.passFork4811 = isForkActive(ds, ForkBlockVersionEnum.VERSION_4_8_1_1);
        if (isolate) {
          VMConfig.setLocalSnapshot(snapshot);
        } else {
          VMConfig.setGlobalSnapshot(snapshot);
        }
      }
    }
  }

  // Mirrors ReceiptCapsule.checkForEnergyLimit over the dynamic-properties view, so the historical
  // path can resolve the energy-limit hard fork without a concrete DynamicPropertiesStore.
  private static boolean isEnergyLimitForkActive(VmDynamicProperties ds) {
    return ds.getLatestBlockHeaderNumber()
        >= CommonParameter.getInstance().getBlockNumForEnergyLimit();
  }

  private static boolean isForkActive(VmDynamicProperties ds, ForkBlockVersionEnum version) {
    long maintenanceTimeInterval = ds.getMaintenanceTimeInterval();
    if (maintenanceTimeInterval <= 0) {
      throw new IllegalArgumentException("maintenance time interval must be positive");
    }
    long hardForkTime = ((version.getHardForkTime() - 1) / maintenanceTimeInterval + 1)
        * maintenanceTimeInterval;
    if (ds.getLatestBlockHeaderTimestamp() < hardForkTime) {
      return false;
    }
    byte[] stats = ds.statsByVersion(version.getValue());
    if (stats == null || stats.length == 0) {
      return false;
    }
    int count = 0;
    for (byte stat : stats) {
      if (stat == 1) {
        count++;
      }
    }
    return count >= ceil((double) version.getHardForkRate() * stats.length / 100,
        ds.getConsensusLogicOptimization() == 1);
  }
}
