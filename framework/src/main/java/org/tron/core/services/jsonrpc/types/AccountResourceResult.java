package org.tron.core.services.jsonrpc.types;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.tron.api.GrpcAPI;

@Getter
@JsonPropertyOrder(alphabetic = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@EqualsAndHashCode(callSuper = false)
public class AccountResourceResult extends Result {

  private final String freeNetUsed;
  private final String freeNetLimit;
  private final String netUsed;
  private final String netLimit;
  private final List<Token10Result> assetNetUsed;
  private final List<Token10Result> assetNetLimit;
  private final String totalNetLimit;
  private final String totalNetWeight;
  private final String totalTronPowerWeight;
  private final String tronPowerUsed;
  private final String tronPowerLimit;
  private final String energyUsed;
  private final String energyLimit;
  private final String totalEnergyLimit;
  private final String totalEnergyWeight;
  private final String storageUsed;
  private final String storageLimit;


  public AccountResourceResult(GrpcAPI.AccountResourceMessage accountResource) {
    this.freeNetUsed = toHex(accountResource.getFreeNetUsed());
    this.freeNetLimit = toHex(accountResource.getFreeNetLimit());
    this.netUsed = toHex(accountResource.getNetUsed());
    this.netLimit = toHex(accountResource.getNetLimit());
    this.assetNetUsed = toHex(accountResource.getAssetNetUsedMap());
    this.assetNetLimit = toHex(accountResource.getAssetNetLimitMap());
    this.totalNetLimit = toHex(accountResource.getTotalNetLimit());
    this.totalNetWeight = toHex(accountResource.getTotalNetWeight());
    this.totalTronPowerWeight = toHex(accountResource.getTotalTronPowerWeight());
    this.tronPowerUsed = toHex(accountResource.getTronPowerUsed());
    this.tronPowerLimit = toHex(accountResource.getTronPowerLimit());
    this.energyUsed = toHex(accountResource.getEnergyUsed());
    this.energyLimit = toHex(accountResource.getEnergyLimit());
    this.totalEnergyLimit = toHex(accountResource.getTotalEnergyLimit());
    this.totalEnergyWeight = toHex(accountResource.getTotalEnergyWeight());
    this.storageUsed = toHex(accountResource.getStorageUsed());
    this.storageLimit = toHex(accountResource.getStorageLimit());
  }
}
