package org.tron.core.archive.reader;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.tron.core.ChainBaseManager;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.CodeCapsule;
import org.tron.core.capsule.ContractCapsule;
import org.tron.core.capsule.ContractStateCapsule;
import org.tron.core.store.AccountStore;
import org.tron.core.store.CodeStore;
import org.tron.core.store.ContractStateStore;
import org.tron.core.store.ContractStore;
import org.tron.protos.Protocol.Account;
import org.tron.protos.contract.SmartContractOuterClass.SmartContract;

/**
 * ChainBaseArchiveReadThrough maps live ChainBase state to the mid-chain archive baseline. Its
 * load-bearing invariant is: an ABSENT live entry is a TOMBSTONE (rendered as the default
 * zero/empty), NOT MISSING (which the coverage gate turns into an error); a PRESENT live entry is
 * PRESENT carrying the live bytes. The existing reader tests only exercise an inline-lambda
 * read-through (and only its DYNAMIC_PROPERTIES branch), so the real per-domain account / code /
 * contract / contract-state mapping was undriven -- a regression flipping absent to MISSING turns a
 * legitimately-empty mid-chain account/code into a spurious RPC 500.
 */
public class ChainBaseArchiveReadThroughTest {

  private static final byte[] ADDR = new byte[21];

  static {
    ADDR[0] = 0x41;
    ADDR[20] = 0x11;
  }

  private AccountStore accountStore;
  private CodeStore codeStore;
  private ContractStore contractStore;
  private ContractStateStore contractStateStore;
  private ChainBaseArchiveReadThrough readThrough;

  @Before
  public void setUp() {
    ChainBaseManager cbm = mock(ChainBaseManager.class);
    accountStore = mock(AccountStore.class);
    codeStore = mock(CodeStore.class);
    contractStore = mock(ContractStore.class);
    contractStateStore = mock(ContractStateStore.class);
    when(cbm.getAccountStore()).thenReturn(accountStore);
    when(cbm.getCodeStore()).thenReturn(codeStore);
    when(cbm.getContractStore()).thenReturn(contractStore);
    when(cbm.getContractStateStore()).thenReturn(contractStateStore);
    readThrough = new ChainBaseArchiveReadThrough(cbm);
  }

  private DomainValue read(ArchiveDomain domain) throws Exception {
    Optional<DomainValue> v = readThrough.read(domain, ADDR, null);
    assertTrue("read-through must always resolve (tombstone or present)", v.isPresent());
    return v.get();
  }

  @Test
  public void absentAccountIsTombstonePresentIsPresent() throws Exception {
    when(accountStore.get(ADDR)).thenReturn(null);
    assertTrue("absent account -> tombstone (default), not MISSING", read(ArchiveDomain.ACCOUNT)
        .isDeleted());

    when(accountStore.get(ADDR)).thenReturn(
        new AccountCapsule(Account.newBuilder().setBalance(9L).build()));
    assertFalse(read(ArchiveDomain.ACCOUNT).isDeleted());
  }

  @Test
  public void absentCodeIsTombstonePresentKeepsBytes() throws Exception {
    when(codeStore.has(ADDR)).thenReturn(false);
    assertTrue(read(ArchiveDomain.CODE).isDeleted());

    byte[] code = new byte[] {0x60, 0x00};
    when(codeStore.has(ADDR)).thenReturn(true);
    when(codeStore.get(ADDR)).thenReturn(new CodeCapsule(code));
    DomainValue present = read(ArchiveDomain.CODE);
    assertFalse(present.isDeleted());
    assertArrayEquals("CODE is a raw-bytes passthrough", code, present.getValue());
  }

  @Test
  public void absentContractIsTombstonePresentIsPresent() throws Exception {
    when(contractStore.has(ADDR)).thenReturn(false);
    assertTrue(read(ArchiveDomain.CONTRACT).isDeleted());

    when(contractStore.has(ADDR)).thenReturn(true);
    when(contractStore.get(ADDR)).thenReturn(new ContractCapsule(SmartContract.newBuilder()
        .setContractAddress(ByteString.copyFrom(ADDR)).build()));
    assertFalse(read(ArchiveDomain.CONTRACT).isDeleted());
  }

  @Test
  public void absentContractStateIsTombstonePresentIsPresent() throws Exception {
    when(contractStateStore.has(ADDR)).thenReturn(false);
    assertTrue(read(ArchiveDomain.CONTRACT_STATE).isDeleted());

    when(contractStateStore.has(ADDR)).thenReturn(true);
    when(contractStateStore.get(ADDR)).thenReturn(new ContractStateCapsule(0L));
    assertFalse(read(ArchiveDomain.CONTRACT_STATE).isDeleted());
  }
}
