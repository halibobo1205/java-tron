package org.tron.core.archive.codec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.common.primitives.Longs;
import org.junit.Test;
import org.tron.core.archive.ArchiveException;

/**
 * Contract for {@link Int64BalanceValueCodec}: the ACCOUNT_ASSET (TRC10) balance codec. Its
 * load-bearing invariant is "zero balance is a tombstone, never present-zero" -- a regression that
 * stored present-zero or accepted a wrong-width value would render a wrong or ghost historical
 * balance. Not exercised elsewhere ({@code CanonicalCodecTest} covers only the address/storage/
 * dynamic/bytes codecs).
 */
public class Int64BalanceValueCodecTest {

  private final Int64BalanceValueCodec codec = new Int64BalanceValueCodec("balance-v2");

  @Test
  public void codecIdIsAsConstructed() {
    assertEquals("balance-v2", codec.codecId());
  }

  @Test
  public void nonZeroBalanceIsPresentAndRoundTrips() {
    DomainValue v = codec.normalizePut(Longs.toByteArray(1234L));
    assertFalse(v.isDeleted());
    assertArrayEquals(Longs.toByteArray(1234L), v.getValue());
    assertEquals(1234L, Longs.fromByteArray(v.getValue()));
  }

  @Test
  public void maxLongIsPresent() {
    DomainValue v = codec.normalizePut(Longs.toByteArray(Long.MAX_VALUE));
    assertFalse(v.isDeleted());
    assertEquals(Long.MAX_VALUE, Longs.fromByteArray(v.getValue()));
  }

  @Test
  public void zeroBalanceIsTombstoneNeverPresentZero() {
    DomainValue v = codec.normalizePut(Longs.toByteArray(0L));
    assertTrue("zero balance must be a tombstone, not present-zero", v.isDeleted());
  }

  @Test
  public void deleteIsTombstone() {
    assertTrue(codec.normalizeDelete().isDeleted());
  }

  @Test
  public void wrongWidthOrNullPutIsRejected() {
    assertThrows(ArchiveException.class, () -> codec.normalizePut(new byte[7]));
    assertThrows(ArchiveException.class, () -> codec.normalizePut(new byte[9]));
    assertThrows(ArchiveException.class, () -> codec.normalizePut(null));
  }

  @Test
  public void validateRejectsNullZeroAndWrongWidthPresent() {
    assertThrows(ArchiveException.class, () -> codec.validate(null));
    // a present value that decodes to zero is illegal (zero must be a tombstone).
    assertThrows(ArchiveException.class,
        () -> codec.validate(DomainValue.present(Longs.toByteArray(0L))));
    assertThrows(ArchiveException.class, () -> codec.validate(DomainValue.present(new byte[7])));
  }

  @Test
  public void validateAcceptsPresentNonZeroAndTombstone() {
    codec.validate(DomainValue.present(Longs.toByteArray(5L)));
    codec.validate(DomainValue.tombstone());
  }
}
