package org.tron.core.archive.txnum;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

import java.util.Arrays;
import org.junit.Test;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.ArchiveSource;

public class ArchiveBlockRangeCodecTest {

  @Test
  public void rangeRoundTrips() {
    byte[] blockHash = blockHash(7);
    ArchiveBlockRange range = range(blockHash);
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
  public void decodeRejectsLegacyRangeWithoutBlockHash() {
    byte[] legacy = Arrays.copyOf(ArchiveBlockRangeCodec.encodeRange(range(blockHash(7))), 45);

    assertThrows(ArchiveException.class, () -> ArchiveBlockRangeCodec.decodeRange(legacy));
  }

  @Test
  public void decodeRejectsEmptyBlockHash() {
    byte[] emptyHash = Arrays.copyOf(ArchiveBlockRangeCodec.encodeRange(range(blockHash(7))), 49);
    Arrays.fill(emptyHash, 45, 49, (byte) 0);

    assertThrows(ArchiveException.class, () -> ArchiveBlockRangeCodec.decodeRange(emptyHash));
  }

  @Test
  public void decodeRejectsTrailingGarbage() {
    byte[] withGarbage = Arrays.copyOf(
        ArchiveBlockRangeCodec.encodeRange(range(blockHash(7))), 82);

    assertThrows(ArchiveException.class, () -> ArchiveBlockRangeCodec.decodeRange(withGarbage));
  }

  @Test
  public void cursorRoundTrips() {
    assertEquals(42L,
        ArchiveBlockRangeCodec.decodeCursor(ArchiveBlockRangeCodec.encodeCursor(42L)));
  }

  @Test
  public void rangeKeyIsPrefixedAndDistinctFromCursor() {
    assertEquals(ArchiveBlockRangeCodec.TXNUM_BLOCK_PREFIX,
        ArchiveBlockRangeCodec.rangeKey(1)[0]);
    assertEquals(9, ArchiveBlockRangeCodec.rangeKey(1).length); // prefix(1) + blockNum(8)
    assertNotEquals(ArchiveBlockRangeCodec.rangeKey(1)[0], ArchiveBlockRangeCodec.CURSOR_KEY[0]);
  }

  @Test
  public void txIdKeyUsesL5PrefixAndLengthPrefix() {
    byte[] key = ArchiveBlockRangeCodec.txIdKey(new byte[] {1, 2, 3});

    assertEquals(ArchiveBlockRangeCodec.TXNUM_BY_TXID_PREFIX, key[0]);
    assertArrayEquals(new byte[] {0, 0, 0, 3}, Arrays.copyOfRange(key, 1, 5));
    assertArrayEquals(new byte[] {1, 2, 3}, Arrays.copyOfRange(key, 5, 8));
  }

  @Test
  public void manifestUsesMetaPrefixNotTxNumMetaPrefix() {
    assertEquals(ArchiveBlockRangeCodec.META_PREFIX, ArchiveBlockRangeCodec.manifestKey()[0]);
    assertNotEquals(ArchiveBlockRangeCodec.TXNUM_META_PREFIX,
        ArchiveBlockRangeCodec.manifestKey()[0]);
  }

  private static ArchiveBlockRange range(byte[] blockHash) {
    return new ArchiveBlockRange(7, 10, 15, 10, 15, blockHash, 3, ArchiveSource.REPLAY);
  }

  private static byte[] blockHash(int seed) {
    byte[] hash = new byte[ArchiveBlockRange.BLOCK_HASH_LENGTH];
    hash[ArchiveBlockRange.BLOCK_HASH_LENGTH - 1] = (byte) seed;
    return hash;
  }
}
