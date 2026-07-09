package org.tron.core.services.jsonrpc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Collections;
import org.junit.Test;
import org.tron.common.utils.ReflectUtils;
import org.tron.core.Wallet;
import org.tron.core.archive.NoopArchiveService;
import org.tron.core.exception.jsonrpc.JsonRpcInternalException;
import org.tron.core.exception.jsonrpc.JsonRpcInvalidParamsException;

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
  public void ethCallRejectsRequireCanonicalObjectParamBeforeWalletLookup() throws Exception {
    Wallet wallet = mock(Wallet.class);
    TronJsonRpcImpl rpc = new TronJsonRpcImpl(null, wallet);
    ReflectUtils.setFieldValue(rpc, "historicalEthCallSupport",
        new HistoricalEthCallSupport(wallet, NoopArchiveService.INSTANCE));
    try {
      java.util.Map<String, Object> blockParam = new java.util.HashMap<>();
      blockParam.put("blockHash", ZERO_HASH);
      blockParam.put("requireCanonical", Boolean.FALSE);

      JsonRpcInvalidParamsException ex = assertThrows(JsonRpcInvalidParamsException.class,
          () -> rpc.getCall(null, blockParam));

      assertTrue(ex.getMessage().contains("invalid json request"));
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
