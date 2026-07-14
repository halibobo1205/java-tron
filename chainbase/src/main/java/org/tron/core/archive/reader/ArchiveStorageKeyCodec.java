package org.tron.core.archive.reader;

import com.google.common.primitives.Bytes;
import java.util.Arrays;
import org.tron.common.crypto.Hash;

/**
 * Builds the exact 32-byte physical storage-row key used by {@code Storage.compose}. The physical
 * identity is intentional: for contract versions other than 1, java-tron aliases logical slots
 * that share their low 16 bytes, so a semantic address/slot tuple is not a unique state key.
 */
public final class ArchiveStorageKeyCodec {

  public static final int ADDRESS_LEN = 21;
  public static final int DEPLOYMENT_HASH_LEN = 32;
  public static final int SLOT_LEN = 32;
  public static final int KEY_LEN = 32;
  private static final int PREFIX_BYTES = 16;

  private ArchiveStorageKeyCodec() {
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
    return physicalRowKey(contractAddressHash(address, deploymentHash), slot, contractVersion);
  }

  /** Composes a physical row key from the already-derived 32-byte contract namespace hash. */
  public static byte[] physicalRowKey(byte[] addressHash, byte[] slot, int contractVersion) {
    if (addressHash == null || addressHash.length != KEY_LEN) {
      throw new IllegalArgumentException("address hash must be " + KEY_LEN + " bytes");
    }
    if (slot == null || slot.length != SLOT_LEN) {
      throw new IllegalArgumentException("slot must be " + SLOT_LEN + " bytes");
    }
    byte[] slotKey = contractVersion == 1 ? Hash.sha3(slot) : slot;
    byte[] rowKey = new byte[KEY_LEN];
    System.arraycopy(addressHash, 0, rowKey, 0, PREFIX_BYTES);
    System.arraycopy(slotKey, PREFIX_BYTES, rowKey, PREFIX_BYTES, PREFIX_BYTES);
    return rowKey;
  }

  private static byte[] contractAddressHash(byte[] address, byte[] deploymentHash) {
    byte[] normalizedDeploymentHash = deploymentHash(deploymentHash);
    if (isNullOrZero(normalizedDeploymentHash)) {
      return Hash.sha3(address);
    }
    return Hash.sha3(Bytes.concat(address, normalizedDeploymentHash));
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
