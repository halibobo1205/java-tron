package org.tron.core.services.jsonrpc;

import static org.tron.core.Wallet.CONTRACT_VALIDATE_ERROR;
import static org.tron.core.services.jsonrpc.JsonRpcApiUtil.parseEnergyFee;
import static org.tron.core.services.jsonrpc.JsonRpcApiUtil.triggerCallContract;

import com.google.protobuf.ByteString;
import java.util.Collections;
import java.util.List;
import java.util.OptionalLong;
import org.tron.common.utils.ByteArray;
import org.tron.core.Wallet;
import org.tron.core.archive.ArchiveService;
import org.tron.core.archive.DefaultArchiveService;
import org.tron.core.archive.reader.ArchiveReaderException;
import org.tron.core.archive.reader.ArchiveStatePoint;
import org.tron.core.archive.reader.ArchiveStateReader;
import org.tron.core.archive.reader.ArchiveStateReaderFactory;
import org.tron.core.archive.reader.JsonRpcArchiveStatePointResolver;
import org.tron.core.archive.reader.ResolvedArchiveStatePoint;
import org.tron.core.archive.txnum.ArchiveTxNumIndex;
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
    if (JsonRpcApiUtil.LATEST_STR.equalsIgnoreCase(blockNumOrTag)) {
      throw new JsonRpcInternalException("historical debug_traceCall invoked for the latest tag");
    }
    requireGenesisCoverage();
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
    return runTrace(historicalBlock, point, trxCap, "historical debug_traceCall");
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
    requireGenesisCoverage();
    ByteString txIdBs = ByteString.copyFrom(txId);
    Transaction tx = wallet.getTransactionById(txIdBs);
    if (tx == null) {
      throw new JsonRpcInvalidParamsException("transaction not found");
    }
    if (tx.getRawData().getContractCount() == 0) {
      return emptyTrace();
    }
    Contract contract = tx.getRawData().getContract(0);
    if (contract.getType() != ContractType.TriggerSmartContract) {
      // Only a TriggerSmartContract produces a TVM opcode trace; other types (transfers, votes,
      // CreateSmartContract deploys, etc.) have no constant-call execution to replay.
      return emptyTrace();
    }

    TransactionInfo info = wallet.getTransactionInfoById(txIdBs);
    if (info == null) {
      throw new JsonRpcInternalException("transaction info not found");
    }
    long blockNum = info.getBlockNumber();

    OptionalLong txNum = txNumIndex().findTxNumByTxId(txId);
    if (!txNum.isPresent()) {
      throw new JsonRpcInternalException("transaction not in archive");
    }
    long t = txNum.getAsLong();
    if (t < 1) {
      throw new JsonRpcInternalException("transaction has no pre-state archive point");
    }

    Block block = wallet.getBlockByNum(blockNum);
    if (block == null) {
      throw new JsonRpcInternalException("archive history unavailable for block " + blockNum);
    }
    BlockCapsule historicalBlock = new BlockCapsule(block);
    byte[] blockHash = historicalBlock.getBlockId().getBytes();
    // The pre-tx state is read as-of t - 1 (getAsOf inclusive-after; t is the tx's own txNum whose
    // writes must NOT be included). The reader reads getAsOf(point.getTxNum()).
    ArchiveStatePoint point = ArchiveStatePoint.blockEnd(blockNum, blockHash, t - 1);
    // Reuse the real transaction so feeLimit (hence the energy limit) is preserved.
    TransactionCapsule trxCap = new TransactionCapsule(tx);
    return runTrace(historicalBlock, point, trxCap, "historical debug_traceTransaction");
  }

  /**
   * Shared core: open the archive reader at {@code point}, build the historical config view at the
   * block (energy fee resolved from the block timestamp + genesis-complete flag), run the trace
   * executor with the given {@link TransactionCapsule}, and render the Geth structLogs result.
   */
  private TraceResult runTrace(BlockCapsule historicalBlock, ArchiveStatePoint point,
      TransactionCapsule trxCap, String label)
      throws JsonRpcInvalidRequestException, JsonRpcInternalException {
    DynamicPropertiesStore latestStore =
        StoreFactory.getInstance().getChainBaseManager().getDynamicPropertiesStore();
    long historicalEnergyFee =
        parseEnergyFee(historicalBlock.getTimeStamp(), latestStore.getEnergyPriceHistory());
    if (historicalEnergyFee == -1) {
      historicalEnergyFee = latestStore.getEnergyFee();
    }
    boolean genesisComplete = isGenesisComplete();

    try (ArchiveStateReader reader = readerFactory().open(point)) {
      VmDynamicProperties vmProperties = new HistoricalArchiveVmDynamicProperties(
          latestStore, historicalEnergyFee, reader, genesisComplete);
      HistoricalTraceCallResult result = new HistoricalTraceCallExecutor()
          .execute(reader, vmProperties, historicalBlock, trxCap);
      return toTraceResult(result);
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
    return ((DefaultArchiveService) archiveService).getTxNumIndex();
  }

  /** Renders the executor outcome as the Geth-shaped struct-log trace result. */
  public static TraceResult toTraceResult(HistoricalTraceCallResult result) {
    List<StructLog> structLogs = StructLogReconstructor.reconstruct(result.getTrace());
    // Geth returnValue is the return data as hex WITHOUT a 0x prefix (empty string when none).
    String returnValue = ByteArray.toHexString(result.getHReturn());
    return new TraceResult(result.getEnergyUsed(), result.isFailed(), returnValue, structLogs);
  }

  private ArchiveStateReaderFactory readerFactory() throws JsonRpcInternalException {
    if (!(archiveService instanceof DefaultArchiveService)) {
      throw new JsonRpcInternalException("archive is not available");
    }
    ArchiveStateReaderFactory factory = ((DefaultArchiveService) archiveService).getReaderFactory();
    if (factory == null) {
      throw new JsonRpcInternalException("archive reader is not available");
    }
    return factory;
  }

  private boolean isGenesisComplete() {
    if (!(archiveService instanceof DefaultArchiveService)) {
      return false;
    }
    long first = ((DefaultArchiveService) archiveService).getTxNumIndex().getFirstArchivedBlock();
    return first >= 0 && first <= 1;
  }

  private void requireGenesisCoverage() throws JsonRpcInternalException {
    if (!isGenesisComplete()) {
      long first = archiveService instanceof DefaultArchiveService
          ? ((DefaultArchiveService) archiveService).getTxNumIndex().getFirstArchivedBlock()
          : -1L;
      throw new JsonRpcInternalException(
          "archive does not cover state from genesis (first archived block " + first
              + "); historical debug trace is unavailable on a mid-chain archive");
    }
  }
}
