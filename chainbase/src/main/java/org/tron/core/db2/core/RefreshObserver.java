package org.tron.core.db2.core;

import java.util.Map;

public interface RefreshObserver extends FrozenLayerListener {

  void beforeRefresh(Map<Chainbase, Snapshot> lastFlushedLayers);

  void refreshIncomplete();

  void observerFailed(Throwable cause);
}
