package org.tron.core.vm.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import org.bouncycastle.util.encoders.Hex;
import org.junit.Test;
import org.mockito.Answers;
import org.tron.common.crypto.Hash;
import org.tron.common.runtime.InternalTransaction;
import org.tron.common.runtime.vm.DataWord;
import org.tron.core.archive.reader.ArchiveReadResult;
import org.tron.core.archive.reader.ArchiveStateReader;
import org.tron.core.capsule.ContractCapsule;
import org.tron.core.store.VmDynamicProperties;
import org.tron.core.vm.OperationRegistry;
import org.tron.core.vm.VM;
import org.tron.core.vm.config.VMConfig;
import org.tron.core.vm.program.Program;
import org.tron.core.vm.program.invoke.ProgramInvoke;
import org.tron.core.vm.repository.Repository;
import org.tron.protos.Protocol;
import org.tron.protos.contract.SmartContractOuterClass.SmartContract;

public class ArchiveContractCodeHashTest {

  private static final byte[] ADDRESS = Hex.decode("410000000000000000000000000000000000000042");

  @Test
  public void runtimeSaveRefreshesConstructorHashBeforeAndAfterChildCommit() throws Exception {
    try (VMConfig.LocalSnapshotScope ignored = VMConfig.preserveLocalSnapshot()) {
      enableConstantinople();
      Repository root = newRepository();
      Repository child = root.newRepositoryChild();
      createContract(child);
      assertArrayEquals(Hash.sha3(new byte[0]),
          program(child, new byte[0]).getCodeHashAt(new DataWord(ADDRESS)));
      byte[] runtime = Hex.decode("602a60005260206000f3");
      child.saveCode(ADDRESS, runtime);
      assertArrayEquals(Hash.sha3(runtime), child.getContract(ADDRESS).getCodeHash());
      child.commit();
      assertArrayEquals(Hash.sha3(runtime),
          program(root, new byte[0]).getCodeHashAt(new DataWord(ADDRESS)));
    }
  }

  @Test
  public void runtimeSavePreservesPreConstantinopleBehavior() {
    try (VMConfig.LocalSnapshotScope ignored = VMConfig.preserveLocalSnapshot()) {
      VMConfig.setLocalSnapshot(new VMConfig.Snapshot());
      Repository root = newRepository();
      createContract(root);
      byte[] before = root.getContract(ADDRESS).getCodeHash();
      root.saveCode(ADDRESS, new byte[] {0});
      assertArrayEquals(before, root.getContract(ADDRESS).getCodeHash());
    }
  }

  @Test
  public void createReturnsRuntimeHashAfterConstructorInspectsOwnHash() throws Exception {
    assertCreateReturnsRuntimeHash("600d6017600039600d60006000f03f60005260206000f3");
  }

  @Test
  public void create2ReturnsRuntimeHashAfterConstructorInspectsOwnHash() throws Exception {
    assertCreateReturnsRuntimeHash("600d60196000396000600d60006000f53f60005260206000f3");
  }

  private void assertCreateReturnsRuntimeHash(String parentCode) throws Exception {
    try (VMConfig.LocalSnapshotScope ignored = VMConfig.preserveLocalSnapshot()) {
      enableConstantinople();
      Repository root = newRepository();
      createContract(root);
      // The constructor caches EXTCODEHASH(address()) before returning the one-byte STOP runtime.
      Program program = program(root, Hex.decode(parentCode + "303f50600060005360016000f3"));
      program.setRootTransactionId(new byte[32]);
      VM.play(program, OperationRegistry.getTable());
      assertNull(program.getResult().getException());
      assertFalse(program.getResult().isRevert());
      assertArrayEquals(Hash.sha3(new byte[] {0}), program.getResult().getHReturn());
    }
  }

  private static void enableConstantinople() {
    VMConfig.Snapshot snapshot = new VMConfig.Snapshot();
    snapshot.allowTvmConstantinople = true;
    snapshot.energyLimitHardFork = true;
    VMConfig.setLocalSnapshot(snapshot);
  }

  private static Repository newRepository() {
    ArchiveStateReader reader = mock(ArchiveStateReader.class, invocation ->
        invocation.getMethod().getReturnType() == ArchiveReadResult.class
            ? ArchiveReadResult.missing() : Answers.RETURNS_DEFAULTS.answer(invocation));
    return new ArchiveRepositoryAdapter(reader, mock(VmDynamicProperties.class));
  }

  private static void createContract(Repository repository) {
    repository.createAccount(ADDRESS, Protocol.AccountType.Contract);
    repository.createContract(ADDRESS, new ContractCapsule(SmartContract.newBuilder()
        .setContractAddress(ByteString.copyFrom(ADDRESS)).build()));
  }

  private static Program program(Repository repository, byte[] code) throws Exception {
    ProgramInvoke invoke = mock(ProgramInvoke.class, invocation ->
        invocation.getMethod().getReturnType() == DataWord.class
            ? DataWord.ZERO() : Answers.RETURNS_DEFAULTS.answer(invocation));
    when(invoke.getDeposit()).thenReturn(repository);
    when(invoke.getContractAddress()).thenReturn(new DataWord(ADDRESS));
    when(invoke.getEnergyLimit()).thenReturn(1_000_000L);
    when(invoke.getVmShouldEndInUs()).thenReturn(Long.MAX_VALUE);
    when(invoke.isConstantCall()).thenReturn(true);
    return new Program(code, ADDRESS, invoke, new InternalTransaction(
        Protocol.Transaction.getDefaultInstance(), InternalTransaction.TrxType.TRX_UNKNOWN_TYPE));
  }
}
