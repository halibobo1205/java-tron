package org.tron.core.services.jsonrpc.types;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.google.protobuf.ByteString;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.tron.protos.Protocol;

@Getter
@JsonPropertyOrder(alphabetic = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@EqualsAndHashCode(callSuper = false)
public class AccountResult extends Result {

  private final String accountName;
  private final String type;
  private final String address;
  private final String balance;
  private final List<Vote> votes;
  private final List<Token10Result> assetV2;
  private final List<Frozen> frozen;
  private final String netUsage;
  private final String acquiredDelegatedFrozenBalanceForBandwidth;
  private final String delegatedFrozenBalanceForBandwidth;
  private final String oldTronPower;
  private final Frozen tronPower;
  private final String assetOptimized;
  private final String createTime;
  private final String latestOperationTime;
  private final String allowance;
  private final String latestWithdrawTime;
  private final String code;
  private final String isWitness;
  private final String isCommittee;
  private final List<Frozen> frozenSupply;
  private final String assetIssuedName;
  private final String assetIssuedId;
  private final List<Token10Result> latestAssetOperationTimeV2;
  private final String freeNetUsage;
  private final List<Token10Result> freeAssetNetUsageV2;
  private final String latestConsumeTime;
  private final String latestConsumeFreeTime;
  private final String accountId;
  private final String netWindowSize;
  private final String netWindowOptimized;
  private final AccountResource accountResource;
  private final String codeHash;
  private final Permission ownerPermission;
  private final Permission witnessPermission;
  private final List<Permission> activePermissions;
  private final List<FreezeV2> frozenV2;
  private final List<UnFreezeV2> unfrozenV2;
  private final String delegatedFrozenV2BalanceForBandwidth;
  private final String acquiredDelegatedFrozenV2BalanceForBandwidth;

  public AccountResult(Protocol.Account account) {
    this.accountName = toHex(account.getAccountName());
    this.type = account == Protocol.Account.getDefaultInstance()
        ? null : toHex(account.getType().getNumber());
    this.address = toEthHexAddress(account.getAddress());
    this.balance = toHex(account.getBalance());
    this.votes = toVotes(account.getVotesList());
    this.assetV2 = toHex(account.getAssetV2Map());
    this.frozen = toFrozen(account.getFrozenList());
    this.netUsage = toHex(account.getNetUsage());
    this.acquiredDelegatedFrozenBalanceForBandwidth =
        toHex(account.getAcquiredDelegatedFrozenBalanceForBandwidth());
    this.delegatedFrozenBalanceForBandwidth =
        toHex(account.getDelegatedFrozenBalanceForBandwidth());
    this.oldTronPower = toHex(account.getOldTronPower());
    this.tronPower = account.hasTronPower()
        && Protocol.Account.Frozen.getDefaultInstance() != account.getTronPower()
        ? new Frozen(account.getTronPower()) : null;
    this.assetOptimized = toHex(account.getAssetOptimized());
    this.createTime = toHex(account.getCreateTime());
    this.latestOperationTime = toHex(account.getLatestOprationTime());
    this.allowance = toHex(account.getAllowance());
    this.latestWithdrawTime = toHex(account.getLatestWithdrawTime());
    this.code = toHex(account.getCode());
    this.isWitness = toHex(account.getIsWitness());
    this.isCommittee = toHex(account.getIsCommittee());
    this.frozenSupply = toFrozen(account.getFrozenSupplyList());
    this.assetIssuedName = toHex(account.getAssetIssuedName());
    this.assetIssuedId = toHex(account.getAssetIssuedID().isEmpty()
        ? 0L : Long.parseLong(account.getAssetIssuedID().toStringUtf8()));
    this.latestAssetOperationTimeV2 = toHex(account.getLatestAssetOperationTimeV2Map());
    this.freeNetUsage = toHex(account.getFreeNetUsage());
    this.freeAssetNetUsageV2 = toHex(account.getFreeAssetNetUsageV2Map());
    this.latestConsumeTime = toHex(account.getLatestConsumeTime());
    this.latestConsumeFreeTime = toHex(account.getLatestConsumeFreeTime());
    this.accountId = toHex(account.getAccountId());
    this.netWindowSize = toHex(account.getNetWindowSize());
    this.netWindowOptimized = toHex(account.getNetWindowOptimized());
    this.accountResource = account.hasAccountResource()
        && Protocol.Account.AccountResource.getDefaultInstance() != account.getAccountResource()
        ? new AccountResource(account.getAccountResource()) : null;
    this.codeHash = toHex(account.getCodeHash());
    this.ownerPermission = account.hasOwnerPermission()
        && Protocol.Permission.getDefaultInstance() != account.getOwnerPermission()
        ? new Permission(account.getOwnerPermission()) : null;
    this.witnessPermission = account.hasWitnessPermission()
        && Protocol.Permission.getDefaultInstance() != account.getWitnessPermission()
        ? new Permission(account.getWitnessPermission()) : null;
    this.activePermissions = toActivePermissions(account.getActivePermissionList());
    this.frozenV2 = toFrozenV2(account.getFrozenV2List());
    this.unfrozenV2 = toUnfrozenV2(account.getUnfrozenV2List());
    this.delegatedFrozenV2BalanceForBandwidth =
        toHex(account.getDelegatedFrozenV2BalanceForBandwidth());
    this.acquiredDelegatedFrozenV2BalanceForBandwidth =
        toHex(account.getAcquiredDelegatedFrozenV2BalanceForBandwidth());
  }

  private static List<Vote> toVotes(List<Protocol.Vote> votes) {
    return votes.stream()
        .filter(v -> v != Protocol.Vote.getDefaultInstance())
        .map(Vote::new)
        .filter(v -> Objects.nonNull(v.voteCount))
        .collect(Collectors.toList());
  }

  private static List<Frozen> toFrozen(List<Protocol.Account.Frozen> frozenList) {
    return frozenList.stream()
        .filter(f -> f != Protocol.Account.Frozen.getDefaultInstance())
        .map(Frozen::new)
        .filter(f -> Objects.nonNull(f.frozenBalance))
        .collect(Collectors.toList());
  }

  private static List<FreezeV2> toFrozenV2(List<Protocol.Account.FreezeV2> frozenList) {
    return frozenList.stream()
        .filter(f -> f != Protocol.Account.FreezeV2.getDefaultInstance())
        .map(FreezeV2::new)
        .filter(f -> Objects.nonNull(f.amount))
        .collect(Collectors.toList());
  }

  private static List<UnFreezeV2> toUnfrozenV2(List<Protocol.Account.UnFreezeV2> unfrozenList) {
    return unfrozenList.stream()
        .filter(u -> u != Protocol.Account.UnFreezeV2.getDefaultInstance())
        .map(UnFreezeV2::new)
        .filter(f -> Objects.nonNull(f.unfreezeAmount))
        .collect(Collectors.toList());
  }

  private static List<Key> toKeys(List<Protocol.Key> keys) {
    return keys.stream()
        .filter(k -> k != Protocol.Key.getDefaultInstance())
        .map(Key::new)
        .filter(k -> Objects.nonNull(k.weight))
        .collect(Collectors.toList());
  }

  private static List<Permission> toActivePermissions(List<Protocol.Permission> permissions) {
    return permissions.stream()
        .filter(p -> p != Protocol.Permission.getDefaultInstance())
        .map(Permission::new)
        .filter(p -> Objects.nonNull(p.threshold))
        .collect(Collectors.toList());
  }

  @Getter
  @JsonPropertyOrder(alphabetic = true)
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  @EqualsAndHashCode(callSuper = false)
  public static class AccountResource {
    private final String energyUsage;
    private final Frozen frozenBalanceForEnergy;
    private final String latestConsumeTimeForEnergy;
    private final String acquiredDelegatedFrozenBalanceForEnergy;
    private final String delegatedFrozenBalanceForEnergy;
    private final String storageLimit;
    private final String storageUsage;
    private final String latestExchangeStorageTime;
    private final String energyWindowSize;
    private final String delegatedFrozenV2BalanceForEnergy;
    private final String acquiredDelegatedFrozenV2BalanceForEnergy;
    private final String energyWindowOptimized;

    public AccountResource(Protocol.Account.AccountResource resource) {
      this.energyUsage = toHex(resource.getEnergyUsage());
      this.frozenBalanceForEnergy = resource.hasFrozenBalanceForEnergy()
          ? new Frozen(resource.getFrozenBalanceForEnergy()) : null;
      this.latestConsumeTimeForEnergy = toHex(resource.getLatestConsumeTimeForEnergy());
      this.acquiredDelegatedFrozenBalanceForEnergy =
          toHex(resource.getAcquiredDelegatedFrozenBalanceForEnergy());
      this.delegatedFrozenBalanceForEnergy = toHex(resource.getDelegatedFrozenBalanceForEnergy());
      this.storageLimit = toHex(resource.getStorageLimit());
      this.storageUsage = toHex(resource.getStorageUsage());
      this.latestExchangeStorageTime = toHex(resource.getLatestExchangeStorageTime());
      this.energyWindowSize = toHex(resource.getEnergyWindowSize());
      this.delegatedFrozenV2BalanceForEnergy =
          toHex(resource.getDelegatedFrozenV2BalanceForEnergy());
      this.acquiredDelegatedFrozenV2BalanceForEnergy =
          toHex(resource.getAcquiredDelegatedFrozenV2BalanceForEnergy());
      this.energyWindowOptimized = toHex(resource.getEnergyWindowOptimized());
    }
  }

  @Getter
  @JsonPropertyOrder(alphabetic = true)
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  @EqualsAndHashCode(callSuper = false)
  public static class Permission {
    private final String type;
    private final String id;
    private final String permissionName;
    private final String threshold;
    private final String parentId;
    private final String operations;
    private final List<Key> keys;

    public Permission(Protocol.Permission permission) {
      this.type = toHex(permission.getType().getNumber());
      this.id = toHex(permission.getId());
      this.permissionName = toHex(ByteString.copyFromUtf8(permission.getPermissionName()));
      this.threshold = toHex(permission.getThreshold());
      this.parentId = toHex(permission.getParentId());
      this.operations = toHex(permission.getOperations());
      this.keys = toKeys(permission.getKeysList());
    }
  }

  @Getter
  @JsonPropertyOrder(alphabetic = true)
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  @EqualsAndHashCode(callSuper = false)
  public static class Key {
    private final String address;
    private final String weight;

    public Key(Protocol.Key key) {
      this.address = toEthHexAddress(key.getAddress());
      this.weight = toHex(key.getWeight());
    }
  }

  @Getter
  @JsonPropertyOrder(alphabetic = true)
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  @EqualsAndHashCode(callSuper = false)
  public static class Frozen {
    private final String frozenBalance;
    private final String expireTime;

    public Frozen(Protocol.Account.Frozen frozen) {
      this.frozenBalance = toHex(frozen.getFrozenBalance());
      this.expireTime = toHex(frozen.getExpireTime());
    }
  }

  @Getter
  @JsonPropertyOrder(alphabetic = true)
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  @EqualsAndHashCode(callSuper = false)
  public static class FreezeV2 {
    private final String type;
    private final String amount;

    public FreezeV2(Protocol.Account.FreezeV2 freeze) {
      this.type = toHex(freeze.getType().getNumber());
      this.amount = toHex(freeze.getAmount());
    }
  }

  @Getter
  @JsonPropertyOrder(alphabetic = true)
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  @EqualsAndHashCode(callSuper = false)
  public static class UnFreezeV2 {
    private final String type;
    private final String unfreezeAmount;
    private final String unfreezeExpireTime;

    public UnFreezeV2(Protocol.Account.UnFreezeV2 unfreeze) {
      this.type = toHex(unfreeze.getType().getNumber());
      this.unfreezeAmount = toHex(unfreeze.getUnfreezeAmount());
      this.unfreezeExpireTime = toHex(unfreeze.getUnfreezeExpireTime());
    }
  }

  @Getter
  @JsonPropertyOrder(alphabetic = true)
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  @EqualsAndHashCode(callSuper = false)
  public static class Vote {
    private final String voteAddress;
    private final String voteCount;

    public Vote(Protocol.Vote vote) {
      this.voteAddress = toEthHexAddress(vote.getVoteAddress());
      this.voteCount = toHex(vote.getVoteCount());
    }
  }
}
