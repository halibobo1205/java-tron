package org.tron.core.services.jsonrpc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.tron.common.BaseMethodTest;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.runtime.TvmTestUtils;
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
import org.tron.protos.Protocol.Transaction;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;
import org.tron.protos.Protocol.TransactionInfo;
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

  private void put(InMemoryArchiveTemporalStore temporal, long txNum, ArchiveDomain domain,
      byte[] canonicalKey, byte[] valueBytes) {
    temporal.putChange(new ArchiveChangeRecord(
        new ArchiveTxPosition(txNum, 1, ArchivePhase.BLOCK_FINALIZE,
            ArchiveSource.NORMAL, -1, null),
        domain, canonicalKey, DomainValue.present(valueBytes)));
  }

  // Archives the contract (account/contract/code) at finalize txNum t1 of block 1, commits block 1,
  // then opens block 2, allocates prepare + the traced user tx, and commits. Returns the service.
  private DefaultArchiveService buildArchive(InMemoryArchiveTemporalStore temporal, byte[] contract,
      byte[] txId) {
    DefaultArchiveService svc = new DefaultArchiveService(true, temporal);

    // Block 1: archive the contract at the finalize txNum (getAsOf inclusive-after finds it).
    svc.getTxNumIndex().beginBlock(1, ArchiveSource.NORMAL);
    svc.getTxNumIndex().allocateSystemTx(1, ArchivePhase.BLOCK_PREPARE);
    ArchiveTxPosition fin1 = svc.getTxNumIndex().allocateSystemTx(1, ArchivePhase.BLOCK_FINALIZE);
    long t1 = fin1.getTxNum();
    put(temporal, t1, ArchiveDomain.ACCOUNT, contract,
        Account.newBuilder().setAddress(ByteString.copyFrom(contract)).build().toByteArray());
    put(temporal, t1, ArchiveDomain.CONTRACT, contract,
        SmartContract.newBuilder().setContractAddress(ByteString.copyFrom(contract)).build()
            .toByteArray());
    put(temporal, t1, ArchiveDomain.CODE, contract, ADD_CODE);
    svc.getTxNumIndex().commitBlock(1, 0);

    // Block 2: prepare, then the traced user tx (txNum t), then finalize.
    svc.getTxNumIndex().beginBlock(2, ArchiveSource.NORMAL);
    svc.getTxNumIndex().allocateSystemTx(2, ArchivePhase.BLOCK_PREPARE);
    svc.getTxNumIndex().allocateUserTx(2, 0, txId);
    svc.getTxNumIndex().allocateSystemTx(2, ArchivePhase.BLOCK_FINALIZE);
    svc.getTxNumIndex().commitBlock(2, 1);
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
    BlockCapsule block = new BlockCapsule(2L, Sha256Hash.ZERO_HASH, 1000L,
        ByteString.copyFrom(new byte[21]));
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
    DefaultArchiveService svc = new DefaultArchiveService(true, new InMemoryArchiveTemporalStore());
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
  public void traceTransactionNotFoundThrowsInvalidParams() {
    byte[] txId = new byte[32];
    txId[31] = 0x7;
    DefaultArchiveService svc = new DefaultArchiveService(true, new InMemoryArchiveTemporalStore());
    Wallet wallet = mock(Wallet.class);
    when(wallet.getTransactionById(ByteString.copyFrom(txId))).thenReturn(null);

    HistoricalTraceSupport support = new HistoricalTraceSupport(wallet, svc);
    JsonRpcInvalidParamsException ex = assertThrows(JsonRpcInvalidParamsException.class,
        () -> support.traceTransaction(txId, null));
    assertEquals("transaction not found", ex.getMessage());
  }
}
