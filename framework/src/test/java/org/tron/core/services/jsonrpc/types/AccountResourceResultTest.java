package org.tron.core.services.jsonrpc.types;

import org.junit.Assert;
import org.junit.Test;
import org.tron.api.GrpcAPI;

public class AccountResourceResultTest {

  @Test
  public void testAccountResourceResult() {

       GrpcAPI.AccountResourceMessage resource = GrpcAPI.AccountResourceMessage.newBuilder()
        .setFreeNetUsed(100L) // 0x64
        .setFreeNetLimit(200L) // 0xc8
        .setNetUsed(300L) // 0x12c
        .setNetLimit(400L) // 0x190
        .putAssetNetUsed("1000387", 0L) // 0xf43c3
        .putAssetNetUsed("1000491", 300L)// 0xf442b, 0x12c
        .putAssetNetUsed("1000542", 100L) // 0xf445e, 0x64
        .putAssetNetUsed("1002798", 200L)  // 0xf4d2e, 0xc8
        .putAssetNetLimit("1000387", 0L) // 0xf43c3
        .putAssetNetLimit("1000491", 300L) // 0xf442b, 0x12c
        .putAssetNetLimit("1000542", 100L)// 0xf445e, 0x64
        .putAssetNetLimit("1002798", 200L) // 0xf4d2e, 0xc8
        .setTotalNetLimit(500L) // 0x1f4
        .setTotalNetWeight(600L) // 0x258
        .setTotalTronPowerWeight(700L) // 0x2bc
        .setTronPowerUsed(800L) // 0x320
        .setTronPowerLimit(900L) // 0x384
        .setEnergyUsed(1000L) // 0x3e8
        .setEnergyLimit(1100L) // 0x44c
        .setTotalEnergyLimit(1200L) //0x4b0
        .setTotalEnergyWeight(1300L) // 0x514
        .setStorageUsed(1400L) // 0x578
        .setStorageLimit(1500L) // 0x5dc
        .build();
    AccountResourceResult result = new AccountResourceResult(resource);
    Assert.assertEquals("0x64", result.getFreeNetUsed());
    Assert.assertEquals("0xc8", result.getFreeNetLimit());
    Assert.assertEquals("0x12c", result.getNetUsed());
    Assert.assertEquals("0x190", result.getNetLimit());
    Assert.assertEquals(3, result.getAssetNetUsed().size());
    Assert.assertEquals("0xf442b", result.getAssetNetUsed().get(0).getKey());
    Assert.assertEquals("0x12c", result.getAssetNetUsed().get(0).getValue());
    Assert.assertEquals("0xf445e", result.getAssetNetUsed().get(1).getKey());
    Assert.assertEquals("0x64", result.getAssetNetUsed().get(1).getValue());
    Assert.assertEquals("0xf4d2e", result.getAssetNetUsed().get(2).getKey());
    Assert.assertEquals("0xc8", result.getAssetNetUsed().get(2).getValue());
    Assert.assertEquals(3, result.getAssetNetLimit().size());
    Assert.assertEquals("0xf442b", result.getAssetNetLimit().get(0).getKey());
    Assert.assertEquals("0x12c", result.getAssetNetLimit().get(0).getValue());
    Assert.assertEquals("0xf445e", result.getAssetNetLimit().get(1).getKey());
    Assert.assertEquals("0x64", result.getAssetNetLimit().get(1).getValue());
    Assert.assertEquals("0xf4d2e", result.getAssetNetLimit().get(2).getKey());
    Assert.assertEquals("0xc8", result.getAssetNetLimit().get(2).getValue());
    Assert.assertEquals("0x1f4", result.getTotalNetLimit());
    Assert.assertEquals("0x258", result.getTotalNetWeight());
    Assert.assertEquals("0x2bc", result.getTotalTronPowerWeight());
    Assert.assertEquals("0x320", result.getTronPowerUsed());
    Assert.assertEquals("0x384", result.getTronPowerLimit());
    Assert.assertEquals("0x3e8", result.getEnergyUsed());
    Assert.assertEquals("0x44c", result.getEnergyLimit());
    Assert.assertEquals("0x4b0", result.getTotalEnergyLimit());
    Assert.assertEquals("0x514", result.getTotalEnergyWeight());
    Assert.assertEquals("0x578", result.getStorageUsed());
    Assert.assertEquals("0x5dc", result.getStorageLimit());
  }
}
