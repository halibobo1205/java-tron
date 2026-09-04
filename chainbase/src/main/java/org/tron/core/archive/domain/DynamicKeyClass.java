package org.tron.core.archive.domain;

/** Classification of a dynamic-properties key, driving its root/history/reader policy. */
public enum DynamicKeyClass {
  /** Block header cursors (latest block number/hash/timestamp). */
  HEADER_CURSOR,
  /** VM / fork-gate config that changes historical execution (ALLOW_TVM_*, ALLOW_*). */
  VM_CONFIG,
  /** Resource model parameters. */
  RESOURCE_PARAMETER,
  /** Fee parameters (energy/bandwidth/memo fee, max cpu time). */
  FEE_PARAMETER,
  /** Price history series. */
  PRICE_HISTORY,
  /** Governance parameters. */
  GOVERNANCE_PARAMETER,
  /** Index/cursor markers. */
  INDEX_CURSOR,
  /** Aggregate statistics (totals). */
  STATISTIC,
  /** One-time migration / done markers. */
  MIGRATION_MARKER,
  /** Unrecognised key. */
  UNKNOWN
}
