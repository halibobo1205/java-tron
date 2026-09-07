package org.tron.core.vm.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.bouncycastle.util.encoders.Hex;
import org.junit.Test;
import org.mockito.Answers;
import org.tron.common.crypto.SignInterface;
import org.tron.common.crypto.SignUtils;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.runtime.InternalTransaction;
import org.tron.common.runtime.ProgramResult;
import org.tron.common.runtime.vm.DataWord;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.ByteUtil;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.ChainBaseManager;
import org.tron.core.archive.query.ArchiveQueryLimits;
import org.tron.core.archive.query.QueryContext;
import org.tron.core.archive.query.QueryContextHolder;
import org.tron.core.archive.reader.ArchiveReadResult;
import org.tron.core.archive.reader.ArchiveStateReader;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.ContractCapsule;
import org.tron.core.store.AccountStore;
import org.tron.core.store.CodeStore;
import org.tron.core.store.ContractStore;
import org.tron.core.store.DynamicPropertiesStore;
import org.tron.core.store.StoreFactory;
import org.tron.core.store.VmDynamicProperties;
import org.tron.core.vm.OperationRegistry;
import org.tron.core.vm.VM;
import org.tron.core.vm.config.VMConfig;
import org.tron.core.vm.program.Program;
import org.tron.core.vm.program.invoke.ProgramInvokeImpl;
import org.tron.core.vm.repository.Repository;
import org.tron.core.vm.repository.RepositoryImpl;
import org.tron.protos.Protocol;
import org.tron.protos.Protocol.Permission;
import org.tron.protos.contract.SmartContractOuterClass.SmartContract;

public class ArchiveAccountPermissionConformanceTest {

  private static final byte[] PARENT = Hex.decode("410000000000000000000000000000000000000042");
  private static final long INITIAL_BALANCE = 1_000_000L;
  private static final long BLOCK_TIMESTAMP = 1_600_000_000_000L;
  private static final long OVERLAY_LIMIT = 1_048_576L;

  @Test
  public void activePermissionPrecompileAcceptsNewAccountUnderHistoricalMultisign()
      throws Exception {
    assertPermissionConforms(true, 2, 1L, false);
  }

  @Test
  public void ownerPermissionSignatureIsAcceptedByBothRepositories() throws Exception {
    assertPermissionConforms(true, 0, 1L, false);
  }

  @Test
  public void disabledHistoricalMultisignDoesNotCreateActivePermission() throws Exception {
    assertPermissionConforms(false, 2, 0L, false);
  }

  @Test
  public void newAccountDefaultsMatchHistoricalCanonicalAccountExactly() throws Exception {
    // TX_BEFORE uses the previous header; BLOCK_END uses the just-finalized header.
    assertPermissionConforms(true, 0, 1L, true);
    assertPermissionConforms(true, 0, 1L, true, BLOCK_TIMESTAMP);
  }

  private static void assertPermissionConforms(boolean allowMultiSign, int permissionId,
      long expected, boolean compareDefaults) throws Exception {
    assertPermissionConforms(allowMultiSign, permissionId, expected, compareDefaults,
        BLOCK_TIMESTAMP - 3_000L);
  }

  private static void assertPermissionConforms(boolean allowMultiSign, int permissionId,
      long expected, boolean compareDefaults, long latestHeaderTimestamp) throws Exception {
    Fixture fixture = new Fixture(allowMultiSign, latestHeaderTimestamp);
    try (VMConfig.LocalSnapshotScope ignored = VMConfig.preserveLocalSnapshot()) {
      VMConfig.Snapshot snapshot = new VMConfig.Snapshot();
      snapshot.energyLimitHardFork = true;
      snapshot.allowTvmConstantinople = true;
      snapshot.allowTvmSolidity059 = true;
      snapshot.allowMultiSign = allowMultiSign;
      VMConfig.setLocalSnapshot(snapshot);

      byte[] input = signedPermissionInput(fixture.key, permissionId);
      Repository canonical = RepositoryImpl.createRoot(fixture.storeFactory);
      Program canonicalProgram = execute(canonical, fixture.recipient, input);
      assertArrayEquals("canonical must validate the real signature with the historical flag",
          word(expected), canonicalProgram.getReturnDataBuffer());
      AccountCapsule canonicalAccount = canonical.getAccount(fixture.recipient);
      assertCanonicalDefaults(fixture, canonicalAccount, allowMultiSign);

      Repository archive = fixture.newArchiveRepository();
      Program archiveProgram = execute(archive, fixture.recipient, input);
      fixture.assertSourcesUnchanged();
      assertArrayEquals("new-account permission must produce the canonical precompile result",
          canonicalProgram.getReturnDataBuffer(), archiveProgram.getReturnDataBuffer());
      assertArrayEquals(canonicalProgram.getResult().getHReturn(),
          archiveProgram.getResult().getHReturn());
      assertEquals(canonicalProgram.getResult().getEnergyUsed(),
          archiveProgram.getResult().getEnergyUsed());
      AccountCapsule archiveAccount = archive.getAccount(fixture.recipient);
      if (!allowMultiSign) {
        assertFalse(archiveAccount.getInstance().hasOwnerPermission());
        assertEquals(0, archiveAccount.getInstance().getActivePermissionCount());
      }
      if (compareDefaults) {
        assertEquals("owner, active operations/keys, and creation time must match canonical",
            canonicalAccount.getInstance(), archiveAccount.getInstance());
      }
    }
  }

  private static void assertCanonicalDefaults(Fixture fixture, AccountCapsule account,
      boolean allowMultiSign) {
    assertNotNull(account);
    assertEquals(Protocol.AccountType.Normal, account.getType());
    assertEquals(fixture.latestHeaderTimestamp, account.getInstance().getCreateTime());
    assertEquals(allowMultiSign, account.getInstance().hasOwnerPermission());
    assertEquals(allowMultiSign ? 1 : 0, account.getInstance().getActivePermissionCount());
    if (allowMultiSign) {
      Permission active = account.getPermissionById(2);
      assertNotNull(active);
      assertEquals(Permission.PermissionType.Active, active.getType());
      assertEquals("active", active.getPermissionName());
      assertEquals(0, active.getParentId());
      assertEquals(1L, active.getThreshold());
      assertEquals(1, active.getKeysCount());
      assertEquals(ByteString.copyFrom(fixture.recipient), active.getKeys(0).getAddress());
      assertEquals(1L, active.getKeys(0).getWeight());
      assertArrayEquals(fixture.activeOperations, active.getOperations().toByteArray());
      assertFalse("available and default active operations are intentionally different",
          Arrays.equals(fixture.availableContracts, active.getOperations().toByteArray()));
      assertEquals(AccountCapsule.createDefaultOwnerPermission(
          ByteString.copyFrom(fixture.recipient)), account.getInstance().getOwnerPermission());
    }
  }

  private static Program execute(Repository repository, byte[] recipient, byte[] input)
      throws Exception {
    assertNull("the value call must create the recipient", repository.getAccount(recipient));
    QueryContext context = new QueryContext(ArchiveQueryLimits.builder()
        .maxVmSteps(1_000).maxVmOverlayBytes(OVERLAY_LIMIT).build());
    try (QueryContextHolder.Scope ignored = QueryContextHolder.attach(context)) {
      ProgramInvokeImpl invoke = new ProgramInvokeImpl(PARENT, PARENT, PARENT,
          repository.getBalance(PARENT), 0L, 0L, 0L, input, new byte[32], PARENT,
          BLOCK_TIMESTAMP / 1_000L, 100L, repository, 0L, Long.MAX_VALUE, 1_000_000L);
      invoke.setConstantCall();
      Program program = new Program(parentCode(recipient), PARENT, invoke, new InternalTransaction(
          Protocol.Transaction.getDefaultInstance(), InternalTransaction.TrxType.TRX_UNKNOWN_TYPE));
      program.setRootTransactionId(new byte[32]);
      VM.play(program, OperationRegistry.getTable());
      ProgramResult result = program.getResult();
      assertNull(result.getException());
      assertFalse(result.isRevert());
      assertEquals(96, result.getHReturn().length);
      assertArrayEquals("value CALL must succeed", word(1),
          Arrays.copyOfRange(result.getHReturn(), 0, 32));
      assertArrayEquals("precompile STATICCALL must succeed", word(1),
          Arrays.copyOfRange(result.getHReturn(), 64, 96));
      assertEquals("precompile must actually return its boolean", 32,
          program.getReturnDataBufferLength());
      assertArrayEquals(program.getReturnDataBuffer(),
          Arrays.copyOfRange(result.getHReturn(), 32, 64));
      assertEquals(1, result.getInternalTransactions().size());
      InternalTransaction transfer = result.getInternalTransactions().get(0);
      assertFalse(transfer.isRejected());
      assertArrayEquals(recipient, transfer.getTransferToAddress());
      assertEquals(1L, transfer.getValue());
      assertEquals(1L, repository.getBalance(recipient));
      assertEquals(INITIAL_BALANCE - 1L, repository.getBalance(PARENT));
      assertTrue(context.getVmSteps() > 0);
      if (repository.isHistoricalArchive()) {
        assertTrue("created accounts must be charged to this request",
            context.getVmOverlayBytes() > 0);
        assertTrue(context.getVmOverlayBytes() <= OVERLAY_LIMIT);
      }
      return program;
    }
  }

  private static byte[] parentCode(byte[] recipient) {
    // Return [value-CALL success, multisign boolean, STATICCALL success] beyond the ABI input.
    return Hex.decode("6000600060006000600173"
        + Hex.toHexString(Arrays.copyOfRange(recipient, 1, recipient.length))
        + "620186a0f161014052366000600037"
        + "6020610160366000600a620186a0fa610180526060610140f3");
  }

  private static byte[] signedPermissionInput(SignInterface key, int permissionId) {
    byte[] data = word(42L);
    byte[] address = key.getAddress();
    boolean ecEngine = CommonParameter.getInstance().isECKeyCryptoEngine();
    byte[] digest = Sha256Hash.hash(ecEngine,
        ByteUtil.merge(address, ByteArray.fromInt(permissionId), data));
    byte[] signature = key.Base64toBytes(key.signHash(digest));
    assertEquals(65, signature.length);

    // ABI (address,uint256,bytes32,bytes[]): one dynamic 65-byte RSV signature, padded to 3 words.
    return ByteBuffer.allocate(320)
        .put(new DataWord(Arrays.copyOfRange(address, 1, address.length)).getData())
        .put(word(permissionId)).put(data).put(word(128))
        .put(word(1)).put(word(32)).put(word(signature.length)).put(signature).array();
  }

  private static byte[] word(long value) {
    return new DataWord(value).getData();
  }

  private static final class Fixture {

    private final SignInterface key = SignUtils.fromPrivate(word(1L),
        CommonParameter.getInstance().isECKeyCryptoEngine());
    private final byte[] recipient = key.getAddress();
    private final byte[] activeOperations = Hex.decode(
        "7fff1fc0033e0100000000000000000000000000000000000000000000000000");
    private final byte[] availableContracts = Hex.decode(
        "7fff1fc0037e0100000000000000000000000000000000000000000000000000");
    private final ArchiveStateReader reader = mock(ArchiveStateReader.class, invocation ->
        invocation.getMethod().getReturnType() == ArchiveReadResult.class
            ? ArchiveReadResult.missing() : Answers.RETURNS_DEFAULTS.answer(invocation));
    private final VmDynamicProperties vmProperties = mock(VmDynamicProperties.class);
    private final StoreFactory storeFactory = mock(StoreFactory.class);
    private final AccountStore accountStore = mock(AccountStore.class);
    private final long latestHeaderTimestamp;

    private Fixture(boolean allowMultiSign, long latestHeaderTimestamp) throws Exception {
      this.latestHeaderTimestamp = latestHeaderTimestamp;
      DynamicPropertiesStore properties = mock(DynamicPropertiesStore.class);
      when(properties.getAllowMultiSign()).thenReturn(allowMultiSign ? 1L : 0L);
      when(properties.getLatestBlockHeaderTimestamp()).thenReturn(latestHeaderTimestamp);
      when(properties.getActiveDefaultOperations()).thenAnswer(i -> activeOperations.clone());
      when(properties.getAvailableContractType()).thenAnswer(i -> availableContracts.clone());
      when(vmProperties.getAllowMultiSign()).thenReturn(allowMultiSign ? 1L : 0L);
      when(vmProperties.getLatestBlockHeaderTimestamp()).thenReturn(latestHeaderTimestamp);
      seedProperty("ALLOW_MULTI_SIGN", ByteArray.fromLong(allowMultiSign ? 1L : 0L));
      seedProperty("latest_block_header_timestamp", ByteArray.fromLong(latestHeaderTimestamp));
      seedProperty("ACTIVE_DEFAULT_OPERATIONS", activeOperations);
      seedProperty("AVAILABLE_CONTRACT_TYPE", availableContracts);

      ContractStore contractStore = mock(ContractStore.class);
      ChainBaseManager manager = mock(ChainBaseManager.class);
      when(storeFactory.getChainBaseManager()).thenReturn(manager);
      when(manager.getAccountStore()).thenReturn(accountStore);
      when(manager.getContractStore()).thenReturn(contractStore);
      when(manager.getCodeStore()).thenReturn(mock(CodeStore.class));
      when(manager.getDynamicPropertiesStore()).thenReturn(properties);
      Protocol.Account parent = Protocol.Account.newBuilder()
          .setAddress(ByteString.copyFrom(PARENT))
          .setType(Protocol.AccountType.Contract).setBalance(INITIAL_BALANCE).build();
      SmartContract contract = SmartContract.newBuilder()
          .setContractAddress(ByteString.copyFrom(PARENT)).build();
      when(accountStore.get(PARENT)).thenAnswer(i -> new AccountCapsule(parent));
      when(contractStore.get(PARENT)).thenAnswer(i -> new ContractCapsule(contract));
      when(reader.getAccount(PARENT)).thenAnswer(i ->
          ArchiveReadResult.present(new AccountCapsule(parent)));
      when(reader.getContract(PARENT)).thenAnswer(i ->
          ArchiveReadResult.present(new ContractCapsule(contract)));
    }

    private void seedProperty(String name, byte[] value) throws Exception {
      when(reader.getDynamicProperty(name.getBytes(StandardCharsets.US_ASCII))).thenAnswer(i ->
          ArchiveReadResult.present(value.clone()));
    }

    private Repository newArchiveRepository() {
      return new ArchiveRepositoryAdapter(reader, vmProperties);
    }

    private void assertSourcesUnchanged() throws Exception {
      assertEquals(ArchiveReadResult.Status.MISSING, reader.getAccount(recipient).getStatus());
      assertNull(accountStore.get(recipient));
      assertEquals(INITIAL_BALANCE, reader.getAccount(PARENT).getValue().getBalance());
      assertEquals(INITIAL_BALANCE, accountStore.get(PARENT).getBalance());
      Repository fresh = newArchiveRepository();
      assertNull("new accounts must not escape the request", fresh.getAccount(recipient));
      assertArrayEquals(activeOperations, fresh.getDynamicProperty(
          "ACTIVE_DEFAULT_OPERATIONS".getBytes(StandardCharsets.US_ASCII)).getData());
    }
  }
}
