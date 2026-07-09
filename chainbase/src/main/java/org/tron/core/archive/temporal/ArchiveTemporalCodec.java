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

/**
 * On-disk byte layout for a single-column-family temporal store: a 1-byte family prefix
 * distinguishes the latest record from the txNum-versioned history. Under the Erigon-v3 prev-value
 * model the history value is the change's PRE-value, so {@code getAsOf} forward-seeks the first
 * history entry after the queried txNum within a (domain, key) prefix. Pure functions, unit-tested
 * without a native RocksDB.
 *
 * <ul>
 *   <li>latest:  {@code 0x20 || domainId(2) || keyLen(4) || canonicalKey} -&gt; value(after)</li>
 *   <li>history: {@code 0x21 || domainId(2) || keyLen(4) || canonicalKey || txNum(8, BE)}
 *       -&gt; value(before the change)</li>
 *   <li>changeset: {@code 0x22 || txNum(8) || domainId(2) || keyLen(4) || canonicalKey}
 *       -&gt; value(after), for unwind and validation</li>
 *   <li>block-commit: {@code 0x01 || "block-commit" || blockNum(8, BE)} -&gt; range
 *       marker, for startup validation</li>
 *   <li>latest-baseline: {@code 0x01 || "latest-baseline" || domainId(2) || keyLen(4)
 *       || canonicalKey} -&gt; value, for latest-only rows restored by unwind</li>
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
  private static final byte[] BLOCK_COMMIT_NAME =
      "block-commit".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] LATEST_BASELINE_NAME =
      "latest-baseline".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] MANIFEST_KEY = new byte[] {META_PREFIX, 'm', 'a', 'n', 'i'};
  private static final byte[] MANIFEST_VALUE =
      ("tron-archive-temporal|schema=6|model=prev-value-v1"
          + "|prefix=archive-table-v1|key-len=u32|block-hash=range-marker"
          + "|block-rows=sha256|changeset-value=after|latest-baseline=value")
          .getBytes(StandardCharsets.US_ASCII);
  private static final int BLOCK_COMMIT_DIGEST_LENGTH = 32;
  private static final int BLOCK_COMMIT_VALUE_LENGTH =
      36 + ArchiveBlockRange.BLOCK_HASH_LENGTH + Integer.BYTES + BLOCK_COMMIT_DIGEST_LENGTH;

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

  /** Prefix shared by all history entries of a (domain, key); a history key starts with it. */
  static byte[] historyPrefix(ArchiveDomain domain, byte[] canonicalKey) {
    return Bytes.concat(new byte[] {HISTORY_PREFIX}, domainId(domain), keyLength(canonicalKey),
        canonicalKey);
  }

  static byte[] historyKey(ArchiveDomain domain, byte[] canonicalKey, long txNum) {
    requireNonNegativeTxNum(txNum);
    return Bytes.concat(historyPrefix(domain, canonicalKey), Longs.toByteArray(txNum));
  }

  static byte[] changesetKey(long txNum, ArchiveDomain domain, byte[] canonicalKey) {
    requireNonNegativeTxNum(txNum);
    return Bytes.concat(new byte[] {CHANGESET_PREFIX}, Longs.toByteArray(txNum),
        domainId(domain), keyLength(canonicalKey), canonicalKey);
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

  static byte[] latestBaselineKey(ArchiveDomain domain, byte[] canonicalKey) {
    return Bytes.concat(new byte[] {META_PREFIX}, LATEST_BASELINE_NAME, domainId(domain),
        keyLength(canonicalKey), canonicalKey);
  }

  static byte[] latestBaselinePrefix() {
    return Bytes.concat(new byte[] {META_PREFIX}, LATEST_BASELINE_NAME);
  }

  static byte[] latestBaselineKeyOfLatest(byte[] latestKey) {
    validateLatestKey(latestKey);
    return Bytes.concat(latestBaselinePrefix(), Arrays.copyOfRange(latestKey, 1,
        latestKey.length));
  }

  static byte[] latestKeyOfBaseline(byte[] baselineKey) {
    validateLatestBaselineKey(baselineKey);
    byte[] prefix = latestBaselinePrefix();
    return Bytes.concat(new byte[] {LATEST_PREFIX}, Arrays.copyOfRange(baselineKey,
        prefix.length, baselineKey.length));
  }

  static long blockNumOfBlockCommitKey(byte[] key) {
    byte[] prefix = blockCommitPrefix();
    if (!startsWith(key, prefix) || key.length != prefix.length + Long.BYTES) {
      throw new ArchiveException("archive temporal commit marker has invalid key");
    }
    long blockNum = Longs.fromByteArray(Arrays.copyOfRange(key, prefix.length, key.length));
    if (blockNum < 0) {
      throw new ArchiveException("archive temporal commit marker block number is negative");
    }
    return blockNum;
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

  static byte[] encodeBlockCommit(ArchiveBlockRange range, int rowCount, byte[] rowDigest) {
    if (range.getBlockNum() < 0) {
      throw new ArchiveException("archive block commit requires non-negative block number");
    }
    if (rowCount < 0) {
      throw new ArchiveException("archive block commit row count is negative");
    }
    byte[] blockHash = range.getBlockHash();
    if (blockHash.length != ArchiveBlockRange.BLOCK_HASH_LENGTH) {
      throw new ArchiveException("archive block commit requires a 32-byte block hash");
    }
    requireBlockCommitDigest(rowDigest);
    return Bytes.concat(
        Longs.toByteArray(range.getBlockNum()),
        Longs.toByteArray(range.getFirstTxNum()),
        Longs.toByteArray(range.getLastTxNum()),
        Longs.toByteArray(range.getFinalizeTxNum()),
        Ints.toByteArray(blockHash.length),
        blockHash,
        Ints.toByteArray(rowCount),
        rowDigest);
  }

  static boolean blockCommitMatches(byte[] encoded, ArchiveBlockRange range, int rowCount,
      byte[] rowDigest) {
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
    if (rowCount < 0 || rowDigest == null || rowDigest.length != BLOCK_COMMIT_DIGEST_LENGTH) {
      return false;
    }
    int rowCountOffset = 36 + blockHashLen;
    int encodedRowCount = Ints.fromBytes(encoded[rowCountOffset], encoded[rowCountOffset + 1],
        encoded[rowCountOffset + 2], encoded[rowCountOffset + 3]);
    if (encodedRowCount != rowCount) {
      return false;
    }
    byte[] encodedDigest = Arrays.copyOfRange(encoded, rowCountOffset + Integer.BYTES,
        rowCountOffset + Integer.BYTES + BLOCK_COMMIT_DIGEST_LENGTH);
    return Arrays.equals(encodedDigest, rowDigest);
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
    int rowCountOffset = 36 + blockHashLen;
    int rowCount = Ints.fromBytes(encoded[rowCountOffset], encoded[rowCountOffset + 1],
        encoded[rowCountOffset + 2], encoded[rowCountOffset + 3]);
    if (rowCount < 0) {
      throw new ArchiveException("archive temporal commit marker row count is negative");
    }
  }

  static int blockCommitDigestLength() {
    return BLOCK_COMMIT_DIGEST_LENGTH;
  }

  static void requireBlockCommitDigest(byte[] rowDigest) {
    if (rowDigest == null || rowDigest.length != BLOCK_COMMIT_DIGEST_LENGTH) {
      throw new ArchiveException("archive block commit row digest must be 32 bytes");
    }
  }

  static long txNumOfChangeset(byte[] changesetKey) {
    validateChangesetKey(changesetKey);
    long txNum = Longs.fromByteArray(Arrays.copyOfRange(changesetKey, 1, 9));
    requireNonNegativeTxNum(txNum);
    return txNum;
  }

  static long txNumOfHistory(byte[] historyKey) {
    validateHistoryKey(historyKey);
    long txNum = Longs.fromByteArray(
        Arrays.copyOfRange(historyKey, historyKey.length - 8, historyKey.length));
    requireNonNegativeTxNum(txNum);
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

  private static void validateLatestBaselineKey(byte[] key) {
    byte[] prefix = latestBaselinePrefix();
    if (!startsWith(key, prefix) || key.length < prefix.length + 6) {
      throw new ArchiveException("archive temporal latest baseline key is invalid");
    }
    domainAt(key, prefix.length, "archive temporal latest baseline");
    int keyLen = intAt(key, prefix.length + 2);
    if (key.length != prefix.length + 6 + keyLen) {
      throw new ArchiveException("archive temporal latest baseline key length is invalid");
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
    byte[] bytes = value.getValue();
    return Bytes.concat(new byte[] {(byte) (value.isDeleted() ? 1 : 0)}, bytes);
  }

  static DomainValue decodeValue(byte[] encoded) {
    if (encoded == null || encoded.length == 0) {
      throw new ArchiveException("archive temporal value is empty");
    }
    if (encoded[0] == 1) {
      if (encoded.length != 1) {
        throw new ArchiveException("archive temporal tombstone value must be empty");
      }
      return DomainValue.tombstone();
    }
    if (encoded[0] != 0) {
      throw new ArchiveException("archive temporal value has invalid flag " + encoded[0]);
    }
    return DomainValue.present(Arrays.copyOfRange(encoded, 1, encoded.length));
  }
}
