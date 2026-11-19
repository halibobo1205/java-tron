package org.tron.core.db.common.iterator;

import java.io.IOException;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import lombok.extern.slf4j.Slf4j;

/**
 * Lifecycle shared by the data-source iterators. Every call runs on this iterator's monitor and
 * is rejected once {@link IteratorGuard#invalidateAll()} has released the iterator; subclasses
 * only adapt the native iterator.
 */
@Slf4j(topic = "DB")
public abstract class GuardedDbIterator implements DBIterator {

  private final IteratorGuard guard;
  private boolean first = true;
  private boolean released;
  private boolean invalidated;

  GuardedDbIterator(IteratorGuard guard) {
    this.guard = guard;
  }

  abstract void seekToFirstNative();

  abstract void seekToLastNative();

  abstract void seekNative(byte[] key);

  abstract boolean validNative();

  abstract Entry<byte[], byte[]> nextNative();

  abstract byte[] keyNative();

  abstract byte[] valueNative();

  abstract void closeNative() throws IOException;

  @Override
  public final synchronized boolean hasNext() {
    checkOpen();
    if (released) {
      return false;
    }
    boolean hasNext = false;
    try {
      if (first) {
        seekToFirstNative();
        first = false;
      }
      hasNext = validNative();
      if (!hasNext) {
        finish();
      }
    } catch (Exception e) {
      logger.error(e.getMessage(), e);
      finishQuietly();
    }
    return hasNext;
  }

  @Override
  public final synchronized Entry<byte[], byte[]> next() {
    checkOpen();
    if (released) {
      throw new NoSuchElementException();
    }
    return nextNative();
  }

  @Override
  public final void remove() {
    throw new UnsupportedOperationException();
  }

  @Override
  public final synchronized void seek(byte[] key) {
    checkState();
    seekNative(key);
    first = false;
  }

  @Override
  public final synchronized void seekToFirst() {
    checkState();
    seekToFirstNative();
    first = false;
  }

  @Override
  public final synchronized void seekToLast() {
    checkState();
    seekToLastNative();
    first = false;
  }

  @Override
  public final synchronized boolean valid() {
    checkState();
    return validNative();
  }

  @Override
  public final synchronized byte[] getKey() {
    checkValid();
    return keyNative();
  }

  @Override
  public final synchronized byte[] getValue() {
    checkValid();
    return valueNative();
  }

  @Override
  public final synchronized void checkState() {
    checkOpen();
    if (released) {
      throw new IllegalStateException("iterator has been closed");
    }
  }

  @Override
  public final synchronized void close() throws IOException {
    finish();
  }

  /** Called by {@link IteratorGuard#invalidateAll()} with the data source's write lock held. */
  final synchronized void invalidate() {
    if (released) {
      return;
    }
    invalidated = true;
    try {
      release();
    } catch (Exception e) {
      logger.warn("Failed to release a DB iterator.", e);
    }
  }

  private void checkOpen() {
    if (invalidated) {
      throw guard.closedException();
    }
  }

  private void finish() throws IOException {
    if (released) {
      return;
    }
    try {
      release();
    } finally {
      guard.unregister(this);
    }
  }

  private void finishQuietly() {
    try {
      finish();
    } catch (Exception e) {
      logger.warn("Failed to release a DB iterator.", e);
    }
  }

  private void release() throws IOException {
    if (!released) {
      released = true;
      closeNative();
    }
  }
}
