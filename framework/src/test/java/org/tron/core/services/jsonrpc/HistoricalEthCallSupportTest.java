package org.tron.core.services.jsonrpc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveSource;
import org.tron.core.archive.DefaultArchiveService;
import org.tron.core.archive.NoopArchiveService;
import org.tron.core.archive.capture.ArchiveCaptureHolder;
import org.tron.core.exception.jsonrpc.JsonRpcInternalException;

/**
 * Compatibility gate for the historical eth_call path: archive-disabled and the {@code latest} tag
 * must never route to the archive, so default-OFF nodes keep their exact latest-only behaviour.
 * Wallet is unused on this path, so the cases run without a live node.
 */
public class HistoricalEthCallSupportTest {

  @After
  public void clearCaptureHolder() {
    // An enabled DefaultArchiveService installs a static capture engine; clear between tests.
    ArchiveCaptureHolder.clear();
  }

  @Test
  public void disabledArchiveNeverRoutesToArchive() {
    HistoricalEthCallSupport support =
        new HistoricalEthCallSupport(null, NoopArchiveService.INSTANCE);
    assertFalse(support.shouldUseArchive("latest"));
    assertFalse(support.shouldUseArchive("earliest"));
    assertFalse(support.shouldUseArchive("0x10"));
  }

  @Test
  public void enabledArchiveBypassesLatestButRoutesHistorical() {
    HistoricalEthCallSupport support =
        new HistoricalEthCallSupport(null, new DefaultArchiveService(true));
    assertFalse(support.shouldUseArchive("latest"));
    assertFalse(support.shouldUseArchive("LATEST"));
    assertTrue(support.shouldUseArchive("earliest"));
    assertTrue(support.shouldUseArchive("0x10"));
  }

  @Test
  public void midChainArchiveRejectsHistoricalEthCallBeforeReadingState() {
    DefaultArchiveService svc = midChainArchiveService();
    HistoricalEthCallSupport support = new HistoricalEthCallSupport(null, svc);
    assertThrows(JsonRpcInternalException.class,
        () -> support.call(null, null, 0L, null, "0x5"));
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
