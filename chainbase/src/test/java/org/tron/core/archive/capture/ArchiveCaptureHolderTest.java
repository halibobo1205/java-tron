package org.tron.core.archive.capture;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;
import org.tron.core.archive.ArchiveExecutionContext;
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveSource;
import org.tron.core.archive.domain.DefaultArchiveDomainCatalog;
import org.tron.core.archive.domain.DefaultArchiveDomainRegistry;
import org.tron.core.archive.domain.DynamicKeyPolicy;
import org.tron.core.archive.txnum.ArchiveTxPosition;

public class ArchiveCaptureHolderTest {

  @After
  public void tearDown() {
    ArchiveCaptureHolder.clear();
  }

  private static ArchiveCaptureEngine engineWithActiveContext() {
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    context.enter(new ArchiveTxPosition(1, 1, ArchivePhase.USER_TX, ArchiveSource.NORMAL, 0, null));
    return new ArchiveCaptureEngine(new DefaultArchiveDomainRegistry(),
        new DefaultArchiveDomainCatalog(), new DynamicKeyPolicy(), context);
  }

  @Test
  public void noEngineSetIsNoOp() {
    ArchiveCaptureHolder.clear();
    assertFalse(ArchiveCaptureHolder.isActive());
    assertFalse(ArchiveCaptureHolder.capturesStore("account")); // false without an engine
    // must not throw even with garbage input
    ArchiveCaptureHolder.capturePut("account", new byte[5], null, new byte[] {(byte) 0xff});
    ArchiveCaptureHolder.captureDelete("account", new byte[5], null);
  }

  @Test
  public void capturesStoreTrueOnceEngineSet() {
    ArchiveCaptureHolder.set(engineWithActiveContext());
    assertTrue(ArchiveCaptureHolder.capturesStore("account"));    // captured store
    assertFalse(ArchiveCaptureHolder.capturesStore("block"));     // excluded store
  }

  @Test
  public void captureFailureIsSwallowedNotPropagated() {
    ArchiveCaptureEngine engine = engineWithActiveContext();
    ArchiveCaptureHolder.set(engine);
    assertTrue(ArchiveCaptureHolder.isActive());
    // invalid Account proto bytes -> codec throws -> holder must swallow (block apply unaffected)
    ArchiveCaptureHolder.capturePut("account", new byte[21], null, new byte[] {(byte) 0xff, 0x01});
    assertTrue("failed capture must not be recorded", engine.records().isEmpty());
  }
}
