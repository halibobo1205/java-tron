package org.tron.core.services.jsonrpc;

import static org.tron.core.Wallet.CONTRACT_VALIDATE_ERROR;
import static org.tron.core.services.jsonrpc.JsonRpcApiUtil.triggerCallContract;

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
 * latest-only logic ({@link #shouldUseArchive} returns false).
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
  private final JsonRpcArchiveStatePointResolver resolver;

  public HistoricalEthCallSupport(Wallet wallet, ArchiveService archiveService) {
    this.wallet = wallet;
    this.archiveService = archiveService;
    this.resolver = new JsonRpcArchiveStatePointResolver(wallet, archiveService);
  }

  /** True when the call must be served from the archive (enabled + a non-latest selector). */
  public boolean shouldUseArchive(String blockNumOrTag) {
    return archiveService.isEnabled()
        && !JsonRpcApiUtil.LATEST_STR.equalsIgnoreCase(blockNumOrTag);
  }

  public String call(byte[] ownerAddress, byte[] contractAddress, long callValue, byte[] data,
      String blockNumOrTag) throws JsonRpcInvalidParamsException, JsonRpcInvalidRequestException,
      JsonRpcInternalException {
    if (JsonRpcApiUtil.LATEST_STR.equalsIgnoreCase(blockNumOrTag)) {
      throw new JsonRpcInternalException("historical eth_call invoked for the latest tag");
    }
    ResolvedArchiveStatePoint resolved = resolver.resolveBlockEnd(blockNumOrTag);
    if (resolved.isLatest()) {
      // shouldUseArchive already filters latest; reaching here means a caller skipped that guard.
      throw new JsonRpcInternalException("historical eth_call invoked for the latest tag");
    }
    ArchiveStatePoint point = resolved.getPoint();
    boolean genesisComplete = isGenesisComplete();

    Block block = wallet.getBlockByNum(point.getBlockNum());
    if (block == null) {
      throw new JsonRpcInternalException("archive history unavailable for block "
          + point.getBlockNum());
    }
    BlockCapsule historicalBlock = new BlockCapsule(block);
    DynamicPropertiesStore latestStore =
        StoreFactory.getInstance().getChainBaseManager().getDynamicPropertiesStore();
    // Energy price has a complete, time-keyed history in the live store, so we replay BASEFEE /
    // GASPRICE with the value in force at the target block rather than the current price.
    long historicalEnergyFee = HistoricalVmDynamicProperties.resolveHistoricalEnergyFee(
        historicalBlock.getTimeStamp(), latestStore.getEnergyPriceHistory());
    TriggerSmartContract trigger =
        triggerCallContract(ownerAddress, contractAddress, callValue, data, 0, null);

    try (ArchiveStateReader reader = readerFactory().open(point)) {
      // The result-affecting hard-fork flags are read from the archive at the target block, so the
      // config view is built here (reader open) rather than before the try.
      VmDynamicProperties vmProperties = new HistoricalArchiveVmDynamicProperties(
          latestStore, historicalEnergyFee, reader, genesisComplete);
      TransactionCapsule trxCap =
          wallet.createTransactionCapsule(trigger, ContractType.TriggerSmartContract);
      HistoricalConstantCallResult result = new HistoricalConstantCallExecutor()
          .execute(reader, vmProperties, historicalBlock, trxCap, genesisComplete);
      if (result.isReverted()) {
        // Mirror the latest path: message carries the decoded revert reason, data the raw bytes.
        throw new JsonRpcInternalException(
            "REVERT opcode executed" + TronJsonRpcImpl.tryDecodeRevertReason(result.getResult()),
            ByteArray.toJsonHex(result.getResult()));
      }
      if (result.getRuntimeError() != null && !result.getRuntimeError().isEmpty()) {
        throw new JsonRpcInternalException(result.getRuntimeError());
      }
      return ByteArray.toJsonHex(result.getResult());
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

  /**
   * True when the archive covers block 0, so a MISSING dynamic-property flag is the in-memory
   * default rather than an un-captured pre-coverage change. A mid-chain archive (or an empty index)
   * returns false, and missing execution-affecting flags fail closed instead of using latest.
   */
  private boolean isGenesisComplete() {
    if (!(archiveService instanceof DefaultArchiveService)) {
      return false;
    }
    long first = ((DefaultArchiveService) archiveService).getTxNumIndex().getFirstArchivedBlock();
    return first == 0;
  }

}
