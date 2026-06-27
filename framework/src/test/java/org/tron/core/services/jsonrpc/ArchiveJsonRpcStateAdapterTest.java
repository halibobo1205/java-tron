package org.tron.core.services.jsonrpc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;
import org.tron.core.archive.DefaultArchiveService;
import org.tron.core.archive.NoopArchiveService;
import org.tron.core.archive.capture.ArchiveCaptureHolder;
import org.tron.core.archive.reader.JsonRpcArchiveStatePointResolver;
import org.tron.core.archive.reader.ResolvedArchiveStatePoint;
import org.tron.core.exception.jsonrpc.JsonRpcInternalException;
import org.tron.core.exception.jsonrpc.JsonRpcInvalidParamsException;

/**
 * Compatibility gate for the historical eth_get* path: archive-disabled and the {@code latest}
 * tag must never route through the archive, so default-OFF nodes keep their exact latest behaviour.
 * Wallet is unused on these paths, so the cases run without a live node.
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
  public void nullStorageSlotRejectedLikeLatestPath() {
    ArchiveJsonRpcStateAdapter adapter =
        new ArchiveJsonRpcStateAdapter(null, new DefaultArchiveService(true));
    // A null slot must be rejected as invalid params (like TronJsonRpcImpl.getStorageAt), not
    // silently read as slot 0. normalizeSlot runs before the reader opens, so no Wallet is needed.
    assertThrows(JsonRpcInvalidParamsException.class,
        () -> adapter.getStorageAt("0xabd4b9367799eaa3197fecb144eb71de1e049abc", null, "0x10"));
  }
}
