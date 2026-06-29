package org.tron.core.vm.program;

import static java.lang.System.arraycopy;

import com.google.common.primitives.Bytes;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.tron.common.crypto.Hash;
import org.tron.common.runtime.vm.DataWord;
import org.tron.common.utils.ByteUtil;
import org.tron.core.archive.capture.ArchiveCaptureHolder;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.capsule.StorageRowCapsule;
import org.tron.core.store.StorageRowStore;

public class Storage {

  private static final int PREFIX_BYTES = 16;
  @Getter
  private final Map<DataWord, StorageRowCapsule> rowCache = new HashMap<>();
  @Getter
  private byte[] addrHash;
  @Getter
  private StorageRowStore store;
  @Getter
  private byte[] address;
  @Setter
  private int contractVersion;

  public Storage(byte[] address, StorageRowStore store) {
    addrHash = addrHash(address);
    this.address = address;
    this.store = store;
  }

  public Storage(Storage storage) {
    this.addrHash = storage.addrHash.clone();
    this.address = storage.getAddress().clone();
    this.store = storage.store;
    this.contractVersion = storage.contractVersion;
    storage.getRowCache().forEach((DataWord rowKey, StorageRowCapsule row) -> {
      StorageRowCapsule newRow = new StorageRowCapsule(row);
      this.rowCache.put(rowKey.clone(), newRow);
    });
  }

  private byte[] compose(byte[] key, byte[] addrHash) {
    if (contractVersion == 1) {
      key = Hash.sha3(key);
    }
    byte[] result = new byte[key.length];
    arraycopy(addrHash, 0, result, 0, PREFIX_BYTES);
    arraycopy(key, PREFIX_BYTES, result, PREFIX_BYTES, PREFIX_BYTES);
    return result;
  }

  // 32 bytes
  private static byte[] addrHash(byte[] address) {
    return Hash.sha3(address);
  }

  private static byte[] addrHash(byte[] address, byte[] trxHash) {
    if (ByteUtil.isNullOrZeroArray(trxHash)) {
      return Hash.sha3(address);
    }
    return Hash.sha3(ByteUtil.merge(address, trxHash));
  }

  public void generateAddrHash(byte[] trxId) {
    // update addreHash for create2
    addrHash = addrHash(address, trxId);
  }

  public DataWord getValue(DataWord key) {
    if (rowCache.containsKey(key)) {
      return new DataWord(rowCache.get(key).getValue());
    } else {
      StorageRowCapsule row = store.get(compose(key.getData(), addrHash));
      if (row == null || row.getInstance() == null) {
        return null;
      }
      rowCache.put(key, row);
      return new DataWord(row.getValue());
    }
  }

  public void put(DataWord key, DataWord value) {
    if (rowCache.containsKey(key)) {
      rowCache.get(key).setValue(value.getData());
    } else {
      byte[] rowKey = compose(key.getData(), addrHash);
      StorageRowCapsule row = new StorageRowCapsule(rowKey, value.getData());
      rowCache.put(key, row);
    }
  }

  public void commit() {
    // L4c archive: capture CONTRACT_STORAGE here (root per-tx storage write) where the contract
    // address + un-hashed slot are known; raw store key (row.getRowKey()) is irreversible.
    boolean archiveActive = ArchiveCaptureHolder.isActive();
    rowCache.forEach((DataWord rowKey, StorageRowCapsule row) -> {
      if (row.isDirty()) {
        boolean zero = new DataWord(row.getValue()).isZero();
        // Read the slot's pre-write value (Erigon prev-value) before mutating the store; gated so a
        // non-archive node never does this extra read.
        byte[] prev = archiveActive ? prevSlotValue(row.getRowKey()) : null;
        if (zero) {
          this.store.delete(row.getRowKey());
        } else {
          this.store.put(row.getRowKey(), row);
        }
        if (archiveActive) {
          byte[] key = Bytes.concat(address, rowKey.getData(), new byte[] {(byte) contractVersion});
          if (zero) {
            ArchiveCaptureHolder.captureSemanticDelete(ArchiveDomain.CONTRACT_STORAGE, key, prev);
          } else {
            ArchiveCaptureHolder.captureSemanticPut(
                ArchiveDomain.CONTRACT_STORAGE, key, prev, row.getValue());
          }
        }
      }
    });
  }

  /** The committed value of a storage row before this tx overwrites it, or null if the slot was
   * absent/zero (a tombstone prev in the archive). */
  private byte[] prevSlotValue(byte[] rowKey) {
    StorageRowCapsule old = this.store.get(rowKey);
    return (old == null || old.getInstance() == null) ? null : old.getValue();
  }
}
