package org.tron.core.services.jsonrpc;

import static org.tron.core.Wallet.CONTRACT_VALIDATE_ERROR;
import static org.tron.core.services.jsonrpc.JsonRpcApiUtil.triggerCallContract;

import com.google.protobuf.ByteString;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.tron.common.utils.ByteArray;
import org.tron.core.Wallet;
import org.tron.core.archive.ArchiveService;
import org.tron.core.archive.query.QueryContext;
import org.tron.core.archive.query.QueryContextHolder;
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
import org.tron.core.services.jsonrpc.types.StructLog;
import org.tron.core.services.jsonrpc.types.TraceResult;
import org.tron.core.store.DynamicPropertiesStore;
import org.tron.core.store.StoreFactory;
import org.tron.core.store.VmDynamicProperties;
import org.tron.core.vm.archive.HistoricalTraceCallExecutor;
import org.tron.core.vm.archive.HistoricalTraceCallResult;
import org.tron.core.vm.archive.HistoricalVmExecutionException;
import org.tron.core.vm.archive.UnsupportedHistoricalStateException;
import org.tron.protos.Protocol.Block;
import org.tron.protos.Protocol.Transaction;
import org.tron.protos.Protocol.Transaction.Contract;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;
import org.tron.protos.Protocol.TransactionInfo;
import org.tron.protos.contract.SmartContractOuterClass.TriggerSmartContract;

/**
 * Serves the historical {@code debug_traceCall} path: resolves a non-latest block selector to an
 * archive state point, replays the call against the archived state with the native opcode tracer
 * on, and reconstructs a Geth/Besu {@code structLogs} {@link TraceResult}. It reuses the exact
 * block-resolution / reader / historical-config wiring of {@link HistoricalEthCallSupport}; only
 * the executor (trace variant) and the result rendering differ.
 *
 * <p>{@link #shouldUseArchive} mirrors the eth_call gate: a non-latest trace request must not fall
 * back to latest state. The latest path is out of scope here (java-tron has no latest
 * debug_traceCall yet), so a latest tag is rejected.
 */
public final class HistoricalTraceSupport {

  private final Wallet wallet;
  private final ArchiveService archiveService;

  public HistoricalTraceSupport(Wallet wallet, ArchiveService archiveService) {
    this.wallet = wallet;
    this.archiveService = archiveService;
  }

  /** True when the trace is historical and must not fall back to latest state. */
  public boolean shouldUseArchive(String blockNumOrTag) {
    return !JsonRpcApiUtil.LATEST_STR.equalsIgnoreCase(blockNumOrTag);
  }

  /** True when archiving is enabled; debug_traceTransaction is archive-only (no block selector). */
  public boolean isArchiveEnabled() {
    return archiveService.isEnabled();
  }

  public TraceResult traceCall(byte[] ownerAddress, byte[] contractAddress, long callValue,
      byte[] data, String blockNumOrTag) throws JsonRpcInvalidParamsException,
      JsonRpcInvalidRequestException, JsonRpcInternalException {
    return traceCall(ownerAddress, contractAddress, callValue, data, blockNumOrTag, null);
  }

  public TraceResult traceCall(byte[] ownerAddress, byte[] contractAddress, long callValue,
      byte[] data, String blockNumOrTag, Object traceOptions)
      throws JsonRpcInvalidParamsException, JsonRpcInvalidRequestException,
      JsonRpcInternalException {
    return traceCall(ownerAddress, contractAddress, callValue, data, blockNumOrTag, traceOptions,
        null);
  }

  public TraceResult traceCall(byte[] ownerAddress, byte[] contractAddress, long callValue,
      byte[] data, String blockNumOrTag, Object traceOptions, byte[] requestedBlockHash)
      throws JsonRpcInvalidParamsException, JsonRpcInvalidRequestException,
      JsonRpcInternalException {
    validateTraceCallRequest(blockNumOrTag, traceOptions);
    requireArchiveEnabled();
    // Read consistency is owned by the reader (openReader): a genesis-complete point runs against a
    // released-lock snapshot; a mid-chain point holds the read lock until the reader closes.
    return traceCallResolved(ownerAddress, contractAddress, callValue, data, blockNumOrTag,
        requestedBlockHash);
  }

  private TraceResult traceCallResolved(byte[] ownerAddress, byte[] contractAddress, long callValue,
      byte[] data, String blockNumOrTag, byte[] requestedBlockHash)
      throws JsonRpcInvalidParamsException, JsonRpcInvalidRequestException,
      JsonRpcInternalException {
    BlockCapsule[] resolvedBlock = new BlockCapsule[1];
    try {
      ArchiveStateReader admittedReader;
      if (JsonRpcApiUtil.FINALIZED_STR.equalsIgnoreCase(blockNumOrTag)) {
        admittedReader = archiveService.openBlockEndReader(wallet::getSolidBlockNum,
            blockNum -> resolveCanonicalBlock(blockNum, resolvedBlock));
      } else {
        long requestedBlockNum = JsonRpcApiUtil.isBlockTag(blockNumOrTag)
            ? JsonRpcApiUtil.parseBlockTag(blockNumOrTag, wallet)
            : JsonRpcApiUtil.parseBlockNumber(blockNumOrTag);
        admittedReader = archiveService.openBlockEndReader(
            requestedBlockNum, blockNum -> resolveCanonicalBlock(blockNum, resolvedBlock));
      }
      try (ArchiveStateReader reader = admittedReader) {
        BlockCapsule historicalBlock = resolvedBlock[0];
        if (requestedBlockHash != null) {
          requireBlockHashesMatch(
              reader.getPoint().getBlockHash(), requestedBlockHash, historicalBlock.getNum());
        }
        requireCanonicalBlockUnchanged(reader, historicalBlock.getNum());
        TriggerSmartContract trigger =
            triggerCallContract(ownerAddress, contractAddress, callValue, data, 0, null);
        TransactionCapsule trxCap = createHistoricalCallTransaction(trigger, historicalBlock);
        return runTrace(historicalBlock, reader, trxCap, true,
            "historical debug_traceCall");
      }
    } catch (BlockHeaderNotFoundException e) {
      throw new JsonRpcInvalidParamsException("block header not found");
    } catch (ArchiveReaderException e) {
      throw new JsonRpcInternalException(e.getMessage());
    }
  }

  /**
   * Historical {@code debug_traceTransaction}: replays the opcode execution of a PAST transaction's
   * contract call against the ARCHIVED pre-transaction state and renders the Geth/Besu structLogs.
   *
   * <p>The pre-tx archive point is {@code getAsOf(t - 1)} where {@code t} is the transaction's own
   * canonical txNum (from the archive index). {@code getAsOf} is inclusive-after, so {@code t - 1}
   * already includes the block's prepare-phase writes and every preceding user tx in that block
   * (L2 assigns ascending txNums: prepare, each user tx, finalize), while excluding the traced tx's
   * own writes -- so replaying the single tx against it is correct and no preceding tx is re-run.
   *
   * <p>The replay reuses the real transaction (so sender/value/data/feeLimit match: the feeLimit
   * governs the constant-call energy limit, so the trace stops exactly where the real tx did).
   * Only the contract's opcode execution is traced -- the non-constant fee / account-update
   * machinery is not replayed (the constant-call / trace path). A non-VM contract type produces no
   * opcode execution, so an empty-structLogs {@link TraceResult} is returned (Geth-friendly).
   */
  public TraceResult traceTransaction(byte[] txId, Object traceOptions)
      throws JsonRpcInvalidParamsException, JsonRpcInvalidRequestException,
      JsonRpcInternalException {
    return traceTransactionResolved(txId, traceOptions);
  }

  private TraceResult traceTransactionResolved(byte[] txId, Object traceOptions)
      throws JsonRpcInvalidParamsException, JsonRpcInvalidRequestException,
      JsonRpcInternalException {
    validateTraceOptions(traceOptions);
    requireArchiveEnabled();
    requireTxId(txId);
    TraceTransactionLookup lookup = new TraceTransactionLookup(txId);
    try (ArchiveStateReader reader = archiveService.openTransactionReader(txId, lookup::resolve)) {
      // The archive index is resolved before the callback performs any Wallet lookup. Once the
      // reader snapshot is fixed, re-read only the canonical header to close the final fork race.
      requireCanonicalBlockUnchanged(reader, reader.getPoint().getBlockNum());
      if (!lookup.traceable) {
        // Only TriggerSmartContract and CreateSmartContract produce TVM opcode traces; other types
        // (transfers, votes, etc.) have no VM execution to replay. Canonical validation still runs
        // first so a pre-archive or forked transaction cannot be reported as a successful empty
        // trace.
        reader.getQueryContext().recordResponseBytes(64L);
        return emptyTrace();
      }
      // Reuse the real transaction so feeLimit (hence the energy limit) is preserved.
      return runTrace(lookup.historicalBlock, reader, lookup.transaction, false,
          "historical debug_traceTransaction",
          lookup.transactionInfo.getReceipt().getEnergyUsageTotal());
    } catch (TraceLookupFailure e) {
      if (e.invalidParams) {
        throw new JsonRpcInvalidParamsException(e.getMessage());
      }
      throw new JsonRpcInternalException(e.getMessage());
    } catch (ArchiveReaderException e) {
      throw new JsonRpcInternalException(e.getMessage());
    }
  }

  private final class TraceTransactionLookup {

    private final byte[] requestedTxId;
    private TransactionCapsule transaction;
    private TransactionInfo transactionInfo;
    private BlockCapsule historicalBlock;
    private boolean traceable;

    private TraceTransactionLookup(byte[] requestedTxId) {
      this.requestedTxId = Arrays.copyOf(requestedTxId, requestedTxId.length);
    }

    private byte[] resolve(long archiveBlockNum) {
      ByteString txId = ByteString.copyFrom(requestedTxId);
      Transaction fetched = wallet.getTransactionById(txId);
      if (fetched == null) {
        throw TraceLookupFailure.invalidParams("transaction not found");
      }
      transaction = new TransactionCapsule(fetched);
      if (!Arrays.equals(transaction.getTransactionId().getBytes(), requestedTxId)) {
        throw TraceLookupFailure.internal("transaction hash mismatch");
      }
      if (fetched.getRawData().getContractCount() > 0) {
        Contract contract = fetched.getRawData().getContract(0);
        ContractType contractType = contract.getType();
        traceable = contractType == ContractType.TriggerSmartContract
            || contractType == ContractType.CreateSmartContract;
      }

      recordBackendRead();
      transactionInfo = wallet.getTransactionInfoById(txId);
      if (transactionInfo == null) {
        throw TraceLookupFailure.internal("transaction info not found");
      }
      if (transactionInfo.getBlockNumber() != archiveBlockNum) {
        throw TraceLookupFailure.internal("archive transaction position mismatch");
      }

      recordBackendRead();
      Block block = wallet.getBlockByNum(archiveBlockNum);
      if (block == null) {
        throw TraceLookupFailure.internal(
            "archive history unavailable for block " + archiveBlockNum);
      }
      historicalBlock = new BlockCapsule(block);
      return historicalBlock.getBlockId().getBytes();
    }
  }

  private static final class TraceLookupFailure extends RuntimeException {

    private final boolean invalidParams;

    private TraceLookupFailure(String message, boolean invalidParams) {
      super(message);
      this.invalidParams = invalidParams;
    }

    private static TraceLookupFailure invalidParams(String message) {
      return new TraceLookupFailure(message, true);
    }

    private static TraceLookupFailure internal(String message) {
      return new TraceLookupFailure(message, false);
    }
  }

  private static void requireTxId(byte[] txId) throws JsonRpcInvalidParamsException {
    if (txId == null || txId.length != ArchiveBlockRange.BLOCK_HASH_LENGTH) {
      throw new JsonRpcInvalidParamsException("invalid transaction hash");
    }
  }

  private static void requireBlockHash(byte[] blockHash, String source, long blockNum)
      throws JsonRpcInternalException {
    if (blockHash == null || blockHash.length != ArchiveBlockRange.BLOCK_HASH_LENGTH) {
      throw new JsonRpcInternalException(source + " has invalid block hash for block " + blockNum);
    }
  }

  private static void requireBlockHashesMatch(byte[] left, byte[] right, long blockNum)
      throws JsonRpcInternalException {
    requireBlockHash(left, "canonical block", blockNum);
    requireBlockHash(right, "requested block", blockNum);
    if (!Arrays.equals(left, right)) {
      throw new JsonRpcInternalException(
          "archive history hash mismatch for block " + blockNum);
    }
  }

  private byte[] canonicalBlockHash(long blockNum) {
    Block block = wallet.getBlockByNum(blockNum);
    return block == null ? null : new BlockCapsule(block).getBlockId().getBytes();
  }

  private void requireCanonicalBlockUnchanged(ArchiveStateReader reader, long blockNum)
      throws JsonRpcInternalException {
    reader.getQueryContext().recordBackendRead();
    requireBlockHashesMatch(canonicalBlockHash(blockNum), reader.getPoint().getBlockHash(),
        blockNum);
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

  private static void recordBackendRead() {
    QueryContext queryContext = QueryContextHolder.current();
    if (queryContext != null) {
      queryContext.recordBackendRead();
    }
  }

  private static void validateTraceOptions(Object traceOptions)
      throws JsonRpcInvalidParamsException {
    if (traceOptions == null) {
      return;
    }
    if (traceOptions instanceof Map && ((Map<?, ?>) traceOptions).isEmpty()) {
      return;
    }
    throw new JsonRpcInvalidParamsException(
        "debug trace options are not supported; only the default struct-log tracer is available");
  }

  private static void validateTraceCallRequest(String blockNumOrTag, Object traceOptions)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException {
    validateTraceOptions(traceOptions);
    if (JsonRpcApiUtil.LATEST_STR.equalsIgnoreCase(blockNumOrTag)) {
      throw new JsonRpcInternalException("historical debug_traceCall invoked for the latest tag");
    }
    validateHistoricalSelectorSyntax(blockNumOrTag);
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

  /**
   * Shared core: open the admitted archive reader, build the historical config view at the
   * block (energy fee and fork flags resolved from the archived point), run the trace executor
   * with the given {@link TransactionCapsule}, and render the Geth structLogs result.
   */
  private TraceResult runTrace(BlockCapsule historicalBlock, ArchiveStateReader reader,
      TransactionCapsule trxCap, boolean useConstantEnergyCap, String label)
      throws JsonRpcInvalidRequestException, JsonRpcInternalException {
    return runTrace(
        historicalBlock, reader, trxCap, useConstantEnergyCap, label, -1L);
  }

  private TraceResult runTrace(BlockCapsule historicalBlock, ArchiveStateReader reader,
      TransactionCapsule trxCap, boolean useConstantEnergyCap, String label, long gasOverride)
      throws JsonRpcInvalidRequestException, JsonRpcInternalException {
    DynamicPropertiesStore latestStore =
        StoreFactory.getInstance().getChainBaseManager().getDynamicPropertiesStore();

    try {
      boolean genesisComplete = reader.isGenesisComplete();
      long historicalEnergyFee =
          HistoricalArchiveVmDynamicProperties.resolveEnergyFee(reader, genesisComplete);
      VmDynamicProperties vmProperties = new HistoricalArchiveVmDynamicProperties(
          latestStore, historicalEnergyFee, reader, genesisComplete);
      HistoricalTraceCallResult result = new HistoricalTraceCallExecutor()
          .execute(reader, vmProperties, historicalBlock, trxCap, genesisComplete,
              useConstantEnergyCap);
      return toTraceResult(result, gasOverride);
    } catch (ContractValidateException e) {
      throw new JsonRpcInvalidRequestException(
          e.getMessage() == null ? CONTRACT_VALIDATE_ERROR : e.getMessage());
    } catch (ArchiveReaderException | HistoricalVmExecutionException
        | UnsupportedHistoricalStateException | ContractExeException e) {
      throw new JsonRpcInternalException(
          e.getMessage() == null ? label + " failed" : e.getMessage());
    }
  }

  /** A no-execution trace: empty structLogs, not failed (Geth returns this for a non-VM tx). */
  private static TraceResult emptyTrace() {
    return new TraceResult(0L, false, "", Collections.emptyList());
  }

  /** Renders the executor outcome as the Geth-shaped struct-log trace result. */
  public static TraceResult toTraceResult(HistoricalTraceCallResult result) {
    return toTraceResult(result, -1L);
  }

  private static TraceResult toTraceResult(HistoricalTraceCallResult result, long gasOverride) {
    QueryContext queryContext = result.getTrace() == null
        ? null : result.getTrace().historicalQueryContext();
    if (queryContext != null) {
      long rawBytes = result.getHReturn() == null ? 0L : result.getHReturn().length;
      queryContext.recordResponseBytes(rawBytes * 2L);
    }
    List<StructLog> structLogs = StructLogReconstructor.reconstruct(result.getTrace());
    // Geth returnValue is the return data as hex WITHOUT a 0x prefix (empty string when none).
    String returnValue = ByteArray.toHexString(result.getHReturn());
    long gas = gasOverride >= 0 ? gasOverride : result.getEnergyUsed();
    return new TraceResult(gas, result.isFailed(), returnValue, structLogs);
  }

  private void requireArchiveEnabled() throws JsonRpcInternalException {
    if (!archiveService.isEnabled()) {
      throw new JsonRpcInternalException("archive is not available");
    }
  }

  private static final class BlockHeaderNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;
  }

}
