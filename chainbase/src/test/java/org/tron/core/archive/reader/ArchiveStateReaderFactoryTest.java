package org.tron.core.archive.reader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveSource;
import org.tron.core.archive.domain.DefaultArchiveDomainCatalog;
import org.tron.core.archive.temporal.InMemoryArchiveTemporalStore;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.archive.txnum.ArchiveTxNumIndex;
import org.tron.core.archive.txnum.InMemoryArchiveTxNumIndex;

public class ArchiveStateReaderFactoryTest {

  @Test
  public void opensReaderBoundToThePoint() throws Exception {
    byte[] hash = blockHash(7);
    ArchiveTxNumIndex index = committedIndex(7, hash, 0);
    ArchiveBlockRange range = index.getBlockRange(7).get();
    ArchiveStateReaderFactory factory = factory(index, new InMemoryArchiveTemporalStore());
    ArchiveStatePoint point = ArchiveStatePoint.blockEnd(7, hash, range.getFinalizeTxNum());

    ArchiveStateReader reader = factory.open(point);

    assertSame(point, reader.getPoint());
    reader.close();
  }

  @Test
  public void nullPointIsHistoryUnavailable() {
    ArchiveStateReaderFactory factory =
        factory(committedIndex(1, blockHash(1), 0), new InMemoryArchiveTemporalStore());
    ArchiveReaderException e = assertThrows(ArchiveReaderException.class, () -> factory.open(null));
    assertEquals(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE, e.getReason());
  }

  @Test
  public void nullStoreIsArchiveDisabled() {
    ArchiveTxNumIndex index = committedIndex(1, blockHash(1), 0);
    ArchiveStateReaderFactory disabled = new DefaultArchiveStateReaderFactory(
        null, new DefaultArchiveDomainCatalog(), index, () -> {
        });
    ArchiveReaderException e = assertThrows(ArchiveReaderException.class,
        () -> disabled.open(ArchiveStatePoint.blockEnd(1, blockHash(1), 1)));
    assertEquals(ArchiveReaderException.Reason.ARCHIVE_DISABLED, e.getReason());
  }

  @Test
  public void rejectsUncoveredPoint() {
    ArchiveStateReaderFactory factory =
        factory(committedIndex(1, blockHash(1), 0), new InMemoryArchiveTemporalStore());

    ArchiveReaderException e = assertThrows(ArchiveReaderException.class,
        () -> factory.open(ArchiveStatePoint.blockEnd(2, blockHash(2), 1)));

    assertEquals(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE, e.getReason());
  }

  @Test
  public void rejectsWrongBlockHash() {
    byte[] hash = blockHash(1);
    ArchiveTxNumIndex index = committedIndex(1, hash, 0);
    ArchiveBlockRange range = index.getBlockRange(1).get();
    ArchiveStateReaderFactory factory = factory(index, new InMemoryArchiveTemporalStore());

    ArchiveReaderException e = assertThrows(ArchiveReaderException.class,
        () -> factory.open(ArchiveStatePoint.blockEnd(1, blockHash(2),
            range.getFinalizeTxNum())));

    assertEquals(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE, e.getReason());
  }

  @Test
  public void availabilityGuardCanRejectUnavailableArchive() {
    byte[] hash = blockHash(1);
    ArchiveTxNumIndex index = committedIndex(1, hash, 0);
    ArchiveBlockRange range = index.getBlockRange(1).get();
    ArchiveStateReaderFactory guarded = new DefaultArchiveStateReaderFactory(
        new InMemoryArchiveTemporalStore(), new DefaultArchiveDomainCatalog(), index,
        () -> {
          throw new ArchiveReaderException(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE,
              "uncovered");
        });

    ArchiveReaderException e = assertThrows(ArchiveReaderException.class,
        () -> guarded.open(ArchiveStatePoint.blockEnd(1, hash, range.getFinalizeTxNum())));

    assertEquals(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE, e.getReason());
  }

  private static ArchiveStateReaderFactory factory(ArchiveTxNumIndex index,
      InMemoryArchiveTemporalStore temporalStore) {
    return new DefaultArchiveStateReaderFactory(
        temporalStore, new DefaultArchiveDomainCatalog(), index, () -> {
        });
  }

  private static ArchiveTxNumIndex committedIndex(long blockNum, byte[] blockHash,
      int userTxCount) {
    ArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    index.beginBlock(blockNum, ArchiveSource.NORMAL);
    index.allocateSystemTx(blockNum, ArchivePhase.BLOCK_PREPARE);
    for (int i = 0; i < userTxCount; i++) {
      index.allocateUserTx(blockNum, i, txId(i));
    }
    index.allocateSystemTx(blockNum, ArchivePhase.BLOCK_FINALIZE);
    index.commitBlock(blockNum, blockHash, userTxCount);
    return index;
  }

  private static byte[] blockHash(int seed) {
    byte[] hash = new byte[ArchiveBlockRange.BLOCK_HASH_LENGTH];
    hash[ArchiveBlockRange.BLOCK_HASH_LENGTH - 1] = (byte) seed;
    return hash;
  }

  private static byte[] txId(int seed) {
    byte[] txId = new byte[ArchiveBlockRange.BLOCK_HASH_LENGTH];
    txId[txId.length - 1] = (byte) seed;
    return txId;
  }
}
