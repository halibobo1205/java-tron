package org.tron.core.vm.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import org.tron.common.runtime.vm.DataWord;
import org.tron.common.utils.ByteArray;
import org.tron.core.archive.query.ArchiveQueryLimits;
import org.tron.core.archive.query.HistoricalQueryLimitException;
import org.tron.core.archive.query.QueryContext;
import org.tron.core.archive.query.QueryContextHolder;
import org.tron.core.archive.reader.ArchiveReadResult;
import org.tron.core.archive.reader.ArchiveReaderException;
import org.tron.core.archive.reader.ArchiveStatePoint;
import org.tron.core.archive.reader.ArchiveStateReader;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BytesCapsule;
import org.tron.core.capsule.ContractCapsule;
import org.tron.core.capsule.ContractStateCapsule;
import org.tron.core.capsule.VotesCapsule;
import org.tron.core.db.EnergyProcessor;
import org.tron.core.store.AccountStore;
import org.tron.core.store.DynamicPropertiesStore;
import org.tron.core.store.VmDynamicProperties;
import org.tron.core.vm.ChainParameterEnum;
import org.tron.protos.Protocol;
import org.tron.protos.contract.Common.ResourceCode;

/**
 * Slice 2 unit tests: the adapter serves reads from a fake {@link ArchiveStateReader}, maps the
 * three-state result to the Repository read contract, exposes the injected historical
 * {@link VmDynamicProperties}, and fails fast (never silently latest) on uncovered domains and on
 * an archive read error. Writes / overlay are deferred to Slice 3 and must throw.
 */
public class ArchiveRepositoryAdapterTest {

  private static final byte[] ADDR = new byte[21];

  static {
    ADDR[0] = 0x41;
  }

  private final FakeReader reader = new FakeReader();
  private final VmDynamicProperties vmProps = mock(VmDynamicProperties.class);
  private final ArchiveRepositoryAdapter adapter = new ArchiveRepositoryAdapter(reader, vmProps);

  /** Reader whose results each test sets directly; the address argument is ignored. */
  private static final class FakeReader implements ArchiveStateReader {
    ArchiveReadResult<AccountCapsule> account = ArchiveReadResult.missing();
    ArchiveReadResult<byte[]> accountAsset = ArchiveReadResult.missing();
    Map<String, Long> accountAssets = Collections.emptyMap();
    ArchiveReadResult<ContractCapsule> contract = ArchiveReadResult.missing();
    ArchiveReadResult<ContractStateCapsule> contractState = ArchiveReadResult.missing();
    ArchiveReadResult<byte[]> code = ArchiveReadResult.missing();
    ArchiveReadResult<byte[]> storage = ArchiveReadResult.missing();
    ArchiveReadResult<VotesCapsule> votes = ArchiveReadResult.missing();
    Map<String, ArchiveReadResult<byte[]>> dynamicProperties = new HashMap<>();
    Map<String, ArchiveReadResult<byte[]>> delegations = new HashMap<>();
    ArchiveReaderException accountError;
    byte[] blockHash = new byte[32];
    int blockHashReads;

    public ArchiveStatePoint getPoint() {
      return null;
    }

    public ArchiveReadResult<AccountCapsule> getAccount(byte[] a) throws ArchiveReaderException {
      if (accountError != null) {
        throw accountError;
      }
      return account;
    }

    public ArchiveReadResult<byte[]> getAccountAsset(byte[] a, byte[] assetId) {
      return accountAsset;
    }

    public Map<String, Long> getAccountAssets(byte[] a) {
      return accountAssets;
    }

    public ArchiveReadResult<ContractCapsule> getContract(byte[] a) {
      return contract;
    }

    public ArchiveReadResult<ContractStateCapsule> getContractState(byte[] a) {
      return contractState;
    }

    public ArchiveReadResult<byte[]> getCode(byte[] a) {
      return code;
    }

    public ArchiveReadResult<byte[]> getStorage(byte[] a, byte[] slot) {
      return storage;
    }

    public ArchiveReadResult<byte[]> getDynamicProperty(byte[] key) {
      return dynamicProperties.getOrDefault(
          ByteArray.toHexString(key), ArchiveReadResult.missing());
    }

    public ArchiveReadResult<VotesCapsule> getVotes(byte[] address) {
      return votes;
    }

    public ArchiveReadResult<byte[]> getDelegation(byte[] key) {
      return delegations.getOrDefault(
          ByteArray.toHexString(key), ArchiveReadResult.missing());
    }

    public byte[] getBlockHash(long blockNum) {
      blockHashReads++;
      QueryContext context = QueryContextHolder.current();
      if (context != null) {
        context.recordLogicalRead();
        context.recordBackendRead();
      }
      return blockHash.clone();
    }

    public void close() {
    }
  }

  private static AccountCapsule account(long balance) {
    return new AccountCapsule(Protocol.Account.newBuilder().setBalance(balance).build());
  }

  private void putDelegation(byte[] key, byte[] value) {
    reader.delegations.put(
        ByteArray.toHexString(key), ArchiveReadResult.present(value));
  }

  @Test
  public void presentAccountReadsBalanceMissingReadsZero() {
    reader.account = ArchiveReadResult.present(account(777L));
    assertEquals(777L, adapter.getBalance(ADDR));
    assertEquals(777L, adapter.getAccount(ADDR).getBalance());

    reader.account = ArchiveReadResult.missing();
    assertNull(adapter.getAccount(ADDR));
    assertEquals(0L, adapter.getBalance(ADDR));
  }

  @Test
  public void tombstoneAccountIsNull() {
    reader.account = ArchiveReadResult.tombstone();
    assertNull(adapter.getAccount(ADDR));
    assertEquals(0L, adapter.getBalance(ADDR));
  }

  @Test
  public void codePresentAndMissing() {
    byte[] code = {1, 2, 3};
    reader.code = ArchiveReadResult.present(code);
    byte[] returned = adapter.getCode(ADDR);
    assertArrayEquals(code, returned);
    assertNotSame(code, returned);
    returned[0] = 9;
    assertArrayEquals(code, adapter.getCode(ADDR));
    reader.code = ArchiveReadResult.missing();
    assertNull(adapter.getCode(ADDR));
  }

  @Test
  public void saveCodeCopiesCallerBytesIntoRequestOverlay() {
    byte[] code = {1, 2, 3};

    adapter.saveCode(ADDR, code);
    code[0] = 9;
    byte[] returned = adapter.getCode(ADDR);
    assertArrayEquals(new byte[] {1, 2, 3}, returned);
    returned[1] = 9;
    assertArrayEquals(new byte[] {1, 2, 3}, adapter.getCode(ADDR));
  }

  @Test
  public void contractPresentAndMissing() {
    ContractCapsule contract = new ContractCapsule(new byte[] {9});
    reader.contract = ArchiveReadResult.present(contract);
    assertNotSame(contract, adapter.getContract(ADDR));
    reader.contract = ArchiveReadResult.missing();
    assertNull(adapter.getContract(ADDR));
  }

  @Test
  public void deleteContractShadowsArchiveAndPropagatesOnCommit() {
    // SELFDESTRUCT write-isolation: deleteContract writes a null tombstone into the overlay, which
    // must shadow the archived contract (a null-tombstone is distinct from absent) and, on commit,
    // route to parent.deleteContract -- never resurrect the archived contract or NPE.
    ContractCapsule archived = new ContractCapsule(new byte[] {9});
    reader.contract = ArchiveReadResult.present(archived);
    reader.account = ArchiveReadResult.present(account(12L));
    reader.code = ArchiveReadResult.present(new byte[] {1, 2, 3});
    assertNotNull("root reads the archived contract", adapter.getContract(ADDR));

    ArchiveRepositoryAdapter child = (ArchiveRepositoryAdapter) adapter.newRepositoryChild();
    DataWord slot = new DataWord(new byte[] {5});
    child.putStorageValue(ADDR, slot, new DataWord(new byte[] {7}));
    child.addTokenBalance(ADDR, new byte[] {'1'}, 9L);
    child.deleteContract(ADDR);
    assertNull("child sees its own tombstone, not the archived contract", child.getContract(ADDR));
    assertNull("SELFDESTRUCT shadows the archived account", child.getAccount(ADDR));
    assertNull("SELFDESTRUCT shadows the archived code", child.getCode(ADDR));
    assertNull("SELFDESTRUCT makes prior overlay storage unreachable",
        child.getStorageValue(ADDR, slot));
    assertEquals("SELFDESTRUCT makes prior overlay assets unreachable", 0L,
        child.getTokenBalance(ADDR, new byte[] {'1'}));
    assertNotNull("parent still archived before commit", adapter.getContract(ADDR));

    child.commit();
    assertNull("commit routed the tombstone to parent.deleteContract", adapter.getContract(ADDR));
    assertNull(adapter.getAccount(ADDR));
    assertNull(adapter.getCode(ADDR));
    assertNull(adapter.getStorageValue(ADDR, slot));
    assertEquals(0L, adapter.getTokenBalance(ADDR, new byte[] {'1'}));
  }

  @Test
  public void createContractMarksAddressAsNewAcrossChildCommit() {
    ArchiveRepositoryAdapter child = (ArchiveRepositoryAdapter) adapter.newRepositoryChild();

    child.createContract(ADDR, new ContractCapsule(new byte[] {9}));

    assertTrue(child.isNewContract(ADDR));
    child.commit();
    assertTrue(adapter.isNewContract(ADDR));
  }

  @Test
  public void capsuleWritesDoNotRetainCallerMutableWrappers() {
    AccountCapsule suppliedAccount = account(11L);
    adapter.updateAccount(ADDR, suppliedAccount);
    suppliedAccount.setBalance(99L);
    assertEquals(11L, adapter.getAccount(ADDR).getBalance());

    byte[] otherAddress = ADDR.clone();
    otherAddress[20] = 1;
    AccountCapsule created = adapter.createNormalAccount(otherAddress);
    created.setBalance(77L);
    assertEquals(0L, adapter.getAccount(otherAddress).getBalance());

    ContractStateCapsule suppliedState = new ContractStateCapsule(1L);
    suppliedState.setEnergyFactor(3L);
    adapter.updateContractState(ADDR, suppliedState);
    suppliedState.setEnergyFactor(8L);
    assertEquals(3L, adapter.getContractState(ADDR).getEnergyFactor());
  }

  @Test
  public void storageReadsArchivedWordOnlyWhenAccountExists() {
    reader.account = ArchiveReadResult.present(account(1L));
    byte[] word = new byte[32];
    word[31] = 42;
    reader.storage = ArchiveReadResult.present(word);
    assertEquals(new DataWord(word), adapter.getStorageValue(ADDR, new DataWord(new byte[] {5})));

    reader.storage = ArchiveReadResult.missing();
    assertNull(adapter.getStorageValue(ADDR, new DataWord(new byte[] {5})));

    // No account -> no storage value, even if a row were archived.
    reader.account = ArchiveReadResult.missing();
    reader.storage = ArchiveReadResult.present(word);
    assertNull(adapter.getStorageValue(ADDR, new DataWord(new byte[] {5})));
  }

  @Test
  public void tokenBalanceReadsAccountAssetDomain() {
    reader.account = ArchiveReadResult.present(account(1L));
    reader.accountAsset = ArchiveReadResult.present(ByteArray.fromLong(77L));

    assertEquals(77L, adapter.getTokenBalance(ADDR, new byte[] {0, 0, '1', '0', '0'}));

    reader.accountAsset = ArchiveReadResult.missing();
    assertEquals(0L, adapter.getTokenBalance(ADDR, new byte[] {'1', '0', '0'}));
  }

  @Test
  public void tokenBalanceOverlayMergesFromChildToParent() {
    reader.account = ArchiveReadResult.present(account(1L));
    reader.accountAsset = ArchiveReadResult.present(ByteArray.fromLong(77L));
    byte[] tokenId = new byte[] {'1', '0', '0'};

    ArchiveRepositoryAdapter child = (ArchiveRepositoryAdapter) adapter.newRepositoryChild();

    assertEquals(87L, child.addTokenBalance(ADDR, tokenId, 10L));
    assertEquals(87L, child.getTokenBalance(ADDR, tokenId));
    assertEquals(77L, adapter.getTokenBalance(ADDR, tokenId));

    child.commit();

    assertEquals(87L, adapter.getTokenBalance(ADDR, tokenId));
  }

  @Test
  public void tokenBalanceEnumerationMergesOverlayAndMasksZeroBalances() {
    reader.account = ArchiveReadResult.present(account(1L));
    reader.accountAsset = ArchiveReadResult.present(ByteArray.fromLong(77L));
    Map<String, Long> archived = new LinkedHashMap<>();
    archived.put("1000001", 77L);
    archived.put("1000010", 99L);
    reader.accountAssets = Collections.unmodifiableMap(archived);

    ArchiveRepositoryAdapter child = (ArchiveRepositoryAdapter) adapter.newRepositoryChild();
    child.addTokenBalance(ADDR, "1000001".getBytes(), -77L);

    assertEquals(Collections.singletonMap("1000010", 99L),
        child.getTokenBalances(ADDR));
    assertEquals(archived, adapter.getTokenBalances(ADDR));

    child.commit();

    assertEquals(Collections.singletonMap("1000010", 99L),
        adapter.getTokenBalances(ADDR));
    assertThrows(UnsupportedOperationException.class,
        () -> adapter.getTokenBalances(ADDR).put("1000001", 1L));
  }

  @Test
  public void vmDynamicPropertiesIsTheInjectedHistoricalView() {
    assertSame(vmProps, adapter.getVmDynamicProperties());
  }

  @Test
  public void chainParameterReadsUseHistoricalVmDynamicProperties() {
    when(vmProps.getTotalNetLimit()).thenReturn(101L);
    when(vmProps.getTotalNetWeight()).thenReturn(102L);
    when(vmProps.getTotalEnergyCurrentLimit()).thenReturn(103L);
    when(vmProps.getTotalEnergyWeight()).thenReturn(104L);
    when(vmProps.getUnfreezeDelayDays()).thenReturn(105L);

    assertEquals(101L, ChainParameterEnum.TOTAL_NET_LIMIT.getAction().apply(adapter).longValue());
    assertEquals(102L, ChainParameterEnum.TOTAL_NET_WEIGHT.getAction().apply(adapter).longValue());
    assertEquals(103L,
        ChainParameterEnum.TOTAL_ENERGY_CURRENT_LIMIT.getAction().apply(adapter).longValue());
    assertEquals(104L,
        ChainParameterEnum.TOTAL_ENERGY_WEIGHT.getAction().apply(adapter).longValue());
    assertEquals(105L,
        ChainParameterEnum.UNFREEZE_DELAY_DAYS.getAction().apply(adapter).longValue());
  }

  @Test
  public void votesDelegationAndResourceWeightsMergeThroughChildOverlay() {
    VotesCapsule archivedVotes = new VotesCapsule(
        ByteString.copyFrom(ADDR), Collections.emptyList());
    reader.votes = ArchiveReadResult.present(archivedVotes);
    putDelegation(ADDR, ByteArray.fromLong(2L));
    byte[] endKey = ("end-" + ByteArray.toHexString(ADDR))
        .getBytes(StandardCharsets.US_ASCII);
    putDelegation(endKey, ByteArray.fromLong(4L));
    byte[] viKey = ("3-" + ByteArray.toHexString(ADDR) + "-vi")
        .getBytes(StandardCharsets.US_ASCII);
    BigInteger vi = BigInteger.TEN.pow(18);
    putDelegation(viKey, vi.toByteArray());
    when(vmProps.getTotalNetWeight()).thenReturn(100L);
    when(vmProps.getTotalEnergyWeight()).thenReturn(200L);
    when(vmProps.getTotalTronPowerWeight()).thenReturn(300L);

    ArchiveRepositoryAdapter child = (ArchiveRepositoryAdapter) adapter.newRepositoryChild();
    assertEquals(2L, child.getBeginCycle(ADDR));
    assertEquals(4L, child.getEndCycle(ADDR));
    assertEquals(vi, child.getWitnessVi(3L, ADDR));
    assertEquals(archivedVotes.getInstance(), child.getVotes(ADDR).getInstance());

    VotesCapsule updatedVotes = child.getVotes(ADDR);
    updatedVotes.addNewVotes(ByteString.copyFrom(ADDR), 7L);
    child.updateVotes(ADDR, updatedVotes);
    child.updateBeginCycle(ADDR, 5L);
    child.addTotalNetWeight(-10L);
    child.addTotalEnergyWeight(-20L);
    child.addTotalTronPowerWeight(-30L);

    assertEquals(5L, child.getBeginCycle(ADDR));
    assertEquals(90L, child.getTotalNetWeight());
    assertEquals(180L, child.getTotalEnergyWeight());
    assertEquals(270L, child.getTotalTronPowerWeight());
    assertEquals(2L, adapter.getBeginCycle(ADDR));
    assertEquals(100L, adapter.getTotalNetWeight());

    child.commit();

    assertEquals(5L, adapter.getBeginCycle(ADDR));
    assertEquals(90L, adapter.getTotalNetWeight());
    assertEquals(180L, adapter.getTotalEnergyWeight());
    assertEquals(270L, adapter.getTotalTronPowerWeight());
    assertEquals(1, adapter.getVotes(ADDR).getNewVotes().size());
  }

  @Test
  public void blackHoleAddressIsInjectedAsImmutableChainIdentity() {
    byte[] blackHole = ADDR.clone();
    blackHole[20] = 0x55;
    ArchiveRepositoryAdapter withIdentity =
        new ArchiveRepositoryAdapter(reader, vmProps, true, blackHole);

    byte[] returned = withIdentity.getBlackHoleAddress();
    returned[20] = 0;
    blackHole[20] = 0;

    assertEquals(0x55, withIdentity.getBlackHoleAddress()[20]);
  }

  @Test
  public void dynamicPropertiesStoreIsUnsupported() {
    assertThrows(UnsupportedHistoricalStateException.class, adapter::getDynamicPropertiesStore);
  }

  @Test
  public void replayEnergyUsageMatchesCanonicalFreezeV2Calculation() {
    for (boolean cancelAllUnfreezeV2 : new boolean[] {false, true}) {
      for (boolean harden : new boolean[] {false, true}) {
        long now = 1_000L;
        long lastTime = 900L;
        long previousUsage = 987_654L;
        long usage = 123_456L;
        DynamicPropertiesStore dynamicProperties = mock(DynamicPropertiesStore.class);
        when(dynamicProperties.supportUnfreezeDelay()).thenReturn(true);
        when(dynamicProperties.supportAllowCancelAllUnfreezeV2())
            .thenReturn(cancelAllUnfreezeV2);
        when(dynamicProperties.allowHardenResourceCalculation()).thenReturn(harden);
        when(vmProps.supportUnfreezeDelay()).thenReturn(true);
        when(vmProps.supportAllowCancelAllUnfreezeV2()).thenReturn(cancelAllUnfreezeV2);
        when(vmProps.getAllowHardenResourceCalculation()).thenReturn(harden ? 1L : 0L);

        AccountCapsule expected = new AccountCapsule(Protocol.Account.newBuilder()
            .setAddress(ByteString.copyFrom(ADDR))
            .build());
        expected.setEnergyUsage(previousUsage);
        expected.setLatestConsumeTimeForEnergy(lastTime);
        if (cancelAllUnfreezeV2) {
          expected.setNewWindowSizeV2(ResourceCode.ENERGY, 20_000_000_000L);
        } else {
          expected.setNewWindowSize(ResourceCode.ENERGY, 20_000L);
        }
        AccountCapsule actual = new AccountCapsule(expected.getInstance());
        EnergyProcessor canonical =
            new EnergyProcessor(dynamicProperties, mock(AccountStore.class));
        long recoveredUsage = canonical.increase(
            expected, ResourceCode.ENERGY, previousUsage, 0L, lastTime, now);
        expected.setEnergyUsage(recoveredUsage);
        expected.setLatestConsumeTimeForEnergy(now);
        expected.setEnergyUsage(canonical.increase(
            expected, ResourceCode.ENERGY, recoveredUsage, usage, now, now));

        adapter.updateEnergyUsageForReplay(actual, usage, now);

        assertEquals(expected.getInstance(), actual.getInstance());
      }
    }
  }

  @Test
  public void selfDestructFrozenV2UsageMergeMatchesCanonicalResourceProcessor() {
    for (boolean cancelAllUnfreezeV2 : new boolean[] {false, true}) {
      for (boolean harden : new boolean[] {false, true}) {
        long now = 1_000L;
        DynamicPropertiesStore dynamicProperties = mock(DynamicPropertiesStore.class);
        when(dynamicProperties.supportUnfreezeDelay()).thenReturn(true);
        when(dynamicProperties.supportAllowCancelAllUnfreezeV2())
            .thenReturn(cancelAllUnfreezeV2);
        when(dynamicProperties.allowHardenResourceCalculation()).thenReturn(harden);
        when(vmProps.supportUnfreezeDelay()).thenReturn(true);
        when(vmProps.supportAllowCancelAllUnfreezeV2()).thenReturn(cancelAllUnfreezeV2);
        when(vmProps.getAllowHardenResourceCalculation()).thenReturn(harden ? 1L : 0L);

        AccountCapsule expectedOwner = resourceAccount(ADDR, cancelAllUnfreezeV2,
            900L, 8_000L, 6_000L);
        byte[] inheritorAddress = ADDR.clone();
        inheritorAddress[20] = 1;
        AccountCapsule expectedInheritor = resourceAccount(
            inheritorAddress, cancelAllUnfreezeV2, 920L, 700L, 500L);
        AccountCapsule actualOwner = new AccountCapsule(expectedOwner.getInstance());
        AccountCapsule actualInheritor = new AccountCapsule(expectedInheritor.getInstance());
        EnergyProcessor canonical =
            new EnergyProcessor(dynamicProperties, mock(AccountStore.class));

        canonicalTransferUsage(
            canonical, expectedOwner, expectedInheritor, ResourceCode.BANDWIDTH, now);
        canonicalTransferUsage(
            canonical, expectedOwner, expectedInheritor, ResourceCode.ENERGY, now);

        adapter.transferFrozenV2UsageForSelfDestruct(
            actualOwner, actualInheritor, now);

        assertEquals(expectedOwner.getInstance(), actualOwner.getInstance());
        assertEquals(expectedInheritor.getInstance(), actualInheritor.getInstance());
      }
    }
  }

  @Test
  public void globalEnergyLimitMatchesCanonicalV1AndFreezeV2Formulas() {
    for (boolean unfreezeDelay : new boolean[] {false, true}) {
      for (boolean harden : new boolean[] {false, true}) {
        DynamicPropertiesStore dynamicProperties = mock(DynamicPropertiesStore.class);
        when(dynamicProperties.supportUnfreezeDelay()).thenReturn(unfreezeDelay);
        when(dynamicProperties.getTotalEnergyCurrentLimit()).thenReturn(50_000_000_000L);
        when(dynamicProperties.getTotalEnergyWeight()).thenReturn(10_000L);
        when(dynamicProperties.allowHardenResourceCalculation()).thenReturn(harden);
        when(vmProps.supportUnfreezeDelay()).thenReturn(unfreezeDelay);
        when(vmProps.getTotalEnergyCurrentLimit()).thenReturn(50_000_000_000L);
        when(vmProps.getTotalEnergyWeight()).thenReturn(10_000L);
        when(vmProps.getAllowHardenResourceCalculation()).thenReturn(harden ? 1L : 0L);

        AccountCapsule account = new AccountCapsule(Protocol.Account.newBuilder()
            .setAddress(ByteString.copyFrom(ADDR))
            .build());
        account.setFrozenForEnergy(1_500_000L, 0L);
        EnergyProcessor canonical =
            new EnergyProcessor(dynamicProperties, mock(AccountStore.class));

        assertEquals(
            canonical.calculateGlobalEnergyLimit(account),
            adapter.calculateGlobalEnergyLimit(account));
      }
    }
  }

  private static AccountCapsule resourceAccount(byte[] address,
      boolean cancelAllUnfreezeV2, long latestTime, long netUsage, long energyUsage) {
    AccountCapsule account = new AccountCapsule(Protocol.Account.newBuilder()
        .setAddress(ByteString.copyFrom(address))
        .build());
    account.setNetUsage(netUsage);
    account.setLatestConsumeTime(latestTime);
    account.setEnergyUsage(energyUsage);
    account.setLatestConsumeTimeForEnergy(latestTime);
    if (cancelAllUnfreezeV2) {
      account.setNewWindowSizeV2(ResourceCode.BANDWIDTH, 20_000_000_000L);
      account.setNewWindowSizeV2(ResourceCode.ENERGY, 20_000_000_000L);
    } else {
      account.setNewWindowSize(ResourceCode.BANDWIDTH, 20_000L);
      account.setNewWindowSize(ResourceCode.ENERGY, 20_000L);
    }
    return account;
  }

  private static void canonicalTransferUsage(EnergyProcessor processor,
      AccountCapsule owner, AccountCapsule inheritor,
      ResourceCode resourceCode, long now) {
    long lastTime = owner.getLastConsumeTime(resourceCode);
    long usage = owner.getUsage(resourceCode);
    usage = processor.increase(owner, resourceCode, usage, 0L, lastTime, now);
    owner.setUsage(resourceCode, usage);
    owner.setLatestTime(resourceCode, now);
    if (usage > 0L) {
      processor.unDelegateIncrease(inheritor, owner, usage, resourceCode, now);
    }
  }

  @Test
  public void unsupportedReadRecordsExactVmTerminalFailure() {
    QueryContext context = new QueryContext(ArchiveQueryLimits.unlimited());

    try (QueryContextHolder.Scope ignored = QueryContextHolder.attach(context)) {
      UnsupportedHistoricalStateException failure = assertThrows(
          UnsupportedHistoricalStateException.class, adapter::getDynamicPropertiesStore);

      assertSame(failure, context.getRecordedVmTerminalFailure());
      assertSame(failure, context.getRecordedFailure());
    }
    assertNull(QueryContextHolder.current());
  }

  @Test
  public void overlayAllocationIsRejectedBeforeStorageMapGrowth() {
    QueryContext context = new QueryContext(ArchiveQueryLimits.builder()
        .maxVmOverlayBytes(276L)
        .build());
    HistoricalQueryLimitException failure;

    try (QueryContextHolder.Scope ignored = QueryContextHolder.attach(context)) {
      failure = assertThrows(HistoricalQueryLimitException.class,
          () -> adapter.putStorageValue(
              ADDR, new DataWord(new byte[] {1}), new DataWord(new byte[] {2})));
    }

    assertEquals(HistoricalQueryLimitException.Limit.VM_OVERLAY_BYTES, failure.getLimit());
    assertEquals(277L, context.getVmOverlayBytes());
    assertSame(failure, context.getRecordedExecutionTerminalFailure());
  }

  @Test
  public void accountPayloadIsReservedBeforeOverlayMapGrowth() {
    AccountCapsule account = new AccountCapsule(Protocol.Account.newBuilder()
        .setAddress(ByteString.copyFrom(ADDR))
        .setAccountName(ByteString.copyFrom(new byte[1024]))
        .build());
    long expectedBytes = 192L + ADDR.length + account.getInstance().getSerializedSize();
    QueryContext context = new QueryContext(ArchiveQueryLimits.builder()
        .maxVmOverlayBytes(expectedBytes - 1L)
        .build());
    HistoricalQueryLimitException failure;

    try (QueryContextHolder.Scope ignored = QueryContextHolder.attach(context)) {
      failure = assertThrows(HistoricalQueryLimitException.class,
          () -> adapter.updateAccount(ADDR, account));
    }

    assertEquals(HistoricalQueryLimitException.Limit.VM_OVERLAY_BYTES, failure.getLimit());
    assertEquals(expectedBytes, context.getVmOverlayBytes());
    assertNull(adapter.getAccount(ADDR));
  }

  @Test
  public void blockHashUsesReaderSnapshotAndSharedDefensiveCache() {
    reader.blockHash[31] = 0x2a;
    QueryContext context = new QueryContext(ArchiveQueryLimits.unlimited());
    byte[] first;
    byte[] second;

    try (QueryContextHolder.Scope ignored = QueryContextHolder.attach(context)) {
      first = adapter.getBlockHashByNum(0L);
      first[31] = 0;
      ArchiveRepositoryAdapter child =
          (ArchiveRepositoryAdapter) adapter.newRepositoryChild();
      second = child.getBlockHashByNum(0L);
    }

    assertEquals(0x2a, second[31]);
    assertEquals(1, reader.blockHashReads);
    assertEquals(2L, context.getLogicalReads());
    assertEquals(1L, context.getBackendReads());
    assertEquals(1L, context.getCacheHits());
  }

  @Test
  public void blockHashCacheEntryIsReservedBeforeRetention() {
    QueryContext bounded = new QueryContext(ArchiveQueryLimits.builder()
        .maxVmOverlayBytes(223L)
        .build());

    try (QueryContextHolder.Scope ignored = QueryContextHolder.attach(bounded)) {
      HistoricalQueryLimitException failure = assertThrows(
          HistoricalQueryLimitException.class, () -> adapter.getBlockHashByNum(0L));
      assertEquals(HistoricalQueryLimitException.Limit.VM_OVERLAY_BYTES, failure.getLimit());
      assertEquals(224L, bounded.getVmOverlayBytes());
    }
    try (QueryContextHolder.Scope ignored = QueryContextHolder.attach(
        new QueryContext(ArchiveQueryLimits.unlimited()))) {
      adapter.getBlockHashByNum(0L);
    }

    assertEquals(2, reader.blockHashReads);
  }

  @Test
  public void fullBlockReadCannotFallBackToLiveStore() {
    QueryContext context = new QueryContext(ArchiveQueryLimits.unlimited());

    try (QueryContextHolder.Scope ignored = QueryContextHolder.attach(context)) {
      UnsupportedHistoricalStateException failure = assertThrows(
          UnsupportedHistoricalStateException.class, () -> adapter.getBlockByNum(0L));

      assertSame(failure, context.getRecordedVmTerminalFailure());
    }
  }

  @Test
  public void blockHashCacheHoldsChainIdAndAllReachableAncestors() {
    QueryContext context = new QueryContext(ArchiveQueryLimits.unlimited());

    try (QueryContextHolder.Scope ignored = QueryContextHolder.attach(context)) {
      for (int round = 0; round < 2; round++) {
        adapter.getBlockHashByNum(0L);
        for (long blockNum = 1_000L; blockNum > 744L; blockNum--) {
          adapter.getBlockHashByNum(blockNum);
        }
      }
    }

    assertEquals(257, reader.blockHashReads);
    assertEquals(514L, context.getLogicalReads());
    assertEquals(257L, context.getBackendReads());
    assertEquals(257L, context.getCacheHits());
  }

  @Test
  public void contractStateReadsArchiveAndMissingFallsBackToNeutralCapsule() {
    ContractStateCapsule archived = new ContractStateCapsule(1L);
    archived.setEnergyFactor(123L);
    reader.contractState = ArchiveReadResult.present(archived);
    assertEquals(123L, adapter.getContractState(ADDR).getEnergyFactor());

    reader.contractState = ArchiveReadResult.missing();
    when(vmProps.getCurrentCycleNumber()).thenReturn(9L);
    ContractStateCapsule neutral = adapter.getContractState(ADDR);
    assertEquals(0L, neutral.getEnergyFactor());
    assertEquals(9L, neutral.getUpdateCycle());
  }

  @Test
  public void contractStateOverlayMergesFromChildToParent() {
    ContractStateCapsule archived = new ContractStateCapsule(1L);
    archived.setEnergyFactor(10L);
    reader.contractState = ArchiveReadResult.present(archived);

    ArchiveRepositoryAdapter child = (ArchiveRepositoryAdapter) adapter.newRepositoryChild();
    ContractStateCapsule updated = child.getContractState(ADDR);
    updated.setEnergyFactor(99L);
    child.updateContractState(ADDR, updated);

    assertEquals(10L, adapter.getContractState(ADDR).getEnergyFactor());
    assertEquals(99L, child.getContractState(ADDR).getEnergyFactor());

    child.commit();

    assertEquals(99L, adapter.getContractState(ADDR).getEnergyFactor());
  }

  @Test
  public void midChainMissingStateFailsClosed() {
    ArchiveRepositoryAdapter midChain =
        new ArchiveRepositoryAdapter(reader, vmProps, false);

    assertThrows(UnsupportedHistoricalStateException.class,
        () -> midChain.getAccount(ADDR));
    assertThrows(UnsupportedHistoricalStateException.class,
        () -> midChain.getCode(ADDR));
    assertThrows(UnsupportedHistoricalStateException.class,
        () -> midChain.getContract(ADDR));
    assertThrows(UnsupportedHistoricalStateException.class,
        () -> midChain.getContractState(ADDR));
    assertThrows(UnsupportedHistoricalStateException.class,
        () -> midChain.getVotes(ADDR));

    reader.account = ArchiveReadResult.present(account(1L));
    assertThrows(UnsupportedHistoricalStateException.class,
        () -> midChain.getStorageValue(ADDR, new DataWord(new byte[] {5})));
    assertThrows(UnsupportedHistoricalStateException.class,
        () -> midChain.getTokenBalance(ADDR, new byte[] {5}));
  }

  @Test
  public void uncoveredDomainsFailFast() {
    assertThrows(UnsupportedHistoricalStateException.class, () -> adapter.getWitness(ADDR));
    assertThrows(UnsupportedHistoricalStateException.class,
        () -> adapter.getDelegatedResource(ADDR));
  }

  @Test
  public void archiveReadErrorIsWrappedNotSilent() {
    reader.accountError = new ArchiveReaderException(
        ArchiveReaderException.Reason.CORRUPT_VALUE, "boom");
    assertThrows(UnsupportedHistoricalStateException.class, () -> adapter.getAccount(ADDR));
  }

  @Test
  public void unsupportedOverlayVariantsStillFailFast() {
    // The VM never calls these on the archive path; they fail fast rather than silently no-op.
    assertThrows(UnsupportedHistoricalStateException.class, () -> adapter.getStorage(ADDR));
    assertThrows(UnsupportedHistoricalStateException.class, () -> adapter.putAccount(null, null));
    assertThrows(UnsupportedHistoricalStateException.class, () -> adapter.getWitness(ADDR));
  }

  @Test
  public void valueTransferToFreshAccountMaterializesItInOverlay() {
    // A value-bearing CALL to a non-existent address creates it in the overlay instead of aborting.
    long balance = adapter.addBalance(ADDR, 250L);
    assertEquals(250L, balance);
    assertEquals(250L, adapter.getBalance(ADDR));
    assertEquals(250L, adapter.getAccount(ADDR).getBalance());
  }

  @Test
  public void storageWriteThenReadIsVisibleInOverlay() {
    reader.account = ArchiveReadResult.present(account(1L));
    DataWord key = new DataWord(new byte[] {7});
    DataWord value = new DataWord(new byte[] {0, 9});
    adapter.putStorageValue(ADDR, key, value);
    assertEquals(value, adapter.getStorageValue(ADDR, key));
  }

  @Test
  public void transientStorageOverlayMergesFromChildToParent() {
    byte[] key = new DataWord(new byte[] {7}).getData();
    byte[] value = new DataWord(new byte[] {9}).getData();
    ArchiveRepositoryAdapter child = (ArchiveRepositoryAdapter) adapter.newRepositoryChild();

    child.updateTransientStorageValue(ADDR, key, value);
    value[31] = 0x0A;

    assertArrayEquals(new DataWord(new byte[] {9}).getData(),
        child.getTransientStorageValue(ADDR, key));
    assertNull(adapter.getTransientStorageValue(ADDR, key));

    child.commit();

    assertArrayEquals(new DataWord(new byte[] {9}).getData(),
        adapter.getTransientStorageValue(ADDR, key));
  }

  @Test
  public void accountOverlayWriteIsReadBack() {
    adapter.putAccountValue(ADDR, account(500L));
    assertEquals(500L, adapter.getBalance(ADDR));
    assertEquals(500L, adapter.getAccount(ADDR).getBalance());
  }

  @Test
  public void childWritesAreInvisibleToParentUntilCommit() {
    // The account must exist so the parent's archive storage path is reachable (returns missing).
    reader.account = ArchiveReadResult.present(account(1L));
    ArchiveRepositoryAdapter child = (ArchiveRepositoryAdapter) adapter.newRepositoryChild();
    DataWord key = new DataWord(new byte[] {3});
    DataWord value = new DataWord(new byte[] {42});
    child.putStorageValue(ADDR, key, value);

    // Child sees its own write; the parent does not (the archive has no such row).
    assertEquals(value, child.getStorageValue(ADDR, key));
    assertNull(adapter.getStorageValue(ADDR, key));

    // Commit merges the child overlay into the parent.
    child.commit();
    assertEquals(value, adapter.getStorageValue(ADDR, key));
  }
}
