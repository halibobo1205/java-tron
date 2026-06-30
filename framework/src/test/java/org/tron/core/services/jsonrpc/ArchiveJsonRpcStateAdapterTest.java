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
import org.tron.core.archive.reader.JsonRpcArchiveStatePointResolver;
import org.tron.core.archive.reader.ResolvedArchiveStatePoint;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.exception.jsonrpc.JsonRpcInternalException;
import org.tron.core.exception.jsonrpc.JsonRpcInvalidParamsException;
import org.tron.protos.Protocol.Block;

/**
 * Compatibility gate for the historical eth_get* path: archive-disabled and the {@code latest}
 * tag must never route through the archive, so default-OFF nodes keep their exact latest behaviour.
 * The cases use a lightweight mocked wallet where block resolution is needed.
 */
public class ArchiveJsonRpcStateAdapterTest {

  @After
  public void clearCaptureHolder() {
    // An enabled DefaultArchiveService installs a static capture engine; clear between tests.
    ArchiveCaptureHolder.clear();
  }

  @Test
  public void disabledArchiveNeverRoutesToArchive() {
    ArchiveJsonRpcStateAdapter adapter =
        new ArchiveJsonRpcStateAdapter(null, NoopArchiveService.INSTANCE);
    assertFalse(adapter.shouldUseArchive("latest"));
    assertFalse(adapter.shouldUseArchive("earliest"));
    assertFalse(adapter.shouldUseArchive("0x10"));
  }

  @Test
  public void enabledArchiveBypassesLatestButRoutesHistorical() {
    ArchiveJsonRpcStateAdapter adapter =
        new ArchiveJsonRpcStateAdapter(null, new DefaultArchiveService(true));
    // latest stays on live state regardless of case.
    assertFalse(adapter.shouldUseArchive("latest"));
    assertFalse(adapter.shouldUseArchive("LATEST"));
    // every other selector is served from the archive.
    assertTrue(adapter.shouldUseArchive("earliest"));
    assertTrue(adapter.shouldUseArchive("finalized"));
    assertTrue(adapter.shouldUseArchive("0x10"));
  }

  @Test
  public void resolverLatestTagResolvesToLatestPointWithoutWallet()
      throws JsonRpcInvalidParamsException, JsonRpcInternalException {
    JsonRpcArchiveStatePointResolver resolver =
        new JsonRpcArchiveStatePointResolver(null, new DefaultArchiveService(true));
    ResolvedArchiveStatePoint resolved = resolver.resolveBlockEnd("latest");
    assertTrue(resolved.isLatest());
  }

  @Test
  public void midChainArchiveRejectsMissingStateInsideCoverage() throws Exception {
    DefaultArchiveService svc = new DefaultArchiveService(true);
    svc.getTxNumIndex().beginBlock(5, ArchiveSource.NORMAL);
    svc.getTxNumIndex().allocateSystemTx(5, ArchivePhase.BLOCK_PREPARE);
    svc.getTxNumIndex().allocateSystemTx(5, ArchivePhase.BLOCK_FINALIZE);
    svc.getTxNumIndex().commitBlock(5, 0); // first archived block = 5 -> mid-chain
    Wallet wallet = mock(Wallet.class);
    when(wallet.getBlockByNum(5)).thenReturn(block(5));
    ArchiveJsonRpcStateAdapter adapter = new ArchiveJsonRpcStateAdapter(wallet, svc);
    String addr = "0xabd4b9367799eaa3197fecb144eb71de1e049abc";

    JsonRpcInternalException balance = assertThrows(JsonRpcInternalException.class,
        () -> adapter.getBalance(addr, "0x5"));
    assertTrue(balance.getMessage().contains(
        "archive account is unknown before mid-chain coverage"));

    JsonRpcInternalException code = assertThrows(JsonRpcInternalException.class,
        () -> adapter.getCode(addr, "0x5"));
    assertTrue(code.getMessage().contains(
        "archive code is unknown before mid-chain coverage"));
  }

  @Test
  public void midChainArchiveRejectsBlocksBeforeCoverage() {
    DefaultArchiveService svc = new DefaultArchiveService(true);
    svc.getTxNumIndex().beginBlock(5, ArchiveSource.NORMAL);
    svc.getTxNumIndex().allocateSystemTx(5, ArchivePhase.BLOCK_PREPARE);
    svc.getTxNumIndex().allocateSystemTx(5, ArchivePhase.BLOCK_FINALIZE);
    svc.getTxNumIndex().commitBlock(5, 0);
    Wallet wallet = mock(Wallet.class);
    when(wallet.getBlockByNum(4)).thenReturn(block(4));
    ArchiveJsonRpcStateAdapter adapter = new ArchiveJsonRpcStateAdapter(wallet, svc);
    String addr = "0xabd4b9367799eaa3197fecb144eb71de1e049abc";

    JsonRpcInternalException ex = assertThrows(JsonRpcInternalException.class,
        () -> adapter.getBalance(addr, "0x4"));
    assertTrue(ex.getMessage().contains("lowest supported block is 5"));
  }

  @Test
  public void nullStorageSlotRejectedLikeLatestPath() {
    ArchiveJsonRpcStateAdapter adapter =
        new ArchiveJsonRpcStateAdapter(null, new DefaultArchiveService(true));
    // A null slot must be rejected as invalid params (like TronJsonRpcImpl.getStorageAt), not
    // silently read as slot 0. normalizeSlot runs before the reader opens, so no Wallet is needed.
    assertThrows(JsonRpcInvalidParamsException.class,
        () -> adapter.getStorageAt("0xabd4b9367799eaa3197fecb144eb71de1e049abc", null, "0x10"));
  }

  private static Block block(long num) {
    return new BlockCapsule(num, Sha256Hash.ZERO_HASH, 1L, ByteString.EMPTY).getInstance();
  }
}
