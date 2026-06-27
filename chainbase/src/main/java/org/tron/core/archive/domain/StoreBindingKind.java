package org.tron.core.archive.domain;

/** Classification of a java-tron store (by dbName) in the archive schema. */
public enum StoreBindingKind {
  /** Backs a P0 archive domain directly. */
  DOMAIN,
  /** Physical backing for a semantic domain (e.g. storage-row for CONTRACT_STORAGE). */
  SEMANTIC_BACKING,
  /** Backs a P1/later domain (captured, not necessarily P0-rooted). */
  P1_DOMAIN,
  /** Explicitly excluded from archive state, with a recorded reason. */
  EXCLUDED,
  /** dbName not recognised - must be surfaced as a warning, never silently treated as excluded. */
  UNKNOWN
}
