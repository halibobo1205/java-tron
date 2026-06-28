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
 * change an {@code eth_call} RESULT (opcode availability + the two CHAINID-value flags) from the
 * DYNAMIC_PROPERTIES history at the target block, instead of using the latest values. Energy price
 * and every other parameter keep {@link HistoricalVmDynamicProperties}'s behaviour (historical fee
 * via {@code EnergyPriceHistory}, latest baseline for the rest).
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
 * genesis coverage. These 14 keys are all rooted in {@code DynamicKeyPolicy}; the energy/math flags
 * (dynamic-energy, strict-math) are intentionally NOT reconstructed because they do not change a
 * read-only call result (energy is discarded, the Maths wrappers are integer-domain identical).
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
}
