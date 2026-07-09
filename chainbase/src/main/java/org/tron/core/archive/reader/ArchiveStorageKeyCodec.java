package org.tron.core.archive.reader;

import com.google.common.primitives.Bytes;
import java.util.Arrays;

/**
 * Builds the CONTRACT_STORAGE canonical archive key
 * {@code address(21) || deploymentHash(32) || slot(32) || version(1)} from an RPC slot and the
 * contract's storage namespace/version. This is the SEMANTIC archive key (the same one L4c captures
 * from the VM with the un-hashed slot) -- never the latest physical storage key (no
 * {@code sha3(slot)}, no {@code Storage.compose}).
 */
public final class ArchiveStorageKeyCodec {

  public static final int ADDRESS_LEN = 21;
  public static final int DEPLOYMENT_HASH_LEN = 32;
  public static final int SLOT_LEN = 32;
  public static final int KEY_LEN = ADDRESS_LEN + DEPLOYMENT_HASH_LEN + SLOT_LEN + 1; // 86

  private ArchiveStorageKeyCodec() {
  }

  /** The 1-byte storage-key version: {@code 0x01} only when the contract storage version is 1. */
  public static byte storageKeyVersion(int contractVersion) {
    return (byte) (contractVersion == 1 ? 0x01 : 0x00);
  }

  public static byte[] contractStorageKey(byte[] address, byte[] slot, int contractVersion) {
    return contractStorageKey(address, slot, null, contractVersion);
  }

  public static byte[] contractStorageKey(byte[] address, byte[] slot, byte[] deploymentHash,
      int contractVersion) {
    if (address == null || address.length != ADDRESS_LEN) {
      throw new IllegalArgumentException("address must be " + ADDRESS_LEN + " bytes");
    }
    if (slot == null || slot.length != SLOT_LEN) {
      throw new IllegalArgumentException("slot must be " + SLOT_LEN + " bytes");
    }
    return Bytes.concat(address, deploymentHash(deploymentHash), slot,
        new byte[] {storageKeyVersion(contractVersion)});
  }

  public static byte[] deploymentHash(byte[] trxHash) {
    if (trxHash == null || trxHash.length == 0) {
      return new byte[DEPLOYMENT_HASH_LEN];
    }
    if (trxHash.length != DEPLOYMENT_HASH_LEN) {
      throw new IllegalArgumentException(
          "deployment hash must be " + DEPLOYMENT_HASH_LEN + " bytes");
    }
    if (isNullOrZero(trxHash)) {
      return new byte[DEPLOYMENT_HASH_LEN];
    }
    return Arrays.copyOf(trxHash, trxHash.length);
  }

  private static boolean isNullOrZero(byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      return true;
    }
    for (byte b : bytes) {
      if (b != 0) {
        return false;
      }
    }
    return true;
  }
}
