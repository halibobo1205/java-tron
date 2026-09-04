package org.tron.core.archive.query;

/** Thread-local bridge for carrying one historical-query budget through VM execution. */
public final class QueryContextHolder {

  private static final ThreadLocal<Entry> CURRENT = new ThreadLocal<>();

  private QueryContextHolder() {
  }

  /** Returns the current historical-query context, or {@code null} outside an archive query. */
  public static QueryContext current() {
    Entry entry = CURRENT.get();
    return entry == null ? null : entry.context;
  }

  /** Returns the query budget visible only to cacheless canonical storage reads. */
  public static QueryContext currentStorageContext() {
    Entry entry = CURRENT.get();
    return entry == null ? null : entry.storageContext;
  }

  public static boolean isActive() {
    return current() != null;
  }

  /**
   * Installs {@code context} until the returned scope closes. Scopes are nesting-safe and must be
   * closed by their owner thread in last-in-first-out order.
   */
  public static Scope attach(QueryContext context) {
    if (context == null) {
      throw new NullPointerException("context");
    }
    Entry previous = CURRENT.get();
    Entry entry = new Entry(context, context, previous);
    Scope scope = new Scope(entry, Thread.currentThread());
    try {
      CURRENT.set(entry);
      return scope;
    } catch (RuntimeException | Error failure) {
      try {
        if (previous == null) {
          CURRENT.remove();
        } else {
          CURRENT.set(previous);
        }
      } catch (RuntimeException | Error restoreFailure) {
        if (failure != restoreFailure) {
          failure.addSuppressed(restoreFailure);
        }
      }
      throw failure;
    }
  }

  /** Preserves an already-active outer scope when a compatibility reader has no context. */
  public static Scope attachIfPresent(QueryContext context) {
    return context == null ? new Scope(null, Thread.currentThread()) : attach(context);
  }

  /** Temporarily hides the current query context from an application callback. */
  public static Scope suspend() {
    Entry previous = CURRENT.get();
    QueryContext storageContext = previous == null ? null : previous.storageContext;
    Entry suspended = new Entry(null, storageContext, previous);
    Scope scope = new Scope(suspended, Thread.currentThread());
    try {
      CURRENT.set(suspended);
      return scope;
    } catch (RuntimeException | Error failure) {
      try {
        if (previous == null) {
          CURRENT.remove();
        } else {
          CURRENT.set(previous);
        }
      } catch (RuntimeException | Error restoreFailure) {
        if (failure != restoreFailure) {
          failure.addSuppressed(restoreFailure);
        }
      }
      throw failure;
    }
  }

  private static final class Entry {

    private final QueryContext context;
    private final QueryContext storageContext;
    private final Entry previous;

    private Entry(QueryContext context, QueryContext storageContext, Entry previous) {
      this.context = context;
      this.storageContext = storageContext;
      this.previous = previous;
    }
  }

  public static final class Scope implements AutoCloseable {

    private final Entry entry;
    private final Thread owner;
    private boolean closed;

    private Scope(Entry entry, Thread owner) {
      this.entry = entry;
      this.owner = owner;
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      if (Thread.currentThread() != owner) {
        throw new IllegalStateException("query context scope closed by a non-owner thread");
      }
      if (entry != null) {
        if (CURRENT.get() != entry) {
          throw new IllegalStateException("query context scopes closed out of order");
        }
        if (entry.previous == null) {
          CURRENT.remove();
        } else {
          CURRENT.set(entry.previous);
        }
      }
      closed = true;
    }
  }
}
