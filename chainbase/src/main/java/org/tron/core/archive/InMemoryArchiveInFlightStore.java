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
  public synchronized void acknowledgeBlock(ArchiveJournalToken token) {
    ArchiveInFlightBlock block = blocks.get(token.getBlockNum());
    if (block == null) {
      throw new ArchiveException("archive acknowledgement has no journal block "
          + token.getBlockNum());
    }
    if (!token.equals(block.getJournalToken())) {
      throw new ArchiveException("archive acknowledgement token mismatch for block "
          + token.getBlockNum());
    }
    blocks.put(token.getBlockNum(), block.withJournalState(
        ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED));
  }

  @Override
  public synchronized void deleteBlock(long blockNum) {
    blocks.remove(blockNum);
  }
}
