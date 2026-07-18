package org.tron.core.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import org.junit.Test;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.Status;
import org.tron.core.archive.query.ArchiveQueryLimits;
import org.tron.core.archive.query.HistoricalQueryLimitException;
import org.tron.core.archive.query.QueryContext;
import org.tron.core.archive.query.QueryContextHolder;

public class ArchiveRocksItersTest {

  @Test
  public void requireOkWrapsIteratorReadErrorAsFailStop() throws Exception {
    // An iterator that stopped because of a read error (RocksDBException from status()) must become
    // a fail-stop ArchiveException, not be swallowed -- otherwise it reads as "no such row".
    RocksIterator it = mock(RocksIterator.class);
    doThrow(new RocksDBException("simulated SST checksum failure")).when(it).status();

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> ArchiveRocksIterators.requireOk(it, "unit probe"));

    assertTrue(ex.getMessage().contains("iterator error"));
    assertTrue(ex.getCause() instanceof RocksDBException);
  }

  @Test
  public void requireOkIsNoopWhenIteratorHealthy() {
    // Happy path: status() returns normally (past-the-end, not an error), so requireOk must not
    // throw -- this is what keeps the check a no-op on every normal scan.
    RocksIterator it = mock(RocksIterator.class);

    ArchiveRocksIterators.requireOk(it, "unit probe");
  }

  @Test
  public void expiredDeadlineDoesNotMaskNativeIoError() throws Exception {
    RocksIterator it = mock(RocksIterator.class);
    RocksDBException nativeFailure = new RocksDBException(
        new Status(Status.Code.IOError, Status.SubCode.None, "injected I/O failure"));
    doThrow(nativeFailure).when(it).status();
    QueryContext context = new QueryContext(
        ArchiveQueryLimits.builder().deadlineMs(0L).build());

    try (QueryContextHolder.Scope ignored = QueryContextHolder.attach(context)) {
      ArchiveException failure = assertThrows(ArchiveException.class,
          () -> ArchiveRocksIterators.requireOk(it, "unit probe"));
      assertSame(nativeFailure, failure.getCause());
    }
  }

  @Test
  public void nativeTimeoutMapsToExpiredQueryDeadlineWithoutLosingCause() throws Exception {
    RocksIterator it = mock(RocksIterator.class);
    RocksDBException nativeFailure = new RocksDBException(
        new Status(Status.Code.TimedOut, Status.SubCode.None, "injected timeout"));
    doThrow(nativeFailure).when(it).status();
    QueryContext context = new QueryContext(
        ArchiveQueryLimits.builder().deadlineMs(0L).build());

    try (QueryContextHolder.Scope ignored = QueryContextHolder.attach(context)) {
      HistoricalQueryLimitException failure = assertThrows(
          HistoricalQueryLimitException.class,
          () -> ArchiveRocksIterators.requireOk(it, "unit probe"));
      assertEquals(HistoricalQueryLimitException.Limit.DEADLINE, failure.getLimit());
      assertEquals(1, failure.getSuppressed().length);
      assertSame(nativeFailure, failure.getSuppressed()[0]);
    }
  }

  @Test
  public void nativeTimeoutUsesExplicitStorageContextWhileExecutionContextIsSuspended()
      throws Exception {
    RocksDBException nativeFailure = new RocksDBException(
        new Status(Status.Code.TimedOut, Status.SubCode.None, "injected storage timeout"));
    QueryContext context = new QueryContext(
        ArchiveQueryLimits.builder().deadlineMs(0L).build());

    try (QueryContextHolder.Scope ignored = QueryContextHolder.attach(context);
        QueryContextHolder.Scope suspended = QueryContextHolder.suspend()) {
      assertNull(QueryContextHolder.current());
      HistoricalQueryLimitException failure = assertThrows(
          HistoricalQueryLimitException.class,
          () -> ArchiveRocksIterators.rethrowIfNativeDeadline(
              nativeFailure, QueryContextHolder.currentStorageContext()));
      assertEquals(HistoricalQueryLimitException.Limit.DEADLINE, failure.getLimit());
      assertSame(nativeFailure, failure.getSuppressed()[0]);
    }
  }
}
