package org.tron.core.archive;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.After;
import org.junit.Test;
import org.tron.core.archive.capture.ArchiveCaptureHolder;
import org.tron.core.archive.temporal.RocksDbArchiveTemporalStore;
import org.tron.core.archive.txnum.PersistentArchiveTxNumIndex;
import org.tron.core.config.args.StorageConfig;

public class NoopArchiveServiceTest {

  @Test
  public void noopServiceIsDisabledAndDoesNothing() {
    ArchiveService service = NoopArchiveService.INSTANCE;
    assertFalse(service.isEnabled());
    // Every lifecycle callback is a no-op; invoking them (even with null) must not throw.
    service.beginBlock(null, ArchiveSource.NORMAL);
    service.beginSystemTx(null, ArchivePhase.BLOCK_PREPARE);
    service.beginUserTx(null, 0, null);
    service.endTx();
    service.commitBlock(null);
    service.abortBlock(null);
    service.unwindBlock(null);
  }

  @Test
  public void factoryReturnsNoopWhenDisabled() {
    StorageConfig.ArchiveConfig config = new StorageConfig.ArchiveConfig();
    assertFalse(config.isEnable());
    assertSame(NoopArchiveService.INSTANCE, ArchiveServiceFactory.create(config));
  }

  @Test
  public void factoryReturnsNoopWhenConfigNull() {
    assertSame(NoopArchiveService.INSTANCE, ArchiveServiceFactory.create(null));
  }

  @Test
  public void factoryReturnsEnabledDefaultServiceWhenEnabled() {
    StorageConfig.ArchiveConfig config = new StorageConfig.ArchiveConfig();
    config.setEnable(true);
    ArchiveService service = ArchiveServiceFactory.create(config);
    assertTrue(service instanceof DefaultArchiveService);
    assertTrue(service.isEnabled());
  }

  @Test
  public void factoryInstallsPersistentStoreWhenPathSupplied() throws IOException {
    StorageConfig.ArchiveConfig config = new StorageConfig.ArchiveConfig();
    config.setEnable(true);
    Path dir = Files.createTempDirectory("archive-factory-test");
    try {
      DefaultArchiveService service =
          (DefaultArchiveService) ArchiveServiceFactory.create(config, dir.toString());
      assertTrue(service.getTemporalStore() instanceof RocksDbArchiveTemporalStore);
      assertTrue(service.getTxNumIndex() instanceof PersistentArchiveTxNumIndex);
      assertNotNull(service.getReaderFactory());
      service.close(); // must release both RocksDB stores cleanly
    } finally {
      deleteRecursively(dir.toFile());
    }
  }

  @After
  public void clearCaptureHolder() {
    // An enabled DefaultArchiveService installs a static capture engine; clear between tests.
    ArchiveCaptureHolder.clear();
  }

  private static void deleteRecursively(File f) {
    File[] children = f.listFiles();
    if (children != null) {
      for (File c : children) {
        deleteRecursively(c);
      }
    }
    f.delete();
  }
}
