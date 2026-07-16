package org.tron.core.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.tron.common.parameter.CommonParameter;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.NoopArchiveService;
import org.tron.core.config.args.Storage;
import org.tron.core.config.args.StorageConfig;
import org.tron.core.exception.TronError;

public class DefaultConfigArchiveServiceTest {

  private CommonParameter parameter;
  private Storage originalStorage;
  private String originalOutputDirectory;
  private boolean originalSolidityNode;

  @Before
  public void saveParameterState() {
    parameter = CommonParameter.getInstance();
    originalStorage = parameter.getStorage();
    originalOutputDirectory = parameter.getOutputDirectory();
    originalSolidityNode = parameter.isSolidityNode();
  }

  @After
  public void restoreParameterState() {
    parameter.storage = originalStorage;
    parameter.outputDirectory = originalOutputDirectory;
    parameter.setSolidityNode(originalSolidityNode);
  }

  @Test
  public void enabledArchiveRejectsSolidityNodeBeforePathAccess() {
    setArchiveEnabled(true);
    parameter.setSolidityNode(true);
    parameter.outputDirectory = null;

    TronError failure = assertThrows(TronError.class,
        () -> new DefaultConfig().archiveService(null));

    assertEquals(TronError.ErrCode.ARCHIVE_RUNTIME, failure.getErrCode());
    assertEquals("fatal archive sidecar initialization failure", failure.getMessage());
    assertEquals(ArchiveException.class, failure.getCause().getClass());
    assertEquals("storage.archive.enable is not supported on SolidityNode",
        failure.getCause().getMessage());
  }

  @Test
  public void disabledArchiveRemainsNoopOnSolidityNode() {
    setArchiveEnabled(false);
    parameter.setSolidityNode(true);
    parameter.outputDirectory = null;

    assertSame(NoopArchiveService.INSTANCE, new DefaultConfig().archiveService(null));
  }

  private void setArchiveEnabled(boolean enabled) {
    StorageConfig.ArchiveConfig archive = new StorageConfig.ArchiveConfig();
    archive.setEnable(enabled);
    Storage storage = new Storage();
    storage.setArchive(archive);
    parameter.storage = storage;
  }
}
