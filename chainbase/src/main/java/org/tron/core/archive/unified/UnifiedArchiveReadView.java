package org.tron.core.archive.unified;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.Snapshot;
import org.tron.common.math.StrictMathWrapper;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.ArchiveRocksIterators;
import org.tron.core.archive.ArchiveSnapshotReleaseException;
import org.tron.core.archive.query.ArchiveSnapshotPermit.SnapshotUse;
import org.tron.core.archive.query.QueryContext;
import org.tron.core.archive.query.QueryContextHolder;

/** One RocksDB snapshot shared by meta, index, temporal, marker, and journal reads. */
public final class UnifiedArchiveReadView implements AutoCloseable {

  private static final byte[] EMPTY_VALUE_BUFFER = new byte[0];
  private static final int MAX_BOUNDED_GET_PROBE_BYTES = 64 * 1024;

  private final RocksDB db;
  private final EnumMap<UnifiedArchiveColumnFamily, ColumnFamilyHandle> handles;
  private final Snapshot snapshot;
  private final ReadOptions readOptions;
  private final Runnable releaseView;
  private final SnapshotUse snapshotUse;
  private final Thread ownerThread;
  private final List<UnifiedArchiveIterator> iterators = new ArrayList<>();
  private final AtomicBoolean closed = new AtomicBoolean();
  private boolean closeStarted;
  private boolean closePreparationComplete;
  private boolean snapshotReleaseBlocked;
  private boolean snapshotReleaseAttempted;
  private boolean snapshotReleased;
  private boolean viewReleased;
  private boolean snapshotUseReleased;
  private Throwable closeFailure;
  private byte[] boundedGetProbe = EMPTY_VALUE_BUFFER;

  UnifiedArchiveReadView(RocksDB db,
      EnumMap<UnifiedArchiveColumnFamily, ColumnFamilyHandle> handles,
      Snapshot snapshot, ReadOptions readOptions, Runnable releaseView,
      SnapshotUse snapshotUse) {
    this.db = db;
    this.handles = new EnumMap<>(handles);
    this.snapshot = snapshot;
    this.readOptions = readOptions;
    this.releaseView = releaseView;
    this.snapshotUse = snapshotUse;
    this.ownerThread = Thread.currentThread();
  }

  public byte[] get(UnifiedArchiveColumnFamily columnFamily, byte[] key) {
    requireOwnerAndOpen();
    requireColumnFamily(columnFamily);
    requireKey(key);
    try {
      QueryContext queryContext = QueryContextHolder.current();
      recordBackendRead(queryContext);
      byte[] value = db.get(handles.get(columnFamily), readOptions, key);
      checkDeadline(queryContext);
      return value;
    } catch (RocksDBException e) {
      throw readFailure("UNIFIED_V1 snapshot read failed for "
          + columnFamily.getName(), e);
    }
  }

  /** Reads a snapshot value only after its native size passes the caller's allocation bound. */
  public byte[] getBounded(UnifiedArchiveColumnFamily columnFamily, byte[] key,
      long maxValueBytes, String what) {
    return getBounded(columnFamily, key, maxValueBytes, what, true);
  }

  private byte[] getBounded(UnifiedArchiveColumnFamily columnFamily, byte[] key,
      long maxValueBytes, String what, boolean accountQueryBytes) {
    requireOwnerAndOpen();
    requireColumnFamily(columnFamily);
    requireKey(key);
    if (maxValueBytes < 0L) {
      throw new IllegalArgumentException("maximum value bytes must be non-negative");
    }
    try {
      ColumnFamilyHandle handle = handles.get(columnFamily);
      int probeBytes = (int) StrictMathWrapper.min(maxValueBytes, MAX_BOUNDED_GET_PROBE_BYTES);
      if (boundedGetProbe.length < probeBytes) {
        boundedGetProbe = new byte[probeBytes];
      }
      QueryContext queryContext = QueryContextHolder.current();
      recordBackendRead(queryContext);
      int valueBytes = db.get(handle, readOptions, key, boundedGetProbe);
      checkDeadline(queryContext);
      if (valueBytes == RocksDB.NOT_FOUND) {
        return null;
      }
      if (valueBytes < 0 || valueBytes > maxValueBytes) {
        throw new ArchiveException(what + " exceeds byte limit " + maxValueBytes
            + ": valueBytes=" + valueBytes);
      }
      if (accountQueryBytes && queryContext != null) {
        queryContext.recordBackendValueBytes(valueBytes);
      }
      if (valueBytes <= boundedGetProbe.length) {
        return Arrays.copyOf(boundedGetProbe, valueBytes);
      }
      recordBackendRead(queryContext);
      byte[] value = new byte[valueBytes];
      int copiedBytes = db.get(handle, readOptions, key, value);
      checkDeadline(queryContext);
      if (copiedBytes != valueBytes) {
        throw new ArchiveException(what + " changed while reading snapshot: expectedBytes="
            + valueBytes + ", actualBytes=" + copiedBytes);
      }
      return value;
    } catch (RocksDBException e) {
      throw readFailure("UNIFIED_V1 bounded snapshot read failed for "
          + columnFamily.getName(), e);
    }
  }

  /** Reads a small fixed-size value with an exact native buffer, then accounts it if present. */
  public byte[] getExact(UnifiedArchiveColumnFamily columnFamily, byte[] key,
      long expectedValueBytes, String what) {
    return getExact(columnFamily, key, expectedValueBytes, what, false);
  }

  /**
   * Reads a value whose exact length is authenticated by a fixed locator. Query budgets are
   * reserved before both Java allocation and the single native read.
   */
  public byte[] getExactBudgeted(UnifiedArchiveColumnFamily columnFamily, byte[] key,
      long expectedValueBytes, String what) {
    return getExact(columnFamily, key, expectedValueBytes, what, true);
  }

  private byte[] getExact(UnifiedArchiveColumnFamily columnFamily, byte[] key,
      long expectedValueBytes, String what, boolean accountBeforeRead) {
    requireOwnerAndOpen();
    requireColumnFamily(columnFamily);
    requireKey(key);
    if (expectedValueBytes < 0L || expectedValueBytes > Integer.MAX_VALUE) {
      throw new ArchiveException(what + " has invalid expected byte length: "
          + expectedValueBytes);
    }
    QueryContext queryContext = QueryContextHolder.current();
    if (accountBeforeRead && queryContext != null) {
      queryContext.validateBackendValueBytes(expectedValueBytes);
    }
    try {
      int probeBytes = (int) StrictMathWrapper.min(expectedValueBytes, MAX_BOUNDED_GET_PROBE_BYTES);
      if (boundedGetProbe.length < probeBytes) {
        boundedGetProbe = new byte[probeBytes];
      }
      ColumnFamilyHandle handle = handles.get(columnFamily);
      recordBackendRead(queryContext);
      int valueBytes = db.get(handle, readOptions, key, boundedGetProbe);
      checkDeadline(queryContext);
      if (valueBytes == RocksDB.NOT_FOUND) {
        return null;
      }
      if (valueBytes != expectedValueBytes) {
        throw new ArchiveException(what + " length mismatch: expectedBytes="
            + expectedValueBytes + ", actualBytes=" + valueBytes);
      }
      if (accountBeforeRead && queryContext != null) {
        queryContext.recordBackendValueBytes(valueBytes);
      }
      if (valueBytes <= boundedGetProbe.length) {
        if (!accountBeforeRead && queryContext != null) {
          queryContext.recordBackendValueBytes(valueBytes);
        }
        return Arrays.copyOf(boundedGetProbe, valueBytes);
      }
      recordBackendRead(queryContext);
      byte[] value = new byte[valueBytes];
      int copiedBytes = db.get(handle, readOptions, key, value);
      checkDeadline(queryContext);
      if (copiedBytes != valueBytes) {
        throw new ArchiveException(what + " changed while reading snapshot: expectedBytes="
            + valueBytes + ", actualBytes=" + copiedBytes);
      }
      if (!accountBeforeRead && queryContext != null) {
        queryContext.recordBackendValueBytes(valueBytes);
      }
      return value;
    } catch (RocksDBException e) {
      throw readFailure("UNIFIED_V1 exact snapshot read failed for "
          + columnFamily.getName(), e);
    }
  }

  /**
   * Performs one exact read after the caller has reserved both Java and native value bytes.
   * Unlike {@link #getExactBudgeted}, this method must not receive an untrusted length.
   */
  public byte[] getPreaccountedExact(UnifiedArchiveColumnFamily columnFamily, byte[] key,
      long expectedValueBytes, String what) {
    requireOwnerAndOpen();
    requireColumnFamily(columnFamily);
    requireKey(key);
    if (expectedValueBytes < 0L || expectedValueBytes > Integer.MAX_VALUE) {
      throw new ArchiveException(what + " has invalid expected byte length: "
          + expectedValueBytes);
    }
    QueryContext queryContext = QueryContextHolder.current();
    if (queryContext != null) {
      queryContext.validateBackendValueBytes(expectedValueBytes);
    }
    try {
      byte[] value = new byte[(int) expectedValueBytes];
      recordBackendRead(queryContext);
      int actualBytes = db.get(handles.get(columnFamily), readOptions, key, value);
      checkDeadline(queryContext);
      if (actualBytes == RocksDB.NOT_FOUND) {
        return null;
      }
      if (actualBytes != expectedValueBytes) {
        throw new ArchiveException(what + " length mismatch: expectedBytes="
            + expectedValueBytes + ", actualBytes=" + actualBytes);
      }
      if (queryContext != null) {
        queryContext.recordBackendValueBytes(actualBytes);
      }
      return value;
    } catch (RocksDBException e) {
      throw readFailure("UNIFIED_V1 preaccounted snapshot read failed for "
          + columnFamily.getName(), e);
    }
  }

  /**
   * Creates an iterator bound to this view's snapshot. The view owns it and closes it during close;
   * callers may also close it earlier.
   */
  public UnifiedArchiveIterator newIterator(UnifiedArchiveColumnFamily columnFamily) {
    requireOwnerAndOpen();
    requireColumnFamily(columnFamily);
    RocksIterator delegate = db.newIterator(handles.get(columnFamily), readOptions);
    try {
      UnifiedArchiveIterator iterator = new UnifiedArchiveIterator(delegate);
      iterators.add(iterator);
      return iterator;
    } catch (RuntimeException | Error failure) {
      try {
        delegate.close();
      } catch (RuntimeException | Error closeFailure) {
        ArchiveSnapshotReleaseException uncertain = new ArchiveSnapshotReleaseException(
            "UNIFIED_V1 iterator cleanup outcome is unknown; restart is required",
            closeFailure);
        addSuppressedSafely(uncertain, failure);
        this.closeFailure = uncertain;
        snapshotReleaseBlocked = true;
        closeStarted = true;
        throw uncertain;
      }
      throw failure;
    }
  }

  public long getSequenceNumber() {
    requireOwnerAndOpen();
    return snapshot.getSequenceNumber();
  }

  boolean fillsCache() {
    requireOwnerAndOpen();
    return readOptions.fillCache();
  }

  @Override
  public void close() {
    requireOwner();
    if (closed.get()) {
      rethrowCloseFailure(closeFailure);
      return;
    }
    closeStarted = true;
    if (!closePreparationComplete) {
      Throwable failure = null;
      for (int i = iterators.size() - 1; i >= 0; i--) {
        UnifiedArchiveIterator iterator = iterators.get(i);
        try {
          iterator.close();
        } catch (Throwable iteratorFailure) {
          failure = collectFailure(failure, iteratorFailure);
        }
      }
      if (failure == null && !snapshotReleaseBlocked) {
        try {
          readOptions.close();
        } catch (Throwable readOptionsFailure) {
          failure = readOptionsFailure;
        }
      }
      if (failure != null) {
        if (closeFailure == null) {
          closeFailure = new ArchiveSnapshotReleaseException(
              "UNIFIED_V1 snapshot owner cleanup outcome is unknown; restart is required",
              failure);
        } else {
          addSuppressedSafely(closeFailure, failure);
        }
        snapshotReleaseBlocked = true;
      }
      closePreparationComplete = true;
    }
    if (snapshotReleaseBlocked) {
      rethrowCloseFailure(closeFailure);
      return;
    }

    Throwable attemptFailure = null;
    if (!snapshotReleaseAttempted) {
      snapshotReleaseAttempted = true;
      try {
        db.releaseSnapshot(snapshot);
        snapshotReleased = true;
      } catch (Throwable snapshotFailure) {
        ArchiveSnapshotReleaseException uncertain = new ArchiveSnapshotReleaseException(
            "UNIFIED_V1 snapshot release outcome is unknown; restart is required",
            snapshotFailure);
        if (closeFailure != null) {
          addSuppressedSafely(uncertain, closeFailure);
        }
        closeFailure = uncertain;
      }
    }
    if (!snapshotReleased) {
      rethrowCloseFailure(closeFailure);
      return;
    }
    if (snapshotReleased && !viewReleased) {
      try {
        releaseView.run();
        viewReleased = true;
      } catch (Throwable releaseFailure) {
        attemptFailure = collectFailure(attemptFailure, releaseFailure);
      }
    }
    if (!viewReleased) {
      rethrowCloseFailure(collectFailure(closeFailure, attemptFailure));
      return;
    }
    if (snapshotUse != null && !snapshotUseReleased) {
      try {
        snapshotUse.close();
        snapshotUseReleased = true;
      } catch (Throwable useFailure) {
        attemptFailure = collectFailure(attemptFailure, useFailure);
      }
    }
    if (snapshotUse != null && !snapshotUseReleased) {
      rethrowCloseFailure(collectFailure(closeFailure, attemptFailure));
      return;
    }
    closed.set(true);
    rethrowCloseFailure(closeFailure);
  }

  private void requireOwnerAndOpen() {
    requireOwner();
    if (closeStarted || closed.get()) {
      throw new ArchiveException("UNIFIED_V1 snapshot read view is closed");
    }
  }

  boolean isBackedBy(RocksDB candidate) {
    return db == candidate;
  }

  private static void recordBackendRead(QueryContext queryContext) {
    if (queryContext != null) {
      queryContext.recordBackendRead();
    }
  }

  private static void checkDeadline(QueryContext queryContext) {
    if (queryContext != null) {
      queryContext.checkDeadline();
    }
  }

  private void requireOwner() {
    if (Thread.currentThread() != ownerThread) {
      throw new ArchiveException("UNIFIED_V1 snapshot read view used from a non-owner thread");
    }
  }

  private static void requireColumnFamily(UnifiedArchiveColumnFamily columnFamily) {
    if (columnFamily == null) {
      throw new ArchiveException("UNIFIED_V1 snapshot column family is required");
    }
  }

  private static void requireKey(byte[] key) {
    if (key == null || key.length == 0) {
      throw new ArchiveException("UNIFIED_V1 snapshot key is required");
    }
  }

  private static ArchiveException readFailure(String message, RocksDBException cause) {
    ArchiveRocksIterators.rethrowIfNativeDeadline(cause);
    return new ArchiveException(message, cause);
  }

  private static Throwable collectFailure(Throwable firstFailure, Throwable failure) {
    if (firstFailure == null) {
      return failure;
    }
    addSuppressedSafely(firstFailure, failure);
    return firstFailure;
  }

  private static void addSuppressedSafely(Throwable firstFailure, Throwable failure) {
    if (firstFailure == failure) {
      return;
    }
    try {
      firstFailure.addSuppressed(failure);
    } catch (Throwable ignored) {
      // Cleanup must continue even when suppression cannot allocate.
    }
  }

  private static void rethrowCloseFailure(Throwable failure) {
    if (failure == null) {
      return;
    }
    if (failure instanceof RuntimeException) {
      throw (RuntimeException) failure;
    }
    if (failure instanceof Error) {
      throw (Error) failure;
    }
    throw new ArchiveException("UNIFIED_V1 snapshot close failed", failure);
  }
}
