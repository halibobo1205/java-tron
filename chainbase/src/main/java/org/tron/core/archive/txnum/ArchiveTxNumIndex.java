package org.tron.core.archive.txnum;

import java.util.Optional;
import java.util.OptionalLong;
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveSource;

/**
 * Allocates the global, monotonic {@code txNum} coordinate for each canonical state transition
 * and indexes block/tx lookups. L2 ships an in-memory implementation; L5 replaces it with a
 * persistent index. Only one block may be pending (un-committed) at a time.
 */
public interface ArchiveTxNumIndex {

  void beginBlock(long blockNum, ArchiveSource source);

  ArchiveTxPosition allocateSystemTx(long blockNum, ArchivePhase phase);

  ArchiveTxPosition allocateUserTx(long blockNum, int txIndex, byte[] txId);

  ArchiveBlockRange commitBlock(long blockNum, int userTxCount);

  void abortBlock(long blockNum);

  void unwindBlock(long blockNum);

  Optional<ArchiveBlockRange> getBlockRange(long blockNum);

  Optional<ArchiveTxPosition> getPosition(long txNum);

  OptionalLong findTxNumByBlockAndIndex(long blockNum, int txIndex);

  OptionalLong findTxNumByTxId(byte[] txId);
}
