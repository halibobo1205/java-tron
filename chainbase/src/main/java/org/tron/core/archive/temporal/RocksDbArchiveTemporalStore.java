package org.tron.core.archive.temporal;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongPredicate;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.ArchiveRocksDbWriteOptions;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.db2.common.WrappedByteArray;

/**
 * RocksDB-backed {@link ArchiveTemporalStore} for the Erigon-v3 prev-value model. A single column
 * family holds the latest record, the txNum-versioned history (storing each change's PRE-value) and
 * the txNum-ordered changeset, distinguished by the {@link ArchiveTemporalCodec} family prefix.
 * {@code getAsOf} forward-seeks the first history entry after the queried txNum and returns its
 * pre-value (falling back to latest when none exists); {@code latest} is a direct get. One column
 * family + plain KV keeps it compatible with the RocksDB shipped for both architectures
 * (5.15.10 / 9.7.4); decision-5 column-family / BlobDB tuning is a follow-on.
 */
public final class RocksDbArchiveTemporalStore implements ArchiveTemporalStore, AutoCloseable {

  static {
    RocksDB.loadLibrary();
  }

  private final Options options;
  private final RocksDB db;

  public RocksDbArchiveTemporalStore(String path) {
    this.options = new Options().setCreateIfMissing(true);
    RocksDB opened = null;
    try {
      opened = RocksDB.open(options, path);
      validateOrInstallManifest(opened);
      this.db = opened;
    } catch (RocksDBException e) {
      closeQuietly(opened);
      options.close();
      throw new ArchiveException("failed to open archive temporal store at " + path, e);
    } catch (RuntimeException e) {
      closeQuietly(opened);
      options.close();
      throw e;
    }
  }

  private static void validateOrInstallManifest(RocksDB db) throws RocksDBException {
    byte[] manifest = db.get(ArchiveTemporalCodec.manifestKey());
    if (manifest != null) {
      if (!ArchiveTemporalCodec.manifestMatches(manifest)) {
        throw new ArchiveException("archive temporal manifest mismatch");
      }
      return;
    }
    if (!isEmpty(db)) {
      throw new ArchiveException("archive temporal store is non-empty but missing manifest");
    }
    try (WriteOptions writeOptions = ArchiveRocksDbWriteOptions.create()) {
      db.put(writeOptions, ArchiveTemporalCodec.manifestKey(),
          ArchiveTemporalCodec.manifestValue());
    }
  }

  private static boolean isEmpty(RocksDB db) {
    try (RocksIterator it = db.newIterator()) {
      it.seekToFirst();
      return !it.isValid();
    }
  }

  private static void closeQuietly(RocksDB db) {
    if (db != null) {
      db.close();
    }
  }

  @Override
  public void putChange(ArchiveChangeRecord record) {
    try (WriteBatch batch = new WriteBatch();
         WriteOptions writeOptions = ArchiveRocksDbWriteOptions.create()) {
      putChange(batch, record);
      db.write(writeOptions, batch);
    } catch (RocksDBException e) {
      throw new ArchiveException("archive temporal putChange failed", e);
    }
  }

  @Override
  public void putChanges(List<ArchiveChangeRecord> records) {
    try (WriteBatch batch = new WriteBatch();
         WriteOptions writeOptions = ArchiveRocksDbWriteOptions.create()) {
      for (ArchiveChangeRecord record : records) {
        putChange(batch, record);
      }
      db.write(writeOptions, batch);
    } catch (RocksDBException e) {
      throw new ArchiveException("archive temporal putChanges failed", e);
    }
  }

  @Override
  public void putBlockChanges(ArchiveBlockRange range, List<ArchiveChangeRecord> records) {
    try (WriteBatch batch = new WriteBatch();
         WriteOptions writeOptions = ArchiveRocksDbWriteOptions.create()) {
      for (ArchiveChangeRecord record : records) {
        validateRecordInRange(range, record);
        putChange(batch, record);
      }
      batch.put(ArchiveTemporalCodec.blockCommitKey(range.getBlockNum()),
          ArchiveTemporalCodec.encodeBlockCommit(range));
      db.write(writeOptions, batch);
    } catch (RocksDBException e) {
      throw new ArchiveException("archive temporal putBlockChanges failed", e);
    }
  }

  @Override
  public void validateCommittedBlock(ArchiveBlockRange range) {
    try {
      byte[] marker = db.get(ArchiveTemporalCodec.blockCommitKey(range.getBlockNum()));
      if (!ArchiveTemporalCodec.blockCommitMatches(marker, range)) {
        throw new ArchiveException("archive temporal commit marker missing for block "
            + range.getBlockNum());
      }
    } catch (RocksDBException e) {
      throw new ArchiveException("archive temporal commit marker read failed", e);
    }
  }

  /** True when this temporal store contains any row other than its schema manifest. */
  public boolean hasDataBeyondManifest() {
    try (RocksIterator it = db.newIterator()) {
      it.seekToFirst();
      while (it.isValid()) {
        if (!Arrays.equals(it.key(), ArchiveTemporalCodec.manifestKey())) {
          return true;
        }
        it.next();
      }
      return false;
    }
  }

  /** Fail closed if temporal commit markers refer to blocks absent from the txNum index. */
  public void validateCommitMarkersCovered(LongPredicate hasIndexRange) {
    byte[] prefix = ArchiveTemporalCodec.blockCommitPrefix();
    try (RocksIterator it = db.newIterator()) {
      it.seek(prefix);
      while (it.isValid() && ArchiveTemporalCodec.startsWith(it.key(), prefix)) {
        byte[] key = it.key();
        long blockNum = ArchiveTemporalCodec.blockNumOfBlockCommitKey(key);
        if (!hasIndexRange.test(blockNum)) {
          throw new ArchiveException(
              "archive temporal commit marker has no index range for block " + blockNum);
        }
        it.next();
      }
    }
  }

  /** Fail closed if history/changeset txNums are not covered by the committed txNum index. */
  public void validateTxNumsCovered(LongPredicate hasCommittedTxNum) {
    try (RocksIterator it = db.newIterator()) {
      it.seek(new byte[] {ArchiveTemporalCodec.HISTORY_PREFIX});
      while (it.isValid() && it.key()[0] == ArchiveTemporalCodec.HISTORY_PREFIX) {
        byte[] historyKey = it.key();
        long txNum = ArchiveTemporalCodec.txNumOfHistory(historyKey);
        if (!hasCommittedTxNum.test(txNum)) {
          throw new ArchiveException(
              "archive temporal history txNum has no index position: " + txNum);
        }
        if (db.get(ArchiveTemporalCodec.changesetKeyOfHistory(historyKey)) == null) {
          throw new ArchiveException(
              "archive temporal changeset missing for history txNum " + txNum);
        }
        it.next();
      }
    } catch (RocksDBException e) {
      throw new ArchiveException("archive temporal history validation failed", e);
    }
    try (RocksIterator it = db.newIterator()) {
      it.seek(new byte[] {ArchiveTemporalCodec.CHANGESET_PREFIX});
      while (it.isValid() && it.key()[0] == ArchiveTemporalCodec.CHANGESET_PREFIX) {
        byte[] changesetKey = it.key();
        long txNum = ArchiveTemporalCodec.txNumOfChangeset(changesetKey);
        if (!hasCommittedTxNum.test(txNum)) {
          throw new ArchiveException(
              "archive temporal changeset txNum has no index position: " + txNum);
        }
        if (db.get(ArchiveTemporalCodec.historyKeyOfChangeset(changesetKey)) == null) {
          throw new ArchiveException(
              "archive temporal history missing for changeset txNum " + txNum);
        }
        it.next();
      }
    } catch (RocksDBException e) {
      throw new ArchiveException("archive temporal changeset validation failed", e);
    }
    try (RocksIterator it = db.newIterator()) {
      it.seek(new byte[] {ArchiveTemporalCodec.LATEST_PREFIX});
      while (it.isValid() && it.key()[0] == ArchiveTemporalCodec.LATEST_PREFIX) {
        byte[] latestKey = it.key();
        byte[] historyPrefix = ArchiveTemporalCodec.historyPrefixOfLatest(latestKey);
        try (RocksIterator history = db.newIterator()) {
          history.seek(historyPrefix);
          if (!history.isValid()
              || !ArchiveTemporalCodec.startsWith(history.key(), historyPrefix)) {
            throw new ArchiveException("archive temporal latest has no history row");
          }
        }
        it.next();
      }
    }
  }

  private static void putChange(WriteBatch batch, ArchiveChangeRecord record)
      throws RocksDBException {
    ArchiveDomain domain = record.getDomain();
    byte[] key = record.getCanonicalKey();
    byte[] prevValue = ArchiveTemporalCodec.encodeValue(record.getPrevValue());
    byte[] newValue = ArchiveTemporalCodec.encodeValue(record.getValue());
    // history stores the value BEFORE the change (Erigon prev-value); latest the value AFTER.
    batch.put(ArchiveTemporalCodec.latestKey(domain, key), newValue);
    batch.put(ArchiveTemporalCodec.historyKey(domain, key, record.getTxNum()), prevValue);
    // changeset marker (txNum-ordered) so unwind can find this change without a full scan.
    batch.put(ArchiveTemporalCodec.changesetKey(record.getTxNum(), domain, key), new byte[0]);
  }

  @Override
  public Optional<DomainValue> getAsOf(ArchiveDomain domain, byte[] canonicalKey, long txNum) {
    // The first change strictly after txNum; its pre-value is the value as of txNum (end of txNum).
    if (txNum != Long.MAX_VALUE) {
      byte[] prefix = ArchiveTemporalCodec.historyPrefix(domain, canonicalKey);
      byte[] seek = ArchiveTemporalCodec.historyKey(domain, canonicalKey, txNum + 1);
      try (RocksIterator it = db.newIterator()) {
        it.seek(seek);
        if (it.isValid() && ArchiveTemporalCodec.startsWith(it.key(), prefix)) {
          return Optional.of(ArchiveTemporalCodec.decodeValue(it.value()));
        }
      }
    }
    // No change after txNum: the key has not changed since, so its value then == latest.
    return latest(domain, canonicalKey);
  }

  @Override
  public Optional<DomainValue> latest(ArchiveDomain domain, byte[] canonicalKey) {
    try {
      byte[] value = db.get(ArchiveTemporalCodec.latestKey(domain, canonicalKey));
      return (value == null)
          ? Optional.empty()
          : Optional.of(ArchiveTemporalCodec.decodeValue(value));
    } catch (RocksDBException e) {
      throw new ArchiveException("archive temporal latest failed", e);
    }
  }

  @Override
  public void unwind(long fromTxNum) {
    unwind(fromTxNum, Long.MAX_VALUE, 0L, false);
  }

  @Override
  public void unwindBlock(ArchiveBlockRange range) {
    validateCommittedBlock(range);
    unwind(range.getFirstTxNum(), range.getLastTxNum(), range.getBlockNum(), true);
  }

  private void unwind(long fromTxNum, long toTxNum, long blockNum, boolean deleteBlockMarker) {
    // One atomic batch: delete each reverted change's history + changeset entry, and reset each
    // affected key's latest to the prevValue of its SMALLEST dropped change only when older history
    // remains. If this was the first canonical capture for the key, delete latest too; otherwise a
    // restart would see an unanchored latest-only row.
    Map<WrappedByteArray, byte[]> affectedPrefix = new LinkedHashMap<>();
    Map<WrappedByteArray, byte[]> restore = new HashMap<>();
    try (WriteBatch batch = new WriteBatch();
         WriteOptions writeOptions = ArchiveRocksDbWriteOptions.create()) {
      try (RocksIterator it = db.newIterator()) {
        it.seek(ArchiveTemporalCodec.changesetSeekFrom(fromTxNum));
        while (it.isValid() && it.key()[0] == ArchiveTemporalCodec.CHANGESET_PREFIX) {
          byte[] changesetKey = it.key();
          long txNum = ArchiveTemporalCodec.txNumOfChangeset(changesetKey);
          if (txNum > toTxNum) {
            break;
          }
          byte[] historyKey = ArchiveTemporalCodec.historyKeyOfChangeset(changesetKey);
          byte[] historyPrefix = ArchiveTemporalCodec.historyPrefixOfChangeset(changesetKey);
          WrappedByteArray prefix = WrappedByteArray.of(historyPrefix);
          if (!affectedPrefix.containsKey(prefix)) {
            // ascending changeset scan -> first hit is the smallest dropped txNum for this key.
            affectedPrefix.put(prefix, historyPrefix);
            byte[] prevValue = db.get(historyKey);
            if (prevValue == null) {
              throw new ArchiveException("archive temporal history missing for unwind txNum "
                  + ArchiveTemporalCodec.txNumOfChangeset(changesetKey));
            }
            restore.put(prefix, prevValue); // its prevValue, read before the delete lands
          }
          batch.delete(historyKey);
          batch.delete(changesetKey);
          it.next();
        }
      }
      for (Map.Entry<WrappedByteArray, byte[]> e : affectedPrefix.entrySet()) {
        byte[] latestKey = e.getValue().clone();
        latestKey[0] = ArchiveTemporalCodec.LATEST_PREFIX;
        if (hasHistoryBefore(e.getValue(), fromTxNum)) {
          batch.put(latestKey, restore.get(e.getKey()));
        } else {
          batch.delete(latestKey);
        }
      }
      if (fromTxNum == 0) {
        deleteAllLatest(batch);
      }
      if (deleteBlockMarker) {
        batch.delete(ArchiveTemporalCodec.blockCommitKey(blockNum));
      }
      db.write(writeOptions, batch);
    } catch (RocksDBException e) {
      throw new ArchiveException("archive temporal unwind failed", e);
    }
  }

  private void deleteAllLatest(WriteBatch batch) throws RocksDBException {
    try (RocksIterator it = db.newIterator()) {
      it.seek(new byte[] {ArchiveTemporalCodec.LATEST_PREFIX});
      while (it.isValid() && it.key()[0] == ArchiveTemporalCodec.LATEST_PREFIX) {
        batch.delete(it.key().clone());
        it.next();
      }
    }
  }

  private boolean hasHistoryBefore(byte[] historyPrefix, long txNum) {
    try (RocksIterator it = db.newIterator()) {
      it.seek(historyPrefix);
      while (it.isValid() && ArchiveTemporalCodec.startsWith(it.key(), historyPrefix)) {
        long historyTxNum = ArchiveTemporalCodec.txNumOfHistory(it.key());
        if (historyTxNum >= txNum) {
          return false;
        }
        return true;
      }
      return false;
    }
  }

  private static void validateRecordInRange(ArchiveBlockRange range, ArchiveChangeRecord record) {
    long txNum = record.getTxNum();
    if (txNum < range.getFirstTxNum() || txNum > range.getLastTxNum()) {
      throw new ArchiveException("archive temporal change txNum " + txNum
          + " is outside committed block range " + range.getBlockNum());
    }
    if (record.getPosition().getBlockNum() != range.getBlockNum()
        || record.getPosition().getSource() != range.getSource()) {
      throw new ArchiveException("archive temporal change position does not match block range "
          + range.getBlockNum());
    }
  }

  @Override
  public void close() {
    db.close();
    options.close();
  }
}
