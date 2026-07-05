package org.tron.core.archive.temporal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;

public class ArchiveTemporalCodecTest {

  @Test
  public void domainIdIsTwoByteBigEndian() {
    assertArrayEquals(new byte[] {0x00, 0x01},
        ArchiveTemporalCodec.domainId(ArchiveDomain.ACCOUNT));
    assertArrayEquals(new byte[] {0x00, 0x20},
        ArchiveTemporalCodec.domainId(ArchiveDomain.CONTRACT_STATE));
  }

  @Test
  public void latestAndHistoryKeysHaveDistinctPrefixesAndStructure() {
    byte[] key = {1, 2, 3};
    byte[] latest = ArchiveTemporalCodec.latestKey(ArchiveDomain.ACCOUNT, key);
    byte[] history = ArchiveTemporalCodec.historyKey(ArchiveDomain.ACCOUNT, key, 7L);
    assertEquals(0x20, latest[0]);
    assertEquals(0x21, history[0]);
    assertEquals(10, latest.length);  // prefix(1) + domainId(2) + keyLen(4) + key(3)
    assertEquals(18, history.length); // + txNum(8)
    assertArrayEquals(new byte[] {0, 1}, Arrays.copyOfRange(latest, 1, 3));
    assertArrayEquals(new byte[] {0, 0, 0, 3}, Arrays.copyOfRange(latest, 3, 7));
    assertArrayEquals(key, Arrays.copyOfRange(latest, 7, 10));
    assertTrue(ArchiveTemporalCodec.startsWith(history,
        ArchiveTemporalCodec.historyPrefix(ArchiveDomain.ACCOUNT, key)));
  }

  @Test
  public void changesetKeyUsesL5PrefixAndU32Length() {
    byte[] key = {1, 2, 3};
    byte[] changeset = ArchiveTemporalCodec.changesetKey(7L, ArchiveDomain.ACCOUNT, key);

    assertEquals(0x22, changeset[0]);
    assertEquals(18, changeset.length); // prefix(1) + txNum(8) + domainId(2) + keyLen(4) + key(3)
    assertArrayEquals(new byte[] {0, 1}, Arrays.copyOfRange(changeset, 9, 11));
    assertArrayEquals(new byte[] {0, 0, 0, 3}, Arrays.copyOfRange(changeset, 11, 15));
    assertArrayEquals(key, Arrays.copyOfRange(changeset, 15, 18));
    assertEquals(7L, ArchiveTemporalCodec.txNumOfChangeset(changeset));
    assertArrayEquals(ArchiveTemporalCodec.historyKey(ArchiveDomain.ACCOUNT, key, 7L),
        ArchiveTemporalCodec.historyKeyOfChangeset(changeset));
  }

  @Test
  public void derivedHistoryLatestAndChangesetKeysRoundTrip() {
    byte[] key = {1, 2, 3, 4};
    byte[] history = ArchiveTemporalCodec.historyKey(ArchiveDomain.CONTRACT_STATE, key, 99L);
    byte[] changeset = ArchiveTemporalCodec.changesetKey(99L, ArchiveDomain.CONTRACT_STATE, key);

    assertEquals(99L, ArchiveTemporalCodec.txNumOfHistory(history));
    assertArrayEquals(history, ArchiveTemporalCodec.historyKeyOfChangeset(changeset));
    assertArrayEquals(changeset, ArchiveTemporalCodec.changesetKeyOfHistory(history));
    assertArrayEquals(ArchiveTemporalCodec.latestKey(ArchiveDomain.CONTRACT_STATE, key),
        ArchiveTemporalCodec.latestKeyOfChangeset(changeset));
    assertArrayEquals(ArchiveTemporalCodec.historyPrefix(ArchiveDomain.CONTRACT_STATE, key),
        ArchiveTemporalCodec.historyPrefixOfChangeset(changeset));
  }

  @Test
  public void keyLengthUsesFourByteBigEndianForLargeKeys() {
    byte[] key = new byte[0x10001];
    key[0] = 1;
    key[key.length - 1] = 2;

    byte[] latest = ArchiveTemporalCodec.latestKey(ArchiveDomain.DYNAMIC_PROPERTIES, key);
    byte[] history = ArchiveTemporalCodec.historyKey(ArchiveDomain.DYNAMIC_PROPERTIES, key, 11L);
    byte[] changeset = ArchiveTemporalCodec.changesetKey(
        11L, ArchiveDomain.DYNAMIC_PROPERTIES, key);

    assertEquals(7 + key.length, latest.length);
    assertEquals(15 + key.length, history.length);
    assertEquals(15 + key.length, changeset.length);
    assertArrayEquals(new byte[] {0, 1, 0, 1}, Arrays.copyOfRange(latest, 3, 7));
    assertArrayEquals(new byte[] {0, 1, 0, 1}, Arrays.copyOfRange(changeset, 11, 15));
    assertArrayEquals(history, ArchiveTemporalCodec.historyKeyOfChangeset(changeset));
    assertArrayEquals(changeset, ArchiveTemporalCodec.changesetKeyOfHistory(history));
  }

  @Test
  public void negativeDecodedKeyLengthIsRejected() {
    byte[] invalidHistory = new byte[15];
    invalidHistory[0] = ArchiveTemporalCodec.HISTORY_PREFIX;
    invalidHistory[3] = (byte) 0x80;

    byte[] invalidChangeset = new byte[15];
    invalidChangeset[0] = ArchiveTemporalCodec.CHANGESET_PREFIX;
    invalidChangeset[11] = (byte) 0x80;

    assertThrows(ArchiveException.class, () -> ArchiveTemporalCodec.txNumOfHistory(invalidHistory));
    assertThrows(ArchiveException.class,
        () -> ArchiveTemporalCodec.txNumOfChangeset(invalidChangeset));
  }

  @Test
  public void blockCommitKeyLivesInMetaTable() {
    byte[] key = ArchiveTemporalCodec.blockCommitKey(9L);

    assertEquals(0x01, key[0]);
    assertTrue(ArchiveTemporalCodec.startsWith(key, ArchiveTemporalCodec.blockCommitPrefix()));
    assertEquals(9L, ArchiveTemporalCodec.blockNumOfBlockCommitKey(key));
  }

  @Test
  public void historyKeysOrderByTxNumLexicographically() {
    byte[] key = {9};
    byte[] tx5 = ArchiveTemporalCodec.historyKey(ArchiveDomain.ACCOUNT, key, 5L);
    byte[] tx8 = ArchiveTemporalCodec.historyKey(ArchiveDomain.ACCOUNT, key, 8L);
    assertTrue("big-endian txNum must sort numerically", compare(tx5, tx8) < 0);
  }

  @Test
  public void valueRoundTrips() {
    DomainValue present = ArchiveTemporalCodec.decodeValue(
        ArchiveTemporalCodec.encodeValue(DomainValue.present(new byte[] {4, 5})));
    assertFalse(present.isDeleted());
    assertArrayEquals(new byte[] {4, 5}, present.getValue());
    assertTrue(ArchiveTemporalCodec.decodeValue(
        ArchiveTemporalCodec.encodeValue(DomainValue.tombstone())).isDeleted());
  }

  private static int compare(byte[] a, byte[] b) {
    int len = a.length < b.length ? a.length : b.length;
    for (int i = 0; i < len; i++) {
      int x = (a[i] & 0xff) - (b[i] & 0xff);
      if (x != 0) {
        return x;
      }
    }
    return a.length - b.length;
  }
}
