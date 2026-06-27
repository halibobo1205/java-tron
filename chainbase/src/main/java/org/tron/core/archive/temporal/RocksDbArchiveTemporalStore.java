package org.tron.core.archive.temporal;

import com.google.common.primitives.Bytes;
import java.util.Arrays;
import java.util.HashMap;
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
import org.tron.core.db2.common.WrappedByteArray;

/**
 * RocksDB-backed {@link ArchiveTemporalStore}. A single column family holds both the latest record
 * and the txNum-versioned history, distinguished by the {@link ArchiveTemporalCodec} family prefix;
 * {@code getAsOf} is a {@code seekForPrev} within a (domain, key) history prefix and {@code latest}
 * a direct get. Single column family + plain KV keeps it compatible with the RocksDB shipped for
 * both architectures (5.15.10 / 9.7.4); decision-5 column-family / BlobDB tuning is a follow-on.
 */
public final class RocksDbArchiveTemporalStore implements ArchiveTemporalStore, AutoCloseable {

  static {
    RocksDB.loadLibrary();
  }

  private static final byte[] EMPTY = new byte[0];
  private static final byte[] MAX_TXNUM_SUFFIX = {
      (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
      (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff};

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
    ArchiveDomain domain = record.getDomain();
    byte[] key = record.getCanonicalKey();
    byte[] value = ArchiveTemporalCodec.encodeValue(record.getValue());
    try (WriteBatch batch = new WriteBatch(); WriteOptions writeOptions = new WriteOptions()) {
      batch.put(ArchiveTemporalCodec.latestKey(domain, key), value);
      batch.put(ArchiveTemporalCodec.historyKey(domain, key, record.getTxNum()), value);
      // changeset marker (txNum-ordered) so unwind can find this change without a full scan.
      batch.put(ArchiveTemporalCodec.changesetKey(record.getTxNum(), domain, key), EMPTY);
      db.write(writeOptions, batch);
    } catch (RocksDBException e) {
      throw new ArchiveException("archive temporal putChange failed", e);
    }
  }

  @Override
  public Optional<DomainValue> getAsOf(ArchiveDomain domain, byte[] canonicalKey, long txNum) {
    byte[] prefix = ArchiveTemporalCodec.historyPrefix(domain, canonicalKey);
    byte[] seek = ArchiveTemporalCodec.historyKey(domain, canonicalKey, txNum);
    try (RocksIterator it = db.newIterator()) {
      it.seekForPrev(seek);
      if (it.isValid() && ArchiveTemporalCodec.startsWith(it.key(), prefix)) {
        return Optional.of(ArchiveTemporalCodec.decodeValue(it.value()));
      }
      return Optional.empty();
    }
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
    // Phase 1: walk the txNum-ordered changeset from fromTxNum, deleting each change's history and
    // changeset entry; collect the affected (deduplicated) history prefixes.
    Map<WrappedByteArray, byte[]> affected = new HashMap<>();
    try (WriteOptions writeOptions = new WriteOptions()) {
      try (WriteBatch deletions = new WriteBatch();
          RocksIterator it = db.newIterator()) {
        it.seek(ArchiveTemporalCodec.changesetSeekFrom(fromTxNum));
        while (it.isValid() && it.key()[0] == ArchiveTemporalCodec.CHANGESET_PREFIX) {
          byte[] changesetKey = it.key();
          deletions.delete(ArchiveTemporalCodec.historyKeyOfChangeset(changesetKey));
          deletions.delete(changesetKey);
          byte[] historyPrefix = ArchiveTemporalCodec.historyPrefixOfChangeset(changesetKey);
          affected.put(WrappedByteArray.of(historyPrefix), historyPrefix);
          it.next();
        }
        db.write(writeOptions, deletions);
      }
      // Phase 2: restore each affected key's latest to its largest remaining (older) history entry.
      try (WriteBatch latestUpdates = new WriteBatch()) {
        for (byte[] historyPrefix : affected.values()) {
          byte[] latestKey = historyPrefix.clone();
          latestKey[0] = ArchiveTemporalCodec.LATEST_PREFIX;
          try (RocksIterator it = db.newIterator()) {
            it.seekForPrev(Bytes.concat(historyPrefix, MAX_TXNUM_SUFFIX));
            if (it.isValid() && ArchiveTemporalCodec.startsWith(it.key(), historyPrefix)) {
              latestUpdates.put(latestKey, it.value());
            } else {
              latestUpdates.delete(latestKey);
            }
          }
        }
        db.write(writeOptions, latestUpdates);
      }
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
