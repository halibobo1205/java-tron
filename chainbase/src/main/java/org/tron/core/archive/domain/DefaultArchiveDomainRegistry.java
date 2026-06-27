package org.tron.core.archive.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.tron.core.archive.ArchiveException;

/**
 * Frozen archive schema for java-tron, per the domain registry in
 * {@code docs/archiveV3/00-ARCHIVE-V3-AUTHORITY-AND-DECISIONS.md}. Every known store dbName is
 * classified explicitly; an unknown dbName resolves to {@link StoreBinding#unknown}.
 *
 * <p>17 domains are {@code IN_GLOBAL_ROOT} (all canonical block-tx-driven state - globalRoot is a
 * near-complete TRON state root); ABI is the only {@code HISTORY_ONLY} domain (client metadata);
 * derived/index/receipt/one-time/operational stores are {@code EXCLUDED} with a recorded reason.
 */
public final class DefaultArchiveDomainRegistry implements ArchiveDomainRegistry {

  private final Map<String, StoreBinding> byDbName = new LinkedHashMap<>();

  public DefaultArchiveDomainRegistry() {
    // --- IN_GLOBAL_ROOT, generic store, FULL_HISTORY ---
    domain("account", ArchiveDomain.ACCOUNT, RawHookMode.GENERIC_TRON_STORE);
    domain("asset-issue-v2", ArchiveDomain.ASSET_ISSUE, RawHookMode.GENERIC_TRON_STORE);
    domain("code", ArchiveDomain.CODE, RawHookMode.GENERIC_TRON_STORE);
    domain("delegation", ArchiveDomain.DELEGATION, RawHookMode.GENERIC_TRON_STORE);
    domain("DelegatedResource", ArchiveDomain.DELEGATED_RESOURCE, RawHookMode.GENERIC_TRON_STORE);
    domain("exchange", ArchiveDomain.EXCHANGE, RawHookMode.GENERIC_TRON_STORE);
    domain("exchange-v2", ArchiveDomain.EXCHANGE_V2, RawHookMode.GENERIC_TRON_STORE);
    domain("market_account", ArchiveDomain.MARKET_ACCOUNT, RawHookMode.GENERIC_TRON_STORE);
    domain("market_order", ArchiveDomain.MARKET_ORDER, RawHookMode.GENERIC_TRON_STORE);
    domain("proposal", ArchiveDomain.PROPOSAL, RawHookMode.GENERIC_TRON_STORE);
    domain("votes", ArchiveDomain.VOTES, RawHookMode.GENERIC_TRON_STORE);
    domain("witness", ArchiveDomain.WITNESS, RawHookMode.GENERIC_TRON_STORE);
    // IN_GLOBAL_ROOT, store-specific write path (clears/transforms before revokingDB.put)
    domain("contract", ArchiveDomain.CONTRACT, RawHookMode.STORE_SPECIFIC);
    domain("contract-state", ArchiveDomain.CONTRACT_STATE, RawHookMode.STORE_SPECIFIC);
    // IN_GLOBAL_ROOT, key-level allowlist (only VM-relevant config keys are rooted)
    domain("properties", ArchiveDomain.DYNAMIC_PROPERTIES,
        RawHookMode.GENERIC_TRON_STORE_ALLOWLIST);

    // --- HISTORY_ONLY (client metadata, not consensus-affecting) ---
    add("abi", StoreBindingKind.DOMAIN, ArchiveDomain.ABI, RawHookMode.GENERIC_TRON_STORE,
        RootPolicy.HISTORY_ONLY, HistoryPolicy.FULL_HISTORY,
        "ABI is immutable client metadata; queryable but not VM/consensus-affecting, not rooted");

    // --- IN_GLOBAL_ROOT semantic backing (domain from a semantic hook, raw store ignored) ---
    add("account-asset", StoreBindingKind.SEMANTIC_BACKING, ArchiveDomain.ACCOUNT_ASSET,
        RawHookMode.IGNORE_RAW, RootPolicy.IN_GLOBAL_ROOT, HistoryPolicy.FULL_HISTORY,
        "TRC10 balances captured semantically from account.assetV2 (decision 2); flush-layer "
            + "store puts ignored, read-through source");
    add("storage-row", StoreBindingKind.SEMANTIC_BACKING, ArchiveDomain.CONTRACT_STORAGE,
        RawHookMode.SEMANTIC_ONLY, RootPolicy.IN_GLOBAL_ROOT, HistoryPolicy.FULL_HISTORY,
        "raw storage-row key is irreversible; CONTRACT_STORAGE comes from a semantic hook "
            + "(address|slot|version)");

    // --- EXCLUDED (explicit, with reason) ---
    excluded("reward-vi", "one-time immutable accumulator with its own main-net root "
        + "(RewardViCalService); read-through, no archive history");
    excluded("nullifier", "shielded pool; AllowShieldedTRC20Transaction disabled + zk-private");
    excluded("IncrementalMerkleTree", "shielded pool; disabled + zk-private");
    excluded("account-index", "derived account index");
    excluded("accountid-index", "derived accountId index");
    excluded("DelegatedResourceAccountIndex", "derived delegated-resource index");
    excluded("market_pair_to_price", "derived market index");
    excluded("market_pair_price_to_order", "derived market index");
    excluded("section-bloom", "log bloom filter, derived from receipts");
    excluded("account-trace", "legacy balance-history-lookup data, superseded by archive");
    excluded("balance-trace", "legacy balance-history-lookup data, superseded by archive");
    excluded("transactionHistoryStore", "transaction info/receipts, not state");
    excluded("transactionRetStore", "transaction results/receipts, not state");
    excluded("tree-block-index", "block index, chain data");
    excluded("witness_schedule", "derived from votes (top-N active set), rebuildable");
    excluded("asset-issue", "legacy v1 asset store (post-AllowSameTokenName dead)");
    excluded("block", "block data, not state");
    excluded("block-index", "block id index, chain data");
    excluded("block_KDB", "khaos fork block db");
    excluded("recent-block", "recent block cache");
    excluded("recent-transaction", "recent transaction cache");
    excluded("trans", "transaction store, not state");
    excluded("trans-cache", "transaction bloom cache");
    excluded("common", "operational metadata");
    excluded("common-database", "operational metadata");
    excluded("pbft-sign-data", "PBFT consensus signatures, not state");
    excluded("tmp", "tmp checkpoint, operational");
    excluded("zkProof", "zk proof cache, operational");
  }

  private void domain(String dbName, ArchiveDomain domain, RawHookMode rawHookMode) {
    add(dbName, StoreBindingKind.DOMAIN, domain, rawHookMode,
        RootPolicy.IN_GLOBAL_ROOT, HistoryPolicy.FULL_HISTORY, "P0 in-global-root state domain");
  }

  private void excluded(String dbName, String reason) {
    add(dbName, StoreBindingKind.EXCLUDED, null, RawHookMode.IGNORE_RAW,
        RootPolicy.EXCLUDED, HistoryPolicy.NO_ARCHIVE, reason);
  }

  private void add(String dbName, StoreBindingKind kind, ArchiveDomain domain,
      RawHookMode rawHookMode, RootPolicy rootPolicy, HistoryPolicy historyPolicy, String reason) {
    if (byDbName.containsKey(dbName)) {
      throw new ArchiveException("duplicate dbName in archive registry: " + dbName);
    }
    byDbName.put(dbName,
        new StoreBinding(dbName, kind, domain, rawHookMode, rootPolicy, historyPolicy, reason));
  }

  @Override
  public StoreBinding bindingForDbName(String dbName) {
    StoreBinding binding = byDbName.get(dbName);
    return (binding != null) ? binding : StoreBinding.unknown(dbName);
  }

  @Override
  public List<StoreBinding> allStoreBindings() {
    List<StoreBinding> bindings = new ArrayList<>(byDbName.values());
    bindings.sort(Comparator.comparing(StoreBinding::getDbName));
    return bindings;
  }

  @Override
  public List<ArchiveDomain> rootDomains() {
    List<ArchiveDomain> domains = new ArrayList<>();
    for (StoreBinding binding : byDbName.values()) {
      if (binding.getRootPolicy() == RootPolicy.IN_GLOBAL_ROOT && binding.getDomain().isPresent()) {
        domains.add(binding.getDomain().get());
      }
    }
    domains.sort(Comparator.comparingInt(ArchiveDomain::getId));
    return domains;
  }

  @Override
  public byte[] checksum() {
    // Sorted-by-dbName serialization so the checksum is stable regardless of insert order.
    StringBuilder sb = new StringBuilder("tron-archive-registry-v1\n");
    Map<String, StoreBinding> sorted = new TreeMap<>(byDbName);
    for (StoreBinding b : sorted.values()) {
      sb.append(b.getDbName()).append('|')
          .append(b.getKind()).append('|')
          .append(b.getDomain().map(d -> Integer.toHexString(d.getId())).orElse("-")).append('|')
          .append(b.getRawHookMode()).append('|')
          .append(b.getRootPolicy()).append('|')
          .append(b.getHistoryPolicy()).append('\n');
    }
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new ArchiveException("SHA-256 unavailable for archive registry checksum", e);
    }
  }
}
