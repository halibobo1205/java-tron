package org.tron.core.vm.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.tron.common.BaseMethodTest;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.runtime.TvmTestUtils;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.archive.reader.ArchiveReadResult;
import org.tron.core.archive.reader.ArchiveStatePoint;
import org.tron.core.archive.reader.ArchiveStateReader;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.ContractCapsule;
import org.tron.core.capsule.ContractStateCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.services.jsonrpc.StructLogReconstructor;
import org.tron.core.services.jsonrpc.types.StructLog;
import org.tron.core.store.VmDynamicProperties;
import org.tron.core.vm.config.VMConfig;
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

    // gas is the remaining energy (monotonically non-increasing); gasCost is the drop to next op.
    assertTrue("remaining gas decreases across ops",
        logs.get(0).getGas() >= logs.get(1).getGas());
    assertTrue("a consuming op has non-negative gasCost", logs.get(0).getGasCost() >= 0);
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

  private HistoricalTraceCallResult runTrace(byte[] code, ArchiveReadResult<byte[]> slot)
      throws Exception {
    byte[] contractAddr = new byte[21];
    contractAddr[0] = 0x41;
    contractAddr[20] = 0x11;
    byte[] caller = new byte[21];
    caller[0] = 0x41;
    caller[20] = 0x22;

    FakeReader reader = new FakeReader();
    reader.account = ArchiveReadResult.present(new AccountCapsule(
        Account.newBuilder().setAddress(ByteString.copyFrom(contractAddr)).build()));
    reader.contract = ArchiveReadResult.present(new ContractCapsule(SmartContract.newBuilder()
        .setContractAddress(ByteString.copyFrom(contractAddr)).build()));
    reader.code = ArchiveReadResult.present(code);
    reader.storage = slot;

    VmDynamicProperties vmProps = mock(VmDynamicProperties.class);
    when(vmProps.supportVM()).thenReturn(true);
    when(vmProps.getMaxFeeLimit()).thenReturn(1_000_000_000_000L);
    when(vmProps.getMaxCpuTimeOfOneTx()).thenReturn(50L);
    when(vmProps.getEnergyFee()).thenReturn(100L);
    when(vmProps.getAllowDynamicEnergy()).thenReturn(1L);
    when(vmProps.getAllowTvmLondon()).thenReturn(1L);
    when(vmProps.getAllowTvmIstanbul()).thenReturn(1L);
    when(vmProps.getAllowTvmShangHai()).thenReturn(0L);

    BlockCapsule block = new BlockCapsule(1L, Sha256Hash.ZERO_HASH, 1000L,
        ByteString.copyFrom(new byte[21]));
    TriggerSmartContract trigger =
        TvmTestUtils.buildTriggerSmartContract(caller, contractAddr, new byte[0], 0L);
    TransactionCapsule trxCap = new TransactionCapsule(trigger, ContractType.TriggerSmartContract);

    return new HistoricalTraceCallExecutor().execute(reader, vmProps, block, trxCap);
  }

  /** Returns the configured archived state for any address. */
  private static final class FakeReader implements ArchiveStateReader {
    ArchiveReadResult<AccountCapsule> account = ArchiveReadResult.missing();
    ArchiveReadResult<ContractCapsule> contract = ArchiveReadResult.missing();
    ArchiveReadResult<ContractStateCapsule> contractState = ArchiveReadResult.missing();
    ArchiveReadResult<byte[]> code = ArchiveReadResult.missing();
    ArchiveReadResult<byte[]> storage = ArchiveReadResult.missing();

    public ArchiveStatePoint getPoint() {
      return null;
    }

    public ArchiveReadResult<AccountCapsule> getAccount(byte[] address) {
      return account;
    }

    public ArchiveReadResult<ContractCapsule> getContract(byte[] address) {
      return contract;
    }

    public ArchiveReadResult<ContractStateCapsule> getContractState(byte[] address) {
      return contractState;
    }

    public ArchiveReadResult<byte[]> getCode(byte[] address) {
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
