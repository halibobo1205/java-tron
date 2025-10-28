package org.tron.plugins.utils.db;

import com.google.common.collect.Streams;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.Getter;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDBException;

public class RocksDBImpl implements DBInterface {

  private org.rocksdb.RocksDB rocksDB;

  @Getter
  private final String name;
  private final AtomicBoolean closed = new AtomicBoolean(false);

  public RocksDBImpl(org.rocksdb.RocksDB rocksDB, String name) {
    this.rocksDB = rocksDB;
    this.name = name;
  }

  @Override
  public byte[] get(byte[] key) {
    throwIfClosed();
    try {
      return rocksDB.get(key);
    } catch (RocksDBException e) {
      throw new RuntimeException(name, e);
    }
  }

  @Override
  public void put(byte[] key, byte[] value) {
    throwIfClosed();
    try {
      rocksDB.put(key, value);
    } catch (RocksDBException e) {
      throw new RuntimeException(name, e);
    }
  }

  @Override
  public void delete(byte[] key) {
    throwIfClosed();
    try {
      rocksDB.delete(key);
    } catch (RocksDBException e) {
      throw new RuntimeException(name, e);
    }
  }

  @Override
  public DBIterator iterator() {
    throwIfClosed();
    ReadOptions readOptions = new ReadOptions().setFillCache(false);
    return new RockDBIterator(rocksDB.newIterator(readOptions), readOptions);
  }

  @Override
  public long size() throws IOException {
    throwIfClosed();
    try (DBIterator iterator = this.iterator()) {
      iterator.seekToFirst();
      return Streams.stream(iterator).count();
    }
  }

  @Override
  public void close() throws IOException {
    if (closed.compareAndSet(false, true)) {
      rocksDB.close();
    }
  }

  private void throwIfClosed() {
    if (closed.get()) {
      throw new IllegalStateException("db " + name + " has been closed");
    }
  }
}
