package org.tron.core.vm.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.tron.common.BaseMethodTest;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.runtime.TvmTestUtils;
import org.tron.common.runtime.vm.DataWord;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.actuator.VMActuator;
import org.tron.core.archive.query.ArchiveQueryLimits;
import org.tron.core.archive.query.HistoricalQueryLimitException;
import org.tron.core.archive.query.QueryContext;
import org.tron.core.archive.query.QueryContextHolder;
import org.tron.core.archive.reader.ArchiveReadResult;
import org.tron.core.archive.reader.ArchiveStatePoint;
import org.tron.core.archive.reader.ArchiveStateReader;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.ContractCapsule;
import org.tron.core.capsule.ContractStateCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.db.TransactionContext;
import org.tron.core.store.VmDynamicProperties;
import org.tron.core.vm.config.VMConfig;
import org.tron.protos.Protocol.Account;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;
import org.tron.protos.contract.SmartContractOuterClass.SmartContract;
import org.tron.protos.contract.SmartContractOuterClass.TriggerSmartContract;

/**
 * End-to-end: a historical constant call replays a contract against archived state. The contract's
 * runtime simply returns storage slot 0, so the call proves SLOAD reads the archived value (not the
 * latest store) through the executor -> VMActuator injected seam -> ArchiveRepositoryAdapter.
 */
public class HistoricalConstantCallExecutorTest extends BaseMethodTest {

  // PUSH1 0; SLOAD; PUSH1 0; MSTORE; PUSH1 0x20; PUSH1 0; RETURN -> returns 32-byte storage slot 0.
  private static final byte[] SLOAD_CODE = {
      0x60, 0x00, 0x54, 0x60, 0x00, 0x52, 0x60, 0x20, 0x60, 0x00, (byte) 0xf3
  };

  // BASEFEE; PUSH1 0; MSTORE; PUSH1 0x20; PUSH1 0; RETURN -> returns the historical energy fee.
  private static final byte[] BASEFEE_CODE = {
      (byte) 0x48, 0x60, 0x00, 0x52, 0x60, 0x20, 0x60, 0x00, (byte) 0xf3
  };

  // CHAINID; PUSH1 0; MSTORE; PUSH1 0x20; PUSH1 0; RETURN -> reads genesis block 0 for chain id.
  private static final byte[] CHAINID_CODE = {
      0x46, 0x60, 0x00, 0x52, 0x60, 0x20, 0x60, 0x00, (byte) 0xf3
  };

  // CALL child(0x33), discard its status, then return 1. A child failure is therefore hidden from
  // the root ProgramResult unless the archive adapter records it in the QueryContext.
  private static final byte[] PARENT_CALLS_CHILD_CODE =
      org.bouncycastle.util.encoders.Hex.decode(
          "60006000600060006000730000000000000000000000000000000000000033"
              + "620f4240f150600160005260206000f3");

  // CALL RewardBalance (0x01000005), discard its status, then STOP. RewardBalance reads the
  // delegation domain, which the historical archive deliberately rejects.
  private static final byte[] CHILD_CALLS_REWARD_BALANCE_CODE =
      org.bouncycastle.util.encoders.Hex.decode(
          "600060006000600060006301000005620f4240f15000");

  @Override
  protected void afterInit() {
  }

  @Before
  public void generousConstantCallTimeout() {
    // Headroom for the constant-call CPU deadline so a tiny timeout another test left in the shared
    // CommonParameter singleton cannot fail this replay (a positive timeout bypasses the ratio).
    CommonParameter.getInstance().setConstantCallTimeoutMs(60_000);
  }

  @Test
  public void historicalViewCallReturnsArchivedStorageSlot() throws Exception {
    // The contract exists only in the archive (not the latest test stores), so a non-empty return
    // proves the call read code + storage from the archive, at the archived value.
    byte[] storedWord = new byte[32];
    storedWord[31] = 0x2a;
    assertArrayEquals(storedWord, runViewCall(SLOAD_CODE, ArchiveReadResult.present(storedWord)));
  }

  @Test
  public void historicalViewCallReadsZeroForMissingArchivedSlot() throws Exception {
    // A slot absent from the archive resolves to zero in execution (three-state MISSING -> 0 word).
    assertArrayEquals(new byte[32], runViewCall(SLOAD_CODE, ArchiveReadResult.missing()));
  }

  @Test
  public void historicalBaseFeeReturnsHistoricalEnergyFee() throws Exception {
    // BASEFEE reads getEnergyFee() through the archive adapter's getVmDynamicProperties() (not the
    // throwing getDynamicPropertiesStore()); the dynamic-energy factor (allowDynamicEnergy on) must
    // also degrade to neutral instead of crashing on the unarchived contract-state.
    byte[] expectedFee = new byte[32];
    expectedFee[31] = 100;
    assertArrayEquals(expectedFee, runViewCall(BASEFEE_CODE, ArchiveReadResult.missing()));
  }

  @Test
  public void historicalCallRestoresOuterVmConfigView() throws Exception {
    boolean globalOsaka = VMConfig.allowTvmOsaka();
    VMConfig.Snapshot outer = new VMConfig.Snapshot();
    outer.allowTvmOsaka = !globalOsaka;
    VMConfig.setLocalSnapshot(outer);
    try {
      runViewCall(SLOAD_CODE, ArchiveReadResult.missing());
      assertEquals(!globalOsaka, VMConfig.allowTvmOsaka());
    } finally {
      VMConfig.clearLocalSnapshot();
    }
  }

  @Test
  public void historicalChainIdReturnsGenesisDerivedValue() throws Exception {
    // CHAINID reads block 0 from the same archive snapshot as state. It must not load a live block
    // body or admit query-controlled bytes into canonical execution's block-store cache.
    QueryContext queryContext = new QueryContext(ArchiveQueryLimits.unlimited());
    byte[] result = runViewCall(CHAINID_CODE, ArchiveReadResult.missing(), 0L, queryContext);
    byte[] expected = chainBaseManager.getBlockIdByNum(0).getBytes();
    if (VMConfig.allowTvmCompatibleEvm() || VMConfig.allowOptimizedReturnValueOfChainId()) {
      expected = Arrays.copyOfRange(expected, expected.length - 4, expected.length);
    }
    assertArrayEquals(new DataWord(expected).getData(), result);
    assertEquals(2L, queryContext.getBackendReads());
  }

  @Test
  public void push0OpcodeGatedOnReconstructedShanghaiFlag() throws Exception {
    // PUSH0; PUSH0; RETURN -> RETURN(0,0). With Shanghai ON, PUSH0 is valid and the call returns
    // empty; with Shanghai OFF, PUSH0 is an invalid opcode and the call fails. This is the exact
    // opcode-gating the historical flag reconstruction exists to get right for pre-Shanghai blocks.
    byte[] push0 = {0x5f, 0x5f, (byte) 0xf3};
    assertArrayEquals(new byte[0], runViewCall(push0, ArchiveReadResult.missing(), 1L));
    assertThrows(HistoricalVmExecutionException.class,
        () -> runViewCall(push0, ArchiveReadResult.missing(), 0L));
  }

  @Test
  public void vmCatchAllCannotHideHistoricalStepLimit() {
    QueryContext queryContext = new QueryContext(ArchiveQueryLimits.builder()
        .maxVmSteps(0)
        .build());

    HistoricalQueryLimitException failure = assertThrows(
        HistoricalQueryLimitException.class,
        () -> runViewCall(SLOAD_CODE, ArchiveReadResult.missing(), 0L, queryContext));

    assertEquals(HistoricalQueryLimitException.Limit.VM_STEPS, failure.getLimit());
    assertSame(failure, queryContext.getTerminalException());
    assertEquals(1L, queryContext.getVmSteps());
    assertNull(QueryContextHolder.current());
  }

  @Test
  public void failedHistoricalCallCannotPoisonReusedExecutionThread() throws Exception {
    boolean globalShanghai = VMConfig.allowTvmShanghai();
    long historicalShanghai = globalShanghai ? 0L : 1L;
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      QueryContext failedContext = new QueryContext(ArchiveQueryLimits.builder()
          .maxVmSteps(0)
          .build());
      Future<?> failedHistorical = executor.submit(() -> assertThrows(
          HistoricalQueryLimitException.class,
          () -> runViewCall(
              SLOAD_CODE, ArchiveReadResult.missing(), historicalShanghai, failedContext)));
      failedHistorical.get(5L, TimeUnit.SECONDS);

      Future<?> canonicalView = executor.submit(() -> {
        assertNull(QueryContextHolder.current());
        assertEquals(globalShanghai, VMConfig.allowTvmShanghai());
      });
      canonicalView.get(5L, TimeUnit.SECONDS);

      Future<byte[]> nextHistorical = executor.submit(() -> runViewCall(
          SLOAD_CODE, ArchiveReadResult.missing(), historicalShanghai));
      assertArrayEquals(new byte[32], nextHistorical.get(5L, TimeUnit.SECONDS));
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5L, TimeUnit.SECONDS));
    }
  }

  @Test
  public void hardErrorWinsOverPreviouslyRecordedQueryLimit() throws Exception {
    QueryContext queryContext = new QueryContext(ArchiveQueryLimits.builder()
        .maxVmSteps(0)
        .build());
    VMActuator vmActuator = mock(VMActuator.class);
    AssertionError hardFailure = new AssertionError("hard VM failure");
    doAnswer(invocation -> {
      assertThrows(HistoricalQueryLimitException.class, queryContext::recordVmStep);
      throw hardFailure;
    }).when(vmActuator).execute(any(TransactionContext.class));
    HistoricalConstantCallExecutor executor =
        new HistoricalConstantCallExecutor(() -> vmActuator);

    AssertionError failure = assertThrows(AssertionError.class,
        () -> runViewCall(SLOAD_CODE, ArchiveReadResult.missing(), 0L,
            queryContext, null, executor));

    assertSame(hardFailure, failure);
    assertEquals(1, failure.getSuppressed().length);
    assertSame(queryContext.getRecordedExecutionTerminalFailure(), failure.getSuppressed()[0]);
    assertNull(QueryContextHolder.current());
  }

  @Test
  public void realVmSloadCannotHideArchiveReaderError() {
    AssertionError hardFailure = new AssertionError("hard archive reader failure");

    AssertionError failure = assertThrows(AssertionError.class,
        () -> runViewCall(SLOAD_CODE, ArchiveReadResult.missing(), 0L,
            null, null, new HistoricalConstantCallExecutor(), hardFailure));

    assertSame(hardFailure, failure);
    assertNull(QueryContextHolder.current());
  }

  @Test
  public void nestedRewardBalanceFailureCannotBeHiddenByParentSuccess() {
    QueryContext queryContext = new QueryContext(ArchiveQueryLimits.builder().build());

    UnsupportedHistoricalStateException failure = assertThrows(
        UnsupportedHistoricalStateException.class,
        () -> runViewCall(PARENT_CALLS_CHILD_CODE, ArchiveReadResult.missing(), 0L,
            queryContext, CHILD_CALLS_REWARD_BALANCE_CODE));

    assertEquals(UnsupportedHistoricalStateException.class, failure.getClass());
    assertEquals("historical archive call does not support begin-cycle reads",
        failure.getMessage());
    assertSame(failure, queryContext.getRecordedVmTerminalFailure());
    assertNull(QueryContextHolder.current());
  }

  private byte[] runViewCall(byte[] code, ArchiveReadResult<byte[]> archivedSlot) throws Exception {
    return runViewCall(code, archivedSlot, 0L);
  }

  private byte[] runViewCall(byte[] code, ArchiveReadResult<byte[]> slot, long allowShangHai)
      throws Exception {
    return runViewCall(code, slot, allowShangHai, null);
  }

  private byte[] runViewCall(byte[] code, ArchiveReadResult<byte[]> slot, long allowShangHai,
      QueryContext queryContext) throws Exception {
    return runViewCall(code, slot, allowShangHai, queryContext, null);
  }

  private byte[] runViewCall(byte[] code, ArchiveReadResult<byte[]> slot, long allowShangHai,
      QueryContext queryContext, byte[] childCode) throws Exception {
    return runViewCall(code, slot, allowShangHai, queryContext, childCode,
        new HistoricalConstantCallExecutor());
  }

  private byte[] runViewCall(byte[] code, ArchiveReadResult<byte[]> slot, long allowShangHai,
      QueryContext queryContext, byte[] childCode, HistoricalConstantCallExecutor executor)
      throws Exception {
    return runViewCall(code, slot, allowShangHai, queryContext, childCode, executor, null);
  }

  private byte[] runViewCall(byte[] code, ArchiveReadResult<byte[]> slot, long allowShangHai,
      QueryContext queryContext, byte[] childCode, HistoricalConstantCallExecutor executor,
      Error storageFailure) throws Exception {
    byte[] contractAddr = new byte[21];
    contractAddr[0] = 0x41;
    contractAddr[20] = 0x11;
    byte[] childAddr = new byte[21];
    childAddr[0] = 0x41;
    childAddr[20] = 0x33;
    byte[] caller = new byte[21];
    caller[0] = 0x41;
    caller[20] = 0x22;

    FakeReader reader = new FakeReader();
    reader.queryContext = queryContext;
    reader.account = ArchiveReadResult.present(new AccountCapsule(
        Account.newBuilder().setAddress(ByteString.copyFrom(contractAddr)).build()));
    reader.contract = ArchiveReadResult.present(new ContractCapsule(SmartContract.newBuilder()
        .setContractAddress(ByteString.copyFrom(contractAddr)).build()));
    reader.code = ArchiveReadResult.present(code);
    reader.storage = slot;
    reader.storageFailure = storageFailure;
    reader.blockHash = chainBaseManager.getBlockIdByNum(0L).getBytes();
    if (childCode != null) {
      reader.childAddress = childAddr;
      reader.childAccount = ArchiveReadResult.present(new AccountCapsule(
          Account.newBuilder().setAddress(ByteString.copyFrom(childAddr)).build()));
      reader.childContract = ArchiveReadResult.present(new ContractCapsule(
          SmartContract.newBuilder().setContractAddress(ByteString.copyFrom(childAddr)).build()));
      reader.childCode = ArchiveReadResult.present(childCode);
    }

    VmDynamicProperties vmProps = mock(VmDynamicProperties.class);
    when(vmProps.supportVM()).thenReturn(true);
    when(vmProps.getMaxFeeLimit()).thenReturn(1_000_000_000_000L);
    when(vmProps.getMaxCpuTimeOfOneTx()).thenReturn(50L);
    when(vmProps.getEnergyFee()).thenReturn(100L);
    // Dynamic energy active at the historical block (mainnet default) must NOT crash the call;
    // London enables BASEFEE, Istanbul enables CHAINID.
    when(vmProps.getAllowDynamicEnergy()).thenReturn(1L);
    when(vmProps.getAllowTvmLondon()).thenReturn(1L);
    when(vmProps.getAllowTvmIstanbul()).thenReturn(1L);
    when(vmProps.getAllowTvmVote()).thenReturn(1L);
    when(vmProps.getAllowTvmShangHai()).thenReturn(allowShangHai);
    when(vmProps.getMaintenanceTimeInterval()).thenReturn(21_600_000L);

    BlockCapsule block = new BlockCapsule(1L, Sha256Hash.ZERO_HASH, 1000L,
        ByteString.copyFrom(new byte[21]));
    TriggerSmartContract trigger =
        TvmTestUtils.buildTriggerSmartContract(caller, contractAddr, new byte[0], 0L);
    TransactionCapsule trxCap = new TransactionCapsule(trigger, ContractType.TriggerSmartContract);

    return executor.execute(reader, vmProps, block, trxCap).getResult();
  }

  /** Returns the configured archived state for any address. */
  private static final class FakeReader implements ArchiveStateReader {
    QueryContext queryContext;
    ArchiveReadResult<AccountCapsule> account = ArchiveReadResult.missing();
    ArchiveReadResult<ContractCapsule> contract = ArchiveReadResult.missing();
    ArchiveReadResult<ContractStateCapsule> contractState = ArchiveReadResult.missing();
    ArchiveReadResult<byte[]> code = ArchiveReadResult.missing();
    ArchiveReadResult<byte[]> storage = ArchiveReadResult.missing();
    Error storageFailure;
    byte[] blockHash;
    byte[] childAddress;
    ArchiveReadResult<AccountCapsule> childAccount = ArchiveReadResult.missing();
    ArchiveReadResult<ContractCapsule> childContract = ArchiveReadResult.missing();
    ArchiveReadResult<byte[]> childCode = ArchiveReadResult.missing();

    public ArchiveStatePoint getPoint() {
      return null;
    }

    public QueryContext getQueryContext() {
      return queryContext;
    }

    public ArchiveReadResult<AccountCapsule> getAccount(byte[] address) {
      if (childAddress != null && Arrays.equals(childAddress, address)) {
        return childAccount;
      }
      return account;
    }

    public ArchiveReadResult<byte[]> getAccountAsset(byte[] address, byte[] assetId) {
      return ArchiveReadResult.missing();
    }

    public ArchiveReadResult<ContractCapsule> getContract(byte[] address) {
      if (childAddress != null && Arrays.equals(childAddress, address)) {
        return childContract;
      }
      return contract;
    }

    public ArchiveReadResult<ContractStateCapsule> getContractState(byte[] address) {
      return contractState;
    }

    public ArchiveReadResult<byte[]> getCode(byte[] address) {
      if (childAddress != null && Arrays.equals(childAddress, address)) {
        return childCode;
      }
      return code;
    }

    public ArchiveReadResult<byte[]> getStorage(byte[] address, byte[] slot) {
      if (storageFailure != null) {
        throw storageFailure;
      }
      return storage;
    }

    public ArchiveReadResult<byte[]> getDynamicProperty(byte[] key) {
      return ArchiveReadResult.missing();
    }

    public byte[] getBlockHash(long blockNum) {
      if (queryContext != null) {
        queryContext.recordLogicalRead();
        queryContext.recordBackendReads(2L);
      }
      return blockHash.clone();
    }

    public void close() {
    }
  }
}
