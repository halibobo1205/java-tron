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

  void beginBlock(BlockCapsule block);

  void commitBlock(BlockCapsule block);

  void abortBlock(BlockCapsule block);

  void unwindBlock(BlockCapsule block);

  void beginSystemTx(BlockCapsule block, ArchivePhase phase);

  void beginUserTx(BlockCapsule block, int txIndex, TransactionCapsule tx);

  void endTx();
}
