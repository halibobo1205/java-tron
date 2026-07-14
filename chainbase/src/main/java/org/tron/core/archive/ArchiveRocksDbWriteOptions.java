package org.tron.core.archive;

import org.rocksdb.RocksDB;
import org.rocksdb.WriteOptions;
import org.tron.common.parameter.CommonParameter;
import org.tron.core.config.args.Storage;

/** Operation-specific RocksDB durability for the archive sidecar. */
public final class ArchiveRocksDbWriteOptions {

  static {
    RocksDB.loadLibrary();
  }

  private ArchiveRocksDbWriteOptions() {
  }

  public static WriteOptions create() {
    return new WriteOptions().setDisableWAL(false).setSync(isDbSyncEnabled());
  }

  /** Journal, identity, repair and legacy publish operations must survive process crashes. */
  public static WriteOptions createForcedSync() {
    return new WriteOptions().setDisableWAL(false).setSync(true);
  }

  /** Recoverable derived markers use WAL ordering but do not add another fsync to block commit. */
  public static WriteOptions createWalOnly() {
    return new WriteOptions().setDisableWAL(false).setSync(false);
  }

  static boolean isDbSyncEnabled() {
    Storage storage = CommonParameter.getInstance().getStorage();
    return storage != null && storage.isDbSync();
  }
}
