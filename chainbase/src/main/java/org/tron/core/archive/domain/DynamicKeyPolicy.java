package org.tron.core.archive.domain;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Key-level policy for the DYNAMIC_PROPERTIES domain. VM, fee, resource, validation and governance
 * parameters that can change historical execution or transaction validity enter the global root;
 * header cursors and price history are kept history-only; one-time migration markers and aggregate
 * statistics are excluded. Unknown keys default to {@code EXCLUDED} root + {@code FULL_HISTORY}
 * (keep history, don't root) so a future execution-affecting key is never silently lost to history
 * - but it never enters the root until explicitly classified and the registry checksum updated.
 */
public final class DynamicKeyPolicy {

  private final Map<String, DynamicKeyDecision> decisions = new LinkedHashMap<>();

  public DynamicKeyPolicy() {
    // --- IN_GLOBAL_ROOT: fee parameters ---
    root("ENERGY_FEE", DynamicKeyClass.FEE_PARAMETER);
    root("TRANSACTION_FEE", DynamicKeyClass.FEE_PARAMETER);
    root("MAX_FEE_LIMIT", DynamicKeyClass.FEE_PARAMETER);
    root("MAX_CPU_TIME_OF_ONE_TX", DynamicKeyClass.FEE_PARAMETER);
    root("MEMO_FEE", DynamicKeyClass.FEE_PARAMETER);
    root("CREATE_ACCOUNT_FEE", DynamicKeyClass.FEE_PARAMETER);
    root("CREATE_NEW_ACCOUNT_FEE_IN_SYSTEM_CONTRACT", DynamicKeyClass.FEE_PARAMETER);
    root("ASSET_ISSUE_FEE", DynamicKeyClass.FEE_PARAMETER);
    root("UPDATE_ACCOUNT_PERMISSION_FEE", DynamicKeyClass.FEE_PARAMETER);
    root("MULTI_SIGN_FEE", DynamicKeyClass.FEE_PARAMETER);
    root("EXCHANGE_CREATE_FEE", DynamicKeyClass.FEE_PARAMETER);
    root("MARKET_SELL_FEE", DynamicKeyClass.FEE_PARAMETER);
    root("MARKET_CANCEL_FEE", DynamicKeyClass.FEE_PARAMETER);

    // --- IN_GLOBAL_ROOT: resource / validation parameters ---
    root("CREATE_NEW_ACCOUNT_BANDWIDTH_RATE", DynamicKeyClass.RESOURCE_PARAMETER);
    root("FREE_NET_LIMIT", DynamicKeyClass.RESOURCE_PARAMETER);
    root("TOTAL_ENERGY_LIMIT", DynamicKeyClass.RESOURCE_PARAMETER);
    root("TOTAL_NET_LIMIT", DynamicKeyClass.RESOURCE_PARAMETER);
    root("TOTAL_ENERGY_CURRENT_LIMIT", DynamicKeyClass.RESOURCE_PARAMETER);
    root("TOTAL_ENERGY_TARGET_LIMIT", DynamicKeyClass.RESOURCE_PARAMETER);
    root("ADAPTIVE_RESOURCE_LIMIT_TARGET_RATIO", DynamicKeyClass.RESOURCE_PARAMETER);
    root("ADAPTIVE_RESOURCE_LIMIT_MULTIPLIER", DynamicKeyClass.RESOURCE_PARAMETER);
    root("TOTAL_NET_WEIGHT", DynamicKeyClass.RESOURCE_PARAMETER);
    root("TOTAL_ENERGY_WEIGHT", DynamicKeyClass.RESOURCE_PARAMETER);
    root("TOTAL_TRON_POWER_WEIGHT", DynamicKeyClass.RESOURCE_PARAMETER);
    root("EXCHANGE_BALANCE_LIMIT", DynamicKeyClass.RESOURCE_PARAMETER);
    root("MARKET_QUANTITY_LIMIT", DynamicKeyClass.RESOURCE_PARAMETER);
    root("MAX_DELEGATE_LOCK_PERIOD", DynamicKeyClass.RESOURCE_PARAMETER);
    root("MAX_CREATE_ACCOUNT_TX_SIZE", DynamicKeyClass.RESOURCE_PARAMETER);

    // --- IN_GLOBAL_ROOT: governance / protocol validation knobs ---
    root("MAINTENANCE_TIME_INTERVAL", DynamicKeyClass.GOVERNANCE_PARAMETER);
    root("ACCOUNT_UPGRADE_COST", DynamicKeyClass.GOVERNANCE_PARAMETER);
    root("WITNESS_PAY_PER_BLOCK", DynamicKeyClass.GOVERNANCE_PARAMETER);
    root("WITNESS_127_PAY_PER_BLOCK", DynamicKeyClass.GOVERNANCE_PARAMETER);
    root("WITNESS_STANDBY_ALLOWANCE", DynamicKeyClass.GOVERNANCE_PARAMETER);
    root("REMOVE_THE_POWER_OF_THE_GR", DynamicKeyClass.GOVERNANCE_PARAMETER);
    root("ALLOW_UPDATE_ACCOUNT_NAME", DynamicKeyClass.GOVERNANCE_PARAMETER);
    // DynamicPropertiesStore's persisted key intentionally has a leading space.
    root(" ALLOW_SAME_TOKEN_NAME", DynamicKeyClass.GOVERNANCE_PARAMETER);
    root("ALLOW_SAME_TOKEN_NAME", DynamicKeyClass.GOVERNANCE_PARAMETER);
    root("ALLOW_DELEGATE_RESOURCE", DynamicKeyClass.GOVERNANCE_PARAMETER);
    root("ALLOW_ADAPTIVE_ENERGY", DynamicKeyClass.GOVERNANCE_PARAMETER);
    root("ALLOW_PROTO_FILTER_NUM", DynamicKeyClass.GOVERNANCE_PARAMETER);
    root("ALLOW_ACCOUNT_STATE_ROOT", DynamicKeyClass.GOVERNANCE_PARAMETER);
    root("CHANGE_DELEGATION", DynamicKeyClass.GOVERNANCE_PARAMETER);
    root("FORBID_TRANSFER_TO_CONTRACT", DynamicKeyClass.GOVERNANCE_PARAMETER);
    root("ALLOW_PBFT", DynamicKeyClass.GOVERNANCE_PARAMETER);
    root("ALLOW_MARKET_TRANSACTION", DynamicKeyClass.GOVERNANCE_PARAMETER);
    root("ALLOW_TRANSACTION_FEE_POOL", DynamicKeyClass.GOVERNANCE_PARAMETER);
    root("ALLOW_BLACKHOLE_OPTIMIZATION", DynamicKeyClass.GOVERNANCE_PARAMETER);
    root("ALLOW_ACCOUNT_ASSET_OPTIMIZATION", DynamicKeyClass.GOVERNANCE_PARAMETER);
    root("ALLOW_ASSET_OPTIMIZATION", DynamicKeyClass.GOVERNANCE_PARAMETER);
    root("ALLOW_NEW_REWARD", DynamicKeyClass.GOVERNANCE_PARAMETER);
    root("ALLOW_DELEGATE_OPTIMIZATION", DynamicKeyClass.GOVERNANCE_PARAMETER);
    root("ALLOW_CANCEL_ALL_UNFREEZE_V2", DynamicKeyClass.GOVERNANCE_PARAMETER);
    root("ALLOW_OLD_REWARD_OPT", DynamicKeyClass.GOVERNANCE_PARAMETER);
    root("PROPOSAL_EXPIRE_TIME", DynamicKeyClass.GOVERNANCE_PARAMETER);
    root("LATEST_PROPOSAL_NUM", DynamicKeyClass.GOVERNANCE_PARAMETER);
    root("LATEST_EXCHANGE_NUM", DynamicKeyClass.GOVERNANCE_PARAMETER);
    root("TOTAL_SIGN_NUM", DynamicKeyClass.GOVERNANCE_PARAMETER);
    root("TOKEN_ID_NUM", DynamicKeyClass.GOVERNANCE_PARAMETER);

    // --- IN_GLOBAL_ROOT: VM / fork-gate config (changes historical execution) ---
    root("ALLOW_CREATION_OF_CONTRACTS", DynamicKeyClass.VM_CONFIG);
    root("ALLOW_TVM_TRANSFER_TRC10", DynamicKeyClass.VM_CONFIG);
    root("ALLOW_TVM_CONSTANTINOPLE", DynamicKeyClass.VM_CONFIG);
    root("ALLOW_TVM_SOLIDITY_059", DynamicKeyClass.VM_CONFIG);
    root("ALLOW_TVM_ISTANBUL", DynamicKeyClass.VM_CONFIG);
    root("ALLOW_TVM_FREEZE", DynamicKeyClass.VM_CONFIG);
    root("ALLOW_TVM_VOTE", DynamicKeyClass.VM_CONFIG);
    root("ALLOW_TVM_LONDON", DynamicKeyClass.VM_CONFIG);
    root("ALLOW_TVM_COMPATIBLE_EVM", DynamicKeyClass.VM_CONFIG);
    root("ALLOW_HIGHER_LIMIT_FOR_MAX_CPU_TIME_OF_ONE_TX", DynamicKeyClass.VM_CONFIG);
    root("ALLOW_OPTIMIZED_RETURN_VALUE_OF_CHAIN_ID", DynamicKeyClass.VM_CONFIG);
    root("CURRENT_CYCLE_NUMBER", DynamicKeyClass.VM_CONFIG);
    root("ALLOW_DYNAMIC_ENERGY", DynamicKeyClass.VM_CONFIG);
    root("DYNAMIC_ENERGY_THRESHOLD", DynamicKeyClass.VM_CONFIG);
    root("DYNAMIC_ENERGY_INCREASE_FACTOR", DynamicKeyClass.VM_CONFIG);
    root("DYNAMIC_ENERGY_MAX_FACTOR", DynamicKeyClass.VM_CONFIG);
    root("ALLOW_TVM_SHANGHAI", DynamicKeyClass.VM_CONFIG);
    root("ALLOW_ENERGY_ADJUSTMENT", DynamicKeyClass.VM_CONFIG);
    root("ALLOW_STRICT_MATH", DynamicKeyClass.VM_CONFIG);
    root("ALLOW_TVM_CANCUN", DynamicKeyClass.VM_CONFIG);
    root("CONSENSUS_LOGIC_OPTIMIZATION", DynamicKeyClass.VM_CONFIG);
    root("ALLOW_TVM_BLOB", DynamicKeyClass.VM_CONFIG);
    root("ALLOW_TVM_SELFDESTRUCT_RESTRICTION", DynamicKeyClass.VM_CONFIG);
    root("ALLOW_TVM_OSAKA", DynamicKeyClass.VM_CONFIG);
    root("ALLOW_TVM_PRAGUE", DynamicKeyClass.VM_CONFIG);
    root("ALLOW_HARDEN_RESOURCE_CALCULATION", DynamicKeyClass.VM_CONFIG);
    root("ALLOW_HARDEN_EXCHANGE_CALCULATION", DynamicKeyClass.VM_CONFIG);
    root("ALLOW_NEW_RESOURCE_MODEL", DynamicKeyClass.VM_CONFIG);
    // These also change historical execution: UNFREEZE_DELAY_DAYS gates the FreezeV2 opcodes
    // (FREEZEBALANCEV2 / DELEGATERESOURCE), shielded-TRC20 gates the verify* precompiles, and
    // multi-sign changes ADDRESS / ORIGIN return bytes -- so they belong in the VM_CONFIG root.
    root("UNFREEZE_DELAY_DAYS", DynamicKeyClass.VM_CONFIG);
    root("ALLOW_SHIELDED_TRC20_TRANSACTION", DynamicKeyClass.VM_CONFIG);
    root("ALLOW_MULTI_SIGN", DynamicKeyClass.VM_CONFIG);

    // --- HISTORY_ONLY: header cursors + price history ---
    historyOnly("latest_block_header_timestamp", DynamicKeyClass.HEADER_CURSOR);
    historyOnly("latest_block_header_number", DynamicKeyClass.HEADER_CURSOR);
    historyOnly("latest_block_header_hash", DynamicKeyClass.HEADER_CURSOR);
    historyOnly("LATEST_SOLIDIFIED_BLOCK_NUM", DynamicKeyClass.HEADER_CURSOR);
    historyOnly("ENERGY_PRICE_HISTORY", DynamicKeyClass.PRICE_HISTORY);
    historyOnly("BANDWIDTH_PRICE_HISTORY", DynamicKeyClass.PRICE_HISTORY);
    historyOnly("MEMO_FEE_HISTORY", DynamicKeyClass.PRICE_HISTORY);

    // --- EXCLUDED: one-time migration markers + aggregate statistics ---
    excluded("ABI_MOVE_DONE", DynamicKeyClass.MIGRATION_MARKER);
    excluded("ENERGY_PRICE_HISTORY_DONE", DynamicKeyClass.MIGRATION_MARKER);
    excluded("BANDWIDTH_PRICE_HISTORY_DONE", DynamicKeyClass.MIGRATION_MARKER);
    excluded("TURKISH_KEY_MIGRATION_DONE", DynamicKeyClass.MIGRATION_MARKER);
    excluded("BLOCK_HASH_HISTORY_INSTALLED", DynamicKeyClass.MIGRATION_MARKER);
    excluded("state_flag", DynamicKeyClass.INDEX_CURSOR);
    excluded("TOTAL_TRANSACTION_COST", DynamicKeyClass.STATISTIC);
    excluded("TOTAL_CREATE_ACCOUNT_COST", DynamicKeyClass.STATISTIC);
    excluded("TOTAL_CREATE_WITNESS_FEE", DynamicKeyClass.STATISTIC);
    excluded("TOTAL_STORAGE_POOL", DynamicKeyClass.STATISTIC);
    excluded("TOTAL_STORAGE_TAX", DynamicKeyClass.STATISTIC);
    excluded("TOTAL_STORAGE_RESERVED", DynamicKeyClass.STATISTIC);
    excluded("TRANSACTION_FEE_POOL", DynamicKeyClass.STATISTIC);
  }

  private void root(String key, DynamicKeyClass keyClass) {
    decisions.put(key, new DynamicKeyDecision(key, keyClass,
        RootPolicy.IN_GLOBAL_ROOT, HistoryPolicy.FULL_HISTORY, ReaderPolicy.HISTORICAL_VM));
  }

  private void historyOnly(String key, DynamicKeyClass keyClass) {
    decisions.put(key, new DynamicKeyDecision(key, keyClass,
        RootPolicy.HISTORY_ONLY, HistoryPolicy.FULL_HISTORY, ReaderPolicy.INTERNAL_ONLY));
  }

  private void excluded(String key, DynamicKeyClass keyClass) {
    decisions.put(key, new DynamicKeyDecision(key, keyClass,
        RootPolicy.EXCLUDED, HistoryPolicy.NO_ARCHIVE, ReaderPolicy.INTERNAL_ONLY));
  }

  /** Decision for a dynamic property key (ASCII bytes); unknown keys are excluded-from-root. */
  public DynamicKeyDecision decision(byte[] key) {
    String name = new String(key, StandardCharsets.US_ASCII);
    DynamicKeyDecision decision = decisions.get(name);
    if (decision != null) {
      return decision;
    }
    // Unknown: keep history (diagnostic), never root, until explicitly classified.
    return new DynamicKeyDecision(name, DynamicKeyClass.UNKNOWN,
        RootPolicy.EXCLUDED, HistoryPolicy.FULL_HISTORY, ReaderPolicy.INTERNAL_ONLY);
  }

  public List<DynamicKeyDecision> allDecisions() {
    return Collections.unmodifiableList(new ArrayList<>(decisions.values()));
  }
}
