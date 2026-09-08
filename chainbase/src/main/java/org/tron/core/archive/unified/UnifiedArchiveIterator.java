package org.tron.core.archive.unified;

import java.util.Arrays;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.tron.common.math.StrictMathWrapper;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.ArchiveRocksIterators;
import org.tron.core.archive.query.QueryContext;
import org.tron.core.archive.query.QueryContextHolder;

/** Owner-thread wrapper around one native iterator belonging to a unified read view. */
public final class UnifiedArchiveIterator implements AutoCloseable {

  private static final int MAX_FIXED_VALUE_BYTES = 64 * 1024;

  private final RocksIterator delegate;
  private final Thread owner = Thread.currentThread();
  private byte[] boundedValueProbe = new byte[0];
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

  /** Reads a trusted fixed-size row without materializing an oversized corrupt native value. */
  public byte[] valueExact(int expectedBytes, String what) {
    requireOwnerAndOpen();
    if (expectedBytes < 0 || expectedBytes > MAX_FIXED_VALUE_BYTES) {
      throw new ArchiveException(what + " has invalid fixed byte length: " + expectedBytes);
    }
    QueryContext context = beforeRead();
    if (context != null) {
      context.validateBackendValueBytes(expectedBytes);
    }
    byte[] value = new byte[expectedBytes];
    int actualBytes = delegate.value(value);
    afterRead(context, "valueExact");
    if (actualBytes != expectedBytes) {
      throw new ArchiveException(what + " length mismatch: expectedBytes="
          + expectedBytes + ", actualBytes=" + actualBytes);
    }
    if (context != null) {
      context.recordBackendValueBytes(actualBytes);
    }
    return value;
  }

  /** Reads a payload through a capped probe before allocating from its persisted locator length. */
  byte[] valueExactBudgeted(int expectedBytes, String what) {
    requireOwnerAndOpen();
    if (expectedBytes < 0) {
      throw new ArchiveException(what + " has invalid expected byte length: " + expectedBytes);
    }
    QueryContext context = beforeRead();
    if (context != null) {
      context.validateBackendValueBytes(expectedBytes);
    }
    int probeBytes = StrictMathWrapper.min(expectedBytes, MAX_FIXED_VALUE_BYTES);
    if (boundedValueProbe.length < probeBytes) {
      boundedValueProbe = new byte[probeBytes];
    }
    int actualBytes = delegate.value(boundedValueProbe);
    afterRead(context, "valueExactBudgeted");
    if (actualBytes != expectedBytes) {
      throw new ArchiveException(what + " length mismatch: expectedBytes="
          + expectedBytes + ", actualBytes=" + actualBytes);
    }
    if (context != null) {
      context.recordBackendValueBytes(actualBytes);
    }
    if (actualBytes <= boundedValueProbe.length) {
      return Arrays.copyOf(boundedValueProbe, actualBytes);
    }
    beforeRead();
    byte[] value = new byte[actualBytes];
    int copiedBytes = delegate.value(value);
    afterRead(context, "valueExactBudgeted");
    if (copiedBytes != actualBytes) {
      throw new ArchiveException(what + " changed while reading snapshot: expectedBytes="
          + actualBytes + ", actualBytes=" + copiedBytes);
    }
    return value;
  }

  /** Reads a variable-size row through a small bounded probe, accounting its actual length. */
  public byte[] valueBounded(int minBytes, int maxBytes, String what) {
    requireOwnerAndOpen();
    if (minBytes < 0 || maxBytes < minBytes || maxBytes > MAX_FIXED_VALUE_BYTES) {
      throw new ArchiveException(what + " has invalid byte bounds: " + minBytes + ".." + maxBytes);
    }
    QueryContext context = beforeRead();
    if (boundedValueProbe.length < maxBytes) {
      boundedValueProbe = new byte[maxBytes];
    }
    int actualBytes = delegate.value(boundedValueProbe);
    afterRead(context, "valueBounded");
    if (actualBytes < minBytes || actualBytes > maxBytes) {
      throw new ArchiveException(what + " length outside bounds: minBytes=" + minBytes
          + ", maxBytes=" + maxBytes + ", actualBytes=" + actualBytes);
    }
    if (context != null) {
      context.recordBackendValueBytes(actualBytes);
    }
    return Arrays.copyOf(boundedValueProbe, actualBytes);
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
