package org.tron.core.archive;

import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongConsumer;
import org.tron.core.archive.query.QueryContext;
import org.tron.core.archive.temporal.ArchiveTemporalReadView;
import org.tron.core.archive.temporal.UnifiedArchiveTemporalStore;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.archive.txnum.UnifiedArchiveTxNumIndex;
import org.tron.core.archive.unified.UnifiedArchiveDb;
import org.tron.core.archive.unified.UnifiedArchivePublish;
import org.tron.core.archive.unified.UnifiedArchiveReadView;

/**
 * Coordinates UNIFIED_V1 atomic publication and shared-snapshot reads across typed adapters.
 * Resource closure remains owned by {@link UnifiedArchiveTxNumIndex}, which closes the shared DB
 * after the in-flight and temporal adapters have completed their no-op closes.
 */
final class UnifiedArchiveBackend {

  private static final long SYSTEM_MUTATION_ALLOWANCE = 100_000L;
  private static final long PUBLICATION_BASE_ALLOWANCE = 8L * 1024L;
  private static final long POSITION_ALLOWANCE = 1024L;
  private static final LongConsumer IGNORE_PUBLICATION_BYTES = ignored -> { };

  private final UnifiedArchiveDb db;
  private final UnifiedArchiveTxNumIndex txNumIndex;
  private final UnifiedArchiveTemporalStore temporalStore;
  private final UnifiedArchiveDb.ProductionWritePermit writePermit;
  private final long maxPublishRetainedBytes;
  private final long maxPublishMutations;
  private final ReentrantLock publicationLock = new ReentrantLock(true);

  UnifiedArchiveBackend(UnifiedArchiveDb db, UnifiedArchiveTxNumIndex txNumIndex,
      UnifiedArchiveTemporalStore temporalStore) {
    this(db, txNumIndex, temporalStore, Long.MAX_VALUE, Long.MAX_VALUE, null);
  }

  UnifiedArchiveBackend(UnifiedArchiveDb db, UnifiedArchiveTxNumIndex txNumIndex,
      UnifiedArchiveTemporalStore temporalStore, long maxPublishRetainedBytes,
      long maxPublishRecords) {
    this(db, txNumIndex, temporalStore, maxPublishRetainedBytes, maxPublishRecords, null);
  }

  UnifiedArchiveBackend(UnifiedArchiveDb db, UnifiedArchiveTxNumIndex txNumIndex,
      UnifiedArchiveTemporalStore temporalStore, long maxPublishRetainedBytes,
      long maxPublishRecords, UnifiedArchiveDb.ProductionWritePermit writePermit) {
    if (db == null || txNumIndex == null || temporalStore == null) {
      throw new NullPointerException("UNIFIED_V1 backend components");
    }
    if (maxPublishRetainedBytes <= 0L || maxPublishRecords <= 0L) {
      throw new IllegalArgumentException("UNIFIED_V1 publish limits must be positive");
    }
    if (!txNumIndex.isOwnedBy(db) || !temporalStore.isOwnedBy(db)) {
      throw new ArchiveException("UNIFIED_V1 backend components belong to different databases");
    }
    this.db = db;
    this.txNumIndex = txNumIndex;
    this.temporalStore = temporalStore;
    this.writePermit = writePermit;
    this.maxPublishRetainedBytes = maxPublishRetainedBytes;
    this.maxPublishMutations = addSaturated(
        multiplySaturated(maxPublishRecords, 10L), SYSTEM_MUTATION_ALLOWANCE);
  }

  /** Publishes index, temporal rows, marker, cursor and journal delete in one RocksDB batch. */
  public ArchiveBlockRange publishBlock(ArchiveInFlightBlock block) {
    return publishBlock(block, IGNORE_PUBLICATION_BYTES);
  }

  ArchiveBlockRange publishBlock(ArchiveInFlightBlock block,
      LongConsumer publicationBytesObserver) {
    if (publicationBytesObserver == null) {
      throw new NullPointerException("publicationBytesObserver");
    }
    publicationLock.lock();
    try {
      return publishBlockLocked(block, publicationBytesObserver);
    } finally {
      publicationLock.unlock();
    }
  }

  private ArchiveBlockRange publishBlockLocked(ArchiveInFlightBlock block,
      LongConsumer publicationBytesObserver) {
    try (UnifiedArchiveTemporalStore.PublicationPreflight preflight =
        temporalStore.preflightPublication(block.getRecords())) {
      long persistedPreparationBytes = preflight.getPersistedPreparationBytes();
      long stateAwarePublicationBytes = addSaturated(
          estimatedPublicationRetainedBytes(block), persistedPreparationBytes);
      if (stateAwarePublicationBytes > maxPublishRetainedBytes) {
        throw new ArchiveException(
            "UNIFIED_V1 state-aware publication exceeds retained byte limit "
                + maxPublishRetainedBytes + ": estimatedBytes=" + stateAwarePublicationBytes
                + ", persistedTemporalPreparationBytes=" + persistedPreparationBytes);
      }
      publicationBytesObserver.accept(stateAwarePublicationBytes);
      long blockNum = block.getRange().getBlockNum();
      ArchiveInFlightBlock journaled = block.withJournalState(
          ArchiveInFlightBlock.JournalState.JOURNALED);
      byte[] journalKey = ArchiveInFlightCodec.blockKey(blockNum);
      byte[] journalValue = ArchiveInFlightCodec.encodeBlock(journaled);
      byte[] proof = ArchiveInFlightCodec.encodeProof(
          ArchiveJournalProof.create(block.getJournalToken(), journalKey, journalValue));
      UnifiedArchivePublish.Builder builder = UnifiedArchivePublish.builder(
              maxPublishRetainedBytes, maxPublishMutations)
          .journal(journalKey, journalValue)
          .journalToken(ArchiveInFlightCodec.tokenKey(blockNum), proof)
          .acknowledgement(ArchiveInFlightCodec.acknowledgementKey(blockNum), proof);
      ArchiveBlockRange range = null;
      try {
        try (UnifiedArchiveReadView view = db.openReadView();
            UnifiedArchiveTxNumIndex.ReadScope ignored = txNumIndex.bindReadView(view)) {
          range = txNumIndex.stagePublication(builder, block);
        }
        temporalStore.stagePublication(builder, range, preflight);
        // Preparation has consumed every snapshot-backed locator/payload. Do not pin the snapshot
        // through the synchronous WriteBatch/WAL phase.
        preflight.close();
        runPersistentMutation(() -> db.publishBlockAtomically(builder.build(), true));
        txNumIndex.publicationSucceeded(range);
        return range;
      } catch (RuntimeException | Error e) {
        try {
          txNumIndex.publicationFailed();
        } catch (Throwable cleanupFailure) {
          if (e != cleanupFailure) {
            e.addSuppressed(cleanupFailure);
          }
        }
        throw e;
      }
    }
  }

  private void runPersistentMutation(Runnable mutation) {
    if (writePermit == null) {
      mutation.run();
    } else {
      db.withProductionWritePermit(writePermit, mutation);
    }
  }

  /**
   * Rejects a journal before durability when its worst-case publication cannot fit the configured
   * builder. This estimate performs no archive reads and never copies a record payload.
   */
  public void validatePublicationAdmission(ArchiveInFlightBlock block) {
    long estimated = estimatedPublicationRetainedBytes(block);
    if (estimated > maxPublishRetainedBytes) {
      throw new ArchiveException("UNIFIED_V1 block cannot fit publish retained byte limit "
          + maxPublishRetainedBytes + ": estimatedBytes=" + estimated);
    }
    long estimatedMutations = estimatedPublicationMutations(block);
    if (estimatedMutations > maxPublishMutations) {
      throw new ArchiveException("UNIFIED_V1 block cannot fit publish mutation limit "
          + maxPublishMutations + ": estimatedMutations=" + estimatedMutations);
    }
  }

  long estimatedPublicationRetainedBytes(ArchiveInFlightBlock block) {
    if (block == null) {
      throw new NullPointerException("block");
    }
    long bytes = addSaturated(
        ArchiveInFlightCodec.encodedBlockSize(block), PUBLICATION_BASE_ALLOWANCE);
    bytes = addSaturated(bytes,
        multiplySaturated(block.getPositions().size(), POSITION_ALLOWANCE));
    return addSaturated(bytes, block.temporalPublicationRetainedBytes());
  }

  long estimatedPublicationMutations(ArchiveInFlightBlock block) {
    if (block == null) {
      throw new NullPointerException("block");
    }
    long indexMutations = addSaturated(4L,
        multiplySaturated(block.getPositions().size(), 2L));
    return addSaturated(indexMutations, block.temporalPublicationMutations());
  }

  /** Opens one cache-isolated maintenance snapshot for package-local validation and tests. */
  ReadSession openReadSession() {
    return new ReadSession(db.openScanView());
  }

  /** Opens a shared snapshot whose native reads inherit the deadline when RocksDB supports it. */
  public ReadSession openReadSession(QueryContext context) {
    if (context == null) {
      throw new NullPointerException("context");
    }
    return new ReadSession(db.openQueryReadView(context, writePermit));
  }

  /** Must be called while the read session has bound the index to its unified snapshot. */
  public boolean hasGenesisCompleteCoverage() {
    return txNumIndex.hasGenesisCompleteCoverage();
  }

  public void validateStartup(boolean fullScrub, boolean deferRepairValidation) {
    try (UnifiedArchiveReadView view = db.openScanView();
        UnifiedArchiveTxNumIndex.ReadScope ignored = txNumIndex.bindReadView(view)) {
      txNumIndex.validateStartup(fullScrub, deferRepairValidation);
      temporalStore.validateStartupTail(view, txNumIndex.getLastRange());
      if (!fullScrub) {
        return;
      }
      long first = txNumIndex.getFirstArchivedBlock();
      long last = txNumIndex.getLastArchivedBlock();
      temporalStore.validateCommittedBlocks(view, first, last,
          blockNum -> txNumIndex.getBlockRange(blockNum)
              .orElseThrow(() -> new ArchiveException(
                  "archive block range missing for block " + blockNum)));
      temporalStore.validateTxNumsCovered(
          view, txNum -> txNumIndex.getPosition(txNum).isPresent());
      temporalStore.validateDomainRows(view);
    }
  }

  public final class ReadSession implements AutoCloseable {

    private final UnifiedArchiveReadView view;
    private final ArchiveTemporalReadView temporalView;
    private final Thread owner = Thread.currentThread();
    private boolean closed;

    private ReadSession(UnifiedArchiveReadView view) {
      this.view = view;
      try {
        temporalView = temporalStore.wrapReadView(view);
      } catch (RuntimeException | Error e) {
        try {
          view.close();
        } catch (Throwable closeFailure) {
          throw unknownSnapshotCleanup(
              "UNIFIED_V1 read-session setup could not confirm snapshot release",
              e, closeFailure);
        }
        throw e;
      }
    }

    public UnifiedArchiveTxNumIndex.ReadScope bindIndex() {
      requireOwnerAndOpen();
      return txNumIndex.bindReadView(view);
    }

    public ArchiveTemporalReadView getTemporalView() {
      requireOwnerAndOpen();
      return temporalView;
    }

    /** Reads and marker-validates one range from this snapshot without retaining a binding. */
    public Optional<ArchiveBlockRange> getBlockRange(long blockNum) {
      requireOwnerAndOpen();
      try (UnifiedArchiveTxNumIndex.ReadScope ignored = bindIndex()) {
        Optional<ArchiveBlockRange> range = txNumIndex.getBlockRange(blockNum);
        if (range.isPresent()) {
          validateBlockRangeMarker(range.get());
        }
        return range;
      }
    }

    /** Validates an already-resolved range against this snapshot's independent block marker. */
    public void validateBlockRangeMarker(ArchiveBlockRange range) {
      requireOwnerAndOpen();
      temporalStore.validateBlockRangeMarker(view, range);
    }

    @Override
    public void close() {
      requireOwner();
      if (!closed) {
        temporalView.close();
        closed = true;
      }
    }

    private void requireOwnerAndOpen() {
      requireOwner();
      if (closed) {
        throw new ArchiveException("UNIFIED_V1 read session is closed");
      }
    }

    private void requireOwner() {
      if (Thread.currentThread() != owner) {
        throw new ArchiveException("UNIFIED_V1 read session used from a non-owner thread");
      }
    }
  }

  private static ArchiveSnapshotReleaseException unknownSnapshotCleanup(
      String message, Throwable setupFailure, Throwable closeFailure) {
    ArchiveSnapshotReleaseException uncertain = new ArchiveSnapshotReleaseException(
        message, closeFailure);
    if (setupFailure != closeFailure) {
      try {
        uncertain.addSuppressed(setupFailure);
      } catch (Throwable ignored) {
        // The release marker and its direct close cause remain authoritative.
      }
    }
    return uncertain;
  }

  private static long multiplySaturated(long left, long right) {
    return left != 0L && right > Long.MAX_VALUE / left ? Long.MAX_VALUE : left * right;
  }

  private static long addSaturated(long left, long right) {
    return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
  }
}
