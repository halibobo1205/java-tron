package org.tron.core.archive.temporal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import java.util.Arrays;
import org.junit.Test;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.unified.UnifiedArchiveColumnFamily;

public class ArchiveTemporalIntegrityCodecTest {

  private static final byte[] ROW_KEY = new byte[] {0x01, 0x02, 0x03};
  private static final byte[] PAYLOAD = new byte[] {0x04, 0x05, 0x06};

  @Test
  public void roundTripsFixedLocatorAndPayload() {
    byte[] locator = ArchiveTemporalIntegrityCodec.encode(
        UnifiedArchiveColumnFamily.HISTORY, ROW_KEY, PAYLOAD, 7L);

    ArchiveTemporalIntegrityCodec.Locator decodedLocator =
        ArchiveTemporalIntegrityCodec.decodeLocator(
            UnifiedArchiveColumnFamily.HISTORY, ROW_KEY, locator, "test");
    ArchiveTemporalIntegrityCodec.DecodedRow decoded =
        ArchiveTemporalIntegrityCodec.decode(
            UnifiedArchiveColumnFamily.HISTORY, ROW_KEY, decodedLocator, PAYLOAD, "test");

    assertEquals(ArchiveTemporalIntegrityCodec.LOCATOR_BYTES, locator.length);
    assertEquals(7L, decoded.linkedTxNum());
    assertArrayEquals(PAYLOAD, decoded.payload());
  }

  @Test
  public void digestAndPayloadKeyAreBoundToTableAndLogicalKey() {
    byte[] locator = ArchiveTemporalIntegrityCodec.encode(
        UnifiedArchiveColumnFamily.HISTORY, ROW_KEY, PAYLOAD, 7L);
    ArchiveTemporalIntegrityCodec.Locator decodedLocator =
        ArchiveTemporalIntegrityCodec.decodeLocator(
            UnifiedArchiveColumnFamily.HISTORY, ROW_KEY, locator, "test");

    assertThrows(ArchiveException.class,
        () -> ArchiveTemporalIntegrityCodec.decode(
            UnifiedArchiveColumnFamily.LATEST, ROW_KEY, decodedLocator, PAYLOAD, "test"));
    assertThrows(ArchiveException.class,
        () -> ArchiveTemporalIntegrityCodec.decode(
            UnifiedArchiveColumnFamily.HISTORY, new byte[] {0x01, 0x02},
            decodedLocator, PAYLOAD, "test"));
    assertFalse(Arrays.equals(
        ArchiveTemporalIntegrityCodec.payloadKey(
            UnifiedArchiveColumnFamily.HISTORY, ROW_KEY),
        ArchiveTemporalIntegrityCodec.payloadKey(
            UnifiedArchiveColumnFamily.LATEST, ROW_KEY)));
    byte[] payloadKey = ArchiveTemporalIntegrityCodec.payloadKey(
        UnifiedArchiveColumnFamily.HISTORY, ROW_KEY);
    assertEquals(UnifiedArchiveColumnFamily.HISTORY,
        ArchiveTemporalIntegrityCodec.columnFamilyOfPayloadKey(payloadKey, "test"));
    assertArrayEquals(ROW_KEY,
        ArchiveTemporalIntegrityCodec.logicalKeyOfPayloadKey(payloadKey, "test"));
  }

  @Test
  public void rejectsMalformedLocatorAndPayloadLengthBeforeDecode() {
    byte[] locator = ArchiveTemporalIntegrityCodec.encode(
        UnifiedArchiveColumnFamily.HISTORY, ROW_KEY, PAYLOAD, 7L);
    assertThrows(ArchiveException.class,
        () -> ArchiveTemporalIntegrityCodec.decodeLocator(
            UnifiedArchiveColumnFamily.HISTORY, ROW_KEY,
            Arrays.copyOf(locator, locator.length - 1), "test"));

    int oversized = ArchiveTemporalIntegrityCodec.MAX_STORED_PAYLOAD_BYTES + 1;
    locator[9] = (byte) (oversized >>> 24);
    locator[10] = (byte) (oversized >>> 16);
    locator[11] = (byte) (oversized >>> 8);
    locator[12] = (byte) oversized;
    assertThrows(ArchiveException.class,
        () -> ArchiveTemporalIntegrityCodec.decodeLocator(
            UnifiedArchiveColumnFamily.HISTORY, ROW_KEY, locator, "test"));
  }
}
