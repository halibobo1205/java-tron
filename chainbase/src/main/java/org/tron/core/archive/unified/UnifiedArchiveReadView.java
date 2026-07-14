package org.tron.core.archive.unified;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.Snapshot;
import org.tron.core.archive.ArchiveException;

/** One RocksDB snapshot shared by meta, index, temporal, marker, and journal reads. */
public final class UnifiedArchiveReadView implements AutoCloseable {

  private final RocksDB db;
  private final EnumMap<UnifiedArchiveColumnFamily, ColumnFamilyHandle> handles;
  private final Snapshot snapshot;
  private final ReadOptions readOptions;
  private final Runnable releaseView;
  private final Thread ownerThread;
  private final List<RocksIterator> iterators = new ArrayList<>();
  private final AtomicBoolean closed = new AtomicBoolean();

  UnifiedArchiveReadView(RocksDB db,
      EnumMap<UnifiedArchiveColumnFamily, ColumnFamilyHandle> handles,
      Snapshot snapshot, ReadOptions readOptions, Runnable releaseView) {
    this.db = db;
    this.handles = new EnumMap<>(handles);
    this.snapshot = snapshot;
    this.readOptions = readOptions;
    this.releaseView = releaseView;
    this.ownerThread = Thread.currentThread();
  }

  public byte[] get(UnifiedArchiveColumnFamily columnFamily, byte[] key) {
    requireOwnerAndOpen();
    requireColumnFamily(columnFamily);
    requireKey(key);
    try {
      return db.get(handles.get(columnFamily), readOptions, key);
    } catch (RocksDBException e) {
      throw new ArchiveException("UNIFIED_V1 snapshot read failed for "
          + columnFamily.getName(), e);
    }
  }

  /**
   * Creates an iterator bound to this view's snapshot. The view owns it and closes it during close;
   * callers may also close it earlier.
   */
  public RocksIterator newIterator(UnifiedArchiveColumnFamily columnFamily) {
    requireOwnerAndOpen();
    requireColumnFamily(columnFamily);
    RocksIterator iterator = db.newIterator(handles.get(columnFamily), readOptions);
    iterators.add(iterator);
    return iterator;
  }

  public long getSequenceNumber() {
    requireOwnerAndOpen();
    return snapshot.getSequenceNumber();
  }

  @Override
  public void close() {
    requireOwner();
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    Throwable failure = null;
    for (int i = iterators.size() - 1; i >= 0; i--) {
      RocksIterator iterator = iterators.get(i);
      failure = closeAndCollect(failure, iterator::close);
    }
    failure = closeAndCollect(failure, readOptions::close);
    failure = closeAndCollect(failure, () -> db.releaseSnapshot(snapshot));
    failure = closeAndCollect(failure, releaseView);
    rethrowCloseFailure(failure);
  }

  private void requireOwnerAndOpen() {
    requireOwner();
    if (closed.get()) {
      throw new ArchiveException("UNIFIED_V1 snapshot read view is closed");
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

  private static Throwable closeAndCollect(Throwable firstFailure, Runnable action) {
    try {
      action.run();
      return firstFailure;
    } catch (Throwable failure) {
      if (firstFailure == null) {
        return failure;
      }
      firstFailure.addSuppressed(failure);
      return firstFailure;
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
