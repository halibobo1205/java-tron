package org.tron.core.vm.archive;

import org.tron.core.ChainBaseManager;
import org.tron.core.store.StoreFactory;

/** Resolves immutable chain identity without making mocked VM execution depend on global stores. */
final class HistoricalArchiveChainIdentity {

  private HistoricalArchiveChainIdentity() {
  }

  static byte[] blackHoleAddress(StoreFactory storeFactory) {
    if (storeFactory == null) {
      return null;
    }
    ChainBaseManager chainBaseManager = storeFactory.getChainBaseManager();
    if (chainBaseManager == null || chainBaseManager.getAccountStore() == null) {
      return null;
    }
    return chainBaseManager.getAccountStore().getBlackholeAddress();
  }
}
