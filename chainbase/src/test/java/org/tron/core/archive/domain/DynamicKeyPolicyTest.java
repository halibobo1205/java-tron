package org.tron.core.archive.domain;

import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class DynamicKeyPolicyTest {

  private final DynamicKeyPolicy policy = new DynamicKeyPolicy();

  private DynamicKeyDecision decide(String key) {
    return policy.decision(key.getBytes(StandardCharsets.US_ASCII));
  }

  @Test
  public void vmAndFeeConfigKeysAreRooted() {
    assertEquals(RootPolicy.IN_GLOBAL_ROOT, decide("ENERGY_FEE").getRootPolicy());
    assertEquals(RootPolicy.IN_GLOBAL_ROOT, decide("MAX_FEE_LIMIT").getRootPolicy());
    DynamicKeyDecision cancun = decide("ALLOW_TVM_CANCUN");
    assertEquals(RootPolicy.IN_GLOBAL_ROOT, cancun.getRootPolicy());
    assertEquals(DynamicKeyClass.VM_CONFIG, cancun.getKeyClass());
    assertEquals(ReaderPolicy.HISTORICAL_VM, cancun.getReaderPolicy());

    DynamicKeyDecision totalNetLimit = decide("TOTAL_NET_LIMIT");
    assertEquals(RootPolicy.IN_GLOBAL_ROOT, totalNetLimit.getRootPolicy());
    assertEquals(DynamicKeyClass.RESOURCE_PARAMETER, totalNetLimit.getKeyClass());
    assertEquals(ReaderPolicy.HISTORICAL_VM, totalNetLimit.getReaderPolicy());
  }

  @Test
  public void executionAffectingGovernanceKeysAreRooted() {
    // VM enablement, fork gates, energy knobs, arithmetic/resource flags and call-visible
    // precompile/address behaviour are rooted + readable, not left as unknown.
    for (String key : new String[] {
        "ALLOW_CREATION_OF_CONTRACTS",
        "ALLOW_HIGHER_LIMIT_FOR_MAX_CPU_TIME_OF_ONE_TX",
        "CURRENT_CYCLE_NUMBER",
        "ALLOW_DYNAMIC_ENERGY",
        "DYNAMIC_ENERGY_THRESHOLD",
        "DYNAMIC_ENERGY_INCREASE_FACTOR",
        "DYNAMIC_ENERGY_MAX_FACTOR",
        "ALLOW_ENERGY_ADJUSTMENT",
        "ALLOW_STRICT_MATH",
        "CONSENSUS_LOGIC_OPTIMIZATION",
        "ALLOW_HARDEN_RESOURCE_CALCULATION",
        "ALLOW_NEW_RESOURCE_MODEL",
        "UNFREEZE_DELAY_DAYS",
        "ALLOW_SHIELDED_TRC20_TRANSACTION",
        "ALLOW_MULTI_SIGN"}) {
      DynamicKeyDecision d = decide(key);
      assertEquals(RootPolicy.IN_GLOBAL_ROOT, d.getRootPolicy());
      assertEquals(DynamicKeyClass.VM_CONFIG, d.getKeyClass());
      assertEquals(ReaderPolicy.HISTORICAL_VM, d.getReaderPolicy());
    }
  }

  @Test
  public void callVisibleResourceTotalsAreRooted() {
    for (String key : new String[] {
        "TOTAL_NET_LIMIT",
        "TOTAL_ENERGY_LIMIT",
        "TOTAL_ENERGY_CURRENT_LIMIT",
        "TOTAL_ENERGY_TARGET_LIMIT",
        "ADAPTIVE_RESOURCE_LIMIT_TARGET_RATIO",
        "ADAPTIVE_RESOURCE_LIMIT_MULTIPLIER",
        "TOTAL_NET_WEIGHT",
        "TOTAL_ENERGY_WEIGHT",
        "TOTAL_TRON_POWER_WEIGHT",
        "FREE_NET_LIMIT",
        "EXCHANGE_BALANCE_LIMIT",
        "MAX_DELEGATE_LOCK_PERIOD",
        "MAX_CREATE_ACCOUNT_TX_SIZE"}) {
      DynamicKeyDecision d = decide(key);
      assertEquals(RootPolicy.IN_GLOBAL_ROOT, d.getRootPolicy());
      assertEquals(DynamicKeyClass.RESOURCE_PARAMETER, d.getKeyClass());
      assertEquals(ReaderPolicy.HISTORICAL_VM, d.getReaderPolicy());
    }
  }

  @Test
  public void proposalFeeKeysAreRooted() {
    for (String key : new String[] {
        "CREATE_ACCOUNT_FEE",
        "CREATE_NEW_ACCOUNT_FEE_IN_SYSTEM_CONTRACT",
        "ASSET_ISSUE_FEE",
        "UPDATE_ACCOUNT_PERMISSION_FEE",
        "MULTI_SIGN_FEE",
        "EXCHANGE_CREATE_FEE",
        "MARKET_SELL_FEE",
        "MARKET_CANCEL_FEE"}) {
      DynamicKeyDecision d = decide(key);
      assertEquals(RootPolicy.IN_GLOBAL_ROOT, d.getRootPolicy());
      assertEquals(DynamicKeyClass.FEE_PARAMETER, d.getKeyClass());
      assertEquals(ReaderPolicy.HISTORICAL_VM, d.getReaderPolicy());
    }
  }

  @Test
  public void proposalValidationAndGovernanceKeysAreRooted() {
    for (String key : new String[] {
        "MAINTENANCE_TIME_INTERVAL",
        "ACCOUNT_UPGRADE_COST",
        "WITNESS_PAY_PER_BLOCK",
        "WITNESS_127_PAY_PER_BLOCK",
        "WITNESS_STANDBY_ALLOWANCE",
        "REMOVE_THE_POWER_OF_THE_GR",
        "ALLOW_UPDATE_ACCOUNT_NAME",
        "ALLOW_SAME_TOKEN_NAME",
        "ALLOW_DELEGATE_RESOURCE",
        "ALLOW_ADAPTIVE_ENERGY",
        "ALLOW_PROTO_FILTER_NUM",
        "ALLOW_ACCOUNT_STATE_ROOT",
        "CHANGE_DELEGATION",
        "FORBID_TRANSFER_TO_CONTRACT",
        "ALLOW_PBFT",
        "ALLOW_MARKET_TRANSACTION",
        "ALLOW_TRANSACTION_FEE_POOL",
        "ALLOW_BLACKHOLE_OPTIMIZATION",
        "ALLOW_ACCOUNT_ASSET_OPTIMIZATION",
        "ALLOW_ASSET_OPTIMIZATION",
        "ALLOW_NEW_REWARD",
        "ALLOW_DELEGATE_OPTIMIZATION",
        "ALLOW_CANCEL_ALL_UNFREEZE_V2",
        "ALLOW_OLD_REWARD_OPT",
        "PROPOSAL_EXPIRE_TIME",
        "LATEST_PROPOSAL_NUM",
        "LATEST_EXCHANGE_NUM",
        "TOTAL_SIGN_NUM",
        "TOKEN_ID_NUM"}) {
      DynamicKeyDecision d = decide(key);
      assertEquals(RootPolicy.IN_GLOBAL_ROOT, d.getRootPolicy());
      assertEquals(DynamicKeyClass.GOVERNANCE_PARAMETER, d.getKeyClass());
      assertEquals(ReaderPolicy.HISTORICAL_VM, d.getReaderPolicy());
    }
  }

  @Test
  public void validationCountersAndMarketLimitsAreRooted() {
    DynamicKeyDecision marketLimit = decide("MARKET_QUANTITY_LIMIT");
    assertEquals(RootPolicy.IN_GLOBAL_ROOT, marketLimit.getRootPolicy());
    assertEquals(DynamicKeyClass.RESOURCE_PARAMETER, marketLimit.getKeyClass());
    assertEquals(ReaderPolicy.HISTORICAL_VM, marketLimit.getReaderPolicy());
  }

  @Test
  public void headerCursorsAndPriceHistoryAreHistoryOnly() {
    assertEquals(RootPolicy.HISTORY_ONLY, decide("latest_block_header_number").getRootPolicy());
    assertEquals(RootPolicy.HISTORY_ONLY, decide("ENERGY_PRICE_HISTORY").getRootPolicy());
  }

  @Test
  public void migrationMarkersAndStatisticsAreExcluded() {
    DynamicKeyDecision done = decide("ABI_MOVE_DONE");
    assertEquals(RootPolicy.EXCLUDED, done.getRootPolicy());
    assertEquals(DynamicKeyClass.MIGRATION_MARKER, done.getKeyClass());
    DynamicKeyDecision stateFlag = decide("state_flag");
    assertEquals(RootPolicy.EXCLUDED, stateFlag.getRootPolicy());
    assertEquals(HistoryPolicy.NO_ARCHIVE, stateFlag.getHistoryPolicy());
    assertEquals(RootPolicy.EXCLUDED, decide("TOTAL_STORAGE_POOL").getRootPolicy());
  }

  @Test
  public void unknownKeyKeepsHistoryButNeverRoots() {
    DynamicKeyDecision d = decide("SOME_FUTURE_KEY");
    assertEquals(DynamicKeyClass.UNKNOWN, d.getKeyClass());
    assertEquals(RootPolicy.EXCLUDED, d.getRootPolicy());
    // Keep history (diagnostic) so a future execution-affecting key is not lost.
    assertEquals(HistoryPolicy.FULL_HISTORY, d.getHistoryPolicy());
  }
}
