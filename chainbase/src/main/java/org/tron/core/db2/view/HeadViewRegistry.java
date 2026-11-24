package org.tron.core.db2.view;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.tron.core.db2.core.Chainbase;
import org.tron.core.db2.core.RefreshObserver;
import org.tron.core.db2.core.Snapshot;
import org.tron.core.db2.view.HeadViewException.Reason;

@Slf4j(topic = "DB")
public final class HeadViewRegistry implements RefreshObserver {

  private static final int READY = 1;
  private static final int FAULT = 1 << 1;
  private static final int FAILED = 1 << 2;
  private static final int CLOSING = 1 << 3;
  private static final int BLOCKING = FAULT | FAILED | CLOSING;

  private final Object gate = new Object();
  private final AtomicInteger state = new AtomicInteger();
  private final AtomicLong generation = new AtomicLong();
  private final AtomicBoolean violationLogged = new AtomicBoolean();
  private final int maxActiveLeases;
  private final Map<HeadView, Integer> leasedViews = new IdentityHashMap<>();
  private HeadView current;
  private int activeLeases;
  private boolean readyOnPublish;

  public HeadViewRegistry(int maxActiveLeases) {
    this.maxActiveLeases = maxActiveLeases;
  }

  public long currentGeneration() {
    return generation.get();
  }

  public boolean isAvailable() {
    int s = state.get();
    return (s & READY) != 0 && (s & BLOCKING) == 0;
  }

  public void publish(HeadView view, long capturedGeneration) {
    synchronized (gate) {
      if (generation.get() != capturedGeneration || isSet(FAILED | CLOSING)) {
        view.invalidate();
        return;
      }
      view.setGeneration(capturedGeneration);
      current = view;
      clear(FAULT);
      violationLogged.set(false);
      if (readyOnPublish) {
        readyOnPublish = false;
        set(READY);
      }
    }
  }

  public void markReady() {
    synchronized (gate) {
      readyOnPublish = false;
      set(READY);
    }
  }

  public void markReadyOnNextPublish() {
    synchronized (gate) {
      if (!isSet(READY)) {
        readyOnPublish = true;
      }
    }
  }

  public void beginClosing() {
    set(CLOSING);
    generation.incrementAndGet();
  }

  public HeadLease acquire() {
    synchronized (gate) {
      if (!isAvailable()) {
        throw new HeadViewException(Reason.UNAVAILABLE, "committed head view unavailable");
      }
      HeadView view = current;
      if (view == null || !view.isValid() || view.getGeneration() != generation.get()) {
        throw new HeadViewException(Reason.EXPIRED, "committed head view expired");
      }
      if (activeLeases >= maxActiveLeases) {
        throw new HeadViewException(Reason.UNAVAILABLE, "too many committed head requests");
      }
      activeLeases++;
      leasedViews.merge(view, 1, Integer::sum);
      return new HeadLease(this, view);
    }
  }

  boolean seal(HeadLease lease) {
    synchronized (gate) {
      HeadView view = lease.getView();
      if (lease.isClosed()) {
        lease.fail(Reason.UNAVAILABLE, "committed head lease already closed");
        return false;
      }
      if ((state.get() & BLOCKING) != 0) {
        lease.fail(Reason.UNAVAILABLE, "committed head view unavailable");
        return false;
      }
      if (!view.isValid()) {
        lease.fail(Reason.EXPIRED, "committed head view expired");
        return false;
      }
      if (view.getGeneration() != generation.get()) {
        lease.fail(Reason.UNAVAILABLE, "committed head view poisoned");
        return false;
      }
      return lease.getFault() == null;
    }
  }

  void release(HeadLease lease) {
    synchronized (gate) {
      activeLeases--;
      leasedViews.computeIfPresent(lease.getView(), (view, count) -> count > 1 ? count - 1 : null);
    }
  }

  boolean isCurrentGeneration(HeadView view) {
    return view.getGeneration() == generation.get();
  }

  @Override
  public void beforeRefresh(Map<Chainbase, Snapshot> lastFlushedLayers) {
    synchronized (gate) {
      List<HeadView> candidates = new ArrayList<>(leasedViews.keySet());
      if (current != null && !leasedViews.containsKey(current)) {
        candidates.add(current);
      }
      for (HeadView view : candidates) {
        if (view.isValid() && !coversAll(view, lastFlushedLayers)) {
          view.invalidate();
        }
      }
    }
  }

  @Override
  public void refreshIncomplete() {
    set(FAILED);
    generation.incrementAndGet();
    logQuietly("refresh did not complete, committed head view disabled until restart", null);
  }

  @Override
  public void observerFailed(Throwable cause) {
    poison();
    logQuietly("committed head observer failed", cause);
  }

  @Override
  public void onFrozenLayerWrite(String dbName) {
    poison();
    if (violationLogged.compareAndSet(false, true)) {
      logQuietly("write to a published layer of " + dbName, null);
    }
  }

  private void poison() {
    generation.incrementAndGet();
    set(FAULT);
  }

  private static boolean coversAll(HeadView view, Map<Chainbase, Snapshot> lastFlushedLayers) {
    for (Map.Entry<Chainbase, Snapshot> entry : lastFlushedLayers.entrySet()) {
      if (!view.coversFlush(entry.getKey(), entry.getValue())) {
        return false;
      }
    }
    return true;
  }

  private boolean isSet(int bits) {
    return (state.get() & bits) != 0;
  }

  private void set(int bits) {
    state.getAndUpdate(s -> s | bits);
  }

  private void clear(int bits) {
    state.getAndUpdate(s -> s & ~bits);
  }

  private static void logQuietly(String message, Throwable cause) {
    try {
      logger.error(message, cause);
    } catch (Throwable ignored) {
      // logging must never escape a write-path hook
    }
  }
}
