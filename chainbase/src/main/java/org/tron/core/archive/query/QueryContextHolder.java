package org.tron.core.archive.query;

/** Thread-local bridge for carrying one historical-query budget through VM and trace code. */
public final class QueryContextHolder {

  private static final ThreadLocal<Entry> CURRENT = new ThreadLocal<>();

  private QueryContextHolder() {
  }

  /** Returns the current historical-query context, or {@code null} outside an archive query. */
  public static QueryContext current() {
    Entry entry = CURRENT.get();
    return entry == null ? null : entry.context;
  }

  public static boolean isActive() {
    return CURRENT.get() != null;
  }

  /**
   * Installs {@code context} until the returned scope closes. Scopes are nesting-safe and must be
   * closed by their owner thread in last-in-first-out order.
   */
  public static Scope attach(QueryContext context) {
    if (context == null) {
      throw new NullPointerException("context");
    }
    Entry entry = new Entry(context, CURRENT.get());
    CURRENT.set(entry);
    return new Scope(entry, Thread.currentThread());
  }

  /** Preserves an already-active outer scope when a compatibility reader has no context. */
  public static Scope attachIfPresent(QueryContext context) {
    return context == null ? new Scope(null, Thread.currentThread()) : attach(context);
  }

  private static final class Entry {

    private final QueryContext context;
    private final Entry previous;

    private Entry(QueryContext context, Entry previous) {
      this.context = context;
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
