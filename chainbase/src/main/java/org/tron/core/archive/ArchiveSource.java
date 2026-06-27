package org.tron.core.archive;

/**
 * Origin of a canonical state transition fed to the Archive sidecar. Determines which
 * Manager path allocated the txNum range.
 */
public enum ArchiveSource {
  /** Normal {@code pushBlock} canonical path. */
  NORMAL,
  /** Fork switch: replaying the newly-selected branch. */
  REPLAY,
  /** Recovery: replaying the original branch after a failed fork switch. */
  RECOVERY,
  /** {@code eraseBlock} unwind. */
  UNWIND
}
