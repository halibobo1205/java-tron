package org.tron.core.archive.domain;

/**
 * Stable archive domain identity. The {@code id} (u16) is the persistent domain id used in archive
 * keys and root aggregation order - it is NOT the enum ordinal and must never be reused/reordered.
 *
 * <p>Ids and names follow the frozen domain registry in
 * {@code docs/archiveV3/00-ARCHIVE-V3-AUTHORITY-AND-DECISIONS.md} (u16 flat, supersedes the older
 * {@code 0x01XX} grouped scheme in the L3 code plan). {@code 0x0000} is reserved for the global
 * root tree.
 */
public enum ArchiveDomain {

  ACCOUNT(0x0001, "account"),
  ACCOUNT_ASSET(0x0002, "account-asset"),
  ASSET_ISSUE(0x0005, "asset-issue"),
  ABI(0x0006, "abi"),
  CODE(0x0007, "code"),
  CONTRACT(0x0008, "contract"),
  DELEGATION(0x0009, "delegation"),
  DELEGATED_RESOURCE(0x000a, "delegated-resource"),
  EXCHANGE(0x000c, "exchange"),
  EXCHANGE_V2(0x000d, "exchange-v2"),
  MARKET_ACCOUNT(0x000f, "market-account"),
  MARKET_ORDER(0x0010, "market-order"),
  DYNAMIC_PROPERTIES(0x0014, "dynamic-properties"),
  PROPOSAL(0x0015, "proposal"),
  CONTRACT_STORAGE(0x0016, "contract-storage"),
  VOTES(0x0017, "votes"),
  WITNESS(0x0018, "witness"),
  CONTRACT_STATE(0x0020, "contract-state");

  private final int id;
  private final String canonicalName;

  ArchiveDomain(int id, String canonicalName) {
    this.id = id;
    this.canonicalName = canonicalName;
  }

  public int getId() {
    return id;
  }

  public String getCanonicalName() {
    return canonicalName;
  }
}
