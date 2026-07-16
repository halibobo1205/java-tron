package org.tron.core.services.jsonrpc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import io.prometheus.client.CollectorRegistry;
import org.junit.After;
import org.junit.Test;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.prometheus.MetricKeys;
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

/**
 * Compatibility gate for the historical eth_call path: {@code latest} stays on latest handling,
 * but historical selectors must never fall back to latest execution when archive is disabled.
 * The cases use a lightweight mocked wallet where block resolution is needed.
 */
public class HistoricalEthCallSupportTest {

  @After
  public void clearCaptureHolder() {
    // An enabled DefaultArchiveService installs a static capture engine; clear between tests.
    ArchiveCaptureHolder.clear();
  }

  @Test
  public void disabledArchiveRoutesHistoricalSelectorsToFailClosedSupport() {
    HistoricalEthCallSupport support =
        new HistoricalEthCallSupport(null, NoopArchiveService.INSTANCE);
    assertFalse(support.shouldUseArchive("latest"));
    assertTrue(support.shouldUseArchive("earliest"));
    assertTrue(support.shouldUseArchive("0x10"));

    JsonRpcInternalException ex = assertThrows(JsonRpcInternalException.class,
        () -> support.call(null, null, 0L, null, "0x10"));
    assertTrue(ex.getMessage().contains("archive is not available"));
  }

  @Test
  public void disabledArchiveValidatesUnsupportedOrMalformedSelectorsBeforeAvailability() {
    HistoricalEthCallSupport support =
        new HistoricalEthCallSupport(null, NoopArchiveService.INSTANCE);

    JsonRpcInvalidParamsException pending = assertThrows(JsonRpcInvalidParamsException.class,
        () -> support.call(null, null, 0L, null, "pending"));
    assertTrue(pending.getMessage().contains(JsonRpcApiUtil.TAG_PENDING_SUPPORT_ERROR));

    JsonRpcInvalidParamsException malformed = assertThrows(JsonRpcInvalidParamsException.class,
        () -> support.call(null, null, 0L, null, "not-a-block"));
    assertTrue(malformed.getMessage().contains(JsonRpcApiUtil.BLOCK_NUM_ERROR));
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

  @Test
  public void canonicalHashRaceSettlesEthCallMetricsAsFailed() {
    boolean metricsPreviouslyEnabled =
        CommonParameter.getInstance().isMetricsPrometheusEnable();
    CommonParameter.getInstance().setMetricsPrometheusEnable(true);
    try {
      double failedBefore = queryCounter("failed");
      double completedBefore = queryCounter("completed");
      Wallet wallet = mock(Wallet.class);
      when(wallet.getBlockByNum(5)).thenReturn(block(5), blockWithParentSeed(5, (byte) 9));
      HistoricalEthCallSupport support =
          new HistoricalEthCallSupport(wallet, midChainArchiveService());

      JsonRpcInternalException ex = assertThrows(JsonRpcInternalException.class,
          () -> support.call(null, null, 0L, null, "0x5"));

      assertTrue(ex.getMessage().contains("hash mismatch"));
      assertEquals(failedBefore + 1D, queryCounter("failed"), 0D);
      assertEquals(completedBefore, queryCounter("completed"), 0D);
    } finally {
      CommonParameter.getInstance().setMetricsPrometheusEnable(metricsPreviouslyEnabled);
    }
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

  private static double queryCounter(String result) {
    Double value = CollectorRegistry.defaultRegistry.getSampleValue(
        MetricKeys.Counter.ARCHIVE_QUERIES + "_total",
        new String[] {"result"}, new String[] {result});
    return value == null ? 0D : value;
  }
}
