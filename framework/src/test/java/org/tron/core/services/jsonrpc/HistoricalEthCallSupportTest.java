package org.tron.core.services.jsonrpc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import org.tron.core.archive.ArchiveService;
import org.tron.core.archive.ArchiveSource;
import org.tron.core.archive.DefaultArchiveService;
import org.tron.core.archive.NoopArchiveService;
import org.tron.core.archive.capture.ArchiveCaptureHolder;
import org.tron.core.archive.query.ArchiveQueryLimits;
import org.tron.core.archive.query.QueryContext;
import org.tron.core.archive.reader.ArchiveReadResult;
import org.tron.core.archive.reader.ArchiveStatePoint;
import org.tron.core.archive.reader.ArchiveStateReader;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.exception.jsonrpc.JsonRpcInternalException;
import org.tron.core.exception.jsonrpc.JsonRpcInvalidParamsException;
import org.tron.core.exception.jsonrpc.JsonRpcInvalidRequestException;
import org.tron.core.vm.archive.HistoricalConstantCallExecutor;
import org.tron.core.vm.archive.HistoricalConstantCallResult;
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
    when(wallet.getBlockByNumWithoutCache(4)).thenReturn(block(4));
    HistoricalEthCallSupport support = new HistoricalEthCallSupport(wallet, svc);

    try {
      JsonRpcInternalException ex = assertThrows(JsonRpcInternalException.class,
          () -> support.call(null, null, 0L, null, "0x4"));
      assertTrue(ex.getMessage().contains("complete from-genesis"));
      verifyNoInteractions(wallet);
    } finally {
      svc.close();
    }
  }

  @Test
  public void supportConstantOffRejectsHistoricalCallAfterCanonicalValidation() {
    boolean previouslySupported = CommonParameter.getInstance().isSupportConstant();
    DefaultArchiveService svc = genesisCompleteArchiveService();
    Wallet wallet = mock(Wallet.class);
    Block canonical = block(0);
    byte[] canonicalHash = new BlockCapsule(canonical).getBlockId().getBytes();
    when(wallet.getBlockByNumWithoutCache(0)).thenReturn(canonical);
    when(wallet.getBlockIdByNumWithoutCache(0)).thenReturn(canonicalHash);
    CommonParameter.getInstance().setSupportConstant(false);
    try {
      JsonRpcInvalidRequestException failure = assertThrows(
          JsonRpcInvalidRequestException.class,
          () -> new HistoricalEthCallSupport(wallet, svc)
              .call(null, null, 0L, null, "0x0"));

      assertTrue(failure.getMessage().contains("does not support constant"));
      verify(wallet).getBlockByNumWithoutCache(0L);
      verify(wallet).getBlockIdByNumWithoutCache(0L);
      verify(wallet, never()).getBlockByNum(0L);
    } finally {
      CommonParameter.getInstance().setSupportConstant(previouslySupported);
      svc.close();
    }
  }

  @Test
  public void hashSelectorUsesOnlyCachelessCanonicalBlockReads() {
    boolean previouslySupported = CommonParameter.getInstance().isSupportConstant();
    DefaultArchiveService svc = genesisCompleteArchiveService();
    Wallet wallet = mock(Wallet.class);
    Block canonical = block(0L);
    byte[] hash = new BlockCapsule(canonical).getBlockId().getBytes();
    ByteString hashBytes = ByteString.copyFrom(hash);
    when(wallet.getBlockByIdWithoutCache(hashBytes)).thenReturn(canonical);
    when(wallet.getBlockIdByNumWithoutCache(0L)).thenReturn(hash);
    CommonParameter.getInstance().setSupportConstant(false);
    try {
      JsonRpcInvalidRequestException failure = assertThrows(
          JsonRpcInvalidRequestException.class,
          () -> new HistoricalEthCallSupport(wallet, svc)
              .call(null, null, 0L, null, null, hash));

      assertTrue(failure.getMessage().contains("does not support constant"));
      verify(wallet).getBlockByIdWithoutCache(hashBytes);
      verify(wallet, never()).getBlockByNumWithoutCache(0L);
      verify(wallet, times(2)).getBlockIdByNumWithoutCache(0L);
      verify(wallet, never()).getBlockById(any(ByteString.class));
      verify(wallet, never()).getBlockByNum(0L);
    } finally {
      CommonParameter.getInstance().setSupportConstant(previouslySupported);
      svc.close();
    }
  }

  @Test
  public void canonicalHashRaceSettlesEthCallMetricsAsFailed() throws Exception {
    boolean metricsPreviouslyEnabled =
        CommonParameter.getInstance().isMetricsPrometheusEnable();
    CommonParameter.getInstance().setMetricsPrometheusEnable(true);
    try {
      double failedBefore = queryCounter("failed");
      double completedBefore = queryCounter("completed");
      Wallet wallet = mock(Wallet.class);
      when(wallet.getBlockByNumWithoutCache(0)).thenReturn(block(0));
      when(wallet.getBlockIdByNumWithoutCache(0)).thenReturn(
          new BlockCapsule(blockWithParentSeed(0, (byte) 9)).getBlockId().getBytes());
      HistoricalEthCallSupport support =
          new HistoricalEthCallSupport(wallet, genesisCompleteArchiveService());

      JsonRpcInternalException ex = assertThrows(JsonRpcInternalException.class,
          () -> support.call(null, null, 0L, null, "0x0"));

      assertTrue(ex.getMessage().contains("hash mismatch"));
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(1L);
      while (queryCounter("failed") < failedBefore + 1D && System.nanoTime() < deadline) {
        Thread.sleep(10L);
      }
      assertEquals(failedBefore + 1D, queryCounter("failed"), 0D);
      assertEquals(completedBefore, queryCounter("completed"), 0D);
    } finally {
      CommonParameter.getInstance().setMetricsPrometheusEnable(metricsPreviouslyEnabled);
    }
  }

  @Test
  public void canonicalReorgDuringExecutionRejectsOrphanStateResult() throws Exception {
    Wallet wallet = mock(Wallet.class);
    ArchiveService archiveService = mock(ArchiveService.class);
    ArchiveStateReader reader = mock(ArchiveStateReader.class);
    HistoricalConstantCallExecutor executor = mock(HistoricalConstantCallExecutor.class);
    Block canonical = block(5);
    byte[] canonicalHash = new BlockCapsule(canonical).getBlockId().getBytes();
    when(archiveService.isEnabled()).thenReturn(true);
    when(reader.getPoint()).thenReturn(ArchiveStatePoint.blockEnd(5L, canonicalHash, 2L));
    when(reader.getQueryContext()).thenReturn(
        new QueryContext(ArchiveQueryLimits.unlimited()));
    when(reader.isGenesisComplete()).thenReturn(true);
    when(reader.getDynamicProperty(any(byte[].class)))
        .thenReturn(ArchiveReadResult.present(new byte[Long.BYTES]));
    when(archiveService.openBlockEndReader(anyLong(),
        org.mockito.ArgumentMatchers.<java.util.function.LongFunction<byte[]>>any()))
        .thenAnswer(invocation -> {
          long blockNum = invocation.getArgument(0);
          java.util.function.LongFunction<byte[]> resolver = invocation.getArgument(1);
          resolver.apply(blockNum);
          return reader;
        });
    when(wallet.getBlockByNumWithoutCache(5L)).thenReturn(canonical);
    when(wallet.getBlockIdByNumWithoutCache(5L)).thenReturn(
        canonicalHash,
        new BlockCapsule(blockWithParentSeed(5L, (byte) 9)).getBlockId().getBytes());
    when(executor.execute(any(), any(), any(), any(), anyBoolean()))
        .thenReturn(HistoricalConstantCallResult.of(new byte[] {1}, false, null));
    HistoricalEthCallSupport support =
        new HistoricalEthCallSupport(wallet, archiveService, executor, () -> { });
    byte[] address = new byte[21];
    address[0] = 0x41;

    JsonRpcInternalException failure = assertThrows(JsonRpcInternalException.class,
        () -> support.call(address, address, 0L, new byte[0], "0x5"));

    assertTrue(failure.getMessage(), failure.getMessage().contains("hash mismatch"));
  }

  private DefaultArchiveService midChainArchiveService() {
    DefaultArchiveService svc = new DefaultArchiveService(true);
    svc.getTxNumIndex().beginBlock(5, ArchiveSource.NORMAL);
    svc.getTxNumIndex().allocateSystemTx(5, ArchivePhase.BLOCK_PREPARE);
    svc.getTxNumIndex().allocateSystemTx(5, ArchivePhase.BLOCK_FINALIZE);
    svc.getTxNumIndex().commitBlock(5, blockHash(5), 0);
    return svc;
  }

  private DefaultArchiveService genesisCompleteArchiveService() {
    DefaultArchiveService svc = new DefaultArchiveService(true);
    svc.getTxNumIndex().beginBlock(0, ArchiveSource.NORMAL);
    svc.getTxNumIndex().allocateSystemTx(0, ArchivePhase.BLOCK_PREPARE);
    svc.getTxNumIndex().allocateSystemTx(0, ArchivePhase.BLOCK_FINALIZE);
    svc.getTxNumIndex().commitBlock(0, blockHash(0), 0);
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
