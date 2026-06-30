package org.tron.core.archive.temporal;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.tron.core.archive.ArchiveException;
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
    try {
      this.db = RocksDB.open(options, path);
    } catch (RocksDBException e) {
      options.close();
      throw new ArchiveException("failed to open archive temporal store at " + path, e);
    }
  }

  @Override
  public void putChange(ArchiveChangeRecord record) {
    try (WriteBatch batch = new WriteBatch(); WriteOptions writeOptions = new WriteOptions()) {
      putChange(batch, record);
      db.write(writeOptions, batch);
    } catch (RocksDBException e) {
      throw new ArchiveException("archive temporal putChange failed", e);
    }
  }

  @Override
  public void putChanges(List<ArchiveChangeRecord> records) {
    try (WriteBatch batch = new WriteBatch(); WriteOptions writeOptions = new WriteOptions()) {
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
    try (WriteBatch batch = new WriteBatch(); WriteOptions writeOptions = new WriteOptions()) {
      for (ArchiveChangeRecord record : records) {
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
    // One atomic batch: delete each reverted change's history + changeset entry, and reset each
    // affected key's latest to the prevValue of its SMALLEST dropped change (= the key's value at
    // the end of fromTxNum-1), computed against the pre-deletion state so a crash can never leave
    // latest pointing at a deleted entry.
    Map<WrappedByteArray, byte[]> affectedPrefix = new LinkedHashMap<>();
    Map<WrappedByteArray, byte[]> restore = new HashMap<>();
    try (WriteBatch batch = new WriteBatch(); WriteOptions writeOptions = new WriteOptions()) {
      try (RocksIterator it = db.newIterator()) {
        it.seek(ArchiveTemporalCodec.changesetSeekFrom(fromTxNum));
        while (it.isValid() && it.key()[0] == ArchiveTemporalCodec.CHANGESET_PREFIX) {
          byte[] changesetKey = it.key();
          byte[] historyKey = ArchiveTemporalCodec.historyKeyOfChangeset(changesetKey);
          byte[] historyPrefix = ArchiveTemporalCodec.historyPrefixOfChangeset(changesetKey);
          WrappedByteArray prefix = WrappedByteArray.of(historyPrefix);
          if (!affectedPrefix.containsKey(prefix)) {
            // ascending changeset scan -> first hit is the smallest dropped txNum for this key.
            affectedPrefix.put(prefix, historyPrefix);
            restore.put(prefix, db.get(historyKey)); // its prevValue, read before the delete lands
          }
          batch.delete(historyKey);
          batch.delete(changesetKey);
          it.next();
        }
      }
      for (Map.Entry<WrappedByteArray, byte[]> e : affectedPrefix.entrySet()) {
        byte[] latestKey = e.getValue().clone();
        latestKey[0] = ArchiveTemporalCodec.LATEST_PREFIX;
        byte[] prevValue = restore.get(e.getKey());
        if (prevValue == null) {
          batch.delete(latestKey); // defensive: dropped change had no history entry
        } else {
          batch.put(latestKey, prevValue);
        }
      }
      db.write(writeOptions, batch);
    } catch (RocksDBException e) {
      throw new ArchiveException("archive temporal unwind failed", e);
    }
  }

  @Override
  public void close() {
    db.close();
    options.close();
  }
}
