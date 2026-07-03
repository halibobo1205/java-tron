package org.tron.core.archive;

import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;

/**
 * Lifecycle entry-point for the transaction-level Archive sidecar.
 *
 * <p>L1 only defines the lifecycle boundary that the canonical block-apply path will call; a
 * disabled node uses {@link NoopArchiveService}. Real txNum / write-collection / temporal /
 * commitment behaviour is introduced in later landings (L2+). Archive is a non-consensus
 * sidecar and never touches block headers.
 */
public interface ArchiveService {

  boolean isEnabled();

  void beginBlock(BlockCapsule block, ArchiveSource source);

  void commitBlock(BlockCapsule block);

  default void commitBlock(BlockCapsule block, int userTxCount) {
    commitBlock(block);
  }

  void abortBlock(BlockCapsule block);

  void unwindBlock(BlockCapsule block);

  /** Startup guard: fail closed if persisted archive head disagrees with canonical chain head. */
  default void validateCanonicalHead(BlockCapsule canonicalHead) {
  }

  /** True when this sidecar has a committed archive range for {@code blockNum}. */
  default boolean hasCommittedBlock(long blockNum) {
    return false;
  }

  void beginSystemTx(BlockCapsule block, ArchivePhase phase);

  void beginUserTx(BlockCapsule block, int txIndex, TransactionCapsule tx);

  void endTx();

  /** Release resources (e.g. close a persistent temporal store) at shutdown; no-op by default. */
  default void close() {
  }
}
