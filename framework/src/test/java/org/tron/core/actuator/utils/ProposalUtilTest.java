package org.tron.core.actuator.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;
import org.tron.common.BaseTest;
import org.tron.common.TestConstants;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.ForkController;
import org.tron.core.Constant;
import org.tron.core.capsule.BytesCapsule;
import org.tron.core.capsule.ProposalCapsule;
import org.tron.core.config.Parameter;
import org.tron.core.config.Parameter.ForkBlockVersionEnum;
import org.tron.core.config.args.Args;
import org.tron.core.consensus.ProposalService;
import org.tron.core.exception.ContractValidateException;
import org.tron.core.store.DynamicPropertiesStore;
import org.tron.core.utils.ProposalUtil;
import org.tron.core.utils.ProposalUtil.ProposalType;
import org.tron.protos.Protocol;

@Slf4j(topic = "actuator")
public class ProposalUtilTest extends BaseTest {

  private static final long LONG_VALUE = 100_000_000_000_000_000L;
  private static final String LONG_VALUE_ERROR =
      "Bad chain parameter value, valid range is [0," + LONG_VALUE + "]";

  @Resource
  private DynamicPropertiesStore dynamicPropertiesStore;

  ForkController forkUtils = ForkController.instance();

  /**
   * Init .
   */
  @BeforeClass
  public static void init() {
    Args.setParam(new String[]{"--output-directory", dbPath()}, TestConstants.TEST_CONF);
  }
  
  @Test
  public void validProposalTypeCheck() throws ContractValidateException {

    Assert.assertFalse(ProposalType.contain(4000));
    Assert.assertFalse(ProposalType.contain(-1));
    Assert.assertTrue(ProposalType.contain(2));

    Assert.assertNull(ProposalType.getEnumOrNull(-2));
    Assert.assertEquals(ProposalType.ALLOW_TVM_SOLIDITY_059, ProposalType.getEnumOrNull(32));

    long finalCode = -1;
    ContractValidateException e = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalType.getEnum(finalCode));
    Assert.assertEquals("Does not support code : " + finalCode, e.getMessage());

    long code = 32;
    Assert.assertEquals(ProposalType.ALLOW_TVM_SOLIDITY_059, ProposalType.getEnum(code));

  }

  @Test
  public void validateCheck() {
    long invalidValue = -1;

    ContractValidateException e1 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.ACCOUNT_UPGRADE_COST.getCode(), invalidValue));
    Assert.assertEquals(LONG_VALUE_ERROR, e1.getMessage());

    ContractValidateException e2 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.ACCOUNT_UPGRADE_COST.getCode(), LONG_VALUE + 1));
    Assert.assertEquals(LONG_VALUE_ERROR, e2.getMessage());

    ContractValidateException e3 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.CREATE_ACCOUNT_FEE.getCode(), invalidValue));
    Assert.assertEquals(LONG_VALUE_ERROR, e3.getMessage());

    ContractValidateException e4 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.CREATE_ACCOUNT_FEE.getCode(), LONG_VALUE + 1));
    Assert.assertEquals(LONG_VALUE_ERROR, e4.getMessage());

    ContractValidateException e5 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.ASSET_ISSUE_FEE.getCode(), invalidValue));
    Assert.assertEquals(LONG_VALUE_ERROR, e5.getMessage());

    ContractValidateException e6 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.ASSET_ISSUE_FEE.getCode(), LONG_VALUE + 1));
    Assert.assertEquals(LONG_VALUE_ERROR, e6.getMessage());

    ContractValidateException e7 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.WITNESS_PAY_PER_BLOCK.getCode(), invalidValue));
    Assert.assertEquals(LONG_VALUE_ERROR, e7.getMessage());

    ContractValidateException e8 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.WITNESS_PAY_PER_BLOCK.getCode(), LONG_VALUE + 1));
    Assert.assertEquals(LONG_VALUE_ERROR, e8.getMessage());

    ContractValidateException e9 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.WITNESS_STANDBY_ALLOWANCE.getCode(), invalidValue));
    Assert.assertEquals(LONG_VALUE_ERROR, e9.getMessage());

    ContractValidateException e10 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.WITNESS_STANDBY_ALLOWANCE.getCode(), LONG_VALUE + 1));
    Assert.assertEquals(LONG_VALUE_ERROR, e10.getMessage());

    ContractValidateException e11 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.CREATE_NEW_ACCOUNT_FEE_IN_SYSTEM_CONTRACT.getCode(), invalidValue));
    Assert.assertEquals(LONG_VALUE_ERROR, e11.getMessage());

    ContractValidateException e12 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.CREATE_NEW_ACCOUNT_FEE_IN_SYSTEM_CONTRACT.getCode(), LONG_VALUE + 1));
    Assert.assertEquals(LONG_VALUE_ERROR, e12.getMessage());

    ContractValidateException e13 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.CREATE_NEW_ACCOUNT_BANDWIDTH_RATE.getCode(), invalidValue));
    Assert.assertEquals(LONG_VALUE_ERROR, e13.getMessage());

    ContractValidateException e14 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.CREATE_NEW_ACCOUNT_BANDWIDTH_RATE.getCode(), LONG_VALUE + 1));
    Assert.assertEquals(LONG_VALUE_ERROR, e14.getMessage());

    ContractValidateException e15 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.MAINTENANCE_TIME_INTERVAL.getCode(), 3 * 27 * 1000 - 1));
    Assert.assertEquals(
        "Bad chain parameter value, valid range is [3 * 27 * 1000,24 * 3600 * 1000]",
        e15.getMessage());

    ContractValidateException e16 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.MAINTENANCE_TIME_INTERVAL.getCode(), 24 * 3600 * 1000 + 1));
    Assert.assertEquals(
        "Bad chain parameter value, valid range is [3 * 27 * 1000,24 * 3600 * 1000]",
        e16.getMessage());

    ContractValidateException e17 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.ALLOW_CREATION_OF_CONTRACTS.getCode(), 2));
    Assert.assertEquals(
        "This value[ALLOW_CREATION_OF_CONTRACTS] is only allowed to be 1",
        e17.getMessage());

    dynamicPropertiesStore = dbManager.getDynamicPropertiesStore();
    dynamicPropertiesStore.saveRemoveThePowerOfTheGr(1);
    ContractValidateException e18 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.REMOVE_THE_POWER_OF_THE_GR.getCode(), 2));
    Assert.assertEquals(
        "This value[REMOVE_THE_POWER_OF_THE_GR] is only allowed to be 1",
        e18.getMessage());

    dynamicPropertiesStore.saveRemoveThePowerOfTheGr(-1);
    ContractValidateException e19 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.REMOVE_THE_POWER_OF_THE_GR.getCode(), 1));
    Assert.assertEquals(
        "This proposal has been executed before and is only allowed to be executed once",
        e19.getMessage());

    ContractValidateException e20 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.MAX_CPU_TIME_OF_ONE_TX.getCode(), 9));
    Assert.assertEquals(
        "Bad chain parameter value, valid range is [10,100]", e20.getMessage());

    ContractValidateException e21 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.MAX_CPU_TIME_OF_ONE_TX.getCode(), 101));
    Assert.assertEquals(
        "Bad chain parameter value, valid range is [10,100]", e21.getMessage());

    ContractValidateException e22 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.ALLOW_DELEGATE_RESOURCE.getCode(), 2));
    Assert.assertEquals(
        "This value[ALLOW_DELEGATE_RESOURCE] is only allowed to be 1", e22.getMessage());

    dynamicPropertiesStore.saveAllowSameTokenName(1);
    ContractValidateException e23 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.ALLOW_TVM_TRANSFER_TRC10.getCode(), 2));
    Assert.assertEquals(
        "This value[ALLOW_TVM_TRANSFER_TRC10] is only allowed to be 1", e23.getMessage());

    dynamicPropertiesStore.saveAllowSameTokenName(0);
    ContractValidateException e24 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.ALLOW_TVM_TRANSFER_TRC10.getCode(), 1));
    Assert.assertEquals("[ALLOW_SAME_TOKEN_NAME] proposal must be approved "
        + "before [ALLOW_TVM_TRANSFER_TRC10] can be proposed", e24.getMessage());

    forkUtils.init(dbManager.getChainBaseManager());
    long maintenanceTimeInterval = forkUtils.getManager().getDynamicPropertiesStore()
        .getMaintenanceTimeInterval();
    long hardForkTime =
        ((ForkBlockVersionEnum.VERSION_4_0_1.getHardForkTime() - 1) / maintenanceTimeInterval + 1)
            * maintenanceTimeInterval;
    forkUtils.getManager().getDynamicPropertiesStore()
        .saveLatestBlockHeaderTimestamp(hardForkTime + 1);
    byte[] stats = new byte[27];
    Arrays.fill(stats, (byte) 1);
    forkUtils.getManager().getDynamicPropertiesStore()
        .statsByVersion(ForkBlockVersionEnum.VERSION_4_0_1.getValue(), stats);
    ByteString address = ByteString
        .copyFrom(ByteArray.fromHexString("41ec6525979a351a54fa09fea64beb4cce33ffbb7a"));
    List<ByteString> w = new ArrayList<>();
    w.add(address);
    forkUtils.getManager().getWitnessScheduleStore().saveActiveWitnesses(w);
    ContractValidateException e25 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.ALLOW_SHIELDED_TRC20_TRANSACTION
                .getCode(), 2));
    Assert.assertEquals("This value[ALLOW_SHIELDED_TRC20_TRANSACTION] is only allowed"
        + " to be 1 or 0", e25.getMessage());

    hardForkTime =
        ((ForkBlockVersionEnum.VERSION_4_3.getHardForkTime() - 1) / maintenanceTimeInterval + 1)
            * maintenanceTimeInterval;
    forkUtils.getManager().getDynamicPropertiesStore()
        .saveLatestBlockHeaderTimestamp(hardForkTime + 1);
    forkUtils.getManager().getDynamicPropertiesStore()
        .statsByVersion(ForkBlockVersionEnum.VERSION_4_3.getValue(), stats);
    ContractValidateException e26 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils, ProposalType.FREE_NET_LIMIT
            .getCode(), -1));
    Assert.assertEquals("Bad chain parameter value, valid range is [0,100_000]",
        e26.getMessage());

    ContractValidateException e27 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.TOTAL_NET_LIMIT.getCode(), -1));
    Assert.assertEquals("Bad chain parameter value, valid range is [0, 1_000_000_000_000L]",
        e27.getMessage());

    ContractValidateException e28 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.ALLOW_OLD_REWARD_OPT.getCode(), 2));
    Assert.assertEquals(
        "Bad chain parameter id [ALLOW_OLD_REWARD_OPT]",
        e28.getMessage());
    hardForkTime =
        ((ForkBlockVersionEnum.VERSION_4_7_4.getHardForkTime() - 1) / maintenanceTimeInterval + 1)
            * maintenanceTimeInterval;
    forkUtils.getManager().getDynamicPropertiesStore()
        .saveLatestBlockHeaderTimestamp(hardForkTime + 1);
    forkUtils.getManager().getDynamicPropertiesStore()
        .statsByVersion(ForkBlockVersionEnum.VERSION_4_7_4.getValue(), stats);
    ContractValidateException e29 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.ALLOW_OLD_REWARD_OPT.getCode(), 2));
    Assert.assertEquals(
        "This value[ALLOW_OLD_REWARD_OPT] is only allowed to be 1",
        e29.getMessage());
    ContractValidateException e30 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.ALLOW_OLD_REWARD_OPT.getCode(), 1));
    Assert.assertEquals(
        "[ALLOW_NEW_REWARD] or [ALLOW_TVM_VOTE] proposal must be approved "
            + "before [ALLOW_OLD_REWARD_OPT] can be proposed",
        e30.getMessage());
    dynamicPropertiesStore.put("NEW_REWARD_ALGORITHM_EFFECTIVE_CYCLE".getBytes(),
        new BytesCapsule(ByteArray.fromLong(4000)));
    dynamicPropertiesStore.saveAllowOldRewardOpt(1);
    ContractValidateException e31 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.ALLOW_OLD_REWARD_OPT.getCode(), 1));
    Assert.assertEquals(
        "[ALLOW_OLD_REWARD_OPT] has been valid, no need to propose again",
        e31.getMessage());

    ContractValidateException e32 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.ALLOW_STRICT_MATH.getCode(), 2));
    Assert.assertEquals(
        "Bad chain parameter id [ALLOW_STRICT_MATH]",
        e32.getMessage());
    hardForkTime =
        ((ForkBlockVersionEnum.VERSION_4_7_7.getHardForkTime() - 1) / maintenanceTimeInterval + 1)
            * maintenanceTimeInterval;
    forkUtils.getManager().getDynamicPropertiesStore()
        .saveLatestBlockHeaderTimestamp(hardForkTime + 1);
    forkUtils.getManager().getDynamicPropertiesStore()
        .statsByVersion(ForkBlockVersionEnum.VERSION_4_7_7.getValue(), stats);
    ContractValidateException e33 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.ALLOW_STRICT_MATH.getCode(), 2));
    Assert.assertEquals(
        "This value[ALLOW_STRICT_MATH] is only allowed to be 1",
        e33.getMessage());
    try {
      ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
          ProposalType.ALLOW_STRICT_MATH.getCode(), 1);
    } catch (ContractValidateException e) {
      Assert.fail(e.getMessage());
    }
    Protocol.Proposal proposal = Protocol.Proposal.newBuilder().putParameters(
        ProposalType.ALLOW_STRICT_MATH.getCode(), 1).build();
    ProposalCapsule proposalCapsule = new ProposalCapsule(proposal);
    ProposalService.process(dbManager, proposalCapsule);
    ContractValidateException e34 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.ALLOW_STRICT_MATH.getCode(), 1));
    Assert.assertEquals(
        "[ALLOW_STRICT_MATH] has been valid, no need to propose again",
        e34.getMessage());

    testEnergyAdjustmentProposal();

    testConsensusLogicOptimizationProposal();

    testAllowTvmCancunProposal();

    testAllowTvmBlobProposal();

    testAllowMarketTransaction();

    testAllowTvmSelfdestructRestrictionProposal();

    testAllowTvmPragueProposal();

    testAllowHardenResourceCalculationProposal();

    testAdaptiveEnergyOverflowGuard();

    testAllowHardenExchangeCalculationProposal();

    forkUtils.getManager().getDynamicPropertiesStore()
        .statsByVersion(ForkBlockVersionEnum.ENERGY_LIMIT.getValue(), stats);
    forkUtils.reset();
  }

  private void testEnergyAdjustmentProposal() {
    // Should fail because cannot pass the fork controller check
    ContractValidateException e1 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.ALLOW_ENERGY_ADJUSTMENT.getCode(), 1));
    Assert.assertEquals(
        "Bad chain parameter id [ALLOW_ENERGY_ADJUSTMENT]",
        e1.getMessage());

    long maintenanceTimeInterval = forkUtils.getManager().getDynamicPropertiesStore()
        .getMaintenanceTimeInterval();

    long hardForkTime =
        ((ForkBlockVersionEnum.VERSION_4_7_5.getHardForkTime() - 1) / maintenanceTimeInterval + 1)
            * maintenanceTimeInterval;
    forkUtils.getManager().getDynamicPropertiesStore()
        .saveLatestBlockHeaderTimestamp(hardForkTime + 1);

    byte[] stats = new byte[27];
    Arrays.fill(stats, (byte) 1);
    forkUtils.getManager().getDynamicPropertiesStore()
        .statsByVersion(ForkBlockVersionEnum.VERSION_4_7_5.getValue(), stats);

    // Should fail because the proposal value is invalid
    ContractValidateException e2 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.ALLOW_ENERGY_ADJUSTMENT.getCode(), 2));
    Assert.assertEquals(
        "This value[ALLOW_ENERGY_ADJUSTMENT] is only allowed to be 1",
        e2.getMessage());

    // Should succeed
    try {
      ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
          ProposalType.ALLOW_ENERGY_ADJUSTMENT.getCode(), 1);
    } catch (Throwable t) {
      Assert.fail();
    }

    ProposalCapsule proposalCapsule = new ProposalCapsule(ByteString.empty(), 0);
    Map<Long, Long> parameter = new HashMap<>();
    parameter.put(81L, 1L);
    proposalCapsule.setParameters(parameter);
    ProposalService.process(dbManager, proposalCapsule);

    ContractValidateException e3 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.ALLOW_ENERGY_ADJUSTMENT.getCode(), 1));
    Assert.assertEquals(
        "[ALLOW_ENERGY_ADJUSTMENT] has been valid, no need to propose again",
        e3.getMessage());
  }

  private void testConsensusLogicOptimizationProposal() {
    ContractValidateException e1 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.CONSENSUS_LOGIC_OPTIMIZATION.getCode(), 1));
    Assert.assertEquals(
        "Bad chain parameter id [CONSENSUS_LOGIC_OPTIMIZATION]",
        e1.getMessage());

    long maintenanceTimeInterval = forkUtils.getManager().getDynamicPropertiesStore()
        .getMaintenanceTimeInterval();

    long hardForkTime =
        ((ForkBlockVersionEnum.VERSION_4_8_0.getHardForkTime() - 1) / maintenanceTimeInterval + 1)
        * maintenanceTimeInterval;
    forkUtils.getManager().getDynamicPropertiesStore()
      .saveLatestBlockHeaderTimestamp(hardForkTime + 1);

    byte[] stats = new byte[27];
    Arrays.fill(stats, (byte) 1);
    forkUtils.getManager().getDynamicPropertiesStore()
      .statsByVersion(ForkBlockVersionEnum.VERSION_4_8_0.getValue(), stats);

    // Should fail because the proposal value is invalid
    ContractValidateException e2 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.CONSENSUS_LOGIC_OPTIMIZATION.getCode(), 2));
    Assert.assertEquals(
        "This value[CONSENSUS_LOGIC_OPTIMIZATION] is only allowed to be 1",
        e2.getMessage());

    dynamicPropertiesStore.saveConsensusLogicOptimization(1);
    ContractValidateException e3 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.CONSENSUS_LOGIC_OPTIMIZATION.getCode(), 1));
    Assert.assertEquals(
        "[CONSENSUS_LOGIC_OPTIMIZATION] has been valid, no need to propose again",
        e3.getMessage());

  }

  private void testAllowTvmCancunProposal() {
    byte[] stats = new byte[27];
    forkUtils.getManager().getDynamicPropertiesStore()
        .statsByVersion(ForkBlockVersionEnum.VERSION_4_8_0.getValue(), stats);
    ContractValidateException e1 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.ALLOW_TVM_CANCUN.getCode(), 1));
    Assert.assertEquals(
        "Bad chain parameter id [ALLOW_TVM_CANCUN]",
        e1.getMessage());

    long maintenanceTimeInterval = forkUtils.getManager().getDynamicPropertiesStore()
        .getMaintenanceTimeInterval();

    long hardForkTime =
        ((ForkBlockVersionEnum.VERSION_4_8_0.getHardForkTime() - 1) / maintenanceTimeInterval + 1)
            * maintenanceTimeInterval;
    forkUtils.getManager().getDynamicPropertiesStore()
        .saveLatestBlockHeaderTimestamp(hardForkTime + 1);

    stats = new byte[27];
    Arrays.fill(stats, (byte) 1);
    forkUtils.getManager().getDynamicPropertiesStore()
        .statsByVersion(ForkBlockVersionEnum.VERSION_4_8_0.getValue(), stats);

    // Should fail because the proposal value is invalid
    ContractValidateException e2 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.ALLOW_TVM_CANCUN.getCode(), 2));
    Assert.assertEquals(
        "This value[ALLOW_TVM_CANCUN] is only allowed to be 1",
        e2.getMessage());

    dynamicPropertiesStore.saveAllowTvmCancun(1);
    ContractValidateException e3 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.ALLOW_TVM_CANCUN.getCode(), 1));
    Assert.assertEquals(
        "[ALLOW_TVM_CANCUN] has been valid, no need to propose again",
        e3.getMessage());

  }

  private void testAllowTvmBlobProposal() {
    byte[] stats = new byte[27];
    forkUtils.getManager().getDynamicPropertiesStore()
        .statsByVersion(ForkBlockVersionEnum.VERSION_4_8_0.getValue(), stats);
    ContractValidateException e1 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.ALLOW_TVM_BLOB.getCode(), 1));
    Assert.assertEquals(
        "Bad chain parameter id [ALLOW_TVM_BLOB]",
        e1.getMessage());

    long maintenanceTimeInterval = forkUtils.getManager().getDynamicPropertiesStore()
        .getMaintenanceTimeInterval();

    long hardForkTime =
        ((ForkBlockVersionEnum.VERSION_4_8_0.getHardForkTime() - 1) / maintenanceTimeInterval + 1)
            * maintenanceTimeInterval;
    forkUtils.getManager().getDynamicPropertiesStore()
        .saveLatestBlockHeaderTimestamp(hardForkTime + 1);

    stats = new byte[27];
    Arrays.fill(stats, (byte) 1);
    forkUtils.getManager().getDynamicPropertiesStore()
        .statsByVersion(ForkBlockVersionEnum.VERSION_4_8_0.getValue(), stats);

    // Should fail because the proposal value is invalid
    ContractValidateException e2 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.ALLOW_TVM_BLOB.getCode(), 2));
    Assert.assertEquals(
        "This value[ALLOW_TVM_BLOB] is only allowed to be 1",
        e2.getMessage());

    dynamicPropertiesStore.saveAllowTvmBlob(1);
    ContractValidateException e3 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.ALLOW_TVM_BLOB.getCode(), 1));
    Assert.assertEquals(
        "[ALLOW_TVM_BLOB] has been valid, no need to propose again",
        e3.getMessage());

  }

  private void testAllowTvmSelfdestructRestrictionProposal() {
    byte[] stats = new byte[27];
    forkUtils.getManager().getDynamicPropertiesStore()
        .statsByVersion(ForkBlockVersionEnum.VERSION_4_8_1.getValue(), stats);
    ContractValidateException e1 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.ALLOW_TVM_SELFDESTRUCT_RESTRICTION.getCode(), 1));
    Assert.assertEquals(
        "Bad chain parameter id [ALLOW_TVM_SELFDESTRUCT_RESTRICTION]",
        e1.getMessage());

    long maintenanceTimeInterval = forkUtils.getManager().getDynamicPropertiesStore()
        .getMaintenanceTimeInterval();

    long hardForkTime =
        ((ForkBlockVersionEnum.VERSION_4_8_1.getHardForkTime() - 1) / maintenanceTimeInterval + 1)
            * maintenanceTimeInterval;
    forkUtils.getManager().getDynamicPropertiesStore()
        .saveLatestBlockHeaderTimestamp(hardForkTime + 1);

    stats = new byte[27];
    Arrays.fill(stats, (byte) 1);
    forkUtils.getManager().getDynamicPropertiesStore()
        .statsByVersion(ForkBlockVersionEnum.VERSION_4_8_1.getValue(), stats);

    // Should fail because the proposal value is invalid
    ContractValidateException e2 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.ALLOW_TVM_SELFDESTRUCT_RESTRICTION.getCode(), 2));
    Assert.assertEquals(
        "This value[ALLOW_TVM_SELFDESTRUCT_RESTRICTION] is only allowed to be 1",
        e2.getMessage());

    dynamicPropertiesStore.saveAllowTvmSelfdestructRestriction(1);
    ContractValidateException e3 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.ALLOW_TVM_SELFDESTRUCT_RESTRICTION.getCode(), 1));
    Assert.assertEquals(
        "[ALLOW_TVM_SELFDESTRUCT_RESTRICTION] has been valid, no need to propose again",
        e3.getMessage());
  }

  private void testAllowHardenResourceCalculationProposal() {
    byte[] stats = new byte[27];
    forkUtils.getManager().getDynamicPropertiesStore()
        .statsByVersion(ForkBlockVersionEnum.VERSION_4_8_1.getValue(), stats);
    forkUtils.getManager().getDynamicPropertiesStore()
        .statsByVersion(ForkBlockVersionEnum.VERSION_4_8_2.getValue(), stats);
    ContractValidateException e1 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.ALLOW_HARDEN_RESOURCE_CALCULATION.getCode(), 1));
    Assert.assertEquals(
        "Bad chain parameter id [ALLOW_HARDEN_RESOURCE_CALCULATION]",
        e1.getMessage());

    long maintenanceTimeInterval = forkUtils.getManager().getDynamicPropertiesStore()
        .getMaintenanceTimeInterval();

    long hardForkTime =
        ((ForkBlockVersionEnum.VERSION_4_8_2.getHardForkTime() - 1) / maintenanceTimeInterval + 1)
            * maintenanceTimeInterval;
    forkUtils.getManager().getDynamicPropertiesStore()
        .saveLatestBlockHeaderTimestamp(hardForkTime + 1);

    stats = new byte[27];
    Arrays.fill(stats, (byte) 1);
    forkUtils.getManager().getDynamicPropertiesStore()
        .statsByVersion(ForkBlockVersionEnum.VERSION_4_8_2.getValue(), stats);

    // Should fail because the proposal value is invalid
    ContractValidateException e2 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.ALLOW_HARDEN_RESOURCE_CALCULATION.getCode(), 2));
    Assert.assertEquals(
        "This value[ALLOW_HARDEN_RESOURCE_CALCULATION] is only allowed to be 1",
        e2.getMessage());

    dynamicPropertiesStore.saveAllowHardenResourceCalculation(1);
    ContractValidateException e3 = Assert.assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
            ProposalType.ALLOW_HARDEN_RESOURCE_CALCULATION.getCode(), 1));
    Assert.assertEquals(
        "[ALLOW_HARDEN_RESOURCE_CALCULATION] has been valid, no need to propose again",
        e3.getMessage());
  }

  /**
   * Proposal guards added on top of TIP-833 (PR #6721): after VERSION_4_8_2,
   * adaptive-energy parameter proposals must not create state that the hardened
   * {@code EnergyProcessor.updateAdaptiveTotalEnergyLimit} path cannot process.
   * This check is intentionally scoped to that adaptive-limit update path; it does
   * not claim to bound {@code totalEnergyAverageUsage}, which depends on block-level
   * energy consumption and other operational parameters.
   *
   * <p>{@code ALLOW_HARDEN_RESOURCE_CALCULATION} only enables fail-fast arithmetic. It
   * is not a repair mechanism for private chains that already contain unsafe adaptive
   * energy parameters; those chains must first pass safe total-limit or multiplier
   * proposals, which this test keeps allowed.
   */
  private void assertValidatorAccepts(long code, long value, String contextMsg) {
    try {
      ProposalUtil.validator(dynamicPropertiesStore, forkUtils, code, value);
    } catch (ContractValidateException e) {
      Assert.fail(contextMsg + ": " + e.getMessage());
    }
  }

  private void testAdaptiveEnergyOverflowGuard() {
    long savedTotalEnergyLimit = dynamicPropertiesStore.getTotalEnergyLimit();
    long savedMultiplier = dynamicPropertiesStore.getAdaptiveResourceLimitMultiplier();
    long savedHarden = dynamicPropertiesStore.getAllowHardenResourceCalculation();

    // Ensure every fork gate this test depends on is enacted. Each version lives in
    // an independent stats slot, so enacting VERSION_4_8_2 does not imply older
    // versions. VERSION_3_2_2 gates TOTAL_CURRENT_ENERGY_LIMIT, VERSION_3_5 gates
    // ALLOW_ADAPTIVE_ENERGY, VERSION_3_6_5 gates adaptive resource ratio / multiplier
    // proposals, VERSION_4_8_2 gates
    // ALLOW_HARDEN_RESOURCE_CALCULATION.
    byte[] forkStats = new byte[27];
    Arrays.fill(forkStats, (byte) 1);
    DynamicPropertiesStore forkStore = forkUtils.getManager().getDynamicPropertiesStore();
    forkStore.statsByVersion(ForkBlockVersionEnum.VERSION_3_2_2.getValue(), forkStats);
    forkStore.statsByVersion(ForkBlockVersionEnum.VERSION_3_5.getValue(), forkStats);
    forkStore.statsByVersion(ForkBlockVersionEnum.VERSION_3_6_5.getValue(), forkStats);
    forkStore.statsByVersion(ForkBlockVersionEnum.VERSION_4_8_2.getValue(), forkStats);

    try {
      // The guard rejects any combo whose product exceeds
      // Long.MAX_VALUE * EXPAND_RATE_DENOMINATOR / EXPAND_RATE_NUMERATOR (~= 9.214e18).
      // This covers both updateAdaptiveTotalEnergyLimit's upperBound multiplication
      // and the EXPAND-branch scaleByRate inside the same method. The bound leaves
      // ~5 orders of magnitude headroom above mainnet's current ~8e10 product.
      final String unsafeMessage = "must be <= ";

      // ---- ALLOW_HARDEN_RESOURCE_CALCULATION itself is not a parameter-healing
      // proposal. Production activation is expected to happen only after adaptive
      // energy parameters are already in a safe range.
      dynamicPropertiesStore.saveAllowHardenResourceCalculation(0);
      dynamicPropertiesStore.saveTotalEnergyLimit(50_000_000_000L);
      dynamicPropertiesStore.saveAdaptiveResourceLimitMultiplier(1000L);
      assertValidatorAccepts(ProposalType.ALLOW_HARDEN_RESOURCE_CALCULATION.getCode(), 1L,
          "ALLOW_HARDEN_RESOURCE_CALCULATION with mainnet-realistic combo should pass");
      assertValidatorAccepts(ProposalType.ALLOW_ADAPTIVE_ENERGY.getCode(), 1L,
          "ALLOW_ADAPTIVE_ENERGY uses safe default adaptive parameters after 3.6.5");

      // ---- Healing path: a chain may already hold an unsafe combo when 4.8.2
      // activates and harden is still 0. Governance must still be able to fix the
      // parameters before enabling fail-fast arithmetic. Reset to an unsafe combo and
      // verify product-healing proposals are accepted. Ratio-only proposals are not
      // accepted while the product remains unsafe because they do not heal that state.
      dynamicPropertiesStore.saveTotalEnergyLimit(10_000_000_000_000_000L);
      dynamicPropertiesStore.saveAdaptiveResourceLimitMultiplier(1000L);
      // total 1e16, multiplier 1000 -> product 1e19 unsafe; harden still 0.
      ContractValidateException denyUnsafeRatio = Assert.assertThrows(
          ContractValidateException.class,
          () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
              ProposalType.ADAPTIVE_RESOURCE_LIMIT_TARGET_RATIO.getCode(), 1L));
      Assert.assertTrue(denyUnsafeRatio.getMessage(),
          denyUnsafeRatio.getMessage().contains(unsafeMessage));

      assertValidatorAccepts(ProposalType.ADAPTIVE_RESOURCE_LIMIT_MULTIPLIER.getCode(), 100L,
          "healing: lowering multiplier to a safe value must pass even with harden off");
      dynamicPropertiesStore.saveAdaptiveResourceLimitMultiplier(100L);
      assertValidatorAccepts(ProposalType.ADAPTIVE_RESOURCE_LIMIT_TARGET_RATIO.getCode(), 1L,
          "ADAPTIVE_RESOURCE_LIMIT_TARGET_RATIO should pass once product is safe");

      dynamicPropertiesStore.saveAdaptiveResourceLimitMultiplier(1000L);
      // Limit-reducing healing proposal: 1e15 * 1000 = 1e18 < 9.214e18, safe.
      assertValidatorAccepts(ProposalType.TOTAL_CURRENT_ENERGY_LIMIT.getCode(),
          1_000_000_000_000_000L,
          "healing: lowering totalCurrentEnergyLimit to a safe value must pass "
              + "even with harden off");

      // A fresh proposal that would *create* an unsafe combo is still rejected
      // even with harden off (silent-wrap path is closed).
      dynamicPropertiesStore.saveAdaptiveResourceLimitMultiplier(100L);
      // total 1e16, multiplier 100 -> product 1e18 safe; propose multiplier 1000
      // -> would push product to 1e19, must be rejected.
      ContractValidateException denyUnsafe = Assert.assertThrows(
          ContractValidateException.class,
          () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
              ProposalType.ADAPTIVE_RESOURCE_LIMIT_MULTIPLIER.getCode(), 1000L));
      Assert.assertTrue(denyUnsafe.getMessage(),
          denyUnsafe.getMessage().contains(unsafeMessage));

      // ---- Post-enact: harden flag does not control the check (4.8.2 fork does).
      dynamicPropertiesStore.saveAllowHardenResourceCalculation(1);
      ContractValidateException e2 = Assert.assertThrows(ContractValidateException.class,
          () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
              ProposalType.ADAPTIVE_RESOURCE_LIMIT_MULTIPLIER.getCode(), 1000L));
      Assert.assertTrue(e2.getMessage(), e2.getMessage().contains(unsafeMessage));

      assertValidatorAccepts(ProposalType.ADAPTIVE_RESOURCE_LIMIT_MULTIPLIER.getCode(), 100L,
          "ADAPTIVE_RESOURCE_LIMIT_MULTIPLIER with safe value should pass under harden");

      // current multiplier still 100; safe bound / 100 ~= 9.214e16, so a proposed
      // totalCurrentEnergyLimit of 1e17 (LONG_VALUE) would overflow.
      ContractValidateException e3 = Assert.assertThrows(ContractValidateException.class,
          () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
              ProposalType.TOTAL_CURRENT_ENERGY_LIMIT.getCode(), 100_000_000_000_000_000L));
      Assert.assertTrue(e3.getMessage(), e3.getMessage().contains(unsafeMessage));

      assertValidatorAccepts(ProposalType.TOTAL_CURRENT_ENERGY_LIMIT.getCode(),
          1_000_000_000_000_000L,
          "TOTAL_CURRENT_ENERGY_LIMIT with safe value should pass under harden");

      // ---- Zero factor is exempt (no false positive on legacy / fresh state).
      dynamicPropertiesStore.saveTotalEnergyLimit(0L);
      assertValidatorAccepts(ProposalType.ADAPTIVE_RESOURCE_LIMIT_MULTIPLIER.getCode(), 10_000L,
          "zero totalEnergyLimit should bypass overflow guard");
    } finally {
      dynamicPropertiesStore.saveTotalEnergyLimit(savedTotalEnergyLimit);
      dynamicPropertiesStore.saveAdaptiveResourceLimitMultiplier(savedMultiplier);
      dynamicPropertiesStore.saveAllowHardenResourceCalculation(savedHarden);
    }
  }

  private void testAllowTvmPragueProposal() {
    byte[] stats = new byte[27];
    forkUtils.getManager().getDynamicPropertiesStore()
        .statsByVersion(ForkBlockVersionEnum.VERSION_4_8_2.getValue(), stats);
    try {
      ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
          ProposalType.ALLOW_TVM_PRAGUE.getCode(), 1);
      Assert.fail();
    } catch (ContractValidateException e) {
      Assert.assertEquals(
          "Bad chain parameter id [ALLOW_TVM_PRAGUE]",
          e.getMessage());
    }

    long maintenanceTimeInterval = forkUtils.getManager().getDynamicPropertiesStore()
        .getMaintenanceTimeInterval();
    long hardForkTime =
        ((ForkBlockVersionEnum.VERSION_4_8_2.getHardForkTime() - 1) / maintenanceTimeInterval + 1)
            * maintenanceTimeInterval;
    forkUtils.getManager().getDynamicPropertiesStore()
        .saveLatestBlockHeaderTimestamp(hardForkTime + 1);

    stats = new byte[27];
    Arrays.fill(stats, (byte) 1);
    forkUtils.getManager().getDynamicPropertiesStore()
        .statsByVersion(ForkBlockVersionEnum.VERSION_4_8_2.getValue(), stats);

    // Fork passed but Shanghai not yet enacted: prague validator must refuse,
    // since the deployed bytecode uses PUSH0 (gated on ALLOW_TVM_SHANGHAI).
    try {
      ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
          ProposalType.ALLOW_TVM_PRAGUE.getCode(), 1);
      Assert.fail();
    } catch (ContractValidateException e) {
      Assert.assertEquals(
          "[ALLOW_TVM_PRAGUE] requires [ALLOW_TVM_SHANGHAI] to be enacted first",
          e.getMessage());
    }

    dynamicPropertiesStore.saveAllowTvmShangHai(1);

    try {
      ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
          ProposalType.ALLOW_TVM_PRAGUE.getCode(), 2);
      Assert.fail();
    } catch (ContractValidateException e) {
      Assert.assertEquals(
          "This value[ALLOW_TVM_PRAGUE] is only allowed to be 1",
          e.getMessage());
    }

    dynamicPropertiesStore.saveAllowTvmPrague(1);
    try {
      ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
          ProposalType.ALLOW_TVM_PRAGUE.getCode(), 1);
      Assert.fail();
    } catch (ContractValidateException e) {
      Assert.assertEquals(
          "[ALLOW_TVM_PRAGUE] has been valid, no need to propose again",
          e.getMessage());
    }
  }

  private void testAllowHardenExchangeCalculationProposal() {
    long code = ProposalType.ALLOW_HARDEN_EXCHANGE_CALCULATION.getCode();
    ThrowingRunnable proposeZero = () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
        code, 0);
    ThrowingRunnable proposeOne = () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
        code, 1);
    ThrowingRunnable proposeTwo = () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
        code, 2);

    byte[] stats = new byte[27];
    forkUtils.getManager().getDynamicPropertiesStore()
        .statsByVersion(ForkBlockVersionEnum.VERSION_4_8_1.getValue(), stats);
    long maintenanceTimeInterval = forkUtils.getManager().getDynamicPropertiesStore()
        .getMaintenanceTimeInterval();
    long hardForkTime =
        ((ForkBlockVersionEnum.VERSION_4_8_2.getHardForkTime() - 1) / maintenanceTimeInterval + 1)
            * maintenanceTimeInterval;
    forkUtils.getManager().getDynamicPropertiesStore()
        .saveLatestBlockHeaderTimestamp(hardForkTime - 1);

    // 1) before fork 4.8.2 -> rejected
    ContractValidateException thrown = assertThrows(ContractValidateException.class, proposeOne);
    assertEquals("Bad chain parameter id [ALLOW_HARDEN_EXCHANGE_CALCULATION]",
        thrown.getMessage());

    forkUtils.getManager().getDynamicPropertiesStore()
        .saveLatestBlockHeaderTimestamp(hardForkTime + 1);
    Arrays.fill(stats, (byte) 1);
    forkUtils.getManager().getDynamicPropertiesStore()
        .statsByVersion(ForkBlockVersionEnum.VERSION_4_8_2.getValue(), stats);

    // 2) value not in {0, 1} -> rejected
    thrown = assertThrows(ContractValidateException.class, proposeTwo);
    assertEquals("This value[ALLOW_HARDEN_EXCHANGE_CALCULATION] is only allowed to be 0 or 1",
        thrown.getMessage());

    // 3) current value is 0 (default), proposing 0 again -> rejected
    thrown = assertThrows(ContractValidateException.class, proposeZero);
    assertEquals("[ALLOW_HARDEN_EXCHANGE_CALCULATION] has been set to 0, no need to propose again",
        thrown.getMessage());

    // 4) value=1 to enable -> ok
    try {
      proposeOne.run();
    } catch (Throwable e) {
      Assert.fail("Should pass when toggling 0 -> 1: " + e.getMessage());
    }

    // 5) after activation, proposing 1 again -> rejected
    dynamicPropertiesStore.saveAllowHardenExchangeCalculation(1);
    thrown = assertThrows(ContractValidateException.class, proposeOne);
    assertEquals("[ALLOW_HARDEN_EXCHANGE_CALCULATION] has been set to 1, no need to propose again",
        thrown.getMessage());

    // 6) value=0 to disable -> ok (toggle back off)
    try {
      proposeZero.run();
    } catch (Throwable e) {
      Assert.fail("Should pass when toggling 1 -> 0: " + e.getMessage());
    }
  }

  private void testAllowMarketTransaction() {
    ThrowingRunnable off = () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
        ProposalType.ALLOW_MARKET_TRANSACTION.getCode(), 0);
    ThrowingRunnable open = () -> ProposalUtil.validator(dynamicPropertiesStore, forkUtils,
        ProposalType.ALLOW_MARKET_TRANSACTION.getCode(), 1);
    String err = "Bad chain parameter id [ALLOW_MARKET_TRANSACTION]";

    ContractValidateException thrown = assertThrows(ContractValidateException.class, open);
    assertEquals(err, thrown.getMessage());

    activateFork(ForkBlockVersionEnum.VERSION_4_1);

    try {
      open.run();
    } catch (Throwable e) {
      Assert.fail(e.getMessage());
    }

    thrown = assertThrows(ContractValidateException.class, off);
    assertEquals("This value[ALLOW_MARKET_TRANSACTION] is only allowed to be 1",
        thrown.getMessage());

    activateFork(ForkBlockVersionEnum.VERSION_4_8_1);

    thrown = assertThrows(ContractValidateException.class, open);
    assertEquals(err, thrown.getMessage());

    thrown = assertThrows(ContractValidateException.class, off);
    assertEquals(err, thrown.getMessage());
  }

  private void activateFork(ForkBlockVersionEnum forkVersion) {
    byte[] stats = new byte[27];
    Arrays.fill(stats, (byte) 1);
    forkUtils.getManager().getDynamicPropertiesStore()
        .statsByVersion(forkVersion.getValue(), stats);

    long maintenanceTimeInterval = forkUtils.getManager().getDynamicPropertiesStore()
        .getMaintenanceTimeInterval();
    long hardForkTime = ((forkVersion.getHardForkTime() - 1) / maintenanceTimeInterval + 1)
            * maintenanceTimeInterval;
    forkUtils.getManager().getDynamicPropertiesStore()
        .saveLatestBlockHeaderTimestamp(hardForkTime + 1);
  }

  @Test
  public void blockVersionCheck() {
    for (ForkBlockVersionEnum forkVersion : ForkBlockVersionEnum.values()) {
      if (forkVersion.getValue() > Parameter.ChainConstant.BLOCK_VERSION) {
        Assert.fail("ForkBlockVersion must be less than BLOCK_VERSION");
      }
    }
  }
}
