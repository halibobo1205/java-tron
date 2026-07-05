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
 *   <li>changeset: {@code 0x22 || txNum(8) || domainId(2) || keyLen(4) || canonicalKey}, for
 *       unwind</li>
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
  private static final byte[] BLOCK_COMMIT_NAME =
      "block-commit".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] MANIFEST_KEY = new byte[] {META_PREFIX, 'm', 'a', 'n', 'i'};
  private static final byte[] MANIFEST_VALUE =
      ("tron-archive-temporal|schema=3|model=prev-value-v1"
          + "|prefix=archive-table-v1|key-len=u32|block-hash=range-marker")
          .getBytes(StandardCharsets.US_ASCII);

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
    return Bytes.concat(historyPrefix(domain, canonicalKey), Longs.toByteArray(txNum));
  }

  static byte[] changesetKey(long txNum, ArchiveDomain domain, byte[] canonicalKey) {
    return Bytes.concat(new byte[] {CHANGESET_PREFIX}, Longs.toByteArray(txNum),
        domainId(domain), keyLength(canonicalKey), canonicalKey);
  }

  /** Seek target for unwind: the first changeset entry at txNum == fromTxNum. */
  static byte[] changesetSeekFrom(long fromTxNum) {
    return Bytes.concat(new byte[] {CHANGESET_PREFIX}, Longs.toByteArray(fromTxNum));
  }

  static byte[] blockCommitKey(long blockNum) {
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
    return Longs.fromByteArray(Arrays.copyOfRange(key, prefix.length, key.length));
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

  static byte[] encodeBlockCommit(ArchiveBlockRange range) {
    byte[] blockHash = range.getBlockHash();
    if (blockHash.length != ArchiveBlockRange.BLOCK_HASH_LENGTH) {
      throw new ArchiveException("archive block commit requires a 32-byte block hash");
    }
    return Bytes.concat(
        Longs.toByteArray(range.getBlockNum()),
        Longs.toByteArray(range.getFirstTxNum()),
        Longs.toByteArray(range.getLastTxNum()),
        Longs.toByteArray(range.getFinalizeTxNum()),
        Ints.toByteArray(blockHash.length),
        blockHash);
  }

  static boolean blockCommitMatches(byte[] encoded, ArchiveBlockRange range) {
    if (encoded == null || encoded.length != 36 + ArchiveBlockRange.BLOCK_HASH_LENGTH) {
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
    return Arrays.equals(Arrays.copyOfRange(encoded, 36, 36 + blockHashLen), blockHash);
  }

  static long txNumOfChangeset(byte[] changesetKey) {
    validateChangesetKey(changesetKey);
    return Longs.fromByteArray(Arrays.copyOfRange(changesetKey, 1, 9));
  }

  static long txNumOfHistory(byte[] historyKey) {
    validateHistoryKey(historyKey);
    return Longs.fromByteArray(
        Arrays.copyOfRange(historyKey, historyKey.length - 8, historyKey.length));
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

  static byte[] historyPrefixOfChangeset(byte[] changesetKey) {
    return Bytes.concat(new byte[] {HISTORY_PREFIX}, domainAndKeyOfChangeset(changesetKey));
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
    int keyLen = intAt(key, 11);
    if (key.length != 15 + keyLen) {
      throw new ArchiveException("archive temporal changeset key length is invalid");
    }
  }

  private static void validateHistoryKey(byte[] key) {
    if (key == null || key.length < 15 || key[0] != HISTORY_PREFIX) {
      throw new ArchiveException("archive temporal history key is invalid");
    }
    int keyLen = intAt(key, 3);
    if (key.length != 15 + keyLen) {
      throw new ArchiveException("archive temporal history key length is invalid");
    }
  }

  private static int intAt(byte[] bytes, int offset) {
    int value = Ints.fromBytes(bytes[offset], bytes[offset + 1], bytes[offset + 2],
        bytes[offset + 3]);
    if (value < 0) {
      throw new ArchiveException("archive temporal key length is negative");
    }
    return value;
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
      return DomainValue.tombstone();
    }
    if (encoded[0] != 0) {
      throw new ArchiveException("archive temporal value has invalid flag " + encoded[0]);
    }
    return DomainValue.present(Arrays.copyOfRange(encoded, 1, encoded.length));
  }
}
