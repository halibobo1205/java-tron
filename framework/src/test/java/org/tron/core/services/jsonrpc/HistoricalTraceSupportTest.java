package org.tron.core.services.jsonrpc;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import java.util.Collections;
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
import org.tron.core.exception.jsonrpc.JsonRpcInvalidParamsException;
import org.tron.protos.Protocol.Block;

public class HistoricalTraceSupportTest {

  @After
  public void clearCaptureHolder() {
    ArchiveCaptureHolder.clear();
  }

  @Test
  public void disabledArchiveTraceCallFailsClosedAsArchiveUnavailable() {
    HistoricalTraceSupport support =
        new HistoricalTraceSupport(null, NoopArchiveService.INSTANCE);

    JsonRpcInternalException ex = assertThrows(JsonRpcInternalException.class,
        () -> support.traceCall(null, null, 0L, null, "0x10"));
    assertTrue(ex.getMessage().contains("archive is not available"));
  }

  @Test
  public void disabledArchiveTraceTransactionFailsClosedBeforeWalletLookup() {
    Wallet wallet = mock(Wallet.class);
    HistoricalTraceSupport support =
        new HistoricalTraceSupport(wallet, NoopArchiveService.INSTANCE);

    JsonRpcInternalException ex = assertThrows(JsonRpcInternalException.class,
        () -> support.traceTransaction(new byte[32], null));

    assertTrue(ex.getMessage().contains("archive is not available"));
    verifyNoInteractions(wallet);
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
  public void traceTransactionChecksArchivePositionBeforeWalletLookup() {
    Wallet wallet = mock(Wallet.class);
    HistoricalTraceSupport support =
        new HistoricalTraceSupport(wallet, midChainArchiveService());

    JsonRpcInternalException ex = assertThrows(JsonRpcInternalException.class,
        () -> support.traceTransaction(new byte[32], null));

    assertTrue(ex.getMessage().contains("transaction not in archive"));
    verifyNoInteractions(wallet);
  }

  @Test
  public void traceTransactionRejectsMalformedTxIdBeforeWalletLookup() {
    Wallet wallet = mock(Wallet.class);
    HistoricalTraceSupport support =
        new HistoricalTraceSupport(wallet, midChainArchiveService());

    JsonRpcInvalidParamsException ex = assertThrows(JsonRpcInvalidParamsException.class,
        () -> support.traceTransaction(new byte[] {1}, null));

    assertTrue(ex.getMessage().contains("invalid transaction hash"));
    verifyNoInteractions(wallet);
  }

  @Test
  public void traceCallRejectsUnsupportedTraceOptions() {
    HistoricalTraceSupport support =
        new HistoricalTraceSupport(mock(Wallet.class), midChainArchiveService());

    JsonRpcInvalidParamsException ex = assertThrows(JsonRpcInvalidParamsException.class,
        () -> support.traceCall(null, null, 0L, null, "0x5",
            Collections.singletonMap("tracer", "callTracer")));

    assertTrue(ex.getMessage().contains("only the default struct-log tracer"));
  }

  @Test
  public void traceCallRejectsBlockHashChangedAfterResolution() {
    Wallet wallet = mock(Wallet.class);
    when(wallet.getBlockByNum(5)).thenReturn(block(5), blockWithParentSeed(5, (byte) 9));
    HistoricalTraceSupport support =
        new HistoricalTraceSupport(wallet, midChainArchiveService());

    JsonRpcInternalException ex = assertThrows(JsonRpcInternalException.class,
        () -> support.traceCall(null, null, 0L, null, "0x5"));

    assertTrue(ex.getMessage().contains("hash mismatch"));
  }

  @Test
  public void traceTransactionRejectsUnsupportedTraceOptionsBeforeLookup() {
    HistoricalTraceSupport support =
        new HistoricalTraceSupport(mock(Wallet.class), midChainArchiveService());

    JsonRpcInvalidParamsException ex = assertThrows(JsonRpcInvalidParamsException.class,
        () -> support.traceTransaction(new byte[] {1},
            Collections.singletonMap("disableStack", Boolean.TRUE)));

    assertTrue(ex.getMessage().contains("only the default struct-log tracer"));
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

  private static Block blockWithParentSeed(long num, byte seed) {
    byte[] parent = new byte[32];
    parent[31] = seed;
    return new BlockCapsule(num, Sha256Hash.wrap(parent), 1L, ByteString.EMPTY).getInstance();
  }

  private static byte[] blockHash(long num) {
    return new BlockCapsule(num, Sha256Hash.ZERO_HASH, 1L, ByteString.EMPTY)
        .getBlockId().getBytes();
  }
}
