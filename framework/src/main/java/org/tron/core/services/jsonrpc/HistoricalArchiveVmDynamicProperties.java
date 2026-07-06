package org.tron.core.services.jsonrpc;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.tron.common.utils.ByteArray;
import org.tron.core.archive.reader.ArchiveReadResult;
import org.tron.core.archive.reader.ArchiveReadResult.Status;
import org.tron.core.archive.reader.ArchiveReaderException;
import org.tron.core.archive.reader.ArchiveStateReader;
import org.tron.core.config.Parameter.ForkBlockVersionEnum;
import org.tron.core.store.VmDynamicProperties;

/**
 * Archive-backed historical {@link VmDynamicProperties}: reconstructs the VM execution parameters
 * from DYNAMIC_PROPERTIES history at the target block, rather than the latest values. This covers
 * opcode/fork gates, CHAINID behaviour, VM enablement, fee/CPU limits, dynamic-energy knobs, and
 * VM arithmetic/resource flags. Energy price keeps {@link HistoricalVmDynamicProperties}'s
 * historical {@code EnergyPriceHistory} behaviour.
 *
 * <p>Each flag is read once at construction (reader open) via {@code getDynamicProperty}:
 * <ul>
 *   <li>PRESENT -- the value explicitly written by a proposal as of this block;</li>
 *   <li>MISSING -- no proposal change captured by this block. For hard-coded local defaults, a
 *       genesis-complete archive can reconstruct the in-memory default. For config/proposal-driven
 *       flags whose genesis value can vary by deployment, archive history must contain the value
 *       explicitly; otherwise execution fails closed.</li>
 * </ul>
 *
 * <p>Only proposals or dynamic-property maintenance writes these keys (the constructor's default
 * seed is not part of block application, so it is never captured), which is what makes
 * MISSING-means-default exact under genesis coverage. Execution-affecting keys are explicitly
 * rooted in {@code DynamicKeyPolicy}.
 */
public final class HistoricalArchiveVmDynamicProperties extends HistoricalVmDynamicProperties {

  private final long latestBlockHeaderNumber;
  private final long latestBlockHeaderTimestamp;
  private final long maintenanceTimeInterval;
  private final long currentCycleNumber;
  private final long totalNetLimit;
  private final long totalNetWeight;
  private final long totalEnergyCurrentLimit;
  private final long totalEnergyWeight;
  private final long totalTronPowerWeight;
  private final long allowCreationOfContracts;
  private final long maxFeeLimit;
  private final long maxCpuTimeOfOneTx;
  private final long allowTvmTransferTrc10;
  private final long allowTvmConstantinople;
  private final long allowTvmSolidity059;
  private final long allowTvmIstanbul;
  private final long allowTvmFreeze;
  private final long allowTvmVote;
  private final long allowTvmLondon;
  private final long allowTvmShangHai;
  private final long allowTvmCancun;
  private final long allowTvmBlob;
  private final long allowTvmOsaka;
  private final long allowTvmSelfdestructRestriction;
  private final long allowTvmCompatibleEvm;
  private final long allowOptimizedReturnValueOfChainId;
  // FreezeV2 / shielded-TRC20 / multi-sign also change a constant-call result (opcode validity,
  // precompile presence, ADDRESS / ORIGIN bytes), so they are rooted VM_CONFIG and reconstructed
  // at the target block exactly like the other flags.
  private final long unfreezeDelayDays;
  private final long allowNewResourceModel;
  private final long allowShieldedTRC20Transaction;
  private final long allowMultiSign;
  private final long allowHigherLimitForMaxCpuTimeOfOneTx;
  private final long allowDynamicEnergy;
  private final long dynamicEnergyThreshold;
  private final long dynamicEnergyIncreaseFactor;
  private final long dynamicEnergyMaxFactor;
  private final long allowEnergyAdjustment;
  private final long allowStrictMath;
  private final long consensusLogicOptimization;
  private final long allowHardenResourceCalculation;
  private final Map<Integer, byte[]> forkStatsByVersion;

  HistoricalArchiveVmDynamicProperties(VmDynamicProperties latest, long energyFee,
      ArchiveStateReader reader, boolean genesisComplete) throws ArchiveReaderException {
    super(latest, energyFee);
    this.latestBlockHeaderNumber = reader.getPoint().getBlockNum();
    this.latestBlockHeaderTimestamp = resolve(reader, "latest_block_header_timestamp",
        genesisComplete, 0L);
    this.maintenanceTimeInterval = resolveArchived(reader, "MAINTENANCE_TIME_INTERVAL");
    this.currentCycleNumber = resolve(reader, "CURRENT_CYCLE_NUMBER", genesisComplete,
        0L);
    this.totalNetLimit = resolve(reader, "TOTAL_NET_LIMIT", genesisComplete,
        43_200_000_000L);
    this.totalNetWeight = resolve(reader, "TOTAL_NET_WEIGHT", genesisComplete,
        0L);
    this.totalEnergyCurrentLimit = resolve(reader, "TOTAL_ENERGY_CURRENT_LIMIT", genesisComplete,
        50_000_000_000L);
    this.totalEnergyWeight = resolve(reader, "TOTAL_ENERGY_WEIGHT", genesisComplete,
        0L);
    this.totalTronPowerWeight = resolve(reader, "TOTAL_TRON_POWER_WEIGHT", genesisComplete,
        0L);
    this.allowCreationOfContracts = resolveArchived(reader, "ALLOW_CREATION_OF_CONTRACTS");
    this.maxFeeLimit = resolve(reader, "MAX_FEE_LIMIT", genesisComplete,
        1_000_000_000L);
    this.maxCpuTimeOfOneTx = resolve(reader, "MAX_CPU_TIME_OF_ONE_TX", genesisComplete,
        50L);
    this.allowTvmTransferTrc10 = resolveArchived(reader, "ALLOW_TVM_TRANSFER_TRC10");
    this.allowTvmConstantinople = resolveArchived(reader, "ALLOW_TVM_CONSTANTINOPLE");
    this.allowTvmSolidity059 = resolveArchived(reader, "ALLOW_TVM_SOLIDITY_059");
    this.allowTvmIstanbul = resolveArchived(reader, "ALLOW_TVM_ISTANBUL");
    this.allowTvmFreeze = resolveArchived(reader, "ALLOW_TVM_FREEZE");
    this.allowTvmVote = resolveArchived(reader, "ALLOW_TVM_VOTE");
    this.allowTvmLondon = resolveArchived(reader, "ALLOW_TVM_LONDON");
    this.allowTvmShangHai = resolveArchived(reader, "ALLOW_TVM_SHANGHAI");
    this.allowTvmCancun = resolveArchived(reader, "ALLOW_TVM_CANCUN");
    this.allowTvmBlob = resolveArchived(reader, "ALLOW_TVM_BLOB");
    // Osaka and selfdestruct-restriction default to a hard-coded 0L in their live getters.
    this.allowTvmOsaka = resolve(reader, "ALLOW_TVM_OSAKA", genesisComplete,
        0L);
    this.allowTvmSelfdestructRestriction = resolve(reader, "ALLOW_TVM_SELFDESTRUCT_RESTRICTION",
        genesisComplete, 0L);
    this.allowTvmCompatibleEvm = resolveArchived(reader, "ALLOW_TVM_COMPATIBLE_EVM");
    this.allowOptimizedReturnValueOfChainId =
        resolveArchived(reader, "ALLOW_OPTIMIZED_RETURN_VALUE_OF_CHAIN_ID");
    this.unfreezeDelayDays = resolveArchived(reader, "UNFREEZE_DELAY_DAYS");
    this.allowNewResourceModel = resolveArchived(reader, "ALLOW_NEW_RESOURCE_MODEL");
    this.allowShieldedTRC20Transaction =
        resolveArchived(reader, "ALLOW_SHIELDED_TRC20_TRANSACTION");
    this.allowMultiSign = resolveArchived(reader, "ALLOW_MULTI_SIGN");
    this.allowHigherLimitForMaxCpuTimeOfOneTx =
        resolveArchived(reader, "ALLOW_HIGHER_LIMIT_FOR_MAX_CPU_TIME_OF_ONE_TX");
    this.allowDynamicEnergy = resolveArchived(reader, "ALLOW_DYNAMIC_ENERGY");
    this.dynamicEnergyThreshold = resolveArchived(reader, "DYNAMIC_ENERGY_THRESHOLD");
    this.dynamicEnergyIncreaseFactor = resolveArchived(reader, "DYNAMIC_ENERGY_INCREASE_FACTOR");
    this.dynamicEnergyMaxFactor = resolveArchived(reader, "DYNAMIC_ENERGY_MAX_FACTOR");
    this.allowEnergyAdjustment = resolveArchived(reader, "ALLOW_ENERGY_ADJUSTMENT");
    this.allowStrictMath = resolveArchived(reader, "ALLOW_STRICT_MATH");
    this.consensusLogicOptimization = resolveArchived(reader, "CONSENSUS_LOGIC_OPTIMIZATION");
    this.allowHardenResourceCalculation =
        resolveArchived(reader, "ALLOW_HARDEN_RESOURCE_CALCULATION");
    this.forkStatsByVersion = resolveForkStats(reader, genesisComplete,
        ForkBlockVersionEnum.VERSION_4_7_1, ForkBlockVersionEnum.VERSION_4_8_1_1);
  }

  static long resolveEnergyFee(ArchiveStateReader reader, boolean genesisComplete)
      throws ArchiveReaderException {
    return resolve(reader, "ENERGY_FEE", genesisComplete,
        HistoricalVmDynamicProperties.DEFAULT_ENERGY_FEE);
  }

  public static void validateGenesisArchiveRows(VmDynamicProperties latest,
      ArchiveStateReader reader) throws ArchiveReaderException {
    long energyFee = resolveEnergyFee(reader, true);
    new HistoricalArchiveVmDynamicProperties(latest, energyFee, reader, true);
  }

  private static long resolve(ArchiveStateReader reader, String key, boolean genesisComplete,
      long inMemoryDefault) throws ArchiveReaderException {
    byte[] canonicalKey = key.getBytes(StandardCharsets.US_ASCII);
    ArchiveReadResult<byte[]> r = reader.getDynamicProperty(canonicalKey);
    if (r.isPresent()) {
      // Stored as ByteArray.fromLong (8 bytes), same encoding the live getter decodes.
      byte[] value = r.getValue();
      if (value.length != Long.BYTES) {
        throw new ArchiveReaderException(ArchiveReaderException.Reason.CORRUPT_VALUE,
            "archive dynamic property " + key + " has invalid length " + value.length);
      }
      return ByteArray.toLong(value);
    }
    if (r.getStatus() == Status.TOMBSTONE) {
      throw new ArchiveReaderException(ArchiveReaderException.Reason.CORRUPT_VALUE,
          "archive dynamic property " + key + " is tombstoned");
    }
    // MISSING: no captured change as of this block.
    if (genesisComplete) {
      return inMemoryDefault;
    }
    throw new ArchiveReaderException(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE,
        "archive dynamic property " + key + " is unknown before mid-chain coverage");
  }

  private static long resolveArchived(ArchiveStateReader reader, String key)
      throws ArchiveReaderException {
    byte[] canonicalKey = key.getBytes(StandardCharsets.US_ASCII);
    ArchiveReadResult<byte[]> r = reader.getDynamicProperty(canonicalKey);
    if (r.isPresent()) {
      byte[] value = r.getValue();
      if (value.length != Long.BYTES) {
        throw new ArchiveReaderException(ArchiveReaderException.Reason.CORRUPT_VALUE,
            "archive dynamic property " + key + " has invalid length " + value.length);
      }
      return ByteArray.toLong(value);
    }
    if (r.getStatus() == Status.TOMBSTONE) {
      throw new ArchiveReaderException(ArchiveReaderException.Reason.CORRUPT_VALUE,
          "archive dynamic property " + key + " is tombstoned");
    }
    throw new ArchiveReaderException(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE,
        "archive dynamic property " + key + " is missing from archived history");
  }

  private static Map<Integer, byte[]> resolveForkStats(ArchiveStateReader reader,
      boolean genesisComplete, ForkBlockVersionEnum... versions) throws ArchiveReaderException {
    Map<Integer, byte[]> statsByVersion = new HashMap<>();
    for (ForkBlockVersionEnum version : versions) {
      int value = version.getValue();
      String key = "FORK_VERSION_" + value;
      ArchiveReadResult<byte[]> r =
          reader.getDynamicProperty(key.getBytes(StandardCharsets.US_ASCII));
      if (r.isPresent()) {
        statsByVersion.put(value, r.getValue().clone());
        continue;
      }
      if (r.getStatus() == Status.TOMBSTONE) {
        throw new ArchiveReaderException(ArchiveReaderException.Reason.CORRUPT_VALUE,
            "archive dynamic property " + key + " is tombstoned");
      }
      if (!genesisComplete) {
        throw new ArchiveReaderException(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE,
            "archive dynamic property " + key + " is unknown before mid-chain coverage");
      }
    }
    return statsByVersion;
  }

  @Override
  public long getLatestBlockHeaderNumber() {
    return latestBlockHeaderNumber;
  }

  @Override
  public long getLatestBlockHeaderTimestamp() {
    return latestBlockHeaderTimestamp;
  }

  @Override
  public long getMaintenanceTimeInterval() {
    return maintenanceTimeInterval;
  }

  @Override
  public byte[] statsByVersion(int version) {
    byte[] stats = forkStatsByVersion.get(version);
    return stats == null ? null : stats.clone();
  }

  @Override
  public long getCurrentCycleNumber() {
    return currentCycleNumber;
  }

  @Override
  public long getTotalNetLimit() {
    return totalNetLimit;
  }

  @Override
  public long getTotalNetWeight() {
    return totalNetWeight;
  }

  @Override
  public long getTotalEnergyCurrentLimit() {
    return totalEnergyCurrentLimit;
  }

  @Override
  public long getTotalEnergyWeight() {
    return totalEnergyWeight;
  }

  @Override
  public long getTotalTronPowerWeight() {
    return totalTronPowerWeight;
  }

  @Override
  public boolean supportVM() {
    return allowCreationOfContracts == 1L;
  }

  @Override
  public long getMaxFeeLimit() {
    return maxFeeLimit;
  }

  @Override
  public long getMaxCpuTimeOfOneTx() {
    return maxCpuTimeOfOneTx;
  }

  @Override
  public long getAllowTvmTransferTrc10() {
    return allowTvmTransferTrc10;
  }

  @Override
  public long getAllowTvmConstantinople() {
    return allowTvmConstantinople;
  }

  @Override
  public long getAllowTvmSolidity059() {
    return allowTvmSolidity059;
  }

  @Override
  public long getAllowTvmIstanbul() {
    return allowTvmIstanbul;
  }

  @Override
  public long getAllowTvmFreeze() {
    return allowTvmFreeze;
  }

  @Override
  public long getAllowTvmVote() {
    return allowTvmVote;
  }

  @Override
  public long getAllowTvmLondon() {
    return allowTvmLondon;
  }

  @Override
  public long getAllowTvmShangHai() {
    return allowTvmShangHai;
  }

  @Override
  public long getAllowTvmCancun() {
    return allowTvmCancun;
  }

  @Override
  public long getAllowTvmBlob() {
    return allowTvmBlob;
  }

  @Override
  public long getAllowTvmOsaka() {
    return allowTvmOsaka;
  }

  @Override
  public long getAllowTvmSelfdestructRestriction() {
    return allowTvmSelfdestructRestriction;
  }

  @Override
  public long getAllowTvmCompatibleEvm() {
    return allowTvmCompatibleEvm;
  }

  @Override
  public long getAllowOptimizedReturnValueOfChainId() {
    return allowOptimizedReturnValueOfChainId;
  }

  @Override
  public boolean supportUnfreezeDelay() {
    // allowTvmFreezeV2 = unfreezeDelayDays > 0; gates FREEZEBALANCEV2 / DELEGATERESOURCE opcodes.
    return unfreezeDelayDays > 0;
  }

  @Override
  public long getUnfreezeDelayDays() {
    return unfreezeDelayDays;
  }

  @Override
  public long getAllowNewResourceModel() {
    return allowNewResourceModel;
  }

  @Override
  public boolean supportAllowNewResourceModel() {
    return allowNewResourceModel == 1L;
  }

  @Override
  public long getAllowShieldedTRC20Transaction() {
    return allowShieldedTRC20Transaction;
  }

  @Override
  public long getAllowMultiSign() {
    return allowMultiSign;
  }

  @Override
  public long getAllowHigherLimitForMaxCpuTimeOfOneTx() {
    return allowHigherLimitForMaxCpuTimeOfOneTx;
  }

  @Override
  public long getAllowDynamicEnergy() {
    return allowDynamicEnergy;
  }

  @Override
  public long getDynamicEnergyThreshold() {
    return dynamicEnergyThreshold;
  }

  @Override
  public long getDynamicEnergyIncreaseFactor() {
    return dynamicEnergyIncreaseFactor;
  }

  @Override
  public long getDynamicEnergyMaxFactor() {
    return dynamicEnergyMaxFactor;
  }

  @Override
  public long getAllowEnergyAdjustment() {
    return allowEnergyAdjustment;
  }

  @Override
  public long getAllowStrictMath() {
    return allowStrictMath;
  }

  @Override
  public long getConsensusLogicOptimization() {
    return consensusLogicOptimization;
  }

  @Override
  public long getAllowHardenResourceCalculation() {
    return allowHardenResourceCalculation;
  }
}
