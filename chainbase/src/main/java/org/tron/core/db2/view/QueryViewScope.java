package org.tron.core.db2.view;

public final class QueryViewScope implements AutoCloseable {

  private static final ThreadLocal<HeadLease> CURRENT = new ThreadLocal<>();

  private final boolean owner;

  private QueryViewScope(boolean owner) {
    this.owner = owner;
  }

  public static HeadLease current() {
    return CURRENT.get();
  }

  public static boolean isBound() {
    return CURRENT.get() != null;
  }

  public static QueryViewScope bind(HeadLease lease) {
    HeadLease existing = CURRENT.get();
    if (existing == null) {
      CURRENT.set(lease);
      return new QueryViewScope(true);
    }
    if (existing == lease) {
      return new QueryViewScope(false);
    }
    throw existing.unsupported("binding a second committed head lease");
  }

  @Override
  public void close() {
    if (owner) {
      CURRENT.remove();
    }
  }
}
