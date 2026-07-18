package org.tron.core.archive.unified;

import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.ArchiveRocksIterators;
import org.tron.core.archive.query.QueryContext;
import org.tron.core.archive.query.QueryContextHolder;

/** Owner-thread wrapper around one native iterator belonging to a unified read view. */
public final class UnifiedArchiveIterator implements AutoCloseable {

  private final RocksIterator delegate;
  private final Thread owner = Thread.currentThread();
  private boolean closed;
  private Throwable closeFailure;

  UnifiedArchiveIterator(RocksIterator delegate) {
    if (delegate == null) {
      throw new NullPointerException("delegate");
    }
    this.delegate = delegate;
  }

  public boolean isValid() {
    requireOwnerAndOpen();
    return delegate.isValid();
  }

  public void seekToFirst() {
    requireOwnerAndOpen();
    QueryContext queryContext = beforeRead();
    delegate.seekToFirst();
    afterRead(queryContext, "seekToFirst");
  }

  public void seekToLast() {
    requireOwnerAndOpen();
    QueryContext queryContext = beforeRead();
    delegate.seekToLast();
    afterRead(queryContext, "seekToLast");
  }

  public void seek(byte[] target) {
    requireOwnerAndOpen();
    QueryContext queryContext = beforeRead();
    delegate.seek(target);
    afterRead(queryContext, "seek");
  }

  public void seekForPrev(byte[] target) {
    requireOwnerAndOpen();
    QueryContext queryContext = beforeRead();
    delegate.seekForPrev(target);
    afterRead(queryContext, "seekForPrev");
  }

  public void next() {
    requireOwnerAndOpen();
    QueryContext queryContext = beforeRead();
    delegate.next();
    afterRead(queryContext, "next");
  }

  public void prev() {
    requireOwnerAndOpen();
    QueryContext queryContext = beforeRead();
    delegate.prev();
    afterRead(queryContext, "prev");
  }

  public byte[] key() {
    requireOwnerAndOpen();
    return delegate.key();
  }

  public byte[] value() {
    requireOwnerAndOpen();
    return delegate.value();
  }

  public void status() throws RocksDBException {
    requireOwnerAndOpen();
    delegate.status();
  }

  @Override
  public void close() {
    requireOwner();
    if (closed) {
      rethrowCloseFailure(closeFailure);
      return;
    }
    closed = true;
    try {
      delegate.close();
    } catch (Throwable failure) {
      closeFailure = failure;
      rethrowCloseFailure(failure);
    }
  }

  private void requireOwnerAndOpen() {
    requireOwner();
    if (closed) {
      throw new ArchiveException("UNIFIED_V1 iterator is closed");
    }
  }

  private void requireOwner() {
    if (Thread.currentThread() != owner) {
      throw new ArchiveException("UNIFIED_V1 iterator used from a non-owner thread");
    }
  }

  private static QueryContext beforeRead() {
    QueryContext queryContext = QueryContextHolder.current();
    if (queryContext != null) {
      queryContext.recordBackendRead();
    }
    return queryContext;
  }

  private void afterRead(QueryContext queryContext, String operation) {
    if (queryContext == null || !queryContext.isTerminated()) {
      return;
    }
    try {
      delegate.status();
    } catch (RocksDBException nativeFailure) {
      ArchiveRocksIterators.rethrowIfNativeDeadline(nativeFailure, queryContext);
      throw new ArchiveException(
          "archive rocksdb iterator error after " + operation, nativeFailure);
    }
    queryContext.checkDeadline();
  }

  private static void rethrowCloseFailure(Throwable failure) {
    if (failure instanceof RuntimeException) {
      throw (RuntimeException) failure;
    }
    if (failure instanceof Error) {
      throw (Error) failure;
    }
    if (failure != null) {
      throw new ArchiveException("UNIFIED_V1 iterator close failed", failure);
    }
  }
}
