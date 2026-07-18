package org.tron.common.runtime.vm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.tron.core.vm.config.VMConfig;

public class VMConfigIsolationTest {

  // Tests mutate the process-wide static VMConfig; snapshot it before each test and restore it
  // after so this class never pollutes other VM tests sharing the same JVM fork (forkEvery=100).
  private VMConfig.Snapshot savedGlobal;

  @Before
  public void snapshotConfig() {
    VMConfig.clearLocalSnapshot();
    savedGlobal = snapshotGlobal();
  }

  @After
  public void restoreConfig() {
    VMConfig.clearLocalSnapshot();
    VMConfig.setGlobalSnapshot(savedGlobal);
  }

  /**
   * A constant call's thread-local config view must not pollute the global config that the
   * (concurrent) block-processing path reads. This is the core Problem-2 guarantee.
   */
  @Test
  public void testLocalConfigDoesNotPolluteGlobalAcrossThreads() throws InterruptedException {
    VMConfig.initAllowTvmOsaka(1);            // global (HEAD) view: activated
    VMConfig.initVmHardFork(true);
    VMConfig.initPassFork4811(true);
    assertTrue(VMConfig.allowTvmOsaka());     // no thread-local -> reads global
    assertTrue(VMConfig.getEnergyLimitHardFork());
    assertTrue(VMConfig.passFork4811());

    VMConfig.Snapshot local = new VMConfig.Snapshot();
    local.allowTvmOsaka = false;              // simulate a not-yet-solidified snapshot
    local.energyLimitHardFork = false;
    local.passFork4811 = false;
    VMConfig.setLocalSnapshot(local);

    // this thread now sees its own (solidity) view...
    assertFalse(VMConfig.allowTvmOsaka());
    assertFalse(VMConfig.getEnergyLimitHardFork());
    assertFalse(VMConfig.passFork4811());

    // ...but another thread (e.g. block processing) must still see the global HEAD value.
    AtomicBoolean otherThreadSaw = new AtomicBoolean(false);
    AtomicBoolean otherThreadSawFork = new AtomicBoolean(false);
    Thread t = new Thread(() -> {
      otherThreadSaw.set(VMConfig.allowTvmOsaka());
      otherThreadSawFork.set(VMConfig.getEnergyLimitHardFork() && VMConfig.passFork4811());
    });
    t.start();
    t.join();
    assertTrue("global config must be unaffected by another thread's local view",
        otherThreadSaw.get());
    assertTrue("global fork config must be unaffected by another thread's local view",
        otherThreadSawFork.get());

    // after dropping the local view, this thread falls back to the global value again.
    VMConfig.clearLocalSnapshot();
    assertTrue(VMConfig.allowTvmOsaka());
  }

  /**
   * A block/broadcast load (setGlobalSnapshot) must drop any thread-local view, so the consensus
   * path can never read a constant call's leaked snapshot left on the same pooled worker thread.
   */
  @Test
  public void testSetGlobalConfigDropsLocalView() {
    VMConfig.Snapshot local = new VMConfig.Snapshot();
    local.allowTvmOsaka = true;
    VMConfig.setLocalSnapshot(local);
    assertTrue(VMConfig.allowTvmOsaka());

    VMConfig.Snapshot head = new VMConfig.Snapshot();
    head.allowTvmOsaka = false;
    head.energyLimitHardFork = false;
    head.passFork4811 = false;
    VMConfig.setGlobalSnapshot(head);
    assertFalse("setGlobalSnapshot must drop the thread-local view", VMConfig.allowTvmOsaka());
    assertFalse("setGlobalSnapshot must drop the energy-limit thread-local view",
        VMConfig.getEnergyLimitHardFork());
    assertFalse("setGlobalSnapshot must drop the fork thread-local view",
        VMConfig.passFork4811());
  }

  @Test
  public void nestedLocalConfigScopeRestoresOuterView() {
    VMConfig.Snapshot head = new VMConfig.Snapshot();
    head.allowTvmOsaka = false;
    VMConfig.setGlobalSnapshot(head);
    VMConfig.Snapshot outer = new VMConfig.Snapshot();
    outer.allowTvmOsaka = true;
    VMConfig.setLocalSnapshot(outer);

    try (VMConfig.LocalSnapshotScope ignored = VMConfig.preserveLocalSnapshot()) {
      VMConfig.Snapshot inner = new VMConfig.Snapshot();
      inner.allowTvmOsaka = false;
      VMConfig.setLocalSnapshot(inner);
      assertFalse(VMConfig.allowTvmOsaka());
    }

    assertTrue(VMConfig.allowTvmOsaka());
  }

  // Deep-copy the current global config through the public getters (no thread-local set here, so
  // the getters read the global) so @After can restore the exact prior state.
  private static VMConfig.Snapshot snapshotGlobal() {
    VMConfig.Snapshot snapshot = new VMConfig.Snapshot();
    snapshot.allowTvmTransferTrc10 = VMConfig.allowTvmTransferTrc10();
    snapshot.allowTvmConstantinople = VMConfig.allowTvmConstantinople();
    snapshot.allowMultiSign = VMConfig.allowMultiSign();
    snapshot.allowTvmSolidity059 = VMConfig.allowTvmSolidity059();
    snapshot.allowShieldedTRC20Transaction = VMConfig.allowShieldedTRC20Transaction();
    snapshot.allowTvmIstanbul = VMConfig.allowTvmIstanbul();
    snapshot.allowTvmFreeze = VMConfig.allowTvmFreeze();
    snapshot.allowTvmVote = VMConfig.allowTvmVote();
    snapshot.allowTvmLondon = VMConfig.allowTvmLondon();
    snapshot.allowTvmCompatibleEvm = VMConfig.allowTvmCompatibleEvm();
    snapshot.allowHigherLimitForMaxCpuTimeOfOneTx = VMConfig.allowHigherLimitForMaxCpuTimeOfOneTx();
    snapshot.allowTvmFreezeV2 = VMConfig.allowTvmFreezeV2();
    snapshot.allowOptimizedReturnValueOfChainId = VMConfig.allowOptimizedReturnValueOfChainId();
    snapshot.allowDynamicEnergy = VMConfig.allowDynamicEnergy();
    snapshot.dynamicEnergyThreshold = VMConfig.getDynamicEnergyThreshold();
    snapshot.dynamicEnergyIncreaseFactor = VMConfig.getDynamicEnergyIncreaseFactor();
    snapshot.dynamicEnergyMaxFactor = VMConfig.getDynamicEnergyMaxFactor();
    snapshot.allowTvmShanghai = VMConfig.allowTvmShanghai();
    snapshot.allowEnergyAdjustment = VMConfig.allowEnergyAdjustment();
    snapshot.allowStrictMath = VMConfig.allowStrictMath();
    snapshot.allowTvmCancun = VMConfig.allowTvmCancun();
    snapshot.disableJavaLangMath = VMConfig.disableJavaLangMath();
    snapshot.allowTvmBlob = VMConfig.allowTvmBlob();
    snapshot.allowTvmSelfdestructRestriction = VMConfig.allowTvmSelfdestructRestriction();
    snapshot.allowTvmOsaka = VMConfig.allowTvmOsaka();
    snapshot.allowHardenResourceCalculation = VMConfig.allowHardenResourceCalculation();
    snapshot.energyLimitHardFork = VMConfig.getEnergyLimitHardFork();
    snapshot.passFork471 = VMConfig.passFork471();
    snapshot.passFork4811 = VMConfig.passFork4811();
    return snapshot;
  }
}
