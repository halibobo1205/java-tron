package org.tron.core.services.jsonrpc;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import org.junit.Before;
import org.junit.Test;
import org.tron.common.BaseMethodTest;
import org.tron.common.parameter.CommonParameter;
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
    // Genesis-complete create at txNum (prev = tombstone): getAsOf(txNum) falls through to latest.
    temporal.putChange(new ArchiveChangeRecord(
        new ArchiveTxPosition(txNum, 1, ArchivePhase.BLOCK_FINALIZE,
            ArchiveSource.NORMAL, -1, null),
        domain, canonicalKey, DomainValue.tombstone(), DomainValue.present(valueBytes)));
  }

  @Test
  public void historicalEthCallReturnsArchivedStorageSlot() throws Exception {
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    DefaultArchiveService svc = new DefaultArchiveService(true, temporal);

    // Index block 1 so the resolver maps "0x1" -> its finalize txNum (genesis-complete: first 1).
    svc.getTxNumIndex().beginBlock(1, ArchiveSource.NORMAL);
    svc.getTxNumIndex().allocateSystemTx(1, ArchivePhase.BLOCK_PREPARE);
    svc.getTxNumIndex().allocateSystemTx(1, ArchivePhase.BLOCK_FINALIZE);
    ArchiveBlockRange range = svc.getTxNumIndex().commitBlock(1, 0);
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

    Wallet wallet = mock(Wallet.class);
    BlockCapsule block = new BlockCapsule(1L, Sha256Hash.ZERO_HASH, 1000L,
        ByteString.copyFrom(new byte[21]));
    when(wallet.getBlockByNum(1L)).thenReturn(block.getInstance());
    when(wallet.createTransactionCapsule(any(), eq(ContractType.TriggerSmartContract)))
        .thenAnswer(inv -> new TransactionCapsule(
            (Message) inv.getArgument(0), ContractType.TriggerSmartContract));

    HistoricalEthCallSupport support = new HistoricalEthCallSupport(wallet, svc);

    String hex = support.call(caller, addr, 0L, new byte[0], "0x1");

    // 0x + 64 hex chars: 62 leading zeros then "2a" -- the archived slot-0 word, not the latest
    // store (which has no such contract). This proves resolver -> reader -> archived read ->
    // executor -> hex render all served the ARCHIVED value.
    assertEquals(
        "0x000000000000000000000000000000000000000000000000000000000000002a", hex);
  }
}
