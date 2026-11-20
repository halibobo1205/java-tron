package org.tron.core.db.common.iterator;

import java.io.IOException;
import java.util.Map.Entry;
import org.iq80.leveldb.DBIterator;

public final class StoreIterator extends GuardedDbIterator {

  private final DBIterator dbIterator;

  StoreIterator(DBIterator dbIterator, IteratorGuard guard) {
    super(guard);
    this.dbIterator = dbIterator;
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
    return dbIterator.hasNext();
  }

  @Override
  Entry<byte[], byte[]> nextNative() {
    return dbIterator.next();
  }

  @Override
  byte[] keyNative() {
    return dbIterator.peekNext().getKey();
  }

  @Override
  byte[] valueNative() {
    return dbIterator.peekNext().getValue();
  }

  @Override
  void closeNative() throws IOException {
    dbIterator.close();
  }
}
