package org.tron.core.vm.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
import org.tron.core.ChainBaseManager;
import org.tron.core.archive.query.ArchiveQueryLimits;
import org.tron.core.archive.query.HistoricalQueryLimitException;
import org.tron.core.archive.query.QueryContext;
import org.tron.core.archive.query.QueryContextHolder;
import org.tron.core.archive.reader.ArchiveReadResult;
import org.tron.core.archive.reader.ArchiveStateReader;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.CodeCapsule;
import org.tron.core.capsule.ContractCapsule;
import org.tron.core.capsule.utils.AssetUtil;
import org.tron.core.store.AccountStore;
import org.tron.core.store.CodeStore;
import org.tron.core.store.ContractStore;
import org.tron.core.store.DynamicPropertiesStore;
import org.tron.core.store.StoreFactory;
import org.tron.core.vm.OperationRegistry;
import org.tron.core.vm.VM;
import org.tron.core.vm.config.VMConfig;
import org.tron.core.vm.program.Program;
import org.tron.core.vm.program.invoke.ProgramInvokeImpl;
import org.tron.core.vm.repository.Repository;
import org.tron.core.vm.repository.RepositoryImpl;
import org.tron.protos.Protocol;
import org.tron.protos.contract.SmartContractOuterClass.SmartContract;

public class ArchiveEmptyCodeConformanceTest {

  private static final byte[] PARENT = Hex.decode("410000000000000000000000000000000000000042");
  private static final byte[] CHILD = Hex.decode("410000000000000000000000000000000000000043");
  // Copy CALLER/SUICIDE into memory, CREATE with it, discard the address, STOP.
  private static final byte[] CREATE_SUICIDE = Hex.decode("60026010600039600260006000f0500033ff");

  @Test
  public void savedEmptyCodeFailsAtCommitOnlyBeforeProposal() throws Exception {
    try (VMConfig.LocalSnapshotScope ignored = VMConfig.preserveLocalSnapshot()) {
      for (boolean multiSign : new boolean[] {false, true}) {
        installFlags(multiSign);
        for (boolean archive : new boolean[] {false, true}) {
          for (boolean rootCommit : new boolean[] {false, true}) {
            for (byte[] code : new byte[][] {null, new byte[0]}) {
              Repository root = new Fixture().repository(archive);
              Repository target = rootCommit ? root : root.newRepositoryChild();
              target.saveCode(CHILD, code);
              assertNull(target.getCode(CHILD));
              if (multiSign) {
                target.commit();
              } else {
                assertThrows(NullPointerException.class, target::commit);
              }
              if (!rootCommit) {
                assertNull(root.getCode(CHILD));
              }
            }
          }
        }
      }
    }
  }

  @Test
  public void legacyCommitMergesAccountsBeforeFailingButNotContracts() throws Exception {
    try (VMConfig.LocalSnapshotScope ignored = VMConfig.preserveLocalSnapshot()) {
      installFlags(false);
      for (boolean archive : new boolean[] {false, true}) {
        Repository root = new Fixture().repository(archive);
        Repository child = root.newRepositoryChild();
        child.createAccount(CHILD, Protocol.AccountType.Contract);
        child.createContract(CHILD, contract(CHILD));
        child.saveCode(CHILD, new byte[0]);
        assertThrows(NullPointerException.class, child::commit);
        assertNotNull(root.getAccount(CHILD));
        assertNull(root.getContract(CHILD));
        assertNull(root.getCode(CHILD));
      }
    }
  }

  @Test
  public void emptyReadCachesLegacyValueButMissingReadDoesNot() throws Exception {
    try (VMConfig.LocalSnapshotScope ignored = VMConfig.preserveLocalSnapshot()) {
      for (boolean multiSign : new boolean[] {false, true}) {
        installFlags(multiSign);
        Fixture fixture = new Fixture();
        fixture.seed(CHILD, new byte[0]);
        for (boolean archive : new boolean[] {false, true}) {
          Repository repository = fixture.repository(archive);
          assertArrayEquals(new byte[0], repository.getCode(CHILD));
          assertNull(repository.getCode(CHILD));
          if (multiSign) {
            repository.commit();
          } else {
            assertThrows(NullPointerException.class, repository::commit);
          }
          Repository missing = new Fixture().repository(archive);
          assertNull(missing.getCode(CHILD));
          missing.commit();
        }
      }
    }
  }

  @Test
  public void proposalIsCapturedWhenValueIsCreatedNotWhenCommitted() throws Exception {
    try (VMConfig.LocalSnapshotScope ignored = VMConfig.preserveLocalSnapshot()) {
      for (boolean archive : new boolean[] {false, true}) {
        for (boolean originallyEnabled : new boolean[] {false, true}) {
          Repository repository = new Fixture().repository(archive);
          installFlags(originallyEnabled);
          repository.saveCode(CHILD, new byte[0]);
          installFlags(!originallyEnabled);
          if (originallyEnabled) {
            repository.commit();
          } else {
            assertThrows(NullPointerException.class, repository::commit);
          }
        }
      }
    }
  }

  @Test
  public void normalReadAndUnknownEmptyCodeDoNotOverwriteParent() throws Exception {
    try (VMConfig.LocalSnapshotScope ignored = VMConfig.preserveLocalSnapshot()) {
      installFlags(true);
      Fixture fixture = new Fixture();
      fixture.seed(CHILD, new byte[] {1});
      for (boolean archive : new boolean[] {false, true}) {
        Repository root = fixture.repository(archive);
        Repository child = root.newRepositoryChild();
        assertArrayEquals(new byte[] {1}, child.getCode(CHILD));
        root.saveCode(CHILD, new byte[] {2});
        child.commit();
        assertArrayEquals(new byte[] {2}, root.getCode(CHILD));
        child.saveCode(CHILD, new byte[0]);
        child.commit();
        assertArrayEquals(new byte[] {2}, root.getCode(CHILD));
        child.saveCode(CHILD, new byte[] {3});
        child.commit();
        assertArrayEquals(new byte[] {3}, root.getCode(CHILD));
      }
    }
  }

  @Test
  public void nonEmptyOverwriteRemovesLegacyFailure() throws Exception {
    try (VMConfig.LocalSnapshotScope ignored = VMConfig.preserveLocalSnapshot()) {
      installFlags(false);
      for (boolean archive : new boolean[] {false, true}) {
        Repository root = new Fixture().repository(archive);
        Repository child = root.newRepositoryChild();
        child.saveCode(CHILD, new byte[0]);
        child.saveCode(CHILD, new byte[] {0});
        child.commit();
        assertArrayEquals(new byte[] {0}, root.getCode(CHILD));
        root.commit();
      }
    }
  }

  @Test
  public void deletionTombstonesNeverBecomeLegacyEmptyCode() throws Exception {
    try (VMConfig.LocalSnapshotScope ignored = VMConfig.preserveLocalSnapshot()) {
      for (boolean multiSign : new boolean[] {false, true}) {
        installFlags(multiSign);
        Repository root = new Fixture().repository(true);
        Repository child = root.newRepositoryChild();
        child.saveCode(CHILD, new byte[0]);
        child.deleteContract(CHILD);
        child.commit();
        root.commit();
        assertNull(root.getCode(CHILD));
        assertNull(root.getContract(CHILD));
      }
    }
  }

  @Test
  public void codeReadAndMergeBudgetFailuresDoNotInstallPartialEntries() throws Exception {
    Fixture fixture = new Fixture();
    fixture.seed(CHILD, new byte[] {1});
    Repository root = fixture.repository(true);
    assertBudgetFailure(() -> root.getCode(CHILD));
    fixture.seed(CHILD, new byte[] {2});
    assertArrayEquals(new byte[] {2}, root.getCode(CHILD));
    Repository child = root.newRepositoryChild();
    child.saveCode(CHILD, new byte[] {3});
    assertBudgetFailure(child::commit);
    assertArrayEquals(new byte[] {2}, root.getCode(CHILD));
    child.commit();
    assertArrayEquals(new byte[] {3}, root.getCode(CHILD));
  }

  @Test
  public void realVmCreateSuicideMatchesCanonicalBeforeAndAfterProposal() throws Exception {
    for (boolean multiSign : new boolean[] {false, true}) {
      assertVmConforms(multiSign, false);
    }
  }

  @Test
  public void nestedCallMayCatchLegacyCreateFailureWithoutPoisoningQuery() throws Exception {
    for (boolean multiSign : new boolean[] {false, true}) {
      assertVmConforms(multiSign, true);
    }
  }

  private static void assertVmConforms(boolean multiSign, boolean nested) throws Exception {
    try (VMConfig.LocalSnapshotScope ignored = VMConfig.preserveLocalSnapshot();
        MockedStatic<AssetUtil> assets = mockStatic(AssetUtil.class, Answers.CALLS_REAL_METHODS)) {
      assets.when(AssetUtil::isAllowAssetOptimization).thenReturn(false);
      installFlags(multiSign);
      Fixture fixture = new Fixture();
      fixture.seed(CHILD, CREATE_SUICIDE);
      byte[] code = nested ? Hex.decode("6000600060006000600073"
          + Hex.toHexString(Arrays.copyOfRange(CHILD, 1, CHILD.length))
          + "620f4240f150600160005260206000f3") : CREATE_SUICIDE;
      ProgramResult canonical = execute(fixture.repository(false), code);
      ProgramResult archive = execute(fixture.repository(true), code);
      if (!multiSign && !nested) {
        assertNotNull(canonical.getException());
        assertEquals("Unknown Exception", canonical.getException().getMessage());
        assertNotNull(archive.getException());
        assertEquals(canonical.getException().getClass(), archive.getException().getClass());
        assertEquals(canonical.getException().getMessage(), archive.getException().getMessage());
      } else {
        assertNull(canonical.getException());
        assertNull(archive.getException());
      }
      assertFalse(canonical.isRevert());
      assertEquals(canonical.isRevert(), archive.isRevert());
      assertArrayEquals(canonical.getHReturn(), archive.getHReturn());
      assertEquals(canonical.getEnergyUsed(), archive.getEnergyUsed());
      assertEquals(nested ? 3 : 2, canonical.getInternalTransactions().size());
      assertEquals(canonical.getInternalTransactions().size(),
          archive.getInternalTransactions().size());
      for (int i = 0; i < canonical.getInternalTransactions().size(); i++) {
        assertEquals(canonical.getInternalTransactions().get(i).isRejected(),
            archive.getInternalTransactions().get(i).isRejected());
      }
      if (nested) {
        assertArrayEquals(new DataWord(1L).getData(), archive.getHReturn());
        assertEquals(!multiSign, archive.getInternalTransactions().get(0).isRejected());
      }
      assertNull(fixture.repository(true).getCode(
          archive.getInternalTransactions().get(nested ? 1 : 0).getTransferToAddress()));
    }
  }

  private static ProgramResult execute(Repository repository, byte[] code) throws Exception {
    QueryContext context = new QueryContext(ArchiveQueryLimits.builder()
        .maxVmSteps(1_000).maxVmOverlayBytes(1_048_576L).build());
    try (QueryContextHolder.Scope ignored = QueryContextHolder.attach(context)) {
      ProgramInvokeImpl invoke = new ProgramInvokeImpl(PARENT, PARENT, PARENT,
          repository.getBalance(PARENT), 0L, 0L, 0L, new byte[0], new byte[32], PARENT,
          1_500_000_000L, 100L, repository, 0L, Long.MAX_VALUE, 2_000_000L);
      invoke.setConstantCall();
      Program program = new Program(code, PARENT, invoke, new InternalTransaction(
          Protocol.Transaction.getDefaultInstance(), InternalTransaction.TrxType.TRX_UNKNOWN_TYPE));
      program.setRootTransactionId(new byte[32]);
      VM.play(program, OperationRegistry.getTable());
      assertTrue(context.getVmSteps() > 0);
      assertNull("ordinary VM failures must remain catchable by CALL",
          context.getRecordedVmTerminalFailure());
      return program.getResult();
    }
  }

  private static void assertBudgetFailure(Runnable operation) {
    QueryContext context = new QueryContext(ArchiveQueryLimits.builder()
        .maxVmOverlayBytes(213L).build());
    try (QueryContextHolder.Scope ignored = QueryContextHolder.attach(context)) {
      assertThrows(HistoricalQueryLimitException.class, operation::run);
    }
  }

  private static void installFlags(boolean multiSign) {
    VMConfig.Snapshot snapshot = new VMConfig.Snapshot();
    snapshot.allowMultiSign = multiSign;
    VMConfig.setLocalSnapshot(snapshot);
  }

  private static ContractCapsule contract(byte[] address) {
    return new ContractCapsule(SmartContract.newBuilder()
        .setContractAddress(ByteString.copyFrom(address)).build());
  }

  private static final class Fixture {

    private final ArchiveStateReader reader = mock(ArchiveStateReader.class, invocation ->
        invocation.getMethod().getReturnType() == ArchiveReadResult.class
            ? ArchiveReadResult.missing() : Answers.RETURNS_DEFAULTS.answer(invocation));
    private final StoreFactory storeFactory = mock(StoreFactory.class);
    private final AccountStore accountStore = mock(AccountStore.class);
    private final ContractStore contractStore = mock(ContractStore.class);
    private final CodeStore codeStore = mock(CodeStore.class);
    private final DynamicPropertiesStore properties = mock(DynamicPropertiesStore.class);

    private Fixture() throws Exception {
      ChainBaseManager manager = mock(ChainBaseManager.class);
      when(storeFactory.getChainBaseManager()).thenReturn(manager);
      when(manager.getAccountStore()).thenReturn(accountStore);
      when(manager.getContractStore()).thenReturn(contractStore);
      when(manager.getCodeStore()).thenReturn(codeStore);
      when(manager.getDynamicPropertiesStore()).thenReturn(properties);
      seed(PARENT, CREATE_SUICIDE);
    }

    private void seed(byte[] address, byte[] code) throws Exception {
      Protocol.Account account = Protocol.Account.newBuilder()
          .setAddress(ByteString.copyFrom(address)).setType(Protocol.AccountType.Contract)
          .setBalance(1_000_000_000L).build();
      when(accountStore.get(address)).thenAnswer(invocation -> new AccountCapsule(account));
      when(contractStore.get(address)).thenAnswer(invocation -> contract(address));
      when(codeStore.get(address)).thenAnswer(invocation -> new CodeCapsule(code.clone()));
      when(reader.getAccount(address)).thenAnswer(invocation ->
          ArchiveReadResult.present(new AccountCapsule(account)));
      when(reader.getContract(address)).thenAnswer(invocation ->
          ArchiveReadResult.present(contract(address)));
      when(reader.getCode(address)).thenAnswer(invocation ->
          ArchiveReadResult.present(code.clone()));
    }

    private Repository repository(boolean archive) {
      return archive ? new ArchiveRepositoryAdapter(reader, properties)
          : RepositoryImpl.createRoot(storeFactory);
    }
  }
}
