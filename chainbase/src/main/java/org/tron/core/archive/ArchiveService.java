package org.tron.core.archive;

import java.util.function.LongFunction;
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

  interface ReadGuard extends AutoCloseable {
    @Override
    void close();
  }

  boolean isEnabled();

  void beginBlock(BlockCapsule block, ArchiveSource source);

  void commitBlock(BlockCapsule block);

  default void commitBlock(BlockCapsule block, int userTxCount) {
    commitBlock(block);
  }

  /**
   * Publish all committed in-flight archive blocks up to {@code solidifiedBlockNum}. Before this
   * boundary, {@link #commitBlock(BlockCapsule)} only records the block in memory so historical
   * readers never observe reversible tip state.
   */
  default void publishSolidifiedBlocks(long solidifiedBlockNum) {
  }

  /**
   * Startup recovery hook: validate durable in-flight blocks against the canonical hot window, then
   * publish any that are already solidified. Implementations may fail closed if the in-flight
   * journal no longer matches canonical block storage.
   */
  default void reconcileInFlightOnStartup(long solidifiedBlockNum,
      LongFunction<BlockCapsule> canonicalBlockProvider) {
    publishSolidifiedBlocks(solidifiedBlockNum);
  }

  /**
   * Startup recovery hook with the current canonical head number. Implementations that persist an
   * in-flight journal can use this to fail closed if canonical block apply committed a hot-tail
   * block but the archive journal write did not survive the crash.
   */
  default void reconcileInFlightOnStartup(long solidifiedBlockNum, long canonicalHeadNum,
      LongFunction<BlockCapsule> canonicalBlockProvider) {
    reconcileInFlightOnStartup(solidifiedBlockNum, canonicalBlockProvider);
  }

  /**
   * Startup recovery hook after the canonical head is known. If archive publication outlived the
   * canonical block stores during shutdown/crash recovery, implementations may rewind published
   * archive head blocks that are above {@code canonicalHeadNum}.
   */
  default void reconcilePublishedHeadOnStartup(long canonicalHeadNum) {
  }

  void abortBlock(BlockCapsule block);

  void unwindBlock(BlockCapsule block);

  /** Startup guard: fail closed if persisted archive head disagrees with canonical chain head. */
  default void validateCanonicalHead(BlockCapsule canonicalHead) {
  }

  /** Runtime guard: fail closed if archive has seen a fatal post-canonical failure. */
  default void validateAvailable() {
  }

  /** Guard a consistent archive read against concurrent commit/unwind publication. */
  default ReadGuard acquireReadGuard() {
    return () -> {
    };
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
