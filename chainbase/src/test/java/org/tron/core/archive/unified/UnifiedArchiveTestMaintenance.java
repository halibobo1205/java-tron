package org.tron.core.archive.unified;

import java.nio.file.Path;

/** Test-only bridge to the package-owned archive fault-injection writer. */
public final class UnifiedArchiveTestMaintenance {

  private UnifiedArchiveTestMaintenance() {
  }

  public static void write(
      UnifiedArchiveDb db, UnifiedArchiveMaintenanceBatch maintenanceBatch) {
    db.writeMaintenanceAtomically(maintenanceBatch);
  }

  public static UnifiedArchiveDb openWithBeforeBatchWrite(
      Path path, byte[] schemaChecksum, Runnable beforeBatchWrite) {
    if (beforeBatchWrite == null) {
      throw new NullPointerException("beforeBatchWrite");
    }
    return UnifiedArchiveDb.open(path, schemaChecksum, (db, writeOptions, batch) -> {
      beforeBatchWrite.run();
      db.write(writeOptions, batch);
    });
  }
}
