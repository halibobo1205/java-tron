package org.tron.core.archive.domain;

/**
 * How the L4 write collector should treat raw {@code TronStoreWithRevoking.put/delete} writes for a
 * store. Non-revoking ({@code TronDatabase}) stores must never be {@code GENERIC_TRON_STORE}.
 */
public enum RawHookMode {
  /** Generic store: raw put/delete maps 1:1 to a domain write. */
  GENERIC_TRON_STORE,
  /** Generic store, but only keys on the per-key allowlist are rooted (e.g. dynamic-properties). */
  GENERIC_TRON_STORE_ALLOWLIST,
  /** Store has a non-generic write path (e.g. ContractStore clears ABI then writes directly). */
  STORE_SPECIFIC,
  /** No domain write from raw hook; a semantic hook supplies it (e.g. storage-row). */
  SEMANTIC_ONLY,
  /** Raw writes are ignored (excluded / index / operational stores). */
  IGNORE_RAW
}
