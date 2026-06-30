package org.tron.core.vm.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.tron.common.runtime.vm.DataWord;
import org.tron.core.archive.reader.ArchiveReadResult;
import org.tron.core.archive.reader.ArchiveReaderException;
import org.tron.core.archive.reader.ArchiveStatePoint;
import org.tron.core.archive.reader.ArchiveStateReader;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.ContractCapsule;
import org.tron.core.capsule.ContractStateCapsule;
import org.tron.core.store.VmDynamicProperties;
import org.tron.protos.Protocol;

/**
 * Slice 2 unit tests: the adapter serves reads from a fake {@link ArchiveStateReader}, maps the
 * three-state result to the Repository read contract, exposes the injected historical
 * {@link VmDynamicProperties}, and fails fast (never silently latest) on uncovered domains and on
 * an archive read error. Writes / overlay are deferred to Slice 3 and must throw.
 */
public class ArchiveRepositoryAdapterTest {

  private static final byte[] ADDR = new byte[21];

  static {
    ADDR[0] = 0x41;
  }

  private final FakeReader reader = new FakeReader();
  private final VmDynamicProperties vmProps = mock(VmDynamicProperties.class);
  private final ArchiveRepositoryAdapter adapter = new ArchiveRepositoryAdapter(reader, vmProps);

  /** Reader whose results each test sets directly; the address argument is ignored. */
  private static final class FakeReader implements ArchiveStateReader {
    ArchiveReadResult<AccountCapsule> account = ArchiveReadResult.missing();
    ArchiveReadResult<ContractCapsule> contract = ArchiveReadResult.missing();
    ArchiveReadResult<ContractStateCapsule> contractState = ArchiveReadResult.missing();
    ArchiveReadResult<byte[]> code = ArchiveReadResult.missing();
    ArchiveReadResult<byte[]> storage = ArchiveReadResult.missing();
    ArchiveReaderException accountError;

    public ArchiveStatePoint getPoint() {
      return null;
    }

    public ArchiveReadResult<AccountCapsule> getAccount(byte[] a) throws ArchiveReaderException {
      if (accountError != null) {
        throw accountError;
      }
      return account;
    }

    public ArchiveReadResult<ContractCapsule> getContract(byte[] a) {
      return contract;
    }

    public ArchiveReadResult<ContractStateCapsule> getContractState(byte[] a) {
      return contractState;
    }

    public ArchiveReadResult<byte[]> getCode(byte[] a) {
      return code;
    }

    public ArchiveReadResult<byte[]> getStorage(byte[] a, byte[] slot) {
      return storage;
    }

    public ArchiveReadResult<byte[]> getDynamicProperty(byte[] key) {
      return ArchiveReadResult.missing();
    }

    public void close() {
    }
  }

  private static AccountCapsule account(long balance) {
    return new AccountCapsule(Protocol.Account.newBuilder().setBalance(balance).build());
  }

  @Test
  public void presentAccountReadsBalanceMissingReadsZero() {
    reader.account = ArchiveReadResult.present(account(777L));
    assertEquals(777L, adapter.getBalance(ADDR));
    assertEquals(777L, adapter.getAccount(ADDR).getBalance());

    reader.account = ArchiveReadResult.missing();
    assertNull(adapter.getAccount(ADDR));
    assertEquals(0L, adapter.getBalance(ADDR));
  }

  @Test
  public void tombstoneAccountIsNull() {
    reader.account = ArchiveReadResult.tombstone();
    assertNull(adapter.getAccount(ADDR));
    assertEquals(0L, adapter.getBalance(ADDR));
  }

  @Test
  public void codePresentAndMissing() {
    byte[] code = {1, 2, 3};
    reader.code = ArchiveReadResult.present(code);
    assertSame(code, adapter.getCode(ADDR));
    reader.code = ArchiveReadResult.missing();
    assertNull(adapter.getCode(ADDR));
  }

  @Test
  public void contractPresentAndMissing() {
    ContractCapsule contract = new ContractCapsule(new byte[] {9});
    reader.contract = ArchiveReadResult.present(contract);
    assertSame(contract, adapter.getContract(ADDR));
    reader.contract = ArchiveReadResult.missing();
    assertNull(adapter.getContract(ADDR));
  }

  @Test
  public void storageReadsArchivedWordOnlyWhenAccountExists() {
    reader.account = ArchiveReadResult.present(account(1L));
    byte[] word = new byte[32];
    word[31] = 42;
    reader.storage = ArchiveReadResult.present(word);
    assertEquals(new DataWord(word), adapter.getStorageValue(ADDR, new DataWord(new byte[] {5})));

    reader.storage = ArchiveReadResult.missing();
    assertNull(adapter.getStorageValue(ADDR, new DataWord(new byte[] {5})));

    // No account -> no storage value, even if a row were archived.
    reader.account = ArchiveReadResult.missing();
    reader.storage = ArchiveReadResult.present(word);
    assertNull(adapter.getStorageValue(ADDR, new DataWord(new byte[] {5})));
  }

  @Test
  public void vmDynamicPropertiesIsTheInjectedHistoricalView() {
    assertSame(vmProps, adapter.getVmDynamicProperties());
  }

  @Test
  public void dynamicPropertiesStoreIsUnsupported() {
    assertThrows(UnsupportedHistoricalStateException.class, adapter::getDynamicPropertiesStore);
  }

  @Test
  public void contractStateReadsArchiveAndMissingFallsBackToNeutralCapsule() {
    ContractStateCapsule archived = new ContractStateCapsule(1L);
    archived.setEnergyFactor(123L);
    reader.contractState = ArchiveReadResult.present(archived);
    assertEquals(123L, adapter.getContractState(ADDR).getEnergyFactor());

    reader.contractState = ArchiveReadResult.missing();
    when(vmProps.getCurrentCycleNumber()).thenReturn(9L);
    ContractStateCapsule neutral = adapter.getContractState(ADDR);
    assertEquals(0L, neutral.getEnergyFactor());
    assertEquals(9L, neutral.getUpdateCycle());
  }

  @Test
  public void midChainMissingStateFailsClosed() {
    ArchiveRepositoryAdapter midChain =
        new ArchiveRepositoryAdapter(reader, vmProps, false);

    assertThrows(UnsupportedHistoricalStateException.class,
        () -> midChain.getAccount(ADDR));
    assertThrows(UnsupportedHistoricalStateException.class,
        () -> midChain.getCode(ADDR));
    assertThrows(UnsupportedHistoricalStateException.class,
        () -> midChain.getContract(ADDR));
    assertThrows(UnsupportedHistoricalStateException.class,
        () -> midChain.getContractState(ADDR));

    reader.account = ArchiveReadResult.present(account(1L));
    assertThrows(UnsupportedHistoricalStateException.class,
        () -> midChain.getStorageValue(ADDR, new DataWord(new byte[] {5})));
  }

  @Test
  public void uncoveredDomainsFailFast() {
    assertThrows(UnsupportedHistoricalStateException.class, () -> adapter.getVotes(ADDR));
    assertThrows(UnsupportedHistoricalStateException.class, () -> adapter.getWitness(ADDR));
    assertThrows(UnsupportedHistoricalStateException.class,
        () -> adapter.getDelegatedResource(ADDR));
  }

  @Test
  public void archiveReadErrorIsWrappedNotSilent() {
    reader.accountError = new ArchiveReaderException(
        ArchiveReaderException.Reason.CORRUPT_VALUE, "boom");
    assertThrows(UnsupportedHistoricalStateException.class, () -> adapter.getAccount(ADDR));
  }

  @Test
  public void unsupportedOverlayVariantsStillFailFast() {
    // The VM never calls these on the archive path; they fail fast rather than silently no-op.
    assertThrows(UnsupportedHistoricalStateException.class, () -> adapter.getStorage(ADDR));
    assertThrows(UnsupportedHistoricalStateException.class, () -> adapter.putAccount(null, null));
    assertThrows(UnsupportedHistoricalStateException.class, () -> adapter.getWitness(ADDR));
  }

  @Test
  public void valueTransferToFreshAccountMaterializesItInOverlay() {
    // A value-bearing CALL to a non-existent address creates it in the overlay instead of aborting.
    long balance = adapter.addBalance(ADDR, 250L);
    assertEquals(250L, balance);
    assertEquals(250L, adapter.getBalance(ADDR));
    assertEquals(250L, adapter.getAccount(ADDR).getBalance());
  }

  @Test
  public void storageWriteThenReadIsVisibleInOverlay() {
    DataWord key = new DataWord(new byte[] {7});
    DataWord value = new DataWord(new byte[] {0, 9});
    adapter.putStorageValue(ADDR, key, value);
    assertEquals(value, adapter.getStorageValue(ADDR, key));
  }

  @Test
  public void accountOverlayWriteIsReadBack() {
    adapter.putAccountValue(ADDR, account(500L));
    assertEquals(500L, adapter.getBalance(ADDR));
    assertEquals(500L, adapter.getAccount(ADDR).getBalance());
  }

  @Test
  public void childWritesAreInvisibleToParentUntilCommit() {
    // The account must exist so the parent's archive storage path is reachable (returns missing).
    reader.account = ArchiveReadResult.present(account(1L));
    ArchiveRepositoryAdapter child = (ArchiveRepositoryAdapter) adapter.newRepositoryChild();
    DataWord key = new DataWord(new byte[] {3});
    DataWord value = new DataWord(new byte[] {42});
    child.putStorageValue(ADDR, key, value);

    // Child sees its own write; the parent does not (the archive has no such row).
    assertEquals(value, child.getStorageValue(ADDR, key));
    assertNull(adapter.getStorageValue(ADDR, key));

    // Commit merges the child overlay into the parent.
    child.commit();
    assertEquals(value, adapter.getStorageValue(ADDR, key));
  }
}
