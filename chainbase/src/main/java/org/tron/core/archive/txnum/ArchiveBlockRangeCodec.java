package org.tron.core.archive.txnum;

import com.google.common.primitives.Bytes;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import java.util.Arrays;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveSource;

/**
 * On-disk byte layout for the persistent txNum index. A 1-byte family prefix separates the
 * block-range entries (keyed by block number) from the single committed-txNum cursor.
 *
 * <ul>
 *   <li>range key: {@code 0x00 || blockNum(8, BE)} -&gt; encoded {@link ArchiveBlockRange}</li>
 *   <li>cursor key: {@code 0x01} -&gt; committedNextTxNum(8, big-endian)</li>
 *   <li>position key: {@code 0x03 || txNum(8, BE)} -&gt; encoded {@link ArchiveTxPosition}</li>
 *   <li>block-index key: {@code 0x04 || blockNum(8, BE) || txIndex(4, BE)} -&gt; txNum</li>
 *   <li>txId key: {@code 0x05 || txId} -&gt; txNum</li>
 *   <li>range value: 5 longs (blockNum, firstTxNum, lastTxNum, prepareTxNum, finalizeTxNum)
 *       || userTxCount(int) || source(1 byte ordinal) || blockHashLen(int) || blockHash</li>
 * </ul>
 */
public final class ArchiveBlockRangeCodec {

  static final byte RANGE_PREFIX = 0x00;
  static final byte CURSOR_PREFIX = 0x01;
  static final byte[] CURSOR_KEY = {CURSOR_PREFIX};
  // The lowest block currently committed to this index -- written for the first committed range and
  // cleared if the archive is unwound back to empty. The historical-read coverage gate uses it to
  // tell a genesis-complete archive
  // (where a MISSING dynamic-property is unambiguously the in-memory default) from a mid-chain one.
  static final byte FIRST_BLOCK_PREFIX = 0x02;
  static final byte[] FIRST_BLOCK_KEY = {FIRST_BLOCK_PREFIX};
  static final byte POSITION_PREFIX = 0x03;
  static final byte BLOCK_INDEX_PREFIX = 0x04;
  static final byte TX_ID_PREFIX = 0x05;
  static final byte[] REPAIR_REQUIRED_KEY = {0x06};

  private ArchiveBlockRangeCodec() {
  }

  static byte[] rangeKey(long blockNum) {
    return Bytes.concat(new byte[] {RANGE_PREFIX}, Longs.toByteArray(blockNum));
  }

  static byte[] positionKey(long txNum) {
    return Bytes.concat(new byte[] {POSITION_PREFIX}, Longs.toByteArray(txNum));
  }

  static byte[] blockIndexKey(long blockNum, int txIndex) {
    return Bytes.concat(new byte[] {BLOCK_INDEX_PREFIX}, Longs.toByteArray(blockNum),
        Ints.toByteArray(txIndex));
  }

  static byte[] txIdKey(byte[] txId) {
    return Bytes.concat(new byte[] {TX_ID_PREFIX}, txId);
  }

  static byte[] encodeRange(ArchiveBlockRange range) {
    requireBlockHash(range.getBlockHash(), "encode archive block range");
    return Bytes.concat(
        Longs.toByteArray(range.getBlockNum()),
        Longs.toByteArray(range.getFirstTxNum()),
        Longs.toByteArray(range.getLastTxNum()),
        Longs.toByteArray(range.getPrepareTxNum()),
        Longs.toByteArray(range.getFinalizeTxNum()),
        Ints.toByteArray(range.getUserTxCount()),
        new byte[] {(byte) range.getSource().ordinal()},
        Ints.toByteArray(range.getBlockHash().length),
        range.getBlockHash());
  }

  static ArchiveBlockRange decodeRange(byte[] bytes) {
    if (bytes == null || bytes.length < 49) {
      throw new ArchiveException("archive block range value is too short");
    }
    long blockNum = Longs.fromBytes(bytes[0], bytes[1], bytes[2], bytes[3],
        bytes[4], bytes[5], bytes[6], bytes[7]);
    long firstTxNum = longAt(bytes, 8);
    long lastTxNum = longAt(bytes, 16);
    long prepareTxNum = longAt(bytes, 24);
    long finalizeTxNum = longAt(bytes, 32);
    int userTxCount = Ints.fromBytes(bytes[40], bytes[41], bytes[42], bytes[43]);
    ArchiveSource source = ArchiveSource.values()[bytes[44]];
    int blockHashLen = Ints.fromBytes(bytes[45], bytes[46], bytes[47], bytes[48]);
    if (blockHashLen != ArchiveBlockRange.BLOCK_HASH_LENGTH
        || bytes.length != 49 + ArchiveBlockRange.BLOCK_HASH_LENGTH) {
      throw new ArchiveException("archive block range has invalid block hash length "
          + blockHashLen);
    }
    byte[] blockHash = Arrays.copyOfRange(bytes, 49, 49 + blockHashLen);
    return new ArchiveBlockRange(blockNum, firstTxNum, lastTxNum, prepareTxNum, finalizeTxNum,
        blockHash, userTxCount, source);
  }

  static void requireBlockHash(byte[] blockHash, String what) {
    if (blockHash == null || blockHash.length != ArchiveBlockRange.BLOCK_HASH_LENGTH) {
      throw new ArchiveException(what + " requires a 32-byte block hash");
    }
  }

  static byte[] encodePosition(ArchiveTxPosition position) {
    byte[] txId = position.getTxId();
    return Bytes.concat(
        Longs.toByteArray(position.getTxNum()),
        Longs.toByteArray(position.getBlockNum()),
        new byte[] {(byte) position.getPhase().ordinal()},
        new byte[] {(byte) position.getSource().ordinal()},
        Ints.toByteArray(position.getTxIndex()),
        Ints.toByteArray(txId.length),
        txId);
  }

  static ArchiveTxPosition decodePosition(byte[] bytes) {
    long txNum = longAt(bytes, 0);
    long blockNum = longAt(bytes, 8);
    ArchivePhase phase = ArchivePhase.values()[bytes[16]];
    ArchiveSource source = ArchiveSource.values()[bytes[17]];
    int txIndex = Ints.fromBytes(bytes[18], bytes[19], bytes[20], bytes[21]);
    int txIdLen = Ints.fromBytes(bytes[22], bytes[23], bytes[24], bytes[25]);
    byte[] txId = Arrays.copyOfRange(bytes, 26, 26 + txIdLen);
    return new ArchiveTxPosition(txNum, blockNum, phase, source, txIndex, txId);
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

  static byte[] encodeRepairRequired(String reason) {
    return reason.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  static String decodeRepairRequired(byte[] bytes) {
    return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
  }

  private static long longAt(byte[] bytes, int offset) {
    return Longs.fromBytes(bytes[offset], bytes[offset + 1], bytes[offset + 2], bytes[offset + 3],
        bytes[offset + 4], bytes[offset + 5], bytes[offset + 6], bytes[offset + 7]);
  }
}
