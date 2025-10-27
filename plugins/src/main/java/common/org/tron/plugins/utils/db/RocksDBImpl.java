package org.tron.plugins.utils.db;

import com.google.common.collect.Streams;
import java.io.IOException;
import lombok.Getter;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDBException;

public class RocksDBImpl implements DBInterface {

  private org.rocksdb.RocksDB rocksDB;

  @Getter
  private final String name;

  public RocksDBImpl(org.rocksdb.RocksDB rocksDB, String name) {
    this.rocksDB = rocksDB;
    this.name = name;
  }

  @Override
  public byte[] get(byte[] key) {
    try {
      return rocksDB.get(key);
    } catch (RocksDBException e) {
      e.printStackTrace();
    }
    return null;
  }

  @Override
  public void put(byte[] key, byte[] value) {
    try {
      rocksDB.put(key, value);
    } catch (RocksDBException e) {
      e.printStackTrace();
    }
  }

  @Override
  public void delete(byte[] key) {
    try {
      rocksDB.delete(key);
    } catch (RocksDBException e) {
      e.printStackTrace();
    }
  }

  @Override
  public DBIterator iterator() {
    return new RockDBIterator(rocksDB.newIterator(new ReadOptions().setFillCache(false)));
  }

  @Override
  public long size() throws IOException {
    try (DBIterator iterator = this.iterator()) {
      iterator.seekToFirst();
      return Streams.stream(iterator).count();
    }
  }

  @Override
  public void close() throws IOException {
    rocksDB.close();
  }
}
