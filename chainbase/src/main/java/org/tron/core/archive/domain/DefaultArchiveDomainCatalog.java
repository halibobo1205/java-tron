package org.tron.core.archive.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.codec.AccountCanonicalValueCodec;
import org.tron.core.archive.codec.Address21KeyCodec;
import org.tron.core.archive.codec.BytesValueCodec;
import org.tron.core.archive.codec.CanonicalKeyCodec;
import org.tron.core.archive.codec.CanonicalValueCodec;
import org.tron.core.archive.codec.ContractCanonicalValueCodec;
import org.tron.core.archive.codec.ContractStorageKeyCodec;
import org.tron.core.archive.codec.DeterministicProtoValueCodec;
import org.tron.core.archive.codec.DynamicPropertyKeyCodec;
import org.tron.core.archive.codec.FixedLengthKeyCodec;
import org.tron.core.archive.codec.RawKeyCodec;
import org.tron.protos.Protocol.DelegatedResource;
import org.tron.protos.Protocol.Exchange;
import org.tron.protos.Protocol.MarketAccountOrder;
import org.tron.protos.Protocol.MarketOrder;
import org.tron.protos.Protocol.Proposal;
import org.tron.protos.Protocol.Votes;
import org.tron.protos.Protocol.Witness;
import org.tron.protos.contract.AssetIssueContractOuterClass.AssetIssueContract;
import org.tron.protos.contract.SmartContractOuterClass.ContractState;
import org.tron.protos.contract.SmartContractOuterClass.SmartContract;

/**
 * The frozen per-domain codec + policy catalog, wired per the domain canonicalization survey
 * (decision 4). Every IN_GLOBAL_ROOT proto-valued domain routes through a deterministic value codec
 * so map fields (e.g. {@code Proposal.parameters}) are serialized in sorted-key order; ACCOUNT and
 * CONTRACT additionally strip local/foreign fields (assets / abi).
 */
public final class DefaultArchiveDomainCatalog implements ArchiveDomainCatalog {

  private final Map<ArchiveDomain, ArchiveDomainDescriptor> byDomain =
      new EnumMap<>(ArchiveDomain.class);
  private final DynamicKeyPolicy dynamicKeyPolicy = new DynamicKeyPolicy();

  public DefaultArchiveDomainCatalog() {
    // Shared, stateless codec instances reused across domains.
    CanonicalKeyCodec address21 = new Address21KeyCodec("tron-address21-v1");
    CanonicalKeyCodec id8 = new FixedLengthKeyCodec("tron-id8-v1", 8);

    // ACCOUNT: strip asset/assetV2/asset_optimized + deterministic (6 map fields).
    rootDomain(ArchiveDomain.ACCOUNT, "account", address21,
        new AccountCanonicalValueCodec(), ReaderPolicy.PUBLIC_STATE);
    // ASSET_ISSUE: AssetIssueContract (no maps), id keyed by variable token id bytes.
    rootDomain(ArchiveDomain.ASSET_ISSUE, "asset-issue-v2", new RawKeyCodec("tron-asset-id-key-v1"),
        new DeterministicProtoValueCodec("asset-issue-contract-v1", AssetIssueContract.parser()),
        ReaderPolicy.PUBLIC_STATE);
    // CODE: raw EVM bytecode (not a proto).
    rootDomain(ArchiveDomain.CODE, "code", address21,
        new BytesValueCodec("bytecode-bytes-v1", true), ReaderPolicy.PUBLIC_STATE);
    // CONTRACT: strip SmartContract.abi (separate ABI domain) + deterministic.
    rootDomain(ArchiveDomain.CONTRACT, "contract", address21,
        new ContractCanonicalValueCodec(), ReaderPolicy.HISTORICAL_VM);
    // DELEGATION: heterogeneous primitive values (longs/addresses), raw key + raw value.
    rootDomain(ArchiveDomain.DELEGATION, "delegation", new RawKeyCodec("tron-delegation-key-v1"),
        new BytesValueCodec("delegation-raw-v1", true), ReaderPolicy.INTERNAL_ONLY);
    // DELEGATED_RESOURCE: DelegatedResource (no maps), from||to key (variable across v1/v2).
    rootDomain(ArchiveDomain.DELEGATED_RESOURCE, "DelegatedResource",
        new RawKeyCodec("tron-delegated-resource-key-v1"),
        new DeterministicProtoValueCodec("delegated-resource-v1", DelegatedResource.parser()),
        ReaderPolicy.PUBLIC_STATE);
    // EXCHANGE / EXCHANGE_V2: Exchange proto, 8-byte id.
    rootDomain(ArchiveDomain.EXCHANGE, "exchange", id8,
        new DeterministicProtoValueCodec("exchange-proto-v1", Exchange.parser()),
        ReaderPolicy.PUBLIC_STATE);
    rootDomain(ArchiveDomain.EXCHANGE_V2, "exchange-v2", id8,
        new DeterministicProtoValueCodec("exchange-proto-v1", Exchange.parser()),
        ReaderPolicy.PUBLIC_STATE);
    // MARKET_ACCOUNT: MarketAccountOrder, 21-byte owner address.
    rootDomain(ArchiveDomain.MARKET_ACCOUNT, "market_account", address21,
        new DeterministicProtoValueCodec("market-account-order-v1", MarketAccountOrder.parser()),
        ReaderPolicy.PUBLIC_STATE);
    // MARKET_ORDER: MarketOrder, 32-byte order-id hash.
    rootDomain(ArchiveDomain.MARKET_ORDER, "market_order",
        new FixedLengthKeyCodec("tron-order-id-v1", 32),
        new DeterministicProtoValueCodec("market-order-v1", MarketOrder.parser()),
        ReaderPolicy.PUBLIC_STATE);
    // DYNAMIC_PROPERTIES: raw per-key bytes; key-level root/history via DynamicKeyPolicy.
    rootDomain(ArchiveDomain.DYNAMIC_PROPERTIES, "properties", new DynamicPropertyKeyCodec(),
        new BytesValueCodec("dynamic-property-raw-v1", true), ReaderPolicy.HISTORICAL_VM);
    // PROPOSAL: Proposal HAS map<int64,int64> parameters -> deterministic is MANDATORY.
    rootDomain(ArchiveDomain.PROPOSAL, "proposal", id8,
        new DeterministicProtoValueCodec("proposal-v1", Proposal.parser()),
        ReaderPolicy.PUBLIC_STATE);
    // VOTES / WITNESS: 21-byte address.
    rootDomain(ArchiveDomain.VOTES, "votes", address21,
        new DeterministicProtoValueCodec("votes-v1", Votes.parser()), ReaderPolicy.PUBLIC_STATE);
    rootDomain(ArchiveDomain.WITNESS, "witness", address21,
        new DeterministicProtoValueCodec("witness-v1", Witness.parser()),
        ReaderPolicy.PUBLIC_STATE);
    // CONTRACT_STATE: ContractState (energy usage/factor), 21-byte contract address.
    rootDomain(ArchiveDomain.CONTRACT_STATE, "contract-state", address21,
        new DeterministicProtoValueCodec("contract-state-v1", ContractState.parser()),
        ReaderPolicy.HISTORICAL_VM);
    // CONTRACT_STORAGE: 32-byte word, semantic 54-byte key (address||slot||version).
    rootDomain(ArchiveDomain.CONTRACT_STORAGE, "storage-row", new ContractStorageKeyCodec(),
        new BytesValueCodec("storage-word-v1", false), ReaderPolicy.PUBLIC_STATE);
    // ACCOUNT_ASSET: TRC10 balance (int64) captured semantically; address||assetId key.
    rootDomain(ArchiveDomain.ACCOUNT_ASSET, "account-asset",
        new RawKeyCodec("tron-account-asset-key-v1"),
        new BytesValueCodec("trc10-balance-v1", false), ReaderPolicy.PUBLIC_STATE);

    // ABI: HISTORY_ONLY (client metadata), SmartContract.ABI proto, 21-byte contract address.
    add(ArchiveDomain.ABI, "abi", address21,
        new DeterministicProtoValueCodec("abi-v1", SmartContract.ABI.parser()),
        RootPolicy.HISTORY_ONLY, HistoryPolicy.FULL_HISTORY, ReaderPolicy.INTERNAL_ONLY);
  }

  private void rootDomain(ArchiveDomain domain, String sourceDbName, CanonicalKeyCodec keyCodec,
      CanonicalValueCodec valueCodec, ReaderPolicy readerPolicy) {
    add(domain, sourceDbName, keyCodec, valueCodec,
        RootPolicy.IN_GLOBAL_ROOT, HistoryPolicy.FULL_HISTORY, readerPolicy);
  }

  private void add(ArchiveDomain domain, String sourceDbName, CanonicalKeyCodec keyCodec,
      CanonicalValueCodec valueCodec, RootPolicy rootPolicy, HistoryPolicy historyPolicy,
      ReaderPolicy readerPolicy) {
    if (byDomain.containsKey(domain)) {
      throw new ArchiveException("duplicate domain in archive catalog: " + domain);
    }
    byDomain.put(domain, new ArchiveDomainDescriptor(
        domain, sourceDbName, keyCodec, valueCodec, rootPolicy, historyPolicy, readerPolicy));
  }

  @Override
  public ArchiveDomainDescriptor descriptorFor(ArchiveDomain domain) {
    return byDomain.get(domain);
  }

  @Override
  public List<ArchiveDomainDescriptor> allDescriptors() {
    List<ArchiveDomainDescriptor> list = new ArrayList<>(byDomain.values());
    list.sort(Comparator.comparingInt(d -> d.getDomain().getId()));
    return list;
  }

  @Override
  public List<ArchiveDomainDescriptor> rootDescriptors() {
    List<ArchiveDomainDescriptor> list = new ArrayList<>();
    for (ArchiveDomainDescriptor d : allDescriptors()) {
      if (d.getRootPolicy() == RootPolicy.IN_GLOBAL_ROOT) {
        list.add(d);
      }
    }
    return list;
  }

  @Override
  public byte[] checksum() {
    StringBuilder sb = new StringBuilder("tron-archive-catalog-v1\n");
    for (ArchiveDomainDescriptor d : allDescriptors()) {
      sb.append(Integer.toHexString(d.getDomain().getId())).append('|')
          .append(d.getSourceDbName()).append('|')
          .append(d.getKeyCodec().codecId()).append('|')
          .append(d.getValueCodec().codecId()).append('|')
          .append(d.getRootPolicy()).append('|')
          .append(d.getHistoryPolicy()).append('|')
          .append(d.getReaderPolicy()).append('\n');
    }
    // The DYNAMIC_PROPERTIES key-level policy is part of the schema.
    for (DynamicKeyDecision dec : dynamicKeyPolicy.allDecisions()) {
      sb.append("dyn|").append(dec.getKey()).append('|').append(dec.getKeyClass()).append('|')
          .append(dec.getRootPolicy()).append('|').append(dec.getHistoryPolicy()).append('|')
          .append(dec.getReaderPolicy()).append('\n');
    }
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new ArchiveException("SHA-256 unavailable for archive catalog checksum", e);
    }
  }
}
