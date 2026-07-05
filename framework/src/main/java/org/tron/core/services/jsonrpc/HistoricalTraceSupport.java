package org.tron.core.services.jsonrpc;

import static org.tron.core.Wallet.CONTRACT_VALIDATE_ERROR;
import static org.tron.core.services.jsonrpc.JsonRpcApiUtil.triggerCallContract;

import com.google.protobuf.ByteString;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.tron.common.utils.ByteArray;
import org.tron.core.Wallet;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveService;
import org.tron.core.archive.DefaultArchiveService;
import org.tron.core.archive.reader.ArchiveReaderException;
import org.tron.core.archive.reader.ArchiveStatePoint;
import org.tron.core.archive.reader.ArchiveStateReader;
import org.tron.core.archive.reader.ArchiveStateReaderFactory;
import org.tron.core.archive.reader.JsonRpcArchiveStatePointResolver;
import org.tron.core.archive.reader.ResolvedArchiveStatePoint;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.archive.txnum.ArchiveTxNumIndex;
import org.tron.core.archive.txnum.ArchiveTxPosition;
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
 * <p>{@link #shouldUseArchive} mirrors the eth_call gate: a trace is served from the archive only
 * when archiving is enabled and the selector is non-latest. The latest path is out of scope here
 * (java-tron has no latest debug_traceCall yet), so a latest tag is rejected.
 */
public final class HistoricalTraceSupport {

  private final Wallet wallet;
  private final ArchiveService archiveService;
  private final JsonRpcArchiveStatePointResolver resolver;

  public HistoricalTraceSupport(Wallet wallet, ArchiveService archiveService) {
    this.wallet = wallet;
    this.archiveService = archiveService;
    this.resolver = new JsonRpcArchiveStatePointResolver(wallet, archiveService);
  }

  /** True when the trace must be served from the archive (enabled + a non-latest selector). */
  public boolean shouldUseArchive(String blockNumOrTag) {
    return archiveService.isEnabled()
        && !JsonRpcApiUtil.LATEST_STR.equalsIgnoreCase(blockNumOrTag);
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
    try (ArchiveService.ReadGuard ignored = readGuard()) {
      return traceCallLocked(ownerAddress, contractAddress, callValue, data, blockNumOrTag,
          traceOptions);
    }
  }

  private TraceResult traceCallLocked(byte[] ownerAddress, byte[] contractAddress, long callValue,
      byte[] data, String blockNumOrTag, Object traceOptions) throws JsonRpcInvalidParamsException,
      JsonRpcInvalidRequestException, JsonRpcInternalException {
    validateTraceOptions(traceOptions);
    if (JsonRpcApiUtil.LATEST_STR.equalsIgnoreCase(blockNumOrTag)) {
      throw new JsonRpcInternalException("historical debug_traceCall invoked for the latest tag");
    }
    ResolvedArchiveStatePoint resolved = resolver.resolveBlockEnd(blockNumOrTag);
    if (resolved.isLatest()) {
      throw new JsonRpcInternalException("historical debug_traceCall invoked for the latest tag");
    }
    ArchiveStatePoint point = resolved.getPoint();

    Block block = wallet.getBlockByNum(point.getBlockNum());
    if (block == null) {
      throw new JsonRpcInternalException("archive history unavailable for block "
          + point.getBlockNum());
    }
    BlockCapsule historicalBlock = new BlockCapsule(block);
    TriggerSmartContract trigger =
        triggerCallContract(ownerAddress, contractAddress, callValue, data, 0, null);
    TransactionCapsule trxCap;
    try {
      trxCap = wallet.createTransactionCapsule(trigger, ContractType.TriggerSmartContract);
    } catch (ContractValidateException e) {
      throw new JsonRpcInvalidRequestException(
          e.getMessage() == null ? CONTRACT_VALIDATE_ERROR : e.getMessage());
    }
    return runTrace(historicalBlock, point, trxCap, true, "historical debug_traceCall");
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
    try (ArchiveService.ReadGuard ignored = readGuard()) {
      return traceTransactionLocked(txId, traceOptions);
    }
  }

  private TraceResult traceTransactionLocked(byte[] txId, Object traceOptions)
      throws JsonRpcInvalidParamsException, JsonRpcInvalidRequestException,
      JsonRpcInternalException {
    validateTraceOptions(traceOptions);
    ByteString txIdBs = ByteString.copyFrom(txId);
    Transaction tx = wallet.getTransactionById(txIdBs);
    if (tx == null) {
      throw new JsonRpcInvalidParamsException("transaction not found");
    }
    ContractType contractType = null;
    boolean traceable = false;
    if (tx.getRawData().getContractCount() > 0) {
      Contract contract = tx.getRawData().getContract(0);
      contractType = contract.getType();
      traceable = contractType == ContractType.TriggerSmartContract;
    }

    TransactionInfo info = wallet.getTransactionInfoById(txIdBs);
    if (info == null) {
      throw new JsonRpcInternalException("transaction info not found");
    }
    long blockNum = info.getBlockNumber();

    ArchiveTxNumIndex index = txNumIndex();
    OptionalLong txNum = index.findTxNumByTxId(txId);
    if (!txNum.isPresent()) {
      throw new JsonRpcInternalException("transaction not in archive");
    }
    long t = txNum.getAsLong();
    if (t < 1) {
      throw new JsonRpcInternalException("transaction has no pre-state archive point");
    }
    Optional<ArchiveTxPosition> position = index.getPosition(t);
    if (!position.isPresent()) {
      throw new JsonRpcInternalException("archive tx-position missing for transaction");
    }
    ArchiveTxPosition archivePosition = position.get();
    if (archivePosition.getPhase() != ArchivePhase.USER_TX
        || archivePosition.getBlockNum() != blockNum
        || !Arrays.equals(archivePosition.getTxId(), txId)) {
      throw new JsonRpcInternalException("archive transaction position mismatch");
    }
    Optional<ArchiveBlockRange> range = index.getBlockRange(blockNum);
    if (!range.isPresent()) {
      long first = index.getFirstArchivedBlock();
      if (first >= 0 && blockNum < first) {
        throw new JsonRpcInternalException("archive history unavailable for block " + blockNum
            + "; lowest supported block is " + first);
      }
      throw new JsonRpcInternalException("archive history unavailable for block " + blockNum);
    }
    ArchiveBlockRange blockRange = range.get();
    if (t < blockRange.getFirstTxNum() || t > blockRange.getLastTxNum()) {
      throw new JsonRpcInternalException("archive transaction range mismatch");
    }

    Block block = wallet.getBlockByNum(blockNum);
    if (block == null) {
      throw new JsonRpcInternalException("archive history unavailable for block " + blockNum);
    }
    BlockCapsule historicalBlock = new BlockCapsule(block);
    byte[] blockHash = historicalBlock.getBlockId().getBytes();
    byte[] archivedHash = blockRange.getBlockHash();
    requireBlockHash(archivedHash, "archive history", blockNum);
    requireBlockHash(blockHash, "canonical block", blockNum);
    if (!Arrays.equals(archivedHash, blockHash)) {
      throw new JsonRpcInternalException("archive history hash mismatch for block " + blockNum);
    }
    if (contractType == ContractType.CreateSmartContract) {
      throw new JsonRpcInternalException(
          "historical debug_traceTransaction does not support CreateSmartContract");
    }
    if (!traceable) {
      // Only a TriggerSmartContract produces a TVM opcode trace; other types (transfers, votes,
      // etc.) have no constant-call execution to replay. Still do the archive/canonical validation
      // first so a pre-archive or forked transaction cannot be reported as a successful empty
      // trace.
      return emptyTrace();
    }
    // The pre-tx state is read as-of t - 1 (getAsOf inclusive-after; t is the tx's own txNum whose
    // writes must NOT be included). The reader reads getAsOf(point.getTxNum()).
    ArchiveStatePoint point = ArchiveStatePoint.blockEnd(blockNum, blockHash, t - 1);
    // Reuse the real transaction so feeLimit (hence the energy limit) is preserved.
    TransactionCapsule trxCap = new TransactionCapsule(tx);
    return runTrace(historicalBlock, point, trxCap, false, "historical debug_traceTransaction",
        info.getReceipt().getEnergyUsageTotal());
  }

  private static void requireBlockHash(byte[] blockHash, String source, long blockNum)
      throws JsonRpcInternalException {
    if (blockHash == null || blockHash.length != ArchiveBlockRange.BLOCK_HASH_LENGTH) {
      throw new JsonRpcInternalException(source + " has invalid block hash for block " + blockNum);
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

  /**
   * Shared core: open the archive reader at {@code point}, build the historical config view at the
   * block (energy fee resolved from the block timestamp + genesis-complete flag), run the trace
   * executor with the given {@link TransactionCapsule}, and render the Geth structLogs result.
   */
  private TraceResult runTrace(BlockCapsule historicalBlock, ArchiveStatePoint point,
      TransactionCapsule trxCap, boolean useConstantEnergyCap, String label)
      throws JsonRpcInvalidRequestException, JsonRpcInternalException {
    return runTrace(historicalBlock, point, trxCap, useConstantEnergyCap, label, -1L);
  }

  private TraceResult runTrace(BlockCapsule historicalBlock, ArchiveStatePoint point,
      TransactionCapsule trxCap, boolean useConstantEnergyCap, String label, long gasOverride)
      throws JsonRpcInvalidRequestException, JsonRpcInternalException {
    DynamicPropertiesStore latestStore =
        StoreFactory.getInstance().getChainBaseManager().getDynamicPropertiesStore();
    long historicalEnergyFee = HistoricalVmDynamicProperties.resolveHistoricalEnergyFee(
        historicalBlock.getTimeStamp(), latestStore.getEnergyPriceHistory());
    boolean genesisComplete = isGenesisComplete();

    try (ArchiveStateReader reader = readerFactory().open(point)) {
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

  private ArchiveTxNumIndex txNumIndex() throws JsonRpcInternalException {
    if (!(archiveService instanceof DefaultArchiveService)) {
      throw new JsonRpcInternalException("archive is not available");
    }
    requireArchiveAvailable();
    return ((DefaultArchiveService) archiveService).getTxNumIndex();
  }

  /** Renders the executor outcome as the Geth-shaped struct-log trace result. */
  public static TraceResult toTraceResult(HistoricalTraceCallResult result) {
    return toTraceResult(result, -1L);
  }

  private static TraceResult toTraceResult(HistoricalTraceCallResult result, long gasOverride) {
    List<StructLog> structLogs = StructLogReconstructor.reconstruct(result.getTrace());
    // Geth returnValue is the return data as hex WITHOUT a 0x prefix (empty string when none).
    String returnValue = ByteArray.toHexString(result.getHReturn());
    long gas = gasOverride >= 0 ? gasOverride : result.getEnergyUsed();
    return new TraceResult(gas, result.isFailed(), returnValue, structLogs);
  }

  private ArchiveStateReaderFactory readerFactory() throws JsonRpcInternalException {
    if (!(archiveService instanceof DefaultArchiveService)) {
      throw new JsonRpcInternalException("archive is not available");
    }
    requireArchiveAvailable();
    ArchiveStateReaderFactory factory = ((DefaultArchiveService) archiveService).getReaderFactory();
    if (factory == null) {
      throw new JsonRpcInternalException("archive reader is not available");
    }
    return factory;
  }

  private boolean isGenesisComplete() throws JsonRpcInternalException {
    if (!(archiveService instanceof DefaultArchiveService)) {
      return false;
    }
    requireArchiveAvailable();
    long first = ((DefaultArchiveService) archiveService).getTxNumIndex().getFirstArchivedBlock();
    return first == 0;
  }

  private void requireArchiveAvailable() throws JsonRpcInternalException {
    try {
      archiveService.validateAvailable();
    } catch (ArchiveException e) {
      throw new JsonRpcInternalException(e.getMessage());
    }
  }

  private ArchiveService.ReadGuard readGuard() throws JsonRpcInternalException {
    try {
      return archiveService.acquireReadGuard();
    } catch (ArchiveException e) {
      throw new JsonRpcInternalException(e.getMessage());
    }
  }

}
