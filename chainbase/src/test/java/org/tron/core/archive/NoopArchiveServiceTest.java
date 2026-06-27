package org.tron.core.archive;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.tron.core.config.args.StorageConfig;

public class NoopArchiveServiceTest {

  @Test
  public void noopServiceIsDisabledAndDoesNothing() {
    ArchiveService service = NoopArchiveService.INSTANCE;
    assertFalse(service.isEnabled());
    // Every lifecycle callback is a no-op; invoking them (even with null) must not throw.
    service.beginBlock(null);
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
  public void factoryRejectsEnableUntilImplementationLands() {
    StorageConfig.ArchiveConfig config = new StorageConfig.ArchiveConfig();
    config.setEnable(true);
    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> ArchiveServiceFactory.create(config));
    assertTrue(ex.getMessage().contains("later landing"));
  }
}
