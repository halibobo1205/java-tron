package org.tron.core.archive.reader;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.google.common.primitives.Bytes;
import com.google.protobuf.ByteString;
import org.junit.Before;
import org.junit.Test;
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveSource;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.domain.ArchiveDomainCatalog;
import org.tron.core.archive.domain.DefaultArchiveDomainCatalog;
import org.tron.core.archive.reader.ArchiveReadResult.Status;
import org.tron.core.archive.temporal.InMemoryArchiveTemporalStore;
import org.tron.core.archive.txnum.ArchiveTxPosition;
import org.tron.core.capsule.AccountCapsule;
import org.tron.protos.Protocol.Account;
import org.tron.protos.contract.SmartContractOuterClass.SmartContract;

public class DefaultArchiveStateReaderTest {

  private InMemoryArchiveTemporalStore store;
  private ArchiveDomainCatalog catalog;

  @Before
  public void setUp() {
    store = new InMemoryArchiveTemporalStore();
    catalog = new DefaultArchiveDomainCatalog();
  }

  private ArchiveStateReader readerAt(long txNum) {
    return new DefaultArchiveStateReader(store, catalog,
        ArchiveStatePoint.blockEnd(1, new byte[] {1}, txNum));
  }

  private void put(ArchiveDomain domain, byte[] key, DomainValue value, long txNum) {
    store.putChange(new ArchiveChangeRecord(
        new ArchiveTxPosition(txNum, 1, ArchivePhase.BLOCK_FINALIZE,
            ArchiveSource.NORMAL, -1, null),
        domain, key, value));
  }

  private static byte[] addr(int last) {
    byte[] a = new byte[21];
    a[0] = 0x41;
    a[20] = (byte) last;
    return a;
  }

  private static byte[] account(long balance) {
    return Account.newBuilder().setBalance(balance).build().toByteArray();
  }

  @Test
  public void getAccountResolvesThreeStates() throws Exception {
    put(ArchiveDomain.ACCOUNT, addr(1), DomainValue.present(account(100)), 5);
    put(ArchiveDomain.ACCOUNT, addr(2), DomainValue.tombstone(), 5);
    ArchiveStateReader reader = readerAt(5);
    ArchiveReadResult<AccountCapsule> present = reader.getAccount(addr(1));
    assertEquals(Status.PRESENT, present.getStatus());
    assertEquals(100, present.getValue().getBalance());
    assertEquals(Status.TOMBSTONE, reader.getAccount(addr(2)).getStatus());
    assertEquals(Status.MISSING, reader.getAccount(addr(3)).getStatus());
  }

  @Test
  public void noFallbackToLatestAndInclusiveAfter() throws Exception {
    put(ArchiveDomain.ACCOUNT, addr(1), DomainValue.present(account(100)), 5);
    // at txNum 4 the tx-5 write is not yet visible -> MISSING, NOT the live/latest value
    assertEquals(Status.MISSING, readerAt(4).getAccount(addr(1)).getStatus());
    assertEquals(Status.PRESENT, readerAt(5).getAccount(addr(1)).getStatus());
  }

  @Test
  public void getCodeAndStorage() throws Exception {
    put(ArchiveDomain.CODE, addr(1), DomainValue.present(new byte[] {0x60, (byte) 0x80}), 5);
    byte[] slot = new byte[32];
    slot[31] = 7;
    byte[] word = new byte[32];
    word[31] = 9;
    put(ArchiveDomain.CONTRACT_STORAGE, Bytes.concat(addr(1), slot, new byte[] {0}),
        DomainValue.present(word), 5);
    ArchiveStateReader reader = readerAt(5);
    assertArrayEquals(new byte[] {0x60, (byte) 0x80}, reader.getCode(addr(1)).getValue());
    assertArrayEquals(word, reader.getStorage(addr(1), slot).getValue());
    assertEquals(Status.MISSING, reader.getStorage(addr(2), slot).getStatus());
  }

  @Test
  public void getContractParsesArchivedContract() throws Exception {
    byte[] contract = SmartContract.newBuilder()
        .setBytecode(ByteString.copyFromUtf8("X")).build().toByteArray();
    put(ArchiveDomain.CONTRACT, addr(1), DomainValue.present(contract), 5);
    assertEquals(Status.PRESENT, readerAt(5).getContract(addr(1)).getStatus());
  }

  @Test
  public void badInputIsIllegalArgumentNotArchiveError() {
    assertThrows(IllegalArgumentException.class, () -> readerAt(5).getAccount(new byte[5]));
    assertThrows(IllegalArgumentException.class,
        () -> readerAt(5).getStorage(addr(1), new byte[8]));
  }

  @Test
  public void corruptValueThrowsCodecErrorNotMissing() {
    put(ArchiveDomain.ACCOUNT, addr(1), DomainValue.present(new byte[] {(byte) 0xff, 0x01}), 5);
    ArchiveReaderException e = assertThrows(ArchiveReaderException.class,
        () -> readerAt(5).getAccount(addr(1)));
    assertEquals(ArchiveReaderException.Reason.CODEC_ERROR, e.getReason());
  }
}
