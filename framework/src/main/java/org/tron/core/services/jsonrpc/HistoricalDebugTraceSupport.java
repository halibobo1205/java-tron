package org.tron.core.services.jsonrpc;

import static org.tron.core.Wallet.CONTRACT_VALIDATE_ERROR;
import static org.tron.core.services.jsonrpc.JsonRpcApiUtil.triggerCallContract;

import com.google.protobuf.ByteString;
import java.math.BigInteger;
import java.util.Arrays;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.WalletUtil;
import org.tron.core.Wallet;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.ArchiveService;
import org.tron.core.archive.query.ArchiveQueryLimits;
import org.tron.core.archive.query.HistoricalQueryLimitException;
import org.tron.core.archive.query.QueryContext;
import org.tron.core.archive.query.QueryContextHolder;
import org.tron.core.archive.reader.ArchiveReaderException;
import org.tron.core.archive.reader.ArchiveStateReader;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.ContractCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.config.args.Args;
import org.tron.core.config.args.StorageConfig;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ContractValidateException;
import org.tron.core.exception.jsonrpc.JsonRpcInternalException;
import org.tron.core.exception.jsonrpc.JsonRpcInvalidParamsException;
import org.tron.core.exception.jsonrpc.JsonRpcInvalidRequestException;
import org.tron.core.services.jsonrpc.types.TraceResult;
import org.tron.core.store.VmDynamicProperties;
import org.tron.core.vm.Op;
import org.tron.core.vm.archive.HistoricalCallTraceSpec;
import org.tron.core.vm.archive.HistoricalDebugTraceExecutor;
import org.tron.core.vm.archive.HistoricalDebugTraceResult;
import org.tron.core.vm.archive.HistoricalVmExecutionException;
import org.tron.core.vm.archive.UnsupportedHistoricalStateException;
import org.tron.protos.Protocol.Block;
import org.tron.protos.Protocol.Transaction;
import org.tron.protos.Protocol.Transaction.Contract;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;
import org.tron.protos.Protocol.Transaction.Result.contractResult;
import org.tron.protos.contract.SmartContractOuterClass.CreateSmartContract;
import org.tron.protos.contract.SmartContractOuterClass.SmartContract;
import org.tron.protos.contract.SmartContractOuterClass.TriggerSmartContract;

/**
 * Historical archive-backed implementation of the V1 debug trace API. It never falls back to live
 * state and never persists opcode traces.
 */
public final class HistoricalDebugTraceSupport {

  private final Wallet wallet;
  private final ArchiveService archiveService;
  private final boolean enabled;
  private final long maxTraceSteps;
  private final long maxTraceBytes;
  private final HistoricalDebugTraceExecutor executor;

  public HistoricalDebugTraceSupport(Wallet wallet, ArchiveService archiveService,
      StorageConfig.ArchiveConfig.DebugConfig config) {
    this(wallet, archiveService, config, new HistoricalDebugTraceExecutor());
  }

  HistoricalDebugTraceSupport(Wallet wallet, ArchiveService archiveService,
      StorageConfig.ArchiveConfig.DebugConfig config,
      HistoricalDebugTraceExecutor executor) {
    this.wallet = wallet;
    this.archiveService = archiveService;
    this.enabled = config != null && config.isEnable();
    this.maxTraceSteps = config == null ? 1L : config.getMaxTraceSteps();
    this.maxTraceBytes = config == null ? 1L : config.getMaxTraceBytes();
    this.executor = executor;
  }

  public boolean isEnabled() {
    return enabled && archiveService.isEnabled();
  }

  public Object traceCall(byte[] ownerAddress, byte[] contractAddress, long callValue,
      byte[] data, String blockNumOrTag, byte[] requestedBlockHash,
      DebugTraceOptions options) throws JsonRpcInvalidParamsException,
      JsonRpcInvalidRequestException, JsonRpcInternalException {
    requireEnabledAndAvailable();
    if (blockNumOrTag != null && JsonRpcApiUtil.LATEST_STR.equalsIgnoreCase(blockNumOrTag)) {
      throw new JsonRpcInvalidParamsException(
          "debug_traceCall supports only committed historical blocks");
    }
    if (blockNumOrTag != null) {
      HistoricalEthCallSupport.validateHistoricalSelectorSyntax(blockNumOrTag);
    }
    requireConstantCallsEnabled();

    BlockCapsule[] resolvedBlock = new BlockCapsule[1];
    try {
      ArchiveStateReader admittedReader;
      if (blockNumOrTag == null) {
        admittedReader = archiveService.openBlockHashReader(requestedBlockHash,
            blockHash -> resolveCanonicalBlock(blockHash, resolvedBlock));
      } else if (JsonRpcApiUtil.FINALIZED_STR.equalsIgnoreCase(blockNumOrTag)) {
        admittedReader = archiveService.openBlockEndReader(wallet::getSolidBlockNum,
            blockNum -> resolveCanonicalBlockHash(blockNum, resolvedBlock));
      } else {
        long requestedBlockNum = JsonRpcApiUtil.isBlockTag(blockNumOrTag)
            ? JsonRpcApiUtil.parseBlockTag(blockNumOrTag, wallet)
            : JsonRpcApiUtil.parseBlockNumber(blockNumOrTag);
        admittedReader = archiveService.openBlockEndReader(requestedBlockNum,
            blockNum -> resolveCanonicalBlockHash(blockNum, resolvedBlock));
      }
      Throwable readerFailure = null;
      try {
        TriggerSmartContract trigger =
            triggerCallContract(ownerAddress, contractAddress, callValue, data, 0, null);
        TransactionCapsule trxCap =
            createHistoricalCallTransaction(trigger, resolvedBlock[0]);
        HistoricalCallTraceSpec callSpec = new HistoricalCallTraceSpec(
            Op.CALL, ownerAddress, contractAddress, data, BigInteger.valueOf(callValue));
        return runTrace(
            admittedReader, resolvedBlock[0], trxCap, callSpec, requestedBlockHash, options, true);
      } catch (ContractValidateException | ContractExeException | ArchiveReaderException
          | JsonRpcInternalException failure) {
        readerFailure = failure;
        recordQueryFailure(admittedReader, failure);
        throw failure;
      } catch (RuntimeException | Error failure) {
        readerFailure = failure;
        recordQueryFailure(admittedReader, failure);
        throw failure;
      } finally {
        closeReader(admittedReader, readerFailure);
      }
    } catch (BlockHeaderNotFoundException failure) {
      throw new JsonRpcInvalidParamsException("block header not found");
    } catch (ContractValidateException failure) {
      throw new JsonRpcInvalidRequestException(
          failure.getMessage() == null ? CONTRACT_VALIDATE_ERROR : failure.getMessage());
    } catch (ArchiveException | ArchiveReaderException | HistoricalVmExecutionException
        | UnsupportedHistoricalStateException | ContractExeException failure) {
      throw internalFailure("historical debug_traceCall failed", failure);
    }
  }

  public Object traceTransaction(byte[] txId, DebugTraceOptions options)
      throws JsonRpcInvalidParamsException, JsonRpcInvalidRequestException,
      JsonRpcInternalException {
    requireEnabledAndAvailable();
    BlockCapsule[] resolvedBlock = new BlockCapsule[1];
    try {
      ArchiveStateReader admittedReader = archiveService.openTransactionReader(
          txId, blockNum -> resolveCanonicalBlockHash(blockNum, resolvedBlock));
      Throwable readerFailure = null;
      try {
        Transaction transaction = wallet.getTransactionById(ByteString.copyFrom(txId));
        if (transaction == null) {
          throw new JsonRpcInvalidParamsException("transaction not found");
        }
        if (transaction.getRetCount() == 0) {
          throw new JsonRpcInternalException(
              "historical transaction execution result is missing");
        }
        if (transaction.getRet(0).getContractRet() == contractResult.OUT_OF_TIME) {
          throw new JsonRpcInvalidParamsException(
              "OUT_OF_TIME transactions cannot be replayed deterministically");
        }
        TransactionCapsule trxCap = new TransactionCapsule(transaction);
        if (!Arrays.equals(trxCap.getTransactionId().getBytes(), txId)) {
          throw new JsonRpcInternalException("archive transaction identity mismatch");
        }
        HistoricalCallTraceSpec callSpec = callSpec(transaction);
        return runTrace(
            admittedReader, resolvedBlock[0], trxCap, callSpec, null, options, false);
      } catch (JsonRpcInvalidParamsException | ContractValidateException
          | ContractExeException | ArchiveReaderException | JsonRpcInternalException failure) {
        readerFailure = failure;
        recordQueryFailure(admittedReader, failure);
        throw failure;
      } catch (RuntimeException | Error failure) {
        readerFailure = failure;
        recordQueryFailure(admittedReader, failure);
        throw failure;
      } finally {
        closeReader(admittedReader, readerFailure);
      }
    } catch (BlockHeaderNotFoundException failure) {
      throw new JsonRpcInvalidParamsException("block header not found");
    } catch (ContractValidateException failure) {
      throw new JsonRpcInvalidRequestException(
          failure.getMessage() == null ? CONTRACT_VALIDATE_ERROR : failure.getMessage());
    } catch (ArchiveException | ArchiveReaderException | HistoricalVmExecutionException
        | UnsupportedHistoricalStateException | ContractExeException failure) {
      throw internalFailure("historical debug_traceTransaction failed", failure);
    }
  }

  private Object runTrace(ArchiveStateReader reader, BlockCapsule historicalBlock,
      TransactionCapsule trxCap, HistoricalCallTraceSpec callSpec, byte[] requestedBlockHash,
      DebugTraceOptions options, boolean constantCall)
      throws ContractValidateException, ContractExeException,
      JsonRpcInternalException, ArchiveReaderException {
    long blockNum = reader.getPoint().getBlockNum();
    byte[] canonicalBlockHash = reader.getPoint().getBlockHash();
    if (requestedBlockHash != null) {
      requireResolvedBlockHash(blockNum, canonicalBlockHash, requestedBlockHash);
    }
    requireCanonicalBlockUnchanged(reader, blockNum);

    boolean genesisComplete = reader.isGenesisComplete();
    long historicalEnergyFee =
        HistoricalArchiveVmDynamicProperties.resolveEnergyFee(reader, genesisComplete);
    VmDynamicProperties vmProperties = new HistoricalArchiveVmDynamicProperties(
        historicalEnergyFee, reader, genesisComplete);
    long effectiveTraceBytes = effectiveTraceBytes(reader);
    if (effectiveTraceBytes <= 0L) {
      throw new HistoricalQueryLimitException(
          HistoricalQueryLimitException.Reason.RESOURCE_EXHAUSTED,
          HistoricalQueryLimitException.Limit.RESPONSE_BYTES,
          effectiveTraceBytes,
          128L,
          "historical debug trace materialization budget exceeded: limit="
              + effectiveTraceBytes + ", observed=128");
    }
    DebugTraceBudget budget = new DebugTraceBudget(effectiveTraceBytes);
    budget.reserve(128L);

    HistoricalDebugTraceResult result;
    Object response;
    if (options.getKind() == DebugTraceOptions.Kind.STRUCT_LOGS) {
      ArchiveStructLogCollector collector =
          new ArchiveStructLogCollector(options, maxTraceSteps, budget);
      result = executor.execute(
          reader, vmProperties, historicalBlock, trxCap, genesisComplete, constantCall,
          collector, null, null);
      requireReplayOutcome(trxCap.getInstance(), result, constantCall);
      int outputLength = result.isFailed() && !result.isReverted()
          ? 0 : result.getOutputLength();
      budget.reserve(outputLength * 2L + 2L);
      byte[] output = outputLength == 0 ? new byte[0] : result.getOutput();
      response = new TraceResult(
          result.getEnergyUsed(),
          result.isFailed(),
          ByteArray.toJsonHex(output),
          collector.getLogs());
    } else {
      ArchiveCallTraceCollector collector = new ArchiveCallTraceCollector(options, budget);
      ArchiveTraceStepLimiter stepLimiter = new ArchiveTraceStepLimiter(maxTraceSteps);
      result = executor.execute(
          reader, vmProperties, historicalBlock, trxCap, genesisComplete, constantCall,
          stepLimiter, collector, callSpec);
      requireReplayOutcome(trxCap.getInstance(), result, constantCall);
      response = collector.getRoot();
    }
    requireCanonicalBlockUnchanged(reader, blockNum);
    return response;
  }

  private long effectiveTraceBytes(ArchiveStateReader reader) {
    long queryLimit = reader.getQueryContext().getLimits().getMaxResponseBytes();
    return ArchiveQueryLimits.isUnlimited(queryLimit) || maxTraceBytes < queryLimit
        ? maxTraceBytes : queryLimit;
  }

  private HistoricalCallTraceSpec callSpec(Transaction transaction)
      throws JsonRpcInvalidParamsException {
    if (transaction.getRawData().getContractCount() == 0) {
      throw new JsonRpcInvalidParamsException("transaction has no contract");
    }
    Contract contract = transaction.getRawData().getContract(0);
    if (contract.getType() == ContractType.TriggerSmartContract) {
      TriggerSmartContract trigger =
          ContractCapsule.getTriggerContractFromTransaction(transaction);
      if (trigger == null) {
        throw new JsonRpcInvalidParamsException("invalid TriggerSmartContract transaction");
      }
      return new HistoricalCallTraceSpec(
          Op.CALL,
          trigger.getOwnerAddress().toByteArray(),
          trigger.getContractAddress().toByteArray(),
          trigger.getData().toByteArray(),
          BigInteger.valueOf(trigger.getCallValue()));
    }
    if (contract.getType() == ContractType.CreateSmartContract) {
      CreateSmartContract create =
          ContractCapsule.getSmartContractFromTransaction(transaction);
      if (create == null) {
        throw new JsonRpcInvalidParamsException("invalid CreateSmartContract transaction");
      }
      SmartContract newContract = create.getNewContract();
      return new HistoricalCallTraceSpec(
          Op.CREATE,
          create.getOwnerAddress().toByteArray(),
          WalletUtil.generateContractAddress(transaction),
          newContract.getBytecode().toByteArray(),
          BigInteger.valueOf(newContract.getCallValue()));
    }
    throw new JsonRpcInvalidParamsException(
        "transaction does not execute TVM bytecode");
  }

  private byte[] resolveCanonicalBlockHash(long blockNum, BlockCapsule[] resolvedBlock) {
    BlockCapsule block = resolveCanonicalBlock(blockNum, resolvedBlock);
    return block.getBlockId().getBytes();
  }

  private BlockCapsule resolveCanonicalBlock(long blockNum, BlockCapsule[] resolvedBlock) {
    Block block = wallet.getBlockByNumWithoutCache(blockNum);
    if (block == null) {
      throw new BlockHeaderNotFoundException();
    }
    BlockCapsule blockCapsule = new BlockCapsule(block);
    resolvedBlock[0] = blockCapsule;
    return blockCapsule;
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

  private void requireEnabledAndAvailable() throws JsonRpcInternalException {
    if (!isEnabled()) {
      throw new JsonRpcInternalException("archive debug tracing is not available");
    }
    try {
      archiveService.validateAvailable();
    } catch (RuntimeException failure) {
      throw new JsonRpcInternalException(failure.getMessage(), failure);
    }
  }

  private static void requireConstantCallsEnabled()
      throws JsonRpcInvalidRequestException {
    if (!Args.getInstance().isSupportConstant()) {
      throw new JsonRpcInvalidRequestException("this node does not support constant");
    }
  }

  private static void requireReplayOutcome(Transaction transaction,
      HistoricalDebugTraceResult result, boolean constantCall)
      throws JsonRpcInternalException {
    if (constantCall) {
      return;
    }
    if (transaction.getRetCount() == 0) {
      throw new JsonRpcInternalException(
          "historical transaction execution result is missing");
    }
    contractResult expected = transaction.getRet(0).getContractRet();
    boolean matches;
    if (expected == contractResult.SUCCESS) {
      matches = !result.isFailed();
    } else if (expected == contractResult.REVERT) {
      matches = result.isReverted();
    } else {
      matches = result.isFailed() && !result.isReverted();
    }
    if (!matches) {
      String actual = result.isReverted()
          ? contractResult.REVERT.name()
          : result.isFailed() ? "FAILED" : contractResult.SUCCESS.name();
      throw new JsonRpcInternalException(
          "historical transaction replay result mismatch: expected="
              + expected.name() + ", actual=" + actual);
    }
  }

  private static TransactionCapsule createHistoricalCallTransaction(
      TriggerSmartContract trigger, BlockCapsule historicalBlock) {
    TransactionCapsule trxCap =
        new TransactionCapsule(trigger, ContractType.TriggerSmartContract);
    long blockTimestamp = historicalBlock.getTimeStamp();
    trxCap.setReference(historicalBlock.getNum(), historicalBlock.getBlockId().getBytes());
    trxCap.setTimestamp(blockTimestamp);
    trxCap.setExpiration(blockTimestamp == Long.MAX_VALUE
        ? Long.MAX_VALUE : blockTimestamp + 1L);
    return trxCap;
  }

  private static void requireResolvedBlockHash(long blockNum, byte[] canonicalHash,
      byte[] requestedHash) throws JsonRpcInternalException {
    if (canonicalHash == null || canonicalHash.length != ArchiveBlockRange.BLOCK_HASH_LENGTH
        || requestedHash == null || requestedHash.length != ArchiveBlockRange.BLOCK_HASH_LENGTH
        || !Arrays.equals(canonicalHash, requestedHash)) {
      throw new JsonRpcInternalException(
          "archive history hash mismatch for block " + blockNum);
    }
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

  private static void closeReader(ArchiveStateReader reader, Throwable primaryFailure) {
    try {
      reader.close();
    } catch (RuntimeException | Error closeFailure) {
      if (primaryFailure == null) {
        throw closeFailure;
      }
      if (primaryFailure != closeFailure) {
        try {
          primaryFailure.addSuppressed(closeFailure);
        } catch (Throwable ignored) {
          // Preserve the execution failure when suppression itself cannot complete.
        }
      }
    }
  }

  private static JsonRpcInternalException internalFailure(String fallback, Exception failure) {
    return new JsonRpcInternalException(
        failure.getMessage() == null ? fallback : failure.getMessage(), failure);
  }

  private static final class BlockHeaderNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;
  }
}
