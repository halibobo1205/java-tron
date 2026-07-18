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
        UnifiedArchiveColumnFamily.CHANGESET, ROW_KEY, PAYLOAD, 7L);

    ArchiveTemporalIntegrityCodec.Locator decodedLocator =
        ArchiveTemporalIntegrityCodec.decodeLocator(
            UnifiedArchiveColumnFamily.CHANGESET, ROW_KEY, locator, "test");
    ArchiveTemporalIntegrityCodec.DecodedRow decoded =
        ArchiveTemporalIntegrityCodec.decode(
            UnifiedArchiveColumnFamily.CHANGESET, ROW_KEY, decodedLocator, PAYLOAD, "test");

    assertEquals(ArchiveTemporalIntegrityCodec.LOCATOR_BYTES, locator.length);
    assertEquals(7L, decoded.linkedTxNum());
    assertArrayEquals(PAYLOAD, decoded.payload());
  }

  @Test
  public void digestAndPayloadKeyAreBoundToTableAndLogicalKey() {
    byte[] locator = ArchiveTemporalIntegrityCodec.encode(
        UnifiedArchiveColumnFamily.CHANGESET, ROW_KEY, PAYLOAD, 7L);
    ArchiveTemporalIntegrityCodec.Locator decodedLocator =
        ArchiveTemporalIntegrityCodec.decodeLocator(
            UnifiedArchiveColumnFamily.CHANGESET, ROW_KEY, locator, "test");

    assertThrows(ArchiveException.class,
        () -> ArchiveTemporalIntegrityCodec.decode(
            UnifiedArchiveColumnFamily.COMMITMENT, ROW_KEY, decodedLocator, PAYLOAD, "test"));
    assertThrows(ArchiveException.class,
        () -> ArchiveTemporalIntegrityCodec.decode(
            UnifiedArchiveColumnFamily.CHANGESET, new byte[] {0x01, 0x02},
            decodedLocator, PAYLOAD, "test"));
    assertFalse(Arrays.equals(
        ArchiveTemporalIntegrityCodec.payloadKey(
            UnifiedArchiveColumnFamily.CHANGESET, ROW_KEY),
        ArchiveTemporalIntegrityCodec.payloadKey(
            UnifiedArchiveColumnFamily.COMMITMENT, ROW_KEY)));
    byte[] payloadKey = ArchiveTemporalIntegrityCodec.payloadKey(
        UnifiedArchiveColumnFamily.CHANGESET, ROW_KEY);
    assertEquals(UnifiedArchiveColumnFamily.CHANGESET,
        ArchiveTemporalIntegrityCodec.columnFamilyOfPayloadKey(payloadKey, "test"));
    assertArrayEquals(ROW_KEY,
        ArchiveTemporalIntegrityCodec.logicalKeyOfPayloadKey(payloadKey, "test"));
  }

  @Test
  public void referenceBindsSourceTargetAndTargetLocator() {
    byte[] sourceKey = new byte[] {0x21, 0x01};
    byte[] targetKey = new byte[] {0x22, 0x02};
    byte[] targetLocator = ArchiveTemporalIntegrityCodec.encode(
        UnifiedArchiveColumnFamily.CHANGESET, targetKey, PAYLOAD, 7L);
    byte[] reference = ArchiveTemporalIntegrityCodec.encodeReference(
        UnifiedArchiveColumnFamily.HISTORY, sourceKey, 7L,
        UnifiedArchiveColumnFamily.CHANGESET, targetKey, targetLocator);

    assertEquals(ArchiveTemporalIntegrityCodec.REFERENCE_BYTES, reference.length);
    assertEquals(7L, ArchiveTemporalIntegrityCodec.decodeReference(
        UnifiedArchiveColumnFamily.HISTORY, sourceKey, reference,
        UnifiedArchiveColumnFamily.CHANGESET, targetKey, targetLocator, "test"));
    assertThrows(ArchiveException.class, () -> ArchiveTemporalIntegrityCodec.decodeReference(
        UnifiedArchiveColumnFamily.LATEST, sourceKey, reference,
        UnifiedArchiveColumnFamily.CHANGESET, targetKey, targetLocator, "test"));
    assertThrows(ArchiveException.class, () -> ArchiveTemporalIntegrityCodec.decodeReference(
        UnifiedArchiveColumnFamily.HISTORY, sourceKey, reference,
        UnifiedArchiveColumnFamily.CHANGESET, new byte[] {0x22, 0x03},
        targetLocator, "test"));
    byte[] modifiedLocator = targetLocator.clone();
    modifiedLocator[modifiedLocator.length - 1] ^= 0x01;
    assertThrows(ArchiveException.class, () -> ArchiveTemporalIntegrityCodec.decodeReference(
        UnifiedArchiveColumnFamily.HISTORY, sourceKey, reference,
        UnifiedArchiveColumnFamily.CHANGESET, targetKey, modifiedLocator, "test"));
  }

  @Test
  public void historyAndLatestCannotOwnPayloads() {
    assertThrows(ArchiveException.class, () -> ArchiveTemporalIntegrityCodec.encode(
        UnifiedArchiveColumnFamily.HISTORY, ROW_KEY, PAYLOAD, 7L));
    assertThrows(ArchiveException.class, () -> ArchiveTemporalIntegrityCodec.payloadKey(
        UnifiedArchiveColumnFamily.LATEST, ROW_KEY));
  }

  @Test
  public void rejectsMalformedLocatorAndPayloadLengthBeforeDecode() {
    byte[] locator = ArchiveTemporalIntegrityCodec.encode(
        UnifiedArchiveColumnFamily.CHANGESET, ROW_KEY, PAYLOAD, 7L);
    assertThrows(ArchiveException.class,
        () -> ArchiveTemporalIntegrityCodec.decodeLocator(
            UnifiedArchiveColumnFamily.CHANGESET, ROW_KEY,
            Arrays.copyOf(locator, locator.length - 1), "test"));

    int oversized = ArchiveTemporalIntegrityCodec.MAX_STORED_PAYLOAD_BYTES + 1;
    locator[9] = (byte) (oversized >>> 24);
    locator[10] = (byte) (oversized >>> 16);
    locator[11] = (byte) (oversized >>> 8);
    locator[12] = (byte) oversized;
    assertThrows(ArchiveException.class,
        () -> ArchiveTemporalIntegrityCodec.decodeLocator(
            UnifiedArchiveColumnFamily.CHANGESET, ROW_KEY, locator, "test"));
  }
}
