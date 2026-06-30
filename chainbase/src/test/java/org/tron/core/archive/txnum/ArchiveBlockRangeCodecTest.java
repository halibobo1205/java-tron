package org.tron.core.archive.txnum;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;
import org.tron.core.archive.ArchiveSource;

public class ArchiveBlockRangeCodecTest {

  @Test
  public void rangeRoundTrips() {
    byte[] blockHash = {1, 2, 3};
    ArchiveBlockRange range = new ArchiveBlockRange(
        7, 10, 15, 10, 15, blockHash, 3, ArchiveSource.REPLAY);
    ArchiveBlockRange back =
        ArchiveBlockRangeCodec.decodeRange(ArchiveBlockRangeCodec.encodeRange(range));
    assertEquals(7, back.getBlockNum());
    assertEquals(10, back.getFirstTxNum());
    assertEquals(15, back.getLastTxNum());
    assertEquals(10, back.getPrepareTxNum());
    assertEquals(15, back.getFinalizeTxNum());
    assertArrayEquals(blockHash, back.getBlockHash());
    assertEquals(3, back.getUserTxCount());
    assertEquals(ArchiveSource.REPLAY, back.getSource());
  }

  @Test
  public void cursorRoundTrips() {
    assertEquals(42L,
        ArchiveBlockRangeCodec.decodeCursor(ArchiveBlockRangeCodec.encodeCursor(42L)));
  }

  @Test
  public void rangeKeyIsPrefixedAndDistinctFromCursor() {
    assertEquals(ArchiveBlockRangeCodec.RANGE_PREFIX, ArchiveBlockRangeCodec.rangeKey(1)[0]);
    assertEquals(9, ArchiveBlockRangeCodec.rangeKey(1).length); // prefix(1) + blockNum(8)
    assertNotEquals(ArchiveBlockRangeCodec.rangeKey(1)[0], ArchiveBlockRangeCodec.CURSOR_KEY[0]);
  }
}
