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
 * asset ID is the canonical decimal representation of a positive long, and normalize returns a
 * defensive copy.
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

  @Test
  public void positiveLongBoundaryIsAcceptedAndDecodes() {
    String max = Long.toString(Long.MAX_VALUE);
    codec.validate(key(max));
    assertEquals(max, AccountAssetKeyCodec.decodeAssetId(
        max.getBytes(StandardCharsets.US_ASCII)));
  }

  @Test
  public void nonCanonicalOrOutOfRangeAssetIdsAreRejected() {
    String[] invalid = {
        "0",
        "01",
        "-1",
        "abc",
        "10000000000000000000",
        "9223372036854775808"
    };
    for (String assetId : invalid) {
      assertThrows(ArchiveException.class, () -> codec.validate(key(assetId)));
      assertThrows(ArchiveException.class, () -> AccountAssetKeyCodec.decodeAssetId(
          assetId.getBytes(StandardCharsets.US_ASCII)));
    }
  }
}
