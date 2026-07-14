package org.tron.core.archive;

import org.tron.core.archive.temporal.ArchiveTemporalReadView;
import org.tron.core.archive.temporal.UnifiedArchiveTemporalStore;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.archive.txnum.UnifiedArchiveTxNumIndex;
import org.tron.core.archive.unified.UnifiedArchiveDb;
import org.tron.core.archive.unified.UnifiedArchivePublish;
import org.tron.core.archive.unified.UnifiedArchiveReadView;

/** Coordinates UNIFIED_V1 atomic publication and shared-snapshot reads across typed adapters. */
public final class UnifiedArchiveBackend {

  private final UnifiedArchiveDb db;
  private final UnifiedArchiveTxNumIndex txNumIndex;
  private final UnifiedArchiveTemporalStore temporalStore;

  public UnifiedArchiveBackend(UnifiedArchiveDb db, UnifiedArchiveTxNumIndex txNumIndex,
      UnifiedArchiveTemporalStore temporalStore) {
    if (db == null || txNumIndex == null || temporalStore == null) {
      throw new NullPointerException("UNIFIED_V1 backend components");
    }
    this.db = db;
    this.txNumIndex = txNumIndex;
    this.temporalStore = temporalStore;
  }

  /** Publishes index, temporal rows, marker, cursor and journal delete in one RocksDB batch. */
  public ArchiveBlockRange publishBlock(ArchiveInFlightBlock block) {
    UnifiedArchivePublish.Builder builder = UnifiedArchivePublish.builder()
        .journal(ArchiveInFlightCodec.blockKey(block.getRange().getBlockNum()),
            ArchiveInFlightCodec.encodeBlock(block));
    ArchiveBlockRange range = null;
    try {
      range = txNumIndex.stagePublication(builder, block);
      temporalStore.stagePublication(builder, range, block.getRecords());
      db.publishBlockAtomically(builder.build(), true);
      txNumIndex.publicationSucceeded(range);
      return range;
    } catch (RuntimeException e) {
      try {
        txNumIndex.publicationFailed();
      } catch (RuntimeException cleanupFailure) {
        e.addSuppressed(cleanupFailure);
      }
      throw e;
    }
  }

  /** Opens one RocksDB snapshot that backs both index point resolution and temporal state reads. */
  public ReadSession openReadSession() {
    return new ReadSession(db.openReadView());
  }

  public void validateStartup(boolean fullScrub, boolean deferRepairValidation) {
    txNumIndex.validateStartup(fullScrub, deferRepairValidation);
    temporalStore.validateStartupTail(txNumIndex.getLastRange());
    if (!fullScrub) {
      return;
    }
    temporalStore.validateCommitMarkersCovered(
        blockNum -> txNumIndex.getBlockRange(blockNum).isPresent());
    long first = txNumIndex.getFirstArchivedBlock();
    long last = txNumIndex.getLastArchivedBlock();
    if (first >= 0) {
      for (long blockNum = first; blockNum <= last; blockNum++) {
        long currentBlock = blockNum;
        ArchiveBlockRange range = txNumIndex.getBlockRange(currentBlock)
            .orElseThrow(() -> new ArchiveException(
                "archive block range missing for block " + currentBlock));
        temporalStore.validateCommittedBlock(range);
      }
    }
    temporalStore.validateTxNumsCovered(
        txNum -> txNumIndex.getPosition(txNum).isPresent());
    temporalStore.validateDomainRows();
  }

  public final class ReadSession implements AutoCloseable {

    private final UnifiedArchiveReadView view;
    private final ArchiveTemporalReadView temporalView;
    private boolean closed;

    private ReadSession(UnifiedArchiveReadView view) {
      this.view = view;
      try {
        temporalView = temporalStore.wrapReadView(view);
      } catch (RuntimeException | Error e) {
        view.close();
        throw e;
      }
    }

    public UnifiedArchiveTxNumIndex.ReadScope bindIndex() {
      if (closed) {
        throw new ArchiveException("UNIFIED_V1 read session is closed");
      }
      return txNumIndex.bindReadView(view);
    }

    public ArchiveTemporalReadView getTemporalView() {
      if (closed) {
        throw new ArchiveException("UNIFIED_V1 read session is closed");
      }
      return temporalView;
    }

    @Override
    public void close() {
      if (!closed) {
        closed = true;
        temporalView.close();
      }
    }
  }
}
