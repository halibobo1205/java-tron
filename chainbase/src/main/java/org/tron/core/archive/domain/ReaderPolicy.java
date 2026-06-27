package org.tron.core.archive.domain;

/** Who may read a domain/key through the archive state reader. */
public enum ReaderPolicy {
  /** Publicly readable historical state (eth_getBalance/getCode/getStorageAt). */
  PUBLIC_STATE,
  /** Needed to reconstruct historical VM execution (eth_call), not necessarily a public getter. */
  HISTORICAL_VM,
  /** Internal/diagnostic only. */
  INTERNAL_ONLY,
  /** Not exposed to readers. */
  NOT_READABLE
}
