package org.tron.core.archive;

import org.rocksdb.RocksDB;
import org.rocksdb.WriteOptions;
import org.tron.common.parameter.CommonParameter;
import org.tron.core.config.args.Storage;

/** Creates RocksDB write options that match the canonical database sync policy. */
public final class ArchiveRocksDbWriteOptions {

  static {
    RocksDB.loadLibrary();
  }

  private ArchiveRocksDbWriteOptions() {
  }

  public static WriteOptions create() {
    return new WriteOptions().setSync(isDbSyncEnabled());
  }

  static boolean isDbSyncEnabled() {
    Storage storage = CommonParameter.getInstance().getStorage();
    return storage != null && storage.isDbSync();
  }
}
