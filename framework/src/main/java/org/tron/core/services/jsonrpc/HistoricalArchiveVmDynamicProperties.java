package org.tron.core.services.jsonrpc;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import org.tron.common.parameter.CommonParameter;
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
 *   <li>MISSING -- no proposal change captured by this block. For a genesis-complete archive that
 *       is unambiguously the in-memory default (which mirrors the live getter's own default, so the
 *       reconstruction matches what the chain computed). For a mid-chain archive it is unknown:
 *       the key may have changed before coverage or after the queried block, so execution fails
 *       closed rather than using latest as a historical value.</li>
 * </ul>
 *
 * <p>Only proposals or dynamic-property maintenance writes these keys (the constructor's default
 * seed is not part of block application, so it is never captured), which is what makes
 * MISSING-means-default exact under genesis coverage. Execution-affecting keys are explicitly
 * rooted in {@code DynamicKeyPolicy}.
 */
final class HistoricalArchiveVmDynamicProperties extends HistoricalVmDynamicProperties {

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
    CommonParameter p = CommonParameter.getInstance();
    this.latestBlockHeaderNumber = reader.getPoint().getBlockNum();
    this.latestBlockHeaderTimestamp = resolve(reader, "latest_block_header_timestamp",
        genesisComplete, 0L, latest::getLatestBlockHeaderTimestamp);
    this.maintenanceTimeInterval = resolve(reader, "MAINTENANCE_TIME_INTERVAL", genesisComplete,
        p.getMaintenanceTimeInterval(), latest::getMaintenanceTimeInterval);
    this.currentCycleNumber = resolve(reader, "CURRENT_CYCLE_NUMBER", genesisComplete,
        0L, latest::getCurrentCycleNumber);
    this.totalNetLimit = resolve(reader, "TOTAL_NET_LIMIT", genesisComplete,
        43_200_000_000L, latest::getTotalNetLimit);
    this.totalNetWeight = resolve(reader, "TOTAL_NET_WEIGHT", genesisComplete,
        0L, latest::getTotalNetWeight);
    this.totalEnergyCurrentLimit = resolve(reader, "TOTAL_ENERGY_CURRENT_LIMIT", genesisComplete,
        50_000_000_000L, latest::getTotalEnergyCurrentLimit);
    this.totalEnergyWeight = resolve(reader, "TOTAL_ENERGY_WEIGHT", genesisComplete,
        0L, latest::getTotalEnergyWeight);
    this.totalTronPowerWeight = resolve(reader, "TOTAL_TRON_POWER_WEIGHT", genesisComplete,
        0L, latest::getTotalTronPowerWeight);
    this.allowCreationOfContracts = resolve(reader, "ALLOW_CREATION_OF_CONTRACTS",
        genesisComplete, p.getAllowCreationOfContracts(), () -> latest.supportVM() ? 1L : 0L);
    this.maxFeeLimit = resolve(reader, "MAX_FEE_LIMIT", genesisComplete,
        1_000_000_000L, latest::getMaxFeeLimit);
    this.maxCpuTimeOfOneTx = resolve(reader, "MAX_CPU_TIME_OF_ONE_TX", genesisComplete,
        50L, latest::getMaxCpuTimeOfOneTx);
    this.allowTvmTransferTrc10 = resolve(reader, "ALLOW_TVM_TRANSFER_TRC10", genesisComplete,
        p.getAllowTvmTransferTrc10(), latest::getAllowTvmTransferTrc10);
    this.allowTvmConstantinople = resolve(reader, "ALLOW_TVM_CONSTANTINOPLE", genesisComplete,
        p.getAllowTvmConstantinople(), latest::getAllowTvmConstantinople);
    this.allowTvmSolidity059 = resolve(reader, "ALLOW_TVM_SOLIDITY_059", genesisComplete,
        p.getAllowTvmSolidity059(), latest::getAllowTvmSolidity059);
    this.allowTvmIstanbul = resolve(reader, "ALLOW_TVM_ISTANBUL", genesisComplete,
        p.getAllowTvmIstanbul(), latest::getAllowTvmIstanbul);
    this.allowTvmFreeze = resolve(reader, "ALLOW_TVM_FREEZE", genesisComplete,
        p.getAllowTvmFreeze(), latest::getAllowTvmFreeze);
    this.allowTvmVote = resolve(reader, "ALLOW_TVM_VOTE", genesisComplete,
        p.getAllowTvmVote(), latest::getAllowTvmVote);
    this.allowTvmLondon = resolve(reader, "ALLOW_TVM_LONDON", genesisComplete,
        p.getAllowTvmLondon(), latest::getAllowTvmLondon);
    this.allowTvmShangHai = resolve(reader, "ALLOW_TVM_SHANGHAI", genesisComplete,
        p.getAllowTvmShangHai(), latest::getAllowTvmShangHai);
    this.allowTvmCancun = resolve(reader, "ALLOW_TVM_CANCUN", genesisComplete,
        p.getAllowTvmCancun(), latest::getAllowTvmCancun);
    this.allowTvmBlob = resolve(reader, "ALLOW_TVM_BLOB", genesisComplete,
        p.getAllowTvmBlob(), latest::getAllowTvmBlob);
    // Osaka and selfdestruct-restriction default to a hard-coded 0L in their live getters.
    this.allowTvmOsaka = resolve(reader, "ALLOW_TVM_OSAKA", genesisComplete,
        0L, latest::getAllowTvmOsaka);
    this.allowTvmSelfdestructRestriction = resolve(reader, "ALLOW_TVM_SELFDESTRUCT_RESTRICTION",
        genesisComplete, 0L, latest::getAllowTvmSelfdestructRestriction);
    this.allowTvmCompatibleEvm = resolve(reader, "ALLOW_TVM_COMPATIBLE_EVM", genesisComplete,
        p.getAllowTvmCompatibleEvm(), latest::getAllowTvmCompatibleEvm);
    this.allowOptimizedReturnValueOfChainId = resolve(reader,
        "ALLOW_OPTIMIZED_RETURN_VALUE_OF_CHAIN_ID", genesisComplete,
        p.getAllowOptimizedReturnValueOfChainId(), latest::getAllowOptimizedReturnValueOfChainId);
    this.unfreezeDelayDays = resolve(reader, "UNFREEZE_DELAY_DAYS", genesisComplete,
        p.getUnfreezeDelayDays(), () -> latest.supportUnfreezeDelay() ? 1L : 0L);
    this.allowNewResourceModel = resolve(reader, "ALLOW_NEW_RESOURCE_MODEL", genesisComplete,
        p.getAllowNewResourceModel(), latest::getAllowNewResourceModel);
    this.allowShieldedTRC20Transaction = resolve(reader, "ALLOW_SHIELDED_TRC20_TRANSACTION",
        genesisComplete, p.getAllowShieldedTRC20Transaction(),
        latest::getAllowShieldedTRC20Transaction);
    this.allowMultiSign = resolve(reader, "ALLOW_MULTI_SIGN", genesisComplete,
        p.getAllowMultiSign(), latest::getAllowMultiSign);
    this.allowHigherLimitForMaxCpuTimeOfOneTx = resolve(reader,
        "ALLOW_HIGHER_LIMIT_FOR_MAX_CPU_TIME_OF_ONE_TX", genesisComplete,
        p.getAllowHigherLimitForMaxCpuTimeOfOneTx(),
        latest::getAllowHigherLimitForMaxCpuTimeOfOneTx);
    this.allowDynamicEnergy = resolve(reader, "ALLOW_DYNAMIC_ENERGY", genesisComplete,
        p.getAllowDynamicEnergy(), latest::getAllowDynamicEnergy);
    this.dynamicEnergyThreshold = resolve(reader, "DYNAMIC_ENERGY_THRESHOLD", genesisComplete,
        p.getDynamicEnergyThreshold(), latest::getDynamicEnergyThreshold);
    this.dynamicEnergyIncreaseFactor = resolve(reader, "DYNAMIC_ENERGY_INCREASE_FACTOR",
        genesisComplete, p.getDynamicEnergyIncreaseFactor(),
        latest::getDynamicEnergyIncreaseFactor);
    this.dynamicEnergyMaxFactor = resolve(reader, "DYNAMIC_ENERGY_MAX_FACTOR", genesisComplete,
        p.getDynamicEnergyMaxFactor(), latest::getDynamicEnergyMaxFactor);
    this.allowEnergyAdjustment = resolve(reader, "ALLOW_ENERGY_ADJUSTMENT", genesisComplete,
        p.getAllowEnergyAdjustment(), latest::getAllowEnergyAdjustment);
    this.allowStrictMath = resolve(reader, "ALLOW_STRICT_MATH", genesisComplete,
        p.getAllowStrictMath(), latest::getAllowStrictMath);
    this.consensusLogicOptimization = resolve(reader, "CONSENSUS_LOGIC_OPTIMIZATION",
        genesisComplete, p.getConsensusLogicOptimization(), latest::getConsensusLogicOptimization);
    this.allowHardenResourceCalculation = resolve(reader, "ALLOW_HARDEN_RESOURCE_CALCULATION",
        genesisComplete, 0L, latest::getAllowHardenResourceCalculation);
    this.forkStatsByVersion = resolveForkStats(reader, genesisComplete,
        ForkBlockVersionEnum.VERSION_4_7_1, ForkBlockVersionEnum.VERSION_4_8_1_1);
  }

  private static long resolve(ArchiveStateReader reader, String key, boolean genesisComplete,
      long inMemoryDefault, LongSupplier latestValue) throws ArchiveReaderException {
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
