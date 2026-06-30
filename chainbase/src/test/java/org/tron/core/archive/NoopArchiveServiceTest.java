package org.tron.core.archive;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.After;
import org.junit.Test;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.archive.capture.ArchiveCaptureHolder;
import org.tron.core.archive.temporal.RocksDbArchiveTemporalStore;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.archive.txnum.PersistentArchiveTxNumIndex;
import org.tron.core.archive.txnum.RocksDbArchiveBlockRangeStore;
import org.tron.core.capsule.BlockCapsule;
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
  public void factoryRejectsArchiveEnabledWithTxnumDisabled() {
    StorageConfig.ArchiveConfig config = new StorageConfig.ArchiveConfig();
    config.setEnable(true);
    config.getTxnum().setEnable(false);
    assertThrows(ArchiveException.class, () -> ArchiveServiceFactory.create(config));
  }

  @Test
  public void factoryRejectsArchiveEnabledWithTemporalDisabled() {
    StorageConfig.ArchiveConfig config = new StorageConfig.ArchiveConfig();
    config.setEnable(true);
    config.getTemporal().setEnable(false);
    assertThrows(ArchiveException.class, () -> ArchiveServiceFactory.create(config));
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

  @Test
  public void factoryReopensCommittedPersistentArchiveWithTemporalMarker() throws IOException {
    StorageConfig.ArchiveConfig config = new StorageConfig.ArchiveConfig();
    config.setEnable(true);
    Path dir = Files.createTempDirectory("archive-factory-reopen-test");
    try {
      DefaultArchiveService service =
          (DefaultArchiveService) ArchiveServiceFactory.create(config, dir.toString());
      BlockCapsule block = new BlockCapsule(1, Sha256Hash.ZERO_HASH, 1L, ByteString.EMPTY);
      service.beginBlock(block, ArchiveSource.NORMAL);
      service.beginSystemTx(block, ArchivePhase.BLOCK_PREPARE);
      service.endTx();
      service.beginSystemTx(block, ArchivePhase.BLOCK_FINALIZE);
      service.endTx();
      service.commitBlock(block);
      service.close();

      DefaultArchiveService reopened =
          (DefaultArchiveService) ArchiveServiceFactory.create(config, dir.toString());
      reopened.close();
    } finally {
      deleteRecursively(dir.toFile());
    }
  }

  @Test
  public void factoryRejectsIndexRangeWithoutTemporalCommitMarker() throws IOException {
    StorageConfig.ArchiveConfig config = new StorageConfig.ArchiveConfig();
    config.setEnable(true);
    Path dir = Files.createTempDirectory("archive-factory-crash-test");
    RocksDbArchiveBlockRangeStore index =
        new RocksDbArchiveBlockRangeStore(dir.resolve("index").toString());
    try {
      ArchiveBlockRange range = new ArchiveBlockRange(
          7, 0, 1, 0, 1, 0, ArchiveSource.NORMAL);
      index.commitRange(range, 2);
    } finally {
      index.close();
    }
    try {
      ArchiveException ex = assertThrows(ArchiveException.class,
          () -> ArchiveServiceFactory.create(config, dir.toString()));
      assertTrue(ex.getMessage().contains("commit marker missing"));
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
