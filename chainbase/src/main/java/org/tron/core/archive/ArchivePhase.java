package org.tron.core.archive;

/**
 * Logical phase of a canonical state transition, used by the txNum index to allocate the global
 * txNum coordinate. {@code UNWIND} is metadata/debug only and is not part of the normal
 * block-apply txNum range (it is not persisted by the txNum meta codec).
 */
public enum ArchivePhase {
  BLOCK_PREPARE,
  USER_TX,
  BLOCK_FINALIZE,
  UNWIND
}
