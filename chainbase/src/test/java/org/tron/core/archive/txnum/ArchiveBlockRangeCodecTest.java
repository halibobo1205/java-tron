package org.tron.core.archive.txnum;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

import java.util.Arrays;
import org.junit.Test;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveSource;

public class ArchiveBlockRangeCodecTest {

  private static final int RANGE_SOURCE_OFFSET = 45;
  private static final int RANGE_BLOCK_HASH_LENGTH_OFFSET =
      1 + Long.BYTES * 5 + Integer.BYTES + 1;
  private static final int RANGE_SCHEMA_CHECKSUM_LENGTH_OFFSET =
      RANGE_BLOCK_HASH_LENGTH_OFFSET + Integer.BYTES + ArchiveBlockRange.BLOCK_HASH_LENGTH;
  private static final int RANGE_FIRST_TX_NUM_OFFSET = 9;
  private static final int RANGE_USER_TX_COUNT_OFFSET = 41;
  private static final int POSITION_PHASE_OFFSET = 17;
  private static final int POSITION_SOURCE_OFFSET = 18;
  private static final int POSITION_BLOCK_NUM_OFFSET = 9;
  private static final int POSITION_TX_ID_LENGTH_OFFSET = 23;
  private static final int POSITION_TX_ID_OFFSET = 27;

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
    assertArrayEquals(schemaChecksum(7), back.getSchemaChecksum());
  }

  @Test
  public void txPositionRoundTrips() {
    byte[] blockHash = blockHash(8);
    ArchiveTxPosition position = new ArchiveTxPosition(12, 7, ArchivePhase.USER_TX,
        ArchiveSource.REPLAY, 1, txId(9), blockHash);

    ArchiveTxPosition back =
        ArchiveBlockRangeCodec.decodePosition(ArchiveBlockRangeCodec.encodePosition(position));

    assertEquals(12, back.getTxNum());
    assertEquals(7, back.getBlockNum());
    assertEquals(ArchivePhase.USER_TX, back.getPhase());
    assertEquals(ArchiveSource.REPLAY, back.getSource());
    assertEquals(1, back.getTxIndex());
    assertArrayEquals(txId(9), back.getTxId());
    assertArrayEquals(blockHash, back.getBlockHash());
  }

  @Test
  public void decodeRejectsUnsupportedRangeVersion() {
    byte[] encoded = ArchiveBlockRangeCodec.encodeRange(range(blockHash(7)));
    encoded[0] = 2;

    assertThrows(ArchiveException.class, () -> ArchiveBlockRangeCodec.decodeRange(encoded));
  }

  @Test
  public void decodeRejectsEmptyBlockHash() {
    byte[] emptyHash = ArchiveBlockRangeCodec.encodeRange(range(blockHash(7)));
    putInt(emptyHash, RANGE_BLOCK_HASH_LENGTH_OFFSET, 0);

    assertThrows(ArchiveException.class, () -> ArchiveBlockRangeCodec.decodeRange(emptyHash));
  }

  @Test
  public void decodeRejectsInvalidSchemaChecksumLength() {
    byte[] invalidChecksum = ArchiveBlockRangeCodec.encodeRange(range(blockHash(7)));
    putInt(invalidChecksum, RANGE_SCHEMA_CHECKSUM_LENGTH_OFFSET, 0);

    assertThrows(ArchiveException.class,
        () -> ArchiveBlockRangeCodec.decodeRange(invalidChecksum));
  }

  @Test
  public void decodeRejectsTrailingGarbage() {
    byte[] withGarbage = Arrays.copyOf(
        ArchiveBlockRangeCodec.encodeRange(range(blockHash(7))),
        ArchiveBlockRangeCodec.encodeRange(range(blockHash(7))).length + 1);

    assertThrows(ArchiveException.class, () -> ArchiveBlockRangeCodec.decodeRange(withGarbage));
  }

  @Test
  public void decodeRejectsInvalidRangeSourceOrdinal() {
    byte[] encoded = ArchiveBlockRangeCodec.encodeRange(range(blockHash(7)));
    encoded[RANGE_SOURCE_OFFSET] = 127;

    assertThrows(ArchiveException.class, () -> ArchiveBlockRangeCodec.decodeRange(encoded));
  }

  @Test
  public void rangeCodecRejectsNegativeCoordinates() {
    assertThrows(ArchiveException.class,
        () -> ArchiveBlockRangeCodec.encodeRange(new ArchiveBlockRange(
            7, -1, 1, -1, 1, blockHash(7), 0, ArchiveSource.NORMAL, schemaChecksum(7))));

    byte[] encoded = ArchiveBlockRangeCodec.encodeRange(range(blockHash(7)));
    putLong(encoded, RANGE_FIRST_TX_NUM_OFFSET, -1L);

    assertThrows(ArchiveException.class, () -> ArchiveBlockRangeCodec.decodeRange(encoded));
  }

  @Test
  public void rangeCodecRejectsNegativeUserTxCount() {
    assertThrows(ArchiveException.class,
        () -> ArchiveBlockRangeCodec.encodeRange(new ArchiveBlockRange(
            7, 0, 1, 0, 1, blockHash(7), -1, ArchiveSource.NORMAL, schemaChecksum(7))));

    byte[] encoded = ArchiveBlockRangeCodec.encodeRange(range(blockHash(7)));
    putInt(encoded, RANGE_USER_TX_COUNT_OFFSET, -1);

    assertThrows(ArchiveException.class, () -> ArchiveBlockRangeCodec.decodeRange(encoded));
  }

  @Test
  public void decodePositionRejectsUnsupportedVersion() {
    ArchiveTxPosition position = new ArchiveTxPosition(12, 7, ArchivePhase.USER_TX,
        ArchiveSource.REPLAY, 1, txId(9), blockHash(8));
    byte[] encoded = ArchiveBlockRangeCodec.encodePosition(position);
    encoded[0] = 2;

    assertThrows(ArchiveException.class, () -> ArchiveBlockRangeCodec.decodePosition(encoded));
  }

  @Test
  public void decodePositionRejectsInvalidBlockHashLength() {
    ArchiveTxPosition position = position();
    byte[] encoded = ArchiveBlockRangeCodec.encodePosition(position);
    putInt(encoded, POSITION_TX_ID_OFFSET + position.getTxId().length, 0);

    assertThrows(ArchiveException.class, () -> ArchiveBlockRangeCodec.decodePosition(encoded));
  }

  @Test
  public void decodePositionRejectsTrailingGarbage() {
    byte[] encoded = ArchiveBlockRangeCodec.encodePosition(position());
    byte[] withGarbage = Arrays.copyOf(encoded, encoded.length + 1);

    assertThrows(ArchiveException.class,
        () -> ArchiveBlockRangeCodec.decodePosition(withGarbage));
  }

  @Test
  public void decodePositionRejectsInvalidTxIdLength() {
    byte[] encoded = ArchiveBlockRangeCodec.encodePosition(position());
    putInt(encoded, POSITION_TX_ID_LENGTH_OFFSET, Integer.MAX_VALUE);

    assertThrows(ArchiveException.class, () -> ArchiveBlockRangeCodec.decodePosition(encoded));
  }

  @Test
  public void decodePositionRejectsInvalidPhaseOrdinal() {
    byte[] encoded = ArchiveBlockRangeCodec.encodePosition(position());
    encoded[POSITION_PHASE_OFFSET] = 127;

    assertThrows(ArchiveException.class, () -> ArchiveBlockRangeCodec.decodePosition(encoded));
  }

  @Test
  public void decodePositionRejectsInvalidSourceOrdinal() {
    byte[] encoded = ArchiveBlockRangeCodec.encodePosition(position());
    encoded[POSITION_SOURCE_OFFSET] = 127;

    assertThrows(ArchiveException.class, () -> ArchiveBlockRangeCodec.decodePosition(encoded));
  }

  @Test
  public void positionCodecRejectsNegativeCoordinates() {
    assertThrows(ArchiveException.class,
        () -> ArchiveBlockRangeCodec.encodePosition(new ArchiveTxPosition(
            -1, 7, ArchivePhase.USER_TX, ArchiveSource.REPLAY, 1, txId(9),
            blockHash(8))));

    byte[] encoded = ArchiveBlockRangeCodec.encodePosition(position());
    putLong(encoded, POSITION_BLOCK_NUM_OFFSET, -1L);

    assertThrows(ArchiveException.class, () -> ArchiveBlockRangeCodec.decodePosition(encoded));
  }

  @Test
  public void cursorRoundTrips() {
    assertEquals(42L,
        ArchiveBlockRangeCodec.decodeCursor(ArchiveBlockRangeCodec.encodeCursor(42L)));
  }

  @Test
  public void decodeCursorRejectsInvalidLength() {
    assertThrows(ArchiveException.class,
        () -> ArchiveBlockRangeCodec.decodeCursor(new byte[] {1}));
  }

  @Test
  public void decodeFirstBlockRejectsInvalidLength() {
    assertThrows(ArchiveException.class,
        () -> ArchiveBlockRangeCodec.decodeFirstBlock(new byte[] {1}));
  }

  @Test
  public void rangeKeyIsPrefixedAndDistinctFromCursor() {
    assertEquals(ArchiveBlockRangeCodec.TXNUM_BLOCK_PREFIX,
        ArchiveBlockRangeCodec.rangeKey(1)[0]);
    assertEquals(9, ArchiveBlockRangeCodec.rangeKey(1).length); // prefix(1) + blockNum(8)
    assertNotEquals(ArchiveBlockRangeCodec.rangeKey(1)[0], ArchiveBlockRangeCodec.CURSOR_KEY[0]);
  }

  @Test
  public void keyCodecsRejectNegativeCoordinates() {
    assertThrows(ArchiveException.class, () -> ArchiveBlockRangeCodec.rangeKey(-1));
    assertThrows(ArchiveException.class, () -> ArchiveBlockRangeCodec.positionKey(-1));

    byte[] rangeKey = ArchiveBlockRangeCodec.rangeKey(1);
    putLong(rangeKey, 1, -1L);
    assertThrows(ArchiveException.class,
        () -> ArchiveBlockRangeCodec.blockNumFromRangeKey(rangeKey));

    byte[] positionKey = ArchiveBlockRangeCodec.positionKey(1);
    putLong(positionKey, 1, -1L);
    assertThrows(ArchiveException.class,
        () -> ArchiveBlockRangeCodec.txNumFromPositionKey(positionKey));
  }

  @Test
  public void txIdKeyUsesL5PrefixAndLengthPrefix() {
    byte[] txId = txId(3);
    byte[] key = ArchiveBlockRangeCodec.txIdKey(txId);

    assertEquals(ArchiveBlockRangeCodec.TXNUM_BY_TXID_PREFIX, key[0]);
    assertArrayEquals(new byte[] {0, 0, 0, 32}, Arrays.copyOfRange(key, 1, 5));
    assertArrayEquals(txId, Arrays.copyOfRange(key, 5, 37));
  }

  @Test
  public void txIdKeyRejectsEmptyInput() {
    assertThrows(ArchiveException.class, () -> ArchiveBlockRangeCodec.txIdKey(null));
    assertThrows(ArchiveException.class, () -> ArchiveBlockRangeCodec.txIdKey(new byte[0]));
    assertThrows(ArchiveException.class, () -> ArchiveBlockRangeCodec.txIdKey(new byte[] {1}));
  }

  private static ArchiveBlockRange range(byte[] blockHash) {
    return new ArchiveBlockRange(7, 10, 15, 10, 15, blockHash, 3, ArchiveSource.REPLAY,
        schemaChecksum(7));
  }

  private static ArchiveTxPosition position() {
    return new ArchiveTxPosition(12, 7, ArchivePhase.USER_TX, ArchiveSource.REPLAY, 1,
        txId(9), blockHash(8));
  }

  private static byte[] blockHash(int seed) {
    byte[] hash = new byte[ArchiveBlockRange.BLOCK_HASH_LENGTH];
    hash[ArchiveBlockRange.BLOCK_HASH_LENGTH - 1] = (byte) seed;
    return hash;
  }

  private static byte[] schemaChecksum(int seed) {
    byte[] checksum = new byte[ArchiveBlockRange.SCHEMA_CHECKSUM_LENGTH];
    checksum[ArchiveBlockRange.SCHEMA_CHECKSUM_LENGTH - 1] = (byte) seed;
    return checksum;
  }

  private static byte[] txId(int seed) {
    byte[] txId = new byte[ArchiveBlockRangeCodec.TX_ID_LENGTH];
    txId[txId.length - 1] = (byte) seed;
    return txId;
  }

  private static void putInt(byte[] bytes, int offset, int value) {
    for (int i = Integer.BYTES - 1; i >= 0; i--) {
      bytes[offset + i] = (byte) value;
      value >>>= Byte.SIZE;
    }
  }

  private static void putLong(byte[] bytes, int offset, long value) {
    for (int i = Long.BYTES - 1; i >= 0; i--) {
      bytes[offset + i] = (byte) value;
      value >>>= Byte.SIZE;
    }
  }
}
