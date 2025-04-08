package org.tron.core.services.jsonrpc.types;

import com.google.protobuf.ByteString;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.junit.Assert;
import org.junit.Test;
import org.tron.common.utils.ByteArray;
import org.tron.protos.Protocol;
import org.tron.protos.contract.Common;

import static org.tron.core.services.jsonrpc.types.Result.toHex;

public class AccountResultTest {

  @Test
  public void testAccountResult() {

    final String address = "0x411234567890abcdef1234567890abcdef12345678";
    final String sr1 = "0x411234567890abcdef1234567890abcdef12345679";
    final String sr2 = "0x411234567890abcdef1234567890abcdef1234567a";
    final String sr3 = "0x411234567890abcdef1234567890abcdef1234567b";
    final String accountName = "account001";
    final String accountId = "accountid12345";
    final String assetIssuedName = "asset001";
    final String assetIssuedId = "10000001";
    final String assetName1 = "asset001";
    final String assetName2 = "asset002";
    final String assetName3 = "asset003";
    final String assetId1 = "10000001";
    final String assetId2 = "10000002";
    final String assetId3 = "10000003";
    final long amount1 = 1000000L;
    final long amount2 = 2000000L;
    final long amount3 = 0L;
    final long time = 1532884287000L;
    final long balance = 9141583L;
    final long allowance = 613247072L;
    final long usage = 500L;
    final long freeUsage = 1000L;
    final long windowSize = 28800000L;
    final long oldTronPower = 300L;
    final long bandwidth = 1000L;
    final long energy = 2000L;
    final long limit = 1000000L;
    final String operation = "operation001";
    final String code = "code001";
    final String codeHash = "codehash001";
    final int id = 100;
    final long threshold = 10L;
    final long weight = 8L;

    Protocol.Account.Builder builder = Protocol.Account.newBuilder();
    builder.setAccountName(ByteString.copyFromUtf8(accountName));
    builder.setType(Protocol.AccountType.Normal);
    builder.setAddress(ByteString.copyFrom(Objects.requireNonNull(
        ByteArray.fromHexString(address))));
    builder.setBalance(balance);
    builder.setCreateTime(time);
    builder.setLatestOprationTime(time);
    builder.setAllowance(allowance);
    builder.setLatestWithdrawTime(time);
    builder.setCode(ByteString.copyFromUtf8(code));
    builder.setIsWitness(true);
    builder.setIsCommittee(true);
    builder.setAccountId(ByteString.copyFromUtf8(accountId));
    builder.setNetUsage(usage);
    builder.setFreeNetUsage(freeUsage);
    builder.setLatestConsumeTime(time);
    builder.setLatestConsumeFreeTime(time);
    builder.setNetWindowSize(windowSize);
    builder.setNetWindowOptimized(true);
    builder.setAssetOptimized(true);
    builder.setOldTronPower(oldTronPower);
    builder.setAcquiredDelegatedFrozenBalanceForBandwidth(bandwidth);
    builder.setDelegatedFrozenBalanceForBandwidth(bandwidth);
    builder.setDelegatedFrozenV2BalanceForBandwidth(bandwidth);
    builder.setAcquiredDelegatedFrozenV2BalanceForBandwidth(bandwidth);
    builder.setCodeHash(ByteString.copyFromUtf8(codeHash));

    List<Protocol.Vote> votes = new ArrayList<>();
    Protocol.Vote.Builder vote = Protocol.Vote.newBuilder();
    vote.setVoteAddress(ByteString.copyFrom(Objects.requireNonNull(ByteArray.fromHexString(sr1))));
    vote.setVoteCount(amount1);
    votes.add(vote.build());

    vote = Protocol.Vote.newBuilder();
    vote.setVoteAddress(ByteString.copyFrom(Objects.requireNonNull(ByteArray.fromHexString(sr2))));
    vote.setVoteCount(amount2);
    votes.add(vote.build());

    vote = Protocol.Vote.newBuilder();
    vote.setVoteAddress(ByteString.copyFrom(Objects.requireNonNull(ByteArray.fromHexString(sr3))));
    vote.setVoteCount(amount3);
    votes.add(vote.build());

    builder.addAllVotes(votes);

    Map<String, Long> assetMap = new HashMap<>();
    assetMap.put(assetName1, amount1);
    assetMap.put(assetName2, amount2);
    assetMap.put(assetName3, amount3);
    builder.putAllAsset(assetMap);

    Map<String, Long> assetV2Map = new HashMap<>();
    assetV2Map.put(assetId1, amount1);
    assetV2Map.put(assetId2, amount2);
    assetV2Map.put(assetId3, amount3);
    builder.putAllAssetV2(assetV2Map);

    List<Protocol.Account.Frozen> frozenList = new ArrayList<>();
    Protocol.Account.Frozen.Builder frozen = Protocol.Account.Frozen.newBuilder();
    frozen.setFrozenBalance(amount1);
    frozen.setExpireTime(time);
    frozenList.add(frozen.build());

    frozen = Protocol.Account.Frozen.newBuilder();
    frozen.setFrozenBalance(amount3);
    frozen.setExpireTime(time);
    frozenList.add(frozen.build());

    builder.addAllFrozen(frozenList);

    Protocol.Account.Frozen.Builder tronPowerBuilder = Protocol.Account.Frozen.newBuilder();
    tronPowerBuilder.setFrozenBalance(oldTronPower);
    tronPowerBuilder.setExpireTime(time);
    builder.setTronPower(tronPowerBuilder.build());

    List<Protocol.Account.Frozen> frozenSupplyList = new ArrayList<>();
    Protocol.Account.Frozen.Builder frozenSupplyBuilder = Protocol.Account.Frozen.newBuilder();
    frozenSupplyBuilder.setFrozenBalance(amount1);
    frozenSupplyBuilder.setExpireTime(time);
    frozenSupplyList.add(frozenSupplyBuilder.build());
    frozenSupplyBuilder.setFrozenBalance(amount3);
    frozenSupplyBuilder.setExpireTime(time);
    frozenSupplyList.add(frozenSupplyBuilder.build());
    builder.addAllFrozenSupply(frozenSupplyList);

    builder.setAssetIssuedName(ByteString.copyFromUtf8(assetIssuedName));
    builder.setAssetIssuedID(ByteString.copyFromUtf8(assetIssuedId));

    Map<String, Long> latestAssetOperationTime = new HashMap<>();
    latestAssetOperationTime.put(assetName1, time);
    latestAssetOperationTime.put(assetName2, time);
    builder.putAllLatestAssetOperationTime(latestAssetOperationTime);

    Map<String, Long> latestAssetOperationTimeV2 = new HashMap<>();
    latestAssetOperationTimeV2.put(assetId1, time);
    latestAssetOperationTimeV2.put(assetId2, time);
    builder.putAllLatestAssetOperationTimeV2(latestAssetOperationTimeV2);

    Map<String, Long> freeAssetNetUsage = new HashMap<>();
    freeAssetNetUsage.put(assetName1, amount1);
    freeAssetNetUsage.put(assetName2, amount2);
    freeAssetNetUsage.put(assetName3, amount3);
    builder.putAllFreeAssetNetUsage(freeAssetNetUsage);

    Map<String, Long> freeAssetNetUsageV2 = new HashMap<>();
    freeAssetNetUsageV2.put(assetId1, amount1);
    freeAssetNetUsageV2.put(assetId2, amount2);
    freeAssetNetUsageV2.put(assetId3, amount3);
    builder.putAllFreeAssetNetUsageV2(freeAssetNetUsageV2);

    Protocol.Account.AccountResource.Builder res = Protocol.Account.AccountResource.newBuilder();
    res.setEnergyUsage(usage);

    Protocol.Account.Frozen.Builder energyFrozenBuilder = Protocol.Account.Frozen.newBuilder();
    energyFrozenBuilder.setFrozenBalance(energy);
    energyFrozenBuilder.setExpireTime(time);
    res.setFrozenBalanceForEnergy(energyFrozenBuilder.build());

    res.setLatestConsumeTimeForEnergy(time);
    res.setAcquiredDelegatedFrozenBalanceForEnergy(energy);
    res.setDelegatedFrozenBalanceForEnergy(energy);
    res.setStorageLimit(limit);
    res.setStorageUsage(usage);
    res.setLatestExchangeStorageTime(time);
    res.setEnergyWindowSize(windowSize);
    res.setDelegatedFrozenV2BalanceForEnergy(energy);
    res.setAcquiredDelegatedFrozenV2BalanceForEnergy(energy);
    res.setEnergyWindowOptimized(true);
    builder.setAccountResource(res.build());

    Protocol.Permission.Builder ownerPermissionBuilder = Protocol.Permission.newBuilder();
    ownerPermissionBuilder.setType(Protocol.Permission.PermissionType.Owner);
    ownerPermissionBuilder.setId(id);
    ownerPermissionBuilder.setPermissionName("owner");
    ownerPermissionBuilder.setThreshold(threshold);
    ownerPermissionBuilder.setParentId(id);
    ownerPermissionBuilder.setOperations(ByteString.copyFromUtf8(operation));

    Protocol.Key.Builder keyBuilder = Protocol.Key.newBuilder();
    keyBuilder.setAddress(ByteString.copyFrom(Objects.requireNonNull(ByteArray.fromHexString(sr1))));
    keyBuilder.setWeight(weight);
    ownerPermissionBuilder.addKeys(keyBuilder.build());
    keyBuilder.setAddress(ByteString.copyFrom(Objects.requireNonNull(ByteArray.fromHexString(sr2))));
    keyBuilder.setWeight(0);
    ownerPermissionBuilder.addKeys(keyBuilder.build());

    builder.setOwnerPermission(ownerPermissionBuilder.build());

    Protocol.Permission.Builder witnessPermissionBuilder = Protocol.Permission.newBuilder();
    witnessPermissionBuilder.setType(Protocol.Permission.PermissionType.Witness);
    witnessPermissionBuilder.setId(id);
    witnessPermissionBuilder.setPermissionName("witness");
    witnessPermissionBuilder.setThreshold(threshold);
    witnessPermissionBuilder.setParentId(id);
    witnessPermissionBuilder.setOperations(ByteString.copyFrom(
        Objects.requireNonNull(ByteArray.fromString(operation))));

    keyBuilder = Protocol.Key.newBuilder();
    keyBuilder.setAddress(ByteString.copyFrom(Objects.requireNonNull(ByteArray.fromHexString(sr1))));
    keyBuilder.setWeight(weight);
    witnessPermissionBuilder.addKeys(keyBuilder.build());

    builder.setWitnessPermission(witnessPermissionBuilder.build());

    List<Protocol.Permission> activePermissions = new ArrayList<>();
    Protocol.Permission.Builder activePermissionBuilder = Protocol.Permission.newBuilder();
    activePermissionBuilder.setType(Protocol.Permission.PermissionType.Active);
    activePermissionBuilder.setId(id);
    activePermissionBuilder.setPermissionName("active0");
    activePermissionBuilder.setThreshold(threshold);
    activePermissionBuilder.setParentId(id);
    activePermissionBuilder.setOperations(ByteString.copyFromUtf8(operation));

    keyBuilder = Protocol.Key.newBuilder();
    keyBuilder.setAddress(ByteString.copyFrom(Objects.requireNonNull(ByteArray.fromHexString(sr1))));
    keyBuilder.setWeight(weight);
    activePermissionBuilder.addKeys(keyBuilder.build());

    keyBuilder = Protocol.Key.newBuilder();
    keyBuilder.setAddress(ByteString.copyFrom(Objects.requireNonNull(ByteArray.fromHexString(sr1))));
    keyBuilder.setWeight(0);
    activePermissionBuilder.addKeys(keyBuilder.build());

    activePermissions.add(activePermissionBuilder.build());

    activePermissionBuilder.setPermissionName("active1");
    activePermissionBuilder.setThreshold(0);
    activePermissions.add(activePermissionBuilder.build());
    builder.addAllActivePermission(activePermissions);

    List<Protocol.Account.FreezeV2> freezeV2List = new ArrayList<>();
    Protocol.Account.FreezeV2.Builder freezeV2Builder = Protocol.Account.FreezeV2.newBuilder();
    freezeV2Builder.setType(Common.ResourceCode.BANDWIDTH);
    freezeV2Builder.setAmount(amount1);
    freezeV2List.add(freezeV2Builder.build());

    freezeV2Builder = Protocol.Account.FreezeV2.newBuilder();
    freezeV2Builder.setType(Common.ResourceCode.ENERGY);
    freezeV2Builder.setAmount(amount2);
    freezeV2List.add(freezeV2Builder.build());

    freezeV2Builder = Protocol.Account.FreezeV2.newBuilder();
    freezeV2Builder.setType(Common.ResourceCode.ENERGY);
    freezeV2Builder.setAmount(amount3);
    freezeV2List.add(freezeV2Builder.build());

    builder.addAllFrozenV2(freezeV2List);

    List<Protocol.Account.UnFreezeV2> unFreezeV2List = new ArrayList<>();
    Protocol.Account.UnFreezeV2.Builder unFreezeV2Builder = Protocol.Account.UnFreezeV2.newBuilder();
    unFreezeV2Builder.setType(Common.ResourceCode.BANDWIDTH);
    unFreezeV2Builder.setUnfreezeAmount(amount1);
    unFreezeV2Builder.setUnfreezeExpireTime(time);
    unFreezeV2List.add(unFreezeV2Builder.build());

    unFreezeV2Builder = Protocol.Account.UnFreezeV2.newBuilder();
    unFreezeV2Builder.setType(Common.ResourceCode.ENERGY);
    unFreezeV2Builder.setUnfreezeAmount(amount2);
    unFreezeV2Builder.setUnfreezeExpireTime(time);
    unFreezeV2List.add(unFreezeV2Builder.build());

    unFreezeV2Builder = Protocol.Account.UnFreezeV2.newBuilder();
    unFreezeV2Builder.setType(Common.ResourceCode.ENERGY);
    unFreezeV2Builder.setUnfreezeAmount(amount3);
    unFreezeV2Builder.setUnfreezeExpireTime(time);
    unFreezeV2List.add(unFreezeV2Builder.build());

    builder.addAllUnfrozenV2(unFreezeV2List);
    Protocol.Account account = builder.build();

    AccountResult result = new AccountResult(account);


    Assert.assertEquals(toHex(accountName), result.getAccountName());
    Assert.assertEquals(toHex(account.getType().getNumber()), result.getType());
    Assert.assertEquals(address, result.getAddress());
    Assert.assertEquals(toHex(balance), result.getBalance());
    Assert.assertEquals(2, result.getVotes().size());
    Assert.assertEquals(sr1, result.getVotes().get(0).getVoteAddress());
    Assert.assertEquals(toHex(amount1), result.getVotes().get(0).getVoteCount());
    Assert.assertEquals(sr2, result.getVotes().get(1).getVoteAddress());
    Assert.assertEquals(toHex(amount2), result.getVotes().get(1).getVoteCount());
    Assert.assertEquals(2, result.getAssetV2().size());
    Assert.assertEquals(toHex(Long.parseUnsignedLong(assetId1)), result.getAssetV2().get(0).getKey());
    Assert.assertEquals(toHex(amount1), result.getAssetV2().get(0).getValue());
    Assert.assertEquals(toHex(Long.parseUnsignedLong(assetId2)), result.getAssetV2().get(1).getKey());
    Assert.assertEquals(toHex(amount2), result.getAssetV2().get(1).getValue());
    Assert.assertEquals(1, result.getFrozen().size());
    Assert.assertEquals(toHex(amount1), result.getFrozen().get(0).getFrozenBalance());
    Assert.assertEquals(toHex(time), result.getFrozen().get(0).getExpireTime());
    Assert.assertEquals(toHex(usage), result.getNetUsage());
    Assert.assertEquals(toHex(bandwidth), result.getAcquiredDelegatedFrozenBalanceForBandwidth());
    Assert.assertEquals(toHex(bandwidth), result.getDelegatedFrozenBalanceForBandwidth());
    Assert.assertEquals(toHex(oldTronPower), result.getOldTronPower());
    Assert.assertEquals(toHex(oldTronPower), result.getTronPower().getFrozenBalance());
    Assert.assertEquals(toHex(time), result.getTronPower().getExpireTime());
    Assert.assertEquals(toHex(1), result.getAssetOptimized());
    Assert.assertEquals(toHex(time), result.getCreateTime());
    Assert.assertEquals(toHex(time), result.getLatestOperationTime());
    Assert.assertEquals(toHex(allowance), result.getAllowance());
    Assert.assertEquals(toHex(time), result.getLatestWithdrawTime());
    Assert.assertEquals(toHex(time), result.getLatestConsumeTime());
    Assert.assertEquals(toHex(code), result.getCode());
    Assert.assertEquals(toHex(1), result.getIsWitness());
    Assert.assertEquals(toHex(1), result.getIsCommittee());
    Assert.assertEquals(toHex(codeHash), result.getCodeHash());
    Assert.assertEquals(1, result.getFrozenSupply().size());
    Assert.assertEquals(toHex(amount1), result.getFrozenSupply().get(0).getFrozenBalance());
    Assert.assertEquals(toHex(time), result.getFrozenSupply().get(0).getExpireTime());
    Assert.assertEquals(toHex(assetIssuedName), result.getAssetIssuedName());
    Assert.assertEquals(toHex(Long.parseUnsignedLong(assetIssuedId)), result.getAssetIssuedId());
    Assert.assertEquals(2, result.getLatestAssetOperationTimeV2().size());
    Assert.assertEquals(toHex(Long.parseUnsignedLong(assetId1)),
        result.getLatestAssetOperationTimeV2().get(0).getKey());
    Assert.assertEquals(toHex(time), result.getLatestAssetOperationTimeV2().get(0).getValue());
    Assert.assertEquals(toHex(Long.parseUnsignedLong(assetId2)),
        result.getLatestAssetOperationTimeV2().get(1).getKey());
    Assert.assertEquals(toHex(time), result.getLatestAssetOperationTimeV2().get(1).getValue());
    Assert.assertEquals(toHex(freeUsage), result.getFreeNetUsage());
    Assert.assertEquals(2, result.getFreeAssetNetUsageV2().size());
    Assert.assertEquals(toHex(Long.parseUnsignedLong(assetId1)),
        result.getFreeAssetNetUsageV2().get(0).getKey());
    Assert.assertEquals(toHex(amount1), result.getFreeAssetNetUsageV2().get(0).getValue());
    Assert.assertEquals(toHex(Long.parseUnsignedLong(assetId2)),
        result.getFreeAssetNetUsageV2().get(1).getKey());
    Assert.assertEquals(toHex(amount2), result.getFreeAssetNetUsageV2().get(1).getValue());
    Assert.assertEquals(toHex(time), result.getLatestConsumeTime());
    Assert.assertEquals(toHex(time), result.getLatestConsumeFreeTime());
    Assert.assertEquals(toHex(accountId), result.getAccountId());
    Assert.assertEquals(toHex(windowSize), result.getNetWindowSize());
    Assert.assertEquals(toHex(1), result.getNetWindowOptimized());

    AccountResult.AccountResource resource = result.getAccountResource();
    Assert.assertEquals(toHex(usage), resource.getEnergyUsage());
    Assert.assertEquals(toHex(energy), resource.getFrozenBalanceForEnergy().getFrozenBalance());
    Assert.assertEquals(toHex(time), resource.getFrozenBalanceForEnergy().getExpireTime());
    Assert.assertEquals(toHex(time), resource.getLatestConsumeTimeForEnergy());
    Assert.assertEquals(toHex(energy), resource.getAcquiredDelegatedFrozenBalanceForEnergy());
    Assert.assertEquals(toHex(energy), resource.getDelegatedFrozenBalanceForEnergy());
    Assert.assertEquals(toHex(limit), resource.getStorageLimit());
    Assert.assertEquals(toHex(usage), resource.getStorageUsage());
    Assert.assertEquals(toHex(time), resource.getLatestExchangeStorageTime());
    Assert.assertEquals(toHex(windowSize), resource.getEnergyWindowSize());
    Assert.assertEquals(toHex(energy), resource.getDelegatedFrozenV2BalanceForEnergy());
    Assert.assertEquals(toHex(energy), resource.getAcquiredDelegatedFrozenV2BalanceForEnergy());
    Assert.assertEquals(toHex(1), resource.getEnergyWindowOptimized());
    Assert.assertEquals(toHex(codeHash), result.getCodeHash());

    AccountResult.Permission ownerPermission = result.getOwnerPermission();
    Assert.assertEquals(toHex(Protocol.Permission.PermissionType.Owner.getNumber()),
        ownerPermission.getType());
    Assert.assertEquals(toHex(id), ownerPermission.getId());
    Assert.assertEquals(toHex("owner"), ownerPermission.getPermissionName());
    Assert.assertEquals(toHex(threshold), ownerPermission.getThreshold());
    Assert.assertEquals(toHex(id), ownerPermission.getParentId());
    Assert.assertEquals(toHex(operation), ownerPermission.getOperations());
    Assert.assertEquals(1, ownerPermission.getKeys().size());
    Assert.assertEquals(sr1, ownerPermission.getKeys().get(0).getAddress());
    Assert.assertEquals(toHex(weight), ownerPermission.getKeys().get(0).getWeight());

    AccountResult.Permission witnessPermission = result.getWitnessPermission();
    Assert.assertEquals(toHex(Protocol.Permission.PermissionType.Witness.getNumber()),
        witnessPermission.getType());
    Assert.assertEquals(toHex(id), witnessPermission.getId());
    Assert.assertEquals(toHex("witness"), witnessPermission.getPermissionName());
    Assert.assertEquals(toHex(threshold), witnessPermission.getThreshold());
    Assert.assertEquals(toHex(id), witnessPermission.getParentId());
    Assert.assertEquals(toHex(operation), witnessPermission.getOperations());
    Assert.assertEquals(1, witnessPermission.getKeys().size());
    Assert.assertEquals(sr1, witnessPermission.getKeys().get(0).getAddress());
    Assert.assertEquals(toHex(weight), witnessPermission.getKeys().get(0).getWeight());

    List<AccountResult.Permission> Permissions = result.getActivePermissions();
    Assert.assertEquals(1, Permissions.size());
    Assert.assertEquals(toHex(Protocol.Permission.PermissionType.Active.getNumber()),
        Permissions.get(0).getType());
    Assert.assertEquals(toHex(id), Permissions.get(0).getId());
    Assert.assertEquals(toHex("active0"), Permissions.get(0).getPermissionName());
    Assert.assertEquals(toHex(threshold), Permissions.get(0).getThreshold());
    Assert.assertEquals(toHex(id), Permissions.get(0).getParentId());
    Assert.assertEquals(toHex(operation), Permissions.get(0).getOperations());
    Assert.assertEquals(1, Permissions.get(0).getKeys().size());
    Assert.assertEquals(sr1, Permissions.get(0).getKeys().get(0).getAddress());
    Assert.assertEquals(toHex(weight), Permissions.get(0).getKeys().get(0).getWeight());
    Assert.assertEquals(2, result.getFrozenV2().size());
    Assert.assertEquals(toHex(Common.ResourceCode.BANDWIDTH.getNumber()),
        result.getFrozenV2().get(0).getType());
    Assert.assertEquals(toHex(amount1), result.getFrozenV2().get(0).getAmount());
    Assert.assertEquals(toHex(Common.ResourceCode.ENERGY.getNumber()),
        result.getFrozenV2().get(1).getType());
    Assert.assertEquals(toHex(amount2), result.getFrozenV2().get(1).getAmount());
    Assert.assertEquals(2, result.getUnfrozenV2().size());
    Assert.assertEquals(toHex(Common.ResourceCode.BANDWIDTH.getNumber()),
        result.getUnfrozenV2().get(0).getType());
    Assert.assertEquals(toHex(amount1), result.getUnfrozenV2().get(0).getUnfreezeAmount());
    Assert.assertEquals(toHex(time), result.getUnfrozenV2().get(0).getUnfreezeExpireTime());
    Assert.assertEquals(toHex(Common.ResourceCode.ENERGY.getNumber()),
        result.getUnfrozenV2().get(1).getType());
    Assert.assertEquals(toHex(amount2), result.getUnfrozenV2().get(1).getUnfreezeAmount());
    Assert.assertEquals(toHex(time), result.getUnfrozenV2().get(1).getUnfreezeExpireTime());
    Assert.assertEquals(toHex(bandwidth), result.getAcquiredDelegatedFrozenV2BalanceForBandwidth());
    Assert.assertEquals(toHex(bandwidth), result.getDelegatedFrozenV2BalanceForBandwidth());


  }
}
