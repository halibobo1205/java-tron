package org.tron.core.vm.archive;

import org.apache.commons.lang3.tuple.Pair;
import org.tron.common.runtime.vm.DataWord;
import org.tron.common.utils.ByteUtil;
import org.tron.core.archive.reader.ArchiveReadResult;
import org.tron.core.archive.reader.ArchiveReaderException;
import org.tron.core.archive.reader.ArchiveStateReader;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.AssetIssueCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.BytesCapsule;
import org.tron.core.capsule.ContractCapsule;
import org.tron.core.capsule.ContractStateCapsule;
import org.tron.core.capsule.DelegatedResourceAccountIndexCapsule;
import org.tron.core.capsule.DelegatedResourceCapsule;
import org.tron.core.capsule.VotesCapsule;
import org.tron.core.capsule.WitnessCapsule;
import org.tron.core.db.TransactionTrace;
import org.tron.core.store.AssetIssueStore;
import org.tron.core.store.AssetIssueV2Store;
import org.tron.core.store.DelegationStore;
import org.tron.core.store.DynamicPropertiesStore;
import org.tron.core.store.VmDynamicProperties;
import org.tron.core.vm.program.Storage;
import org.tron.core.vm.repository.Key;
import org.tron.core.vm.repository.Repository;
import org.tron.core.vm.repository.Value;
import org.tron.protos.Protocol;

/**
 * Root {@link Repository} that serves the TVM's state reads from the archive at a fixed historical
 * point (L6 {@link ArchiveStateReader}) instead of the latest stores, so a constant call replays
 * against the state as it was at the target block. Dynamic-properties / hard-fork flags come from a
 * historical {@link VmDynamicProperties}.
 *
 * <p>Three buckets: archive-backed reads (account / balance / contract / code / storage / token
 * balance) map to the reader; domains the archive does not cover in P0 (delegation, votes, witness,
 * asset-issue, resource accounting, the live dynamic-properties store) throw
 * {@link UnsupportedHistoricalStateException}, never a silent latest fallback. Writes and the
 * copy-on-write overlay (child repository, storage object, commit) are deferred to L8 Slice 3; till
 * then they throw, so this class is exercised only as a read source.
 */
public class ArchiveRepositoryAdapter implements Repository {

  private static final String SLICE3 = " is implemented in L8 Slice 3";

  private final ArchiveStateReader reader;
  private final VmDynamicProperties vmProperties;

  public ArchiveRepositoryAdapter(ArchiveStateReader reader, VmDynamicProperties vmProperties) {
    this.reader = reader;
    this.vmProperties = vmProperties;
  }

  // ---------------------------------------------------------------------------------------------
  // Archive-backed reads.
  // ---------------------------------------------------------------------------------------------

  @Override
  public AccountCapsule getAccount(byte[] address) {
    return present(read(() -> reader.getAccount(address), "account"));
  }

  @Override
  public long getBalance(byte[] address) {
    AccountCapsule account = getAccount(address);
    return account == null ? 0L : account.getBalance();
  }

  @Override
  public ContractCapsule getContract(byte[] address) {
    return present(read(() -> reader.getContract(address), "contract"));
  }

  @Override
  public byte[] getCode(byte[] address) {
    return present(read(() -> reader.getCode(address), "code"));
  }

  @Override
  public DataWord getStorageValue(byte[] address, DataWord key) {
    byte[] tronAddress = TransactionTrace.convertToTronAddress(address);
    if (getAccount(tronAddress) == null) {
      return null;
    }
    ArchiveReadResult<byte[]> row = read(() -> reader.getStorage(tronAddress, key.getData()),
        "storage");
    return row.isPresent() ? new DataWord(row.getValue()) : null;
  }

  @Override
  public long getTokenBalance(byte[] address, byte[] tokenId) {
    AccountCapsule account = getAccount(address);
    if (account == null) {
      return 0L;
    }
    return account.getAssetV2(new String(ByteUtil.stripLeadingZeroes(tokenId)));
  }

  @Override
  public VmDynamicProperties getVmDynamicProperties() {
    return vmProperties;
  }

  /**
   * No contract is "newly created" when read from the archive root; the per-call overlay (Slice 3)
   * tracks contracts created during the current call.
   */
  @Override
  public boolean isNewContract(byte[] address) {
    return false;
  }

  // ---------------------------------------------------------------------------------------------
  // Domains outside P0 archive coverage: fail fast, never read latest.
  // ---------------------------------------------------------------------------------------------

  @Override
  public DynamicPropertiesStore getDynamicPropertiesStore() {
    throw unsupported("getDynamicPropertiesStore (use getVmDynamicProperties on the archive path)");
  }

  @Override
  public AssetIssueCapsule getAssetIssue(byte[] tokenId) {
    throw unsupported("asset-issue reads");
  }

  @Override
  public AssetIssueV2Store getAssetIssueV2Store() {
    throw unsupported("asset-issue store access");
  }

  @Override
  public AssetIssueStore getAssetIssueStore() {
    throw unsupported("asset-issue store access");
  }

  @Override
  public DelegationStore getDelegationStore() {
    throw unsupported("delegation store access");
  }

  @Override
  public BytesCapsule getDynamicProperty(byte[] bytesKey) {
    throw unsupported("raw dynamic-property reads");
  }

  @Override
  public DelegatedResourceCapsule getDelegatedResource(byte[] key) {
    throw unsupported("delegated-resource reads");
  }

  @Override
  public VotesCapsule getVotes(byte[] address) {
    throw unsupported("votes reads");
  }

  @Override
  public long getBeginCycle(byte[] address) {
    throw unsupported("begin-cycle reads");
  }

  @Override
  public long getEndCycle(byte[] address) {
    throw unsupported("end-cycle reads");
  }

  @Override
  public AccountCapsule getAccountVote(long cycle, byte[] address) {
    throw unsupported("account-vote reads");
  }

  @Override
  public BytesCapsule getDelegation(Key key) {
    throw unsupported("delegation reads");
  }

  @Override
  public DelegatedResourceAccountIndexCapsule getDelegatedResourceAccountIndex(byte[] key) {
    throw unsupported("delegated-resource-account-index reads");
  }

  @Override
  public ContractStateCapsule getContractState(byte[] address) {
    throw unsupported("contract-state reads");
  }

  @Override
  public WitnessCapsule getWitness(byte[] address) {
    throw unsupported("witness reads");
  }

  @Override
  public byte[] getBlackHoleAddress() {
    throw unsupported("black-hole address");
  }

  @Override
  public BlockCapsule getBlockByNum(long num) {
    throw unsupported("block reads (BLOCKHASH)" + SLICE3);
  }

  @Override
  public long getTotalNetWeight() {
    throw unsupported("total-net-weight reads");
  }

  @Override
  public long getTotalEnergyWeight() {
    throw unsupported("total-energy-weight reads");
  }

  @Override
  public long getTotalTronPowerWeight() {
    throw unsupported("total-tron-power-weight reads");
  }

  @Override
  public long getHeadSlot() {
    throw unsupported("head-slot reads");
  }

  @Override
  public long getSlotByTimestampMs(long timestamp) {
    throw unsupported("slot-by-timestamp reads");
  }

  @Override
  public long getAccountLeftEnergyFromFreeze(AccountCapsule accountCapsule) {
    throw unsupported("energy-from-freeze accounting");
  }

  @Override
  public long getAccountEnergyUsage(AccountCapsule accountCapsule) {
    throw unsupported("energy-usage accounting");
  }

  @Override
  public Pair<Long, Long> getAccountEnergyUsageBalanceAndRestoreSeconds(AccountCapsule account) {
    throw unsupported("energy-usage accounting");
  }

  @Override
  public Pair<Long, Long> getAccountNetUsageBalanceAndRestoreSeconds(AccountCapsule account) {
    throw unsupported("net-usage accounting");
  }

  @Override
  public long calculateGlobalEnergyLimit(AccountCapsule accountCapsule) {
    throw unsupported("global-energy-limit accounting");
  }

  // ---------------------------------------------------------------------------------------------
  // Writes + copy-on-write overlay: deferred to L8 Slice 3 (executor wiring).
  // ---------------------------------------------------------------------------------------------

  @Override
  public Storage getStorage(byte[] address) {
    throw overlay("getStorage");
  }

  @Override
  public byte[] getTransientStorageValue(byte[] address, byte[] key) {
    throw overlay("transient storage");
  }

  @Override
  public Repository newRepositoryChild() {
    throw overlay("newRepositoryChild");
  }

  @Override
  public void setParent(Repository deposit) {
    throw overlay("setParent");
  }

  @Override
  public void commit() {
    throw overlay("commit");
  }

  @Override
  public AccountCapsule createAccount(byte[] address, Protocol.AccountType type) {
    throw overlay("createAccount");
  }

  @Override
  public AccountCapsule createAccount(byte[] address, String accountName,
      Protocol.AccountType type) {
    throw overlay("createAccount");
  }

  @Override
  public AccountCapsule createNormalAccount(byte[] address) {
    throw overlay("createNormalAccount");
  }

  @Override
  public void deleteContract(byte[] address) {
    throw overlay("deleteContract");
  }

  @Override
  public void createContract(byte[] address, ContractCapsule contractCapsule) {
    throw overlay("createContract");
  }

  @Override
  public void updateContract(byte[] address, ContractCapsule contractCapsule) {
    throw overlay("updateContract");
  }

  @Override
  public void updateContractState(byte[] address, ContractStateCapsule contractStateCapsule) {
    throw overlay("updateContractState");
  }

  @Override
  public void putNewContract(byte[] address) {
    throw overlay("putNewContract");
  }

  @Override
  public void updateAccount(byte[] address, AccountCapsule accountCapsule) {
    throw overlay("updateAccount");
  }

  @Override
  public void updateDynamicProperty(byte[] word, BytesCapsule bytesCapsule) {
    throw overlay("updateDynamicProperty");
  }

  @Override
  public void updateDelegatedResource(byte[] word, DelegatedResourceCapsule capsule) {
    throw overlay("updateDelegatedResource");
  }

  @Override
  public void updateVotes(byte[] word, VotesCapsule votesCapsule) {
    throw overlay("updateVotes");
  }

  @Override
  public void updateBeginCycle(byte[] word, long cycle) {
    throw overlay("updateBeginCycle");
  }

  @Override
  public void updateEndCycle(byte[] word, long cycle) {
    throw overlay("updateEndCycle");
  }

  @Override
  public void updateAccountVote(byte[] word, long cycle, AccountCapsule accountCapsule) {
    throw overlay("updateAccountVote");
  }

  @Override
  public void updateDelegation(byte[] word, BytesCapsule bytesCapsule) {
    throw overlay("updateDelegation");
  }

  @Override
  public void updateDelegatedResourceAccountIndex(byte[] word,
      DelegatedResourceAccountIndexCapsule capsule) {
    throw overlay("updateDelegatedResourceAccountIndex");
  }

  @Override
  public void updateTransientStorageValue(byte[] address, byte[] key, byte[] value) {
    throw overlay("updateTransientStorageValue");
  }

  @Override
  public void saveCode(byte[] address, byte[] code) {
    throw overlay("saveCode");
  }

  @Override
  public void putStorageValue(byte[] address, DataWord key, DataWord value) {
    throw overlay("putStorageValue");
  }

  @Override
  public long addBalance(byte[] address, long value) {
    throw overlay("addBalance");
  }

  @Override
  public long addTokenBalance(byte[] address, byte[] tokenId, long value) {
    throw overlay("addTokenBalance");
  }

  @Override
  public void putAccount(Key key, Value value) {
    throw overlay("putAccount");
  }

  @Override
  public void putCode(Key key, Value value) {
    throw overlay("putCode");
  }

  @Override
  public void putContract(Key key, Value value) {
    throw overlay("putContract");
  }

  @Override
  public void putContractState(Key key, Value value) {
    throw overlay("putContractState");
  }

  @Override
  public void putStorage(Key key, Storage cache) {
    throw overlay("putStorage");
  }

  @Override
  public void putAccountValue(byte[] address, AccountCapsule accountCapsule) {
    throw overlay("putAccountValue");
  }

  @Override
  public void putDynamicProperty(Key key, Value value) {
    throw overlay("putDynamicProperty");
  }

  @Override
  public void putDelegatedResource(Key key, Value value) {
    throw overlay("putDelegatedResource");
  }

  @Override
  public void putVotes(Key key, Value value) {
    throw overlay("putVotes");
  }

  @Override
  public void putDelegation(Key key, Value value) {
    throw overlay("putDelegation");
  }

  @Override
  public void putDelegatedResourceAccountIndex(Key key, Value value) {
    throw overlay("putDelegatedResourceAccountIndex");
  }

  @Override
  public void putTransientStorageValue(Key address, Key key, Value value) {
    throw overlay("putTransientStorageValue");
  }

  @Override
  public void addTotalNetWeight(long amount) {
    throw overlay("addTotalNetWeight");
  }

  @Override
  public void addTotalEnergyWeight(long amount) {
    throw overlay("addTotalEnergyWeight");
  }

  @Override
  public void addTotalTronPowerWeight(long amount) {
    throw overlay("addTotalTronPowerWeight");
  }

  @Override
  public void saveTotalNetWeight(long totalNetWeight) {
    throw overlay("saveTotalNetWeight");
  }

  @Override
  public void saveTotalEnergyWeight(long totalEnergyWeight) {
    throw overlay("saveTotalEnergyWeight");
  }

  @Override
  public void saveTotalTronPowerWeight(long totalTronPowerWeight) {
    throw overlay("saveTotalTronPowerWeight");
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers.
  // ---------------------------------------------------------------------------------------------

  private interface ReaderCall<T> {
    ArchiveReadResult<T> call() throws ArchiveReaderException;
  }

  private static <T> T present(ArchiveReadResult<T> result) {
    return result.isPresent() ? result.getValue() : null;
  }

  private <T> ArchiveReadResult<T> read(ReaderCall<T> call, String what) {
    try {
      return call.call();
    } catch (ArchiveReaderException e) {
      throw new UnsupportedHistoricalStateException("archive read failed for " + what, e);
    }
  }

  private static UnsupportedHistoricalStateException unsupported(String what) {
    return new UnsupportedHistoricalStateException(
        "historical archive call does not support " + what);
  }

  private static UnsupportedHistoricalStateException overlay(String what) {
    return new UnsupportedHistoricalStateException(what + SLICE3);
  }
}
