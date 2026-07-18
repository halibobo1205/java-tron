package org.tron.core.services.jsonrpc;

import static org.junit.Assert.assertEquals;
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
import java.nio.charset.StandardCharsets;
import org.junit.Before;
import org.junit.Test;
import org.tron.common.BaseMethodTest;
import org.tron.common.parameter.CommonParameter;
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
import org.tron.core.exception.jsonrpc.JsonRpcInternalException;
import org.tron.protos.Protocol.Account;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;
import org.tron.protos.contract.SmartContractOuterClass.SmartContract;

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
  }

  @Test
  public void historicalEthCallReturnsArchivedStorageSlot() throws Exception {
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    DefaultArchiveService svc = new DefaultArchiveService(true, temporal);

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
    DefaultArchiveService svc = new DefaultArchiveService(true, new InMemoryArchiveTemporalStore());
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

  private static byte[] blockHash(long blockNum) {
    return blockCapsule(blockNum).getBlockId().getBytes();
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
