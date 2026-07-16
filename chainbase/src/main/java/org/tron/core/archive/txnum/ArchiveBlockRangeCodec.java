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
  static final int TX_ID_LENGTH = ArchiveBlockRange.BLOCK_HASH_LENGTH;

  // The lowest block currently committed to this index -- written for the first committed range and
  // cleared if the archive is unwound back to empty. The historical-read coverage gate uses it to
  // tell a genesis-complete archive
  // (where a MISSING dynamic-property is unambiguously the in-memory default) from a mid-chain one.
  static final byte[] CURSOR_KEY = metaKey("cursor");
  static final byte[] FIRST_BLOCK_KEY = metaKey("first-block");
  static final byte[] REPAIR_REQUIRED_KEY = metaKey("repair-required");

  private ArchiveBlockRangeCodec() {
  }

  static byte[] rangeKey(long blockNum) {
    requireNonNegative(blockNum, "archive block range block number");
    return Bytes.concat(new byte[] {TXNUM_BLOCK_PREFIX}, Longs.toByteArray(blockNum));
  }

  static byte[] positionKey(long txNum) {
    requireNonNegative(txNum, "archive tx-position txNum");
    return Bytes.concat(new byte[] {TXNUM_META_PREFIX}, Longs.toByteArray(txNum));
  }

  static byte[] txIdKey(byte[] txId) {
    requireTxId(txId, "archive txId key");
    return Bytes.concat(new byte[] {TXNUM_BY_TXID_PREFIX}, Ints.toByteArray(txId.length), txId);
  }

  static long blockNumFromRangeKey(byte[] key) {
    if (key == null || key.length != 1 + Long.BYTES
        || key[0] != TXNUM_BLOCK_PREFIX) {
      throw new ArchiveException("archive block range key is invalid");
    }
    long blockNum = longAt(key, 1);
    requireNonNegative(blockNum, "archive block range key block number");
    return blockNum;
  }

  static long txNumFromPositionKey(byte[] key) {
    if (key == null || key.length != 1 + Long.BYTES
        || key[0] != TXNUM_META_PREFIX) {
      throw new ArchiveException("archive tx-position key is invalid");
    }
    long txNum = longAt(key, 1);
    requireNonNegative(txNum, "archive tx-position key txNum");
    return txNum;
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

  static byte[] encodeRange(ArchiveBlockRange range) {
    requireNonNegative(range.getBlockNum(), "archive block range block number");
    requireNonNegative(range.getFirstTxNum(), "archive block range first txNum");
    requireNonNegative(range.getLastTxNum(), "archive block range last txNum");
    requireNonNegative(range.getPrepareTxNum(), "archive block range prepare txNum");
    requireNonNegative(range.getFinalizeTxNum(), "archive block range finalize txNum");
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
    requireNonNegative(blockNum, "archive block range block number");
    requireNonNegative(firstTxNum, "archive block range first txNum");
    requireNonNegative(lastTxNum, "archive block range last txNum");
    requireNonNegative(prepareTxNum, "archive block range prepare txNum");
    requireNonNegative(finalizeTxNum, "archive block range finalize txNum");
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
    requireNonNegative(position.getTxNum(), "archive tx-position txNum");
    requireNonNegative(position.getBlockNum(), "archive tx-position block number");
    byte[] txId = position.getTxId();
    requirePositionTxId(position.getPhase(), txId, "encode archive tx-position");
    requireBlockHash(position.getBlockHash(), "encode archive tx-position");
    return Bytes.concat(
        new byte[] {VALUE_VERSION},
        Longs.toByteArray(position.getTxNum()),
        Longs.toByteArray(position.getBlockNum()),
        new byte[] {(byte) position.getPhase().ordinal()},
        new byte[] {(byte) position.getSource().ordinal()},
        Ints.toByteArray(position.getTxIndex()),
        Ints.toByteArray(txId.length),
        txId,
        Ints.toByteArray(position.getBlockHash().length),
        position.getBlockHash());
  }

  static ArchiveTxPosition decodePosition(byte[] bytes) {
    if (bytes == null || bytes.length < 31) {
      throw new ArchiveException("archive tx-position value is too short");
    }
    requireVersion(bytes[0], "archive tx-position");
    long txNum = longAt(bytes, 1);
    long blockNum = longAt(bytes, 9);
    requireNonNegative(txNum, "archive tx-position txNum");
    requireNonNegative(blockNum, "archive tx-position block number");
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
    if (committedNextTxNum < 0) {
      throw new ArchiveException("archive cursor value must be non-negative");
    }
    return Longs.toByteArray(committedNextTxNum);
  }

  static long decodeCursor(byte[] bytes) {
    if (bytes == null || bytes.length != Long.BYTES) {
      throw new ArchiveException("archive cursor value must be 8 bytes");
    }
    long cursor = Longs.fromByteArray(bytes);
    if (cursor < 0) {
      throw new ArchiveException("archive cursor value must be non-negative");
    }
    return cursor;
  }

  static byte[] encodeFirstBlock(long blockNum) {
    if (blockNum < 0) {
      throw new ArchiveException("archive first-block value must be non-negative");
    }
    return Longs.toByteArray(blockNum);
  }

  static long decodeFirstBlock(byte[] bytes) {
    if (bytes == null || bytes.length != Long.BYTES) {
      throw new ArchiveException("archive first-block value must be 8 bytes");
    }
    long blockNum = Longs.fromByteArray(bytes);
    if (blockNum < 0) {
      throw new ArchiveException("archive first-block value must be non-negative");
    }
    return blockNum;
  }

  static byte[] encodeRepairRequired(String reason) {
    return reason.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  static void requirePositionTxId(ArchivePhase phase, byte[] txId, String what) {
    if (phase == ArchivePhase.USER_TX) {
      requireTxId(txId, what + " user txId");
      return;
    }
    if (txId != null && txId.length != 0) {
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

  private static void requireNonNegative(long value, String what) {
    if (value < 0) {
      throw new ArchiveException(what + " must be non-negative");
    }
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
