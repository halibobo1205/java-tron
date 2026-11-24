package org.tron.core.db.common.iterator;

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksIterator;
import org.tron.common.error.TronDBException;

public class IteratorGuardTest {

  private static final byte[] KEY = {1};

  private final ReentrantReadWriteLock dbLock = new ReentrantReadWriteLock();
  private final IteratorGuard guard = new IteratorGuard(() -> "test", dbLock);
  private final List<String> events = Collections.synchronizedList(new ArrayList<>());
  private final ExecutorService threads = Executors.newCachedThreadPool();
  private final CountDownLatch entered = new CountDownLatch(1);
  private final CountDownLatch release = new CountDownLatch(1);

  @After
  public void tearDown() {
    release.countDown();
    threads.shutdownNow();
  }

  @Test(timeout = 30000)
  public void closeDbWaitsForACallInProgress() throws Exception {
    org.iq80.leveldb.DBIterator nativeIterator = levelDbIterator();
    when(nativeIterator.hasNext()).thenAnswer(invocation -> {
      blockUntilReleased();
      events.add("native call returned");
      return true;
    });
    DBIterator iterator = guard.openLevelDb(() -> nativeIterator);

    Future<Boolean> call = threads.submit(iterator::hasNext);
    Assert.assertTrue(entered.await(10, TimeUnit.SECONDS));
    Future<?> closeDb = threads.submit(this::closeDb);
    assertStillRunning(closeDb);

    release.countDown();
    Assert.assertTrue(call.get(10, TimeUnit.SECONDS));
    closeDb.get(10, TimeUnit.SECONDS);

    Assert.assertEquals(Arrays.asList("native call returned", "native close", "db closed"), events);
    Assert.assertThrows(TronDBException.class, iterator::hasNext);
    verify(nativeIterator, times(1)).hasNext();
  }

  @Test(timeout = 30000)
  public void closeDbWaitsForACloseInProgress() throws Exception {
    org.iq80.leveldb.DBIterator nativeIterator = mock(org.iq80.leveldb.DBIterator.class);
    doAnswer(invocation -> {
      blockUntilReleased();
      events.add("native close");
      return null;
    }).when(nativeIterator).close();
    DBIterator iterator = guard.openLevelDb(() -> nativeIterator);

    Future<?> close = threads.submit(() -> {
      iterator.close();
      return null;
    });
    Assert.assertTrue(entered.await(10, TimeUnit.SECONDS));
    Future<?> closeDb = threads.submit(this::closeDb);
    assertStillRunning(closeDb);

    release.countDown();
    close.get(10, TimeUnit.SECONDS);
    closeDb.get(10, TimeUnit.SECONDS);

    Assert.assertEquals(Arrays.asList("native close", "db closed"), events);
    verify(nativeIterator, times(1)).close();
  }

  @Test(timeout = 30000)
  public void closeWaitsForACallOnAnotherThread() throws Exception {
    org.iq80.leveldb.DBIterator nativeIterator = levelDbIterator();
    when(nativeIterator.next()).thenAnswer(invocation -> {
      blockUntilReleased();
      events.add("native call returned");
      return new SimpleImmutableEntry<>(KEY, KEY);
    });
    DBIterator iterator = guard.openLevelDb(() -> nativeIterator);

    Future<?> call = threads.submit(iterator::next);
    Assert.assertTrue(entered.await(10, TimeUnit.SECONDS));
    Future<?> close = threads.submit(() -> {
      iterator.close();
      return null;
    });
    assertStillRunning(close);

    release.countDown();
    call.get(10, TimeUnit.SECONDS);
    close.get(10, TimeUnit.SECONDS);

    Assert.assertEquals(Arrays.asList("native call returned", "native close"), events);
  }

  @Test(timeout = 30000)
  public void openWaitsForCloseDb() throws Exception {
    org.iq80.leveldb.DBIterator nativeIterator = levelDbIterator();
    AtomicInteger created = new AtomicInteger();
    Future<DBIterator> opened;
    dbLock.writeLock().lock();
    try {
      opened = threads.submit(() -> guard.openLevelDb(() -> {
        created.incrementAndGet();
        return nativeIterator;
      }));
      assertStillRunning(opened);
      Assert.assertEquals(0, created.get());
    } finally {
      dbLock.writeLock().unlock();
    }

    Assert.assertNotNull(opened.get(10, TimeUnit.SECONDS));
    Assert.assertEquals(1, created.get());
  }

  @Test
  public void callsAfterCloseDbNeverReachTheNativeIterator() throws Exception {
    org.iq80.leveldb.DBIterator nativeIterator = levelDbIterator();
    DBIterator iterator = guard.openLevelDb(() -> nativeIterator);

    closeDb();

    Assert.assertThrows(TronDBException.class, iterator::hasNext);
    Assert.assertThrows(TronDBException.class, iterator::next);
    Assert.assertThrows(TronDBException.class, () -> iterator.seek(KEY));
    Assert.assertThrows(TronDBException.class, iterator::seekToFirst);
    Assert.assertThrows(TronDBException.class, iterator::seekToLast);
    Assert.assertThrows(TronDBException.class, iterator::valid);
    Assert.assertThrows(TronDBException.class, iterator::getKey);
    Assert.assertThrows(TronDBException.class, iterator::getValue);
    Assert.assertThrows(TronDBException.class, iterator::checkState);
    iterator.close();
    verify(nativeIterator, times(1)).close();
    verifyNoMoreInteractions(nativeIterator);
  }

  @Test
  public void finishedIteratorIsNotInvalidated() throws Exception {
    org.iq80.leveldb.DBIterator nativeIterator = levelDbIterator();
    DBIterator iterator = guard.openLevelDb(() -> nativeIterator);
    Assert.assertFalse(iterator.hasNext());

    closeDb();

    Assert.assertFalse(iterator.hasNext());
    Assert.assertThrows(IllegalStateException.class, iterator::checkState);
    verify(nativeIterator, times(1)).close();
  }

  @Test
  public void invalidateAllNeedsTheWriteLock() throws Exception {
    org.iq80.leveldb.DBIterator nativeIterator = levelDbIterator();
    guard.openLevelDb(() -> nativeIterator);

    Assert.assertThrows(IllegalStateException.class, guard::invalidateAll);
    verify(nativeIterator, never()).close();
  }

  @Test
  public void closeDbReleasesRocksDbIteratorAndReadOptions() throws Exception {
    RocksIterator nativeIterator = mock(RocksIterator.class);
    ReadOptions readOptions = mock(ReadOptions.class);
    DBIterator iterator = guard.openRocksDb(() -> readOptions, options -> nativeIterator);

    closeDb();

    verify(nativeIterator, times(1)).close();
    verify(readOptions, times(1)).close();
    Assert.assertThrows(TronDBException.class, iterator::hasNext);
  }

  @Test
  public void failedRocksDbOpenClosesReadOptions() {
    ReadOptions readOptions = mock(ReadOptions.class);

    Assert.assertThrows(TronDBException.class, () -> guard.openRocksDb(() -> readOptions,
        options -> {
          throw new TronDBException("DB test is closed.");
        }));
    verify(readOptions, times(1)).close();
  }

  private org.iq80.leveldb.DBIterator levelDbIterator() throws IOException {
    org.iq80.leveldb.DBIterator nativeIterator = mock(org.iq80.leveldb.DBIterator.class);
    doAnswer(invocation -> events.add("native close")).when(nativeIterator).close();
    return nativeIterator;
  }

  private void closeDb() {
    dbLock.writeLock().lock();
    try {
      guard.invalidateAll();
      events.add("db closed");
    } finally {
      dbLock.writeLock().unlock();
    }
  }

  private void blockUntilReleased() throws InterruptedException {
    entered.countDown();
    release.await();
  }

  private static void assertStillRunning(Future<?> future) {
    Assert.assertThrows(TimeoutException.class,
        () -> future.get(300, TimeUnit.MILLISECONDS));
  }
}
