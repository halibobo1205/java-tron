package org.tron.core.services.jsonrpc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.tron.common.BaseMethodTest;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.runtime.TvmTestUtils;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.Wallet;
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveSource;
import org.tron.core.archive.DefaultArchiveService;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.reader.ArchiveStorageKeyCodec;
import org.tron.core.archive.temporal.InMemoryArchiveTemporalStore;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.archive.txnum.ArchiveTxPosition;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.exception.jsonrpc.JsonRpcInternalException;
import org.tron.core.exception.jsonrpc.JsonRpcInvalidParamsException;
import org.tron.core.services.jsonrpc.types.StructLog;
import org.tron.core.services.jsonrpc.types.TraceResult;
import org.tron.protos.Protocol.Account;
import org.tron.protos.Protocol.ResourceReceipt;
import org.tron.protos.Protocol.Transaction;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;
import org.tron.protos.Protocol.TransactionInfo;
import org.tron.protos.contract.SmartContractOuterClass.CreateSmartContract;
import org.tron.protos.contract.SmartContractOuterClass.SmartContract;
import org.tron.protos.contract.SmartContractOuterClass.TriggerSmartContract;

/**
 * End-to-end integration test for {@link HistoricalTraceSupport#traceTransaction}: a past
 * TriggerSmartContract transaction is replayed against its ARCHIVED pre-transaction state. A real
 * {@link DefaultArchiveService} (in-memory txNum index + temporal store) archives the target
 * contract at an earlier block, then the traced tx is allocated a later user txNum {@code t}; the
 * support resolves the pre-tx point at {@code t - 1} (getAsOf inclusive-after, excluding the tx's
 * own writes), opens the reader, runs the native opcode tracer and renders the Geth structLogs.
 * The reconstructed ops + return value prove the real tx's contract execution was traced against
 * archived state -- no preceding tx was re-run.
 */
public class HistoricalTraceTransactionIntegrationTest extends BaseMethodTest {

  // PUSH1 1; PUSH1 2; ADD; PUSH1 0; MSTORE; PUSH1 0x20; PUSH1 0; RETURN -> returns 3 (1 + 2).
  private static final byte[] ADD_CODE = {
      0x60, 0x01, 0x60, 0x02, 0x01, 0x60, 0x00, 0x52, 0x60, 0x20, 0x60, 0x00, (byte) 0xf3
  };
  private static final byte[] INVALID_CODE = {(byte) 0xfe};
  private static final byte[] STOP_CODE = {0x00};
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

  @Override
  protected void afterInit() {
  }

  @Before
  public void generousConstantCallTimeout() {
    // Trace replay (per-op capture) is slower than a plain call; give the constant-call CPU
    // deadline headroom, and make this independent of a tiny timeout another test may have left.
    CommonParameter.getInstance().setConstantCallTimeoutMs(60_000);
  }

  private static byte[] addr(int last) {
    byte[] a = new byte[21];
    a[0] = 0x41;
    a[20] = (byte) last;
    return a;
  }

  private static String word(long v) {
    byte[] w = new byte[32];
    w[31] = (byte) v;
    return org.bouncycastle.util.encoders.Hex.toHexString(w);
  }

  private static byte[] blockHash(long blockNum) {
    return blockCapsule(blockNum).getBlockId().getBytes();
  }

  private static byte[] mismatchedBlockHash(long blockNum) {
    byte[] hash = java.util.Arrays.copyOf(blockHash(blockNum), ArchiveBlockRange.BLOCK_HASH_LENGTH);
    hash[ArchiveBlockRange.BLOCK_HASH_LENGTH - 1] ^= 0x01;
    return hash;
  }

  private static BlockCapsule blockCapsule(long blockNum) {
    return new BlockCapsule(blockNum, Sha256Hash.ZERO_HASH, 1000L,
        ByteString.copyFrom(new byte[21]));
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
  }

  // Archives the contract (account/contract/code) at finalize txNum t1 of block 1, commits block 1,
  // then opens block 2, allocates prepare + the traced user tx, and commits. Returns the service.
  private DefaultArchiveService buildArchive(InMemoryArchiveTemporalStore temporal, byte[] contract,
      byte[] txId) {
    return buildArchive(temporal, contract, txId, blockHash(2), ADD_CODE);
  }

  private DefaultArchiveService buildArchive(InMemoryArchiveTemporalStore temporal, byte[] contract,
      byte[] txId, byte[] block2Hash) {
    return buildArchive(temporal, contract, txId, block2Hash, ADD_CODE);
  }

  private DefaultArchiveService buildArchive(InMemoryArchiveTemporalStore temporal, byte[] contract,
      byte[] txId, byte[] block2Hash, byte[] code) {
    return buildArchive(temporal, contract, txId, block2Hash, code, 2_000_000_000L);
  }

  private DefaultArchiveService buildArchive(InMemoryArchiveTemporalStore temporal, byte[] contract,
      byte[] txId, byte[] block2Hash, byte[] code, long callerBalance) {
    DefaultArchiveService svc = new DefaultArchiveService(true, temporal);
    byte[] caller = addr(0x22);

    // Block 0 proves genesis-complete coverage for default dynamic properties.
    svc.getTxNumIndex().beginBlock(0, ArchiveSource.NORMAL);
    svc.getTxNumIndex().allocateSystemTx(0, ArchivePhase.BLOCK_PREPARE);
    svc.getTxNumIndex().allocateSystemTx(0, ArchivePhase.BLOCK_FINALIZE);
    svc.getTxNumIndex().commitBlock(0, blockHash(0), 0);

    // Block 1: archive the contract at the finalize txNum (getAsOf inclusive-after finds it).
    svc.getTxNumIndex().beginBlock(1, ArchiveSource.NORMAL);
    svc.getTxNumIndex().allocateSystemTx(1, ArchivePhase.BLOCK_PREPARE);
    ArchiveTxPosition fin1 = svc.getTxNumIndex().allocateSystemTx(1, ArchivePhase.BLOCK_FINALIZE);
    long t1 = fin1.getTxNum();
    put(temporal, t1, ArchiveDomain.ACCOUNT, caller,
        Account.newBuilder().setAddress(ByteString.copyFrom(caller))
            .setBalance(callerBalance).build().toByteArray());
    put(temporal, t1, ArchiveDomain.ACCOUNT, contract,
        Account.newBuilder().setAddress(ByteString.copyFrom(contract)).build().toByteArray());
    put(temporal, t1, ArchiveDomain.CONTRACT, contract,
        SmartContract.newBuilder().setContractAddress(ByteString.copyFrom(contract))
            .setOriginAddress(ByteString.copyFrom(caller)).build()
            .toByteArray());
    put(temporal, t1, ArchiveDomain.CODE, contract, code);
    putArchiveVmDefaults(temporal, t1, true);
    svc.getTxNumIndex().commitBlock(1, blockHash(1), 0);

    // Block 2: prepare, then the traced user tx (txNum t), then finalize.
    svc.getTxNumIndex().beginBlock(2, ArchiveSource.NORMAL);
    svc.getTxNumIndex().allocateSystemTx(2, ArchivePhase.BLOCK_PREPARE);
    svc.getTxNumIndex().allocateUserTx(2, 0, txId);
    svc.getTxNumIndex().allocateSystemTx(2, ArchivePhase.BLOCK_FINALIZE);
    svc.getTxNumIndex().commitBlock(2, block2Hash, 1);
    return svc;
  }

  private DefaultArchiveService genesisCompleteEmptyArchive() {
    DefaultArchiveService svc = new DefaultArchiveService(true, new InMemoryArchiveTemporalStore());
    svc.getTxNumIndex().beginBlock(0, ArchiveSource.NORMAL);
    svc.getTxNumIndex().allocateSystemTx(0, ArchivePhase.BLOCK_PREPARE);
    svc.getTxNumIndex().allocateSystemTx(0, ArchivePhase.BLOCK_FINALIZE);
    svc.getTxNumIndex().commitBlock(0, blockHash(0), 0);
    return svc;
  }

  @Test
  public void traceTransactionReplaysContractAgainstArchivedPreState() throws Exception {
    byte[] contract = addr(0x11);
    byte[] caller = addr(0x22);

    // The real past tx: a TriggerSmartContract call (with a feeLimit) into the archived contract.
    TriggerSmartContract trigger =
        TvmTestUtils.buildTriggerSmartContract(caller, contract, new byte[0], 0L);
    TransactionCapsule trxCap =
        new TransactionCapsule(trigger, ContractType.TriggerSmartContract);
    trxCap.setFeeLimit(1_000_000_000L);
    Transaction tx = trxCap.getInstance();
    byte[] txId = trxCap.getTransactionId().getBytes();

    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    DefaultArchiveService svc = buildArchive(temporal, contract, txId);

    Wallet wallet = mock(Wallet.class);
    when(wallet.getTransactionById(ByteString.copyFrom(txId))).thenReturn(tx);
    when(wallet.getTransactionInfoById(ByteString.copyFrom(txId)))
        .thenReturn(TransactionInfo.newBuilder().setBlockNumber(2L).build());
    BlockCapsule block = blockCapsule(2);
    when(wallet.getBlockByNum(2L)).thenReturn(block.getInstance());

    HistoricalTraceSupport support = new HistoricalTraceSupport(wallet, svc);
    TraceResult result = support.traceTransaction(txId, null);

    // The ADD call succeeds; returnValue is the 32-byte word holding 3 (Geth form, no 0x).
    assertFalse("the ADD call succeeds, so failed is false", result.isFailed());
    assertEquals(word(3), result.getReturnValue());

    List<StructLog> logs = result.getStructLogs();
    assertEquals("PUSH1,PUSH1,ADD,PUSH1,MSTORE,PUSH1,PUSH1,RETURN = 8 ops", 8, logs.size());
    assertEquals("PUSH1", logs.get(0).getOp());
    assertEquals("ADD", logs.get(2).getOp());
    assertEquals("RETURN", logs.get(7).getOp());
    // ADD's pre-op stack is [1, 2] (bottom-first), proving real opcode-level execution was traced.
    assertEquals(java.util.Arrays.asList(word(1), word(2)), logs.get(2).getStack());
    assertEquals(1, logs.get(0).getDepth());
    assertTrue("first op has an empty pre-op stack", logs.get(0).getStack().isEmpty());
  }

  @Test
  public void traceCallBuildsHistoricalTransactionWithoutWalletLatestReference() throws Exception {
    byte[] contract = addr(0x11);
    byte[] caller = addr(0x22);
    byte[] unrelatedTxId = new byte[32];
    unrelatedTxId[31] = 1;

    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    DefaultArchiveService svc = buildArchive(temporal, contract, unrelatedTxId);

    Wallet wallet = mock(Wallet.class);
    BlockCapsule block = blockCapsule(1);
    when(wallet.getBlockByNum(1L)).thenReturn(block.getInstance());

    HistoricalTraceSupport support = new HistoricalTraceSupport(wallet, svc);
    TraceResult result = support.traceCall(caller, contract, 0L, new byte[0], "0x1");

    assertFalse(result.isFailed());
    assertEquals(word(3), result.getReturnValue());
    verify(wallet, never()).createTransactionCapsule(any(), eq(ContractType.TriggerSmartContract));
  }

  @Test
  public void traceTransactionReturnsFailedTraceForVmException() throws Exception {
    byte[] contract = addr(0x11);
    byte[] caller = addr(0x22);

    TriggerSmartContract trigger =
        TvmTestUtils.buildTriggerSmartContract(caller, contract, new byte[0], 0L);
    TransactionCapsule trxCap =
        new TransactionCapsule(trigger, ContractType.TriggerSmartContract);
    trxCap.setFeeLimit(1_000_000_000L);
    Transaction tx = trxCap.getInstance();
    byte[] txId = trxCap.getTransactionId().getBytes();

    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    DefaultArchiveService svc = buildArchive(temporal, contract, txId, blockHash(2), INVALID_CODE);

    Wallet wallet = mock(Wallet.class);
    when(wallet.getTransactionById(ByteString.copyFrom(txId))).thenReturn(tx);
    when(wallet.getTransactionInfoById(ByteString.copyFrom(txId)))
        .thenReturn(TransactionInfo.newBuilder().setBlockNumber(2L)
            .setReceipt(ResourceReceipt.newBuilder().setEnergyUsageTotal(12_345L)).build());
    BlockCapsule block = blockCapsule(2);
    when(wallet.getBlockByNum(2L)).thenReturn(block.getInstance());

    HistoricalTraceSupport support = new HistoricalTraceSupport(wallet, svc);
    TraceResult result = support.traceTransaction(txId, null);

    assertTrue("VM exception transactions still return a failed opcode trace", result.isFailed());
    assertEquals("top-level gas must come from the real transaction receipt",
        12_345L, result.getGas());
    assertEquals("", result.getReturnValue());
    assertEquals(1, result.getStructLogs().size());
    assertEquals("INVALID", result.getStructLogs().get(0).getOp());
  }

  @Test
  public void traceTransactionReplaysBalanceLimitedCall() throws Exception {
    byte[] contract = addr(0x11);
    byte[] caller = addr(0x22);

    TriggerSmartContract trigger =
        TvmTestUtils.buildTriggerSmartContract(caller, contract, new byte[0], 0L);
    TransactionCapsule trxCap =
        new TransactionCapsule(trigger, ContractType.TriggerSmartContract);
    trxCap.setFeeLimit(1_000_000_000L);
    Transaction tx = trxCap.getInstance();
    byte[] txId = trxCap.getTransactionId().getBytes();

    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    DefaultArchiveService svc =
        buildArchive(temporal, contract, txId, blockHash(2), ADD_CODE, 1_000_000L);

    Wallet wallet = mock(Wallet.class);
    when(wallet.getTransactionById(ByteString.copyFrom(txId))).thenReturn(tx);
    when(wallet.getTransactionInfoById(ByteString.copyFrom(txId)))
        .thenReturn(TransactionInfo.newBuilder().setBlockNumber(2L).build());
    BlockCapsule block = blockCapsule(2);
    when(wallet.getBlockByNum(2L)).thenReturn(block.getInstance());

    TraceResult result = new HistoricalTraceSupport(wallet, svc).traceTransaction(txId, null);

    assertFalse(result.isFailed());
    assertEquals(word(3), result.getReturnValue());
  }

  @Test
  public void traceTransactionNotInArchiveThrowsInternal() throws Exception {
    byte[] contract = addr(0x11);
    byte[] caller = addr(0x22);

    TriggerSmartContract trigger =
        TvmTestUtils.buildTriggerSmartContract(caller, contract, new byte[0], 0L);
    TransactionCapsule trxCap =
        new TransactionCapsule(trigger, ContractType.TriggerSmartContract);
    Transaction tx = trxCap.getInstance();
    byte[] txId = trxCap.getTransactionId().getBytes();

    // The archive index has no record of this tx (findTxNumByTxId empty), but the tx + info exist.
    DefaultArchiveService svc = genesisCompleteEmptyArchive();
    Wallet wallet = mock(Wallet.class);
    when(wallet.getTransactionById(ByteString.copyFrom(txId))).thenReturn(tx);
    when(wallet.getTransactionInfoById(ByteString.copyFrom(txId)))
        .thenReturn(TransactionInfo.newBuilder().setBlockNumber(2L).build());

    HistoricalTraceSupport support = new HistoricalTraceSupport(wallet, svc);
    JsonRpcInternalException ex = assertThrows(JsonRpcInternalException.class,
        () -> support.traceTransaction(txId, null));
    assertEquals("transaction not in archive", ex.getMessage());
  }

  @Test
  public void traceTransactionRejectsFetchedTransactionHashMismatch() throws Exception {
    byte[] contract = addr(0x11);
    byte[] caller = addr(0x22);
    byte[] otherCaller = addr(0x23);

    TransactionCapsule requested = new TransactionCapsule(
        TvmTestUtils.buildTriggerSmartContract(caller, contract, new byte[0], 0L),
        ContractType.TriggerSmartContract);
    byte[] requestedTxId = requested.getTransactionId().getBytes();
    Transaction wrongTx = new TransactionCapsule(
        TvmTestUtils.buildTriggerSmartContract(otherCaller, contract, new byte[0], 0L),
        ContractType.TriggerSmartContract).getInstance();
    DefaultArchiveService svc =
        buildArchive(new InMemoryArchiveTemporalStore(), contract, requestedTxId);

    Wallet wallet = mock(Wallet.class);
    when(wallet.getTransactionById(ByteString.copyFrom(requestedTxId))).thenReturn(wrongTx);

    HistoricalTraceSupport support = new HistoricalTraceSupport(wallet, svc);
    JsonRpcInternalException ex = assertThrows(JsonRpcInternalException.class,
        () -> support.traceTransaction(requestedTxId, null));
    assertEquals("transaction hash mismatch", ex.getMessage());
  }

  @Test
  public void traceTransactionRejectsArchivePositionBlockMismatch() throws Exception {
    byte[] contract = addr(0x11);
    byte[] caller = addr(0x22);

    TriggerSmartContract trigger =
        TvmTestUtils.buildTriggerSmartContract(caller, contract, new byte[0], 0L);
    TransactionCapsule trxCap =
        new TransactionCapsule(trigger, ContractType.TriggerSmartContract);
    Transaction tx = trxCap.getInstance();
    byte[] txId = trxCap.getTransactionId().getBytes();

    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    DefaultArchiveService svc = buildArchive(temporal, contract, txId);

    Wallet wallet = mock(Wallet.class);
    when(wallet.getTransactionById(ByteString.copyFrom(txId))).thenReturn(tx);
    when(wallet.getTransactionInfoById(ByteString.copyFrom(txId)))
        .thenReturn(TransactionInfo.newBuilder().setBlockNumber(3L).build());

    HistoricalTraceSupport support = new HistoricalTraceSupport(wallet, svc);
    JsonRpcInternalException ex = assertThrows(JsonRpcInternalException.class,
        () -> support.traceTransaction(txId, null));
    assertEquals("archive transaction position mismatch", ex.getMessage());
  }

  @Test
  public void traceTransactionRejectsArchiveBlockHashMismatch() throws Exception {
    byte[] contract = addr(0x11);
    byte[] caller = addr(0x22);

    TriggerSmartContract trigger =
        TvmTestUtils.buildTriggerSmartContract(caller, contract, new byte[0], 0L);
    TransactionCapsule trxCap =
        new TransactionCapsule(trigger, ContractType.TriggerSmartContract);
    Transaction tx = trxCap.getInstance();
    byte[] txId = trxCap.getTransactionId().getBytes();

    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    DefaultArchiveService svc = buildArchive(temporal, contract, txId, mismatchedBlockHash(2));

    Wallet wallet = mock(Wallet.class);
    when(wallet.getTransactionById(ByteString.copyFrom(txId))).thenReturn(tx);
    when(wallet.getTransactionInfoById(ByteString.copyFrom(txId)))
        .thenReturn(TransactionInfo.newBuilder().setBlockNumber(2L).build());
    BlockCapsule block = blockCapsule(2);
    when(wallet.getBlockByNum(2L)).thenReturn(block.getInstance());

    HistoricalTraceSupport support = new HistoricalTraceSupport(wallet, svc);
    JsonRpcInternalException ex = assertThrows(JsonRpcInternalException.class,
        () -> support.traceTransaction(txId, null));
    assertEquals("archive history hash mismatch for block 2", ex.getMessage());
  }

  @Test
  public void traceTransactionEmptyTraceStillRequiresArchivePosition() throws Exception {
    Transaction tx = Transaction.newBuilder()
        .setRawData(Transaction.raw.newBuilder().build())
        .build();
    byte[] txId = new TransactionCapsule(tx).getTransactionId().getBytes();
    DefaultArchiveService svc = genesisCompleteEmptyArchive();
    Wallet wallet = mock(Wallet.class);
    when(wallet.getTransactionById(ByteString.copyFrom(txId))).thenReturn(tx);
    when(wallet.getTransactionInfoById(ByteString.copyFrom(txId)))
        .thenReturn(TransactionInfo.newBuilder().setBlockNumber(2L).build());

    HistoricalTraceSupport support = new HistoricalTraceSupport(wallet, svc);
    JsonRpcInternalException ex = assertThrows(JsonRpcInternalException.class,
        () -> support.traceTransaction(txId, null));
    assertEquals("transaction not in archive", ex.getMessage());
  }

  @Test
  public void traceTransactionReturnsEmptyTraceForArchivedNonVmTransaction() throws Exception {
    Transaction tx = Transaction.newBuilder()
        .setRawData(Transaction.raw.newBuilder().build())
        .build();
    byte[] txId = new TransactionCapsule(tx).getTransactionId().getBytes();
    DefaultArchiveService svc = buildArchive(new InMemoryArchiveTemporalStore(), addr(0x11), txId);
    Wallet wallet = mock(Wallet.class);
    when(wallet.getTransactionById(ByteString.copyFrom(txId))).thenReturn(tx);
    when(wallet.getTransactionInfoById(ByteString.copyFrom(txId)))
        .thenReturn(TransactionInfo.newBuilder().setBlockNumber(2L).build());
    BlockCapsule block = blockCapsule(2);
    when(wallet.getBlockByNum(2L)).thenReturn(block.getInstance());

    HistoricalTraceSupport support = new HistoricalTraceSupport(wallet, svc);
    TraceResult result = support.traceTransaction(txId, null);

    assertEquals(0L, result.getGas());
    assertFalse(result.isFailed());
    assertEquals("", result.getReturnValue());
    assertTrue(result.getStructLogs().isEmpty());
  }

  @Test
  public void traceTransactionReplaysCreateSmartContractConstructor() throws Exception {
    byte[] owner = addr(0x22);
    SmartContract newContract = SmartContract.newBuilder()
        .setName("tracecreate")
        .setOriginAddress(ByteString.copyFrom(owner))
        .setConsumeUserResourcePercent(100)
        .setOriginEnergyLimit(1_000_000L)
        .setBytecode(ByteString.copyFrom(STOP_CODE))
        .build();
    CreateSmartContract create = CreateSmartContract.newBuilder()
        .setOwnerAddress(ByteString.copyFrom(owner))
        .setNewContract(newContract)
        .build();
    TransactionCapsule trxCap = new TransactionCapsule(create, ContractType.CreateSmartContract);
    trxCap.setFeeLimit(1_000_000_000L);
    Transaction tx = trxCap.getInstance();
    byte[] txId = trxCap.getTransactionId().getBytes();
    DefaultArchiveService svc = buildArchive(new InMemoryArchiveTemporalStore(), addr(0x11), txId);
    Wallet wallet = mock(Wallet.class);
    when(wallet.getTransactionById(ByteString.copyFrom(txId))).thenReturn(tx);
    when(wallet.getTransactionInfoById(ByteString.copyFrom(txId)))
        .thenReturn(TransactionInfo.newBuilder().setBlockNumber(2L)
            .setReceipt(ResourceReceipt.newBuilder().setEnergyUsageTotal(777L)).build());
    BlockCapsule block = blockCapsule(2);
    when(wallet.getBlockByNum(2L)).thenReturn(block.getInstance());

    HistoricalTraceSupport support = new HistoricalTraceSupport(wallet, svc);
    TraceResult result = support.traceTransaction(txId, null);

    assertFalse(result.isFailed());
    assertEquals("top-level gas must come from the real transaction receipt", 777L,
        result.getGas());
    assertEquals("", result.getReturnValue());
    assertEquals(1, result.getStructLogs().size());
    assertEquals("STOP", result.getStructLogs().get(0).getOp());
  }

  @Test
  public void traceTransactionNotFoundThrowsInvalidParams() {
    byte[] txId = new byte[32];
    txId[31] = 0x7;
    DefaultArchiveService svc = buildArchive(new InMemoryArchiveTemporalStore(), addr(0x11), txId);
    Wallet wallet = mock(Wallet.class);
    when(wallet.getTransactionById(ByteString.copyFrom(txId))).thenReturn(null);

    HistoricalTraceSupport support = new HistoricalTraceSupport(wallet, svc);
    JsonRpcInvalidParamsException ex = assertThrows(JsonRpcInvalidParamsException.class,
        () -> support.traceTransaction(txId, null));
    assertEquals("transaction not found", ex.getMessage());
  }
}
