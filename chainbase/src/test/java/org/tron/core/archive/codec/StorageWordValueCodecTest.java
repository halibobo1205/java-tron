package org.tron.core.archive.codec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.tron.core.archive.ArchiveException;

/**
 * Contract for {@link StorageWordValueCodec}: the CONTRACT_STORAGE value codec. Its load-bearing
 * invariant is "a zero 32-byte word is a tombstone (slot cleared), never present-zero", plus a
 * strict 32-byte width. A regression here would render a historical {@code eth_getStorageAt} slot
 * as non-zero garbage or a ghost value. Not exercised elsewhere.
 */
public class StorageWordValueCodecTest {

  private final StorageWordValueCodec codec = new StorageWordValueCodec();

  private static byte[] word(int lastByte) {
    byte[] w = new byte[32];
    w[31] = (byte) lastByte;
    return w;
  }

  @Test
  public void codecId() {
    assertEquals("storage-word-v2", codec.codecId());
  }

  @Test
  public void nonZeroWordIsPresentAndPreserved() {
    byte[] w = word(0x2a);
    DomainValue v = codec.normalizePut(w);
    assertFalse(v.isDeleted());
    assertArrayEquals(w, v.getValue());
  }

  @Test
  public void zeroWordIsTombstoneNeverPresentZero() {
    assertTrue("a zero storage word must be a tombstone",
        codec.normalizePut(new byte[32]).isDeleted());
  }

  @Test
  public void deleteIsTombstone() {
    assertTrue(codec.normalizeDelete().isDeleted());
  }

  @Test
  public void wrongWidthOrNullPutIsRejected() {
    assertThrows(ArchiveException.class, () -> codec.normalizePut(new byte[31]));
    assertThrows(ArchiveException.class, () -> codec.normalizePut(new byte[33]));
    assertThrows(ArchiveException.class, () -> codec.normalizePut(null));
  }

  @Test
  public void validateRejectsNullZeroAndWrongWidthPresent() {
    assertThrows(ArchiveException.class, () -> codec.validate(null));
    assertThrows(ArchiveException.class, () -> codec.validate(DomainValue.present(new byte[32])));
    assertThrows(ArchiveException.class, () -> codec.validate(DomainValue.present(new byte[31])));
  }

  @Test
  public void validateAcceptsPresentNonZeroAndTombstone() {
    codec.validate(DomainValue.present(word(1)));
    codec.validate(DomainValue.tombstone());
  }
}
