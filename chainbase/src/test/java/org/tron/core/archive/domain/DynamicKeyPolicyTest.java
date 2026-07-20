package org.tron.core.archive.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class DynamicKeyPolicyTest {

  private final DynamicKeyPolicy policy = new DynamicKeyPolicy();

  private DynamicKeyDecision decide(String key) {
    return policy.decision(key.getBytes(StandardCharsets.US_ASCII));
  }

  private void assertExcluded(String key, DynamicKeyClass keyClass) {
    DynamicKeyDecision decision = decide(key);
    assertEquals(keyClass, decision.getKeyClass());
    assertEquals(RootPolicy.EXCLUDED, decision.getRootPolicy());
    assertEquals(HistoryPolicy.NO_ARCHIVE, decision.getHistoryPolicy());
    assertEquals(ReaderPolicy.INTERNAL_ONLY, decision.getReaderPolicy());
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
        "VERSION_NUMBER",
        "FORK_VERSION_27",
        "FORK_VERSION_35",
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
        "ALLOW_SHIELDED_TRANSACTION",
        "ALLOW_MULTI_SIGN",
        "AVAILABLE_CONTRACT_TYPE",
        "ACTIVE_DEFAULT_OPERATIONS"}) {
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
        "TOTAL_ENERGY_AVERAGE_USAGE",
        "TOTAL_ENERGY_AVERAGE_TIME",
        "BLOCK_ENERGY_USAGE",
        "ADAPTIVE_RESOURCE_LIMIT_TARGET_RATIO",
        "ADAPTIVE_RESOURCE_LIMIT_MULTIPLIER",
        "TOTAL_NET_WEIGHT",
        "TOTAL_ENERGY_WEIGHT",
        "TOTAL_TRON_POWER_WEIGHT",
        "FREE_NET_LIMIT",
        "ONE_DAY_NET_LIMIT",
        "PUBLIC_NET_LIMIT",
        "PUBLIC_NET_USAGE",
        "PUBLIC_NET_TIME",
        "SHIELDED_TRANSACTION_FEE",
        "SHIELDED_TRANSACTION_CREATE_ACCOUNT_FEE",
        "TOTAL_SHIELDED_POOL_VALUE",
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
        "MAX_FROZEN_TIME",
        "MIN_FROZEN_TIME",
        "MAX_FROZEN_SUPPLY_NUMBER",
        "MAX_FROZEN_SUPPLY_TIME",
        "MIN_FROZEN_SUPPLY_TIME",
        "WITNESS_ALLOWANCE_FROZEN_TIME",
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
  public void maintenanceCursorIsRootedBecauseItControlsFinalize() {
    DynamicKeyDecision d = decide("NEXT_MAINTENANCE_TIME");
    assertEquals(RootPolicy.IN_GLOBAL_ROOT, d.getRootPolicy());
    assertEquals(DynamicKeyClass.GOVERNANCE_PARAMETER, d.getKeyClass());
    assertEquals(ReaderPolicy.HISTORICAL_VM, d.getReaderPolicy());
  }

  @Test
  public void validationCountersAndMarketLimitsAreRooted() {
    DynamicKeyDecision marketLimit = decide("MARKET_QUANTITY_LIMIT");
    assertEquals(RootPolicy.IN_GLOBAL_ROOT, marketLimit.getRootPolicy());
    assertEquals(DynamicKeyClass.RESOURCE_PARAMETER, marketLimit.getKeyClass());
    assertEquals(ReaderPolicy.HISTORICAL_VM, marketLimit.getReaderPolicy());
  }

  @Test
  public void realAllowSameTokenNameKeyWithLeadingSpaceIsRooted() {
    DynamicKeyDecision decision = decide(" ALLOW_SAME_TOKEN_NAME");
    assertEquals(RootPolicy.IN_GLOBAL_ROOT, decision.getRootPolicy());
    assertEquals(DynamicKeyClass.GOVERNANCE_PARAMETER, decision.getKeyClass());
    assertEquals(ReaderPolicy.HISTORICAL_VM, decision.getReaderPolicy());
  }

  @Test
  public void internalExecutionControlKeysAreRootedButNotReaderAccessible() {
    for (String key : new String[] {
        "NEW_REWARD_ALGORITHM_EFFECTIVE_CYCLE",
        "BLOCK_HASH_HISTORY_INSTALLED"}) {
      DynamicKeyDecision decision = decide(key);
      assertEquals(RootPolicy.IN_GLOBAL_ROOT, decision.getRootPolicy());
      assertEquals(HistoryPolicy.FULL_HISTORY, decision.getHistoryPolicy());
      assertEquals(ReaderPolicy.INTERNAL_ONLY, decision.getReaderPolicy());
    }
  }

  @Test
  public void forkVersionWildcardOnlyAcceptsNumericSuffix() {
    DynamicKeyDecision numeric = decide("FORK_VERSION_27");
    assertEquals(RootPolicy.IN_GLOBAL_ROOT, numeric.getRootPolicy());
    assertEquals(ReaderPolicy.HISTORICAL_VM, numeric.getReaderPolicy());

    DynamicKeyDecision nonNumeric = decide("FORK_VERSION_SECRET");
    assertEquals(RootPolicy.EXCLUDED, nonNumeric.getRootPolicy());
    assertEquals(ReaderPolicy.INTERNAL_ONLY, nonNumeric.getReaderPolicy());

    DynamicKeyDecision emptySuffix = decide("FORK_VERSION_");
    assertEquals(RootPolicy.EXCLUDED, emptySuffix.getRootPolicy());
    assertEquals(ReaderPolicy.INTERNAL_ONLY, emptySuffix.getReaderPolicy());

    DynamicKeyDecision literalWildcard = decide("FORK_VERSION_*");
    assertEquals(RootPolicy.EXCLUDED, literalWildcard.getRootPolicy());
    assertEquals(ReaderPolicy.INTERNAL_ONLY, literalWildcard.getReaderPolicy());
    assertFalse(policy.allDecisions().stream()
        .anyMatch(d -> "FORK_VERSION_*".equals(d.getKey())));
    assertTrue(policy.patternDecisionsForChecksum().stream()
        .anyMatch(d -> "FORK_VERSION_<numeric>".equals(d.getKey())));
  }

  @Test
  public void headerCursorsAndPriceHistoryAreHistoryOnly() {
    DynamicKeyDecision timestamp = decide("latest_block_header_timestamp");
    assertEquals(RootPolicy.HISTORY_ONLY, timestamp.getRootPolicy());
    assertEquals(ReaderPolicy.HISTORICAL_VM, timestamp.getReaderPolicy());

    assertEquals(RootPolicy.HISTORY_ONLY, decide("latest_block_header_number").getRootPolicy());
    assertEquals(RootPolicy.HISTORY_ONLY, decide("ENERGY_PRICE_HISTORY").getRootPolicy());
  }

  @Test
  public void migrationMarkersAndStatisticsAreExcluded() {
    for (String key : new String[] {
        "ABI_MOVE_DONE",
        "TOKEN_UPDATE_DONE",
        "ENERGY_PRICE_HISTORY_DONE",
        "BANDWIDTH_PRICE_HISTORY_DONE",
        "TURKISH_KEY_MIGRATION_DONE"}) {
      DynamicKeyDecision done = decide(key);
      assertEquals(RootPolicy.EXCLUDED, done.getRootPolicy());
      assertEquals(HistoryPolicy.NO_ARCHIVE, done.getHistoryPolicy());
      assertEquals(ReaderPolicy.INTERNAL_ONLY, done.getReaderPolicy());
      assertEquals(DynamicKeyClass.MIGRATION_MARKER, done.getKeyClass());
    }
    DynamicKeyDecision stateFlag = decide("state_flag");
    assertEquals(RootPolicy.EXCLUDED, stateFlag.getRootPolicy());
    assertEquals(HistoryPolicy.NO_ARCHIVE, stateFlag.getHistoryPolicy());
    assertEquals(RootPolicy.EXCLUDED, decide("TOTAL_STORAGE_POOL").getRootPolicy());
  }

  @Test
  public void knownOperationalAndLegacyKeysDoNotFallBackToUnknownHistory() {
    assertExcluded("BLOCK_FILLED_SLOTS", DynamicKeyClass.STATISTIC);
    assertExcluded("BLOCK_FILLED_SLOTS_INDEX", DynamicKeyClass.INDEX_CURSOR);
    assertExcluded("BURN_TRX_AMOUNT", DynamicKeyClass.STATISTIC);
    assertExcluded("SET_BLACKHOLE_ACCOUNT_PERMISSION", DynamicKeyClass.MIGRATION_MARKER);
    assertExcluded("STORAGE_EXCHANGE_TAX_RATE", DynamicKeyClass.FEE_PARAMETER);
  }

  @Test
  public void unknownKeyKeepsDiagnosticHistoryButDoesNotEnterRoot() {
    DynamicKeyDecision d = decide("SOME_FUTURE_KEY");
    assertEquals(DynamicKeyClass.UNKNOWN, d.getKeyClass());
    assertEquals(RootPolicy.EXCLUDED, d.getRootPolicy());
    // Keep diagnostic history so a future execution-affecting key can be promoted explicitly.
    assertEquals(HistoryPolicy.FULL_HISTORY, d.getHistoryPolicy());
    assertEquals(ReaderPolicy.INTERNAL_ONLY, d.getReaderPolicy());
  }
}
