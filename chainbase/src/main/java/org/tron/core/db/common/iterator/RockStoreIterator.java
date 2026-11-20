package org.tron.core.db.common.iterator;

import java.util.Map.Entry;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksIterator;

public final class RockStoreIterator extends GuardedDbIterator {

  private final RocksIterator dbIterator;
  private final ReadOptions readOptions;

  RockStoreIterator(RocksIterator dbIterator, ReadOptions readOptions, IteratorGuard guard) {
    super(guard);
    this.dbIterator = dbIterator;
    this.readOptions = readOptions;
  }

  @Override
  void seekToFirstNative() {
    dbIterator.seekToFirst();
  }

  @Override
  void seekToLastNative() {
    dbIterator.seekToLast();
  }

  @Override
  void seekNative(byte[] key) {
    dbIterator.seek(key);
  }

  @Override
  boolean validNative() {
    return dbIterator.isValid();
  }

  @Override
  Entry<byte[], byte[]> nextNative() {
    byte[] key = dbIterator.key();
    byte[] value = dbIterator.value();
    dbIterator.next();
    return new Entry<byte[], byte[]>() {
      @Override
      public byte[] getKey() {
        return key;
      }

      @Override
      public byte[] getValue() {
        return value;
      }

      @Override
      public byte[] setValue(byte[] value) {
        throw new UnsupportedOperationException();
      }
    };
  }

  @Override
  byte[] keyNative() {
    return dbIterator.key();
  }

  @Override
  byte[] valueNative() {
    return dbIterator.value();
  }

  @Override
  void closeNative() {
    readOptions.close();
    dbIterator.close();
  }
}
