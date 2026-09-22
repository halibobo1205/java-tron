package org.tron.core.vm.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import java.util.Arrays;
import org.bouncycastle.util.encoders.Hex;
import org.junit.Test;
import org.mockito.Answers;
import org.tron.common.BaseMethodTest;
import org.tron.common.runtime.ProgramResult;
import org.tron.common.runtime.TvmTestUtils;
import org.tron.common.runtime.vm.DataWord;
import org.tron.common.utils.Sha256Hash;
import org.tron.common.utils.WalletUtil;
import org.tron.core.actuator.VMActuator;
import org.tron.core.archive.query.ArchiveQueryLimits;
import org.tron.core.archive.query.QueryContext;
import org.tron.core.archive.query.QueryContextHolder;
import org.tron.core.archive.reader.ArchiveReadResult;
import org.tron.core.archive.reader.ArchiveStateReader;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.CodeCapsule;
import org.tron.core.capsule.ContractCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.db.TransactionContext;
import org.tron.core.store.StoreFactory;
import org.tron.core.store.VmDynamicProperties;
import org.tron.core.vm.config.ConfigLoader;
import org.tron.core.vm.config.VMConfig;
import org.tron.core.vm.program.VmResultCodeMapper;
import org.tron.core.vm.repository.Repository;
import org.tron.core.vm.repository.RepositoryImpl;
import org.tron.protos.Protocol;
import org.tron.protos.Protocol.Transaction;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;
import org.tron.protos.Protocol.Transaction.Result.contractResult;
import org.tron.protos.contract.SmartContractOuterClass.SmartContract;

public class HistoricalEmptyCodeTransactionTest extends BaseMethodTest {

  private static final byte[] OWNER = Hex.decode("410000000000000000000000000000000000000051");
  private static final byte[] CONTRACT = Hex.decode("410000000000000000000000000000000000000052");
  private static final byte[] CHILD = Hex.decode("410000000000000000000000000000000000000053");
  private static final byte[] CREATE_SUICIDE = Hex.decode("60026010600039600260006000f0500033ff");
  private static final long FEE_LIMIT = 100_000_000L;

  @Test
  public void topLevelEmptyRuntimePreservesCommitFailureAndNeverPersistsReplay() throws Exception {
    for (boolean multiSign : new boolean[] {false, true}) {
      Fixture fixture = new Fixture(multiSign);
      // RETURN(0, 0), STOP delimiter, empty runtime: also empty in legacy ProgramPrecompile.
      Transaction trx = TvmTestUtils.generateDeploySmartContractAndGetTransaction(
          "empty" + multiSign, OWNER, "[]", "60006000f300", 0L, FEE_LIMIT, 100L,
          null, 1_000_000L);
      byte[] created = WalletUtil.generateContractAddress(trx);
      ProgramResult canonical = fixture.compare(trx, multiSign ? contractResult.SUCCESS
          : contractResult.UNKNOWN, created);
      if (!multiSign) {
        assertEquals("Unknown Throwable", canonical.getRuntimeError());
        assertEquals(FEE_LIMIT / 100L, canonical.getEnergyUsed());
        assertNull(chainBaseManager.getContractStore().get(created));
      }
      // Original root commit writes accounts before checking code, even when code then fails.
      assertNotNull(chainBaseManager.getAccountStore().get(created));
      assertFalse(chainBaseManager.getCodeStore().has(created));
    }
  }

  @Test
  public void internalCreateFailureMatchesTransactionResultAndEnergy() throws Exception {
    for (boolean multiSign : new boolean[] {false, true}) {
      Fixture fixture = new Fixture(multiSign);
      fixture.seedContract(CONTRACT, CREATE_SUICIDE);
      ProgramResult canonical = fixture.compare(trigger(), multiSign ? contractResult.SUCCESS
          : contractResult.UNKNOWN, null);
      if (!multiSign) {
        assertEquals("Unknown Exception", canonical.getRuntimeError());
        assertEquals(FEE_LIMIT / 100L, canonical.getEnergyUsed());
        canonical.getInternalTransactions().forEach(internal -> assertTrue(internal.isRejected()));
        assertNull(chainBaseManager.getAccountStore().get(
            canonical.getInternalTransactions().get(0).getTransferToAddress()));
      }
    }
  }

  @Test
  public void nestedCallCanCatchLegacyCreateFailureInFullTransactionReplay() throws Exception {
    for (boolean multiSign : new boolean[] {false, true}) {
      Fixture fixture = new Fixture(multiSign);
      fixture.seedContract(CHILD, CREATE_SUICIDE);
      fixture.seedContract(CONTRACT, Hex.decode("6000600060006000600073"
          + Hex.toHexString(Arrays.copyOfRange(CHILD, 1, CHILD.length))
          + "6207a120f150600160005260206000f3"));
      ProgramResult canonical = fixture.compare(trigger(), contractResult.SUCCESS, null);
      assertArrayEquals(new DataWord(1L).getData(), canonical.getHReturn());
      assertEquals(!multiSign, canonical.getInternalTransactions().get(0).isRejected());
    }
  }

  private static Transaction trigger() {
    TransactionCapsule capsule = new TransactionCapsule(
        TvmTestUtils.buildTriggerSmartContract(OWNER, CONTRACT, new byte[0], 0L),
        ContractType.TriggerSmartContract);
    return capsule.getInstance().toBuilder().setRawData(capsule.getInstance().getRawData()
        .toBuilder().setFeeLimit(FEE_LIMIT)).build();
  }

  private final class Fixture {

    private final QueryContext query = new QueryContext(ArchiveQueryLimits.unlimited());
    private final ArchiveStateReader reader = mock(ArchiveStateReader.class, invocation ->
        invocation.getMethod().getReturnType() == ArchiveReadResult.class
            ? ArchiveReadResult.missing() : Answers.RETURNS_DEFAULTS.answer(invocation));
    private final VmDynamicProperties properties = mock(VmDynamicProperties.class);

    private Fixture(boolean multiSign) throws Exception {
      when(reader.getQueryContext()).thenReturn(query);
      when(properties.supportVM()).thenReturn(true);
      when(properties.getAllowMultiSign()).thenReturn(multiSign ? 1L : 0L);
      when(properties.getMaxFeeLimit()).thenReturn(1_000_000_000_000L);
      when(properties.getMaxCpuTimeOfOneTx()).thenReturn(50L);
      when(properties.getEnergyFee()).thenReturn(100L);
      when(properties.getMaintenanceTimeInterval()).thenReturn(21_600_000L);
      when(properties.getLatestBlockHeaderTimestamp()).thenReturn(
          chainBaseManager.getDynamicPropertiesStore().getLatestBlockHeaderTimestamp());
      seedAccount(OWNER, Protocol.AccountType.Normal);
    }

    private void seedAccount(byte[] address, Protocol.AccountType type) throws Exception {
      Protocol.Account account = Protocol.Account.newBuilder()
          .setAddress(ByteString.copyFrom(address)).setType(type).setBalance(1_000_000_000L)
          .build();
      chainBaseManager.getAccountStore().put(address, new AccountCapsule(account));
      when(reader.getAccount(address)).thenAnswer(invocation ->
          ArchiveReadResult.present(new AccountCapsule(account)));
    }

    private void seedContract(byte[] address, byte[] code) throws Exception {
      seedAccount(address, Protocol.AccountType.Contract);
      SmartContract contract = SmartContract.newBuilder()
          .setContractAddress(ByteString.copyFrom(address))
          .setOriginAddress(ByteString.copyFrom(OWNER)).setConsumeUserResourcePercent(100)
          .setOriginEnergyLimit(1_000_000L).build();
      chainBaseManager.getContractStore().put(address, new ContractCapsule(contract));
      chainBaseManager.getCodeStore().put(address, new CodeCapsule(code));
      when(reader.getContract(address)).thenAnswer(invocation ->
          ArchiveReadResult.present(new ContractCapsule(contract)));
      when(reader.getCode(address)).thenAnswer(invocation ->
          ArchiveReadResult.present(code.clone()));
    }

    private ProgramResult compare(Transaction trx, contractResult expected, byte[] created)
        throws Exception {
      boolean disabled = ConfigLoader.disable;
      try (VMConfig.LocalSnapshotScope ignored = VMConfig.preserveLocalSnapshot()) {
        ConfigLoader.disable = false;
        BlockCapsule block = new BlockCapsule(1L, Sha256Hash.ZERO_HASH,
            properties.getLatestBlockHeaderTimestamp(), ByteString.copyFrom(new byte[21]));
        HistoricalDebugTraceResult historical = new HistoricalDebugTraceExecutor().execute(
            reader, properties, block, new TransactionCapsule(trx), true, false, null, null, null);
        assertNull(query.getRecordedVmTerminalFailure());
        assertNull(QueryContextHolder.current());
        if (created != null) {
          assertNull(chainBaseManager.getAccountStore().get(created));
          assertNull(chainBaseManager.getContractStore().get(created));
          assertFalse(chainBaseManager.getCodeStore().has(created));
        }
        Repository canonical = spy(RepositoryImpl.createRoot(StoreFactory.getInstance()));
        when(canonical.getVmDynamicProperties()).thenReturn(properties);
        TransactionContext context = new TransactionContext(block, new TransactionCapsule(trx),
            StoreFactory.getInstance(), false, false);
        VMActuator actuator = new VMActuator(false);
        actuator.setInjectedRootRepository(canonical);
        actuator.setUseQueryDeadlineForVm(true);
        try (QueryContextHolder.Scope scope = QueryContextHolder.attach(
            new QueryContext(ArchiveQueryLimits.unlimited()))) {
          actuator.validate(context);
          actuator.execute(context);
        }
        ProgramResult result = context.getProgramResult();
        assertEquals(expected, VmResultCodeMapper.resultCodeOf(result));
        assertEquals(expected, historical.getResultCode());
        assertEquals(expected != contractResult.SUCCESS, historical.isFailed());
        assertEquals(result.isRevert(), historical.isReverted());
        assertArrayEquals(result.getHReturn(), historical.getOutput());
        assertEquals(result.getEnergyUsed(), historical.getEnergyUsed());
        return result;
      } finally {
        ConfigLoader.disable = disabled;
      }
    }
  }
}
