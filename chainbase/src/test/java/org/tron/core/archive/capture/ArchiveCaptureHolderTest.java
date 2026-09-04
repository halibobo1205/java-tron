package org.tron.core.archive.capture;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.After;
import org.junit.Test;
import org.tron.core.archive.ArchiveExecutionContext;
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveSource;
import org.tron.core.archive.codec.ContractStorageKeyCodec;
import org.tron.core.archive.domain.ArchiveDomain;
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

  private static ArchiveCaptureEngine engineWithoutActiveContext() {
    return new ArchiveCaptureEngine(new DefaultArchiveDomainRegistry(),
        new DefaultArchiveDomainCatalog(), new DynamicKeyPolicy(), new ArchiveExecutionContext());
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  @Test
  public void noEngineSetIsNoOp() {
    ArchiveCaptureHolder.clear();
    assertFalse(ArchiveCaptureHolder.isActive());
    assertFalse(ArchiveCaptureHolder.isCapturingCurrentTx());
    assertFalse(ArchiveCaptureHolder.capturesStore("account")); // false without an engine
    // must not throw even with garbage input
    ArchiveCaptureHolder.capturePut("account", new byte[5], null, new byte[] {(byte) 0xff});
    ArchiveCaptureHolder.captureDelete("account", new byte[5], null);
  }

  @Test
  public void txCaptureStateRequiresCurrentExecutionPosition() {
    ArchiveCaptureEngine inactive = engineWithoutActiveContext();
    ArchiveCaptureHolder.set(inactive);
    assertTrue(ArchiveCaptureHolder.isActive());
    assertFalse(ArchiveCaptureHolder.isCapturingCurrentTx());
    assertFalse(ArchiveCaptureHolder.capturesStore("account"));
    assertFalse(inactive.failure().isPresent());

    inactive.beginBlockCapture();
    assertFalse(ArchiveCaptureHolder.capturesStore("account"));
    assertTrue(inactive.failure().isPresent());

    ArchiveCaptureHolder.set(engineWithActiveContext());
    assertTrue(ArchiveCaptureHolder.isCapturingCurrentTx());
    assertTrue(ArchiveCaptureHolder.capturesStore("account"));
  }

  @Test
  public void capturesStoreTrueOnceEngineSet() {
    ArchiveCaptureHolder.set(engineWithActiveContext());
    assertTrue(ArchiveCaptureHolder.capturesStore("account"));    // captured store
    assertFalse(ArchiveCaptureHolder.capturesStore("block"));     // excluded store
    assertTrue(ArchiveCaptureHolder.capturesStore("properties", ascii("ENERGY_FEE")));
    assertFalse(ArchiveCaptureHolder.capturesStore("properties", ascii("ABI_MOVE_DONE")));
  }

  @Test
  public void clearIfDoesNotClearNewerEngine() {
    ArchiveCaptureEngine older = engineWithActiveContext();
    ArchiveCaptureEngine newer = engineWithActiveContext();
    ArchiveCaptureHolder.set(older);
    assertTrue(ArchiveCaptureHolder.isCurrent(older));
    ArchiveCaptureHolder.set(newer);
    assertFalse(ArchiveCaptureHolder.isCurrent(older));
    assertTrue(ArchiveCaptureHolder.isCurrent(newer));

    ArchiveCaptureHolder.clearIf(older);
    assertTrue(ArchiveCaptureHolder.isActive());

    ArchiveCaptureHolder.clearIf(newer);
    assertFalse(ArchiveCaptureHolder.isActive());
  }

  @Test
  public void unknownStoreLookupIsRecordedForFailClosedCommit() {
    ArchiveCaptureEngine engine = engineWithActiveContext();
    ArchiveCaptureHolder.set(engine);

    assertFalse(ArchiveCaptureHolder.capturesStore("no-such-store"));

    assertTrue("unknown store must be visible to commitBlock", engine.failure().isPresent());
    assertTrue(engine.failure().get().getMessage().contains("capturesStore(no-such-store)"));
  }

  @Test
  public void unknownStoreDuringActiveBlockWithoutTxIsRecorded() {
    ArchiveCaptureEngine engine = engineWithoutActiveContext();
    engine.beginBlockCapture();
    ArchiveCaptureHolder.set(engine);

    assertFalse(ArchiveCaptureHolder.capturesStore("no-such-store"));

    assertTrue("unknown active-block store must fail closed", engine.failure().isPresent());
    assertTrue(engine.failure().get().getMessage().contains("capturesStore(no-such-store)"));
  }

  @Test
  public void directCaptureDuringActiveBlockWithoutTxIsRecorded() {
    ArchiveCaptureEngine engine = engineWithoutActiveContext();
    engine.beginBlockCapture();
    ArchiveCaptureHolder.set(engine);

    ArchiveCaptureHolder.capturePut("account", new byte[21], null, new byte[] {1});

    assertTrue("direct capture must fail closed", engine.failure().isPresent());
    assertTrue(engine.failure().get().getMessage().contains("capturePut(account)"));
    assertTrue(engine.records().isEmpty());
  }

  @Test
  public void directSemanticCaptureDuringActiveBlockWithoutTxIsRecorded() {
    ArchiveCaptureEngine engine = engineWithoutActiveContext();
    engine.beginBlockCapture();
    ArchiveCaptureHolder.set(engine);

    ArchiveCaptureHolder.captureSemanticPut(
        ArchiveDomain.CONTRACT_STORAGE,
        new byte[ContractStorageKeyCodec.LENGTH], null, new byte[32]);

    assertTrue("direct semantic capture must fail closed", engine.failure().isPresent());
    assertTrue(engine.failure().get().getMessage().contains(
        "captureSemanticPut(CONTRACT_STORAGE)"));
    assertTrue(engine.records().isEmpty());
  }

  @Test
  public void captureFailureIsRecordedForFailClosedCommit() {
    ArchiveCaptureEngine engine = engineWithActiveContext();
    ArchiveCaptureHolder.set(engine);
    assertTrue(ArchiveCaptureHolder.isActive());
    // Invalid Account proto bytes do not propagate into the store path, but they mark the archive
    // engine failed so DefaultArchiveService.commitBlock can refuse to publish partial history.
    ArchiveCaptureHolder.capturePut("account", new byte[21], null, new byte[] {(byte) 0xff, 0x01});
    assertTrue("failed capture must not be recorded", engine.records().isEmpty());
    assertTrue("failed capture must be visible to commitBlock", engine.failure().isPresent());
  }

  @Test
  public void failureDiagnosticsCannotEscapeCaptureIsolation() {
    ArchiveCaptureEngine engine = engineWithActiveContext();
    ArchiveCaptureHolder.set(engine);
    RuntimeException failure = new RuntimeException("unrenderable") {
      @Override
      public String getMessage() {
        throw new AssertionError("message rendering failed");
      }
    };

    ArchiveCaptureHolder.recordFailure("hostileDiagnostic", failure);

    assertTrue(engine.failure().isPresent());
    assertTrue(engine.failure().get().getCause() == failure);
  }
}
