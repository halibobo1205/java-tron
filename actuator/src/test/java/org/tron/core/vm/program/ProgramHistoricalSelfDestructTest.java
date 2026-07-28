package org.tron.core.vm.program;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.lang3.tuple.Pair;
import org.bouncycastle.util.encoders.Hex;
import org.junit.Test;
import org.tron.common.runtime.InternalTransaction;
import org.tron.common.runtime.vm.DataWord;
import org.tron.common.utils.ByteArray;
import org.tron.core.archive.reader.ArchiveReadResult;
import org.tron.core.archive.reader.ArchiveStatePoint;
import org.tron.core.archive.reader.ArchiveStateReader;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.ContractCapsule;
import org.tron.core.capsule.ContractStateCapsule;
import org.tron.core.capsule.VotesCapsule;
import org.tron.core.store.DelegationStore;
import org.tron.core.store.VmDynamicProperties;
import org.tron.core.vm.MessageCall;
import org.tron.core.vm.Op;
import org.tron.core.vm.OperationRegistry;
import org.tron.core.vm.PrecompiledContracts;
import org.tron.core.vm.VM;
import org.tron.core.vm.archive.ArchiveRepositoryAdapter;
import org.tron.core.vm.config.VMConfig;
import org.tron.core.vm.program.invoke.ProgramInvoke;
import org.tron.core.vm.repository.Repository;
import org.tron.core.vm.trace.VmCallTraceCollector;
import org.tron.protos.Protocol;

public class ProgramHistoricalSelfDestructTest {

  private static final String TOKEN_ID = "1000001";
  private static final long TOKEN_BALANCE = 37L;

  @Test
  public void historicalSelfDestructSweepsEveryArchivedBalance() throws Exception {
    byte[] owner = address(0x11);
    byte[] inheritor = address(0x22);
    HistoricalReader reader = new HistoricalReader(owner, 900L);
    ArchiveRepositoryAdapter repository = new ArchiveRepositoryAdapter(
        reader, mock(VmDynamicProperties.class), true, address(0x00));
    VMConfig.Snapshot historical = new VMConfig.Snapshot();
    historical.allowTvmTransferTrc10 = true;
    historical.allowTvmConstantinople = true;
    historical.allowTvmSolidity059 = true;
    RecordingCallCollector collector = new RecordingCallCollector();
    Program program = runSelfDestruct(repository, owner, inheritor, historical, collector);

    assertNull(program.getResult().getException());
    assertFalse(program.getResult().isRevert());
    assertArrayEquals(inheritor,
        program.getResult().getInternalTransactions().get(0).getTransferToAddress());
    assertEquals(0L, program.getContractState().getBalance(owner));
    assertNotNull(program.getContractState().getAccount(inheritor));
    assertEquals(900L, program.getContractState().getBalance(inheritor));
    assertEquals(0L,
        program.getContractState().getTokenBalance(
            owner, TOKEN_ID.getBytes(StandardCharsets.US_ASCII)));
    assertEquals(TOKEN_BALANCE,
        program.getContractState().getTokenBalance(
            inheritor, TOKEN_ID.getBytes(StandardCharsets.US_ASCII)));
    assertEquals(1, program.getResult().getDeleteAccounts().size());
    assertEquals(1, program.getResult().getInternalTransactions().size());
    assertEquals(Collections.singletonMap(TOKEN_ID, TOKEN_BALANCE),
        program.getResult().getInternalTransactions().get(0).getTokenInfo());
    assertTrue(reader.enumeratedAssets);
    collector.assertSuccessfulSelfDestruct(owner, inheritor, 900L);
  }

  @Test
  public void historicalRestrictedSelfDestructSweepsWithoutDeletingExistingContract()
      throws Exception {
    byte[] owner = address(0x23);
    byte[] inheritor = address(0x24);
    HistoricalReader reader = new HistoricalReader(owner, 900L);
    ArchiveRepositoryAdapter repository = new ArchiveRepositoryAdapter(
        reader, mock(VmDynamicProperties.class), true, address(0x00));
    VMConfig.Snapshot historical = new VMConfig.Snapshot();
    historical.allowTvmTransferTrc10 = true;
    historical.allowTvmConstantinople = true;
    historical.allowTvmSolidity059 = true;
    historical.allowTvmSelfdestructRestriction = true;

    RecordingCallCollector collector = new RecordingCallCollector();
    Program program = runSelfDestruct(repository, owner, inheritor, historical, collector);

    assertNull(program.getResult().getException());
    assertEquals(0L, program.getContractState().getBalance(owner));
    assertEquals(900L, program.getContractState().getBalance(inheritor));
    assertEquals(0L,
        program.getContractState().getTokenBalance(
            owner, TOKEN_ID.getBytes(StandardCharsets.US_ASCII)));
    assertEquals(TOKEN_BALANCE,
        program.getContractState().getTokenBalance(
            inheritor, TOKEN_ID.getBytes(StandardCharsets.US_ASCII)));
    assertTrue(program.getResult().getDeleteAccounts().isEmpty());
    assertEquals(Collections.singletonMap(TOKEN_ID, TOKEN_BALANCE),
        program.getResult().getInternalTransactions().get(0).getTokenInfo());
    collector.assertSuccessfulSelfDestruct(owner, inheritor, 900L);
  }

  @Test
  public void historicalSelfDestructToSelfSweepsIntoInjectedBlackHole() throws Exception {
    byte[] owner = address(0x25);
    byte[] blackHole = address(0x26);
    HistoricalReader reader = new HistoricalReader(owner, 900L);
    ArchiveRepositoryAdapter repository = new ArchiveRepositoryAdapter(
        reader, mock(VmDynamicProperties.class), true, blackHole);
    VMConfig.Snapshot historical = new VMConfig.Snapshot();
    historical.allowTvmTransferTrc10 = true;
    historical.allowTvmConstantinople = true;
    historical.allowTvmSolidity059 = true;

    Program program = runSelfDestruct(repository, owner, owner, historical);

    assertNull(program.getResult().getException());
    assertEquals(0L, program.getContractState().getBalance(owner));
    assertEquals(900L, program.getContractState().getBalance(blackHole));
    assertEquals(0L,
        program.getContractState().getTokenBalance(
            owner, TOKEN_ID.getBytes(StandardCharsets.US_ASCII)));
    assertEquals(TOKEN_BALANCE,
        program.getContractState().getTokenBalance(
            blackHole, TOKEN_ID.getBytes(StandardCharsets.US_ASCII)));
    assertEquals(1, program.getResult().getDeleteAccounts().size());
  }

  @Test
  public void historicalSelfDestructWithdrawsVoteRewardFromArchivedDelegationState()
      throws Exception {
    byte[] owner = address(0x31);
    byte[] inheritor = address(0x32);
    byte[] witness = address(0x33);
    Protocol.Vote vote = Protocol.Vote.newBuilder()
        .setVoteAddress(ByteString.copyFrom(witness))
        .setVoteCount(2L)
        .build();
    AccountCapsule ownerAccount = new AccountCapsule(Protocol.Account.newBuilder()
        .setAddress(ByteString.copyFrom(owner))
        .setType(Protocol.AccountType.Contract)
        .setBalance(900L)
        .addVotes(vote)
        .build());
    HistoricalReader reader = new HistoricalReader(ownerAccount);
    reader.clearAccountAssets();
    reader.putDelegation(owner, ByteArray.fromLong(1L));
    BigInteger endVi = DelegationStore.DECIMAL_OF_VI_REWARD.multiply(BigInteger.valueOf(2L));
    reader.putDelegation(witnessViKey(2L, witness), endVi.toByteArray());
    VmDynamicProperties vmProperties = mock(VmDynamicProperties.class);
    when(vmProperties.getCurrentCycleNumber()).thenReturn(3L);
    ArchiveRepositoryAdapter repository = new ArchiveRepositoryAdapter(
        reader, vmProperties, true, address(0x00));
    VMConfig.Snapshot historical = new VMConfig.Snapshot();
    historical.allowTvmConstantinople = true;
    historical.allowTvmSolidity059 = true;
    historical.allowTvmVote = true;

    Program program = runSelfDestruct(repository, owner, inheritor, historical);

    assertNull(program.getResult().getException());
    assertEquals(904L, program.getContractState().getBalance(inheritor));
    assertEquals(3L, program.getContractState().getBeginCycle(owner));
    assertEquals(4L, program.getContractState().getEndCycle(owner));
    assertEquals(1, program.getContractState().getAccountVote(3L, owner).getVotesList().size());
    VotesCapsule votes = program.getContractState().getVotes(owner);
    assertEquals(1, votes.getOldVotes().size());
    assertTrue(program.getContractState().getAccount(owner).getVotesList().isEmpty());
  }

  @Test
  public void historicalProgramsDoNotShareJumpDestinationAnalysis() throws Exception {
    byte[] owner = address(0x41);
    ArchiveRepositoryAdapter repository = new ArchiveRepositoryAdapter(
        new HistoricalReader(owner, 1L), mock(VmDynamicProperties.class),
        true, address(0x00));
    byte[] code = new byte[] {(byte) 0x5b, 0x00};

    Program first = newProgram(repository, owner, code);
    Program second = newProgram(repository, owner, code);

    assertTrue(first.usesIsolatedExecutionHelpers());
    assertTrue(second.usesIsolatedExecutionHelpers());
    assertNotSame(first.getProgramPrecompile(), second.getProgramPrecompile());
  }

  @Test
  public void canonicalProgramsRetainSharedJumpDestinationCache() throws Exception {
    byte[] owner = address(0x42);
    Repository repository = mock(Repository.class);
    byte[] code = new byte[] {(byte) 0x5b, 0x00};

    Program first = newProgram(repository, owner, code);
    Program second = newProgram(repository, owner, code);

    assertFalse(first.usesIsolatedExecutionHelpers());
    assertFalse(second.usesIsolatedExecutionHelpers());
    assertSame(first.getProgramPrecompile(), second.getProgramPrecompile());
  }

  @Test
  public void isolatedPrecompileFactoryNeverReturnsSharedSingleton() {
    PrecompiledContracts.PrecompiledContract singleton =
        PrecompiledContracts.getContractForAddress(new DataWord(4L));
    PrecompiledContracts.PrecompiledContract first =
        PrecompiledContracts.newIsolatedContract(singleton);
    PrecompiledContracts.PrecompiledContract second =
        PrecompiledContracts.newIsolatedContract(singleton);

    assertNotSame(singleton, first);
    assertNotSame(singleton, second);
    assertNotSame(first, second);
  }

  @Test
  public void guardRejectedCallsRemainVisibleWithoutExtendingMemory() throws Exception {
    byte[] owner = address(0x43);
    Repository repository = mock(Repository.class);
    VMConfig.Snapshot snapshot = new VMConfig.Snapshot();
    snapshot.allowTvmCompatibleEvm = true;
    VMConfig.setLocalSnapshot(snapshot);
    try {
      DepthFailureCollector createCollector = new DepthFailureCollector();
      Program create = newProgram(repository, owner, new byte[0], 64);
      create.setRootTransactionId(new byte[32]);
      create.setCallTraceCollector(createCollector);
      create.createContract(
          DataWord.ZERO(), new DataWord(96L), new DataWord(1L));
      createCollector.assertFailure(Op.CREATE, false, "max call depth exceeded");
      assertEquals(0, create.getMemSize());

      DepthFailureCollector create2Collector = new DepthFailureCollector();
      Program create2 = newProgram(repository, owner, new byte[0], 64);
      create2.setRootTransactionId(new byte[32]);
      create2.setCallTraceCollector(create2Collector);
      create2.createContract2(
          DataWord.ZERO(), new DataWord(96L), new DataWord(1L), DataWord.ZERO());
      create2Collector.assertFailure(Op.CREATE2, false, "max call depth exceeded");
      assertEquals(0, create2.getMemSize());

      DepthFailureCollector callCollector = new DepthFailureCollector();
      Program call = newProgram(repository, owner, new byte[0], 64);
      call.setCallTraceCollector(callCollector);
      call.callToAddress(callMessage(DataWord.ZERO()));
      callCollector.assertFailure(Op.CALL, false, "max call depth exceeded");
      assertEquals(0, call.getMemSize());

      DepthFailureCollector precompileDepthCollector = new DepthFailureCollector();
      Program precompileDepth = newProgram(repository, owner, new byte[0], 64);
      precompileDepth.setCallTraceCollector(precompileDepthCollector);
      precompileDepth.callToPrecompiledAddress(callMessage(DataWord.ZERO()), null);
      precompileDepthCollector.assertFailure(Op.CALL, true, "max call depth exceeded");
      assertEquals(0, precompileDepth.getMemSize());

      Repository emptyRepository = mock(Repository.class);
      when(emptyRepository.newRepositoryChild()).thenReturn(emptyRepository);
      when(emptyRepository.getBalance(owner)).thenReturn(0L);
      DepthFailureCollector precompileBalanceCollector = new DepthFailureCollector();
      Program precompileBalance = newProgram(emptyRepository, owner, new byte[0]);
      precompileBalance.setCallTraceCollector(precompileBalanceCollector);
      precompileBalance.callToPrecompiledAddress(callMessage(new DataWord(1L)), null);
      precompileBalanceCollector.assertFailure(
          Op.CALL, true, "insufficient balance for transfer");
      assertEquals(0, precompileBalance.getMemSize());
    } finally {
      VMConfig.clearLocalSnapshot();
    }
  }

  @Test
  public void tracedPrecompileSuccessExtendsMemoryAtCanonicalPoint() throws Exception {
    byte[] owner = address(0x45);
    Repository repository = mock(Repository.class);
    when(repository.newRepositoryChild()).thenReturn(repository);
    PrecompiledContracts.PrecompiledContract contract =
        mock(PrecompiledContracts.PrecompiledContract.class);
    when(contract.getEnergyForData(any(byte[].class))).thenReturn(0L);
    when(contract.execute(any(byte[].class))).thenReturn(Pair.of(true, new byte[0]));
    DepthFailureCollector collector = new DepthFailureCollector();
    Program program = newProgram(repository, owner, new byte[0]);
    program.setCallTraceCollector(collector);

    program.callToPrecompiledAddress(callMessage(DataWord.ZERO()), contract);

    collector.assertSuccess(Op.CALL, true);
    assertEquals(128, program.getMemSize());
  }

  private static MessageCall callMessage(DataWord endowment) {
    return new MessageCall(
        Op.CALL,
        new DataWord(100L),
        new DataWord(address(0x44)),
        endowment,
        new DataWord(96L),
        new DataWord(1L),
        DataWord.ZERO(),
        DataWord.ZERO(),
        DataWord.ZERO(),
        false);
  }

  private static Program runSelfDestruct(ArchiveRepositoryAdapter repository,
      byte[] owner, byte[] inheritor, VMConfig.Snapshot historical) throws Exception {
    return runSelfDestruct(repository, owner, inheritor, historical, null);
  }

  private static Program runSelfDestruct(ArchiveRepositoryAdapter repository,
      byte[] owner, byte[] inheritor, VMConfig.Snapshot historical,
      VmCallTraceCollector collector) throws Exception {
    ProgramInvoke invoke = mock(ProgramInvoke.class);
    when(invoke.getContractAddress()).thenReturn(new DataWord(owner));
    when(invoke.getDeposit()).thenReturn(repository);
    when(invoke.getTimestamp()).thenReturn(new DataWord(123L));
    when(invoke.getEnergyLimit()).thenReturn(1_000_000L);
    when(invoke.getVmShouldEndInUs()).thenReturn(Long.MAX_VALUE);
    byte[] code = selfDestructCode(inheritor);
    Program program = new Program(code, owner, invoke, new InternalTransaction(
        Protocol.Transaction.getDefaultInstance(),
        InternalTransaction.TrxType.TRX_UNKNOWN_TYPE));
    program.setRootTransactionId(new byte[32]);
    program.setCallTraceCollector(collector);

    VMConfig.setLocalSnapshot(historical);
    try {
      VM.play(program, OperationRegistry.getTable());
    } finally {
      VMConfig.clearLocalSnapshot();
    }
    return program;
  }

  private static Program newProgram(Repository repository, byte[] owner, byte[] code)
      throws Exception {
    return newProgram(repository, owner, code, 0);
  }

  private static Program newProgram(
      Repository repository, byte[] owner, byte[] code, int callDepth) throws Exception {
    ProgramInvoke invoke = mock(ProgramInvoke.class);
    when(invoke.getContractAddress()).thenReturn(new DataWord(owner));
    when(invoke.getCallerAddress()).thenReturn(new DataWord(owner));
    when(invoke.getDeposit()).thenReturn(repository);
    when(invoke.getCallDeep()).thenReturn(callDepth);
    when(invoke.getEnergyLimit()).thenReturn(1_000_000L);
    return new Program(code, owner, invoke, new InternalTransaction(
        Protocol.Transaction.getDefaultInstance(),
        InternalTransaction.TrxType.TRX_UNKNOWN_TYPE));
  }

  private static final class DepthFailureCollector implements VmCallTraceCollector {

    private int opCode;
    private byte[] input;
    private boolean precompile;
    private String error;
    private boolean closed;

    @Override
    public TraceScope enter(int enteredOpCode, byte[] from, byte[] to, byte[] input,
        long energy, BigInteger value, boolean enteredPrecompile) {
      opCode = enteredOpCode;
      this.input = input.clone();
      precompile = enteredPrecompile;
      assertNotNull(from);
      assertNotNull(to);
      return new TraceScope() {
        @Override
        public void complete(byte[] output, long energyUsed, boolean reverted,
            String failure) {
          assertEquals(0L, energyUsed);
          assertFalse(reverted);
          error = failure;
        }

        @Override
        public void close() {
          closed = true;
        }
      };
    }

    private void assertFailure(
        int expectedOpCode, boolean expectedPrecompile, String expectedError) {
      assertEquals(expectedOpCode, opCode);
      assertArrayEquals(new byte[] {0}, input);
      assertEquals(expectedPrecompile, precompile);
      assertEquals(expectedError, error);
      assertTrue(closed);
    }

    private void assertSuccess(int expectedOpCode, boolean expectedPrecompile) {
      assertEquals(expectedOpCode, opCode);
      assertArrayEquals(new byte[] {0}, input);
      assertEquals(expectedPrecompile, precompile);
      assertNull(error);
      assertTrue(closed);
    }
  }

  private static final class RecordingCallCollector implements VmCallTraceCollector {

    private int opCode;
    private byte[] from;
    private byte[] to;
    private BigInteger value;
    private boolean completed;
    private boolean closed;

    @Override
    public TraceScope enter(int enteredOpCode, byte[] enteredFrom, byte[] enteredTo, byte[] input,
        long energy, BigInteger enteredValue, boolean precompile) {
      opCode = enteredOpCode;
      from = enteredFrom.clone();
      to = enteredTo.clone();
      value = enteredValue;
      assertEquals(0, input.length);
      assertEquals(0L, energy);
      assertFalse(precompile);
      return new TraceScope() {
        @Override
        public void complete(byte[] output, long energyUsed, boolean reverted, String error) {
          assertEquals(0, output.length);
          assertEquals(0L, energyUsed);
          assertFalse(reverted);
          assertNull(error);
          completed = true;
        }

        @Override
        public void close() {
          closed = true;
        }
      };
    }

    private void assertSuccessfulSelfDestruct(byte[] expectedFrom, byte[] expectedTo,
        long expectedValue) {
      assertEquals(org.tron.core.vm.Op.SUICIDE, opCode);
      assertArrayEquals(expectedFrom, from);
      assertArrayEquals(expectedTo, to);
      assertEquals(BigInteger.valueOf(expectedValue), value);
      assertTrue(completed);
      assertTrue(closed);
    }
  }

  private static byte[] selfDestructCode(byte[] inheritor) {
    byte[] code = new byte[22];
    code[0] = 0x73;
    System.arraycopy(inheritor, 1, code, 1, 20);
    code[21] = (byte) 0xff;
    return code;
  }

  private static byte[] address(int suffix) {
    byte[] address = new byte[21];
    address[0] = 0x41;
    address[1] = (byte) suffix;
    return address;
  }

  private static byte[] witnessViKey(long cycle, byte[] witness) {
    return (cycle + "-" + Hex.toHexString(witness) + "-vi")
        .getBytes(StandardCharsets.US_ASCII);
  }

  private static final class HistoricalReader implements ArchiveStateReader {

    private final byte[] owner;
    private final AccountCapsule ownerAccount;
    private final Map<String, byte[]> delegations = new HashMap<>();
    private Map<String, Long> accountAssets =
        Collections.singletonMap(TOKEN_ID, TOKEN_BALANCE);
    private boolean enumeratedAssets;

    private HistoricalReader(byte[] owner, long balance) {
      this(new AccountCapsule(Protocol.Account.newBuilder()
          .setAddress(ByteString.copyFrom(owner))
          .setType(Protocol.AccountType.Contract)
          .setBalance(balance)
          .build()));
    }

    private HistoricalReader(AccountCapsule ownerAccount) {
      this.owner = ownerAccount.createDbKey().clone();
      this.ownerAccount = new AccountCapsule(ownerAccount.getInstance());
    }

    private void putDelegation(byte[] key, byte[] value) {
      delegations.put(ByteArray.toHexString(key), value.clone());
    }

    private void clearAccountAssets() {
      accountAssets = Collections.emptyMap();
    }

    @Override
    public ArchiveStatePoint getPoint() {
      return ArchiveStatePoint.txBefore(1L, new byte[32], 1L);
    }

    @Override
    public ArchiveReadResult<AccountCapsule> getAccount(byte[] address) {
      return Arrays.equals(owner, address)
          ? ArchiveReadResult.present(new AccountCapsule(ownerAccount.getInstance()))
          : ArchiveReadResult.missing();
    }

    @Override
    public ArchiveReadResult<byte[]> getAccountAsset(byte[] address, byte[] assetId) {
      Long balance = accountAssets.get(new String(assetId, StandardCharsets.US_ASCII));
      return Arrays.equals(owner, address)
          && balance != null
          ? ArchiveReadResult.present(ByteArray.fromLong(balance))
          : ArchiveReadResult.missing();
    }

    @Override
    public Map<String, Long> getAccountAssets(byte[] address) {
      enumeratedAssets = true;
      return Arrays.equals(owner, address)
          ? accountAssets
          : Collections.emptyMap();
    }

    @Override
    public ArchiveReadResult<VotesCapsule> getVotes(byte[] address) {
      return ArchiveReadResult.missing();
    }

    @Override
    public ArchiveReadResult<byte[]> getDelegation(byte[] key) {
      byte[] value = delegations.get(ByteArray.toHexString(key));
      return value == null
          ? ArchiveReadResult.missing()
          : ArchiveReadResult.present(value.clone());
    }

    @Override
    public ArchiveReadResult<ContractCapsule> getContract(byte[] address) {
      return ArchiveReadResult.missing();
    }

    @Override
    public ArchiveReadResult<ContractStateCapsule> getContractState(byte[] address) {
      return ArchiveReadResult.missing();
    }

    @Override
    public ArchiveReadResult<byte[]> getCode(byte[] address) {
      return ArchiveReadResult.missing();
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
  }
}
