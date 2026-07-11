package org.tron.core.services.jsonrpc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import org.junit.After;
import org.junit.Test;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.Wallet;
import org.tron.core.archive.ArchiveException;
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
import org.tron.protos.Protocol.Account;
import org.tron.protos.Protocol.Block;

/**
 * Compatibility gate for the historical eth_get* path: {@code latest} must stay live, but
 * historical selectors only enter the archive adapter when archive is enabled. The cases use a
 * lightweight mocked wallet where block resolution is needed.
 */
public class ArchiveJsonRpcStateAdapterTest {

  @After
  public void clearCaptureHolder() {
    // An enabled DefaultArchiveService installs a static capture engine; clear between tests.
    ArchiveCaptureHolder.clear();
  }

  @Test
  public void disabledArchiveDoesNotRouteHistoricalSelectorsToAdapter() {
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
  public void resolverAcceptsDecimalHistoricalBlockNumber() throws Exception {
    DefaultArchiveService svc = new DefaultArchiveService(true);
    svc.getTxNumIndex().beginBlock(16, ArchiveSource.NORMAL);
    svc.getTxNumIndex().allocateSystemTx(16, ArchivePhase.BLOCK_PREPARE);
    svc.getTxNumIndex().allocateSystemTx(16, ArchivePhase.BLOCK_FINALIZE);
    svc.getTxNumIndex().commitBlock(16, blockHash(16), 0);
    Wallet wallet = mock(Wallet.class);
    when(wallet.getBlockByNum(16)).thenReturn(block(16));
    JsonRpcArchiveStatePointResolver resolver =
        new JsonRpcArchiveStatePointResolver(wallet, svc);

    ResolvedArchiveStatePoint resolved = resolver.resolveBlockEnd("16");

    assertFalse(resolved.isLatest());
    assertEquals(16L, resolved.getPoint().getBlockNum());
    assertEquals(1L, resolved.getPoint().getTxNum());
  }

  @Test
  public void midChainArchiveRejectsMissingStateWithoutReadingLatest() throws Exception {
    DefaultArchiveService svc = new DefaultArchiveService(true);
    svc.getTxNumIndex().beginBlock(5, ArchiveSource.NORMAL);
    svc.getTxNumIndex().allocateSystemTx(5, ArchivePhase.BLOCK_PREPARE);
    svc.getTxNumIndex().allocateSystemTx(5, ArchivePhase.BLOCK_FINALIZE);
    svc.getTxNumIndex().commitBlock(5, blockHash(5), 0); // first archived block = 5 -> mid-chain
    Wallet wallet = mock(Wallet.class);
    when(wallet.getBlockByNum(5)).thenReturn(block(5));
    when(wallet.getAccount(any(Account.class))).thenReturn(
        Account.newBuilder().setBalance(7L).build());
    ArchiveJsonRpcStateAdapter adapter = new ArchiveJsonRpcStateAdapter(wallet, svc);
    String addr = "0xabd4b9367799eaa3197fecb144eb71de1e049abc";

    JsonRpcInternalException balance = assertThrows(JsonRpcInternalException.class,
        () -> adapter.getBalance(addr, "0x5"));
    assertEquals("archive account is unknown before mid-chain coverage", balance.getMessage());

    JsonRpcInternalException code = assertThrows(JsonRpcInternalException.class,
        () -> adapter.getCode(addr, "0x5"));
    assertEquals("archive code is unknown before mid-chain coverage", code.getMessage());

    verify(wallet, never()).getAccount(any(Account.class));
  }

  @Test
  public void midChainTombstoneRendersAsDefaultZeroNotError() throws Exception {
    DefaultArchiveService svc = new DefaultArchiveService(true);
    byte[] addr = ByteArray.fromHexString("41abd4b9367799eaa3197fecb144eb71de1e049abc");
    // Publish block 5 (mid-chain: first archived block = 5) carrying a captured account DELETE for
    // addr, so the temporal store holds a TOMBSTONE (known-deleted) -- distinct from MISSING
    // (never captured), which the coverage gate turns into an error.
    BlockCapsule b5 = blockCapsule(5);
    svc.beginBlock(b5, ArchiveSource.NORMAL);
    svc.beginSystemTx(b5, ArchivePhase.BLOCK_PREPARE);
    svc.endTx();
    svc.beginSystemTx(b5, ArchivePhase.BLOCK_FINALIZE);
    svc.getCaptureEngine().captureDelete("account", addr,
        Account.newBuilder().setBalance(5L).build().toByteArray());
    svc.endTx();
    svc.commitBlock(b5);
    svc.publishSolidifiedBlocks(5);

    Wallet wallet = mock(Wallet.class);
    when(wallet.getBlockByNum(5)).thenReturn(b5.getInstance());
    ArchiveJsonRpcStateAdapter adapter = new ArchiveJsonRpcStateAdapter(wallet, svc);

    // A KNOWN-deleted account at a mid-chain block renders as 0x0, NOT the "unknown before
    // mid-chain coverage" error that a MISSING (never-captured) account produces.
    assertEquals("0x0",
        adapter.getBalance("0xabd4b9367799eaa3197fecb144eb71de1e049abc", "0x5"));
  }

  @Test
  public void midChainArchiveRejectsBlocksBeforeCoverage() {
    DefaultArchiveService svc = new DefaultArchiveService(true);
    svc.getTxNumIndex().beginBlock(5, ArchiveSource.NORMAL);
    svc.getTxNumIndex().allocateSystemTx(5, ArchivePhase.BLOCK_PREPARE);
    svc.getTxNumIndex().allocateSystemTx(5, ArchivePhase.BLOCK_FINALIZE);
    svc.getTxNumIndex().commitBlock(5, blockHash(5), 0);
    Wallet wallet = mock(Wallet.class);
    when(wallet.getBlockByNum(4)).thenReturn(block(4));
    ArchiveJsonRpcStateAdapter adapter = new ArchiveJsonRpcStateAdapter(wallet, svc);
    String addr = "0xabd4b9367799eaa3197fecb144eb71de1e049abc";

    JsonRpcInternalException ex = assertThrows(JsonRpcInternalException.class,
        () -> adapter.getBalance(addr, "0x4"));
    assertTrue(ex.getMessage().contains("lowest supported block is 5"));
  }

  @Test
  public void historicalStateRejectsBlockHashChangedAfterResolution() {
    DefaultArchiveService svc = new DefaultArchiveService(true);
    svc.getTxNumIndex().beginBlock(5, ArchiveSource.NORMAL);
    svc.getTxNumIndex().allocateSystemTx(5, ArchivePhase.BLOCK_PREPARE);
    svc.getTxNumIndex().allocateSystemTx(5, ArchivePhase.BLOCK_FINALIZE);
    svc.getTxNumIndex().commitBlock(5, blockHash(5, 1), 0);
    Wallet wallet = mock(Wallet.class);
    when(wallet.getBlockByNum(5)).thenReturn(block(5, 1), block(5, 2));
    ArchiveJsonRpcStateAdapter adapter = new ArchiveJsonRpcStateAdapter(wallet, svc);

    JsonRpcInternalException ex = assertThrows(JsonRpcInternalException.class,
        () -> adapter.getBalance("0xabd4b9367799eaa3197fecb144eb71de1e049abc", "0x5"));

    assertEquals("archive history hash mismatch for block 5", ex.getMessage());
  }

  @Test
  public void historicalGetterRejectsRequestedBlockHashMismatch() {
    DefaultArchiveService svc = new DefaultArchiveService(true);
    svc.getTxNumIndex().beginBlock(5, ArchiveSource.NORMAL);
    svc.getTxNumIndex().allocateSystemTx(5, ArchivePhase.BLOCK_PREPARE);
    svc.getTxNumIndex().allocateSystemTx(5, ArchivePhase.BLOCK_FINALIZE);
    svc.getTxNumIndex().commitBlock(5, blockHash(5, 1), 0);
    Wallet wallet = mock(Wallet.class);
    when(wallet.getBlockByNum(5)).thenReturn(block(5, 1));
    ArchiveJsonRpcStateAdapter adapter = new ArchiveJsonRpcStateAdapter(wallet, svc);

    JsonRpcInternalException ex = assertThrows(JsonRpcInternalException.class,
        () -> adapter.getBalance("0xabd4b9367799eaa3197fecb144eb71de1e049abc", "0x5",
            blockHash(5, 2)));

    assertEquals("archive history hash mismatch for block 5", ex.getMessage());
  }

  @Test
  public void historicalRpcFailsClosedAfterArchiveFatalError() {
    DefaultArchiveService svc = new DefaultArchiveService(true);
    BlockCapsule archiveBlock = blockCapsule(5);
    svc.beginBlock(archiveBlock, ArchiveSource.NORMAL);
    svc.beginSystemTx(archiveBlock, ArchivePhase.BLOCK_PREPARE);
    svc.endTx();
    assertThrows(ArchiveException.class, () -> svc.commitBlock(archiveBlock));

    Wallet wallet = mock(Wallet.class);
    when(wallet.getBlockByNum(5)).thenReturn(archiveBlock.getInstance());
    ArchiveJsonRpcStateAdapter adapter = new ArchiveJsonRpcStateAdapter(wallet, svc);

    JsonRpcInternalException ex = assertThrows(JsonRpcInternalException.class,
        () -> adapter.getBalance("0xabd4b9367799eaa3197fecb144eb71de1e049abc", "0x5"));
    assertTrue(ex.getMessage().contains("archive is unavailable after fatal failure"));
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
    return blockCapsule(num).getInstance();
  }

  private static Block block(long num, long timestamp) {
    return blockCapsule(num, timestamp).getInstance();
  }

  private static byte[] blockHash(long num) {
    return blockCapsule(num).getBlockId().getBytes();
  }

  private static byte[] blockHash(long num, long timestamp) {
    return blockCapsule(num, timestamp).getBlockId().getBytes();
  }

  private static BlockCapsule blockCapsule(long num) {
    return blockCapsule(num, 1L);
  }

  private static BlockCapsule blockCapsule(long num, long timestamp) {
    return new BlockCapsule(num, Sha256Hash.ZERO_HASH, timestamp, ByteString.EMPTY);
  }
}
