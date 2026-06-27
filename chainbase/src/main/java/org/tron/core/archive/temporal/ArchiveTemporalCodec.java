package org.tron.core.archive.temporal;

import com.google.common.primitives.Bytes;
import com.google.common.primitives.Longs;
import java.util.Arrays;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;

/**
 * On-disk byte layout for a single-column-family temporal store: a 1-byte family prefix
 * distinguishes the latest record from the txNum-versioned history, so {@code getAsOf} is a
 * {@code seekForPrev} within a (domain, key) history prefix. Pure functions, unit-tested without a
 * native RocksDB.
 *
 * <ul>
 *   <li>latest:  {@code 0x00 || domainId(2) || canonicalKey}                       -&gt; value</li>
 *   <li>history: {@code 0x01 || domainId(2) || canonicalKey || txNum(8, BE)} -&gt; value</li>
 *   <li>value:   {@code deletedFlag(1) || valueBytes} (flag 1 = tombstone)</li>
 * </ul>
 * txNum is big-endian so lexicographic key order matches numeric txNum order (seekForPrev works).
 */
public final class ArchiveTemporalCodec {

  static final byte LATEST_PREFIX = 0x00;
  static final byte HISTORY_PREFIX = 0x01;
  // changeset: 0x02 || txNum(8) || domainId(2) || canonicalKey -> ordered by txNum, for unwind.
  static final byte CHANGESET_PREFIX = 0x02;

  private ArchiveTemporalCodec() {
  }

  static byte[] domainId(ArchiveDomain domain) {
    int id = domain.getId();
    return new byte[] {(byte) (id >>> 8), (byte) id};
  }

  static byte[] latestKey(ArchiveDomain domain, byte[] canonicalKey) {
    return Bytes.concat(new byte[] {LATEST_PREFIX}, domainId(domain), canonicalKey);
  }

  /** Prefix shared by all history entries of a (domain, key); a history key starts with it. */
  static byte[] historyPrefix(ArchiveDomain domain, byte[] canonicalKey) {
    return Bytes.concat(new byte[] {HISTORY_PREFIX}, domainId(domain), canonicalKey);
  }

  static byte[] historyKey(ArchiveDomain domain, byte[] canonicalKey, long txNum) {
    return Bytes.concat(historyPrefix(domain, canonicalKey), Longs.toByteArray(txNum));
  }

  static byte[] changesetKey(long txNum, ArchiveDomain domain, byte[] canonicalKey) {
    return Bytes.concat(new byte[] {CHANGESET_PREFIX}, Longs.toByteArray(txNum),
        domainId(domain), canonicalKey);
  }

  /** Seek target for unwind: the first changeset entry at txNum == fromTxNum. */
  static byte[] changesetSeekFrom(long fromTxNum) {
    return Bytes.concat(new byte[] {CHANGESET_PREFIX}, Longs.toByteArray(fromTxNum));
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
