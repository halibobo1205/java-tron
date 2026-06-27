package org.tron.core.archive.temporal;

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
  public void close() {
    db.close();
    options.close();
  }
}
