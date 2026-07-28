package org.tron.core.archive.txnum;

import com.google.common.primitives.Bytes;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveSource;

/**
 * On-disk byte layout for the UNIFIED_V1 txNum index. A 1-byte table prefix follows the L5 archive
 * keyspace for index rows, while small operational metadata lives in the META column family.
 *
 * <ul>
 *   <li>range key: {@code 0x10 || blockNum(8, BE)} -&gt; encoded {@link ArchiveBlockRange}</li>
 *   <li>txId key: {@code 0x11 || txIdLen(4) || txId} -&gt; txNum</li>
 *   <li>position key: {@code 0x12 || txNum(8, BE)} -&gt; encoded {@link ArchiveTxPosition}</li>
 *   <li>block-index key: {@code 0x13 || blockNum(8, BE) || txIndex(4, BE)} -&gt; txNum</li>
 *   <li>meta key: {@code 0x01 || asciiName} -&gt; cursor/repair metadata</li>
 *   <li>range value: version || block/txNum fields || userTxCount || source ||
 *       blockHashLen/blockHash || schemaChecksumLen/schemaChecksum</li>
 * </ul>
 */
public final class ArchiveBlockRangeCodec {

  private static final byte VALUE_VERSION = 0x01;

  static final byte META_PREFIX = 0x01;
  static final byte TXNUM_BLOCK_PREFIX = 0x10;
  static final byte TXNUM_BY_TXID_PREFIX = 0x11;
  static final byte TXNUM_META_PREFIX = 0x12;
  static final byte TXNUM_BY_BLOCK_INDEX_PREFIX = 0x13;
  static final int TX_ID_LENGTH = ArchiveBlockRange.BLOCK_HASH_LENGTH;
  static final int RANGE_VALUE_LENGTH = 1 + Long.BYTES * 5 + Integer.BYTES + 1
      + Integer.BYTES + ArchiveBlockRange.BLOCK_HASH_LENGTH
      + Integer.BYTES + ArchiveBlockRange.SCHEMA_CHECKSUM_LENGTH;
  static final int POSITION_VALUE_MAX_LENGTH = 1 + Long.BYTES * 2 + 2 + Integer.BYTES * 3
      + TX_ID_LENGTH + ArchiveBlockRange.BLOCK_HASH_LENGTH;

  // The lowest block currently committed to this index. The historical-read coverage gate uses it
  // to distinguish genesis-complete archives from mid-chain archives.
  static final byte[] FIRST_BLOCK_KEY = metaKey("first-block");
  static final byte[] REPAIR_REQUIRED_KEY = metaKey("repair-required");

  private ArchiveBlockRangeCodec() {
  }

  /** Stable META key used to atomically flag writes that require a full startup scrub. */
  public static byte[] repairRequiredKey() {
    return Arrays.copyOf(REPAIR_REQUIRED_KEY, REPAIR_REQUIRED_KEY.length);
  }

  static byte[] rangeKey(long blockNum) {
    ArchiveCoordinates.requireBlockNum(blockNum, "archive block range block number");
    return Bytes.concat(new byte[] {TXNUM_BLOCK_PREFIX}, Longs.toByteArray(blockNum));
  }

  static byte[] positionKey(long txNum) {
    ArchiveCoordinates.requireTxNum(txNum, "archive tx-position txNum");
    return Bytes.concat(new byte[] {TXNUM_META_PREFIX}, Longs.toByteArray(txNum));
  }

  static byte[] txIdKey(byte[] txId) {
    requireTxId(txId, "archive txId key");
    return Bytes.concat(new byte[] {TXNUM_BY_TXID_PREFIX}, Ints.toByteArray(txId.length), txId);
  }

  static byte[] blockIndexKey(long blockNum, int txIndex) {
    ArchiveCoordinates.requireBlockNum(blockNum, "archive block-index block number");
    if (txIndex < 0) {
      throw new ArchiveException("archive block-index transaction index must be non-negative");
    }
    return Bytes.concat(
        new byte[] {TXNUM_BY_BLOCK_INDEX_PREFIX},
        Longs.toByteArray(blockNum),
        Ints.toByteArray(txIndex));
  }

  static long blockNumFromRangeKey(byte[] key) {
    if (key == null || key.length != 1 + Long.BYTES
        || key[0] != TXNUM_BLOCK_PREFIX) {
      throw new ArchiveException("archive block range key is invalid");
    }
    long blockNum = longAt(key, 1);
    ArchiveCoordinates.requireBlockNum(blockNum, "archive block range key block number");
    return blockNum;
  }

  static long txNumFromPositionKey(byte[] key) {
    if (key == null || key.length != 1 + Long.BYTES
        || key[0] != TXNUM_META_PREFIX) {
      throw new ArchiveException("archive tx-position key is invalid");
    }
    long txNum = longAt(key, 1);
    ArchiveCoordinates.requireTxNum(txNum, "archive tx-position key txNum");
    return txNum;
  }

  static long blockNumFromBlockIndexKey(byte[] key) {
    requireBlockIndexKey(key);
    long blockNum = longAt(key, 1);
    ArchiveCoordinates.requireBlockNum(blockNum, "archive block-index key block number");
    return blockNum;
  }

  static int txIndexFromBlockIndexKey(byte[] key) {
    requireBlockIndexKey(key);
    int txIndex = intAt(key, 1 + Long.BYTES);
    if (txIndex < 0) {
      throw new ArchiveException("archive block-index key has a negative transaction index");
    }
    return txIndex;
  }

  static byte[] txIdFromKey(byte[] key) {
    if (key == null || key.length < 1 + Integer.BYTES
        || key[0] != TXNUM_BY_TXID_PREFIX) {
      throw new ArchiveException("archive txId key is invalid");
    }
    int txIdLen = intAt(key, 1);
    if (txIdLen != TX_ID_LENGTH || key.length != 1 + Integer.BYTES + txIdLen) {
      throw new ArchiveException("archive txId key has invalid txId length " + txIdLen);
    }
    return Arrays.copyOfRange(key, 1 + Integer.BYTES, key.length);
  }

  private static byte[] metaKey(String name) {
    return Bytes.concat(new byte[] {META_PREFIX}, name.getBytes(StandardCharsets.US_ASCII));
  }

  private static void requireBlockIndexKey(byte[] key) {
    if (key == null
        || key.length != 1 + Long.BYTES + Integer.BYTES
        || key[0] != TXNUM_BY_BLOCK_INDEX_PREFIX) {
      throw new ArchiveException("archive block-index key is invalid");
    }
  }

  static byte[] encodeRange(ArchiveBlockRange range) {
    ArchiveCoordinates.requireBlockNum(
        range.getBlockNum(), "archive block range block number");
    ArchiveCoordinates.requireTxNum(
        range.getFirstTxNum(), "archive block range first txNum");
    ArchiveCoordinates.requireTxNum(
        range.getLastTxNum(), "archive block range last txNum");
    ArchiveCoordinates.requireTxNum(
        range.getPrepareTxNum(), "archive block range prepare txNum");
    ArchiveCoordinates.requireTxNum(
        range.getFinalizeTxNum(), "archive block range finalize txNum");
    if (range.getUserTxCount() < 0) {
      throw new ArchiveException("archive block range user tx count must be non-negative");
    }
    requireBlockHash(range.getBlockHash(), "encode archive block range");
    requireSchemaChecksum(range.getSchemaChecksum(), "encode archive block range");
    return Bytes.concat(
        new byte[] {VALUE_VERSION},
        Longs.toByteArray(range.getBlockNum()),
        Longs.toByteArray(range.getFirstTxNum()),
        Longs.toByteArray(range.getLastTxNum()),
        Longs.toByteArray(range.getPrepareTxNum()),
        Longs.toByteArray(range.getFinalizeTxNum()),
        Ints.toByteArray(range.getUserTxCount()),
        new byte[] {(byte) range.getSource().ordinal()},
        Ints.toByteArray(range.getBlockHash().length),
        range.getBlockHash(),
        Ints.toByteArray(range.getSchemaChecksum().length),
        range.getSchemaChecksum());
  }

  static ArchiveBlockRange decodeRange(byte[] bytes) {
    if (bytes == null || bytes.length < 1) {
      throw new ArchiveException("archive block range value is too short");
    }
    requireVersion(bytes[0], "archive block range");
    if (bytes.length < 50) {
      throw new ArchiveException("archive block range value is too short");
    }
    long blockNum = longAt(bytes, 1);
    long firstTxNum = longAt(bytes, 9);
    long lastTxNum = longAt(bytes, 17);
    long prepareTxNum = longAt(bytes, 25);
    long finalizeTxNum = longAt(bytes, 33);
    int userTxCount = intAt(bytes, 41);
    ArchiveCoordinates.requireBlockNum(blockNum, "archive block range block number");
    ArchiveCoordinates.requireTxNum(firstTxNum, "archive block range first txNum");
    ArchiveCoordinates.requireTxNum(lastTxNum, "archive block range last txNum");
    ArchiveCoordinates.requireTxNum(prepareTxNum, "archive block range prepare txNum");
    ArchiveCoordinates.requireTxNum(finalizeTxNum, "archive block range finalize txNum");
    if (userTxCount < 0) {
      throw new ArchiveException("archive block range user tx count must be non-negative");
    }
    ArchiveSource source = sourceAt(bytes[45], "archive block range");
    int blockHashLen = intAt(bytes, 46);
    if (blockHashLen != ArchiveBlockRange.BLOCK_HASH_LENGTH
        || bytes.length < 50 + blockHashLen + Integer.BYTES) {
      throw new ArchiveException("archive block range has invalid block hash length "
          + blockHashLen);
    }
    byte[] blockHash = Arrays.copyOfRange(bytes, 50, 50 + blockHashLen);
    int checksumOffset = 50 + blockHashLen;
    int checksumLen = intAt(bytes, checksumOffset);
    if (checksumLen != ArchiveBlockRange.SCHEMA_CHECKSUM_LENGTH
        || bytes.length != checksumOffset + Integer.BYTES + checksumLen) {
      throw new ArchiveException("archive block range has invalid schema checksum length "
          + checksumLen);
    }
    byte[] schemaChecksum = Arrays.copyOfRange(bytes, checksumOffset + Integer.BYTES,
        checksumOffset + Integer.BYTES + checksumLen);
    return new ArchiveBlockRange(blockNum, firstTxNum, lastTxNum, prepareTxNum, finalizeTxNum,
        blockHash, userTxCount, source, schemaChecksum);
  }

  static void requireBlockHash(byte[] blockHash, String what) {
    if (blockHash == null || blockHash.length != ArchiveBlockRange.BLOCK_HASH_LENGTH) {
      throw new ArchiveException(what + " requires a 32-byte block hash");
    }
  }

  static void requireSchemaChecksum(byte[] schemaChecksum, String what) {
    if (schemaChecksum == null
        || schemaChecksum.length != ArchiveBlockRange.SCHEMA_CHECKSUM_LENGTH) {
      throw new ArchiveException(what + " requires a 32-byte schema checksum");
    }
  }

  static byte[] encodePosition(ArchiveTxPosition position) {
    ArchiveCoordinates.requireTxNum(position.getTxNum(), "archive tx-position txNum");
    ArchiveCoordinates.requireBlockNum(
        position.getBlockNum(), "archive tx-position block number");
    int txIdSize = position.txIdSize();
    int blockHashSize = position.blockHashSize();
    requirePositionTxIdSize(
        position.getPhase(), txIdSize, "encode archive tx-position");
    if (blockHashSize != ArchiveBlockRange.BLOCK_HASH_LENGTH) {
      throw new ArchiveException("encode archive tx-position requires a 32-byte block hash");
    }
    ByteBuffer encoded = ByteBuffer.allocate(
        1 + 2 * Long.BYTES + 2 * Byte.BYTES + 3 * Integer.BYTES
            + txIdSize + blockHashSize);
    encoded.put(VALUE_VERSION);
    encoded.putLong(position.getTxNum());
    encoded.putLong(position.getBlockNum());
    encoded.put((byte) position.getPhase().ordinal());
    encoded.put((byte) position.getSource().ordinal());
    encoded.putInt(position.getTxIndex());
    encoded.putInt(txIdSize);
    int txIdOffset = encoded.position();
    position.copyTxIdTo(encoded.array(), txIdOffset);
    encoded.position(txIdOffset + txIdSize);
    encoded.putInt(blockHashSize);
    int blockHashOffset = encoded.position();
    position.copyBlockHashTo(encoded.array(), blockHashOffset);
    encoded.position(blockHashOffset + blockHashSize);
    return encoded.array();
  }

  static ArchiveTxPosition decodePosition(byte[] bytes) {
    if (bytes == null || bytes.length < 31) {
      throw new ArchiveException("archive tx-position value is too short");
    }
    requireVersion(bytes[0], "archive tx-position");
    long txNum = longAt(bytes, 1);
    long blockNum = longAt(bytes, 9);
    ArchiveCoordinates.requireTxNum(txNum, "archive tx-position txNum");
    ArchiveCoordinates.requireBlockNum(blockNum, "archive tx-position block number");
    ArchivePhase phase = phaseAt(bytes[17], "archive tx-position");
    ArchiveSource source = sourceAt(bytes[18], "archive tx-position");
    int txIndex = intAt(bytes, 19);
    int txIdLen = intAt(bytes, 23);
    if (txIdLen < 0 || txIdLen > bytes.length - 27 - Integer.BYTES) {
      throw new ArchiveException("archive tx-position has invalid txId length " + txIdLen);
    }
    int blockHashLenOffset = 27 + txIdLen;
    byte[] txId = Arrays.copyOfRange(bytes, 27, blockHashLenOffset);
    requirePositionTxId(phase, txId, "archive tx-position");
    int blockHashLen = intAt(bytes, blockHashLenOffset);
    if (blockHashLen != ArchiveBlockRange.BLOCK_HASH_LENGTH
        || bytes.length != blockHashLenOffset + Integer.BYTES + blockHashLen) {
      throw new ArchiveException("archive tx-position has invalid block hash length "
          + blockHashLen);
    }
    byte[] blockHash = Arrays.copyOfRange(bytes, blockHashLenOffset + Integer.BYTES,
        blockHashLenOffset + Integer.BYTES + blockHashLen);
    return new ArchiveTxPosition(txNum, blockNum, phase, source, txIndex, txId, blockHash);
  }

  static byte[] encodeCursor(long committedNextTxNum) {
    ArchiveCoordinates.requireCursor(committedNextTxNum, "archive cursor value");
    return Longs.toByteArray(committedNextTxNum);
  }

  static long decodeCursor(byte[] bytes) {
    if (bytes == null || bytes.length != Long.BYTES) {
      throw new ArchiveException("archive cursor value must be 8 bytes");
    }
    long cursor = Longs.fromByteArray(bytes);
    ArchiveCoordinates.requireCursor(cursor, "archive cursor value");
    return cursor;
  }

  static byte[] encodeFirstBlock(long blockNum) {
    ArchiveCoordinates.requireBlockNum(blockNum, "archive first-block value");
    return Longs.toByteArray(blockNum);
  }

  static long decodeFirstBlock(byte[] bytes) {
    if (bytes == null || bytes.length != Long.BYTES) {
      throw new ArchiveException("archive first-block value must be 8 bytes");
    }
    long blockNum = Longs.fromByteArray(bytes);
    ArchiveCoordinates.requireBlockNum(blockNum, "archive first-block value");
    return blockNum;
  }

  public static byte[] encodeRepairRequired(String reason) {
    return reason.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  static void requirePositionTxId(ArchivePhase phase, byte[] txId, String what) {
    requirePositionTxIdSize(phase, txId == null ? 0 : txId.length, what);
  }

  private static void requirePositionTxIdSize(ArchivePhase phase, int txIdSize, String what) {
    if (phase == ArchivePhase.USER_TX || phase == ArchivePhase.USER_TX_VM) {
      if (txIdSize != TX_ID_LENGTH) {
        throw new ArchiveException(what + " user txId must be a 32-byte txId");
      }
      return;
    }
    if (txIdSize != 0) {
      throw new ArchiveException(what + " system txId must be empty");
    }
  }

  static void requireTxId(byte[] txId, String what) {
    if (txId == null || txId.length != TX_ID_LENGTH) {
      throw new ArchiveException(what + " must be a 32-byte txId");
    }
  }

  static String decodeRepairRequired(byte[] bytes) {
    if (bytes == null) {
      throw new ArchiveException("archive repair marker value is missing");
    }
    try {
      return StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException e) {
      throw new ArchiveException("archive repair marker value is invalid UTF-8", e);
    }
  }

  private static long longAt(byte[] bytes, int offset) {
    return Longs.fromBytes(bytes[offset], bytes[offset + 1], bytes[offset + 2], bytes[offset + 3],
        bytes[offset + 4], bytes[offset + 5], bytes[offset + 6], bytes[offset + 7]);
  }

  private static int intAt(byte[] bytes, int offset) {
    return Ints.fromBytes(bytes[offset], bytes[offset + 1], bytes[offset + 2],
        bytes[offset + 3]);
  }

  private static void requireVersion(byte version, String what) {
    if (version != VALUE_VERSION) {
      throw new ArchiveException(what + " has unsupported value version " + version);
    }
  }

  private static ArchiveSource sourceAt(byte value, String what) {
    int ordinal = value & 0xff;
    ArchiveSource[] sources = ArchiveSource.values();
    if (ordinal >= sources.length) {
      throw new ArchiveException(what + " has invalid source ordinal " + ordinal);
    }
    return sources[ordinal];
  }

  private static ArchivePhase phaseAt(byte value, String what) {
    int ordinal = value & 0xff;
    ArchivePhase[] phases = ArchivePhase.values();
    if (ordinal >= phases.length) {
      throw new ArchiveException(what + " has invalid phase ordinal " + ordinal);
    }
    return phases[ordinal];
  }
}
