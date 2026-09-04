package org.tron.common.storage.leveldb;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.iq80.leveldb.ReadOptions;
import org.junit.Test;
import org.tron.common.utils.ReflectUtils;
import org.tron.core.archive.query.ArchiveQueryLimits;
import org.tron.core.archive.query.HistoricalQueryLimitException;
import org.tron.core.archive.query.QueryContext;
import org.tron.core.archive.query.QueryContextHolder;

public class LevelDbDataSourceImplCachelessTest {

  @Test
  public void historicalPointReadDisablesBlockCacheAdmission() {
    byte[] key = "cacheless-key".getBytes(StandardCharsets.US_ASCII);
    byte[] value = "cacheless-value".getBytes(StandardCharsets.US_ASCII);
    LevelDbDataSourceImpl dataSource = new LevelDbDataSourceImpl();
    org.iq80.leveldb.DB database = mock(org.iq80.leveldb.DB.class);
    ReflectUtils.setFieldValue(dataSource, "database", database);
    when(database.get(same(key), any(ReadOptions.class))).thenAnswer(invocation -> {
      ReadOptions options = invocation.getArgument(1);
      assertFalse(options.fillCache());
      return value;
    });

    assertArrayEquals(value, dataSource.getDataWithoutCache(key));
  }

  @Test
  public void historicalPointReadDeadlineBoundsResetLockWait() throws Exception {
    LevelDbDataSourceImpl dataSource = new LevelDbDataSourceImpl();
    ReentrantReadWriteLock resetLock = new ReentrantReadWriteLock();
    ReflectUtils.setFieldValue(dataSource, "resetDbLock", resetLock);
    resetLock.writeLock().lock();
    FutureTask<HistoricalQueryLimitException> read = new FutureTask<>(() -> {
      QueryContext context = new QueryContext(
          ArchiveQueryLimits.builder().deadlineMs(20L).build());
      try (QueryContextHolder.Scope ignored = QueryContextHolder.attach(context)) {
        return assertThrows(HistoricalQueryLimitException.class,
            () -> dataSource.getDataWithoutCache(new byte[] {1}));
      }
    });
    Thread reader = new Thread(read, "leveldb-cacheless-lock-deadline-test");
    try {
      reader.start();
      HistoricalQueryLimitException failure = read.get(1L, TimeUnit.SECONDS);
      assertEquals(HistoricalQueryLimitException.Reason.DEADLINE, failure.getReason());
    } finally {
      resetLock.writeLock().unlock();
      reader.join(1_000L);
    }
  }

  @Test
  public void historicalPointReadRechecksDeadlineAfterNativeReturn() throws Exception {
    LevelDbDataSourceImpl dataSource = new LevelDbDataSourceImpl();
    org.iq80.leveldb.DB database = mock(org.iq80.leveldb.DB.class);
    ReflectUtils.setFieldValue(dataSource, "database", database);
    when(database.get(any(byte[].class), any(ReadOptions.class))).thenAnswer(invocation -> {
      Thread.sleep(30L);
      return null;
    });
    QueryContext context = new QueryContext(
        ArchiveQueryLimits.builder().deadlineMs(5L).build());

    try (QueryContextHolder.Scope ignored = QueryContextHolder.attach(context);
        QueryContextHolder.Scope suspended = QueryContextHolder.suspend()) {
      assertNull(QueryContextHolder.current());
      HistoricalQueryLimitException failure = assertThrows(
          HistoricalQueryLimitException.class,
          () -> dataSource.getDataWithoutCache(new byte[] {1}));
      assertEquals(HistoricalQueryLimitException.Reason.DEADLINE, failure.getReason());
    }
  }
}
