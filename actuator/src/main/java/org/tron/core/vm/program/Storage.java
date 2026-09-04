package org.tron.core.vm.program;

import static java.lang.System.arraycopy;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.tron.common.crypto.Hash;
import org.tron.common.runtime.vm.DataWord;
import org.tron.common.utils.ByteUtil;
import org.tron.core.archive.ArchiveMetrics;
import org.tron.core.archive.capture.ArchiveCaptureHolder;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.reader.ArchiveStorageKeyCodec;
import org.tron.core.capsule.StorageRowCapsule;
import org.tron.core.db2.common.WrappedByteArray;
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
    // Keep the VM's legacy namespace calculation byte-for-byte unchanged.
    addrHash = addrHash(address, trxId);
  }

  public DataWord getValue(DataWord key) {
    if (rowCache.containsKey(key)) {
      return new DataWord(rowCache.get(key).getValue());
    }
    byte[] rowKey = compose(key.getData(), addrHash);
    StorageRowCapsule row = store.get(rowKey);
    if (row == null || row.getInstance() == null) {
      return null;
    }
    rowCache.put(key, row);
    return new DataWord(row.getValue());
  }

  public void put(DataWord key, DataWord value) {
    if (rowCache.containsKey(key)) {
      StorageRowCapsule row = rowCache.get(key);
      row.setValue(value.getData());
    } else {
      byte[] rowKey = compose(key.getData(), addrHash);
      StorageRowCapsule row = new StorageRowCapsule(rowKey, value.getData());
      rowCache.put(key, row);
    }
  }

  public void commit() {
    // L4c archive: capture the exact physical row identity at the per-tx storage write boundary.
    boolean archiveActive = ArchiveCaptureHolder.isCapturingCurrentTx();
    Map<WrappedByteArray, byte[]> currentValues = archiveActive ? new HashMap<>() : null;
    rowCache.forEach((DataWord logicalKey, StorageRowCapsule row) -> {
      if (row.isDirty()) {
        if (!archiveActive) {
          ArchiveCaptureHolder.requireCurrentTx("contractStorageCommit");
        }
        boolean zero = new DataWord(row.getValue()).isZero();
        byte[] prev = null;
        byte[] archiveKey = null;
        boolean capturePrepared = false;
        if (archiveActive) {
          try {
            if (address.length != ArchiveStorageKeyCodec.ADDRESS_LEN) {
              throw new IllegalArgumentException(
                  "address must be " + ArchiveStorageKeyCodec.ADDRESS_LEN + " bytes");
            }
            if (row.getRowKey() == null) {
              throw new IllegalStateException("storage row key is missing");
            }
            byte[] archiveRowKey = Arrays.copyOf(row.getRowKey(), row.getRowKey().length);
            WrappedByteArray cacheKey = WrappedByteArray.copyOf(archiveRowKey);
            archiveKey = archiveRowKey;
            // Read the Erigon prev-value before mutating the store. Preparation failures must not
            // alter canonical VM execution; the archive commit will fail closed instead.
            if (currentValues.containsKey(cacheKey)) {
              byte[] current = currentValues.get(cacheKey);
              prev = current == null ? null : Arrays.copyOf(current, current.length);
            } else {
              prev = prevSlotValue(archiveRowKey);
            }
            capturePrepared = true;
          } catch (Exception e) {
            ArchiveCaptureHolder.recordFailure("contractStoragePrepare", e);
          }
        }
        if (zero) {
          this.store.delete(row.getRowKey());
        } else {
          this.store.put(row.getRowKey(), row);
        }
        if (capturePrepared) {
          if (zero) {
            ArchiveCaptureHolder.captureSemanticDelete(
                ArchiveDomain.CONTRACT_STORAGE, archiveKey, prev);
            currentValues.put(WrappedByteArray.copyOf(archiveKey), null);
          } else {
            byte[] current = row.getValue();
            ArchiveCaptureHolder.captureSemanticPut(
                ArchiveDomain.CONTRACT_STORAGE, archiveKey, prev, current);
            currentValues.put(WrappedByteArray.copyOf(archiveKey),
                Arrays.copyOf(current, current.length));
          }
        }
      }
    });
  }

  /** The committed value of a storage row before this tx overwrites it, or null if the slot was
   * absent/zero (a tombstone prev in the archive). */
  private byte[] prevSlotValue(byte[] rowKey) {
    long startedNanos = ArchiveMetrics.startTimer();
    try {
      StorageRowCapsule old = this.store.get(rowKey);
      ArchiveCaptureHolder.recordPreviousValueRead(startedNanos, true);
      return (old == null || old.getInstance() == null) ? null : old.getValue();
    } catch (RuntimeException e) {
      ArchiveCaptureHolder.recordPreviousValueRead(startedNanos, false);
      throw e;
    }
  }

}
