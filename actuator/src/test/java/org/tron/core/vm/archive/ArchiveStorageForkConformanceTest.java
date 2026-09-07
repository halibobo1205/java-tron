package org.tron.core.vm.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import java.util.Arrays;
import org.bouncycastle.util.encoders.Hex;
import org.junit.Test;
import org.mockito.Answers;
import org.mockito.MockedStatic;
import org.tron.common.runtime.InternalTransaction;
import org.tron.common.runtime.ProgramResult;
import org.tron.common.runtime.vm.DataWord;
import org.tron.common.utils.StorageUtils;
import org.tron.core.ChainBaseManager;
import org.tron.core.archive.query.ArchiveQueryLimits;
import org.tron.core.archive.query.HistoricalQueryLimitException;
import org.tron.core.archive.query.QueryContext;
import org.tron.core.archive.query.QueryContextHolder;
import org.tron.core.archive.reader.ArchiveReadResult;
import org.tron.core.archive.reader.ArchiveStateReader;
import org.tron.core.archive.reader.ArchiveStorageKeyCodec;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.CodeCapsule;
import org.tron.core.capsule.ContractCapsule;
import org.tron.core.capsule.StorageRowCapsule;
import org.tron.core.store.AccountStore;
import org.tron.core.store.CodeStore;
import org.tron.core.store.ContractStore;
import org.tron.core.store.StorageRowStore;
import org.tron.core.store.StoreFactory;
import org.tron.core.store.VmDynamicProperties;
import org.tron.core.vm.OperationRegistry;
import org.tron.core.vm.VM;
import org.tron.core.vm.config.VMConfig;
import org.tron.core.vm.program.Program;
import org.tron.core.vm.program.invoke.ProgramInvokeImpl;
import org.tron.core.vm.repository.Repository;
import org.tron.core.vm.repository.RepositoryImpl;
import org.tron.protos.Protocol;
import org.tron.protos.contract.SmartContractOuterClass.SmartContract;

public class ArchiveStorageForkConformanceTest {

  private static final byte[] PARENT = Hex.decode("410000000000000000000000000000000000000042");
  private static final byte[] CHILD = Hex.decode("410000000000000000000000000000000000000043");
  private static final byte[] OTHER = Hex.decode("410000000000000000000000000000000000000044");
  private static final long INITIAL_VALUE = 7L;
  private static final long CHILD_VALUE = 1L;
  private static final byte[] CHILD_CODE = Hex.decode("600160005560006000fd");
  private static final long OVERLAY_LIMIT = 1_048_576L;

  @Test
  public void preEnergyLimitForkParentReadSeesRevertedDelegateWrite() throws Exception {
    assertDelegateRevertConforms(false, CHILD_VALUE);
  }

  @Test
  public void postEnergyLimitForkParentReadDiscardsRevertedDelegateWrite() throws Exception {
    assertDelegateRevertConforms(true, INITIAL_VALUE);
  }

  @Test
  public void uncachedParentDoesNotShareRevertedChildStorageOnEitherSideOfFork() throws Exception {
    for (boolean fork : new boolean[] {false, true}) {
      assertVmScenario(fork, Hex.decode(delegate(CHILD) + returnSlot()), CHILD_CODE,
          new byte[] {0}, INITIAL_VALUE, true);
    }
  }

  @Test
  public void committedChildSuppliesViewForLaterRevertedSibling() throws Exception {
    for (boolean fork : new boolean[] {false, true}) {
      assertVmScenario(fork, Hex.decode(delegate(CHILD) + delegate(OTHER) + returnSlot()),
          Hex.decode("600160005500"), Hex.decode("600260005560006000fd"),
          fork ? CHILD_VALUE : 2L, false, true);
    }
  }

  @Test
  public void nestedRevertsShareWarmAncestorButDoNotWarmColdAncestor() throws Exception {
    for (boolean fork : new boolean[] {false, true}) {
      byte[] nestedRevert = Hex.decode(delegate(OTHER) + "60006000fd");
      assertVmScenario(fork, parentCode(), nestedRevert, CHILD_CODE,
          fork ? INITIAL_VALUE : CHILD_VALUE, true, true);
      byte[] warmMiddle = Hex.decode("60005450" + delegate(OTHER) + "60006000fd");
      assertVmScenario(fork, Hex.decode(delegate(CHILD) + returnSlot()), warmMiddle, CHILD_CODE,
          INITIAL_VALUE, true, true);
    }
  }

  @Test
  public void cachedSiblingCommitReplacesViewRatherThanMergingNewerSlots() throws Exception {
    Fixture fixture = new Fixture();
    try (VMConfig.LocalSnapshotScope ignored = VMConfig.preserveLocalSnapshot();
        MockedStatic<StorageUtils> storageFlags = mockStatic(StorageUtils.class)) {
      for (boolean fork : new boolean[] {false, true}) {
        installFork(fork);
        storageFlags.when(StorageUtils::getEnergyLimitHardFork).thenReturn(fork);
        Repository canonical = RepositoryImpl.createRoot(fixture.storeFactory);
        siblingCommit(canonical);
        DataWord expectedOtherSlot = fork ? null : new DataWord(2L);
        assertEquals(new DataWord(CHILD_VALUE), canonical.getStorageValue(PARENT, DataWord.ZERO()));
        assertEquals(expectedOtherSlot, canonical.getStorageValue(PARENT, DataWord.ONE()));

        storageFlags.when(StorageUtils::getEnergyLimitHardFork).thenReturn(!fork);
        Repository archive = fixture.newArchiveRepository();
        siblingCommit(archive);
        assertEquals(new DataWord(CHILD_VALUE), archive.getStorageValue(PARENT, DataWord.ZERO()));
        assertEquals(expectedOtherSlot, archive.getStorageValue(PARENT, DataWord.ONE()));
        assertEquals(new DataWord(INITIAL_VALUE),
            fixture.newArchiveRepository().getStorageValue(PARENT, DataWord.ZERO()));
      }
    }
  }

  @Test
  public void postForkCopyBudgetFailureDoesNotInstallPartialView() throws Exception {
    Fixture fixture = new Fixture();
    try (VMConfig.LocalSnapshotScope ignored = VMConfig.preserveLocalSnapshot()) {
      installFork(true);
      Repository root = fixture.newArchiveRepository();
      root.putStorageValue(PARENT, DataWord.ZERO(), new DataWord(CHILD_VALUE));
      Repository child = root.newRepositoryChild();
      // One address/view entry (213), followed by one copied slot entry (277).
      QueryContext context = new QueryContext(ArchiveQueryLimits.builder()
          .maxVmOverlayBytes(489L).build());
      try (QueryContextHolder.Scope query = QueryContextHolder.attach(context)) {
        HistoricalQueryLimitException failure = assertThrows(HistoricalQueryLimitException.class,
            () -> child.getStorageValue(PARENT, DataWord.ZERO()));
        assertEquals(HistoricalQueryLimitException.Limit.VM_OVERLAY_BYTES, failure.getLimit());
        assertEquals(490L, failure.getObserved());
      }
      root.putStorageValue(PARENT, DataWord.ZERO(), new DataWord(2L));
      assertEquals("failed copying must not leave an old snapshot cached", new DataWord(2L),
          child.getStorageValue(PARENT, DataWord.ZERO()));
      assertEquals(new DataWord(INITIAL_VALUE),
          fixture.newArchiveRepository().getStorageValue(PARENT, DataWord.ZERO()));
    }
  }

  @Test
  public void preForkWriteBudgetFailureCannotMutateSharedParentView() throws Exception {
    Fixture fixture = new Fixture();
    try (VMConfig.LocalSnapshotScope ignored = VMConfig.preserveLocalSnapshot()) {
      installFork(false);
      Repository root = fixture.newArchiveRepository();
      assertEquals(new DataWord(INITIAL_VALUE), root.getStorageValue(PARENT, DataWord.ZERO()));
      Repository child = root.newRepositoryChild();
      assertEquals(new DataWord(INITIAL_VALUE), child.getStorageValue(PARENT, DataWord.ZERO()));
      QueryContext context = new QueryContext(ArchiveQueryLimits.builder()
          .maxVmOverlayBytes(276L).build());
      try (QueryContextHolder.Scope query = QueryContextHolder.attach(context)) {
        HistoricalQueryLimitException failure = assertThrows(HistoricalQueryLimitException.class,
            () -> child.putStorageValue(PARENT, DataWord.ZERO(), new DataWord(CHILD_VALUE)));
        assertEquals(HistoricalQueryLimitException.Limit.VM_OVERLAY_BYTES, failure.getLimit());
        assertEquals(277L, failure.getObserved());
      }
      assertEquals(new DataWord(INITIAL_VALUE), root.getStorageValue(PARENT, DataWord.ZERO()));
      assertEquals(new DataWord(INITIAL_VALUE), child.getStorageValue(PARENT, DataWord.ZERO()));
    }
  }

  @Test
  public void readOnlyStorageViewsAndCommitReferencesAreBudgeted() throws Exception {
    Fixture fixture = new Fixture();
    try (VMConfig.LocalSnapshotScope ignored = VMConfig.preserveLocalSnapshot()) {
      installFork(false);
      Repository root = fixture.newArchiveRepository();
      assertViewBudgetFailure(() -> root.getStorageValue(PARENT, DataWord.ZERO()));
      Repository child = root.newRepositoryChild();
      assertEquals(new DataWord(INITIAL_VALUE), child.getStorageValue(PARENT, DataWord.ZERO()));
      assertViewBudgetFailure(child::commit);
      root.putStorageValue(PARENT, DataWord.ZERO(), new DataWord(2L));
      assertEquals("neither failed read nor failed commit may cache the child's view in root",
          new DataWord(INITIAL_VALUE), child.getStorageValue(PARENT, DataWord.ZERO()));
    }
  }

  private static void assertViewBudgetFailure(Runnable access) {
    QueryContext context = new QueryContext(ArchiveQueryLimits.builder()
        .maxVmOverlayBytes(212L).build());
    try (QueryContextHolder.Scope query = QueryContextHolder.attach(context)) {
      HistoricalQueryLimitException failure = assertThrows(HistoricalQueryLimitException.class,
          access::run);
      assertEquals(HistoricalQueryLimitException.Limit.VM_OVERLAY_BYTES, failure.getLimit());
      assertEquals(213L, failure.getObserved());
    }
  }

  private static void siblingCommit(Repository root) {
    root.getStorageValue(PARENT, DataWord.ZERO());
    Repository first = root.newRepositoryChild();
    first.getStorageValue(PARENT, DataWord.ZERO());
    Repository second = root.newRepositoryChild();
    second.putStorageValue(PARENT, DataWord.ONE(), new DataWord(2L));
    second.commit();
    first.putStorageValue(PARENT, DataWord.ZERO(), new DataWord(CHILD_VALUE));
    first.commit();
  }

  private static void assertDelegateRevertConforms(boolean energyLimitFork, long expected)
      throws Exception {
    assertVmScenario(energyLimitFork, parentCode(), CHILD_CODE, new byte[] {0}, expected, true);
  }

  private static void assertVmScenario(boolean energyLimitFork, byte[] parentCode, byte[] childCode,
      byte[] otherCode, long expected, boolean... rejected) throws Exception {
    Fixture fixture = new Fixture();
    fixture.seedContract(CHILD, childCode);
    fixture.seedContract(OTHER, otherCode);
    try (VMConfig.LocalSnapshotScope ignored = VMConfig.preserveLocalSnapshot();
        MockedStatic<StorageUtils> storageFlags = mockStatic(StorageUtils.class)) {
      installFork(energyLimitFork);

      // The canonical repository still reads this legacy getter. Mockito scopes it to this thread.
      storageFlags.when(StorageUtils::getEnergyLimitHardFork).thenReturn(energyLimitFork);
      Repository canonical = RepositoryImpl.createRoot(fixture.storeFactory);
      ProgramResult canonicalResult = execute(canonical, parentCode);
      assertChildren(canonicalResult, rejected);
      assertArrayEquals("canonical fork control", word(expected), canonicalResult.getHReturn());
      assertEquals(new DataWord(expected), canonical.getStorageValue(PARENT, DataWord.ZERO()));

      // A live node may be on the other side of the fork; archive must use its local snapshot.
      storageFlags.when(StorageUtils::getEnergyLimitHardFork).thenReturn(!energyLimitFork);
      Repository archive = fixture.newArchiveRepository();
      ProgramResult archiveResult = execute(archive, parentCode);
      assertChildren(archiveResult, rejected);

      assertArrayEquals(word(INITIAL_VALUE), fixture.reader.getStorage(PARENT, word(0)).getValue());
      assertArrayEquals(word(INITIAL_VALUE), fixture.storageStore.get(fixture.rowKey).getValue());
      assertEquals(new DataWord(INITIAL_VALUE),
          fixture.newArchiveRepository().getStorageValue(PARENT, DataWord.ZERO()));
      assertEquals(new DataWord(INITIAL_VALUE),
          RepositoryImpl.createRoot(fixture.storeFactory).getStorageValue(PARENT, DataWord.ZERO()));

      assertArrayEquals("archive must preserve canonical fork-specific return data",
          canonicalResult.getHReturn(), archiveResult.getHReturn());
      assertEquals("archive request overlay must match canonical storage",
          canonical.getStorageValue(PARENT, DataWord.ZERO()),
          archive.getStorageValue(PARENT, DataWord.ZERO()));
      assertEquals(canonicalResult.getEnergyUsed(), archiveResult.getEnergyUsed());
    }
  }

  private static void installFork(boolean energyLimitFork) {
    VMConfig.Snapshot snapshot = new VMConfig.Snapshot();
    snapshot.energyLimitHardFork = energyLimitFork;
    VMConfig.setLocalSnapshot(snapshot);
  }

  private static ProgramResult execute(Repository repository, byte[] code) throws Exception {
    QueryContext context = new QueryContext(ArchiveQueryLimits.builder()
        .maxVmSteps(1_000).maxVmOverlayBytes(OVERLAY_LIMIT).build());
    try (QueryContextHolder.Scope ignored = QueryContextHolder.attach(context)) {
      ProgramInvokeImpl invoke = new ProgramInvokeImpl(PARENT, PARENT, PARENT,
          repository.getBalance(PARENT), 0L, 0L, 0L, new byte[0], new byte[32], PARENT,
          1_500_000_000L, 100L, repository, 0L, Long.MAX_VALUE, 1_000_000L);
      invoke.setConstantCall();
      Program program = new Program(code, PARENT, invoke, new InternalTransaction(
          Protocol.Transaction.getDefaultInstance(), InternalTransaction.TrxType.TRX_UNKNOWN_TYPE));
      program.setRootTransactionId(new byte[32]);
      VM.play(program, OperationRegistry.getTable());
      ProgramResult result = program.getResult();
      assertNull(result.getException());
      assertFalse(result.isRevert());
      assertTrue("the real VM must execute the fixture", context.getVmSteps() > 0);
      if (repository.isHistoricalArchive()) {
        assertTrue("reverted writes must still be budgeted", context.getVmOverlayBytes() > 0);
        assertTrue(context.getVmOverlayBytes() <= OVERLAY_LIMIT);
      }
      return result;
    }
  }

  private static void assertChildren(ProgramResult result, boolean... rejected) {
    assertEquals("DELEGATECALL must reach each child", rejected.length,
        result.getInternalTransactions().size());
    for (int i = 0; i < rejected.length; i++) {
      InternalTransaction child = result.getInternalTransactions().get(i);
      assertEquals("child " + i + " rejection", rejected[i], child.isRejected());
      assertArrayEquals(PARENT, child.getTransferToAddress());
    }
  }

  private static byte[] parentCode() {
    // Warm slot zero in the parent, DELEGATECALL SSTORE/REVERT, then return that same slot.
    return Hex.decode("60005450" + delegate(CHILD) + returnSlot());
  }

  private static String delegate(byte[] address) {
    return "600060006000600073"
        + Hex.toHexString(Arrays.copyOfRange(address, 1, address.length)) + "620186a0f450";
  }

  private static String returnSlot() {
    return "60005460005260206000f3";
  }

  private static byte[] word(long value) {
    return new DataWord(value).getData();
  }

  private static final class Fixture {

    private final ArchiveStateReader reader = mock(ArchiveStateReader.class, invocation ->
        invocation.getMethod().getReturnType() == ArchiveReadResult.class
            ? ArchiveReadResult.missing() : Answers.RETURNS_DEFAULTS.answer(invocation));
    private final StoreFactory storeFactory = mock(StoreFactory.class);
    private final AccountStore accountStore = mock(AccountStore.class);
    private final ContractStore contractStore = mock(ContractStore.class);
    private final CodeStore codeStore = mock(CodeStore.class);
    private final StorageRowStore storageStore = mock(StorageRowStore.class);
    private final byte[] rowKey = ArchiveStorageKeyCodec.contractStorageKey(PARENT, word(0), 0);

    private Fixture() throws Exception {
      ChainBaseManager manager = mock(ChainBaseManager.class);
      when(storeFactory.getChainBaseManager()).thenReturn(manager);
      when(manager.getAccountStore()).thenReturn(accountStore);
      when(manager.getContractStore()).thenReturn(contractStore);
      when(manager.getCodeStore()).thenReturn(codeStore);
      when(manager.getStorageRowStore()).thenReturn(storageStore);
      seedContract(PARENT, parentCode());
      seedContract(CHILD, CHILD_CODE);
      when(storageStore.get(rowKey)).thenAnswer(invocation ->
          new StorageRowCapsule(rowKey.clone(), word(INITIAL_VALUE)));
      when(reader.getStorage(PARENT, word(0))).thenAnswer(invocation ->
          ArchiveReadResult.present(word(INITIAL_VALUE)));
    }

    private void seedContract(byte[] address, byte[] code) throws Exception {
      Protocol.Account account = Protocol.Account.newBuilder()
          .setAddress(ByteString.copyFrom(address)).setType(Protocol.AccountType.Contract)
          .setBalance(1_000_000L).build();
      SmartContract contract = SmartContract.newBuilder()
          .setContractAddress(ByteString.copyFrom(address)).build();
      when(accountStore.get(address)).thenAnswer(invocation -> new AccountCapsule(account));
      when(contractStore.get(address)).thenAnswer(invocation -> new ContractCapsule(contract));
      when(codeStore.get(address)).thenAnswer(invocation -> new CodeCapsule(code.clone()));
      when(reader.getAccount(address)).thenAnswer(invocation ->
          ArchiveReadResult.present(new AccountCapsule(account)));
      when(reader.getContract(address)).thenAnswer(invocation ->
          ArchiveReadResult.present(new ContractCapsule(contract)));
      when(reader.getCode(address)).thenAnswer(invocation ->
          ArchiveReadResult.present(code.clone()));
    }

    private Repository newArchiveRepository() {
      return new ArchiveRepositoryAdapter(reader, mock(VmDynamicProperties.class));
    }
  }
}
