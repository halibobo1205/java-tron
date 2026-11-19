package org.tron.core.db.common.iterator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.Supplier;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksIterator;
import org.tron.common.error.TronDBException;

/**
 * Keeps the iterators of one data source from touching its native DB after it is closed.
 *
 * <p>Iterators are created under the data source's read lock and registered here; each one
 * serializes its own calls on its monitor. {@code closeDB()} holds the write lock and calls
 * {@link #invalidateAll()} before closing the DB, which takes each registered iterator's monitor,
 * so it waits for a call in progress, and releases the native iterator. Any later call throws
 * {@link TronDBException}. An iterator that was dropped without being closed is no longer
 * tracked once it has been garbage collected.
 */
public final class IteratorGuard {

  private final Supplier<String> dbName;
  private final ReentrantReadWriteLock dbLock;
  private final Set<GuardedDbIterator> openIterators =
      Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

  public IteratorGuard(Supplier<String> dbName, ReentrantReadWriteLock dbLock) {
    this.dbName = dbName;
    this.dbLock = dbLock;
  }

  public DBIterator openLevelDb(Supplier<org.iq80.leveldb.DBIterator> nativeIterator) {
    return open(() -> new StoreIterator(nativeIterator.get(), this));
  }

  public DBIterator openRocksDb(Supplier<ReadOptions> readOptions,
      Function<ReadOptions, RocksIterator> nativeIterator) {
    return open(() -> {
      ReadOptions options = readOptions.get();
      try {
        return new RockStoreIterator(nativeIterator.apply(options), options, this);
      } catch (RuntimeException e) {
        options.close();
        throw e;
      }
    });
  }

  /**
   * Releases every registered iterator. Must be called with the data source's write lock held,
   * before the native DB is closed.
   */
  public void invalidateAll() {
    if (!dbLock.isWriteLockedByCurrentThread()) {
      throw new IllegalStateException("invalidateAll() needs the DB write lock");
    }
    List<GuardedDbIterator> iterators;
    synchronized (openIterators) {
      iterators = new ArrayList<>(openIterators);
      openIterators.clear();
    }
    iterators.forEach(GuardedDbIterator::invalidate);
  }

  void unregister(GuardedDbIterator iterator) {
    openIterators.remove(iterator);
  }

  TronDBException closedException() {
    return new TronDBException("DB " + dbName.get() + " is closed.");
  }

  private <T extends GuardedDbIterator> T open(Supplier<T> factory) {
    dbLock.readLock().lock();
    try {
      T iterator = factory.get();
      openIterators.add(iterator);
      return iterator;
    } finally {
      dbLock.readLock().unlock();
    }
  }
}
