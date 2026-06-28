package org.tron.core.services.jsonrpc;

import static org.tron.core.Wallet.CONTRACT_VALIDATE_ERROR;
import static org.tron.core.services.jsonrpc.JsonRpcApiUtil.parseEnergyFee;
import static org.tron.core.services.jsonrpc.JsonRpcApiUtil.triggerCallContract;

import java.util.List;
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
import org.tron.protos.Protocol.Transaction.Contract.ContractType;
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

  public TraceResult traceCall(byte[] ownerAddress, byte[] contractAddress, long callValue,
      byte[] data, String blockNumOrTag) throws JsonRpcInvalidParamsException,
      JsonRpcInvalidRequestException, JsonRpcInternalException {
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
    DynamicPropertiesStore latestStore =
        StoreFactory.getInstance().getChainBaseManager().getDynamicPropertiesStore();
    long historicalEnergyFee =
        parseEnergyFee(historicalBlock.getTimeStamp(), latestStore.getEnergyPriceHistory());
    if (historicalEnergyFee == -1) {
      historicalEnergyFee = latestStore.getEnergyFee();
    }
    boolean genesisComplete = isGenesisComplete();
    TriggerSmartContract trigger =
        triggerCallContract(ownerAddress, contractAddress, callValue, data, 0, null);

    try (ArchiveStateReader reader = readerFactory().open(point)) {
      VmDynamicProperties vmProperties = new HistoricalArchiveVmDynamicProperties(
          latestStore, historicalEnergyFee, reader, genesisComplete);
      TransactionCapsule trxCap =
          wallet.createTransactionCapsule(trigger, ContractType.TriggerSmartContract);
      HistoricalTraceCallResult result = new HistoricalTraceCallExecutor()
          .execute(reader, vmProperties, historicalBlock, trxCap);
      return toTraceResult(result);
    } catch (ContractValidateException e) {
      throw new JsonRpcInvalidRequestException(
          e.getMessage() == null ? CONTRACT_VALIDATE_ERROR : e.getMessage());
    } catch (ArchiveReaderException | HistoricalVmExecutionException
        | UnsupportedHistoricalStateException | ContractExeException e) {
      throw new JsonRpcInternalException(
          e.getMessage() == null ? "historical debug_traceCall failed" : e.getMessage());
    }
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
}
