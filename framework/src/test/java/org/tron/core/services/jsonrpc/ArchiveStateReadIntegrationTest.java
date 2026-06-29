package org.tron.core.services.jsonrpc;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import org.junit.After;
import org.junit.Test;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.Wallet;
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveSource;
import org.tron.core.archive.DefaultArchiveService;
import org.tron.core.archive.capture.ArchiveCaptureHolder;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.reader.ArchiveStorageKeyCodec;
import org.tron.core.archive.temporal.InMemoryArchiveTemporalStore;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.archive.txnum.ArchiveTxPosition;
import org.tron.core.capsule.BlockCapsule;
import org.tron.protos.Protocol.Account;

/**
 * End-to-end positive test for the L6 historical state-read path: a genesis-complete archive
 * (first archived block = 1) is populated with an account / code / storage slot at block 1, and the
 * {@link ArchiveJsonRpcStateAdapter} renders eth_getBalance / eth_getCode / eth_getStorageAt with
 * the ARCHIVED values -- proving resolver -> coverage gate -> reader -> hex render end to end. The
 * eth-form 20-byte address is converted to the 21-byte TRON address the reader keys on.
 */
public class ArchiveStateReadIntegrationTest {

  @After
  public void clearCaptureHolder() {
    ArchiveCaptureHolder.clear();
  }

  @Test
  public void genesisCompleteArchiveReturnsHistoricalBalanceCodeStorage() throws Exception {
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    DefaultArchiveService svc = new DefaultArchiveService(true, temporal);

    // Genesis-complete: first archived block = 1, so the coverage gate passes.
    svc.getTxNumIndex().beginBlock(1, ArchiveSource.NORMAL);
    svc.getTxNumIndex().allocateSystemTx(1, ArchivePhase.BLOCK_PREPARE);
    svc.getTxNumIndex().allocateSystemTx(1, ArchivePhase.BLOCK_FINALIZE);
    ArchiveBlockRange range = svc.getTxNumIndex().commitBlock(1, 0);
    long t = range.getFinalizeTxNum();

    byte[] addr21 = new byte[21];
    addr21[0] = 0x41;
    addr21[20] = 0x11;
    put(temporal, t, ArchiveDomain.ACCOUNT, addr21,
        Account.newBuilder().setAddress(ByteString.copyFrom(addr21)).setBalance(777L).build()
            .toByteArray());
    put(temporal, t, ArchiveDomain.CODE, addr21, new byte[] {0x60, 0x00});
    byte[] slot = new byte[32];
    byte[] word = new byte[32];
    word[31] = 0x2a;
    put(temporal, t, ArchiveDomain.CONTRACT_STORAGE,
        ArchiveStorageKeyCodec.contractStorageKey(addr21, slot, 0), word);

    Wallet wallet = mock(Wallet.class);
    BlockCapsule block = new BlockCapsule(1L, Sha256Hash.ZERO_HASH, 1000L,
        ByteString.copyFrom(new byte[21]));
    when(wallet.getBlockByNum(1L)).thenReturn(block.getInstance());

    ArchiveJsonRpcStateAdapter adapter = new ArchiveJsonRpcStateAdapter(wallet, svc);
    // 20-byte eth form; addressCompatibleToByteArray prepends 0x41 -> addr21.
    String ethAddr = "0x0000000000000000000000000000000000000011";

    assertEquals("0x309", adapter.getBalance(ethAddr, "0x1"));      // 777 == 0x309
    assertEquals("0x6000", adapter.getCode(ethAddr, "0x1"));
    assertEquals("0x000000000000000000000000000000000000000000000000000000000000002a",
        adapter.getStorageAt(ethAddr, "0x0", "0x1"));
  }

  private void put(InMemoryArchiveTemporalStore temporal, long txNum, ArchiveDomain domain,
      byte[] key, byte[] value) {
    ArchiveTxPosition pos = new ArchiveTxPosition(
        txNum, 1, ArchivePhase.BLOCK_FINALIZE, ArchiveSource.NORMAL, -1, null);
    temporal.putChange(new ArchiveChangeRecord(pos, domain, key, DomainValue.present(value)));
  }
}
