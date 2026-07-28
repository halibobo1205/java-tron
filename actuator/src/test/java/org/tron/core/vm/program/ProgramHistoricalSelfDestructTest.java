package org.tron.core.vm.program;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
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
import org.tron.core.vm.OperationRegistry;
import org.tron.core.vm.VM;
import org.tron.core.vm.archive.ArchiveRepositoryAdapter;
import org.tron.core.vm.config.VMConfig;
import org.tron.core.vm.program.invoke.ProgramInvoke;
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
    Program program = runSelfDestruct(repository, owner, inheritor, historical);

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

    Program program = runSelfDestruct(repository, owner, inheritor, historical);

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

  private static Program runSelfDestruct(ArchiveRepositoryAdapter repository,
      byte[] owner, byte[] inheritor, VMConfig.Snapshot historical) throws Exception {
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

    VMConfig.setLocalSnapshot(historical);
    try {
      VM.play(program, OperationRegistry.getTable());
    } finally {
      VMConfig.clearLocalSnapshot();
    }
    return program;
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
