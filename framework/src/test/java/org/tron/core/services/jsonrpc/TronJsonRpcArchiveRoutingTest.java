package org.tron.core.services.jsonrpc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Collections;
import org.junit.Test;
import org.tron.common.utils.ReflectUtils;
import org.tron.core.Wallet;
import org.tron.core.archive.NoopArchiveService;
import org.tron.core.exception.jsonrpc.JsonRpcInternalException;
import org.tron.core.exception.jsonrpc.JsonRpcInvalidParamsException;
import org.tron.core.services.jsonrpc.types.CallArguments;

public class TronJsonRpcArchiveRoutingTest {

  private static final String ADDRESS = "0xabd4b9367799eaa3197fecb144eb71de1e049abc";
  private static final String QUANTITY_NOT_SUPPORT_ERROR =
      "QUANTITY not supported, just support TAG as latest";
  private static final String ZERO_HASH =
      "0x0000000000000000000000000000000000000000000000000000000000000000";

  @Test
  public void objectBlockNumberStateGetterIsRejectedBeforeWalletLookup() throws Exception {
    Wallet wallet = mock(Wallet.class);
    TronJsonRpcImpl rpc = new TronJsonRpcImpl(null, wallet);
    ReflectUtils.setFieldValue(rpc, "archiveJsonRpcStateAdapter",
        new ArchiveJsonRpcStateAdapter(wallet, NoopArchiveService.INSTANCE));
    try {
      JsonRpcInvalidParamsException ex = assertThrows(JsonRpcInvalidParamsException.class,
          () -> rpc.getTrxBalance(ADDRESS, Collections.singletonMap("blockNumber", "0x10")));

      assertTrue(ex.getMessage().contains("invalid json request"));
      verifyNoInteractions(wallet);
    } finally {
      rpc.close();
    }
  }

  @Test
  public void objectBlockHashStateGetterIsRejectedBeforeWalletLookup() throws Exception {
    Wallet wallet = mock(Wallet.class);
    TronJsonRpcImpl rpc = new TronJsonRpcImpl(null, wallet);
    ReflectUtils.setFieldValue(rpc, "archiveJsonRpcStateAdapter",
        new ArchiveJsonRpcStateAdapter(wallet, NoopArchiveService.INSTANCE));
    try {
      JsonRpcInvalidParamsException ex = assertThrows(JsonRpcInvalidParamsException.class,
          () -> rpc.getTrxBalance(ADDRESS, Collections.singletonMap("blockHash", ZERO_HASH)));

      assertTrue(ex.getMessage().contains("invalid json request"));
      verifyNoInteractions(wallet);
    } finally {
      rpc.close();
    }
  }

  @Test
  public void objectBlockNumberStorageGetterIsRejectedBeforeWalletLookup() throws Exception {
    Wallet wallet = mock(Wallet.class);
    TronJsonRpcImpl rpc = new TronJsonRpcImpl(null, wallet);
    ReflectUtils.setFieldValue(rpc, "archiveJsonRpcStateAdapter",
        new ArchiveJsonRpcStateAdapter(wallet, NoopArchiveService.INSTANCE));
    try {
      JsonRpcInvalidParamsException ex = assertThrows(JsonRpcInvalidParamsException.class,
          () -> rpc.getStorageAt(ADDRESS, "0x0",
              Collections.singletonMap("blockNumber", "0x10")));

      assertTrue(ex.getMessage().contains("invalid json request"));
      verifyNoInteractions(wallet);
    } finally {
      rpc.close();
    }
  }

  @Test
  public void objectBlockNumberCodeGetterIsRejectedBeforeWalletLookup() throws Exception {
    Wallet wallet = mock(Wallet.class);
    TronJsonRpcImpl rpc = new TronJsonRpcImpl(null, wallet);
    ReflectUtils.setFieldValue(rpc, "archiveJsonRpcStateAdapter",
        new ArchiveJsonRpcStateAdapter(wallet, NoopArchiveService.INSTANCE));
    try {
      JsonRpcInvalidParamsException ex = assertThrows(JsonRpcInvalidParamsException.class,
          () -> rpc.getABIOfSmartContract(ADDRESS,
              Collections.singletonMap("blockNumber", "0x10")));

      assertTrue(ex.getMessage().contains("invalid json request"));
      verifyNoInteractions(wallet);
    } finally {
      rpc.close();
    }
  }

  @Test
  public void objectBlockNumberEthCallFailsClosedBeforeWalletLookup() throws Exception {
    Wallet wallet = mock(Wallet.class);
    TronJsonRpcImpl rpc = new TronJsonRpcImpl(null, wallet);
    ReflectUtils.setFieldValue(rpc, "historicalEthCallSupport",
        new HistoricalEthCallSupport(wallet, NoopArchiveService.INSTANCE));
    try {
      JsonRpcInternalException ex = assertThrows(JsonRpcInternalException.class,
          () -> rpc.getCall(null, Collections.singletonMap("blockNumber", "0x10")));

      assertTrue(ex.getMessage().contains("archive is not available"));
      verifyNoInteractions(wallet);
    } finally {
      rpc.close();
    }
  }

  @Test
  public void ethCallAcceptsFalseRequireCanonicalBeforeArchiveAvailabilityCheck()
      throws Exception {
    Wallet wallet = mock(Wallet.class);
    TronJsonRpcImpl rpc = new TronJsonRpcImpl(null, wallet);
    ReflectUtils.setFieldValue(rpc, "historicalEthCallSupport",
        new HistoricalEthCallSupport(wallet, NoopArchiveService.INSTANCE));
    try {
      java.util.Map<String, Object> blockParam = new java.util.HashMap<>();
      blockParam.put("blockHash", ZERO_HASH);
      blockParam.put("requireCanonical", Boolean.FALSE);

      JsonRpcInternalException ex = assertThrows(JsonRpcInternalException.class,
          () -> rpc.getCall(null, blockParam));

      assertTrue(ex.getMessage().contains("archive is not available"));
      verifyNoInteractions(wallet);
    } finally {
      rpc.close();
    }
  }

  @Test
  public void ethCallRejectsNonBooleanRequireCanonicalBeforeWalletLookup() throws Exception {
    Wallet wallet = mock(Wallet.class);
    TronJsonRpcImpl rpc = new TronJsonRpcImpl(null, wallet);
    ReflectUtils.setFieldValue(rpc, "historicalEthCallSupport",
        new HistoricalEthCallSupport(wallet, NoopArchiveService.INSTANCE));
    try {
      java.util.Map<String, Object> blockParam = new java.util.HashMap<>();
      blockParam.put("blockHash", ZERO_HASH);
      blockParam.put("requireCanonical", "true");

      JsonRpcInvalidParamsException ex = assertThrows(JsonRpcInvalidParamsException.class,
          () -> rpc.getCall(null, blockParam));

      assertTrue(ex.getMessage().contains("invalid json request"));
      verifyNoInteractions(wallet);
    } finally {
      rpc.close();
    }
  }

  @Test
  public void ethCallAcceptsTrueRequireCanonicalBeforeArchiveAvailabilityCheck() throws Exception {
    Wallet wallet = mock(Wallet.class);
    TronJsonRpcImpl rpc = new TronJsonRpcImpl(null, wallet);
    ReflectUtils.setFieldValue(rpc, "historicalEthCallSupport",
        new HistoricalEthCallSupport(wallet, NoopArchiveService.INSTANCE));
    try {
      java.util.Map<String, Object> blockParam = new java.util.HashMap<>();
      blockParam.put("blockHash", ZERO_HASH);
      blockParam.put("requireCanonical", Boolean.TRUE);

      JsonRpcInternalException ex = assertThrows(JsonRpcInternalException.class,
          () -> rpc.getCall(null, blockParam));

      assertTrue(ex.getMessage().contains("archive is not available"));
      verifyNoInteractions(wallet);
    } finally {
      rpc.close();
    }
  }

  @Test
  public void debugTraceCallRoutesCanonicalCreateAndExplicitGas() throws Exception {
    Wallet wallet = mock(Wallet.class);
    HistoricalDebugTraceSupport support = mock(HistoricalDebugTraceSupport.class);
    Object expected = new Object();
    when(support.isEnabled()).thenReturn(true);
    when(support.traceCall(
        any(byte[].class), isNull(), eq(0L), any(byte[].class), eq(Long.valueOf(2L)),
        isNull(), any(byte[].class), any(DebugTraceOptions.class))).thenReturn(expected);
    TronJsonRpcImpl rpc = new TronJsonRpcImpl(null, wallet);
    ReflectUtils.setFieldValue(rpc, "historicalDebugTraceSupport", support);
    try {
      CallArguments call = new CallArguments();
      call.setInput("0x00");
      call.setGas("0x2");
      java.util.Map<String, Object> blockParam = new java.util.HashMap<>();
      blockParam.put("blockHash", ZERO_HASH);
      blockParam.put("requireCanonical", Boolean.TRUE);

      Object actual = rpc.debugTraceCall(call, blockParam);

      assertSame(expected, actual);
      verify(support).traceCall(
          any(byte[].class), isNull(), eq(0L), eq(new byte[] {0}), eq(Long.valueOf(2L)),
          isNull(), eq(new byte[32]), any(DebugTraceOptions.class));
      verifyNoInteractions(wallet);
    } finally {
      rpc.close();
    }
  }

  @Test
  public void debugTraceCallRejectsExplicitEmptyToInsteadOfCreatingContract() throws Exception {
    Wallet wallet = mock(Wallet.class);
    HistoricalDebugTraceSupport support = mock(HistoricalDebugTraceSupport.class);
    when(support.isEnabled()).thenReturn(true);
    TronJsonRpcImpl rpc = new TronJsonRpcImpl(null, wallet);
    ReflectUtils.setFieldValue(rpc, "historicalDebugTraceSupport", support);
    try {
      CallArguments call = new CallArguments();
      call.setTo("0x");
      call.setInput("0x00");
      java.util.Map<String, Object> blockParam = new java.util.HashMap<>();
      blockParam.put("blockHash", ZERO_HASH);

      JsonRpcInvalidParamsException failure = assertThrows(
          JsonRpcInvalidParamsException.class, () -> rpc.debugTraceCall(call, blockParam));

      assertTrue(failure.getMessage().contains("invalid address"));
      verify(support).isEnabled();
      verifyNoMoreInteractions(support);
      verifyNoInteractions(wallet);
    } finally {
      rpc.close();
    }
  }

  @Test
  public void historicalEthCallRejectsOversizedLoserFieldBeforeHexValidation() throws Exception {
    Wallet wallet = mock(Wallet.class);
    HistoricalEthCallSupport support = mock(HistoricalEthCallSupport.class);
    when(support.shouldUseArchive("0x10")).thenReturn(true);
    TronJsonRpcImpl rpc = new TronJsonRpcImpl(null, wallet);
    ReflectUtils.setFieldValue(rpc, "historicalEthCallSupport", support);
    CallArguments call = new CallArguments();
    call.setInput("0x");
    char[] oversized = new char[(int) (org.tron.core.Constant.TRANSACTION_MAX_BYTE_SIZE * 2 + 3)];
    java.util.Arrays.fill(oversized, '0');
    oversized[oversized.length - 1] = 'z';
    call.setData(new String(oversized));
    try {
      JsonRpcInvalidParamsException failure = assertThrows(
          JsonRpcInvalidParamsException.class, () -> rpc.getCall(call, "0x10"));

      assertTrue(failure.getMessage().contains("exceeds maximum transaction size"));
      verify(support).shouldUseArchive("0x10");
      verify(support).validateArchiveAvailable();
      verifyNoMoreInteractions(support);
      verifyNoInteractions(wallet);
    } finally {
      rpc.close();
    }
  }

  @Test
  public void historicalEthCallDoesNotGrantPrefixAllowanceToBareData() throws Exception {
    Wallet wallet = mock(Wallet.class);
    HistoricalEthCallSupport support = mock(HistoricalEthCallSupport.class);
    when(support.shouldUseArchive("0x10")).thenReturn(true);
    TronJsonRpcImpl rpc = new TronJsonRpcImpl(null, wallet);
    ReflectUtils.setFieldValue(rpc, "historicalEthCallSupport", support);
    CallArguments call = new CallArguments();
    char[] oversized = new char[
        (int) (org.tron.core.Constant.TRANSACTION_MAX_BYTE_SIZE * 2L + 1L)];
    java.util.Arrays.fill(oversized, '0');
    call.setData(new String(oversized));
    try {
      JsonRpcInvalidParamsException failure = assertThrows(
          JsonRpcInvalidParamsException.class, () -> rpc.getCall(call, "0x10"));

      assertTrue(failure.getMessage().contains("exceeds maximum transaction size"));
      verify(support).shouldUseArchive("0x10");
      verify(support).validateArchiveAvailable();
      verifyNoMoreInteractions(support);
      verifyNoInteractions(wallet);
    } finally {
      rpc.close();
    }
  }

  @Test
  public void stringBlockNumberEthCallFailsClosedBeforeCallObjectValidation() throws Exception {
    Wallet wallet = mock(Wallet.class);
    TronJsonRpcImpl rpc = new TronJsonRpcImpl(null, wallet);
    ReflectUtils.setFieldValue(rpc, "historicalEthCallSupport",
        new HistoricalEthCallSupport(wallet, NoopArchiveService.INSTANCE));
    try {
      JsonRpcInternalException ex = assertThrows(JsonRpcInternalException.class,
          () -> rpc.getCall(null, "0x10"));

      assertTrue(ex.getMessage().contains("archive is not available"));
      verifyNoInteractions(wallet);
    } finally {
      rpc.close();
    }
  }

  @Test
  public void archiveDisabledNumericBalanceGetterKeepsLatestOnlyError() throws Exception {
    Wallet wallet = mock(Wallet.class);
    TronJsonRpcImpl rpc = new TronJsonRpcImpl(null, wallet);
    ReflectUtils.setFieldValue(rpc, "archiveJsonRpcStateAdapter",
        new ArchiveJsonRpcStateAdapter(wallet, NoopArchiveService.INSTANCE));
    try {
      JsonRpcInvalidParamsException ex = assertThrows(JsonRpcInvalidParamsException.class,
          () -> rpc.getTrxBalance(ADDRESS, "0x10"));

      assertEquals(QUANTITY_NOT_SUPPORT_ERROR, ex.getMessage());
      verifyNoInteractions(wallet);
    } finally {
      rpc.close();
    }
  }

  @Test
  public void archiveDisabledNumericStorageGetterKeepsLatestOnlyError() throws Exception {
    Wallet wallet = mock(Wallet.class);
    TronJsonRpcImpl rpc = new TronJsonRpcImpl(null, wallet);
    ReflectUtils.setFieldValue(rpc, "archiveJsonRpcStateAdapter",
        new ArchiveJsonRpcStateAdapter(wallet, NoopArchiveService.INSTANCE));
    try {
      JsonRpcInvalidParamsException ex = assertThrows(JsonRpcInvalidParamsException.class,
          () -> rpc.getStorageAt(ADDRESS, "0x0", "0x10"));

      assertEquals(QUANTITY_NOT_SUPPORT_ERROR, ex.getMessage());
      verifyNoInteractions(wallet);
    } finally {
      rpc.close();
    }
  }

  @Test
  public void archiveDisabledNumericCodeGetterKeepsLatestOnlyError() throws Exception {
    Wallet wallet = mock(Wallet.class);
    TronJsonRpcImpl rpc = new TronJsonRpcImpl(null, wallet);
    ReflectUtils.setFieldValue(rpc, "archiveJsonRpcStateAdapter",
        new ArchiveJsonRpcStateAdapter(wallet, NoopArchiveService.INSTANCE));
    try {
      JsonRpcInvalidParamsException ex = assertThrows(JsonRpcInvalidParamsException.class,
          () -> rpc.getABIOfSmartContract(ADDRESS, "0x10"));

      assertEquals(QUANTITY_NOT_SUPPORT_ERROR, ex.getMessage());
      verifyNoInteractions(wallet);
    } finally {
      rpc.close();
    }
  }
}
