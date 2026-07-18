package org.tron.core.archive;

import java.util.List;
import java.util.function.Consumer;

/**
 * Durable journal for canonical blocks that have committed locally but are not yet solidified
 * enough to publish into the reader-visible archive index/temporal store.
 */
public interface ArchiveInFlightStore extends AutoCloseable {

  List<ArchiveInFlightBlock> loadBlocks();

  /** Visits persisted blocks in height order without requiring an additional aggregate list. */
  default void forEachBlock(Consumer<ArchiveInFlightBlock> consumer) {
    for (ArchiveInFlightBlock block : loadBlocks()) {
      consumer.accept(block);
    }
  }

  void putBlock(ArchiveInFlightBlock block);

  /**
   * Durably records canonical commit for an existing journal. Persistent implementations may use
   * a compact token marker instead of rewriting the full block payload.
   */
  default void acknowledgeBlock(ArchiveInFlightBlock block) {
    putBlock(block);
  }

  /** Acknowledge by compact journal identity without requiring the full payload to be copied. */
  default void acknowledgeBlock(ArchiveJournalToken token) {
    for (ArchiveInFlightBlock block : loadBlocks()) {
      if (token.equals(block.getJournalToken())) {
        acknowledgeBlock(block.withJournalState(
            ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED));
        return;
      }
    }
    throw new ArchiveException("archive acknowledgement has no journal block "
        + token.getBlockNum());
  }

  /** Acknowledges a block already validated and retained by the owning archive service. */
  default void acknowledgeLoadedBlock(ArchiveInFlightBlock block) {
    acknowledgeBlock(block);
  }

  void deleteBlock(long blockNum);

  /** Deletes a block already validated and retained by the owning archive service. */
  default void deleteLoadedBlock(ArchiveInFlightBlock block) {
    deleteBlock(block.getRange().getBlockNum());
  }

  /** Drops transient metadata after another atomic backend has removed the durable journal. */
  default void onBlockPublished(long blockNum) {
  }

  /** Usable bytes on the journal filesystem, or {@link Long#MAX_VALUE} when not applicable. */
  default long usableSpaceBytes() {
    return Long.MAX_VALUE;
  }

  /** Releases transient adapter caches only after the final shared storage owner closed cleanly. */
  default void ownerCloseSucceeded() {
  }

  @Override
  default void close() {
  }
}
