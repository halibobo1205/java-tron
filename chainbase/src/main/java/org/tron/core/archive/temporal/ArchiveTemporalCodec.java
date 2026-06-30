package org.tron.core.archive.temporal;

import com.google.common.primitives.Bytes;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import java.util.Arrays;
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
 *   <li>latest:  {@code 0x00 || domainId(2) || keyLen(2) || canonicalKey} -&gt; value(after)</li>
 *   <li>history: {@code 0x01 || domainId(2) || keyLen(2) || canonicalKey || txNum(8, BE)}
 *       -&gt; value(before the change)</li>
 *   <li>changeset: {@code 0x02 || txNum(8) || domainId(2) || keyLen(2) || canonicalKey}, for
 *       unwind</li>
 *   <li>block-commit: {@code 0x03 || blockNum(8, BE)} -&gt; range marker, for startup
 *       validation</li>
 *   <li>value:   {@code deletedFlag(1) || valueBytes} (flag 1 = tombstone)</li>
 * </ul>
 * txNum is big-endian so lexicographic key order matches numeric txNum order (forward seek works).
 */
public final class ArchiveTemporalCodec {

  static final byte LATEST_PREFIX = 0x00;
  static final byte HISTORY_PREFIX = 0x01;
  // changeset: 0x02 || txNum(8) || domainId(2) || canonicalKey -> ordered by txNum, for unwind.
  static final byte CHANGESET_PREFIX = 0x02;
  static final byte BLOCK_COMMIT_PREFIX = 0x03;

  private ArchiveTemporalCodec() {
  }

  static byte[] domainId(ArchiveDomain domain) {
    int id = domain.getId();
    return new byte[] {(byte) (id >>> 8), (byte) id};
  }

  // 2-byte big-endian length of the canonical key, inserted before the key so that no key can be a
  // byte prefix of another (the spec's length-prefix requirement); otherwise seekForPrev/startsWith
  // could cross key boundaries for variable-length keys (e.g. DYNAMIC_PROPERTIES property names).
  private static byte[] keyLength(byte[] canonicalKey) {
    int len = canonicalKey.length;
    return new byte[] {(byte) (len >>> 8), (byte) len};
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
    return Bytes.concat(new byte[] {BLOCK_COMMIT_PREFIX}, Longs.toByteArray(blockNum));
  }

  static byte[] encodeBlockCommit(ArchiveBlockRange range) {
    byte[] blockHash = range.getBlockHash();
    return Bytes.concat(
        Longs.toByteArray(range.getBlockNum()),
        Longs.toByteArray(range.getFirstTxNum()),
        Longs.toByteArray(range.getLastTxNum()),
        Longs.toByteArray(range.getFinalizeTxNum()),
        Ints.toByteArray(blockHash.length),
        blockHash);
  }

  static boolean blockCommitMatches(byte[] encoded, ArchiveBlockRange range) {
    if (encoded == null || encoded.length < 32) {
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
    byte[] blockHash = range.getBlockHash();
    if (encoded.length == 32) {
      return blockHash.length == 0;
    }
    if (encoded.length < 36) {
      return false;
    }
    int blockHashLen = Ints.fromBytes(encoded[32], encoded[33], encoded[34], encoded[35]);
    if (blockHashLen < 0 || encoded.length != 36 + blockHashLen) {
      return false;
    }
    return Arrays.equals(Arrays.copyOfRange(encoded, 36, 36 + blockHashLen), blockHash);
  }

  static long txNumOfChangeset(byte[] changesetKey) {
    return Longs.fromByteArray(Arrays.copyOfRange(changesetKey, 1, 9));
  }

  // The (domainId || canonicalKey) bytes shared by latest/history keys, recovered from a changeset.
  private static byte[] domainAndKeyOfChangeset(byte[] changesetKey) {
    return Arrays.copyOfRange(changesetKey, 9, changesetKey.length);
  }

  static byte[] historyKeyOfChangeset(byte[] changesetKey) {
    return Bytes.concat(new byte[] {HISTORY_PREFIX}, domainAndKeyOfChangeset(changesetKey),
        Arrays.copyOfRange(changesetKey, 1, 9));
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

  static byte[] encodeValue(DomainValue value) {
    byte[] bytes = value.getValue();
    return Bytes.concat(new byte[] {(byte) (value.isDeleted() ? 1 : 0)}, bytes);
  }

  static DomainValue decodeValue(byte[] encoded) {
    boolean deleted = encoded[0] == 1;
    if (deleted) {
      return DomainValue.tombstone();
    }
    return DomainValue.present(Arrays.copyOfRange(encoded, 1, encoded.length));
  }
}
