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

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> new DefaultConfig().archiveService(null));

    assertEquals("storage.archive.enable is not supported on SolidityNode",
        failure.getMessage());
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
