package org.tron.core.vm.archive;

import static org.tron.common.math.Maths.addExact;

import com.google.protobuf.ByteString;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
import org.tron.core.store.StoreFactory;
import org.tron.core.store.VmDynamicProperties;
import org.tron.core.vm.config.VMConfig;
import org.tron.core.vm.program.Storage;
import org.tron.core.vm.repository.Key;
import org.tron.core.vm.repository.Repository;
import org.tron.core.vm.repository.Value;
import org.tron.protos.Protocol;

/**
 * {@link Repository} that replays a constant call against the archive state at a fixed historical
 * point. A root instance reads through an L6 {@link ArchiveStateReader}; the VM executes against a
 * {@link #newRepositoryChild() child} whose writes land in an in-memory copy-on-write overlay and
 * are discarded at the top (a constant call persists nothing). Reads resolve overlay first, then
 * the parent chain, then the archive root; a value absent from the archive is reported absent,
 * never read from the latest stores.
 *
 * <p>Hard-fork / proposal flags are NOT read here: they come from the thread-local {@link VMConfig}
 * snapshot the executor installs (L8 Slice 3). Domains the archive does not cover in P0
 * (delegation, votes, witness, asset-issue, resource accounting, the live dynamic-properties store)
 * and account-creation paths that need block context throw
 * {@link UnsupportedHistoricalStateException} rather than fall back to latest.
 */
public class ArchiveRepositoryAdapter implements Repository {

  private static final String NEEDS_BLOCK_CTX = " needs block context (L8 Slice 3b)";

  // Root: reader + vmProperties set, parent null. Child: parent set, reader/vmProperties null.
  private final ArchiveStateReader reader;
  private final VmDynamicProperties vmProperties;
  private final ArchiveRepositoryAdapter parent;

  // Copy-on-write overlay. containsKey decides; a null value marks a deletion at this level.
  private final Map<Key, AccountCapsule> accounts = new HashMap<>();
  private final Map<Key, byte[]> codes = new HashMap<>();
  private final Map<Key, ContractCapsule> contracts = new HashMap<>();
  private final Map<Key, Map<DataWord, DataWord>> storage = new HashMap<>();
  private final Set<Key> newContracts = new HashSet<>();

  public ArchiveRepositoryAdapter(ArchiveStateReader reader, VmDynamicProperties vmProperties) {
    this.reader = reader;
    this.vmProperties = vmProperties;
    this.parent = null;
  }

  private ArchiveRepositoryAdapter(ArchiveRepositoryAdapter parent) {
    this.reader = null;
    this.vmProperties = null;
    this.parent = parent;
  }

  @Override
  public Repository newRepositoryChild() {
    return new ArchiveRepositoryAdapter(this);
  }

  // ---------------------------------------------------------------------------------------------
  // Reads: overlay -> parent -> archive root.
  // ---------------------------------------------------------------------------------------------

  @Override
  public AccountCapsule getAccount(byte[] address) {
    Key key = Key.create(address);
    if (accounts.containsKey(key)) {
      AccountCapsule account = accounts.get(key);
      return account == null ? null : new AccountCapsule(account.getData());
    }
    if (parent != null) {
      return parent.getAccount(address);
    }
    return present(read(() -> reader.getAccount(address), "account"));
  }

  @Override
  public long getBalance(byte[] address) {
    AccountCapsule account = getAccount(address);
    return account == null ? 0L : account.getBalance();
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
  public byte[] getCode(byte[] address) {
    Key key = Key.create(address);
    if (codes.containsKey(key)) {
      byte[] code = codes.get(key);
      return code == null ? null : code.clone();
    }
    if (parent != null) {
      return parent.getCode(address);
    }
    return present(read(() -> reader.getCode(address), "code"));
  }

  @Override
  public ContractCapsule getContract(byte[] address) {
    Key key = Key.create(address);
    if (contracts.containsKey(key)) {
      ContractCapsule contract = contracts.get(key);
      return contract == null ? null : new ContractCapsule(contract.getData());
    }
    if (parent != null) {
      return parent.getContract(address);
    }
    return present(read(() -> reader.getContract(address), "contract"));
  }

  @Override
  public DataWord getStorageValue(byte[] address, DataWord key) {
    byte[] tronAddress = TransactionTrace.convertToTronAddress(address);
    Map<DataWord, DataWord> slots = storage.get(Key.create(tronAddress));
    if (slots != null && slots.containsKey(key)) {
      DataWord value = slots.get(key);
      return value == null ? null : new DataWord(value.getData());
    }
    if (parent != null) {
      return parent.getStorageValue(address, key);
    }
    if (getAccount(tronAddress) == null) {
      return null;
    }
    ArchiveReadResult<byte[]> row = read(() -> reader.getStorage(tronAddress, key.getData()),
        "storage");
    return row.isPresent() ? new DataWord(row.getValue()) : null;
  }

  @Override
  public boolean isNewContract(byte[] address) {
    Key key = Key.create(address);
    if (newContracts.contains(key)) {
      return true;
    }
    return parent != null && parent.isNewContract(address);
  }

  @Override
  public VmDynamicProperties getVmDynamicProperties() {
    return parent != null ? parent.getVmDynamicProperties() : vmProperties;
  }

  // ---------------------------------------------------------------------------------------------
  // Writes: into this level's overlay.
  // ---------------------------------------------------------------------------------------------

  @Override
  public void putAccountValue(byte[] address, AccountCapsule accountCapsule) {
    accounts.put(Key.create(address), accountCapsule);
  }

  @Override
  public void updateAccount(byte[] address, AccountCapsule accountCapsule) {
    accounts.put(Key.create(address), accountCapsule);
  }

  @Override
  public long addBalance(byte[] address, long value) {
    AccountCapsule account = getAccount(address);
    if (account == null) {
      // A value-bearing CALL to a fresh address materializes it in the overlay (discarded at the
      // top), mirroring RepositoryImpl, so a read-only call that forwards value does not abort.
      account = createAccount(address, Protocol.AccountType.Normal);
    }
    account.setBalance(addExact(account.getBalance(), value, VMConfig.disableJavaLangMath()));
    accounts.put(Key.create(address), account);
    return account.getBalance();
  }

  @Override
  public void saveCode(byte[] address, byte[] code) {
    codes.put(Key.create(address), code);
  }

  @Override
  public void createContract(byte[] address, ContractCapsule contractCapsule) {
    contracts.put(Key.create(address), contractCapsule);
  }

  @Override
  public void updateContract(byte[] address, ContractCapsule contractCapsule) {
    contracts.put(Key.create(address), contractCapsule);
  }

  @Override
  public void deleteContract(byte[] address) {
    contracts.put(Key.create(address), null);
  }

  @Override
  public void putNewContract(byte[] address) {
    newContracts.add(Key.create(address));
  }

  @Override
  public void putStorageValue(byte[] address, DataWord key, DataWord value) {
    byte[] tronAddress = TransactionTrace.convertToTronAddress(address);
    storage.computeIfAbsent(Key.create(tronAddress), k -> new HashMap<>())
        .put(key.clone(), value.clone());
  }

  // ---------------------------------------------------------------------------------------------
  // Commit: merge this overlay into the parent; the root discards (constant calls persist nothing).
  // ---------------------------------------------------------------------------------------------

  @Override
  public void commit() {
    if (parent == null) {
      return;
    }
    accounts.forEach((key, account) -> {
      if (account != null) {
        parent.putAccountValue(key.getData(), account);
      }
    });
    codes.forEach((key, code) -> parent.saveCode(key.getData(), code));
    contracts.forEach((key, contract) -> {
      if (contract == null) {
        parent.deleteContract(key.getData());
      } else {
        parent.updateContract(key.getData(), contract);
      }
    });
    newContracts.forEach(key -> parent.putNewContract(key.getData()));
    storage.forEach((addrKey, slots) ->
        slots.forEach((slot, value) -> parent.putStorageValue(addrKey.getData(), slot, value)));
  }

  @Override
  public void setParent(Repository deposit) {
    throw unsupported("setParent (the archive overlay sets its parent at construction)");
  }

  // ---------------------------------------------------------------------------------------------
  // Account creation: needs the historical block context; wired in L8 Slice 3b.
  // ---------------------------------------------------------------------------------------------

  // Account creation materializes a fresh zero-balance account in the overlay (discarded at the top
  // of a constant call). The persisted-only fields RepositoryImpl derives from the store (creation
  // time, default permission) do not affect a read-only result, so a minimal account is safe.
  @Override
  public AccountCapsule createAccount(byte[] address, Protocol.AccountType type) {
    AccountCapsule account = new AccountCapsule(Protocol.Account.newBuilder()
        .setAddress(ByteString.copyFrom(address)).setType(type).build());
    accounts.put(Key.create(address), account);
    return account;
  }

  @Override
  public AccountCapsule createAccount(byte[] address, String accountName,
      Protocol.AccountType type) {
    AccountCapsule account = new AccountCapsule(Protocol.Account.newBuilder()
        .setAddress(ByteString.copyFrom(address))
        .setAccountName(ByteString.copyFromUtf8(accountName)).setType(type).build());
    accounts.put(Key.create(address), account);
    return account;
  }

  @Override
  public AccountCapsule createNormalAccount(byte[] address) {
    return createAccount(address, Protocol.AccountType.Normal);
  }

  // ---------------------------------------------------------------------------------------------
  // Domains outside P0 archive coverage / unused on the constant-call path: fail fast.
  // ---------------------------------------------------------------------------------------------

  @Override
  public DynamicPropertiesStore getDynamicPropertiesStore() {
    throw unsupported("getDynamicPropertiesStore (use getVmDynamicProperties on the archive path)");
  }

  @Override
  public Storage getStorage(byte[] address) {
    throw unsupported("getStorage object access (the VM reads via getStorageValue)");
  }

  @Override
  public byte[] getTransientStorageValue(byte[] address, byte[] key) {
    throw unsupported("transient storage");
  }

  @Override
  public void updateTransientStorageValue(byte[] address, byte[] key, byte[] value) {
    throw unsupported("transient storage");
  }

  /**
   * P0 does not archive per-contract dynamic-energy usage. Return a fresh capsule at the historical
   * cycle so the energy factor degrades to neutral (zero usage -> DYNAMIC_ENERGY_FACTOR_DECIMAL),
   * rather than aborting; the factor only affects energy accounting, which a constant call does not
   * charge. Must be non-null: the VM dereferences it directly in addContextContractUsage.
   */
  @Override
  public ContractStateCapsule getContractState(byte[] address) {
    return new ContractStateCapsule(getVmDynamicProperties().getCurrentCycleNumber());
  }

  @Override
  public void updateContractState(byte[] address, ContractStateCapsule contractStateCapsule) {
    // The dynamic-energy factor write is discarded on the read-only historical path.
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
  public WitnessCapsule getWitness(byte[] address) {
    throw unsupported("witness reads");
  }

  @Override
  public byte[] getBlackHoleAddress() {
    throw unsupported("black-hole address" + NEEDS_BLOCK_CTX);
  }

  /**
   * Block hashes are immutable canonical-chain data (not mutable latest state), so serving them
   * from the live block store is correct for historical CHAINID (block 0) and BLOCKHASH (an
   * ancestor of the executing block) and is NOT a latest-state leak.
   */
  @Override
  public BlockCapsule getBlockByNum(long num) {
    try {
      return StoreFactory.getInstance().getChainBaseManager().getBlockByNum(num);
    } catch (Exception e) {
      throw new UnsupportedHistoricalStateException("historical block " + num + " unavailable", e);
    }
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
    throw unsupported("head-slot reads" + NEEDS_BLOCK_CTX);
  }

  @Override
  public long getSlotByTimestampMs(long timestamp) {
    throw unsupported("slot-by-timestamp reads" + NEEDS_BLOCK_CTX);
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

  @Override
  public long addTokenBalance(byte[] address, byte[] tokenId, long value) {
    throw unsupported("addTokenBalance" + NEEDS_BLOCK_CTX);
  }

  // ---------------------------------------------------------------------------------------------
  // Key/Value merge variants (used by RepositoryImpl.commit, not by this overlay) + uncovered puts.
  // ---------------------------------------------------------------------------------------------

  @Override
  public void putAccount(Key key, Value value) {
    throw unsupported("putAccount(Key, Value)");
  }

  @Override
  public void putCode(Key key, Value value) {
    throw unsupported("putCode(Key, Value)");
  }

  @Override
  public void putContract(Key key, Value value) {
    throw unsupported("putContract(Key, Value)");
  }

  @Override
  public void putContractState(Key key, Value value) {
    throw unsupported("putContractState(Key, Value)");
  }

  @Override
  public void putStorage(Key key, Storage cache) {
    throw unsupported("putStorage(Key, Storage)");
  }

  @Override
  public void putDynamicProperty(Key key, Value value) {
    throw unsupported("putDynamicProperty");
  }

  @Override
  public void putDelegatedResource(Key key, Value value) {
    throw unsupported("putDelegatedResource");
  }

  @Override
  public void putVotes(Key key, Value value) {
    throw unsupported("putVotes");
  }

  @Override
  public void putDelegation(Key key, Value value) {
    throw unsupported("putDelegation");
  }

  @Override
  public void putDelegatedResourceAccountIndex(Key key, Value value) {
    throw unsupported("putDelegatedResourceAccountIndex");
  }

  @Override
  public void putTransientStorageValue(Key address, Key key, Value value) {
    throw unsupported("putTransientStorageValue");
  }

  @Override
  public void updateDynamicProperty(byte[] word, BytesCapsule bytesCapsule) {
    throw unsupported("updateDynamicProperty");
  }

  @Override
  public void updateDelegatedResource(byte[] word, DelegatedResourceCapsule capsule) {
    throw unsupported("updateDelegatedResource");
  }

  @Override
  public void updateVotes(byte[] word, VotesCapsule votesCapsule) {
    throw unsupported("updateVotes");
  }

  @Override
  public void updateBeginCycle(byte[] word, long cycle) {
    throw unsupported("updateBeginCycle");
  }

  @Override
  public void updateEndCycle(byte[] word, long cycle) {
    throw unsupported("updateEndCycle");
  }

  @Override
  public void updateAccountVote(byte[] word, long cycle, AccountCapsule accountCapsule) {
    throw unsupported("updateAccountVote");
  }

  @Override
  public void updateDelegation(byte[] word, BytesCapsule bytesCapsule) {
    throw unsupported("updateDelegation");
  }

  @Override
  public void updateDelegatedResourceAccountIndex(byte[] word,
      DelegatedResourceAccountIndexCapsule capsule) {
    throw unsupported("updateDelegatedResourceAccountIndex");
  }

  @Override
  public void addTotalNetWeight(long amount) {
    throw unsupported("addTotalNetWeight");
  }

  @Override
  public void addTotalEnergyWeight(long amount) {
    throw unsupported("addTotalEnergyWeight");
  }

  @Override
  public void addTotalTronPowerWeight(long amount) {
    throw unsupported("addTotalTronPowerWeight");
  }

  @Override
  public void saveTotalNetWeight(long totalNetWeight) {
    throw unsupported("saveTotalNetWeight");
  }

  @Override
  public void saveTotalEnergyWeight(long totalEnergyWeight) {
    throw unsupported("saveTotalEnergyWeight");
  }

  @Override
  public void saveTotalTronPowerWeight(long totalTronPowerWeight) {
    throw unsupported("saveTotalTronPowerWeight");
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
}
