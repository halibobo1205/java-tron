package org.tron.core.services.jsonrpc;

import java.nio.charset.StandardCharsets;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.utils.ByteArray;
import org.tron.core.archive.reader.ArchiveReadResult;
import org.tron.core.archive.reader.ArchiveReaderException;
import org.tron.core.archive.reader.ArchiveStateReader;
import org.tron.core.store.VmDynamicProperties;

/**
 * Archive-backed historical {@link VmDynamicProperties}: reconstructs the hard-fork flags that can
 * change an {@code eth_call} RESULT from the DYNAMIC_PROPERTIES history at the target block, rather
 * than the latest values. These are the 12 opcode-availability flags, the 2 CHAINID-value flags,
 * plus FreezeV2 (FREEZEBALANCEV2 / DELEGATERESOURCE opcode validity), shielded-TRC20 (precompile
 * presence) and multi-sign (ADDRESS / ORIGIN return bytes) -- 17 in all. Energy price and every
 * other parameter keep {@link HistoricalVmDynamicProperties}'s behaviour (historical fee via
 * {@code EnergyPriceHistory}, latest baseline for the rest).
 *
 * <p>Each flag is read once at construction (reader open) via {@code getDynamicProperty}:
 * <ul>
 *   <li>PRESENT -- the value explicitly written by a proposal as of this block;</li>
 *   <li>MISSING -- no proposal change captured by this block. For a genesis-complete archive that
 *       is unambiguously the in-memory default (which mirrors the live getter's own default, so the
 *       reconstruction matches what the chain computed). For a mid-chain archive the change may
 *       predate coverage, so we fall back to the latest value -- the documented baseline, which is
 *       correct for the long-activated flags that dominate that case.</li>
 * </ul>
 *
 * <p>Only proposals ever write these keys (the constructor's default seed is not part of block
 * application, so it is never captured), which is what makes MISSING-means-default exact under
 * genesis coverage. All 17 keys are rooted VM_CONFIG in {@code DynamicKeyPolicy}. The energy/math
 * flags (dynamic-energy, strict-math) are intentionally NOT reconstructed -- they do not change a
 * read-only result (energy is discarded, the Maths wrappers are integer-domain identical).
 */
final class HistoricalArchiveVmDynamicProperties extends HistoricalVmDynamicProperties {

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
  private final long allowShieldedTRC20Transaction;
  private final long allowMultiSign;

  HistoricalArchiveVmDynamicProperties(VmDynamicProperties latest, long energyFee,
      ArchiveStateReader reader, boolean genesisComplete) throws ArchiveReaderException {
    super(latest, energyFee);
    CommonParameter p = CommonParameter.getInstance();
    this.allowTvmTransferTrc10 = resolve(reader, "ALLOW_TVM_TRANSFER_TRC10", genesisComplete,
        p.getAllowTvmTransferTrc10(), latest.getAllowTvmTransferTrc10());
    this.allowTvmConstantinople = resolve(reader, "ALLOW_TVM_CONSTANTINOPLE", genesisComplete,
        p.getAllowTvmConstantinople(), latest.getAllowTvmConstantinople());
    this.allowTvmSolidity059 = resolve(reader, "ALLOW_TVM_SOLIDITY_059", genesisComplete,
        p.getAllowTvmSolidity059(), latest.getAllowTvmSolidity059());
    this.allowTvmIstanbul = resolve(reader, "ALLOW_TVM_ISTANBUL", genesisComplete,
        p.getAllowTvmIstanbul(), latest.getAllowTvmIstanbul());
    this.allowTvmFreeze = resolve(reader, "ALLOW_TVM_FREEZE", genesisComplete,
        p.getAllowTvmFreeze(), latest.getAllowTvmFreeze());
    this.allowTvmVote = resolve(reader, "ALLOW_TVM_VOTE", genesisComplete,
        p.getAllowTvmVote(), latest.getAllowTvmVote());
    this.allowTvmLondon = resolve(reader, "ALLOW_TVM_LONDON", genesisComplete,
        p.getAllowTvmLondon(), latest.getAllowTvmLondon());
    this.allowTvmShangHai = resolve(reader, "ALLOW_TVM_SHANGHAI", genesisComplete,
        p.getAllowTvmShangHai(), latest.getAllowTvmShangHai());
    this.allowTvmCancun = resolve(reader, "ALLOW_TVM_CANCUN", genesisComplete,
        p.getAllowTvmCancun(), latest.getAllowTvmCancun());
    this.allowTvmBlob = resolve(reader, "ALLOW_TVM_BLOB", genesisComplete,
        p.getAllowTvmBlob(), latest.getAllowTvmBlob());
    // Osaka and selfdestruct-restriction default to a hard-coded 0L in their live getters.
    this.allowTvmOsaka = resolve(reader, "ALLOW_TVM_OSAKA", genesisComplete,
        0L, latest.getAllowTvmOsaka());
    this.allowTvmSelfdestructRestriction = resolve(reader, "ALLOW_TVM_SELFDESTRUCT_RESTRICTION",
        genesisComplete, 0L, latest.getAllowTvmSelfdestructRestriction());
    this.allowTvmCompatibleEvm = resolve(reader, "ALLOW_TVM_COMPATIBLE_EVM", genesisComplete,
        p.getAllowTvmCompatibleEvm(), latest.getAllowTvmCompatibleEvm());
    this.allowOptimizedReturnValueOfChainId = resolve(reader,
        "ALLOW_OPTIMIZED_RETURN_VALUE_OF_CHAIN_ID", genesisComplete,
        p.getAllowOptimizedReturnValueOfChainId(), latest.getAllowOptimizedReturnValueOfChainId());
    this.unfreezeDelayDays = resolve(reader, "UNFREEZE_DELAY_DAYS", genesisComplete,
        p.getUnfreezeDelayDays(), latest.supportUnfreezeDelay() ? 1L : 0L);
    this.allowShieldedTRC20Transaction = resolve(reader, "ALLOW_SHIELDED_TRC20_TRANSACTION",
        genesisComplete, p.getAllowShieldedTRC20Transaction(),
        latest.getAllowShieldedTRC20Transaction());
    this.allowMultiSign = resolve(reader, "ALLOW_MULTI_SIGN", genesisComplete,
        p.getAllowMultiSign(), latest.getAllowMultiSign());
  }

  private static long resolve(ArchiveStateReader reader, String key, boolean genesisComplete,
      long inMemoryDefault, long latestValue) throws ArchiveReaderException {
    byte[] canonicalKey = key.getBytes(StandardCharsets.US_ASCII);
    ArchiveReadResult<byte[]> r = reader.getDynamicProperty(canonicalKey);
    if (r.isPresent()) {
      // Stored as ByteArray.fromLong (8 bytes), same encoding the live getter decodes.
      return ByteArray.toLong(r.getValue());
    }
    // MISSING or TOMBSTONE (flags are never tombstoned): no captured change as of this block.
    return genesisComplete ? inMemoryDefault : latestValue;
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
  public long getAllowShieldedTRC20Transaction() {
    return allowShieldedTRC20Transaction;
  }

  @Override
  public long getAllowMultiSign() {
    return allowMultiSign;
  }
}
