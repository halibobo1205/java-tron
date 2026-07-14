package org.tron.core.archive.reader;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import java.util.Arrays;
import org.junit.Test;

public class ArchiveStorageKeyCodecTest {

  @Test
  public void buildsExactPhysicalStorageRowKey() {
    byte[] address = new byte[21];
    address[0] = 0x41;
    byte[] slot = new byte[32];
    slot[31] = 5;
    byte[] deploymentHash = new byte[32];
    deploymentHash[31] = 7;
    byte[] key = ArchiveStorageKeyCodec.contractStorageKey(address, slot, 2);

    assertEquals(32, key.length);
    assertArrayEquals(Arrays.copyOfRange(slot, 16, 32), Arrays.copyOfRange(key, 16, 32));

    byte[] create2Key = ArchiveStorageKeyCodec.contractStorageKey(
        address, slot, deploymentHash, 2);
    assertFalse("CREATE2 deployment hash must select a different storage namespace",
        Arrays.equals(key, create2Key));
  }

  @Test
  public void nonVersionOneSlotsWithSameLowHalfAliasToSamePhysicalRow() {
    byte[] address = new byte[21];
    address[0] = 0x41;
    byte[] first = new byte[32];
    byte[] alias = new byte[32];
    first[0] = 1;
    alias[0] = 2;
    first[31] = alias[31] = 9;

    assertArrayEquals(
        ArchiveStorageKeyCodec.contractStorageKey(address, first, 0),
        ArchiveStorageKeyCodec.contractStorageKey(address, alias, 2));
    assertFalse(Arrays.equals(
        ArchiveStorageKeyCodec.contractStorageKey(address, first, 1),
        ArchiveStorageKeyCodec.contractStorageKey(address, alias, 1)));
  }

  @Test
  public void rejectsBadAddressOrSlotLength() {
    assertThrows(IllegalArgumentException.class,
        () -> ArchiveStorageKeyCodec.contractStorageKey(new byte[5], new byte[32], 0));
    assertThrows(IllegalArgumentException.class,
        () -> ArchiveStorageKeyCodec.contractStorageKey(new byte[21], new byte[8], 0));
    assertThrows(IllegalArgumentException.class,
        () -> ArchiveStorageKeyCodec.contractStorageKey(
            new byte[21], new byte[32], new byte[31], 0));
  }
}
