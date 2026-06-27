package org.tron.core.archive.reader;

import java.util.Optional;
import org.tron.core.Wallet;
import org.tron.core.archive.ArchiveService;
import org.tron.core.archive.DefaultArchiveService;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.archive.txnum.ArchiveTxNumIndex;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.exception.jsonrpc.JsonRpcInternalException;
import org.tron.core.exception.jsonrpc.JsonRpcInvalidParamsException;
import org.tron.core.services.jsonrpc.JsonRpcApiUtil;
import org.tron.protos.Protocol.Block;

/**
 * Resolves a JSON-RPC block selector to a {@link ResolvedArchiveStatePoint}. {@code latest} always
 * bypasses the archive (served from live state); any other selector becomes a {@code BLOCK_END}
 * archive point at the block's finalize txNum (decision 3: inclusive-after). The txNum semantics
 * are centralized here so RPC methods never compute {@code +1}/before-after themselves.
 */
public final class JsonRpcArchiveStatePointResolver {

  private final Wallet wallet;
  private final ArchiveService archiveService;

  public JsonRpcArchiveStatePointResolver(Wallet wallet, ArchiveService archiveService) {
    this.wallet = wallet;
    this.archiveService = archiveService;
  }

  public ResolvedArchiveStatePoint resolveBlockEnd(String blockNumOrTag)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException {
    if (JsonRpcApiUtil.LATEST_STR.equalsIgnoreCase(blockNumOrTag)) {
      return ResolvedArchiveStatePoint.latest();
    }
    // Reuses the canonical tag/quantity parser: earliest -> 0, finalized -> solid block,
    // quantity -> its number; pending / bad input throw JsonRpcInvalidParamsException.
    long requestedBlockNum = JsonRpcApiUtil.parseBlockNumber(blockNumOrTag, wallet);
    Block block = wallet.getBlockByNum(requestedBlockNum);
    if (block == null) {
      throw new JsonRpcInvalidParamsException("block header not found");
    }
    long blockNum = block.getBlockHeader().getRawData().getNumber();
    byte[] blockHash = new BlockCapsule(block).getBlockId().getBytes();
    Optional<ArchiveBlockRange> range = txNumIndex().getBlockRange(blockNum);
    if (!range.isPresent()) {
      throw new JsonRpcInternalException("archive history unavailable for block " + blockNum);
    }
    return ResolvedArchiveStatePoint.archive(
        ArchiveStatePoint.blockEnd(blockNum, blockHash, range.get().getFinalizeTxNum()));
  }

  private ArchiveTxNumIndex txNumIndex() throws JsonRpcInternalException {
    if (!(archiveService instanceof DefaultArchiveService)) {
      throw new JsonRpcInternalException("archive is not available");
    }
    return ((DefaultArchiveService) archiveService).getTxNumIndex();
  }
}
