package org.tron.core.archive.txnum;

import com.google.common.primitives.Bytes;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveSource;

/**
 * On-disk byte layout for the persistent txNum index. A 1-byte table prefix follows the L5
 * archive keyspace for txNum rows, while small operational metadata lives under the meta table.
 *
 * <ul>
 *   <li>range key: {@code 0x10 || blockNum(8, BE)} -&gt; encoded {@link ArchiveBlockRange}</li>
 *   <li>txId key: {@code 0x11 || txIdLen(4) || txId} -&gt; txNum</li>
 *   <li>position key: {@code 0x12 || txNum(8, BE)} -&gt; encoded {@link ArchiveTxPosition}</li>
 *   <li>meta key: {@code 0x01 || asciiName} -&gt; manifest/cursor/repair metadata</li>
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

  static final byte LEGACY_RANGE_PREFIX = 0x00;
  static final byte[] LEGACY_CURSOR_KEY = {0x01};
  // The lowest block currently committed to this index -- written for the first committed range and
  // cleared if the archive is unwound back to empty. The historical-read coverage gate uses it to
  // tell a genesis-complete archive
  // (where a MISSING dynamic-property is unambiguously the in-memory default) from a mid-chain one.
  static final byte[] LEGACY_FIRST_BLOCK_KEY = {0x02};
  static final byte LEGACY_POSITION_PREFIX = 0x03;
  static final byte LEGACY_BLOCK_INDEX_PREFIX = 0x04;
  static final byte LEGACY_TX_ID_PREFIX = 0x05;
  static final byte[] LEGACY_REPAIR_REQUIRED_KEY = {0x06};
  private static final byte[] LEGACY_MANIFEST_KEY =
      new byte[] {TXNUM_META_PREFIX, 'm', 'a', 'n', 'i'};

  static final byte[] CURSOR_KEY = metaKey("cursor");
  static final byte[] FIRST_BLOCK_KEY = metaKey("first-block");
  static final byte[] REPAIR_REQUIRED_KEY = metaKey("repair-required");
  private static final byte[] MANIFEST_KEY = metaKey("mani");
  private static final byte[] MANIFEST_VALUE =
      "tron-archive-txnum|schema=4|keys=l5-txnum-v1|values=versioned-range-position-v1"
          .getBytes(StandardCharsets.US_ASCII);
  private static final byte[] LEGACY_SCHEMA_THREE_MANIFEST_VALUE =
      "tron-archive-txnum|schema=3|keys=l5-txnum-v1|values=range-position-v2"
          .getBytes(StandardCharsets.US_ASCII);
  private static final byte[] LEGACY_SCHEMA_TWO_MANIFEST_VALUE =
      "tron-archive-txnum|schema=2|model=range-position-txid-v1|block-index=derived"
          .getBytes(StandardCharsets.US_ASCII);
  private static final byte[] LEGACY_SCHEMA_ONE_MANIFEST_VALUE =
      "tron-archive-txnum|schema=1|model=range-position-index-v1|prefix=legacy-0x00-0x06"
          .getBytes(StandardCharsets.US_ASCII);

  private ArchiveBlockRangeCodec() {
  }

  static byte[] rangeKey(long blockNum) {
    return Bytes.concat(new byte[] {TXNUM_BLOCK_PREFIX}, Longs.toByteArray(blockNum));
  }

  static byte[] positionKey(long txNum) {
    return Bytes.concat(new byte[] {TXNUM_META_PREFIX}, Longs.toByteArray(txNum));
  }

  static byte[] txIdKey(byte[] txId) {
    return Bytes.concat(new byte[] {TXNUM_BY_TXID_PREFIX}, Ints.toByteArray(txId.length), txId);
  }

  static long txNumFromPositionKey(byte[] key) {
    if (key == null || key.length != 1 + Long.BYTES
        || key[0] != TXNUM_META_PREFIX) {
      throw new ArchiveException("archive tx-position key is invalid");
    }
    return longAt(key, 1);
  }

  static byte[] txIdFromKey(byte[] key) {
    if (key == null || key.length < 1 + Integer.BYTES
        || key[0] != TXNUM_BY_TXID_PREFIX) {
      throw new ArchiveException("archive txId key is invalid");
    }
    int txIdLen = intAt(key, 1);
    if (txIdLen <= 0 || key.length != 1 + Integer.BYTES + txIdLen) {
      throw new ArchiveException("archive txId key has invalid txId length " + txIdLen);
    }
    return Arrays.copyOfRange(key, 1 + Integer.BYTES, key.length);
  }

  static byte[] legacyManifestKey() {
    return Arrays.copyOf(LEGACY_MANIFEST_KEY, LEGACY_MANIFEST_KEY.length);
  }

  static byte[] legacyRangeKey(long blockNum) {
    return Bytes.concat(new byte[] {LEGACY_RANGE_PREFIX}, Longs.toByteArray(blockNum));
  }

  static byte[] legacyPositionKey(long txNum) {
    return Bytes.concat(new byte[] {LEGACY_POSITION_PREFIX}, Longs.toByteArray(txNum));
  }

  static byte[] legacyTxIdKey(byte[] txId) {
    return Bytes.concat(new byte[] {LEGACY_TX_ID_PREFIX}, txId);
  }

  static byte[] manifestKey() {
    return Arrays.copyOf(MANIFEST_KEY, MANIFEST_KEY.length);
  }

  static byte[] manifestValue() {
    return Arrays.copyOf(MANIFEST_VALUE, MANIFEST_VALUE.length);
  }

  static boolean manifestMatches(byte[] value) {
    return Arrays.equals(MANIFEST_VALUE, value);
  }

  static boolean legacySchemaOneManifestMatches(byte[] value) {
    return Arrays.equals(LEGACY_SCHEMA_ONE_MANIFEST_VALUE, value);
  }

  static boolean legacySchemaTwoManifestMatches(byte[] value) {
    return Arrays.equals(LEGACY_SCHEMA_TWO_MANIFEST_VALUE, value);
  }

  static boolean legacySchemaThreeManifestMatches(byte[] value) {
    return Arrays.equals(LEGACY_SCHEMA_THREE_MANIFEST_VALUE, value);
  }

  private static byte[] metaKey(String name) {
    return Bytes.concat(new byte[] {META_PREFIX}, name.getBytes(StandardCharsets.US_ASCII));
  }

  static byte[] encodeRange(ArchiveBlockRange range) {
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
    byte[] txId = position.getTxId();
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
    ArchivePhase phase = phaseAt(bytes[17], "archive tx-position");
    ArchiveSource source = sourceAt(bytes[18], "archive tx-position");
    int txIndex = intAt(bytes, 19);
    int txIdLen = intAt(bytes, 23);
    if (txIdLen < 0 || txIdLen > bytes.length - 27 - Integer.BYTES) {
      throw new ArchiveException("archive tx-position has invalid txId length " + txIdLen);
    }
    int blockHashLenOffset = 27 + txIdLen;
    byte[] txId = Arrays.copyOfRange(bytes, 27, blockHashLenOffset);
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
    return Longs.toByteArray(committedNextTxNum);
  }

  static long decodeCursor(byte[] bytes) {
    if (bytes == null || bytes.length != Long.BYTES) {
      throw new ArchiveException("archive cursor value must be 8 bytes");
    }
    return Longs.fromByteArray(bytes);
  }

  static byte[] encodeFirstBlock(long blockNum) {
    return Longs.toByteArray(blockNum);
  }

  static long decodeFirstBlock(byte[] bytes) {
    if (bytes == null || bytes.length != Long.BYTES) {
      throw new ArchiveException("archive first-block value must be 8 bytes");
    }
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
