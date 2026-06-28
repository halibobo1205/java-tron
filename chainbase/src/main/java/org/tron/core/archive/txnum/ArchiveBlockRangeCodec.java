package org.tron.core.archive.txnum;

import com.google.common.primitives.Bytes;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import org.tron.core.archive.ArchiveSource;

/**
 * On-disk byte layout for the persistent txNum index. A 1-byte family prefix separates the
 * block-range entries (keyed by block number) from the single committed-txNum cursor.
 *
 * <ul>
 *   <li>range key: {@code 0x00 || blockNum(8, BE)} -&gt; encoded {@link ArchiveBlockRange}</li>
 *   <li>cursor key: {@code 0x01} -&gt; committedNextTxNum(8, big-endian)</li>
 *   <li>range value: 5 longs (blockNum, firstTxNum, lastTxNum, prepareTxNum, finalizeTxNum)
 *       || userTxCount(int) || source(1 byte ordinal) = 45 bytes</li>
 * </ul>
 */
public final class ArchiveBlockRangeCodec {

  static final byte RANGE_PREFIX = 0x00;
  static final byte CURSOR_PREFIX = 0x01;
  static final byte[] CURSOR_KEY = {CURSOR_PREFIX};
  // The lowest block ever committed to this index -- written once on the first commit and never
  // overwritten. The historical-read coverage gate uses it to tell a genesis-complete archive
  // (where a MISSING dynamic-property is unambiguously the in-memory default) from a mid-chain one.
  static final byte FIRST_BLOCK_PREFIX = 0x02;
  static final byte[] FIRST_BLOCK_KEY = {FIRST_BLOCK_PREFIX};

  private ArchiveBlockRangeCodec() {
  }

  static byte[] rangeKey(long blockNum) {
    return Bytes.concat(new byte[] {RANGE_PREFIX}, Longs.toByteArray(blockNum));
  }

  static byte[] encodeRange(ArchiveBlockRange range) {
    return Bytes.concat(
        Longs.toByteArray(range.getBlockNum()),
        Longs.toByteArray(range.getFirstTxNum()),
        Longs.toByteArray(range.getLastTxNum()),
        Longs.toByteArray(range.getPrepareTxNum()),
        Longs.toByteArray(range.getFinalizeTxNum()),
        Ints.toByteArray(range.getUserTxCount()),
        new byte[] {(byte) range.getSource().ordinal()});
  }

  static ArchiveBlockRange decodeRange(byte[] bytes) {
    long blockNum = Longs.fromBytes(bytes[0], bytes[1], bytes[2], bytes[3],
        bytes[4], bytes[5], bytes[6], bytes[7]);
    long firstTxNum = longAt(bytes, 8);
    long lastTxNum = longAt(bytes, 16);
    long prepareTxNum = longAt(bytes, 24);
    long finalizeTxNum = longAt(bytes, 32);
    int userTxCount = Ints.fromBytes(bytes[40], bytes[41], bytes[42], bytes[43]);
    ArchiveSource source = ArchiveSource.values()[bytes[44]];
    return new ArchiveBlockRange(blockNum, firstTxNum, lastTxNum, prepareTxNum, finalizeTxNum,
        userTxCount, source);
  }

  static byte[] encodeCursor(long committedNextTxNum) {
    return Longs.toByteArray(committedNextTxNum);
  }

  static long decodeCursor(byte[] bytes) {
    return Longs.fromByteArray(bytes);
  }

  static byte[] encodeFirstBlock(long blockNum) {
    return Longs.toByteArray(blockNum);
  }

  static long decodeFirstBlock(byte[] bytes) {
    return Longs.fromByteArray(bytes);
  }

  private static long longAt(byte[] bytes, int offset) {
    return Longs.fromBytes(bytes[offset], bytes[offset + 1], bytes[offset + 2], bytes[offset + 3],
        bytes[offset + 4], bytes[offset + 5], bytes[offset + 6], bytes[offset + 7]);
  }
}
