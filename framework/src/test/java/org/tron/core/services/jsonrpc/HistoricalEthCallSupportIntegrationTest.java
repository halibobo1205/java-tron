package org.tron.core.services.jsonrpc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.tron.common.BaseMethodTest;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.runtime.TvmTestUtils;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.Sha256Hash;
import org.tron.common.utils.WalletUtil;
import org.tron.core.Wallet;
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveSource;
import org.tron.core.archive.DefaultArchiveService;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.query.HistoricalQueryLimitException;
import org.tron.core.archive.reader.ArchiveStorageKeyCodec;
import org.tron.core.archive.temporal.InMemoryArchiveTemporalStore;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.archive.txnum.ArchiveTxPosition;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.config.args.StorageConfig;
import org.tron.core.exception.jsonrpc.JsonRpcInternalException;
import org.tron.core.exception.jsonrpc.JsonRpcInvalidParamsException;
import org.tron.core.services.jsonrpc.types.CallTraceFrame;
import org.tron.core.services.jsonrpc.types.TraceResult;
import org.tron.protos.Protocol.Account;
import org.tron.protos.Protocol.Transaction;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;
import org.tron.protos.Protocol.Transaction.Result.contractResult;
import org.tron.protos.contract.SmartContractOuterClass.SmartContract;
import org.tron.protos.contract.SmartContractOuterClass.TriggerSmartContract;

/**
 * End-to-end integration test for {@link HistoricalEthCallSupport#call}: a real
 * {@link DefaultArchiveService} (in-memory txNum index + temporal store) is populated with a
 * contract whose runtime returns storage slot 0. The whole orchestration -- resolver -> reader ->
 * archived-state read -> constant-call executor -> JSON-RPC hex render -- runs against that
 * archive, and the rendered word is the value archived at the target block (0x2a), proving it read
 * ARCHIVED state (the latest stores have no such contract).
 */
public class HistoricalEthCallSupportIntegrationTest extends BaseMethodTest {

  // PUSH1 0; SLOAD; PUSH1 0; MSTORE; PUSH1 0x20; PUSH1 0; RETURN -> returns 32-byte storage slot 0.
  private static final byte[] SLOAD_CODE = {
      0x60, 0x00, 0x54, 0x60, 0x00, 0x52, 0x60, 0x20, 0x60, 0x00, (byte) 0xf3
  };
  private static final byte[] ADD_CODE = {
      0x60, 0x01, 0x60, 0x02, 0x01, 0x60, 0x00, 0x52,
      0x60, 0x20, 0x60, 0x00, (byte) 0xf3
  };
  private static final String[] REQUIRED_VM_DEFAULT_KEYS = {
      "ALLOW_CREATION_OF_CONTRACTS",
      "ALLOW_TVM_TRANSFER_TRC10",
      "ALLOW_TVM_CONSTANTINOPLE",
      "ALLOW_TVM_SOLIDITY_059",
      "ALLOW_TVM_ISTANBUL",
      "ALLOW_TVM_FREEZE",
      "ALLOW_TVM_VOTE",
      "ALLOW_TVM_LONDON",
      "ALLOW_TVM_SHANGHAI",
      "ALLOW_TVM_CANCUN",
      "ALLOW_TVM_BLOB",
      "ALLOW_TVM_COMPATIBLE_EVM",
      "ALLOW_OPTIMIZED_RETURN_VALUE_OF_CHAIN_ID",
      "MAINTENANCE_TIME_INTERVAL",
      "UNFREEZE_DELAY_DAYS",
      "ALLOW_NEW_RESOURCE_MODEL",
      "ALLOW_SHIELDED_TRC20_TRANSACTION",
      "ALLOW_MULTI_SIGN",
      "ALLOW_HIGHER_LIMIT_FOR_MAX_CPU_TIME_OF_ONE_TX",
      "ALLOW_DYNAMIC_ENERGY",
      "DYNAMIC_ENERGY_THRESHOLD",
      "DYNAMIC_ENERGY_INCREASE_FACTOR",
      "DYNAMIC_ENERGY_MAX_FACTOR",
      "ALLOW_ENERGY_ADJUSTMENT",
      "ALLOW_STRICT_MATH",
      "CONSENSUS_LOGIC_OPTIMIZATION",
      "ALLOW_HARDEN_RESOURCE_CALCULATION"
  };
  private final List<DefaultArchiveService> archiveServices = new ArrayList<>();

  @Override
  protected void afterInit() {
  }

  @Override
  protected void beforeDestroy() {
    for (DefaultArchiveService archiveService : archiveServices) {
      archiveService.close();
    }
  }

  private DefaultArchiveService newArchiveService(InMemoryArchiveTemporalStore temporalStore) {
    DefaultArchiveService archiveService = new DefaultArchiveService(true, temporalStore);
    archiveServices.add(archiveService);
    return archiveService;
  }

  @Before
  public void generousConstantCallTimeout() {
    // Headroom for the constant-call CPU deadline so a tiny timeout left by another test in the
    // shared CommonParameter singleton cannot fail this historical replay.
    CommonParameter.getInstance().setConstantCallTimeoutMs(60_000);
  }

  private static byte[] addr(int last) {
    byte[] a = new byte[21];
    a[0] = 0x41;
    a[20] = (byte) last;
    return a;
  }

  private void put(InMemoryArchiveTemporalStore temporal, long txNum, ArchiveDomain domain,
      byte[] canonicalKey, byte[] valueBytes) {
    // Archive create at txNum (prev = tombstone): getAsOf(txNum) falls through to latest.
    temporal.putChange(new ArchiveChangeRecord(
        new ArchiveTxPosition(txNum, 1, ArchivePhase.BLOCK_FINALIZE,
            ArchiveSource.NORMAL, -1, null),
        domain, canonicalKey, DomainValue.tombstone(), DomainValue.present(valueBytes)));
  }

  private void putArchiveVmDefaults(InMemoryArchiveTemporalStore temporal, long txNum,
      boolean supportVm) {
    for (String key : REQUIRED_VM_DEFAULT_KEYS) {
      put(temporal, txNum, ArchiveDomain.DYNAMIC_PROPERTIES,
          key.getBytes(StandardCharsets.US_ASCII), ByteArray.fromLong(0L));
    }
    put(temporal, txNum, ArchiveDomain.DYNAMIC_PROPERTIES,
        "MAINTENANCE_TIME_INTERVAL".getBytes(StandardCharsets.US_ASCII),
        ByteArray.fromLong(21_600_000L));
    put(temporal, txNum, ArchiveDomain.DYNAMIC_PROPERTIES,
        "ALLOW_CREATION_OF_CONTRACTS".getBytes(StandardCharsets.US_ASCII),
        ByteArray.fromLong(supportVm ? 1L : 0L));
    long genesisTimestamp =
        Long.parseLong(CommonParameter.getInstance().getGenesisBlock().getTimestamp());
    put(temporal, txNum, ArchiveDomain.DYNAMIC_PROPERTIES,
        "latest_block_header_timestamp".getBytes(StandardCharsets.US_ASCII),
        ByteArray.fromLong(genesisTimestamp + 3_000L));
  }

  @Test
  public void historicalEthCallReturnsArchivedStorageSlot() throws Exception {
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    DefaultArchiveService svc = newArchiveService(temporal);

    // Block 0 proves genesis-complete coverage; block 1 holds the archived contract snapshot.
    svc.getTxNumIndex().beginBlock(0, ArchiveSource.NORMAL);
    svc.getTxNumIndex().allocateSystemTx(0, ArchivePhase.BLOCK_PREPARE);
    svc.getTxNumIndex().allocateSystemTx(0, ArchivePhase.BLOCK_FINALIZE);
    svc.getTxNumIndex().commitBlock(0, blockHash(0), 0);

    // Index block 1 so the resolver maps "0x1" -> its finalize txNum.
    svc.getTxNumIndex().beginBlock(1, ArchiveSource.NORMAL);
    svc.getTxNumIndex().allocateSystemTx(1, ArchivePhase.BLOCK_PREPARE);
    svc.getTxNumIndex().allocateSystemTx(1, ArchivePhase.BLOCK_FINALIZE);
    ArchiveBlockRange range = svc.getTxNumIndex().commitBlock(1, blockHash(1), 0);
    long t = range.getFinalizeTxNum();

    byte[] addr = addr(0x11);
    byte[] caller = addr(0x22);

    // Archive the contract state at the finalize txNum so getAsOf(t) (inclusive-after) finds it.
    put(temporal, t, ArchiveDomain.ACCOUNT, addr,
        Account.newBuilder().setAddress(ByteString.copyFrom(addr)).build().toByteArray());
    put(temporal, t, ArchiveDomain.CONTRACT, addr,
        SmartContract.newBuilder().setContractAddress(ByteString.copyFrom(addr)).build()
            .toByteArray());
    put(temporal, t, ArchiveDomain.CODE, addr, SLOAD_CODE);
    byte[] slot = new byte[32];
    byte[] storedWord = new byte[32];
    storedWord[31] = 0x2a;
    put(temporal, t, ArchiveDomain.CONTRACT_STORAGE,
        ArchiveStorageKeyCodec.contractStorageKey(addr, slot, 0), storedWord);
    putArchiveVmDefaults(temporal, t, true);

    Wallet wallet = mock(Wallet.class);
    BlockCapsule block = blockCapsule(1);
    when(wallet.getBlockByNumWithoutCache(1L)).thenReturn(block.getInstance());
    when(wallet.getBlockIdByNumWithoutCache(1L)).thenReturn(block.getBlockId().getBytes());

    HistoricalEthCallSupport support = new HistoricalEthCallSupport(wallet, svc);

    String hex = support.call(caller, addr, 0L, new byte[0], "0x1");

    // 0x + 64 hex chars: 62 leading zeros then "2a" -- the archived slot-0 word, not the latest
    // store (which has no such contract). This proves resolver -> reader -> archived read ->
    // executor -> hex render all served the ARCHIVED value.
    assertEquals(
        "0x000000000000000000000000000000000000000000000000000000000000002a", hex);
    verify(wallet).getBlockByNumWithoutCache(1L);
    verify(wallet, times(2)).getBlockIdByNumWithoutCache(1L);
    verify(wallet, never()).createTransactionCapsule(any(), eq(ContractType.TriggerSmartContract));
  }

  @Test
  public void historicalEthCallRejectsBlockHashChangedAfterResolution() throws Exception {
    DefaultArchiveService svc = newArchiveService(new InMemoryArchiveTemporalStore());
    svc.getTxNumIndex().beginBlock(0, ArchiveSource.NORMAL);
    svc.getTxNumIndex().allocateSystemTx(0, ArchivePhase.BLOCK_PREPARE);
    svc.getTxNumIndex().allocateSystemTx(0, ArchivePhase.BLOCK_FINALIZE);
    svc.getTxNumIndex().commitBlock(0, blockHash(0), 0);

    Wallet wallet = mock(Wallet.class);
    when(wallet.getBlockByNumWithoutCache(0L)).thenReturn(blockCapsule(0).getInstance());
    when(wallet.getBlockIdByNumWithoutCache(0L)).thenReturn(
        blockCapsuleWithParentSeed(0, (byte) 9).getBlockId().getBytes());

    HistoricalEthCallSupport support = new HistoricalEthCallSupport(wallet, svc);

    JsonRpcInternalException ex = assertThrows(JsonRpcInternalException.class,
        () -> support.call(addr(0x22), addr(0x11), 0L, new byte[0], "0x0"));

    assertTrue(ex.getMessage().contains("hash mismatch"));
  }

  @Test
  public void historicalDebugTraceReturnsStructLogsAndCallFrame() throws Exception {
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    DefaultArchiveService svc = newArchiveService(temporal);
    svc.getTxNumIndex().beginBlock(0, ArchiveSource.NORMAL);
    svc.getTxNumIndex().allocateSystemTx(0, ArchivePhase.BLOCK_PREPARE);
    svc.getTxNumIndex().allocateSystemTx(0, ArchivePhase.BLOCK_FINALIZE);
    svc.getTxNumIndex().commitBlock(0, blockHash(0), 0);
    svc.getTxNumIndex().beginBlock(1, ArchiveSource.NORMAL);
    svc.getTxNumIndex().allocateSystemTx(1, ArchivePhase.BLOCK_PREPARE);
    svc.getTxNumIndex().allocateSystemTx(1, ArchivePhase.BLOCK_FINALIZE);
    ArchiveBlockRange range = svc.getTxNumIndex().commitBlock(1, blockHash(1), 0);
    long txNum = range.getFinalizeTxNum();

    byte[] contractAddress = addr(0x31);
    byte[] caller = addr(0x32);
    byte[] revertAddress = addr(0x33);
    put(temporal, txNum, ArchiveDomain.ACCOUNT, contractAddress,
        Account.newBuilder().setAddress(ByteString.copyFrom(contractAddress)).build()
            .toByteArray());
    put(temporal, txNum, ArchiveDomain.CONTRACT, contractAddress,
        SmartContract.newBuilder()
            .setContractAddress(ByteString.copyFrom(contractAddress))
            .build()
            .toByteArray());
    put(temporal, txNum, ArchiveDomain.CODE, contractAddress, SLOAD_CODE);
    putContract(temporal, txNum, revertAddress,
        new byte[] {0x60, 0x00, 0x60, 0x00, (byte) 0xfd});
    byte[] storedWord = new byte[32];
    storedWord[31] = 0x2a;
    put(temporal, txNum, ArchiveDomain.CONTRACT_STORAGE,
        ArchiveStorageKeyCodec.contractStorageKey(contractAddress, new byte[32], 0),
        storedWord);
    putArchiveVmDefaults(temporal, txNum, true);

    Wallet wallet = mock(Wallet.class);
    BlockCapsule block = blockCapsule(1);
    when(wallet.getBlockByNumWithoutCache(1L)).thenReturn(block.getInstance());
    when(wallet.getBlockIdByNumWithoutCache(1L)).thenReturn(block.getBlockId().getBytes());
    StorageConfig.ArchiveConfig.DebugConfig debug =
        new StorageConfig.ArchiveConfig.DebugConfig();
    debug.setEnable(true);
    HistoricalDebugTraceSupport support =
        new HistoricalDebugTraceSupport(wallet, svc, debug);

    TraceResult structTrace = (TraceResult) support.traceCall(
        caller, contractAddress, 0L, new byte[0], "0x1", null,
        DebugTraceOptions.parse(Collections.emptyMap()));
    assertEquals(
        "0x000000000000000000000000000000000000000000000000000000000000002a",
        structTrace.getReturnValue());
    assertEquals(7, structTrace.getStructLogs().size());
    assertEquals("PUSH1", structTrace.getStructLogs().get(0).getOp());
    assertEquals("RETURN", structTrace.getStructLogs().get(6).getOp());
    assertTrue(structTrace.getGas() > 0L);

    Map<String, Object> callTracerOptions = new HashMap<>();
    callTracerOptions.put("tracer", "callTracer");
    CallTraceFrame callTrace = (CallTraceFrame) support.traceCall(
        caller, contractAddress, 0L, new byte[0], "0x1", null,
        DebugTraceOptions.parse(callTracerOptions));
    assertEquals("CALL", callTrace.getType());
    assertEquals(
        "0x000000000000000000000000000000000000000000000000000000000000002a",
        callTrace.getOutput());
    assertEquals(
        structTrace.getGas(),
        new BigInteger(callTrace.getGasUsed().substring(2), 16).longValueExact());
    assertNull(callTrace.getError());

    TraceResult zeroGasTrace = (TraceResult) support.traceCall(
        caller, contractAddress, 0L, new byte[0], 0L, "0x1", null,
        DebugTraceOptions.parse(Collections.emptyMap()));
    assertTrue(zeroGasTrace.isFailed());
    assertEquals(0L, zeroGasTrace.getGas());

    TraceResult revertTrace = (TraceResult) support.traceCall(
        caller, revertAddress, 0L, new byte[0], "0x1", null,
        DebugTraceOptions.parse(Collections.emptyMap()));
    assertTrue(revertTrace.isFailed());
    assertEquals("0x", revertTrace.getReturnValue());
    assertNull(
        revertTrace.getStructLogs().get(revertTrace.getStructLogs().size() - 1).getError());
    CallTraceFrame revertCallTrace = (CallTraceFrame) support.traceCall(
        caller, revertAddress, 0L, new byte[0], "0x1", null,
        DebugTraceOptions.parse(callTracerOptions));
    assertEquals("execution reverted", revertCallTrace.getError());

    debug.setMaxTraceSteps(1L);
    HistoricalDebugTraceSupport stepLimited =
        new HistoricalDebugTraceSupport(wallet, svc, debug);
    HistoricalQueryLimitException stepFailure = assertThrows(
        HistoricalQueryLimitException.class,
        () -> stepLimited.traceCall(
            caller, contractAddress, 0L, new byte[0], "0x1", null,
            DebugTraceOptions.parse(callTracerOptions)));
    assertEquals(HistoricalQueryLimitException.Limit.VM_STEPS, stepFailure.getLimit());

    debug.setMaxTraceSteps(250_000L);
    debug.setMaxTraceBytes(128L);
    HistoricalDebugTraceSupport byteLimited =
        new HistoricalDebugTraceSupport(wallet, svc, debug);
    HistoricalQueryLimitException byteFailure = assertThrows(
        HistoricalQueryLimitException.class,
        () -> byteLimited.traceCall(
            caller, contractAddress, 0L, new byte[0], "0x1", null,
            DebugTraceOptions.parse(callTracerOptions)));
    assertEquals(HistoricalQueryLimitException.Limit.RESPONSE_BYTES, byteFailure.getLimit());
  }

  @Test
  public void historicalDebugTraceTransactionUsesArchivedPreState() throws Exception {
    byte[] contractAddress = addr(0x41);
    byte[] caller = addr(0x42);
    TriggerSmartContract trigger =
        TvmTestUtils.buildTriggerSmartContract(caller, contractAddress, new byte[0], 0L);
    TransactionCapsule trxCap =
        new TransactionCapsule(trigger, ContractType.TriggerSmartContract);
    trxCap.setFeeLimit(1_000_000_000L);
    Transaction transaction = withContractResult(trxCap.getInstance());
    byte[] txId = trxCap.getTransactionId().getBytes();
    TransactionCapsule lowFeeTrxCap =
        new TransactionCapsule(trigger, ContractType.TriggerSmartContract);
    lowFeeTrxCap.setFeeLimit(100L);
    Transaction lowFeeTransaction =
        withContractResult(lowFeeTrxCap.getInstance(), contractResult.OUT_OF_ENERGY);
    byte[] lowFeeTxId = lowFeeTrxCap.getTransactionId().getBytes();
    TransactionCapsule outOfTimeTrxCap =
        new TransactionCapsule(trigger, ContractType.TriggerSmartContract);
    outOfTimeTrxCap.setFeeLimit(200L);
    Transaction outOfTimeTransaction =
        withContractResult(outOfTimeTrxCap.getInstance(), contractResult.OUT_OF_TIME);
    byte[] outOfTimeTxId = outOfTimeTrxCap.getTransactionId().getBytes();
    TransactionCapsule defaultResultTrxCap =
        new TransactionCapsule(trigger, ContractType.TriggerSmartContract);
    defaultResultTrxCap.setFeeLimit(300L);
    Transaction defaultResultTransaction =
        withContractResult(defaultResultTrxCap.getInstance(), contractResult.DEFAULT);
    byte[] defaultResultTxId = defaultResultTrxCap.getTransactionId().getBytes();
    TransactionCapsule unknownResultTrxCap =
        new TransactionCapsule(trigger, ContractType.TriggerSmartContract);
    unknownResultTrxCap.setFeeLimit(400L);
    Transaction unknownResultTransaction = unknownResultTrxCap.getInstance().toBuilder()
        .addRet(Transaction.Result.newBuilder().setContractRetValue(999))
        .build();
    byte[] unknownResultTxId = unknownResultTrxCap.getTransactionId().getBytes();

    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    DefaultArchiveService svc = newArchiveService(temporal);
    svc.getTxNumIndex().beginBlock(0, ArchiveSource.NORMAL);
    svc.getTxNumIndex().allocateSystemTx(0, ArchivePhase.BLOCK_PREPARE);
    svc.getTxNumIndex().allocateSystemTx(0, ArchivePhase.BLOCK_FINALIZE);
    svc.getTxNumIndex().commitBlock(0, blockHash(0), 0);
    svc.getTxNumIndex().beginBlock(1, ArchiveSource.NORMAL);
    svc.getTxNumIndex().allocateSystemTx(1, ArchivePhase.BLOCK_PREPARE);
    svc.getTxNumIndex().allocateSystemTx(1, ArchivePhase.BLOCK_FINALIZE);
    ArchiveBlockRange contractRange =
        svc.getTxNumIndex().commitBlock(1, blockHash(1), 0);
    long contractTxNum = contractRange.getFinalizeTxNum();
    put(temporal, contractTxNum, ArchiveDomain.ACCOUNT, contractAddress,
        Account.newBuilder().setAddress(ByteString.copyFrom(contractAddress)).build()
            .toByteArray());
    put(temporal, contractTxNum, ArchiveDomain.ACCOUNT, caller,
        Account.newBuilder()
            .setAddress(ByteString.copyFrom(caller))
            .setBalance(10_000_000_000L)
            .build()
            .toByteArray());
    put(temporal, contractTxNum, ArchiveDomain.CONTRACT, contractAddress,
        SmartContract.newBuilder()
            .setContractAddress(ByteString.copyFrom(contractAddress))
            .setOriginAddress(ByteString.copyFrom(caller))
            .setConsumeUserResourcePercent(100)
            .build()
            .toByteArray());
    put(temporal, contractTxNum, ArchiveDomain.CODE, contractAddress, SLOAD_CODE);
    byte[] storedWord = new byte[32];
    storedWord[31] = 0x2a;
    put(temporal, contractTxNum, ArchiveDomain.CONTRACT_STORAGE,
        ArchiveStorageKeyCodec.contractStorageKey(contractAddress, new byte[32], 0),
        storedWord);
    putArchiveVmDefaults(temporal, contractTxNum, true);
    svc.getTxNumIndex().beginBlock(2, ArchiveSource.NORMAL);
    svc.getTxNumIndex().allocateSystemTx(2, ArchivePhase.BLOCK_PREPARE);
    svc.getTxNumIndex().allocateUserTx(2, 0, txId);
    svc.getTxNumIndex().allocateUserVmTx(2, 0, txId);
    svc.getTxNumIndex().allocateUserTx(2, 1, lowFeeTxId);
    svc.getTxNumIndex().allocateUserVmTx(2, 1, lowFeeTxId);
    svc.getTxNumIndex().allocateUserTx(2, 2, outOfTimeTxId);
    svc.getTxNumIndex().allocateUserVmTx(2, 2, outOfTimeTxId);
    svc.getTxNumIndex().allocateUserTx(2, 3, defaultResultTxId);
    svc.getTxNumIndex().allocateUserVmTx(2, 3, defaultResultTxId);
    svc.getTxNumIndex().allocateUserTx(2, 4, unknownResultTxId);
    svc.getTxNumIndex().allocateUserVmTx(2, 4, unknownResultTxId);
    svc.getTxNumIndex().allocateSystemTx(2, ArchivePhase.BLOCK_FINALIZE);
    svc.getTxNumIndex().commitBlock(2, blockHash(2), 5);

    Wallet wallet = mock(Wallet.class);
    BlockCapsule block = blockCapsule(2);
    when(wallet.getBlockByNumWithoutCache(2L)).thenReturn(block.getInstance());
    when(wallet.getBlockIdByNumWithoutCache(2L)).thenReturn(block.getBlockId().getBytes());
    when(wallet.getTransactionById(ByteString.copyFrom(txId))).thenReturn(transaction);
    when(wallet.getTransactionById(ByteString.copyFrom(lowFeeTxId)))
        .thenReturn(lowFeeTransaction);
    when(wallet.getTransactionById(ByteString.copyFrom(outOfTimeTxId)))
        .thenReturn(outOfTimeTransaction);
    when(wallet.getTransactionById(ByteString.copyFrom(defaultResultTxId)))
        .thenReturn(defaultResultTransaction);
    when(wallet.getTransactionById(ByteString.copyFrom(unknownResultTxId)))
        .thenReturn(unknownResultTransaction);
    StorageConfig.ArchiveConfig.DebugConfig debug =
        new StorageConfig.ArchiveConfig.DebugConfig();
    debug.setEnable(true);
    HistoricalDebugTraceSupport support =
        new HistoricalDebugTraceSupport(wallet, svc, debug);

    TraceResult result = (TraceResult) support.traceTransaction(
        txId, DebugTraceOptions.parse(Collections.emptyMap()));

    assertEquals(
        "0x000000000000000000000000000000000000000000000000000000000000002a",
        result.getReturnValue());
    assertEquals(7, result.getStructLogs().size());

    TraceResult lowFeeResult = (TraceResult) support.traceTransaction(
        lowFeeTxId, DebugTraceOptions.parse(Collections.emptyMap()));
    assertTrue(lowFeeResult.isFailed());
    assertEquals(1L, lowFeeResult.getGas());
    assertEquals("0x", lowFeeResult.getReturnValue());
    assertEquals(1, lowFeeResult.getStructLogs().size());
    when(wallet.getTransactionById(ByteString.copyFrom(lowFeeTxId)))
        .thenReturn(withContractResult(lowFeeTrxCap.getInstance(), contractResult.SUCCESS));
    JsonRpcInternalException mismatch = assertThrows(JsonRpcInternalException.class,
        () -> support.traceTransaction(
            lowFeeTxId, DebugTraceOptions.parse(Collections.emptyMap())));
    assertTrue(mismatch.getMessage().contains("replay result mismatch"));
    assertThrows(JsonRpcInvalidParamsException.class,
        () -> support.traceTransaction(
            outOfTimeTxId, DebugTraceOptions.parse(Collections.emptyMap())));
    JsonRpcInternalException missingResult = assertThrows(JsonRpcInternalException.class,
        () -> support.traceTransaction(
            defaultResultTxId, DebugTraceOptions.parse(Collections.emptyMap())));
    assertTrue(missingResult.getMessage().contains("execution result is missing"));
    JsonRpcInternalException unknownResult = assertThrows(JsonRpcInternalException.class,
        () -> support.traceTransaction(
            unknownResultTxId, DebugTraceOptions.parse(Collections.emptyMap())));
    assertTrue(unknownResult.getMessage().contains("unrecognized"));
  }

  @Test
  public void historicalDebugTraceTransactionSupportsContractCreation() throws Exception {
    byte[] creator = addr(0x45);
    String initCode = "600060005360016000f3";
    Transaction transaction = withContractResult(
        TvmTestUtils.generateDeploySmartContractAndGetTransaction(
            "TraceCreate", creator, "[]", initCode, 0L, 1_000_000_000L, 100L, null));
    TransactionCapsule trxCap = new TransactionCapsule(transaction);
    byte[] txId = trxCap.getTransactionId().getBytes();

    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    DefaultArchiveService svc = newArchiveService(temporal);
    svc.getTxNumIndex().beginBlock(0, ArchiveSource.NORMAL);
    svc.getTxNumIndex().allocateSystemTx(0, ArchivePhase.BLOCK_PREPARE);
    svc.getTxNumIndex().allocateSystemTx(0, ArchivePhase.BLOCK_FINALIZE);
    svc.getTxNumIndex().commitBlock(0, blockHash(0), 0);
    svc.getTxNumIndex().beginBlock(1, ArchiveSource.NORMAL);
    svc.getTxNumIndex().allocateSystemTx(1, ArchivePhase.BLOCK_PREPARE);
    svc.getTxNumIndex().allocateSystemTx(1, ArchivePhase.BLOCK_FINALIZE);
    ArchiveBlockRange stateRange =
        svc.getTxNumIndex().commitBlock(1, blockHash(1), 0);
    long stateTxNum = stateRange.getFinalizeTxNum();
    put(temporal, stateTxNum, ArchiveDomain.ACCOUNT, creator,
        Account.newBuilder()
            .setAddress(ByteString.copyFrom(creator))
            .setBalance(10_000_000_000L)
            .build()
            .toByteArray());
    putArchiveVmDefaults(temporal, stateTxNum, true);
    svc.getTxNumIndex().beginBlock(2, ArchiveSource.NORMAL);
    svc.getTxNumIndex().allocateSystemTx(2, ArchivePhase.BLOCK_PREPARE);
    svc.getTxNumIndex().allocateUserTx(2, 0, txId);
    svc.getTxNumIndex().allocateUserVmTx(2, 0, txId);
    svc.getTxNumIndex().allocateSystemTx(2, ArchivePhase.BLOCK_FINALIZE);
    svc.getTxNumIndex().commitBlock(2, blockHash(2), 1);

    Wallet wallet = mock(Wallet.class);
    BlockCapsule stateBlock = blockCapsule(1);
    BlockCapsule block = blockCapsule(2);
    when(wallet.getBlockByNumWithoutCache(1L)).thenReturn(stateBlock.getInstance());
    when(wallet.getBlockIdByNumWithoutCache(1L))
        .thenReturn(stateBlock.getBlockId().getBytes());
    when(wallet.getBlockByNumWithoutCache(2L)).thenReturn(block.getInstance());
    when(wallet.getBlockIdByNumWithoutCache(2L)).thenReturn(block.getBlockId().getBytes());
    when(wallet.getTransactionById(ByteString.copyFrom(txId))).thenReturn(transaction);
    StorageConfig.ArchiveConfig.DebugConfig debug =
        new StorageConfig.ArchiveConfig.DebugConfig();
    debug.setEnable(true);
    HistoricalDebugTraceSupport support =
        new HistoricalDebugTraceSupport(wallet, svc, debug);

    TraceResult structTrace = (TraceResult) support.traceTransaction(
        txId, DebugTraceOptions.parse(Collections.emptyMap()));
    assertEquals("0x00", structTrace.getReturnValue());
    assertTrue(structTrace.getStructLogs().stream()
        .anyMatch(log -> "MSTORE8".equals(log.getOp())));

    Map<String, Object> callTracerOptions = new HashMap<>();
    callTracerOptions.put("tracer", "callTracer");
    CallTraceFrame callTrace = (CallTraceFrame) support.traceTransaction(
        txId, DebugTraceOptions.parse(callTracerOptions));
    assertEquals("CREATE", callTrace.getType());
    assertEquals(
        ByteArray.toJsonHexAddress(WalletUtil.generateContractAddress(transaction)),
        callTrace.getTo());
    assertEquals("0x00", callTrace.getOutput());
    assertNull(callTrace.getError());

    CallTraceFrame syntheticCreate = (CallTraceFrame) support.traceCall(
        creator, null, 0L, ByteArray.fromHexString(initCode), 1_000_000L,
        "0x1", null, DebugTraceOptions.parse(callTracerOptions));
    assertEquals("CREATE", syntheticCreate.getType());
    assertNotNull(syntheticCreate.getTo());
    assertEquals("0x00", syntheticCreate.getOutput());
    assertNull(syntheticCreate.getError());
  }

  @Test
  public void historicalCallTracerCapturesNestedTvmCall() throws Exception {
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    DefaultArchiveService svc = newArchiveService(temporal);
    svc.getTxNumIndex().beginBlock(0, ArchiveSource.NORMAL);
    svc.getTxNumIndex().allocateSystemTx(0, ArchivePhase.BLOCK_PREPARE);
    svc.getTxNumIndex().allocateSystemTx(0, ArchivePhase.BLOCK_FINALIZE);
    svc.getTxNumIndex().commitBlock(0, blockHash(0), 0);
    svc.getTxNumIndex().beginBlock(1, ArchiveSource.NORMAL);
    svc.getTxNumIndex().allocateSystemTx(1, ArchivePhase.BLOCK_PREPARE);
    svc.getTxNumIndex().allocateSystemTx(1, ArchivePhase.BLOCK_FINALIZE);
    ArchiveBlockRange range = svc.getTxNumIndex().commitBlock(1, blockHash(1), 0);
    long txNum = range.getFinalizeTxNum();
    byte[] rootAddress = addr(0x51);
    byte[] childAddress = addr(0x52);
    byte[] caller = addr(0x53);
    byte[] failingRootAddress = addr(0x54);
    byte[] failingChildAddress = addr(0x55);
    putContract(temporal, txNum, rootAddress, nestedCallCode(childAddress));
    putContract(temporal, txNum, childAddress, ADD_CODE);
    putContract(temporal, txNum, failingRootAddress, nestedCallCode(failingChildAddress));
    putContract(temporal, txNum, failingChildAddress, new byte[] {(byte) 0xfe});
    putArchiveVmDefaults(temporal, txNum, true);

    Wallet wallet = mock(Wallet.class);
    BlockCapsule block = blockCapsule(1);
    when(wallet.getBlockByNumWithoutCache(1L)).thenReturn(block.getInstance());
    when(wallet.getBlockIdByNumWithoutCache(1L)).thenReturn(block.getBlockId().getBytes());
    StorageConfig.ArchiveConfig.DebugConfig debug =
        new StorageConfig.ArchiveConfig.DebugConfig();
    debug.setEnable(true);
    HistoricalDebugTraceSupport support =
        new HistoricalDebugTraceSupport(wallet, svc, debug);
    TraceResult structTrace = (TraceResult) support.traceCall(
        caller, rootAddress, 0L, new byte[0], "0x1", null,
        DebugTraceOptions.parse(Collections.emptyMap()));
    assertTrue(structTrace.getStructLogs().stream().anyMatch(log -> log.getDepth() == 2));

    Map<String, Object> callTracerOptions = new HashMap<>();
    callTracerOptions.put("tracer", "callTracer");

    CallTraceFrame root = (CallTraceFrame) support.traceCall(
        caller, rootAddress, 0L, new byte[0], "0x1", null,
        DebugTraceOptions.parse(callTracerOptions));

    assertEquals(1, root.getCalls().size());
    CallTraceFrame child = root.getCalls().get(0);
    assertEquals("CALL", child.getType());
    assertEquals(ByteArray.toJsonHexAddress(rootAddress), child.getFrom());
    assertEquals(ByteArray.toJsonHexAddress(childAddress), child.getTo());
    assertEquals(
        "0x0000000000000000000000000000000000000000000000000000000000000003",
        child.getOutput());
    long childGasUsed = new BigInteger(child.getGasUsed().substring(2), 16).longValueExact();
    long childStructGas = structTrace.getStructLogs().stream()
        .filter(log -> log.getDepth() == 2)
        .mapToLong(log -> log.getGasCost())
        .sum();
    assertEquals(childStructGas, childGasUsed);

    CallTraceFrame failingRoot = (CallTraceFrame) support.traceCall(
        caller, failingRootAddress, 0L, new byte[0], "0x1", null,
        DebugTraceOptions.parse(callTracerOptions));
    CallTraceFrame failingChild = failingRoot.getCalls().get(0);
    String nestedError = failingChild.getError();
    assertNotNull(nestedError);
    assertTrue(nestedError.contains("Invalid operation code"));
    assertNull(failingChild.getOutput());
  }

  private void putContract(InMemoryArchiveTemporalStore temporal, long txNum, byte[] address,
      byte[] code) {
    put(temporal, txNum, ArchiveDomain.ACCOUNT, address,
        Account.newBuilder().setAddress(ByteString.copyFrom(address)).build().toByteArray());
    put(temporal, txNum, ArchiveDomain.CONTRACT, address,
        SmartContract.newBuilder().setContractAddress(ByteString.copyFrom(address)).build()
            .toByteArray());
    put(temporal, txNum, ArchiveDomain.CODE, address, code);
  }

  private static byte[] nestedCallCode(byte[] childAddress) {
    ByteArrayOutputStream code = new ByteArrayOutputStream();
    code.write(0x60);
    code.write(0x20);
    code.write(0x60);
    code.write(0x00);
    code.write(0x60);
    code.write(0x00);
    code.write(0x60);
    code.write(0x00);
    code.write(0x60);
    code.write(0x00);
    code.write(0x73);
    code.write(childAddress, 1, 20);
    code.write(0x62);
    code.write(0x0f);
    code.write(0x42);
    code.write(0x40);
    code.write(0xf1);
    code.write(0x50);
    code.write(0x60);
    code.write(0x20);
    code.write(0x60);
    code.write(0x00);
    code.write(0xf3);
    return code.toByteArray();
  }

  private static byte[] blockHash(long blockNum) {
    return blockCapsule(blockNum).getBlockId().getBytes();
  }

  private static Transaction withContractResult(Transaction transaction) {
    return withContractResult(transaction, contractResult.SUCCESS);
  }

  private static Transaction withContractResult(
      Transaction transaction, contractResult result) {
    return transaction.toBuilder()
        .addRet(Transaction.Result.newBuilder().setContractRet(result))
        .build();
  }

  private static BlockCapsule blockCapsule(long blockNum) {
    return new BlockCapsule(blockNum, Sha256Hash.ZERO_HASH, 1000L,
        ByteString.copyFrom(new byte[21]));
  }

  private static BlockCapsule blockCapsuleWithParentSeed(long blockNum, byte seed) {
    byte[] parent = new byte[32];
    parent[31] = seed;
    return new BlockCapsule(blockNum, Sha256Hash.wrap(parent), 1000L,
        ByteString.copyFrom(new byte[21]));
  }
}
