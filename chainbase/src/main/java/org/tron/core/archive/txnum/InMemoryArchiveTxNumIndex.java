package org.tron.core.archive.txnum;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.tron.common.utils.ByteArray;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveSource;

/**
 * In-memory {@link ArchiveTxNumIndex} for L2. Allocates a global monotonic txNum across a single
 * pending block, commits the range only on canonical commit, and supports abort / unwind. All
 * methods are synchronized: allocation happens on the single canonical apply thread while
 * read-back may come from other threads. L5 replaces this with a persistent index.
 */
public final class InMemoryArchiveTxNumIndex implements ArchiveTxNumIndex {

  private long committedNextTxNum;
  private long workingNextTxNum;

  // null when no block is pending; only one block may be pending at a time.
  private Long pendingBlockNum;
  private ArchiveSource pendingSource;
  private final List<ArchiveTxPosition> pendingPositions = new ArrayList<>();

  private final Map<Long, ArchiveBlockRange> blockRanges = new HashMap<>();
  private final Map<Long, ArchiveTxPosition> positionsByTxNum = new HashMap<>();
  private final Map<String, Long> txNumByBlockAndIndex = new HashMap<>();
  private final Map<String, Long> txNumByTxId = new HashMap<>();

  @Override
  public synchronized void beginBlock(long blockNum, ArchiveSource source) {
    if (pendingBlockNum != null) {
      throw new ArchiveException(
          "archive txNum index already has pending block " + pendingBlockNum);
    }
    workingNextTxNum = committedNextTxNum;
    pendingPositions.clear();
    pendingBlockNum = blockNum;
    pendingSource = source;
  }

  @Override
  public synchronized ArchiveTxPosition allocateSystemTx(long blockNum, ArchivePhase phase) {
    requirePending(blockNum);
    if (phase != ArchivePhase.BLOCK_PREPARE && phase != ArchivePhase.BLOCK_FINALIZE) {
      throw new ArchiveException("system tx phase must be BLOCK_PREPARE or BLOCK_FINALIZE: " + phase);
    }
    ArchiveTxPosition position = new ArchiveTxPosition(
        workingNextTxNum++, blockNum, phase, pendingSource, -1, null);
    pendingPositions.add(position);
    return position;
  }

  @Override
  public synchronized ArchiveTxPosition allocateUserTx(long blockNum, int txIndex, byte[] txId) {
    requirePending(blockNum);
    if (txIndex < 0) {
      throw new ArchiveException("user tx index must be non-negative: " + txIndex);
    }
    ArchiveTxPosition position = new ArchiveTxPosition(
        workingNextTxNum++, blockNum, ArchivePhase.USER_TX, pendingSource, txIndex, txId);
    pendingPositions.add(position);
    return position;
  }

  @Override
  public synchronized ArchiveBlockRange commitBlock(long blockNum, int userTxCount) {
    requirePending(blockNum);
    long prepareTxNum = -1;
    long finalizeTxNum = -1;
    int actualUserCount = 0;
    for (ArchiveTxPosition position : pendingPositions) {
      switch (position.getPhase()) {
        case BLOCK_PREPARE:
          prepareTxNum = position.getTxNum();
          break;
        case BLOCK_FINALIZE:
          finalizeTxNum = position.getTxNum();
          break;
        case USER_TX:
          actualUserCount++;
          break;
        default:
          break;
      }
    }
    if (prepareTxNum < 0 || finalizeTxNum < 0) {
      throw new ArchiveException(
          "commit of block " + blockNum + " requires both prepare and finalize system tx");
    }
    if (actualUserCount != userTxCount) {
      throw new ArchiveException("user tx count mismatch for block " + blockNum
          + ": expected " + userTxCount + ", allocated " + actualUserCount);
    }

    long firstTxNum = committedNextTxNum;
    long lastTxNum = workingNextTxNum - 1;
    ArchiveBlockRange range = new ArchiveBlockRange(
        blockNum, firstTxNum, lastTxNum, prepareTxNum, finalizeTxNum, userTxCount, pendingSource);
    blockRanges.put(blockNum, range);
    for (ArchiveTxPosition position : pendingPositions) {
      positionsByTxNum.put(position.getTxNum(), position);
      if (position.getTxIndex() >= 0) {
        txNumByBlockAndIndex.put(blockIndexKey(blockNum, position.getTxIndex()), position.getTxNum());
      }
      byte[] txId = position.getTxId();
      if (txId.length > 0) {
        txNumByTxId.put(ByteArray.toHexString(txId), position.getTxNum());
      }
    }
    committedNextTxNum = workingNextTxNum;
    clearPending();
    return range;
  }

  @Override
  public synchronized void abortBlock(long blockNum) {
    if (pendingBlockNum != null && pendingBlockNum != blockNum) {
      throw new ArchiveException("cannot abort block " + blockNum
          + " while block " + pendingBlockNum + " is pending");
    }
    workingNextTxNum = committedNextTxNum;
    clearPending();
  }

  @Override
  public synchronized void unwindBlock(long blockNum) {
    ArchiveBlockRange range = blockRanges.remove(blockNum);
    if (range == null) {
      throw new ArchiveException("cannot unwind block " + blockNum + ": not committed");
    }
    for (long txNum = range.getFirstTxNum(); txNum <= range.getLastTxNum(); txNum++) {
      ArchiveTxPosition position = positionsByTxNum.remove(txNum);
      if (position == null) {
        continue;
      }
      if (position.getTxIndex() >= 0) {
        txNumByBlockAndIndex.remove(blockIndexKey(blockNum, position.getTxIndex()));
      }
      byte[] txId = position.getTxId();
      if (txId.length > 0) {
        txNumByTxId.remove(ByteArray.toHexString(txId));
      }
    }
    committedNextTxNum = range.getFirstTxNum();
    workingNextTxNum = committedNextTxNum;
  }

  @Override
  public synchronized Optional<ArchiveBlockRange> getBlockRange(long blockNum) {
    return Optional.ofNullable(blockRanges.get(blockNum));
  }

  @Override
  public synchronized Optional<ArchiveTxPosition> getPosition(long txNum) {
    return Optional.ofNullable(positionsByTxNum.get(txNum));
  }

  @Override
  public synchronized OptionalLong findTxNumByBlockAndIndex(long blockNum, int txIndex) {
    Long txNum = txNumByBlockAndIndex.get(blockIndexKey(blockNum, txIndex));
    return (txNum == null) ? OptionalLong.empty() : OptionalLong.of(txNum);
  }

  @Override
  public synchronized OptionalLong findTxNumByTxId(byte[] txId) {
    if (txId == null || txId.length == 0) {
      return OptionalLong.empty();
    }
    Long txNum = txNumByTxId.get(ByteArray.toHexString(txId));
    return (txNum == null) ? OptionalLong.empty() : OptionalLong.of(txNum);
  }

  private void requirePending(long blockNum) {
    if (pendingBlockNum == null) {
      throw new ArchiveException("no pending block; beginBlock(" + blockNum + ") required first");
    }
    if (pendingBlockNum != blockNum) {
      throw new ArchiveException(
          "pending block is " + pendingBlockNum + ", not " + blockNum);
    }
  }

  private void clearPending() {
    pendingBlockNum = null;
    pendingSource = null;
    pendingPositions.clear();
  }

  private static String blockIndexKey(long blockNum, int txIndex) {
    return blockNum + ":" + txIndex;
  }
}
