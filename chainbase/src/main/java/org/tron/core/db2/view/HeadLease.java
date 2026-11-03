package org.tron.core.db2.view;

import java.util.Map;
import org.tron.core.db2.core.Chainbase;
import org.tron.core.db2.core.StoreLayers;
import org.tron.core.db2.view.HeadViewException.Reason;

public final class HeadLease implements AutoCloseable {

  private final HeadViewRegistry registry;
  private final HeadView view;
  private volatile Reason fault;
  private boolean closed;

  HeadLease(HeadViewRegistry registry, HeadView view) {
    this.registry = registry;
    this.view = view;
  }

  public HeadView getView() {
    return view;
  }

  public Reason getFault() {
    return fault;
  }

  public byte[] read(Chainbase db, byte[] key) {
    checkUsable();
    Map<Chainbase, StoreLayers> layers = view.layers();
    if (layers == null) {
      throw fail(Reason.EXPIRED, "committed head view expired");
    }
    StoreLayers storeLayers = layers.get(db);
    if (storeLayers == null) {
      throw fail(Reason.UNSUPPORTED, "store not captured: " + db.getDbName());
    }
    return storeLayers.get(key);
  }

  public HeadViewException unsupported(String what) {
    return fail(Reason.UNSUPPORTED, "unsupported in committed head view: " + what);
  }

  public HeadViewException fail(Reason reason, String message) {
    if (fault == null) {
      fault = reason;
    }
    return new HeadViewException(reason, message);
  }

  public boolean seal() {
    return registry.seal(this);
  }

  synchronized boolean isClosed() {
    return closed;
  }

  @Override
  public void close() {
    synchronized (this) {
      if (closed) {
        return;
      }
      closed = true;
    }
    registry.release(this);
  }

  private void checkUsable() {
    Reason recorded = fault;
    if (recorded != null) {
      throw new HeadViewException(recorded, "committed head request already failed");
    }
    if (!view.isValid()) {
      throw fail(Reason.EXPIRED, "committed head view expired");
    }
    if (!registry.isCurrentGeneration(view)) {
      throw fail(Reason.UNAVAILABLE, "committed head view poisoned");
    }
  }
}
