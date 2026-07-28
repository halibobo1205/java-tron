package org.tron.core.archive.temporal;

import com.google.common.primitives.Bytes;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.archive.txnum.ArchiveCoordinates;

/**
 * Logical key/value layout for the UNIFIED_V1 temporal column families. A 1-byte logical prefix is
 * retained in each key for strict validation and deterministic ordering. Under the Erigon-v3
 * prev-value model the history value is the change's PRE-value, so {@code getAsOf} forward-seeks
 * the first history entry after the queried txNum within a (domain, key) prefix. Pure functions,
 * unit-tested without a native RocksDB.
 *
 * <ul>
 *   <li>latest:  {@code 0x20 || domainId(2) || keyLen(4) || canonicalKey} -&gt;
 *       authenticated reference to the last changeset</li>
 *   <li>history: {@code 0x21 || domainId(2) || keyLen(4) || canonicalKey || txNum(8, BE)}
 *       -&gt; authenticated reference to the preceding changeset or anchor</li>
 *   <li>changeset: {@code 0x22 || txNum(8) || domainId(2) || keyLen(4) || canonicalKey}
 *       -&gt; value(after), for unwind and validation</li>
 *   <li>anchor: {@code 0x23 || domainId(2) || keyLen(4) || canonicalKey}
 *       -&gt; immutable first observed pre-value, in the commitment column family</li>
 *   <li>block-commit: {@code 0x01 || "block-commit" || blockNum(8, BE)} -&gt; range
 *       marker, for startup validation</li>
 *   <li>value:   {@code deletedFlag(1) || valueBytes} (flag 1 = tombstone)</li>
 * </ul>
 * txNum is big-endian so lexicographic key order matches numeric txNum order (forward seek works).
 */
public final class ArchiveTemporalCodec {

  static final byte META_PREFIX = 0x01;
  static final byte LATEST_PREFIX = 0x20;
  static final byte HISTORY_PREFIX = 0x21;
  // changeset: 0x22 || txNum(8) || domainId(2) || keyLen(4) || canonicalKey.
  static final byte CHANGESET_PREFIX = 0x22;
  static final byte ANCHOR_PREFIX = 0x23;
  private static final byte[] BLOCK_COMMIT_NAME =
      "block-commit".getBytes(StandardCharsets.US_ASCII);
  private static final int BLOCK_COMMIT_DIGEST_LENGTH = 32;
  private static final int BLOCK_COMMIT_VALUE_LENGTH =
      36 + ArchiveBlockRange.BLOCK_HASH_LENGTH + ArchiveBlockRange.SCHEMA_CHECKSUM_LENGTH
          + Integer.BYTES + BLOCK_COMMIT_DIGEST_LENGTH;

  private ArchiveTemporalCodec() {
  }

  static byte[] domainId(ArchiveDomain domain) {
    int id = domain.getId();
    return new byte[] {(byte) (id >>> 8), (byte) id};
  }

  // 4-byte big-endian length of the canonical key, inserted before the key so that no key can be a
  // byte prefix of another (the spec's length-prefix requirement); otherwise seekForPrev/startsWith
  // could cross key boundaries for variable-length keys (e.g. DYNAMIC_PROPERTIES property names).
  private static byte[] keyLength(byte[] canonicalKey) {
    return Ints.toByteArray(canonicalKey.length);
  }

  static byte[] latestKey(ArchiveDomain domain, byte[] canonicalKey) {
    return Bytes.concat(new byte[] {LATEST_PREFIX}, domainId(domain), keyLength(canonicalKey),
        canonicalKey);
  }

  static byte[] latestKeyPrefix(ArchiveDomain domain, int canonicalKeyLength,
      byte[] canonicalPrefix) {
    if (domain == null) {
      throw new NullPointerException("domain");
    }
    if (canonicalPrefix == null) {
      throw new NullPointerException("canonicalPrefix");
    }
    if (canonicalKeyLength < canonicalPrefix.length) {
      throw new IllegalArgumentException(
          "canonical key length must cover the canonical prefix");
    }
    return Bytes.concat(new byte[] {LATEST_PREFIX}, domainId(domain),
        Ints.toByteArray(canonicalKeyLength), canonicalPrefix);
  }

  /** Prefix shared by all history entries of a (domain, key); a history key starts with it. */
  static byte[] historyPrefix(ArchiveDomain domain, byte[] canonicalKey) {
    return Bytes.concat(new byte[] {HISTORY_PREFIX}, domainId(domain), keyLength(canonicalKey),
        canonicalKey);
  }

  static byte[] historyKey(ArchiveDomain domain, byte[] canonicalKey, long txNum) {
    requireNonNegativeTxNum(txNum);
    return Bytes.concat(historyPrefix(domain, canonicalKey), Longs.toByteArray(txNum));
  }

  static byte[] anchorKeyOfHistoryPrefix(byte[] historyPrefix) {
    if (historyPrefix == null || historyPrefix.length < 7
        || historyPrefix[0] != HISTORY_PREFIX) {
      throw new ArchiveException("archive temporal history prefix is invalid");
    }
    byte[] anchorKey = Arrays.copyOf(historyPrefix, historyPrefix.length);
    anchorKey[0] = ANCHOR_PREFIX;
    validateAnchorKey(anchorKey);
    return anchorKey;
  }

  static byte[] changesetKey(long txNum, ArchiveDomain domain, byte[] canonicalKey) {
    ArchiveCoordinates.requireTxNum(txNum, "archive temporal changeset txNum");
    return Bytes.concat(new byte[] {CHANGESET_PREFIX}, Longs.toByteArray(txNum),
        domainId(domain), keyLength(canonicalKey), canonicalKey);
  }

  static byte[] anchorKey(ArchiveDomain domain, byte[] canonicalKey) {
    return Bytes.concat(new byte[] {ANCHOR_PREFIX}, domainId(domain), keyLength(canonicalKey),
        canonicalKey);
  }

  /** Seek target for unwind: the first changeset entry at txNum == fromTxNum. */
  static byte[] changesetSeekFrom(long fromTxNum) {
    requireNonNegativeTxNum(fromTxNum);
    return Bytes.concat(new byte[] {CHANGESET_PREFIX}, Longs.toByteArray(fromTxNum));
  }

  static byte[] blockCommitKey(long blockNum) {
    if (blockNum < 0) {
      throw new ArchiveException("archive temporal commit marker block number is negative");
    }
    return Bytes.concat(new byte[] {META_PREFIX}, BLOCK_COMMIT_NAME, Longs.toByteArray(blockNum));
  }

  static byte[] blockCommitPrefix() {
    return Bytes.concat(new byte[] {META_PREFIX}, BLOCK_COMMIT_NAME);
  }

  static long blockNumOfBlockCommitKey(byte[] key) {
    byte[] prefix = blockCommitPrefix();
    if (!startsWith(key, prefix) || key.length != prefix.length + Long.BYTES) {
      throw new ArchiveException("archive temporal commit marker has invalid key");
    }
    long blockNum = Longs.fromByteArray(Arrays.copyOfRange(key, prefix.length, key.length));
    ArchiveCoordinates.requireBlockNum(
        blockNum, "archive temporal commit marker block number");
    return blockNum;
  }

  static byte[] encodeBlockCommit(ArchiveBlockRange range, int rowCount, byte[] rowDigest) {
    ArchiveCoordinates.requireBlockNum(
        range.getBlockNum(), "archive block commit block number");
    ArchiveCoordinates.requireTxNum(
        range.getFirstTxNum(), "archive block commit first txNum");
    ArchiveCoordinates.requireTxNum(
        range.getLastTxNum(), "archive block commit last txNum");
    ArchiveCoordinates.requireTxNum(
        range.getFinalizeTxNum(), "archive block commit finalize txNum");
    if (rowCount < 0) {
      throw new ArchiveException("archive block commit row count is negative");
    }
    byte[] blockHash = range.getBlockHash();
    if (blockHash.length != ArchiveBlockRange.BLOCK_HASH_LENGTH) {
      throw new ArchiveException("archive block commit requires a 32-byte block hash");
    }
    requireBlockCommitDigest(rowDigest);
    byte[] schemaChecksum = markerSchemaChecksum(range);
    return Bytes.concat(
        Longs.toByteArray(range.getBlockNum()),
        Longs.toByteArray(range.getFirstTxNum()),
        Longs.toByteArray(range.getLastTxNum()),
        Longs.toByteArray(range.getFinalizeTxNum()),
        Ints.toByteArray(blockHash.length),
        blockHash,
        schemaChecksum,
        Ints.toByteArray(rowCount),
        rowDigest);
  }

  static boolean blockCommitMatches(byte[] encoded, ArchiveBlockRange range, int rowCount,
      byte[] rowDigest) {
    if (!blockCommitRangeMatches(encoded, range)) {
      return false;
    }
    if (rowCount < 0 || rowDigest == null || rowDigest.length != BLOCK_COMMIT_DIGEST_LENGTH) {
      return false;
    }
    int rowCountOffset = 36 + ArchiveBlockRange.BLOCK_HASH_LENGTH
        + ArchiveBlockRange.SCHEMA_CHECKSUM_LENGTH;
    int encodedRowCount = Ints.fromBytes(encoded[rowCountOffset], encoded[rowCountOffset + 1],
        encoded[rowCountOffset + 2], encoded[rowCountOffset + 3]);
    if (encodedRowCount != rowCount) {
      return false;
    }
    byte[] encodedDigest = Arrays.copyOfRange(encoded, rowCountOffset + Integer.BYTES,
        rowCountOffset + Integer.BYTES + BLOCK_COMMIT_DIGEST_LENGTH);
    return Arrays.equals(encodedDigest, rowDigest);
  }

  static boolean blockCommitRangeMatches(byte[] encoded, ArchiveBlockRange range) {
    if (encoded == null || encoded.length != BLOCK_COMMIT_VALUE_LENGTH) {
      return false;
    }
    boolean coreMatches =
        Longs.fromByteArray(Arrays.copyOfRange(encoded, 0, 8)) == range.getBlockNum()
        && Longs.fromByteArray(Arrays.copyOfRange(encoded, 8, 16)) == range.getFirstTxNum()
        && Longs.fromByteArray(Arrays.copyOfRange(encoded, 16, 24)) == range.getLastTxNum()
        && Longs.fromByteArray(Arrays.copyOfRange(encoded, 24, 32)) == range.getFinalizeTxNum();
    if (!coreMatches) {
      return false;
    }
    int blockHashLen = Ints.fromBytes(encoded[32], encoded[33], encoded[34], encoded[35]);
    if (blockHashLen != ArchiveBlockRange.BLOCK_HASH_LENGTH) {
      return false;
    }
    byte[] blockHash = range.getBlockHash();
    if (blockHash.length != ArchiveBlockRange.BLOCK_HASH_LENGTH) {
      return false;
    }
    if (!Arrays.equals(Arrays.copyOfRange(encoded, 36, 36 + blockHashLen), blockHash)) {
      return false;
    }
    int schemaOffset = 36 + blockHashLen;
    byte[] schemaChecksum = markerSchemaChecksum(range);
    return Arrays.equals(Arrays.copyOfRange(encoded, schemaOffset,
        schemaOffset + ArchiveBlockRange.SCHEMA_CHECKSUM_LENGTH), schemaChecksum);
  }

  static void validateBlockCommitValue(byte[] encoded, long expectedBlockNum) {
    if (encoded == null || encoded.length != BLOCK_COMMIT_VALUE_LENGTH) {
      throw new ArchiveException("archive temporal commit marker value is invalid");
    }
    long blockNum = Longs.fromByteArray(Arrays.copyOfRange(encoded, 0, 8));
    if (blockNum != expectedBlockNum) {
      throw new ArchiveException("archive temporal commit marker key/value mismatch for block "
          + expectedBlockNum);
    }
    int blockHashLen = Ints.fromBytes(encoded[32], encoded[33], encoded[34], encoded[35]);
    if (blockHashLen != ArchiveBlockRange.BLOCK_HASH_LENGTH) {
      throw new ArchiveException("archive temporal commit marker hash length is invalid");
    }
    int rowCountOffset = 36 + blockHashLen + ArchiveBlockRange.SCHEMA_CHECKSUM_LENGTH;
    int rowCount = Ints.fromBytes(encoded[rowCountOffset], encoded[rowCountOffset + 1],
        encoded[rowCountOffset + 2], encoded[rowCountOffset + 3]);
    if (rowCount < 0) {
      throw new ArchiveException("archive temporal commit marker row count is negative");
    }
  }

  static int blockCommitDigestLength() {
    return BLOCK_COMMIT_DIGEST_LENGTH;
  }

  static int blockCommitValueLength() {
    return BLOCK_COMMIT_VALUE_LENGTH;
  }

  private static byte[] markerSchemaChecksum(ArchiveBlockRange range) {
    byte[] schemaChecksum = range.getSchemaChecksum();
    if (schemaChecksum.length == 0) {
      // Temporal-store unit callers may use a schema-less range. Production journals and the
      // persistent txNum index always carry the real 32-byte checksum.
      return new byte[ArchiveBlockRange.SCHEMA_CHECKSUM_LENGTH];
    }
    if (schemaChecksum.length != ArchiveBlockRange.SCHEMA_CHECKSUM_LENGTH) {
      throw new ArchiveException("archive block commit requires a 32-byte schema checksum");
    }
    return schemaChecksum;
  }

  static void requireBlockCommitDigest(byte[] rowDigest) {
    if (rowDigest == null || rowDigest.length != BLOCK_COMMIT_DIGEST_LENGTH) {
      throw new ArchiveException("archive block commit row digest must be 32 bytes");
    }
  }

  static long txNumOfChangeset(byte[] changesetKey) {
    validateChangesetKey(changesetKey);
    long txNum = Longs.fromByteArray(Arrays.copyOfRange(changesetKey, 1, 9));
    ArchiveCoordinates.requireTxNum(txNum, "archive temporal changeset txNum");
    return txNum;
  }

  static long txNumOfHistory(byte[] historyKey) {
    validateHistoryKey(historyKey);
    long txNum = Longs.fromByteArray(
        Arrays.copyOfRange(historyKey, historyKey.length - 8, historyKey.length));
    ArchiveCoordinates.requireTxNum(txNum, "archive temporal history txNum");
    return txNum;
  }

  // The (domainId || canonicalKey) bytes shared by latest/history keys, recovered from a changeset.
  private static byte[] domainAndKeyOfChangeset(byte[] changesetKey) {
    validateChangesetKey(changesetKey);
    return Arrays.copyOfRange(changesetKey, 9, changesetKey.length);
  }

  private static byte[] domainAndKeyOfHistory(byte[] historyKey) {
    validateHistoryKey(historyKey);
    return Arrays.copyOfRange(historyKey, 1, historyKey.length - 8);
  }

  static ArchiveDomain domainOfLatestKey(byte[] latestKey) {
    validateLatestKey(latestKey);
    return domainAt(latestKey, 1, "archive temporal latest");
  }

  static ArchiveDomain domainOfHistoryKey(byte[] historyKey) {
    validateHistoryKey(historyKey);
    return domainAt(historyKey, 1, "archive temporal history");
  }

  static ArchiveDomain domainOfChangesetKey(byte[] changesetKey) {
    validateChangesetKey(changesetKey);
    return domainAt(changesetKey, 9, "archive temporal changeset");
  }

  static byte[] canonicalKeyOfLatestKey(byte[] latestKey) {
    validateLatestKey(latestKey);
    int keyLen = intAt(latestKey, 3);
    return Arrays.copyOfRange(latestKey, 7, 7 + keyLen);
  }

  static byte[] canonicalKeyOfHistoryKey(byte[] historyKey) {
    validateHistoryKey(historyKey);
    int keyLen = intAt(historyKey, 3);
    return Arrays.copyOfRange(historyKey, 7, 7 + keyLen);
  }

  static byte[] canonicalKeyOfChangesetKey(byte[] changesetKey) {
    validateChangesetKey(changesetKey);
    int keyLen = intAt(changesetKey, 11);
    return Arrays.copyOfRange(changesetKey, 15, 15 + keyLen);
  }

  static byte[] historyKeyOfChangeset(byte[] changesetKey) {
    return Bytes.concat(new byte[] {HISTORY_PREFIX}, domainAndKeyOfChangeset(changesetKey),
        Arrays.copyOfRange(changesetKey, 1, 9));
  }

  static byte[] changesetKeyOfHistory(byte[] historyKey) {
    return Bytes.concat(new byte[] {CHANGESET_PREFIX},
        Arrays.copyOfRange(historyKey, historyKey.length - 8, historyKey.length),
        domainAndKeyOfHistory(historyKey));
  }

  static byte[] latestKeyOfChangeset(byte[] changesetKey) {
    return Bytes.concat(new byte[] {LATEST_PREFIX}, domainAndKeyOfChangeset(changesetKey));
  }

  static byte[] latestKeyOfHistory(byte[] historyKey) {
    return Bytes.concat(new byte[] {LATEST_PREFIX}, domainAndKeyOfHistory(historyKey));
  }

  static byte[] anchorKeyOfHistory(byte[] historyKey) {
    return Bytes.concat(new byte[] {ANCHOR_PREFIX}, domainAndKeyOfHistory(historyKey));
  }

  static byte[] anchorKeyOfLatest(byte[] latestKey) {
    validateLatestKey(latestKey);
    byte[] anchorKey = Arrays.copyOf(latestKey, latestKey.length);
    anchorKey[0] = ANCHOR_PREFIX;
    return anchorKey;
  }

  static byte[] latestKeyOfAnchor(byte[] anchorKey) {
    validateAnchorKey(anchorKey);
    byte[] latestKey = Arrays.copyOf(anchorKey, anchorKey.length);
    latestKey[0] = LATEST_PREFIX;
    return latestKey;
  }

  static byte[] historyPrefixOfChangeset(byte[] changesetKey) {
    return Bytes.concat(new byte[] {HISTORY_PREFIX}, domainAndKeyOfChangeset(changesetKey));
  }

  static byte[] historyPrefixOfLatest(byte[] latestKey) {
    validateLatestKey(latestKey);
    byte[] historyPrefix = Arrays.copyOf(latestKey, latestKey.length);
    historyPrefix[0] = HISTORY_PREFIX;
    return historyPrefix;
  }

  static boolean startsWith(byte[] array, byte[] prefix) {
    if (array == null || array.length < prefix.length) {
      return false;
    }
    for (int i = 0; i < prefix.length; i++) {
      if (array[i] != prefix[i]) {
        return false;
      }
    }
    return true;
  }

  private static void validateChangesetKey(byte[] key) {
    if (key == null || key.length < 15 || key[0] != CHANGESET_PREFIX) {
      throw new ArchiveException("archive temporal changeset key is invalid");
    }
    domainAt(key, 9, "archive temporal changeset");
    int keyLen = intAt(key, 11);
    if (key.length != 15 + keyLen) {
      throw new ArchiveException("archive temporal changeset key length is invalid");
    }
  }

  private static void validateHistoryKey(byte[] key) {
    if (key == null || key.length < 15 || key[0] != HISTORY_PREFIX) {
      throw new ArchiveException("archive temporal history key is invalid");
    }
    domainAt(key, 1, "archive temporal history");
    int keyLen = intAt(key, 3);
    if (key.length != 15 + keyLen) {
      throw new ArchiveException("archive temporal history key length is invalid");
    }
  }

  private static void validateLatestKey(byte[] key) {
    if (key == null || key.length < 7 || key[0] != LATEST_PREFIX) {
      throw new ArchiveException("archive temporal latest key is invalid");
    }
    domainAt(key, 1, "archive temporal latest");
    int keyLen = intAt(key, 3);
    if (key.length != 7 + keyLen) {
      throw new ArchiveException("archive temporal latest key length is invalid");
    }
  }

  private static void validateAnchorKey(byte[] key) {
    if (key == null || key.length < 7 || key[0] != ANCHOR_PREFIX) {
      throw new ArchiveException("archive temporal anchor key is invalid");
    }
    domainAt(key, 1, "archive temporal anchor");
    int keyLen = intAt(key, 3);
    if (key.length != 7 + keyLen) {
      throw new ArchiveException("archive temporal anchor key length is invalid");
    }
  }

  private static ArchiveDomain domainAt(byte[] key, int offset, String what) {
    int id = ((key[offset] & 0xff) << 8) | (key[offset + 1] & 0xff);
    for (ArchiveDomain domain : ArchiveDomain.values()) {
      if (domain.getId() == id) {
        return domain;
      }
    }
    throw new ArchiveException(what + " domain id is invalid: " + id);
  }

  private static int intAt(byte[] bytes, int offset) {
    int value = Ints.fromBytes(bytes[offset], bytes[offset + 1], bytes[offset + 2],
        bytes[offset + 3]);
    if (value < 0) {
      throw new ArchiveException("archive temporal key length is negative");
    }
    return value;
  }

  private static void requireNonNegativeTxNum(long txNum) {
    if (txNum < 0) {
      throw new ArchiveException("archive temporal txNum must be non-negative");
    }
  }

  static byte[] encodeValue(DomainValue value) {
    return encodeValue(value, ArchiveTemporalIntegrityCodec.MAX_PAYLOAD_BYTES);
  }

  static byte[] encodeValue(DomainValue value, int maxPayloadBytes) {
    if (value == null) {
      throw new NullPointerException("value");
    }
    if (maxPayloadBytes <= 0) {
      throw new IllegalArgumentException("maximum temporal payload bytes must be positive");
    }
    long encodedBytes = (long) value.size() + 1L;
    if (encodedBytes > maxPayloadBytes) {
      throw new ArchiveException("archive temporal payload exceeds format limit "
          + maxPayloadBytes + ": payloadBytes=" + encodedBytes);
    }
    byte[] encoded = new byte[(int) encodedBytes];
    encoded[0] = (byte) (value.isDeleted() ? 1 : 0);
    value.copyValueTo(encoded, 1);
    return encoded;
  }

  static DomainValue decodeValue(byte[] encoded) {
    return decodeValue(encoded, 0, encoded == null ? 0 : encoded.length);
  }

  static void validateValueEncoding(byte[] encoded) {
    validateValueEncoding(encoded, 0, encoded == null ? 0 : encoded.length);
  }

  static DomainValue decodeValue(byte[] encoded, int offset, int length) {
    validateValueEncoding(encoded, offset, length);
    if (encoded[offset] == 1) {
      return DomainValue.tombstone();
    }
    return DomainValue.present(encoded, offset + 1, length - 1);
  }

  private static void validateValueEncoding(byte[] encoded, int offset, int length) {
    if (encoded == null || offset < 0 || length <= 0
        || offset > encoded.length - length) {
      throw new ArchiveException("archive temporal value is empty");
    }
    if (encoded[offset] == 1) {
      if (length != 1) {
        throw new ArchiveException("archive temporal tombstone value must be empty");
      }
      return;
    }
    if (encoded[offset] != 0) {
      throw new ArchiveException("archive temporal value has invalid flag " + encoded[offset]);
    }
  }
}
