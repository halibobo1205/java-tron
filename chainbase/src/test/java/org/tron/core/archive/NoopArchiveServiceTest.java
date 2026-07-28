package org.tron.core.archive;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import org.junit.After;
import org.junit.Test;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.archive.capture.ArchiveCaptureHolder;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.config.args.StorageConfig;

public class NoopArchiveServiceTest {

  @Test
  public void noopServiceIsDisabledAndDoesNothing() {
    ArchiveService service = NoopArchiveService.INSTANCE;
    assertFalse(service.isEnabled());
    service.beginBlock(null, ArchiveSource.NORMAL);
    service.beginSystemTx(null, ArchivePhase.BLOCK_PREPARE);
    service.beginUserTx(null, 0, null);
    service.endTx();
    service.commitBlock(null);
    service.abortBlock(null);
    service.unwindBlock(null);
    service.validateCanonicalHead(null);
  }

  @Test
  public void factoryReturnsNoopWhenDisabled() {
    StorageConfig.ArchiveConfig config = new StorageConfig.ArchiveConfig();
    assertFalse(config.isEnable());
    assertSame(NoopArchiveService.INSTANCE, ArchiveServiceFactory.create(config));
  }

  @Test
  public void disabledFactoryDoesNotClearLiveCaptureHolder() {
    ArchiveService enabled = new DefaultArchiveService(true);
    try {
      assertTrue(ArchiveCaptureHolder.isActive());

      StorageConfig.ArchiveConfig disabledConfig = new StorageConfig.ArchiveConfig();
      assertSame(NoopArchiveService.INSTANCE, ArchiveServiceFactory.create(disabledConfig));

      assertTrue(ArchiveCaptureHolder.isActive());
      enabled.validateAvailable();
    } finally {
      enabled.close();
    }
  }

  @Test
  public void closingOlderEnabledServiceDoesNotClearNewerCaptureHolder() {
    ArchiveService older = new DefaultArchiveService(true);
    ArchiveService newer = new DefaultArchiveService(true);
    try {
      assertTrue(ArchiveCaptureHolder.isActive());
      assertThrows(ArchiveException.class, older::validateAvailable);
      older.close();
      assertTrue(ArchiveCaptureHolder.isActive());
    } finally {
      newer.close();
    }
  }

  @Test
  public void closingOlderEnabledServiceDoesNotClearNewerActiveTxContext() {
    ArchiveService older = new DefaultArchiveService(true);
    ArchiveService newer = new DefaultArchiveService(true);
    BlockCapsule block = new BlockCapsule(1, Sha256Hash.ZERO_HASH, 1L, ByteString.EMPTY);
    try {
      newer.beginBlock(block, ArchiveSource.NORMAL);
      newer.beginSystemTx(block, ArchivePhase.BLOCK_PREPARE);
      assertTrue(ArchiveCaptureHolder.isCapturingCurrentTx());

      older.close();

      assertTrue(ArchiveCaptureHolder.isCapturingCurrentTx());
      newer.endTx();
    } finally {
      newer.close();
    }
  }

  @Test
  public void factoryReturnsNoopWhenConfigNull() {
    assertSame(NoopArchiveService.INSTANCE, ArchiveServiceFactory.create(null));
  }

  @Test
  public void factoryRejectsEnabledArchiveWithoutUnifiedDirectory() {
    StorageConfig.ArchiveConfig config = new StorageConfig.ArchiveConfig();
    config.setEnable(true);

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> createViaFactory(config));

    assertTrue(failure.getMessage().contains("requires a UNIFIED_V1 archive directory"));
  }

  @Test
  public void factoryRejectsArchiveEnabledWithTxnumDisabled() {
    StorageConfig.ArchiveConfig config = new StorageConfig.ArchiveConfig();
    config.setEnable(true);
    config.getTxnum().setEnable(false);
    assertThrows(ArchiveException.class, () -> createViaFactory(config));
  }

  @Test
  public void factoryRejectsArchiveEnabledWithTemporalDisabled() {
    StorageConfig.ArchiveConfig config = new StorageConfig.ArchiveConfig();
    config.setEnable(true);
    config.getTemporal().setEnable(false);
    assertThrows(ArchiveException.class, () -> createViaFactory(config));
  }

  @Test
  public void factoryRejectsUnsupportedCoverageProfile() {
    StorageConfig.ArchiveConfig config = new StorageConfig.ArchiveConfig();
    config.setEnable(true);
    config.setCoverage("FULL");
    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> createViaFactory(config));
    assertTrue(failure.getMessage().contains("supports only TVM_STATE_ONLY"));
  }

  @Test
  public void factoryRejectsWarnUnclassifiedDisabled() {
    StorageConfig.ArchiveConfig config = new StorageConfig.ArchiveConfig();
    config.setEnable(true);
    config.setWarnUnclassifiedStoreWrites(false);
    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> createViaFactory(config));
    assertTrue(failure.getMessage().contains("cannot be false"));
  }

  @Test
  public void factoryRejectsUnsupportedCommitmentEnabled() {
    StorageConfig.ArchiveConfig config = new StorageConfig.ArchiveConfig();
    config.setEnable(true);
    config.getCommitment().setEnable(true);
    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> createViaFactory(config));
    assertTrue(failure.getMessage().contains("commitment.enable is not supported"));
  }

  @Test
  public void factoryRejectsPersistTxRootsEnabled() {
    StorageConfig.ArchiveConfig config = new StorageConfig.ArchiveConfig();
    config.setEnable(true);
    config.getCommitment().setPersistTxRoots(true);
    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> createViaFactory(config));
    assertTrue(failure.getMessage().contains("persistTxRoots cannot be true"));
  }

  @Test
  public void factoryAllowsDebugTracingThroughFeatureValidation() {
    StorageConfig.ArchiveConfig config = new StorageConfig.ArchiveConfig();
    config.setEnable(true);
    config.getDebug().setEnable(true);
    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> createViaFactory(config));
    assertTrue(failure.getMessage().contains("requires a UNIFIED_V1 archive directory"));
  }

  @After
  public void clearCaptureHolder() {
    ArchiveCaptureHolder.clear();
  }

  private static ArchiveService createViaFactory(StorageConfig.ArchiveConfig config) {
    return ArchiveServiceFactory.create(config);
  }
}
