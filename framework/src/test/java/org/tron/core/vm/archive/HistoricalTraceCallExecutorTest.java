package org.tron.core.vm.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import java.math.BigInteger;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.tron.common.BaseMethodTest;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.runtime.TvmTestUtils;
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
import org.tron.core.services.jsonrpc.StructLogReconstructor;
import org.tron.core.services.jsonrpc.types.StructLog;
import org.tron.core.store.VmDynamicProperties;
import org.tron.core.vm.config.VMConfig;
import org.tron.core.vm.program.Program;
import org.tron.core.vm.program.Program.OutOfTimeException;
import org.tron.core.vm.trace.ProgramTrace;
import org.tron.protos.Protocol.Account;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;
import org.tron.protos.contract.SmartContractOuterClass.SmartContract;
import org.tron.protos.contract.SmartContractOuterClass.TriggerSmartContract;

/**
 * End-to-end: a historical debug_traceCall replays a contract against archived state with the
 * native opcode tracer on, and the structLog reconstruction rebuilds the Geth/Besu per-op trace.
 * The executor enables tracing only on its own thread, so the global default must be untouched.
 */
public class HistoricalTraceCallExecutorTest extends BaseMethodTest {

  // PUSH1 1; PUSH1 2; ADD; PUSH1 0; MSTORE; PUSH1 0x20; PUSH1 0; RETURN -> returns 3 (1 + 2).
  private static final byte[] ADD_CODE = {
      0x60, 0x01, 0x60, 0x02, 0x01, 0x60, 0x00, 0x52, 0x60, 0x20, 0x60, 0x00, (byte) 0xf3
  };

  // PUSH1 0; SLOAD; PUSH1 0; MSTORE; PUSH1 0x20; PUSH1 0; RETURN -> returns storage slot 0.
  private static final byte[] SLOAD_CODE = {
      0x60, 0x00, 0x54, 0x60, 0x00, 0x52, 0x60, 0x20, 0x60, 0x00, (byte) 0xf3
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

  @Before
  public void generousConstantCallTimeout() {
    // Per-op trace capture is slower than a plain constant call, so give the constant-call CPU
    // deadline plenty of headroom. This also makes the test independent of a tiny timeout another
    // test in the same JVM may have left in the shared CommonParameter singleton.
    CommonParameter.getInstance().setConstantCallTimeoutMs(60_000);
  }

  private static String word(long v) {
    byte[] w = new byte[32];
    w[31] = (byte) v;
    return org.bouncycastle.util.encoders.Hex.toHexString(w);
  }

  @Override
  protected void afterInit() {
  }

  @Test
  public void traceReconstructsOpsStackAndReturnValue() throws Exception {
    HistoricalTraceCallResult result = runTrace(ADD_CODE, ArchiveReadResult.missing());
    // The trace executor enables vmTrace only on its own thread, then drops it: the global default
    // must remain false (consensus / latest path byte-identical).
    assertFalse("global vmTrace must stay false after a trace call", VMConfig.vmTrace());

    assertFalse("the ADD call succeeds, so failed is false", result.isFailed());
    // returnValue is the 32-byte word holding 3 (no 0x prefix, Geth form).
    assertEquals(word(3), org.tron.common.utils.ByteArray.toHexString(result.getHReturn()));

    List<StructLog> logs = StructLogReconstructor.reconstruct(result.getTrace());
    assertEquals("PUSH1,PUSH1,ADD,PUSH1,MSTORE,PUSH1,PUSH1,RETURN = 8 ops", 8, logs.size());

    // Exact opcode names match the bytecode (immediates are not separate ops).
    assertEquals("PUSH1", logs.get(0).getOp());
    assertEquals("PUSH1", logs.get(1).getOp());
    assertEquals("ADD", logs.get(2).getOp());
    assertEquals("MSTORE", logs.get(4).getOp());
    assertEquals("RETURN", logs.get(7).getOp());

    // pc values come straight from the trace (PUSH1 consumes a 1-byte immediate).
    assertEquals(0, logs.get(0).getPc());
    assertEquals(2, logs.get(1).getPc());
    assertEquals(4, logs.get(2).getPc());

    // Geth depth starts at 1.
    assertEquals(1, logs.get(0).getDepth());

    // Pre-op stack reconstruction: the first PUSH1 sees an empty stack; ADD sees [1, 2]
    // (bottom-first); the op after ADD sees [3].
    assertTrue("first op has empty pre-op stack", logs.get(0).getStack().isEmpty());
    assertEquals("ADD pre-op stack is [1, 2]",
        java.util.Arrays.asList(word(1), word(2)), logs.get(2).getStack());
    assertEquals("op after ADD sees [3] on the stack",
        java.util.Collections.singletonList(word(3)), logs.get(3).getStack());

    // gas is the remaining energy; gasCost is the native opcode charge recorded by VM.play.
    assertTrue("remaining gas decreases across ops",
        logs.get(0).getGas() >= logs.get(1).getGas());
    assertEquals("PUSH1 native trace records the actual opcode cost",
        BigInteger.valueOf(3L), result.getTrace().getOps().get(0).getEnergyCost());
    assertEquals("structLog gasCost uses the recorded opcode cost",
        3L, logs.get(0).getGasCost());
  }

  @Test
  public void traceCapturesSloadStorageReadValueAndOps() throws Exception {
    byte[] storedWord = new byte[32];
    storedWord[31] = 0x2a;
    HistoricalTraceCallResult result =
        runTrace(SLOAD_CODE, ArchiveReadResult.present(storedWord));
    assertFalse(result.isFailed());
    // The call returns the archived storage slot, proving the trace ran against archived state.
    assertEquals("000000000000000000000000000000000000000000000000000000000000002a",
        org.tron.common.utils.ByteArray.toHexString(result.getHReturn()));

    List<StructLog> logs = StructLogReconstructor.reconstruct(result.getTrace());
    assertEquals("PUSH1", logs.get(0).getOp());
    assertEquals("SLOAD", logs.get(1).getOp());
    // SLOAD's pre-op stack is [0] (the slot index pushed by the preceding PUSH1).
    assertEquals(java.util.Collections.singletonList(word(0)), logs.get(1).getStack());
  }

  @Test
  public void vmCatchAllCannotHideHistoricalStepLimit() {
    QueryContext queryContext = new QueryContext(ArchiveQueryLimits.builder()
        .maxVmSteps(0)
        .build());

    HistoricalQueryLimitException failure = assertThrows(
        HistoricalQueryLimitException.class,
        () -> runTrace(ADD_CODE, ArchiveReadResult.missing(), queryContext));

    assertEquals(HistoricalQueryLimitException.Limit.VM_STEPS, failure.getLimit());
    assertSame(failure, queryContext.getTerminalException());
    assertEquals(1L, queryContext.getVmSteps());
    assertNull(QueryContextHolder.current());
    assertFalse(VMConfig.vmTrace());
  }

  @Test
  public void vmCatchAllCannotHideHistoricalTraceByteLimit() {
    QueryContext queryContext = new QueryContext(ArchiveQueryLimits.builder()
        .maxTraceBytes(0)
        .build());

    HistoricalQueryLimitException failure = assertThrows(
        HistoricalQueryLimitException.class,
        () -> runTrace(ADD_CODE, ArchiveReadResult.missing(), queryContext));

    assertEquals(HistoricalQueryLimitException.Limit.TRACE_BYTES, failure.getLimit());
    assertSame(failure, queryContext.getTerminalException());
    assertTrue(queryContext.getTraceBytes() > 0L);
    assertNull(QueryContextHolder.current());
    assertFalse(VMConfig.vmTrace());
  }

  @Test
  public void responseReconstructionRestoresTheTraceQueryContext() throws Exception {
    QueryContext queryContext = new QueryContext(ArchiveQueryLimits.builder()
        .maxResponseBytes(0)
        .build());
    HistoricalTraceCallResult result =
        runTrace(ADD_CODE, ArchiveReadResult.missing(), queryContext);
    assertNull(QueryContextHolder.current());
    assertSame(queryContext, result.getTrace().historicalQueryContext());

    HistoricalQueryLimitException failure = assertThrows(
        HistoricalQueryLimitException.class,
        () -> StructLogReconstructor.reconstruct(result.getTrace()));

    assertEquals(HistoricalQueryLimitException.Limit.RESPONSE_BYTES, failure.getLimit());
    assertSame(failure, queryContext.getTerminalException());
    assertTrue(queryContext.getVmSteps() > 0L);
    assertTrue(queryContext.getTraceBytes() > 0L);
    assertTrue(queryContext.getResponseBytes() > 0L);
    assertNull(QueryContextHolder.current());
  }

  @Test
  public void directCpuTimeoutIsNotOverwrittenByFinallyDeadlineCheck() throws Exception {
    QueryContext queryContext = new QueryContext(ArchiveQueryLimits.builder()
        .deadlineMs(0)
        .build());
    VMActuator vmActuator = mock(VMActuator.class);
    OutOfTimeException cpuTimeout = new OutOfTimeException("direct CPU timeout");
    doThrow(cpuTimeout).when(vmActuator).execute(any(TransactionContext.class));
    HistoricalTraceCallExecutor executor = new HistoricalTraceCallExecutor(() -> vmActuator);

    OutOfTimeException failure = assertThrows(
        OutOfTimeException.class,
        () -> runTrace(ADD_CODE, ArchiveReadResult.missing(), queryContext, executor));

    assertSame(cpuTimeout, failure);
    assertNull(QueryContextHolder.current());
    assertFalse(VMConfig.vmTrace());
  }

  @Test
  public void capturedCpuTimeoutResultIsNotOverwrittenByFinallyDeadlineCheck() throws Exception {
    QueryContext queryContext = new QueryContext(ArchiveQueryLimits.builder()
        .deadlineMs(0)
        .build());
    VMActuator vmActuator = mock(VMActuator.class);
    Program program = mock(Program.class);
    ProgramTrace trace = new ProgramTrace();
    OutOfTimeException cpuTimeout = new OutOfTimeException("captured CPU timeout");
    when(vmActuator.getProgram()).thenReturn(program);
    when(program.getTrace()).thenReturn(trace);
    doAnswer(invocation -> {
      TransactionContext context = invocation.getArgument(0);
      context.getProgramResult().setException(cpuTimeout);
      context.getProgramResult().setRuntimeError(cpuTimeout.getMessage());
      return null;
    }).when(vmActuator).execute(any(TransactionContext.class));
    HistoricalTraceCallExecutor executor = new HistoricalTraceCallExecutor(() -> vmActuator);

    HistoricalTraceCallResult result =
        runTrace(ADD_CODE, ArchiveReadResult.missing(), queryContext, executor);

    assertTrue(result.isFailed());
    assertEquals(cpuTimeout.getMessage(), result.getRuntimeError());
    assertNull(queryContext.getRecordedTerminalException());
    assertNull(QueryContextHolder.current());
    assertFalse(VMConfig.vmTrace());
  }

  @Test
  public void recordedQueryLimitWinsOverLaterDirectCpuTimeout() throws Exception {
    QueryContext queryContext = new QueryContext(ArchiveQueryLimits.builder()
        .maxVmSteps(0)
        .build());
    VMActuator vmActuator = mock(VMActuator.class);
    OutOfTimeException cpuTimeout = new OutOfTimeException("later CPU timeout");
    doAnswer(invocation -> {
      assertThrows(HistoricalQueryLimitException.class, queryContext::recordVmStep);
      throw cpuTimeout;
    }).when(vmActuator).execute(any(TransactionContext.class));
    HistoricalTraceCallExecutor executor = new HistoricalTraceCallExecutor(() -> vmActuator);

    HistoricalQueryLimitException failure = assertThrows(
        HistoricalQueryLimitException.class,
        () -> runTrace(ADD_CODE, ArchiveReadResult.missing(), queryContext, executor));

    assertSame(queryContext.getRecordedTerminalException(), failure);
    assertEquals(HistoricalQueryLimitException.Limit.VM_STEPS, failure.getLimit());
    assertNull(QueryContextHolder.current());
    assertFalse(VMConfig.vmTrace());
  }

  @Test
  public void nestedRewardBalanceFailureCannotBeHiddenByParentSuccess() {
    QueryContext queryContext = new QueryContext(ArchiveQueryLimits.builder().build());

    UnsupportedHistoricalStateException failure = assertThrows(
        UnsupportedHistoricalStateException.class,
        () -> runTrace(PARENT_CALLS_CHILD_CODE, ArchiveReadResult.missing(), queryContext,
            new HistoricalTraceCallExecutor(), CHILD_CALLS_REWARD_BALANCE_CODE));

    assertEquals(UnsupportedHistoricalStateException.class, failure.getClass());
    assertEquals("historical archive call does not support begin-cycle reads",
        failure.getMessage());
    assertSame(failure, queryContext.getRecordedVmTerminalFailure());
    assertNull(QueryContextHolder.current());
    assertFalse(VMConfig.vmTrace());
  }

  private HistoricalTraceCallResult runTrace(byte[] code, ArchiveReadResult<byte[]> slot)
      throws Exception {
    return runTrace(code, slot, null);
  }

  private HistoricalTraceCallResult runTrace(byte[] code, ArchiveReadResult<byte[]> slot,
      QueryContext queryContext) throws Exception {
    return runTrace(code, slot, queryContext, new HistoricalTraceCallExecutor());
  }

  private HistoricalTraceCallResult runTrace(byte[] code, ArchiveReadResult<byte[]> slot,
      QueryContext queryContext, HistoricalTraceCallExecutor executor) throws Exception {
    return runTrace(code, slot, queryContext, executor, null);
  }

  private HistoricalTraceCallResult runTrace(byte[] code, ArchiveReadResult<byte[]> slot,
      QueryContext queryContext, HistoricalTraceCallExecutor executor, byte[] childCode)
      throws Exception {
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
    when(vmProps.getAllowDynamicEnergy()).thenReturn(1L);
    when(vmProps.getAllowTvmLondon()).thenReturn(1L);
    when(vmProps.getAllowTvmIstanbul()).thenReturn(1L);
    when(vmProps.getAllowTvmVote()).thenReturn(1L);
    when(vmProps.getAllowTvmShangHai()).thenReturn(0L);
    when(vmProps.getMaintenanceTimeInterval()).thenReturn(21_600_000L);

    BlockCapsule block = new BlockCapsule(1L, Sha256Hash.ZERO_HASH, 1000L,
        ByteString.copyFrom(new byte[21]));
    TriggerSmartContract trigger =
        TvmTestUtils.buildTriggerSmartContract(caller, contractAddr, new byte[0], 0L);
    TransactionCapsule trxCap = new TransactionCapsule(trigger, ContractType.TriggerSmartContract);

    return executor.execute(reader, vmProps, block, trxCap);
  }

  /** Returns the configured archived state for any address. */
  private static final class FakeReader implements ArchiveStateReader {
    QueryContext queryContext;
    ArchiveReadResult<AccountCapsule> account = ArchiveReadResult.missing();
    ArchiveReadResult<ContractCapsule> contract = ArchiveReadResult.missing();
    ArchiveReadResult<ContractStateCapsule> contractState = ArchiveReadResult.missing();
    ArchiveReadResult<byte[]> code = ArchiveReadResult.missing();
    ArchiveReadResult<byte[]> storage = ArchiveReadResult.missing();
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
      if (childAddress != null && java.util.Arrays.equals(childAddress, address)) {
        return childAccount;
      }
      return account;
    }

    public ArchiveReadResult<byte[]> getAccountAsset(byte[] address, byte[] assetId) {
      return ArchiveReadResult.missing();
    }

    public ArchiveReadResult<ContractCapsule> getContract(byte[] address) {
      if (childAddress != null && java.util.Arrays.equals(childAddress, address)) {
        return childContract;
      }
      return contract;
    }

    public ArchiveReadResult<ContractStateCapsule> getContractState(byte[] address) {
      return contractState;
    }

    public ArchiveReadResult<byte[]> getCode(byte[] address) {
      if (childAddress != null && java.util.Arrays.equals(childAddress, address)) {
        return childCode;
      }
      return code;
    }

    public ArchiveReadResult<byte[]> getStorage(byte[] address, byte[] slot) {
      return storage;
    }

    public ArchiveReadResult<byte[]> getDynamicProperty(byte[] key) {
      return ArchiveReadResult.missing();
    }

    public void close() {
    }
  }
}
