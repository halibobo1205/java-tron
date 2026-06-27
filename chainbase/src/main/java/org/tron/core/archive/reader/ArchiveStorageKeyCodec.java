package org.tron.core.archive.reader;

import com.google.common.primitives.Bytes;

/**
 * Builds the CONTRACT_STORAGE canonical archive key {@code address(21) || slot(32) || version(1)}
 * from an RPC slot and the contract's storage version. This is the SEMANTIC archive key (the same
 * one L4c captures from the VM with the un-hashed slot) -- never the latest physical storage key
 * (no {@code sha3(slot)}, no {@code Storage.compose}).
 */
public final class ArchiveStorageKeyCodec {

  public static final int ADDRESS_LEN = 21;
  public static final int SLOT_LEN = 32;
  public static final int KEY_LEN = ADDRESS_LEN + SLOT_LEN + 1; // 54

  private ArchiveStorageKeyCodec() {
  }

  /** The 1-byte storage-key version: {@code 0x01} only when the contract storage version is 1. */
  public static byte storageKeyVersion(int contractVersion) {
    return (byte) (contractVersion == 1 ? 0x01 : 0x00);
  }

  public static byte[] contractStorageKey(byte[] address, byte[] slot, int contractVersion) {
    if (address == null || address.length != ADDRESS_LEN) {
      throw new IllegalArgumentException("address must be " + ADDRESS_LEN + " bytes");
    }
    if (slot == null || slot.length != SLOT_LEN) {
      throw new IllegalArgumentException("slot must be " + SLOT_LEN + " bytes");
    }
    return Bytes.concat(address, slot, new byte[] {storageKeyVersion(contractVersion)});
  }
}
