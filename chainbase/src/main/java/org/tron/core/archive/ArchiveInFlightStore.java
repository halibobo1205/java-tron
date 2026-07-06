package org.tron.core.archive;

import java.util.List;

/**
 * Durable journal for canonical blocks that have committed locally but are not yet solidified
 * enough to publish into the reader-visible archive index/temporal store.
 */
public interface ArchiveInFlightStore extends AutoCloseable {

  List<ArchiveInFlightBlock> loadBlocks();

  void putBlock(ArchiveInFlightBlock block);

  void deleteBlock(long blockNum);

  @Override
  default void close() {
  }
}
