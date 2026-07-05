package org.tron.core.archive;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.rocksdb.WriteOptions;
import org.tron.common.parameter.CommonParameter;
import org.tron.core.config.args.Storage;

public class ArchiveRocksDbWriteOptionsTest {

  private Storage originalStorage;

  @Before
  public void setUpStorage() {
    originalStorage = CommonParameter.getInstance().storage;
    CommonParameter.getInstance().storage = new Storage();
  }

  @After
  public void restoreStorage() {
    CommonParameter.getInstance().storage = originalStorage;
  }

  @Test
  public void createUsesCanonicalDbSyncFlag() {
    CommonParameter.getInstance().getStorage().setDbSync(true);
    assertTrue(ArchiveRocksDbWriteOptions.isDbSyncEnabled());
    try (WriteOptions options = ArchiveRocksDbWriteOptions.create()) {
      assertTrue(options.sync());
    }

    CommonParameter.getInstance().getStorage().setDbSync(false);
    assertFalse(ArchiveRocksDbWriteOptions.isDbSyncEnabled());
    try (WriteOptions options = ArchiveRocksDbWriteOptions.create()) {
      assertFalse(options.sync());
    }
  }
}
