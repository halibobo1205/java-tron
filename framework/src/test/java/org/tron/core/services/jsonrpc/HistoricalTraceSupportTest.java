package org.tron.core.services.jsonrpc;

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
import org.tron.core.archive.capture.ArchiveCaptureHolder;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.exception.jsonrpc.JsonRpcInternalException;
import org.tron.core.exception.jsonrpc.JsonRpcInvalidParamsException;
import org.tron.protos.Protocol.Block;

public class HistoricalTraceSupportTest {

  @After
  public void clearCaptureHolder() {
    ArchiveCaptureHolder.clear();
  }

  @Test
  public void midChainArchiveRejectsTraceCallBeforeCoverage() {
    Wallet wallet = mock(Wallet.class);
    when(wallet.getBlockByNum(4)).thenReturn(block(4));
    HistoricalTraceSupport support =
        new HistoricalTraceSupport(wallet, midChainArchiveService());

    JsonRpcInternalException ex = assertThrows(JsonRpcInternalException.class,
        () -> support.traceCall(null, null, 0L, null, "0x4"));
    assertTrue(ex.getMessage().contains("lowest supported block is 5"));
  }

  @Test
  public void midChainTraceTransactionLooksUpTransactionBeforeArchivePosition() {
    Wallet wallet = mock(Wallet.class);
    HistoricalTraceSupport support =
        new HistoricalTraceSupport(wallet, midChainArchiveService());
    assertThrows(JsonRpcInvalidParamsException.class,
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

  private static Block block(long num) {
    return new BlockCapsule(num, Sha256Hash.ZERO_HASH, 1L, ByteString.EMPTY).getInstance();
  }
}
