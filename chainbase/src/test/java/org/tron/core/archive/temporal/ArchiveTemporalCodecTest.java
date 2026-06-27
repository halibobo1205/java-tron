package org.tron.core.archive.temporal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
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
    assertEquals(ArchiveTemporalCodec.LATEST_PREFIX, latest[0]);
    assertEquals(ArchiveTemporalCodec.HISTORY_PREFIX, history[0]);
    assertEquals(6, latest.length);  // prefix(1) + domainId(2) + key(3)
    assertEquals(14, history.length); // + txNum(8)
    assertTrue(ArchiveTemporalCodec.startsWith(history,
        ArchiveTemporalCodec.historyPrefix(ArchiveDomain.ACCOUNT, key)));
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
