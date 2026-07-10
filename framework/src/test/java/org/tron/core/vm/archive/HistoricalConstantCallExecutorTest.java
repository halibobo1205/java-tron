package org.tron.core.vm.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
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
import org.tron.core.store.VmDynamicProperties;
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
  public void historicalChainIdDoesNotCrashOnGenesisBlockRead() throws Exception {
    // CHAINID reads block 0 via getBlockByNum; the adapter must serve it from the immutable block
    // store (EIP-712 / permit contracts use it) instead of throwing. Value is genesis-derived, so
    // we only assert the call completes and returns a 32-byte word.
    byte[] result = runViewCall(CHAINID_CODE, ArchiveReadResult.missing());
    org.junit.Assert.assertEquals(32, result.length);
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

  private byte[] runViewCall(byte[] code, ArchiveReadResult<byte[]> archivedSlot) throws Exception {
    return runViewCall(code, archivedSlot, 0L);
  }

  private byte[] runViewCall(byte[] code, ArchiveReadResult<byte[]> slot, long allowShangHai)
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
    // Dynamic energy active at the historical block (mainnet default) must NOT crash the call;
    // London enables BASEFEE, Istanbul enables CHAINID.
    when(vmProps.getAllowDynamicEnergy()).thenReturn(1L);
    when(vmProps.getAllowTvmLondon()).thenReturn(1L);
    when(vmProps.getAllowTvmIstanbul()).thenReturn(1L);
    when(vmProps.getAllowTvmShangHai()).thenReturn(allowShangHai);
    when(vmProps.getMaintenanceTimeInterval()).thenReturn(21_600_000L);

    BlockCapsule block = new BlockCapsule(1L, Sha256Hash.ZERO_HASH, 1000L,
        ByteString.copyFrom(new byte[21]));
    TriggerSmartContract trigger =
        TvmTestUtils.buildTriggerSmartContract(caller, contractAddr, new byte[0], 0L);
    TransactionCapsule trxCap = new TransactionCapsule(trigger, ContractType.TriggerSmartContract);

    return new HistoricalConstantCallExecutor().execute(reader, vmProps, block, trxCap).getResult();
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

    public ArchiveReadResult<byte[]> getAccountAsset(byte[] address, byte[] assetId) {
      return ArchiveReadResult.missing();
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
