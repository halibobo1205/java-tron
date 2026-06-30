package org.tron.core.archive.domain;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Key-level policy for the DYNAMIC_PROPERTIES domain. Only VM/fee config keys changing historical
 * execution enter the global root; header cursors and price history are kept history-only; one-time
 * migration markers and aggregate statistics are excluded. Unknown keys default to
 * {@code EXCLUDED} root + {@code FULL_HISTORY} (keep history, don't root) so a future
 * execution-affecting key is never silently lost to history - but it never enters the root until
 * explicitly classified and the registry checksum updated.
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
