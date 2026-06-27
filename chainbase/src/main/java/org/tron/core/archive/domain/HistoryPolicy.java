package org.tron.core.archive.domain;

/** How much temporal history the archive keeps for a domain. */
public enum HistoryPolicy {
  /** Temporal store writes latest + history + changeset. */
  FULL_HISTORY,
  /** Only the latest value is kept (no point-in-time history). */
  LATEST_ONLY,
  /** Only periodic checkpoints are kept. */
  CHECKPOINT_ONLY,
  /** Not archived. */
  NO_ARCHIVE
}
