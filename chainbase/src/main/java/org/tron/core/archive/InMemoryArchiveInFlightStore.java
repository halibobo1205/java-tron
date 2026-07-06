package org.tron.core.archive;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/** In-memory in-flight journal used by tests and non-persistent archive runs. */
public final class InMemoryArchiveInFlightStore implements ArchiveInFlightStore {

  private final NavigableMap<Long, ArchiveInFlightBlock> blocks = new TreeMap<>();

  @Override
  public synchronized List<ArchiveInFlightBlock> loadBlocks() {
    return new ArrayList<>(blocks.values());
  }

  @Override
  public synchronized void putBlock(ArchiveInFlightBlock block) {
    blocks.put(block.getRange().getBlockNum(), block);
  }

  @Override
  public synchronized void deleteBlock(long blockNum) {
    blocks.remove(blockNum);
  }
}
