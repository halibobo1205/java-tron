package org.tron.core.archive.reader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class ArchiveStorageKeyCodecTest {

  @Test
  public void buildsEightySixByteKeyWithDeploymentHashAndVersionTail() {
    byte[] deploymentHash = new byte[32];
    deploymentHash[31] = 7;
    byte[] key = ArchiveStorageKeyCodec.contractStorageKey(new byte[21], new byte[32], 1);
    assertEquals(86, key.length);
    assertEquals(1, key[85] & 0xff);
    byte[] create2Key = ArchiveStorageKeyCodec.contractStorageKey(
        new byte[21], new byte[32], deploymentHash, 1);
    assertEquals(7, create2Key[52] & 0xff);
  }

  @Test
  public void versionByteIsOneOnlyForContractVersionOne() {
    assertEquals(1, ArchiveStorageKeyCodec.storageKeyVersion(1) & 0xff);
    assertEquals(0, ArchiveStorageKeyCodec.storageKeyVersion(0) & 0xff);
    assertEquals(0, ArchiveStorageKeyCodec.storageKeyVersion(2) & 0xff);
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
