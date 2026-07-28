package org.tron.core.vm.archive;

import static org.tron.common.math.Maths.addExact;
import static org.tron.common.math.Maths.max;
import static org.tron.common.math.Maths.min;
import static org.tron.common.math.Maths.round;
import static org.tron.core.config.Parameter.ChainConstant.BLOCK_PRODUCED_INTERVAL;
import static org.tron.core.config.Parameter.ChainConstant.PRECISION;
import static org.tron.core.config.Parameter.ChainConstant.TRX_PRECISION;
import static org.tron.core.config.Parameter.ChainConstant.WINDOW_SIZE_MS;
import static org.tron.core.config.Parameter.ChainConstant.WINDOW_SIZE_PRECISION;

import com.google.protobuf.ByteString;
import com.google.protobuf.MessageLite;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.tuple.Pair;
import org.bouncycastle.util.encoders.Hex;
import org.tron.common.math.StrictMathWrapper;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.runtime.vm.DataWord;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.ByteUtil;
import org.tron.core.archive.reader.ArchiveReadResult;
import org.tron.core.archive.reader.ArchiveReaderException;
import org.tron.core.archive.reader.ArchiveStateReader;
import org.tron.core.archive.query.QueryContext;
import org.tron.core.archive.query.QueryContextHolder;
import org.tron.core.archive.txnum.ArchiveBlockRange;
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
import org.tron.core.vm.config.VMConfig;
import org.tron.core.vm.program.Storage;
import org.tron.core.vm.repository.Key;
import org.tron.core.vm.repository.Repository;
import org.tron.core.vm.repository.Value;
import org.tron.protos.Protocol;
import org.tron.protos.contract.Common.ResourceCode;

/**
 * {@link Repository} that replays TVM execution against archive state at a fixed historical point.
 * A root instance reads through an L6 {@link ArchiveStateReader}; the VM executes against a
 * {@link #newRepositoryChild() child} whose writes land in an in-memory copy-on-write overlay and
 * are discarded at the top (a constant call persists nothing). Reads resolve overlay first, then
 * the parent chain, then the archive root; a value absent from the archive is reported absent,
 * never read from the latest stores.
 *
 * <p>Hard-fork / proposal flags come from the thread-local {@link VMConfig} snapshot the executor
 * installs. Mutable VM side effects for votes, delegation, and resource weights are isolated in
 * the same request overlay. Domains the archive does not cover and account-creation paths that
 * need unavailable block context throw
 * {@link UnsupportedHistoricalStateException} rather than fall back to latest.
 */
public class ArchiveRepositoryAdapter implements Repository {

  private static final String NEEDS_BLOCK_CTX = " needs block context (L8 Slice 3b)";
  // BLOCKHASH exposes 256 ancestors and CHAINID additionally pins block zero at mature heights.
  private static final int MAX_BLOCK_HASH_CACHE_ENTRIES = 257;
  private static final long OVERLAY_ENTRY_OVERHEAD_BYTES = 192L;
  private static final long ENERGY_WINDOW_SIZE = WINDOW_SIZE_MS / BLOCK_PRODUCED_INTERVAL;
  private static final byte[] TOTAL_NET_WEIGHT =
      "TOTAL_NET_WEIGHT".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] TOTAL_ENERGY_WEIGHT =
      "TOTAL_ENERGY_WEIGHT".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] TOTAL_TRON_POWER_WEIGHT =
      "TOTAL_TRON_POWER_WEIGHT".getBytes(StandardCharsets.US_ASCII);

  // Root: reader + vmProperties set, parent null. Child: parent set, reader/vmProperties null.
  private final ArchiveStateReader reader;
  private final VmDynamicProperties vmProperties;
  private final ArchiveRepositoryAdapter parent;
  private final boolean genesisComplete;
  private final Map<Long, byte[]> blockHashCache;
  private final byte[] blackHoleAddress;

  // Copy-on-write overlay. containsKey decides; a null value marks a deletion at this level.
  private final Map<Key, AccountCapsule> accounts = new HashMap<>();
  private final Map<Key, byte[]> codes = new HashMap<>();
  private final Map<Key, ContractCapsule> contracts = new HashMap<>();
  private final Map<Key, ContractStateCapsule> contractStates = new HashMap<>();
  private final Map<Key, BytesCapsule> dynamicProperties = new HashMap<>();
  private final Map<Key, VotesCapsule> votes = new HashMap<>();
  private final Map<Key, BytesCapsule> delegations = new HashMap<>();
  private final Map<Key, Map<DataWord, DataWord>> storage = new HashMap<>();
  private final Map<Key, Map<Key, Long>> tokenBalances = new HashMap<>();
  private final Map<Key, Map<Key, byte[]>> transientStorage = new HashMap<>();
  private final Set<Key> newContracts = new HashSet<>();

  public ArchiveRepositoryAdapter(ArchiveStateReader reader, VmDynamicProperties vmProperties) {
    this(reader, vmProperties, true);
  }

  public ArchiveRepositoryAdapter(ArchiveStateReader reader, VmDynamicProperties vmProperties,
      boolean genesisComplete) {
    this(reader, vmProperties, genesisComplete, null);
  }

  public ArchiveRepositoryAdapter(ArchiveStateReader reader, VmDynamicProperties vmProperties,
      boolean genesisComplete, byte[] blackHoleAddress) {
    this.reader = reader;
    this.vmProperties = vmProperties;
    this.parent = null;
    this.genesisComplete = genesisComplete;
    this.blackHoleAddress =
        blackHoleAddress == null ? null : blackHoleAddress.clone();
    this.blockHashCache = new LinkedHashMap<Long, byte[]>(16, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest) {
        return size() > MAX_BLOCK_HASH_CACHE_ENTRIES;
      }
    };
  }

  private ArchiveRepositoryAdapter(ArchiveRepositoryAdapter parent) {
    this.reader = null;
    this.vmProperties = null;
    this.parent = parent;
    this.genesisComplete = parent.genesisComplete;
    this.blockHashCache = parent.blockHashCache;
    this.blackHoleAddress = parent.blackHoleAddress;
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
      return account == null ? null : copyAccount(account);
    }
    if (parent != null) {
      return parent.getAccount(address);
    }
    return copyAccount(present(read(() -> reader.getAccount(address), "account"), "account"));
  }

  @Override
  public long getBalance(byte[] address) {
    AccountCapsule account = getAccount(address);
    return account == null ? 0L : account.getBalance();
  }

  @Override
  public long getTokenBalance(byte[] address, byte[] tokenId) {
    byte[] tokenIdWithoutLeadingZero = canonicalTokenId(tokenId);
    if (tokenIdWithoutLeadingZero.length == 0) {
      return 0L;
    }
    Key accountKey = Key.create(address);
    Key tokenKey = Key.create(tokenIdWithoutLeadingZero);
    AccountCapsule account = getAccount(address);
    if (account == null) {
      return 0L;
    }
    Map<Key, Long> balances = tokenBalances.get(accountKey);
    if (balances != null && balances.containsKey(tokenKey)) {
      return balances.get(tokenKey);
    }
    if (parent != null) {
      return parent.getTokenBalance(address, tokenId);
    }
    ArchiveReadResult<byte[]> row = read(
        () -> reader.getAccountAsset(address, tokenIdWithoutLeadingZero), "account-asset");
    requireKnown(row, "account-asset");
    return row.isPresent() ? ByteArray.toLong(row.getValue()) : 0L;
  }

  @Override
  public Map<String, Long> getTokenBalances(byte[] address) {
    Key accountKey = Key.create(address);
    if (getAccount(address) == null) {
      return Collections.emptyMap();
    }
    Map<String, Long> base = parent == null
        ? readValue(() -> reader.getAccountAssets(address), "account-assets")
        : parent.getTokenBalances(address);
    Map<Key, Long> local = tokenBalances.get(accountKey);
    if (local == null || local.isEmpty()) {
      return base;
    }
    reserveTokenBalanceMap(base);
    Map<String, Long> visible = new LinkedHashMap<>(base);
    local.forEach((tokenKey, balance) -> {
      String tokenId = new String(tokenKey.getData(), StandardCharsets.US_ASCII);
      if (balance == null || balance <= 0L) {
        visible.remove(tokenId);
      } else {
        visible.put(tokenId, balance);
      }
    });
    return Collections.unmodifiableMap(visible);
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
    byte[] code = present(read(() -> reader.getCode(address), "code"), "code");
    return code == null ? null : code.clone();
  }

  @Override
  public ContractCapsule getContract(byte[] address) {
    Key key = Key.create(address);
    if (contracts.containsKey(key)) {
      ContractCapsule contract = contracts.get(key);
      return contract == null ? null : copyContract(contract);
    }
    if (parent != null) {
      return parent.getContract(address);
    }
    return copyContract(present(read(() -> reader.getContract(address), "contract"), "contract"));
  }

  @Override
  public DataWord getStorageValue(byte[] address, DataWord key) {
    byte[] tronAddress = TransactionTrace.convertToTronAddress(address);
    if (getAccount(tronAddress) == null) {
      return null;
    }
    Map<DataWord, DataWord> slots = storage.get(Key.create(tronAddress));
    if (slots != null && slots.containsKey(key)) {
      DataWord value = slots.get(key);
      return value == null ? null : new DataWord(value.getData());
    }
    if (parent != null) {
      return parent.getStorageValue(address, key);
    }
    ArchiveReadResult<byte[]> row = read(() -> reader.getStorage(tronAddress, key.getData()),
        "storage");
    requireKnown(row, "storage");
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

  @Override
  public boolean isHistoricalArchive() {
    return true;
  }

  // ---------------------------------------------------------------------------------------------
  // Writes: into this level's overlay.
  // ---------------------------------------------------------------------------------------------

  @Override
  public void putAccountValue(byte[] address, AccountCapsule accountCapsule) {
    reserveOverlayMessage(address,
        accountCapsule == null ? null : accountCapsule.getInstance());
    accounts.put(Key.create(address), copyAccount(accountCapsule));
  }

  @Override
  public void updateAccount(byte[] address, AccountCapsule accountCapsule) {
    reserveOverlayMessage(address,
        accountCapsule == null ? null : accountCapsule.getInstance());
    accounts.put(Key.create(address), copyAccount(accountCapsule));
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
    reserveOverlayMessage(address, account.getInstance());
    accounts.put(Key.create(address), account);
    return account.getBalance();
  }

  @Override
  public void saveCode(byte[] address, byte[] code) {
    reserveOverlay(address, code);
    codes.put(Key.create(address), code == null ? null : code.clone());
  }

  @Override
  public void createContract(byte[] address, ContractCapsule contractCapsule) {
    reserveOverlayMessage(address,
        contractCapsule == null ? null : contractCapsule.getInstance());
    reserveOverlay(address);
    Key key = Key.create(address);
    contracts.put(key, copyContract(contractCapsule));
    newContracts.add(key);
  }

  @Override
  public void updateContract(byte[] address, ContractCapsule contractCapsule) {
    reserveOverlayMessage(address,
        contractCapsule == null ? null : contractCapsule.getInstance());
    contracts.put(Key.create(address), copyContract(contractCapsule));
  }

  @Override
  public void deleteContract(byte[] address) {
    reserveOverlay(address);
    reserveOverlay(address);
    reserveOverlay(address);
    Key key = Key.create(address);
    accounts.put(key, null);
    codes.put(key, null);
    contracts.put(key, null);
    storage.remove(key);
    tokenBalances.remove(key);
  }

  @Override
  public void putNewContract(byte[] address) {
    reserveOverlay(address);
    newContracts.add(Key.create(address));
  }

  @Override
  public void putStorageValue(byte[] address, DataWord key, DataWord value) {
    byte[] tronAddress = TransactionTrace.convertToTronAddress(address);
    reserveOverlay(tronAddress, key.getData(), value.getData());
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
    codes.forEach((key, code) -> {
      if (code != null) {
        parent.saveCode(key.getData(), code);
      }
    });
    contracts.forEach((key, contract) -> {
      if (contract == null) {
        parent.deleteContract(key.getData());
      } else {
        parent.updateContract(key.getData(), contract);
      }
    });
    contractStates.forEach((key, state) -> parent.updateContractState(key.getData(), state));
    dynamicProperties.forEach((key, value) ->
        parent.updateDynamicProperty(key.getData(), value));
    votes.forEach((key, value) -> parent.updateVotes(key.getData(), value));
    delegations.forEach((key, value) ->
        parent.updateDelegation(key.getData(), value));
    newContracts.forEach(key -> parent.putNewContract(key.getData()));
    storage.forEach((addrKey, slots) ->
        slots.forEach((slot, value) -> parent.putStorageValue(addrKey.getData(), slot, value)));
    tokenBalances.forEach((addrKey, balances) ->
        balances.forEach((tokenKey, balance) ->
            parent.putTokenBalance(addrKey.getData(), tokenKey.getData(), balance)));
    transientStorage.forEach((addrKey, values) ->
        values.forEach((key, value) -> parent.updateTransientStorageValue(
            addrKey.getData(), key.getData(), value)));
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
    reserveOverlayMessage(address, account.getInstance());
    accounts.put(Key.create(address), copyAccount(account));
    return account;
  }

  @Override
  public AccountCapsule createAccount(byte[] address, String accountName,
      Protocol.AccountType type) {
    AccountCapsule account = new AccountCapsule(Protocol.Account.newBuilder()
        .setAddress(ByteString.copyFrom(address))
        .setAccountName(ByteString.copyFromUtf8(accountName)).setType(type).build());
    reserveOverlayMessage(address, account.getInstance());
    accounts.put(Key.create(address), copyAccount(account));
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
    Key addressKey = Key.create(address);
    Key storageKey = Key.create(key);
    Map<Key, byte[]> values = transientStorage.get(addressKey);
    if (values != null && values.containsKey(storageKey)) {
      byte[] value = values.get(storageKey);
      return value == null ? null : value.clone();
    }
    return parent == null ? null : parent.getTransientStorageValue(address, key);
  }

  @Override
  public void updateTransientStorageValue(byte[] address, byte[] key, byte[] value) {
    reserveOverlay(address, key, value);
    transientStorage.computeIfAbsent(Key.create(address), k -> new HashMap<>())
        .put(Key.create(key), value == null ? null : value.clone());
  }

  @Override
  public ContractStateCapsule getContractState(byte[] address) {
    Key key = Key.create(address);
    if (contractStates.containsKey(key)) {
      ContractStateCapsule state = contractStates.get(key);
      return state == null ? null : copyContractState(state);
    }
    if (parent != null) {
      return parent.getContractState(address);
    }
    ArchiveReadResult<ContractStateCapsule> state =
        read(() -> reader.getContractState(address), "contract-state");
    requireKnown(state, "contract-state");
    return state.isPresent()
        ? new ContractStateCapsule(state.getValue().getData())
        : new ContractStateCapsule(getVmDynamicProperties().getCurrentCycleNumber());
  }

  @Override
  public void updateContractState(byte[] address, ContractStateCapsule contractStateCapsule) {
    reserveOverlayMessage(address,
        contractStateCapsule == null ? null : contractStateCapsule.getInstance());
    contractStates.put(Key.create(address), copyContractState(contractStateCapsule));
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
    Key key = Key.create(bytesKey);
    if (dynamicProperties.containsKey(key)) {
      return copyBytes(dynamicProperties.get(key));
    }
    if (parent != null) {
      return parent.getDynamicProperty(bytesKey);
    }
    ArchiveReadResult<byte[]> row =
        read(() -> reader.getDynamicProperty(bytesKey), "dynamic-property");
    requireKnown(row, "dynamic-property");
    return row.isPresent() ? new BytesCapsule(row.getValue()) : null;
  }

  @Override
  public DelegatedResourceCapsule getDelegatedResource(byte[] key) {
    throw unsupported("delegated-resource reads");
  }

  @Override
  public VotesCapsule getVotes(byte[] address) {
    Key key = Key.create(address);
    if (votes.containsKey(key)) {
      return copyVotes(votes.get(key));
    }
    if (parent != null) {
      return parent.getVotes(address);
    }
    ArchiveReadResult<VotesCapsule> row =
        read(() -> reader.getVotes(address), "votes");
    requireKnown(row, "votes");
    return row.isPresent() ? copyVotes(row.getValue()) : null;
  }

  @Override
  public long getBeginCycle(byte[] address) {
    BytesCapsule value = getDelegation(Key.create(address));
    return value == null ? 0L : delegationLong(value, "begin-cycle");
  }

  @Override
  public long getEndCycle(byte[] address) {
    BytesCapsule value = getDelegation(Key.create(endCycleKey(address)));
    return value == null ? DelegationStore.REMARK : delegationLong(value, "end-cycle");
  }

  @Override
  public AccountCapsule getAccountVote(long cycle, byte[] address) {
    BytesCapsule value = getDelegation(Key.create(accountVoteKey(cycle, address)));
    return value == null ? null : new AccountCapsule(value.getData());
  }

  @Override
  public BytesCapsule getDelegation(Key key) {
    if (delegations.containsKey(key)) {
      return copyBytes(delegations.get(key));
    }
    if (parent != null) {
      return parent.getDelegation(key);
    }
    ArchiveReadResult<byte[]> row =
        read(() -> reader.getDelegation(key.getData()), "delegation");
    requireKnown(row, "delegation");
    return row.isPresent() ? new BytesCapsule(row.getValue()) : null;
  }

  @Override
  public BigInteger getWitnessVi(long cycle, byte[] address) {
    BytesCapsule value = getDelegation(Key.create(witnessViKey(cycle, address)));
    return value == null ? BigInteger.ZERO : new BigInteger(value.getData());
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
    if (blackHoleAddress == null || blackHoleAddress.length == 0) {
      throw unsupported("black-hole address" + NEEDS_BLOCK_CTX);
    }
    return blackHoleAddress.clone();
  }

  @Override
  public BlockCapsule getBlockByNum(long num) {
    throw unsupported("full-block reads" + NEEDS_BLOCK_CTX);
  }

  @Override
  public byte[] getBlockHashByNum(long num) {
    byte[] cached = blockHashCache.get(num);
    if (cached != null) {
      QueryContext queryContext = QueryContextHolder.current();
      if (queryContext != null) {
        queryContext.recordLogicalRead();
        queryContext.recordCacheHit();
      }
      return cached.clone();
    }
    if (parent != null) {
      return parent.getBlockHashByNum(num);
    }
    byte[] blockHash;
    try {
      blockHash = reader.getBlockHash(num);
    } catch (ArchiveReaderException e) {
      throw recordUnsupported(new UnsupportedHistoricalStateException(
          "historical block hash " + num + " unavailable", e));
    }
    if (blockHash == null || blockHash.length != ArchiveBlockRange.BLOCK_HASH_LENGTH) {
      throw recordUnsupported(new UnsupportedHistoricalStateException(
          "historical block hash " + num + " unavailable"));
    }
    reserveOverlayBytes(blockHash.length);
    byte[] cachedHash = blockHash.clone();
    blockHashCache.put(num, cachedHash);
    return cachedHash.clone();
  }

  @Override
  public long getTotalNetWeight() {
    return dynamicLong(
        TOTAL_NET_WEIGHT, getVmDynamicProperties().getTotalNetWeight(), "total net weight");
  }

  @Override
  public long getTotalEnergyWeight() {
    return dynamicLong(TOTAL_ENERGY_WEIGHT,
        getVmDynamicProperties().getTotalEnergyWeight(), "total energy weight");
  }

  @Override
  public long getTotalTronPowerWeight() {
    return dynamicLong(TOTAL_TRON_POWER_WEIGHT,
        getVmDynamicProperties().getTotalTronPowerWeight(), "total tron-power weight");
  }

  @Override
  public long getHeadSlot() {
    return getSlotByTimestampMs(getVmDynamicProperties().getLatestBlockHeaderTimestamp());
  }

  @Override
  public long getSlotByTimestampMs(long timestamp) {
    return (timestamp - Long.parseLong(
        CommonParameter.getInstance().getGenesisBlock().getTimestamp()))
        / BLOCK_PRODUCED_INTERVAL;
  }

  @Override
  public long getAccountLeftEnergyFromFreeze(AccountCapsule accountCapsule) {
    long now = getHeadSlot();
    long energyUsage = accountCapsule.getEnergyUsage();
    long latestConsumeTime =
        accountCapsule.getAccountResource().getLatestConsumeTimeForEnergy();
    long energyLimit = calculateGlobalEnergyLimit(accountCapsule);
    long windowSize = accountCapsule.getWindowSize(ResourceCode.ENERGY);
    long recoveredUsage =
        recoverEnergyUsage(energyUsage, latestConsumeTime, now, windowSize);
    return max(energyLimit - recoveredUsage, 0L, disableJavaLangMath());
  }

  @Override
  public long getAccountEnergyUsage(AccountCapsule accountCapsule) {
    return recoverEnergyUsage(
        accountCapsule.getEnergyUsage(),
        accountCapsule.getAccountResource().getLatestConsumeTimeForEnergy(),
        getHeadSlot(),
        accountCapsule.getWindowSize(ResourceCode.ENERGY));
  }

  /** Applies the same pre-execution energy-usage update as a canonical transaction. */
  public void updateEnergyUsageForReplay(AccountCapsule accountCapsule, long usage, long now) {
    if (usage < 0L) {
      throw new IllegalArgumentException("energy usage must be non-negative");
    }
    long previousUsage = accountCapsule.getEnergyUsage();
    long previousTime =
        accountCapsule.getAccountResource().getLatestConsumeTimeForEnergy();
    long recoveredUsage =
        increaseUsage(
            accountCapsule, ResourceCode.ENERGY, previousUsage, 0L, previousTime, now);
    accountCapsule.setEnergyUsage(recoveredUsage);
    accountCapsule.setLatestConsumeTimeForEnergy(now);
    accountCapsule.setEnergyUsage(
        increaseUsage(
            accountCapsule, ResourceCode.ENERGY, recoveredUsage, usage, now, now));
  }

  @Override
  public void transferFrozenV2UsageForSelfDestruct(AccountCapsule owner,
      AccountCapsule inheritor, long now) {
    transferUsageForSelfDestruct(owner, inheritor, ResourceCode.BANDWIDTH, now);
    transferUsageForSelfDestruct(owner, inheritor, ResourceCode.ENERGY, now);
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
    long frozenBalance = accountCapsule.getAllFrozenBalanceForEnergy();
    if (frozenBalance < TRX_PRECISION) {
      return 0L;
    }
    long energyWeight = frozenBalance / TRX_PRECISION;
    long totalEnergyLimit = getVmDynamicProperties().getTotalEnergyCurrentLimit();
    long totalEnergyWeight = getVmDynamicProperties().getTotalEnergyWeight();

    assert totalEnergyWeight > 0L;

    if (hardenResourceCalculation()) {
      return BigInteger.valueOf(energyWeight)
          .multiply(BigInteger.valueOf(totalEnergyLimit))
          .divide(BigInteger.valueOf(totalEnergyWeight))
          .longValueExact();
    }
    return (long) (energyWeight * ((double) totalEnergyLimit / totalEnergyWeight));
  }

  @Override
  public long addTokenBalance(byte[] address, byte[] tokenId, long value) {
    byte[] tokenIdWithoutLeadingZero = canonicalTokenId(tokenId);
    if (tokenIdWithoutLeadingZero.length == 0) {
      return 0L;
    }
    AccountCapsule account = getAccount(address);
    if (account == null) {
      account = createAccount(address, Protocol.AccountType.Normal);
    }
    long balance = getTokenBalance(address, tokenIdWithoutLeadingZero);
    if (value == 0) {
      return balance;
    }
    if (value < 0 && balance < -value) {
      throw new RuntimeException(
          ByteArray.toHexString(account.createDbKey()) + " insufficient balance");
    }
    long updated = addExact(balance, value, VMConfig.disableJavaLangMath());
    putTokenBalance(address, tokenIdWithoutLeadingZero, updated);
    return updated;
  }

  private void putTokenBalance(byte[] address, byte[] tokenId, long balance) {
    reserveOverlay(address, tokenId);
    tokenBalances.computeIfAbsent(Key.create(address), k -> new HashMap<>())
        .put(Key.create(tokenId), balance);
  }

  private static AccountCapsule copyAccount(AccountCapsule account) {
    return account == null ? null : new AccountCapsule(account.getInstance());
  }

  private static ContractCapsule copyContract(ContractCapsule contract) {
    return contract == null ? null : new ContractCapsule(contract.getInstance());
  }

  private static ContractStateCapsule copyContractState(ContractStateCapsule state) {
    return state == null ? null : new ContractStateCapsule(state.getInstance());
  }

  private static VotesCapsule copyVotes(VotesCapsule value) {
    return value == null ? null : new VotesCapsule(value.getData());
  }

  private static BytesCapsule copyBytes(BytesCapsule value) {
    return value == null ? null : new BytesCapsule(value.getData().clone());
  }

  private long dynamicLong(byte[] key, long fallback, String what) {
    BytesCapsule value = getDynamicProperty(key);
    if (value == null) {
      return fallback;
    }
    return delegationLong(value, what);
  }

  private static long delegationLong(BytesCapsule value, String what) {
    byte[] data = value.getData();
    if (data == null || data.length != Long.BYTES) {
      throw unsupportedHistoricalOperation(what + " with a non-int64 value");
    }
    return ByteArray.toLong(data);
  }

  private static byte[] endCycleKey(byte[] address) {
    return ("end-" + Hex.toHexString(address)).getBytes(StandardCharsets.US_ASCII);
  }

  private static byte[] accountVoteKey(long cycle, byte[] address) {
    return (cycle + "-" + Hex.toHexString(address) + "-account-vote")
        .getBytes(StandardCharsets.US_ASCII);
  }

  private static byte[] witnessViKey(long cycle, byte[] address) {
    return (cycle + "-" + Hex.toHexString(address) + "-vi")
        .getBytes(StandardCharsets.US_ASCII);
  }

  private static void reserveOverlay(byte[]... values) {
    long payloadBytes = 0L;
    if (values != null) {
      for (byte[] value : values) {
        if (value != null) {
          payloadBytes = addSaturated(payloadBytes, value.length);
        }
      }
    }
    reserveOverlayBytes(payloadBytes);
  }

  private static void reserveOverlayMessage(byte[] key, MessageLite value) {
    long payloadBytes = addSaturated(length(key), value == null ? 0L : value.getSerializedSize());
    reserveOverlayBytes(payloadBytes);
  }

  private static void reserveOverlayBytes(long payloadBytes) {
    QueryContext queryContext = QueryContextHolder.current();
    if (queryContext != null) {
      queryContext.recordVmOverlayBytes(
          addSaturated(OVERLAY_ENTRY_OVERHEAD_BYTES, payloadBytes));
    }
  }

  private static void reserveTokenBalanceMap(Map<String, Long> balances) {
    if (balances == null || balances.isEmpty()) {
      return;
    }
    QueryContext queryContext = QueryContextHolder.current();
    if (queryContext != null) {
      queryContext.recordVmOverlayBytes(
          OVERLAY_ENTRY_OVERHEAD_BYTES * (long) balances.size());
    }
  }

  private static int length(byte[] value) {
    return value == null ? 0 : value.length;
  }

  private static long addSaturated(long left, long right) {
    return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
  }

  private static byte[] canonicalTokenId(byte[] tokenId) {
    byte[] stripped = ByteUtil.stripLeadingZeroes(tokenId);
    return stripped == null ? ByteUtil.EMPTY_BYTE_ARRAY : stripped;
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
    byte[] raw = value == null ? null : (byte[]) value.getValue();
    updateTransientStorageValue(address.getData(), key.getData(), raw);
  }

  @Override
  public void updateDynamicProperty(byte[] word, BytesCapsule bytesCapsule) {
    reserveOverlay(word, bytesCapsule == null ? null : bytesCapsule.getData());
    dynamicProperties.put(Key.create(word), copyBytes(bytesCapsule));
  }

  @Override
  public void updateDelegatedResource(byte[] word, DelegatedResourceCapsule capsule) {
    throw unsupported("updateDelegatedResource");
  }

  @Override
  public void updateVotes(byte[] word, VotesCapsule votesCapsule) {
    reserveOverlayMessage(word,
        votesCapsule == null ? null : votesCapsule.getInstance());
    votes.put(Key.create(word), copyVotes(votesCapsule));
  }

  @Override
  public void updateBeginCycle(byte[] word, long cycle) {
    updateDelegation(word, new BytesCapsule(ByteArray.fromLong(cycle)));
  }

  @Override
  public void updateEndCycle(byte[] word, long cycle) {
    updateDelegation(endCycleKey(word), new BytesCapsule(ByteArray.fromLong(cycle)));
  }

  @Override
  public void updateAccountVote(byte[] word, long cycle, AccountCapsule accountCapsule) {
    updateDelegation(
        accountVoteKey(cycle, word), new BytesCapsule(accountCapsule.getData()));
  }

  @Override
  public void updateDelegation(byte[] word, BytesCapsule bytesCapsule) {
    reserveOverlay(word, bytesCapsule == null ? null : bytesCapsule.getData());
    delegations.put(Key.create(word), copyBytes(bytesCapsule));
  }

  @Override
  public void updateDelegatedResourceAccountIndex(byte[] word,
      DelegatedResourceAccountIndexCapsule capsule) {
    throw unsupported("updateDelegatedResourceAccountIndex");
  }

  @Override
  public void addTotalNetWeight(long amount) {
    saveTotalNetWeight(getTotalNetWeight() + amount);
  }

  @Override
  public void addTotalEnergyWeight(long amount) {
    saveTotalEnergyWeight(getTotalEnergyWeight() + amount);
  }

  @Override
  public void addTotalTronPowerWeight(long amount) {
    saveTotalTronPowerWeight(getTotalTronPowerWeight() + amount);
  }

  @Override
  public void saveTotalNetWeight(long totalNetWeight) {
    updateDynamicProperty(
        TOTAL_NET_WEIGHT, new BytesCapsule(ByteArray.fromLong(totalNetWeight)));
  }

  @Override
  public void saveTotalEnergyWeight(long totalEnergyWeight) {
    updateDynamicProperty(
        TOTAL_ENERGY_WEIGHT, new BytesCapsule(ByteArray.fromLong(totalEnergyWeight)));
  }

  @Override
  public void saveTotalTronPowerWeight(long totalTronPowerWeight) {
    updateDynamicProperty(
        TOTAL_TRON_POWER_WEIGHT, new BytesCapsule(ByteArray.fromLong(totalTronPowerWeight)));
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers.
  // ---------------------------------------------------------------------------------------------

  private long recoverEnergyUsage(long lastUsage, long lastTime, long now, long windowSize) {
    return increaseEnergyUsage(lastUsage, 0L, lastTime, now, windowSize);
  }

  private long increaseEnergyUsage(long lastUsage, long usage, long lastTime, long now,
      long windowSize) {
    requireValidUsageWindow(lastTime, now, windowSize);
    long averageLastUsage;
    long averageUsage;
    if (hardenResourceCalculation()) {
      BigInteger precision = BigInteger.valueOf(PRECISION);
      BigInteger window = BigInteger.valueOf(windowSize);
      averageLastUsage = divideCeilExact(
          BigInteger.valueOf(lastUsage).multiply(precision), window);
      averageUsage = divideCeilExact(
          BigInteger.valueOf(usage).multiply(precision), window);
    } else {
      averageLastUsage = divideCeil(lastUsage * PRECISION, windowSize);
      averageUsage = divideCeil(usage * PRECISION, windowSize);
    }
    if (lastTime != now) {
      if (lastTime + windowSize > now) {
        long delta = now - lastTime;
        double decay = (windowSize - delta) / (double) windowSize;
        averageLastUsage = round(averageLastUsage * decay, disableJavaLangMath());
      } else {
        averageLastUsage = 0L;
      }
    }
    return energyUsageFromAverage(averageLastUsage + averageUsage, windowSize);
  }

  private long increaseUsage(AccountCapsule accountCapsule, ResourceCode resourceCode,
      long lastUsage, long usage, long lastTime, long now) {
    if (getVmDynamicProperties().supportAllowCancelAllUnfreezeV2()) {
      return increaseUsageV2(
          accountCapsule, resourceCode, lastUsage, usage, lastTime, now);
    }
    long oldWindowSize = accountCapsule.getWindowSize(resourceCode);
    requireValidUsageWindow(lastTime, now, oldWindowSize);
    long averageLastUsage;
    long averageUsage;
    if (hardenResourceCalculation()) {
      BigInteger precision = BigInteger.valueOf(PRECISION);
      averageLastUsage = divideCeilExact(
          BigInteger.valueOf(lastUsage).multiply(precision),
          BigInteger.valueOf(oldWindowSize));
      averageUsage = divideCeilExact(
          BigInteger.valueOf(usage).multiply(precision),
          BigInteger.valueOf(ENERGY_WINDOW_SIZE));
    } else {
      averageLastUsage = divideCeil(lastUsage * PRECISION, oldWindowSize);
      averageUsage = divideCeil(usage * PRECISION, ENERGY_WINDOW_SIZE);
    }
    if (lastTime != now) {
      if (lastTime + oldWindowSize > now) {
        long delta = now - lastTime;
        double decay = (oldWindowSize - delta) / (double) oldWindowSize;
        averageLastUsage = round(averageLastUsage * decay, disableJavaLangMath());
      } else {
        averageLastUsage = 0L;
      }
    }
    long newUsage = combinedEnergyUsage(
        averageLastUsage, oldWindowSize, averageUsage, ENERGY_WINDOW_SIZE);
    if (getVmDynamicProperties().supportUnfreezeDelay()) {
      long remainingUsage = energyUsageFromAverage(averageLastUsage, oldWindowSize);
      if (remainingUsage == 0L) {
        accountCapsule.setNewWindowSize(resourceCode, ENERGY_WINDOW_SIZE);
        return newUsage;
      }
      long remainingWindow = oldWindowSize - (now - lastTime);
      long newWindow = weightedWindowSize(
          remainingUsage, remainingWindow, usage, ENERGY_WINDOW_SIZE, newUsage);
      accountCapsule.setNewWindowSize(resourceCode, newWindow);
    }
    return newUsage;
  }

  private long increaseUsageV2(AccountCapsule accountCapsule, ResourceCode resourceCode,
      long lastUsage, long usage, long lastTime, long now) {
    long oldWindowSizeV2 = accountCapsule.getWindowSizeV2(resourceCode);
    long oldWindowSize = accountCapsule.getWindowSize(resourceCode);
    requireValidUsageWindow(lastTime, now, oldWindowSize);
    long averageLastUsage;
    long averageUsage;
    if (hardenResourceCalculation()) {
      BigInteger precision = BigInteger.valueOf(PRECISION);
      averageLastUsage = divideCeilExact(
          BigInteger.valueOf(lastUsage).multiply(precision),
          BigInteger.valueOf(oldWindowSize));
      averageUsage = divideCeilExact(
          BigInteger.valueOf(usage).multiply(precision),
          BigInteger.valueOf(ENERGY_WINDOW_SIZE));
    } else {
      averageLastUsage = divideCeil(lastUsage * PRECISION, oldWindowSize);
      averageUsage = divideCeil(usage * PRECISION, ENERGY_WINDOW_SIZE);
    }
    if (lastTime != now) {
      if (lastTime + oldWindowSize > now) {
        long delta = now - lastTime;
        double decay = (oldWindowSize - delta) / (double) oldWindowSize;
        averageLastUsage = round(averageLastUsage * decay, disableJavaLangMath());
      } else {
        averageLastUsage = 0L;
      }
    }
    long newUsage = combinedEnergyUsage(
        averageLastUsage, oldWindowSize, averageUsage, ENERGY_WINDOW_SIZE);
    long remainingUsage = energyUsageFromAverage(averageLastUsage, oldWindowSize);
    if (remainingUsage == 0L) {
      accountCapsule.setNewWindowSizeV2(
          resourceCode, ENERGY_WINDOW_SIZE * WINDOW_SIZE_PRECISION);
      return newUsage;
    }
    long remainingWindowV2 =
        oldWindowSizeV2 - (now - lastTime) * WINDOW_SIZE_PRECISION;
    long newWindowV2;
    if (hardenResourceCalculation()) {
      BigInteger weightedWindow = BigInteger.valueOf(remainingUsage)
          .multiply(BigInteger.valueOf(remainingWindowV2))
          .add(BigInteger.valueOf(usage)
              .multiply(BigInteger.valueOf(ENERGY_WINDOW_SIZE))
              .multiply(BigInteger.valueOf(WINDOW_SIZE_PRECISION)));
      newWindowV2 = divideCeilExact(weightedWindow, BigInteger.valueOf(newUsage));
    } else {
      newWindowV2 = divideCeil(
          remainingUsage * remainingWindowV2
              + usage * ENERGY_WINDOW_SIZE * WINDOW_SIZE_PRECISION,
          newUsage);
    }
    newWindowV2 = min(
        newWindowV2,
        ENERGY_WINDOW_SIZE * WINDOW_SIZE_PRECISION,
        disableJavaLangMath());
    accountCapsule.setNewWindowSizeV2(resourceCode, newWindowV2);
    return newUsage;
  }

  private void transferUsageForSelfDestruct(AccountCapsule owner,
      AccountCapsule inheritor, ResourceCode resourceCode, long now) {
    long lastTime = owner.getLastConsumeTime(resourceCode);
    long usage = owner.getUsage(resourceCode);
    usage = increaseUsage(owner, resourceCode, usage, 0L, lastTime, now);
    owner.setUsage(resourceCode, usage);
    owner.setLatestTime(resourceCode, now);
    if (usage > 0L) {
      unDelegateIncreaseForReplay(inheritor, owner, usage, resourceCode, now);
    }
  }

  private void unDelegateIncreaseForReplay(AccountCapsule owner,
      AccountCapsule receiver, long transferUsage, ResourceCode resourceCode, long now) {
    long lastOwnerTime = owner.getLastConsumeTime(resourceCode);
    long ownerUsage = owner.getUsage(resourceCode);
    ownerUsage =
        increaseUsage(owner, resourceCode, ownerUsage, 0L, lastOwnerTime, now);
    long newOwnerUsage = ownerUsage + transferUsage;
    if (newOwnerUsage == 0L) {
      if (getVmDynamicProperties().supportAllowCancelAllUnfreezeV2()) {
        owner.setNewWindowSizeV2(
            resourceCode, ENERGY_WINDOW_SIZE * WINDOW_SIZE_PRECISION);
      } else {
        owner.setNewWindowSize(resourceCode, ENERGY_WINDOW_SIZE);
      }
      owner.setUsage(resourceCode, 0L);
      owner.setLatestTime(resourceCode, now);
      return;
    }
    if (getVmDynamicProperties().supportAllowCancelAllUnfreezeV2()) {
      long ownerWindow = nonNegative(owner.getWindowSizeV2(resourceCode));
      long receiverWindow = nonNegative(receiver.getWindowSizeV2(resourceCode));
      long newWindow;
      if (hardenResourceCalculation()) {
        BigInteger weighted = BigInteger.valueOf(ownerUsage)
            .multiply(BigInteger.valueOf(ownerWindow))
            .add(BigInteger.valueOf(transferUsage)
                .multiply(BigInteger.valueOf(receiverWindow)));
        newWindow = divideCeilExact(weighted, BigInteger.valueOf(newOwnerUsage));
      } else {
        newWindow = divideCeil(
            ownerUsage * ownerWindow + transferUsage * receiverWindow,
            newOwnerUsage);
      }
      owner.setNewWindowSizeV2(resourceCode,
          min(newWindow, ENERGY_WINDOW_SIZE * WINDOW_SIZE_PRECISION,
              disableJavaLangMath()));
    } else {
      long ownerWindow = nonNegative(owner.getWindowSize(resourceCode));
      long receiverWindow = nonNegative(receiver.getWindowSize(resourceCode));
      owner.setNewWindowSize(resourceCode, weightedWindowSize(
          ownerUsage, ownerWindow, transferUsage, receiverWindow, newOwnerUsage));
    }
    owner.setUsage(resourceCode, newOwnerUsage);
    owner.setLatestTime(resourceCode, now);
  }

  private static long nonNegative(long value) {
    return value < 0L ? 0L : value;
  }

  private long combinedEnergyUsage(long averageLastUsage, long lastWindowSize,
      long averageUsage, long usageWindowSize) {
    if (hardenResourceCalculation()) {
      BigInteger numerator = BigInteger.valueOf(averageLastUsage)
          .multiply(BigInteger.valueOf(lastWindowSize))
          .add(BigInteger.valueOf(averageUsage)
              .multiply(BigInteger.valueOf(usageWindowSize)));
      return numerator.divide(BigInteger.valueOf(PRECISION)).longValueExact();
    }
    return (averageLastUsage * lastWindowSize + averageUsage * usageWindowSize)
        / PRECISION;
  }

  private long energyUsageFromAverage(long averageUsage, long windowSize) {
    if (hardenResourceCalculation()) {
      return BigInteger.valueOf(averageUsage)
          .multiply(BigInteger.valueOf(windowSize))
          .divide(BigInteger.valueOf(PRECISION))
          .longValueExact();
    }
    return averageUsage * windowSize / PRECISION;
  }

  private long weightedWindowSize(long lastUsage, long lastWindowSize,
      long usage, long usageWindowSize, long newUsage) {
    if (hardenResourceCalculation()) {
      BigInteger numerator = BigInteger.valueOf(lastUsage)
          .multiply(BigInteger.valueOf(lastWindowSize))
          .add(BigInteger.valueOf(usage).multiply(BigInteger.valueOf(usageWindowSize)));
      return numerator.divide(BigInteger.valueOf(newUsage)).longValueExact();
    }
    return (lastUsage * lastWindowSize + usage * usageWindowSize) / newUsage;
  }

  private boolean hardenResourceCalculation() {
    return getVmDynamicProperties().getAllowHardenResourceCalculation() == 1L;
  }

  private boolean disableJavaLangMath() {
    return getVmDynamicProperties().getConsensusLogicOptimization() == 1L;
  }

  private static long divideCeil(long numerator, long denominator) {
    return numerator / denominator + (numerator % denominator > 0L ? 1L : 0L);
  }

  private static long divideCeilExact(BigInteger numerator, BigInteger denominator) {
    BigInteger[] quotientAndRemainder = numerator.divideAndRemainder(denominator);
    long quotient = quotientAndRemainder[0].longValueExact();
    return quotientAndRemainder[1].signum() > 0
        ? StrictMathWrapper.addExact(quotient, 1L) : quotient;
  }

  private static void requireValidUsageWindow(long lastTime, long now, long windowSize) {
    if (windowSize <= 0L || now < lastTime) {
      throw unsupportedHistoricalOperation(
          "invalid historical energy-usage window");
    }
  }

  private interface ReaderCall<T> {
    ArchiveReadResult<T> call() throws ArchiveReaderException;
  }

  private interface ReaderValueCall<T> {
    T call() throws ArchiveReaderException;
  }

  private <T> T present(ArchiveReadResult<T> result, String what) {
    requireKnown(result, what);
    return result.isPresent() ? result.getValue() : null;
  }

  private void requireKnown(ArchiveReadResult<?> result, String what) {
    if (!genesisComplete && result.getStatus() == ArchiveReadResult.Status.MISSING) {
      throw recordUnsupported(new UnsupportedHistoricalStateException(
          "archive " + what + " is unknown before mid-chain coverage"));
    }
  }

  private <T> ArchiveReadResult<T> read(ReaderCall<T> call, String what) {
    try {
      return call.call();
    } catch (ArchiveReaderException e) {
      throw recordUnsupported(new UnsupportedHistoricalStateException(
          "archive read failed for " + what, e));
    }
  }

  private <T> T readValue(ReaderValueCall<T> call, String what) {
    try {
      return call.call();
    } catch (ArchiveReaderException e) {
      throw recordUnsupported(new UnsupportedHistoricalStateException(
          "archive read failed for " + what, e));
    }
  }

  private static UnsupportedHistoricalStateException unsupported(String what) {
    return unsupportedHistoricalOperation(what);
  }

  /** Creates and records an archive-only VM operation that cannot be replayed faithfully. */
  public static UnsupportedHistoricalStateException unsupportedHistoricalOperation(String what) {
    return recordUnsupported(new UnsupportedHistoricalStateException(
        "historical archive call does not support " + what));
  }

  private static UnsupportedHistoricalStateException recordUnsupported(
      UnsupportedHistoricalStateException failure) {
    QueryContext queryContext = QueryContextHolder.current();
    if (queryContext != null) {
      queryContext.recordVmTerminalFailure(failure);
    }
    return failure;
  }
}
