package org.tron.core.archive.domain;

/**
 * How a domain participates in the sidecar state root (decision 1: 4-value, keeps DOMAIN_ROOT_ONLY
 * for per-domain shadow-then-promote).
 */
public enum RootPolicy {
  /** Domain keys are aggregated into the global root. */
  IN_GLOBAL_ROOT,
  /** A domain root is computed but not yet merged into the global root (shadow). */
  DOMAIN_ROOT_ONLY,
  /** Latest/history are kept but the domain is not rooted. */
  HISTORY_ONLY,
  /** Not collected into archive state at all. */
  EXCLUDED
}
