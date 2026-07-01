package org.tron.core.services.jsonrpc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import org.junit.After;
import org.junit.Test;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.Wallet;
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveSource;
import org.tron.core.archive.DefaultArchiveService;
import org.tron.core.archive.NoopArchiveService;
import org.tron.core.archive.capture.ArchiveCaptureHolder;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.exception.jsonrpc.JsonRpcInternalException;
import org.tron.protos.Protocol.Block;

/**
 * Compatibility gate for the historical eth_call path: archive-disabled and the {@code latest} tag
 * must never route to the archive, so default-OFF nodes keep their exact latest-only behaviour.
 * The cases use a lightweight mocked wallet where block resolution is needed.
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
  public void midChainArchiveRejectsHistoricalEthCallBeforeCoverage() {
    DefaultArchiveService svc = midChainArchiveService();
    Wallet wallet = mock(Wallet.class);
    when(wallet.getBlockByNum(4)).thenReturn(block(4));
    HistoricalEthCallSupport support = new HistoricalEthCallSupport(wallet, svc);

    JsonRpcInternalException ex = assertThrows(JsonRpcInternalException.class,
        () -> support.call(null, null, 0L, null, "0x4"));
    assertTrue(ex.getMessage().contains("lowest supported block is 5"));
  }

  private DefaultArchiveService midChainArchiveService() {
    DefaultArchiveService svc = new DefaultArchiveService(true);
    svc.getTxNumIndex().beginBlock(5, ArchiveSource.NORMAL);
    svc.getTxNumIndex().allocateSystemTx(5, ArchivePhase.BLOCK_PREPARE);
    svc.getTxNumIndex().allocateSystemTx(5, ArchivePhase.BLOCK_FINALIZE);
    svc.getTxNumIndex().commitBlock(5, blockHash(5), 0);
    return svc;
  }

  private static Block block(long num) {
    return new BlockCapsule(num, Sha256Hash.ZERO_HASH, 1L, ByteString.EMPTY).getInstance();
  }

  private static byte[] blockHash(long num) {
    return new BlockCapsule(num, Sha256Hash.ZERO_HASH, 1L, ByteString.EMPTY)
        .getBlockId().getBytes();
  }
}
