package org.tron.core.services.jsonrpc;

import static org.junit.Assert.assertThrows;

import org.junit.After;
import org.junit.Test;
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveSource;
import org.tron.core.archive.DefaultArchiveService;
import org.tron.core.archive.capture.ArchiveCaptureHolder;
import org.tron.core.exception.jsonrpc.JsonRpcInternalException;

public class HistoricalTraceSupportTest {

  @After
  public void clearCaptureHolder() {
    ArchiveCaptureHolder.clear();
  }

  @Test
  public void midChainArchiveRejectsTraceCallBeforeReadingState() {
    HistoricalTraceSupport support =
        new HistoricalTraceSupport(null, midChainArchiveService());
    assertThrows(JsonRpcInternalException.class,
        () -> support.traceCall(null, null, 0L, null, "0x5"));
  }

  @Test
  public void midChainArchiveRejectsTraceTransactionBeforeReadingState() {
    HistoricalTraceSupport support =
        new HistoricalTraceSupport(null, midChainArchiveService());
    assertThrows(JsonRpcInternalException.class,
        () -> support.traceTransaction(new byte[] {1}, null));
  }

  private DefaultArchiveService midChainArchiveService() {
    DefaultArchiveService svc = new DefaultArchiveService(true);
    svc.getTxNumIndex().beginBlock(5, ArchiveSource.NORMAL);
    svc.getTxNumIndex().allocateSystemTx(5, ArchivePhase.BLOCK_PREPARE);
    svc.getTxNumIndex().allocateSystemTx(5, ArchivePhase.BLOCK_FINALIZE);
    svc.getTxNumIndex().commitBlock(5, 0);
    return svc;
  }
}
