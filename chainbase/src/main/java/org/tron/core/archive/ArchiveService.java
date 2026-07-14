package org.tron.core.archive;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;
import org.tron.core.archive.reader.ArchiveReaderException;
import org.tron.core.archive.reader.ArchiveStatePoint;
import org.tron.core.archive.reader.ArchiveStateReader;
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

  ArchiveWorkLease NOOP_WORK_LEASE = new ArchiveWorkLease() {
    @Override
    public void start() {
    }

    @Override
    public void close() {
    }
  };

  ArchiveMutationLease NOOP_MUTATION_LEASE = new ArchiveMutationLease() {
    @Override
    public long getEpoch() {
      return 0;
    }

    @Override
    public boolean isExclusive() {
      return false;
    }

    @Override
    public void close() {
    }
  };

  interface ReadGuard extends AutoCloseable {
    @Override
    void close();
  }

  boolean isEnabled();

  default ArchiveWorkLease acquireRecoveryLease() {
    return NOOP_WORK_LEASE;
  }

  default ArchiveWorkLease acquireWriterLease() {
    return NOOP_WORK_LEASE;
  }

  default ArchiveWorkLease acquirePublisherLease() {
    return NOOP_WORK_LEASE;
  }

  /** Shared by an ordinary block append; excludes fork and startup mutation. */
  default ArchiveMutationLease acquireMutationReadLease() {
    return NOOP_MUTATION_LEASE;
  }

  /** Exclusive fork/startup barrier; every acquisition invalidates older publish targets. */
  default ArchiveMutationLease acquireMutationWriteLease() {
    return NOOP_MUTATION_LEASE;
  }

  /** Wait for journal capacity before acquiring a writer or mutation lease. */
  default void awaitWriterCapacity() {
  }

  default void completeRecovery() {
  }

  /** Installs the one production callback invoked after the first fatal archive failure. */
  default void setFatalFailureHandler(Consumer<RuntimeException> fatalHandler) {
  }

  void beginBlock(BlockCapsule block, ArchiveSource source);

  void commitBlock(BlockCapsule block);

  default void commitBlock(BlockCapsule block, int userTxCount) {
    commitBlock(block);
  }

  /** Durably journal a completed block and return its exact compensation identity. */
  default ArchiveJournalToken commitBlockJournaled(BlockCapsule block, int userTxCount) {
    commitBlock(block, userTxCount);
    return null;
  }

  /** Mark a journal generation as backed by a successful canonical session commit. */
  default void acknowledgeCanonicalCommit(ArchiveJournalToken token) {
  }

  /** Remove a journal generation whose canonical session commit failed. */
  default void rollbackJournaledBlock(ArchiveJournalToken token) {
  }

  /**
   * Publish all committed in-flight archive blocks up to {@code solidifiedBlockNum}. Before this
   * boundary, {@link #commitBlock(BlockCapsule)} only records the block in memory so historical
   * readers never observe reversible tip state.
   */
  default void publishSolidifiedBlocks(long solidifiedBlockNum) {
  }

  /** Startup/fault-recovery oracle that always completes publication on the caller thread. */
  default void recoverSynchronouslyTo(long solidifiedBlockNum) {
    publishSolidifiedBlocks(solidifiedBlockNum);
  }

  /**
   * Runtime publication entry. Async implementations may coalesce this request; synchronous
   * implementations complete it before returning.
   */
  default void requestPublishSolidifiedBlocks(long solidifiedBlockNum,
      byte[] solidifiedBlockHash) {
    publishSolidifiedBlocks(solidifiedBlockNum);
  }

  /** Whether runtime publication needs a canonical hash bound to its asynchronous target. */
  default boolean requiresPublishTargetHash() {
    return false;
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

  /** Persists a sticky marker that prevents this archive from being reused without a rebuild. */
  default void markRebuildRequired(String reason) {
  }

  /** Runtime guard: fail closed if archive has seen a fatal post-canonical failure. */
  default void validateAvailable() {
  }

  /** Guard a consistent archive read against concurrent commit/unwind publication. */
  default ReadGuard acquireReadGuard() {
    return () -> {
    };
  }

  /**
   * Open a reader for a historical point. An enabled archive returns a reader that either holds a
   * consistent snapshot (genesis-complete, lock released for the reader's lifetime) or holds the
   * read lock until closed (mid-chain). The reader MUST be closed. Disabled stubs reject the call.
   */
  default ArchiveStateReader openReader(ArchiveStatePoint point) throws ArchiveReaderException {
    throw new ArchiveReaderException(ArchiveReaderException.Reason.ARCHIVE_DISABLED,
        "archive is not enabled");
  }

  /** Resolve a block-end point and open its reader under one admitted archive snapshot boundary. */
  default ArchiveStateReader openBlockEndReader(long blockNum, byte[] canonicalBlockHash)
      throws ArchiveReaderException {
    throw new ArchiveReaderException(ArchiveReaderException.Reason.ARCHIVE_DISABLED,
        "archive is not enabled");
  }

  /**
   * Resolve the canonical block hash only after query admission, then open the block-end reader
   * under the same consistency boundary.
   */
  default ArchiveStateReader openBlockEndReader(long blockNum,
      LongFunction<byte[]> canonicalBlockHashProvider) throws ArchiveReaderException {
    throw new ArchiveReaderException(ArchiveReaderException.Reason.ARCHIVE_DISABLED,
        "archive is not enabled");
  }

  /** Resolve both a dynamic block tag and its canonical hash only after query admission. */
  default ArchiveStateReader openBlockEndReader(LongSupplier blockNumProvider,
      LongFunction<byte[]> canonicalBlockHashProvider) throws ArchiveReaderException {
    throw new ArchiveReaderException(ArchiveReaderException.Reason.ARCHIVE_DISABLED,
        "archive is not enabled");
  }

  /** Resolve an object-form blockHash only after query admission. */
  default ArchiveStateReader openBlockHashReader(byte[] requestedBlockHash,
      Function<byte[], BlockCapsule> canonicalBlockProvider) throws ArchiveReaderException {
    throw new ArchiveReaderException(ArchiveReaderException.Reason.ARCHIVE_DISABLED,
        "archive is not enabled");
  }

  /** Resolve a transaction pre-state and open it under one admitted archive snapshot boundary. */
  default ArchiveStateReader openTransactionReader(byte[] txId, long expectedBlockNum,
      byte[] canonicalBlockHash) throws ArchiveReaderException {
    throw new ArchiveReaderException(ArchiveReaderException.Reason.ARCHIVE_DISABLED,
        "archive is not enabled");
  }

  /**
   * Resolve a transaction's archive position before consulting the canonical block provider, then
   * open its pre-state under the same admitted archive boundary. This ordering prevents an
   * unindexed transaction from falling through to live Wallet state and keeps the index, canonical
   * hash validation, and reader snapshot in one consistency generation.
   */
  default ArchiveStateReader openTransactionReader(byte[] txId,
      LongFunction<byte[]> canonicalBlockHashProvider) throws ArchiveReaderException {
    throw new ArchiveReaderException(ArchiveReaderException.Reason.ARCHIVE_DISABLED,
        "archive is not enabled");
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
