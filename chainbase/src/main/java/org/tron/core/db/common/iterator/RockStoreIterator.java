package org.tron.core.db.common.iterator;

import java.io.IOException;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksIterator;


@Slf4j(topic = "DB")
public final class RockStoreIterator implements DBIterator {

  private final RocksIterator dbIterator;
  private final boolean failOnReadError;
  private boolean first = true;

  private final AtomicBoolean close = new AtomicBoolean(false);
  private final ReadOptions readOptions;

  public RockStoreIterator(RocksIterator dbIterator, ReadOptions readOptions) {
    this(dbIterator, readOptions, false);
  }

  public RockStoreIterator(RocksIterator dbIterator, ReadOptions readOptions,
      boolean failOnReadError) {
    this.readOptions = readOptions;
    this.dbIterator = dbIterator;
    this.failOnReadError = failOnReadError;
  }

  @Override
  public void close() throws IOException {
    if (close.compareAndSet(false, true)) {
      readOptions.close();
      dbIterator.close();
    }
  }

  @Override
  public boolean hasNext() {
    if (close.get()) {
      return false;
    }
    boolean hasNext = false;
    // true is first item
    try {
      if (first) {
        dbIterator.seekToFirst();
        first = false;
      }
      if (!(hasNext = dbIterator.isValid())) { // false is last item
        if (failOnReadError) {
          dbIterator.status();
        }
        close();
      }
    } catch (Exception e) {
      RuntimeException strictFailure = new IllegalStateException(
          "rocksdb iterator failed while reading", e);
      try {
        close();
      } catch (Exception e1) {
        strictFailure.addSuppressed(e1);
      }
      if (failOnReadError) {
        throw strictFailure;
      }
      logger.error(e.getMessage(), e);
    }
    return hasNext;
  }

  @Override
  public Entry<byte[], byte[]> next() {
    if (close.get()) {
      throw new NoSuchElementException();
    }
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
  public void remove() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void seek(byte[] key) {
    checkState();
    dbIterator.seek(key);
    this.first = false;
  }

  @Override
  public void seekToFirst() {
    checkState();
    dbIterator.seekToFirst();
    this.first = false;
  }

  @Override
  public void seekToLast() {
    checkState();
    dbIterator.seekToLast();
    this.first = false;
  }

  @Override
  public boolean valid() {
    checkState();
    return dbIterator.isValid();
  }

  @Override
  public byte[] getKey() {
    checkValid();
    return dbIterator.key();
  }

  @Override
  public byte[] getValue() {
    checkValid();
    return dbIterator.value();
  }

  @Override
  public void checkState() {
    if (close.get()) {
      throw new IllegalStateException("iterator has been closed");
    }
  }
}
