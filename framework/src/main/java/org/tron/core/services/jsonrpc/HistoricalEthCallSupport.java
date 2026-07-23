package org.tron.core.services.jsonrpc;

import static org.tron.core.Wallet.CONTRACT_VALIDATE_ERROR;
import static org.tron.core.services.jsonrpc.JsonRpcApiUtil.triggerCallContract;

import com.google.protobuf.ByteString;
import java.util.Arrays;
import java.util.Objects;
import org.tron.common.utils.ByteArray;
import org.tron.core.Wallet;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.ArchiveService;
import org.tron.core.archive.query.QueryContext;
import org.tron.core.archive.query.QueryContextHolder;
import org.tron.core.archive.reader.ArchiveReaderException;
import org.tron.core.archive.reader.ArchiveStateReader;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.config.args.Args;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ContractValidateException;
import org.tron.core.exception.jsonrpc.JsonRpcInternalException;
import org.tron.core.exception.jsonrpc.JsonRpcInvalidParamsException;
import org.tron.core.exception.jsonrpc.JsonRpcInvalidRequestException;
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
 * <p>Account, code, storage, energy price, and VM execution parameters are read from the archive
 * at the target block. Mid-chain archives fail closed when an execution-affecting dynamic property
 * is missing because latest cannot be used as a historical value.
 */
public final class HistoricalEthCallSupport {

  private final Wallet wallet;
  private final ArchiveService archiveService;
  private final HistoricalConstantCallExecutor executor;
  private final ConstantCallGate constantCallGate;

  public HistoricalEthCallSupport(Wallet wallet, ArchiveService archiveService) {
    this(wallet, archiveService, new HistoricalConstantCallExecutor());
  }

  HistoricalEthCallSupport(Wallet wallet, ArchiveService archiveService,
      HistoricalConstantCallExecutor executor) {
    this(wallet, archiveService, executor,
        HistoricalEthCallSupport::requireConstantCallsEnabled);
  }

  HistoricalEthCallSupport(Wallet wallet, ArchiveService archiveService,
      HistoricalConstantCallExecutor executor, ConstantCallGate constantCallGate) {
    this.wallet = wallet;
    this.archiveService = archiveService;
    this.executor = Objects.requireNonNull(executor, "executor");
    this.constantCallGate = Objects.requireNonNull(constantCallGate, "constantCallGate");
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
    // The reader owns a from-genesis snapshot and validates its canonical epoch on close. A long
    // VM execution therefore does not block fork handling, while a stale result still fails closed.
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
        try {
          BlockCapsule historicalBlock = resolvedBlock[0];
          long blockNum = reader.getPoint().getBlockNum();
          byte[] canonicalBlockHash = reader.getPoint().getBlockHash();
          if (requestedBlockHash != null) {
            requireResolvedBlockHash(blockNum, canonicalBlockHash, requestedBlockHash);
          }
          requireCanonicalBlockUnchanged(reader, blockNum);

          // Coverage and canonical identity are proven before touching the live VM configuration or
          // constructing the call. This keeps unsupported historical points on the archive error
          // surface even when the request payload itself is incomplete.
          constantCallGate.requireEnabled();
          TriggerSmartContract trigger =
              triggerCallContract(ownerAddress, contractAddress, callValue, data, 0, null);
          boolean genesisComplete = reader.isGenesisComplete();
          // Execution parameters are read from the archive at the target point, so proposal writes
          // made later in the same block cannot leak into historical replay.
          long historicalEnergyFee =
              HistoricalArchiveVmDynamicProperties.resolveEnergyFee(reader, genesisComplete);
          VmDynamicProperties vmProperties = new HistoricalArchiveVmDynamicProperties(
              historicalEnergyFee, reader, genesisComplete);
          TransactionCapsule trxCap =
              createHistoricalCallTransaction(trigger, historicalBlock);
          HistoricalConstantCallResult result = executor.execute(
              reader, vmProperties, historicalBlock, trxCap, genesisComplete);
          // Linearize the response before returning it: a fork during VM execution invalidates the
          // old snapshot instead of allowing an orphan-state result to escape.
          requireCanonicalBlockUnchanged(reader, blockNum);
          if (result.isReverted()) {
            recordJsonHexResponse(reader, result.getResult());
            // Mirror the latest path: message carries the decoded revert reason, while data carries
            // the raw bytes.
            throw new JsonRpcInternalException(
                "REVERT opcode executed"
                    + TronJsonRpcImpl.tryDecodeRevertReason(result.getResult()),
                ByteArray.toJsonHex(result.getResult()));
          }
          if (result.getRuntimeError() != null && !result.getRuntimeError().isEmpty()) {
            throw new JsonRpcInternalException(result.getRuntimeError());
          }
          recordJsonHexResponse(reader, result.getResult());
          return ByteArray.toJsonHex(result.getResult());
        } catch (Exception | Error failure) {
          recordQueryFailure(reader, failure);
          throw failure;
        }
      }
    } catch (BlockHeaderNotFoundException e) {
      throw new JsonRpcInvalidParamsException("block header not found");
    } catch (ContractValidateException e) {
      // Match the latest eth_call path, which maps a validate failure to an invalid-request error.
      throw new JsonRpcInvalidRequestException(
          e.getMessage() == null ? CONTRACT_VALIDATE_ERROR : e.getMessage());
    } catch (ArchiveException | ArchiveReaderException | HistoricalVmExecutionException
        | UnsupportedHistoricalStateException | ContractExeException e) {
      throw new JsonRpcInternalException(
          e.getMessage() == null ? "historical eth_call failed" : e.getMessage(), e);
    }
  }

  private byte[] resolveCanonicalBlock(long blockNum, BlockCapsule[] resolvedBlock) {
    Block block = wallet.getBlockByNumWithoutCache(blockNum);
    if (block == null) {
      throw new BlockHeaderNotFoundException();
    }
    BlockCapsule blockCapsule = new BlockCapsule(block);
    resolvedBlock[0] = blockCapsule;
    return blockCapsule.getBlockId().getBytes();
  }

  private BlockCapsule resolveCanonicalBlock(byte[] requestedHash,
      BlockCapsule[] resolvedBlock) {
    Block requested = wallet.getBlockByIdWithoutCache(ByteString.copyFrom(requestedHash));
    if (requested == null) {
      throw new BlockHeaderNotFoundException();
    }
    long blockNum = requested.getBlockHeader().getRawData().getNumber();
    byte[] canonicalBlockHash = wallet.getBlockIdByNumWithoutCache(blockNum);
    if (!Arrays.equals(canonicalBlockHash, requestedHash)) {
      throw new BlockHeaderNotFoundException();
    }
    BlockCapsule blockCapsule = new BlockCapsule(requested);
    resolvedBlock[0] = blockCapsule;
    return blockCapsule;
  }

  private void requireCanonicalBlockUnchanged(ArchiveStateReader reader, long blockNum)
      throws JsonRpcInternalException {
    QueryContext queryContext = reader.getQueryContext();
    try (QueryContextHolder.Scope ignored = QueryContextHolder.attach(queryContext)) {
      byte[] currentBlockHash = wallet.getBlockIdByNumWithoutCache(blockNum);
      requireResolvedBlockHash(blockNum, currentBlockHash, reader.getPoint().getBlockHash());
    }
  }

  private static void recordJsonHexResponse(ArchiveStateReader reader, byte[] value) {
    long rawBytes = value == null ? 0L : value.length;
    reader.getQueryContext().recordResponseBytes(2L + rawBytes * 2L);
  }

  private static void recordQueryFailure(ArchiveStateReader reader, Throwable failure) {
    try {
      QueryContext queryContext = reader.getQueryContext();
      if (queryContext != null) {
        queryContext.recordFailure(failure);
      }
    } catch (Throwable attributionFailure) {
      if (failure != attributionFailure) {
        failure.addSuppressed(attributionFailure);
      }
    }
  }

  private void requireArchiveAvailable() throws JsonRpcInternalException {
    try {
      archiveService.validateAvailable();
    } catch (RuntimeException e) {
      throw new JsonRpcInternalException(e.getMessage(), e);
    }
  }

  private void requireArchiveEnabled() throws JsonRpcInternalException {
    if (!archiveService.isEnabled()) {
      throw new JsonRpcInternalException("archive is not available");
    }
  }

  static void requireConstantCallsEnabled() throws JsonRpcInvalidRequestException {
    if (!Args.getInstance().isSupportConstant()) {
      throw new JsonRpcInvalidRequestException("this node does not support constant");
    }
  }

  @FunctionalInterface
  interface ConstantCallGate {

    void requireEnabled() throws JsonRpcInvalidRequestException;
  }

  static void validateHistoricalSelectorSyntax(String blockNumOrTag)
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
