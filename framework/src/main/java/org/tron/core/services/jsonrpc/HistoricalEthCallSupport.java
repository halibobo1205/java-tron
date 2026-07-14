package org.tron.core.services.jsonrpc;

import static org.tron.core.Wallet.CONTRACT_VALIDATE_ERROR;
import static org.tron.core.services.jsonrpc.JsonRpcApiUtil.triggerCallContract;

import com.google.protobuf.ByteString;
import java.util.Arrays;
import org.tron.common.utils.ByteArray;
import org.tron.core.Wallet;
import org.tron.core.archive.ArchiveService;
import org.tron.core.archive.reader.ArchiveReaderException;
import org.tron.core.archive.reader.ArchiveStateReader;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ContractValidateException;
import org.tron.core.exception.jsonrpc.JsonRpcInternalException;
import org.tron.core.exception.jsonrpc.JsonRpcInvalidParamsException;
import org.tron.core.exception.jsonrpc.JsonRpcInvalidRequestException;
import org.tron.core.store.DynamicPropertiesStore;
import org.tron.core.store.StoreFactory;
import org.tron.core.store.VmDynamicProperties;
import org.tron.core.vm.archive.HistoricalConstantCallExecutor;
import org.tron.core.vm.archive.HistoricalConstantCallResult;
import org.tron.core.vm.archive.HistoricalVmExecutionException;
import org.tron.core.vm.archive.UnsupportedHistoricalStateException;
import org.tron.protos.Protocol.Block;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;
import org.tron.protos.contract.SmartContractOuterClass.TriggerSmartContract;

/**
 * Serves the historical {@code eth_call} path: resolves a non-latest block selector to an archive
 * state point, replays the call against the archived state via the constant-call executor and
 * renders the result as JSON-RPC hex. {@code latest} / archive-disabled stay on the existing
 * latest-only logic. Non-latest selectors enter this support even when archive is disabled, so
 * disabled archive fails closed with the archive error surface rather than latest-only param
 * validation.
 *
 * <p>Account / code / storage are read historically, and the energy price is reconstructed from the
 * live {@code EnergyPriceHistory} (see {@link HistoricalVmDynamicProperties}), so {@code BASEFEE} /
 * {@code GASPRICE} replay at the value in force then. VM execution parameters are read from the
 * archive at the target block; mid-chain archives fail closed when an execution-affecting dynamic
 * property is missing because latest cannot be used as a historical value.
 */
public final class HistoricalEthCallSupport {

  private final Wallet wallet;
  private final ArchiveService archiveService;

  public HistoricalEthCallSupport(Wallet wallet, ArchiveService archiveService) {
    this.wallet = wallet;
    this.archiveService = archiveService;
  }

  /** True when the call is historical and must not fall back to latest state. */
  public boolean shouldUseArchive(String blockNumOrTag) {
    return !JsonRpcApiUtil.LATEST_STR.equalsIgnoreCase(blockNumOrTag);
  }

  void validateArchiveAvailable() throws JsonRpcInternalException {
    requireArchiveEnabled();
    requireArchiveAvailable();
  }

  public String call(byte[] ownerAddress, byte[] contractAddress, long callValue, byte[] data,
      String blockNumOrTag) throws JsonRpcInvalidParamsException, JsonRpcInvalidRequestException,
      JsonRpcInternalException {
    return call(ownerAddress, contractAddress, callValue, data, blockNumOrTag, null);
  }

  public String call(byte[] ownerAddress, byte[] contractAddress, long callValue, byte[] data,
      String blockNumOrTag, byte[] requestedBlockHash) throws JsonRpcInvalidParamsException,
      JsonRpcInvalidRequestException, JsonRpcInternalException {
    if (blockNumOrTag != null && JsonRpcApiUtil.LATEST_STR.equalsIgnoreCase(blockNumOrTag)) {
      throw new JsonRpcInternalException("historical eth_call invoked for the latest tag");
    }
    if (blockNumOrTag != null) {
      validateHistoricalSelectorSyntax(blockNumOrTag);
    }
    requireArchiveEnabled();
    // The reader (openReader) owns read consistency: a genesis-complete point runs against a
    // released-lock snapshot, a mid-chain point holds the read lock until the reader closes. So the
    // whole request no longer sits under the write-blocking lock -- only the snapshot capture does.
    try {
      BlockCapsule[] resolvedBlock = new BlockCapsule[1];
      ArchiveStateReader admittedReader;
      if (blockNumOrTag == null) {
        admittedReader = archiveService.openBlockHashReader(requestedBlockHash,
            blockHash -> resolveCanonicalBlock(blockHash, resolvedBlock));
      } else if (JsonRpcApiUtil.FINALIZED_STR.equalsIgnoreCase(blockNumOrTag)) {
        admittedReader = archiveService.openBlockEndReader(wallet::getSolidBlockNum,
            blockNum -> resolveCanonicalBlock(blockNum, resolvedBlock));
      } else {
        long requestedBlockNum = JsonRpcApiUtil.isBlockTag(blockNumOrTag)
            ? JsonRpcApiUtil.parseBlockTag(blockNumOrTag, wallet)
            : JsonRpcApiUtil.parseBlockNumber(blockNumOrTag);
        admittedReader = archiveService.openBlockEndReader(requestedBlockNum,
            blockNum -> resolveCanonicalBlock(blockNum, resolvedBlock));
      }
      try (ArchiveStateReader reader = admittedReader) {
        BlockCapsule historicalBlock = resolvedBlock[0];
        long blockNum = reader.getPoint().getBlockNum();
        byte[] canonicalBlockHash = reader.getPoint().getBlockHash();
        if (requestedBlockHash != null) {
          requireResolvedBlockHash(blockNum, canonicalBlockHash, requestedBlockHash);
        }
        reader.getQueryContext().recordBackendReads(2L);
        Block currentBlock = wallet.getBlockByNum(blockNum);
        byte[] currentBlockHash = currentBlock == null
            ? null
            : new BlockCapsule(currentBlock).getBlockId().getBytes();
        requireResolvedBlockHash(blockNum, currentBlockHash, reader.getPoint().getBlockHash());

        // Coverage and canonical identity are proven before touching the live VM configuration or
        // constructing the call. This keeps unsupported historical points on the archive error
        // surface even when the request payload itself is incomplete.
        DynamicPropertiesStore latestStore =
            StoreFactory.getInstance().getChainBaseManager().getDynamicPropertiesStore();
        TriggerSmartContract trigger =
            triggerCallContract(ownerAddress, contractAddress, callValue, data, 0, null);
        boolean genesisComplete = reader.isGenesisComplete();
        // Execution parameters are read from the archive at the target point, so proposal writes
        // made later in the same block cannot leak into historical replay.
        long historicalEnergyFee =
            HistoricalArchiveVmDynamicProperties.resolveEnergyFee(reader, genesisComplete);
        VmDynamicProperties vmProperties = new HistoricalArchiveVmDynamicProperties(
            latestStore, historicalEnergyFee, reader, genesisComplete);
        TransactionCapsule trxCap =
            createHistoricalCallTransaction(trigger, historicalBlock);
        HistoricalConstantCallResult result = new HistoricalConstantCallExecutor()
            .execute(reader, vmProperties, historicalBlock, trxCap, genesisComplete);
        if (result.isReverted()) {
          recordJsonHexResponse(reader, result.getResult());
          // Mirror the latest path: message carries the decoded revert reason, data the raw bytes.
          throw new JsonRpcInternalException(
              "REVERT opcode executed" + TronJsonRpcImpl.tryDecodeRevertReason(result.getResult()),
              ByteArray.toJsonHex(result.getResult()));
        }
        if (result.getRuntimeError() != null && !result.getRuntimeError().isEmpty()) {
          throw new JsonRpcInternalException(result.getRuntimeError());
        }
        recordJsonHexResponse(reader, result.getResult());
        return ByteArray.toJsonHex(result.getResult());
      }
    } catch (BlockHeaderNotFoundException e) {
      throw new JsonRpcInvalidParamsException("block header not found");
    } catch (ContractValidateException e) {
      // Match the latest eth_call path, which maps a validate failure to an invalid-request error.
      throw new JsonRpcInvalidRequestException(
          e.getMessage() == null ? CONTRACT_VALIDATE_ERROR : e.getMessage());
    } catch (ArchiveReaderException | HistoricalVmExecutionException
        | UnsupportedHistoricalStateException | ContractExeException e) {
      throw new JsonRpcInternalException(
          e.getMessage() == null ? "historical eth_call failed" : e.getMessage());
    }
  }

  private byte[] resolveCanonicalBlock(long blockNum, BlockCapsule[] resolvedBlock) {
    Block block = wallet.getBlockByNum(blockNum);
    if (block == null) {
      throw new BlockHeaderNotFoundException();
    }
    BlockCapsule blockCapsule = new BlockCapsule(block);
    resolvedBlock[0] = blockCapsule;
    return blockCapsule.getBlockId().getBytes();
  }

  private BlockCapsule resolveCanonicalBlock(byte[] requestedHash,
      BlockCapsule[] resolvedBlock) {
    Block requested = wallet.getBlockById(ByteString.copyFrom(requestedHash));
    if (requested == null) {
      throw new BlockHeaderNotFoundException();
    }
    long blockNum = requested.getBlockHeader().getRawData().getNumber();
    Block canonical = wallet.getBlockByNum(blockNum);
    if (canonical == null
        || !JsonRpcApiUtil.getBlockID(canonical).equals(JsonRpcApiUtil.getBlockID(requested))) {
      throw new BlockHeaderNotFoundException();
    }
    BlockCapsule blockCapsule = new BlockCapsule(requested);
    resolvedBlock[0] = blockCapsule;
    return blockCapsule;
  }

  private static void recordJsonHexResponse(ArchiveStateReader reader, byte[] value) {
    long rawBytes = value == null ? 0L : value.length;
    reader.getQueryContext().recordResponseBytes(2L + rawBytes * 2L);
  }

  private void requireArchiveAvailable() throws JsonRpcInternalException {
    try {
      archiveService.validateAvailable();
    } catch (RuntimeException e) {
      throw new JsonRpcInternalException(e.getMessage());
    }
  }

  private void requireArchiveEnabled() throws JsonRpcInternalException {
    if (!archiveService.isEnabled()) {
      throw new JsonRpcInternalException("archive is not available");
    }
  }

  private static void validateHistoricalSelectorSyntax(String blockNumOrTag)
      throws JsonRpcInvalidParamsException {
    if (JsonRpcApiUtil.PENDING_STR.equalsIgnoreCase(blockNumOrTag)) {
      throw new JsonRpcInvalidParamsException(JsonRpcApiUtil.TAG_PENDING_SUPPORT_ERROR);
    }
    if (JsonRpcApiUtil.SAFE_STR.equalsIgnoreCase(blockNumOrTag)) {
      throw new JsonRpcInvalidParamsException(JsonRpcApiUtil.TAG_SAFE_SUPPORT_ERROR);
    }
    if (JsonRpcApiUtil.isBlockTag(blockNumOrTag)) {
      return;
    }
    JsonRpcApiUtil.parseBlockNumber(blockNumOrTag);
  }

  private static TransactionCapsule createHistoricalCallTransaction(TriggerSmartContract trigger,
      BlockCapsule historicalBlock) {
    TransactionCapsule trxCap =
        new TransactionCapsule(trigger, ContractType.TriggerSmartContract);
    long blockTimestamp = historicalBlock.getTimeStamp();
    trxCap.setReference(historicalBlock.getNum(), historicalBlock.getBlockId().getBytes());
    trxCap.setTimestamp(blockTimestamp);
    trxCap.setExpiration(blockTimestamp == Long.MAX_VALUE ? Long.MAX_VALUE : blockTimestamp + 1);
    return trxCap;
  }

  private static void requireResolvedBlockHash(long blockNum, byte[] canonicalHash,
      byte[] requestedHash)
      throws JsonRpcInternalException {
    if (canonicalHash == null || canonicalHash.length != ArchiveBlockRange.BLOCK_HASH_LENGTH
        || requestedHash == null || requestedHash.length != ArchiveBlockRange.BLOCK_HASH_LENGTH
        || !Arrays.equals(canonicalHash, requestedHash)) {
      throw new JsonRpcInternalException(
          "archive history hash mismatch for block " + blockNum);
    }
  }

  private static final class BlockHeaderNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;
  }

}
