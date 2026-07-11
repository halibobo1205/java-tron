package org.tron.core.archive.codec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThrows;

import com.google.common.primitives.Bytes;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.tron.core.archive.ArchiveException;

/**
 * Contract for {@link AccountAssetKeyCodec}: the ACCOUNT_ASSET key = address(21) || assetId. Its
 * invariant is "must be strictly longer than the 21-byte address" (a non-empty assetId) and a
 * defensive copy on normalize. A regression accepting a bare 21-byte address would collide distinct
 * assets under one key. Not exercised elsewhere.
 */
public class AccountAssetKeyCodecTest {

  private final AccountAssetKeyCodec codec = new AccountAssetKeyCodec();

  private static byte[] key(String assetId) {
    byte[] addr = new byte[21];
    addr[0] = 0x41;
    return Bytes.concat(addr, assetId.getBytes(StandardCharsets.US_ASCII));
  }

  @Test
  public void codecId() {
    assertEquals("tron-account-asset-key-v2", codec.codecId());
  }

  @Test
  public void normalizeKeepsBytesAndDefensivelyCopies() {
    byte[] k = key("1000001");
    byte[] norm = codec.normalize(k);
    assertArrayEquals(k, norm);
    assertNotSame(k, norm);
    k[0] = 0; // mutate source after normalize
    assertEquals(0x41, norm[0] & 0xff); // normalized copy is unaffected
  }

  @Test
  public void bareAddressWithoutAssetIdIsRejected() {
    byte[] addressOnly = new byte[21];
    addressOnly[0] = 0x41;
    assertThrows(ArchiveException.class, () -> codec.normalize(addressOnly));
    assertThrows(ArchiveException.class, () -> codec.validate(addressOnly));
  }

  @Test
  public void tooShortOrNullIsRejected() {
    assertThrows(ArchiveException.class, () -> codec.normalize(new byte[20]));
    assertThrows(ArchiveException.class, () -> codec.normalize(null));
  }

  @Test
  public void oneByteAssetIdIsAccepted() {
    byte[] k = key("1"); // 21 + 1 = 22 bytes, the minimum valid key
    assertArrayEquals(k, codec.normalize(k));
    codec.validate(k);
  }
}
