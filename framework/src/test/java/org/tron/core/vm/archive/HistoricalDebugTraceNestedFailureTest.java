package org.tron.core.vm.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import java.math.BigInteger;
import java.util.Arrays;
import org.bouncycastle.util.encoders.Hex;
import org.junit.Before;
import org.junit.Test;
import org.tron.common.BaseMethodTest;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.runtime.TvmTestUtils;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.archive.query.ArchiveQueryLimits;
import org.tron.core.archive.query.QueryContext;
import org.tron.core.archive.query.QueryContextHolder;
import org.tron.core.archive.reader.ArchiveReadResult;
import org.tron.core.archive.reader.ArchiveReaderException;
import org.tron.core.archive.reader.ArchiveStatePoint;
import org.tron.core.archive.reader.ArchiveStateReader;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.ContractCapsule;
import org.tron.core.capsule.ContractStateCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.store.VmDynamicProperties;
import org.tron.core.vm.Op;
import org.tron.core.vm.trace.VmCallTraceCollector;
import org.tron.core.vm.trace.VmStructuredTraceListener;
import org.tron.protos.Protocol.Account;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;
import org.tron.protos.contract.SmartContractOuterClass.SmartContract;
import org.tron.protos.contract.SmartContractOuterClass.TriggerSmartContract;

public class HistoricalDebugTraceNestedFailureTest extends BaseMethodTest {

  // CALL child(0x33), discard its status, then return 1.
  private static final byte[] PARENT_CALLS_CHILD_CODE = Hex.decode(
      "60006000600060006000730000000000000000000000000000000000000033"
          + "620f4240f150600160005260206000f3");

  // CALL RewardBalance (0x01000005), whose historical delegation read fails closed.
  private static final byte[] CHILD_CALLS_REWARD_BALANCE_CODE = Hex.decode(
      "600060006000600060006301000005620f4240f15000");

  @Before
  public void generousConstantCallTimeout() {
    CommonParameter.getInstance().setConstantCallTimeoutMs(60_000);
  }

  @Test
  public void structLogNestedFailureCannotBeHiddenByParentSuccess() {
    assertNestedFailure(false);
  }

  @Test
  public void callTracerNestedFailureCannotBeHiddenByParentSuccess() {
    assertNestedFailure(true);
  }

  private void assertNestedFailure(boolean callTracer) {
    QueryContext queryContext = new QueryContext(ArchiveQueryLimits.unlimited());

    UnsupportedHistoricalStateException failure = assertThrows(
        UnsupportedHistoricalStateException.class,
        () -> executeNestedCall(queryContext, callTracer));

    assertEquals("archive read failed for delegation", failure.getMessage());
    assertTrue(failure.getCause() instanceof ArchiveReaderException);
    assertSame(failure, queryContext.getRecordedVmTerminalFailure());
    assertNull(QueryContextHolder.current());
  }

  private void executeNestedCall(QueryContext queryContext, boolean callTracer)
      throws Exception {
    byte[] contractAddress = address(0x11);
    byte[] caller = address(0x22);
    byte[] childAddress = address(0x33);
    FakeReader reader = new FakeReader(queryContext, contractAddress, childAddress);

    VmDynamicProperties vmProperties = mock(VmDynamicProperties.class);
    when(vmProperties.supportVM()).thenReturn(true);
    when(vmProperties.getMaxFeeLimit()).thenReturn(1_000_000_000_000L);
    when(vmProperties.getMaxCpuTimeOfOneTx()).thenReturn(50L);
    when(vmProperties.getEnergyFee()).thenReturn(100L);
    when(vmProperties.getAllowDynamicEnergy()).thenReturn(1L);
    when(vmProperties.getAllowTvmLondon()).thenReturn(1L);
    when(vmProperties.getAllowTvmIstanbul()).thenReturn(1L);
    when(vmProperties.getAllowTvmVote()).thenReturn(1L);
    when(vmProperties.getAllowTvmShangHai()).thenReturn(0L);
    when(vmProperties.getMaintenanceTimeInterval()).thenReturn(21_600_000L);

    BlockCapsule block = new BlockCapsule(
        1L, Sha256Hash.ZERO_HASH, 1_000L, ByteString.copyFrom(new byte[21]));
    TriggerSmartContract trigger =
        TvmTestUtils.buildTriggerSmartContract(caller, contractAddress, new byte[0], 0L);
    TransactionCapsule transaction =
        new TransactionCapsule(trigger, ContractType.TriggerSmartContract);
    VmStructuredTraceListener structuredListener =
        callTracer ? null : new NoopStructuredTraceListener();
    VmCallTraceCollector callCollector =
        callTracer ? new NoopCallTraceCollector() : null;
    HistoricalCallTraceSpec callTraceSpec = callTracer
        ? new HistoricalCallTraceSpec(
            Op.CALL, caller, contractAddress, new byte[0], BigInteger.ZERO)
        : null;

    new HistoricalDebugTraceExecutor().execute(
        reader,
        vmProperties,
        block,
        transaction,
        true,
        true,
        structuredListener,
        callCollector,
        callTraceSpec);
  }

  private static byte[] address(int suffix) {
    byte[] address = new byte[21];
    address[0] = 0x41;
    address[20] = (byte) suffix;
    return address;
  }

  private static final class FakeReader implements ArchiveStateReader {

    private final QueryContext queryContext;
    private final byte[] contractAddress;
    private final byte[] childAddress;
    private final AccountCapsule contractAccount;
    private final AccountCapsule childAccount;
    private final ContractCapsule contract;
    private final ContractCapsule childContract;

    private FakeReader(
        QueryContext queryContext, byte[] contractAddress, byte[] childAddress) {
      this.queryContext = queryContext;
      this.contractAddress = contractAddress.clone();
      this.childAddress = childAddress.clone();
      contractAccount = account(contractAddress);
      childAccount = account(childAddress);
      contract = contract(contractAddress);
      childContract = contract(childAddress);
    }

    @Override
    public ArchiveStatePoint getPoint() {
      return null;
    }

    @Override
    public QueryContext getQueryContext() {
      return queryContext;
    }

    @Override
    public ArchiveReadResult<AccountCapsule> getAccount(byte[] address) {
      if (Arrays.equals(childAddress, address)) {
        return ArchiveReadResult.present(childAccount);
      }
      return Arrays.equals(contractAddress, address)
          ? ArchiveReadResult.present(contractAccount)
          : ArchiveReadResult.missing();
    }

    @Override
    public ArchiveReadResult<byte[]> getAccountAsset(byte[] address, byte[] assetId) {
      return ArchiveReadResult.missing();
    }

    @Override
    public ArchiveReadResult<ContractCapsule> getContract(byte[] address) {
      if (Arrays.equals(childAddress, address)) {
        return ArchiveReadResult.present(childContract);
      }
      return Arrays.equals(contractAddress, address)
          ? ArchiveReadResult.present(contract)
          : ArchiveReadResult.missing();
    }

    @Override
    public ArchiveReadResult<ContractStateCapsule> getContractState(byte[] address) {
      return ArchiveReadResult.missing();
    }

    @Override
    public ArchiveReadResult<byte[]> getCode(byte[] address) {
      if (Arrays.equals(childAddress, address)) {
        return ArchiveReadResult.present(CHILD_CALLS_REWARD_BALANCE_CODE);
      }
      return Arrays.equals(contractAddress, address)
          ? ArchiveReadResult.present(PARENT_CALLS_CHILD_CODE)
          : ArchiveReadResult.missing();
    }

    @Override
    public ArchiveReadResult<byte[]> getStorage(byte[] address, byte[] slot) {
      return ArchiveReadResult.missing();
    }

    @Override
    public ArchiveReadResult<byte[]> getDynamicProperty(byte[] key) {
      return ArchiveReadResult.missing();
    }

    @Override
    public void close() {
    }

    private static AccountCapsule account(byte[] address) {
      return new AccountCapsule(
          Account.newBuilder().setAddress(ByteString.copyFrom(address)).build());
    }

    private static ContractCapsule contract(byte[] address) {
      return new ContractCapsule(
          SmartContract.newBuilder()
              .setContractAddress(ByteString.copyFrom(address))
              .build());
    }
  }

  private static final class NoopStructuredTraceListener
      implements VmStructuredTraceListener {

    @Override
    public void capture(org.tron.core.vm.program.Program program) {
    }

    @Override
    public void onProgramExit(org.tron.core.vm.program.Program program) {
    }
  }

  private static final class NoopCallTraceCollector implements VmCallTraceCollector {

    @Override
    public TraceScope enter(
        int opCode,
        byte[] from,
        byte[] to,
        byte[] input,
        long energy,
        BigInteger value,
        boolean precompile) {
      return new TraceScope() {
        @Override
        public void complete(byte[] output, long energyUsed, boolean reverted, String error) {
        }

        @Override
        public void close() {
        }
      };
    }
  }
}
