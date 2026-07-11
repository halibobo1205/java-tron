package org.tron.core.archive.txnum;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
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
  // Lowest block committed since this index was created; -1 = none yet. The persistent index keeps
  // its own restart-surviving floor, so this in-memory value is the coverage floor only when the
  // in-memory index is used standalone (no persistence).
  private long firstArchivedBlock = -1L;
  private long lastCommittedBlock = -1L;
  private long discardedThroughBlock = -1L;

  // null when no block is pending; only one block may be pending at a time.
  private Long pendingBlockNum;
  private ArchiveSource pendingSource;
  private final List<ArchiveTxPosition> pendingPositions = new ArrayList<>();

  private final Map<Long, ArchiveBlockRange> blockRanges = new HashMap<>();
  private final Map<Long, ArchiveTxPosition> positionsByTxNum = new HashMap<>();
  private final Map<String, Long> txNumByBlockAndIndex = new HashMap<>();
  private final Map<String, Long> txNumByTxId = new HashMap<>();

  public InMemoryArchiveTxNumIndex() {
    this(0L);
  }

  /** Seeds the committed cursor (e.g. restored from a persistent index on restart). */
  public InMemoryArchiveTxNumIndex(long startTxNum) {
    this(startTxNum, -1L);
  }

  /** Seeds a cursor plus a discarded block high-water mark for append validation. */
  InMemoryArchiveTxNumIndex(long startTxNum, long lastCommittedBlock) {
    if (startTxNum < 0) {
      throw new ArchiveException("archive txNum cursor must be non-negative: " + startTxNum);
    }
    if (lastCommittedBlock < -1L) {
      throw new ArchiveException("archive last committed block must be at least -1: "
          + lastCommittedBlock);
    }
    this.committedNextTxNum = startTxNum;
    this.workingNextTxNum = startTxNum;
    this.lastCommittedBlock = lastCommittedBlock;
    this.discardedThroughBlock = lastCommittedBlock;
  }

  /** The next txNum to be allocated for the following block (the committed cursor). */
  public synchronized long getCommittedNextTxNum() {
    return committedNextTxNum;
  }

  @Override
  public synchronized void beginBlock(long blockNum, ArchiveSource source) {
    if (blockNum < 0) {
      throw new ArchiveException("archive block number must be non-negative: " + blockNum);
    }
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
      throw new ArchiveException("system phase must be BLOCK_PREPARE/BLOCK_FINALIZE: " + phase);
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
    ArchiveBlockRangeCodec.requireTxId(txId, "archive user transaction");
    ArchiveTxPosition position = new ArchiveTxPosition(
        workingNextTxNum++, blockNum, ArchivePhase.USER_TX, pendingSource, txIndex, txId);
    pendingPositions.add(position);
    return position;
  }

  @Override
  public synchronized ArchiveBlockRange commitBlock(long blockNum, int userTxCount) {
    return commitBlock(blockNum, new byte[0], userTxCount);
  }

  @Override
  public synchronized ArchiveBlockRange commitBlock(long blockNum, byte[] blockHash,
      int userTxCount) {
    return commitBlock(blockNum, blockHash, userTxCount, new byte[0]);
  }

  @Override
  public synchronized ArchiveBlockRange commitBlock(long blockNum, byte[] blockHash,
      int userTxCount, byte[] schemaChecksum) {
    requirePending(blockNum);
    long prepareTxNum = -1;
    long finalizeTxNum = -1;
    int prepareCount = 0;
    int finalizeCount = 0;
    int actualUserCount = 0;
    for (ArchiveTxPosition position : pendingPositions) {
      switch (position.getPhase()) {
        case BLOCK_PREPARE:
          prepareCount++;
          prepareTxNum = position.getTxNum();
          break;
        case BLOCK_FINALIZE:
          finalizeCount++;
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
    if (prepareCount != 1 || finalizeCount != 1) {
      throw new ArchiveException("commit of block " + blockNum
          + " requires exactly one prepare and one finalize system tx");
    }
    if (actualUserCount != userTxCount) {
      throw new ArchiveException("user tx count mismatch for block " + blockNum
          + ": expected " + userTxCount + ", allocated " + actualUserCount);
    }

    long firstTxNum = committedNextTxNum;
    long lastTxNum = workingNextTxNum - 1;
    validatePendingShape(blockNum, firstTxNum, lastTxNum, prepareTxNum, finalizeTxNum,
        userTxCount);
    validatePendingTxIds(blockNum);
    ArchiveBlockRange range = new ArchiveBlockRange(
        blockNum, firstTxNum, lastTxNum, prepareTxNum, finalizeTxNum, blockHash, userTxCount,
        pendingSource, schemaChecksum);
    validateAppendOnlyCommit(range);
    blockRanges.put(blockNum, range);
    lastCommittedBlock = blockNum;
    if (firstArchivedBlock < 0 || blockNum < firstArchivedBlock) {
      firstArchivedBlock = blockNum;
    }
    for (ArchiveTxPosition position : pendingPositions) {
      positionsByTxNum.put(position.getTxNum(), position);
      if (position.getTxIndex() >= 0) {
        txNumByBlockAndIndex.put(
            blockIndexKey(blockNum, position.getTxIndex()), position.getTxNum());
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
    requireNonNegativeBlockNum(blockNum);
    ArchiveBlockRange range = getHeadBlockRange(blockNum);
    removeCommittedRange(range);
    committedNextTxNum = range.getFirstTxNum();
    workingNextTxNum = committedNextTxNum;
    long retainedHead = findLastCommittedBlock();
    lastCommittedBlock = retainedHead >= 0 ? retainedHead : discardedThroughBlock;
    firstArchivedBlock = findFirstCommittedBlock();
  }

  /**
   * Drops lookup rows for a durably published prefix while preserving the allocation cursor and
   * the retained in-flight tail needed for head unwind.
   */
  public synchronized void discardBlocksThrough(long blockNum) {
    requireNonNegativeBlockNum(blockNum);
    if (pendingBlockNum != null) {
      throw new ArchiveException("cannot discard archive blocks while block "
          + pendingBlockNum + " is pending");
    }
    if (lastCommittedBlock < 0) {
      return;
    }
    long discardThrough = Math.min(blockNum, lastCommittedBlock);
    if (discardThrough <= discardedThroughBlock) {
      return;
    }
    List<ArchiveBlockRange> discarded = new ArrayList<>();
    for (ArchiveBlockRange range : blockRanges.values()) {
      if (range.getBlockNum() <= discardThrough) {
        discarded.add(range);
      }
    }
    for (ArchiveBlockRange range : discarded) {
      removeCommittedRange(range);
    }
    discardedThroughBlock = discardThrough;
    firstArchivedBlock = findFirstCommittedBlock();
  }

  private void removeCommittedRange(ArchiveBlockRange range) {
    for (long txNum = range.getFirstTxNum(); txNum <= range.getLastTxNum(); txNum++) {
      if (!positionsByTxNum.containsKey(txNum)) {
        throw new ArchiveException("archive tx-position missing for removal txNum " + txNum);
      }
    }
    blockRanges.remove(range.getBlockNum());
    for (long txNum = range.getFirstTxNum(); txNum <= range.getLastTxNum(); txNum++) {
      ArchiveTxPosition position = positionsByTxNum.remove(txNum);
      if (position.getTxIndex() >= 0) {
        txNumByBlockAndIndex.remove(
            blockIndexKey(range.getBlockNum(), position.getTxIndex()));
      }
      byte[] txId = position.getTxId();
      if (txId.length > 0) {
        txNumByTxId.remove(ByteArray.toHexString(txId));
      }
    }
  }

  @Override
  public synchronized ArchiveBlockRange getHeadBlockRange(long blockNum) {
    requireNonNegativeBlockNum(blockNum);
    ArchiveBlockRange range = blockRanges.get(blockNum);
    if (range == null) {
      throw new ArchiveException("cannot unwind block " + blockNum + ": not committed");
    }
    if (range.getLastTxNum() != committedNextTxNum - 1) {
      throw new ArchiveException("cannot unwind block " + blockNum + ": not archive head");
    }
    return range;
  }

  @Override
  public synchronized void validateCanonicalHead(long headNum, byte[] headHash) {
    requireNonNegativeBlockNum(headNum);
    if (lastCommittedBlock < 0) {
      return;
    }
    ArchiveBlockRange range = blockRanges.get(lastCommittedBlock);
    if (range == null) {
      throw new ArchiveException("archive head block " + lastCommittedBlock
          + " is no longer retained in memory");
    }
    if (range.getBlockNum() != headNum) {
      throw new ArchiveException("archive head block " + range.getBlockNum()
          + " does not match canonical head block " + headNum);
    }
    byte[] archiveHash = range.getBlockHash();
    ArchiveBlockRangeCodec.requireBlockHash(archiveHash, "archive head block");
    ArchiveBlockRangeCodec.requireBlockHash(headHash, "canonical head block");
    if (!Arrays.equals(archiveHash, headHash)) {
      throw new ArchiveException("archive head block hash does not match canonical head hash");
    }
  }

  @Override
  public synchronized Optional<ArchiveBlockRange> getBlockRange(long blockNum) {
    requireNonNegativeBlockNum(blockNum);
    return Optional.ofNullable(blockRanges.get(blockNum));
  }

  @Override
  public synchronized Optional<ArchiveTxPosition> getPosition(long txNum) {
    if (txNum < 0) {
      throw new ArchiveException("archive tx-position txNum must be non-negative");
    }
    return Optional.ofNullable(positionsByTxNum.get(txNum));
  }

  @Override
  public synchronized OptionalLong findTxNumByBlockAndIndex(long blockNum, int txIndex) {
    requireNonNegativeBlockNum(blockNum);
    Long txNum = txNumByBlockAndIndex.get(blockIndexKey(blockNum, txIndex));
    return (txNum == null) ? OptionalLong.empty() : OptionalLong.of(txNum);
  }

  @Override
  public synchronized OptionalLong findTxNumByTxId(byte[] txId) {
    if (txId == null || txId.length == 0) {
      return OptionalLong.empty();
    }
    ArchiveBlockRangeCodec.requireTxId(txId, "archive txId lookup");
    Long txNum = txNumByTxId.get(ByteArray.toHexString(txId));
    return (txNum == null) ? OptionalLong.empty() : OptionalLong.of(txNum);
  }

  @Override
  public synchronized long getNextTxNum() {
    return committedNextTxNum;
  }

  @Override
  public synchronized long getLastArchivedBlock() {
    return lastCommittedBlock;
  }

  @Override
  public synchronized long getFirstArchivedBlock() {
    return firstArchivedBlock;
  }

  private void requirePending(long blockNum) {
    requireNonNegativeBlockNum(blockNum);
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

  private void validateAppendOnlyCommit(ArchiveBlockRange range) {
    long blockNum = range.getBlockNum();
    if (blockRanges.containsKey(blockNum)) {
      throw new ArchiveException("archive block range already committed for block " + blockNum);
    }
    if (lastCommittedBlock >= 0 && blockNum != lastCommittedBlock + 1) {
      throw new ArchiveException("non-contiguous archive block range: expected block "
          + (lastCommittedBlock + 1) + " after " + lastCommittedBlock + " but got " + blockNum);
    }
    if (range.getFirstTxNum() != committedNextTxNum) {
      throw new ArchiveException("non-contiguous archive txNum range: expected first txNum "
          + committedNextTxNum + " but got " + range.getFirstTxNum());
    }
  }

  private void validatePendingShape(long blockNum, long firstTxNum, long lastTxNum,
      long prepareTxNum, long finalizeTxNum, int userTxCount) {
    if (prepareTxNum != firstTxNum) {
      throw new ArchiveException("archive prepare txNum must be first for block " + blockNum);
    }
    if (finalizeTxNum != lastTxNum) {
      throw new ArchiveException("archive finalize txNum must be last for block " + blockNum);
    }
    long expectedSpan = (long) userTxCount + 2L;
    long actualSpan = lastTxNum - firstTxNum + 1L;
    if (actualSpan != expectedSpan) {
      throw new ArchiveException("archive txNum span does not match user tx count for block "
          + blockNum);
    }
    for (ArchiveTxPosition position : pendingPositions) {
      if (position.getPhase() == ArchivePhase.USER_TX) {
        long expectedTxNum = firstTxNum + 1L + position.getTxIndex();
        if (position.getTxNum() != expectedTxNum
            || position.getTxIndex() < 0
            || position.getTxIndex() >= userTxCount) {
          throw new ArchiveException("archive user tx-position order mismatch for block "
              + blockNum);
        }
      }
    }
  }

  private void validatePendingTxIds(long blockNum) {
    Set<String> seen = new HashSet<>();
    for (ArchiveTxPosition position : pendingPositions) {
      byte[] txId = position.getTxId();
      if (txId.length == 0) {
        continue;
      }
      String txIdHex = ByteArray.toHexString(txId);
      if (!seen.add(txIdHex)) {
        throw new ArchiveException("archive duplicate txId in block " + blockNum);
      }
      if (txNumByTxId.containsKey(txIdHex)) {
        throw new ArchiveException("archive duplicate txId already committed in block "
            + blockNum);
      }
    }
  }

  private long findLastCommittedBlock() {
    long last = -1L;
    for (Long blockNum : blockRanges.keySet()) {
      if (blockNum > last) {
        last = blockNum;
      }
    }
    return last;
  }

  private long findFirstCommittedBlock() {
    long first = -1L;
    for (Long blockNum : blockRanges.keySet()) {
      if (first < 0 || blockNum < first) {
        first = blockNum;
      }
    }
    return first;
  }

  private static String blockIndexKey(long blockNum, int txIndex) {
    return blockNum + ":" + txIndex;
  }

  private static void requireNonNegativeBlockNum(long blockNum) {
    if (blockNum < 0) {
      throw new ArchiveException("archive block number must be non-negative: " + blockNum);
    }
  }
}
