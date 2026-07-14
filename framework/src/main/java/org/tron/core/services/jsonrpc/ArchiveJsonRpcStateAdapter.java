package org.tron.core.services.jsonrpc;

import java.util.Arrays;
import org.tron.common.runtime.vm.DataWord;
import org.tron.common.utils.ByteArray;
import org.tron.core.Wallet;
import org.tron.core.archive.ArchiveService;
import org.tron.core.archive.reader.ArchiveReadResult;
import org.tron.core.archive.reader.ArchiveReadResult.Status;
import org.tron.core.archive.reader.ArchiveReaderException;
import org.tron.core.archive.reader.ArchiveStateReader;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.exception.jsonrpc.JsonRpcInternalException;
import org.tron.core.exception.jsonrpc.JsonRpcInvalidParamsException;
import org.tron.protos.Protocol.Block;

/**
 * Bridges the historical {@code eth_getBalance}/{@code eth_getCode}/{@code eth_getStorageAt} paths
 * to the archive state reader, rendering the typed {@link ArchiveReadResult} into JSON-RPC hex.
 * {@code latest} and archive-disabled requests are left to the caller's existing latest-only logic.
 */
public final class ArchiveJsonRpcStateAdapter {

  private static final String EMPTY_CODE = "0x";
  private static final String ZERO_WORD = ByteArray.toJsonHex(new byte[32]);
  // "0x" + 64 hex chars; matches the latest getStorageAt cap so both paths reject oversized keys
  // before decoding, instead of allocating from an unbounded hex string.
  private static final int MAX_STORAGE_KEY_HEX_LEN = 66;

  private final ArchiveService archiveService;
  private final Wallet wallet;

  public ArchiveJsonRpcStateAdapter(Wallet wallet, ArchiveService archiveService) {
    this.wallet = wallet;
    this.archiveService = archiveService;
  }

  /** True when the request is historical and must not fall back to latest state. */
  public boolean shouldUseArchive(String blockNumOrTag) {
    return archiveService.isEnabled() && !JsonRpcApiUtil.LATEST_STR.equalsIgnoreCase(blockNumOrTag);
  }

  void validateArchiveAvailable() throws JsonRpcInternalException {
    requireArchiveEnabled();
    try {
      archiveService.validateAvailable();
    } catch (RuntimeException e) {
      throw new JsonRpcInternalException(e.getMessage());
    }
  }

  public String getBalance(String address, String blockNumOrTag)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException {
    return getBalance(address, blockNumOrTag, null);
  }

  public String getBalance(String address, String blockNumOrTag, byte[] requestedBlockHash)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException {
    byte[] address21 = JsonRpcApiUtil.addressCompatibleToByteArray(address);
    try (ArchiveStateReader reader = resolveReader(blockNumOrTag, requestedBlockHash)) {
      ArchiveReadResult<AccountCapsule> account = reader.getAccount(address21);
      requireKnown(account, "account", reader.isGenesisComplete());
      reader.getQueryContext().recordResponseBytes(18L);
      return account.isPresent()
          ? ByteArray.toJsonHex(account.getValue().getBalance())
          : ByteArray.toJsonHex(0L); // missing account = zero balance, not an archive gap
    } catch (ArchiveReaderException e) {
      throw toInternal(e);
    }
  }

  public String getCode(String address, String blockNumOrTag)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException {
    return getCode(address, blockNumOrTag, null);
  }

  public String getCode(String address, String blockNumOrTag, byte[] requestedBlockHash)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException {
    byte[] address21 = JsonRpcApiUtil.addressCompatibleToByteArray(address);
    try (ArchiveStateReader reader = resolveReader(blockNumOrTag, requestedBlockHash)) {
      ArchiveReadResult<byte[]> code = reader.getCode(address21);
      requireKnown(code, "code", reader.isGenesisComplete());
      recordJsonHexResponse(reader, code.isPresent() ? code.getValue().length : 0L);
      return code.isPresent() ? ByteArray.toJsonHex(code.getValue()) : EMPTY_CODE;
    } catch (ArchiveReaderException e) {
      throw toInternal(e);
    }
  }

  public String getStorageAt(String address, String storageIdx, String blockNumOrTag)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException {
    return getStorageAt(address, storageIdx, blockNumOrTag, null);
  }

  public String getStorageAt(String address, String storageIdx, String blockNumOrTag,
      byte[] requestedBlockHash) throws JsonRpcInvalidParamsException, JsonRpcInternalException {
    byte[] address21 = JsonRpcApiUtil.addressCompatibleToByteArray(address);
    byte[] slot32 = normalizeSlot(storageIdx);
    try (ArchiveStateReader reader = resolveReader(blockNumOrTag, requestedBlockHash)) {
      ArchiveReadResult<byte[]> value = reader.getStorage(address21, slot32);
      requireKnown(value, "storage", reader.isGenesisComplete());
      recordJsonHexResponse(reader, 32L);
      return value.isPresent() ? ByteArray.toJsonHex(leftPad32(value.getValue())) : ZERO_WORD;
    } catch (ArchiveReaderException e) {
      throw toInternal(e);
    }
  }

  private ArchiveStateReader resolveReader(String blockNumOrTag)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException {
    return resolveReader(blockNumOrTag, null);
  }

  private ArchiveStateReader resolveReader(String blockNumOrTag, byte[] requestedBlockHash)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException {
    requireArchiveEnabled();
    if (JsonRpcApiUtil.LATEST_STR.equalsIgnoreCase(blockNumOrTag)) {
      // shouldUseArchive already filters latest; reaching here means a caller skipped that guard.
      throw new JsonRpcInternalException("archive adapter invoked for the latest tag");
    }
    ArchiveStateReader reader = null;
    try {
      // Canonical header lookup, point resolution, and snapshot creation share one admitted archive
      // boundary. This prevents unauthenticated requests from doing block-store I/O first.
      if (JsonRpcApiUtil.FINALIZED_STR.equalsIgnoreCase(blockNumOrTag)) {
        reader = archiveService.openBlockEndReader(
            wallet::getSolidBlockNum, this::canonicalBlockHashOrThrow);
      } else {
        long requestedBlockNum = JsonRpcApiUtil.isBlockTag(blockNumOrTag)
            ? JsonRpcApiUtil.parseBlockTag(blockNumOrTag, wallet)
            : JsonRpcApiUtil.parseBlockNumber(blockNumOrTag);
        reader = archiveService.openBlockEndReader(
            requestedBlockNum, this::canonicalBlockHashOrThrow);
      }
      long blockNum = reader.getPoint().getBlockNum();
      byte[] pointBlockHash = reader.getPoint().getBlockHash();
      if (requestedBlockHash != null) {
        requireBlockHash(blockNum, pointBlockHash, requestedBlockHash);
      }
      reader.getQueryContext().recordBackendReads(2L);
      Block currentBlock = wallet.getBlockByNum(blockNum);
      byte[] currentBlockHash = currentBlock == null
          ? null
          : new BlockCapsule(currentBlock).getBlockId().getBytes();
      // Re-read the canonical header after the archive snapshot is fixed. A fork between the first
      // Wallet lookup and reader creation must fail closed instead of serving the old point under a
      // new canonical height.
      requireBlockHash(blockNum, currentBlockHash, pointBlockHash);
      return reader;
    } catch (BlockHeaderNotFoundException e) {
      closeAfterFailure(reader, e);
      throw new JsonRpcInvalidParamsException("block header not found");
    } catch (ArchiveReaderException e) {
      throw toInternal(e);
    } catch (JsonRpcInternalException | RuntimeException e) {
      closeAfterFailure(reader, e);
      throw e;
    }
  }

  private byte[] canonicalBlockHashOrThrow(long blockNum) {
    Block block = wallet.getBlockByNum(blockNum);
    if (block == null) {
      throw new BlockHeaderNotFoundException();
    }
    return new BlockCapsule(block).getBlockId().getBytes();
  }

  private static void recordJsonHexResponse(ArchiveStateReader reader, long rawBytes) {
    reader.getQueryContext().recordResponseBytes(2L + rawBytes * 2L);
  }

  private static void closeAfterFailure(ArchiveStateReader reader, Throwable failure) {
    if (reader == null) {
      return;
    }
    try {
      reader.close();
    } catch (RuntimeException closeFailure) {
      failure.addSuppressed(closeFailure);
    }
  }

  private static void requireBlockHash(long blockNum, byte[] canonicalHash, byte[] requestedHash)
      throws JsonRpcInternalException {
    if (canonicalHash == null || canonicalHash.length != ArchiveBlockRange.BLOCK_HASH_LENGTH
        || requestedHash == null || requestedHash.length != ArchiveBlockRange.BLOCK_HASH_LENGTH
        || !Arrays.equals(canonicalHash, requestedHash)) {
      throw new JsonRpcInternalException(
          "archive history hash mismatch for block " + blockNum);
    }
  }

  private void requireArchiveEnabled() throws JsonRpcInternalException {
    if (!archiveService.isEnabled()) {
      throw new JsonRpcInternalException("archive is not available");
    }
  }

  private static void requireKnown(ArchiveReadResult<?> result, String what,
      boolean genesisComplete)
      throws JsonRpcInternalException {
    if (!genesisComplete && result.getStatus() == Status.MISSING) {
      throw new JsonRpcInternalException(
          "archive " + what + " is unknown before mid-chain coverage");
    }
  }

  private static byte[] normalizeSlot(String storageIdx) throws JsonRpcInvalidParamsException {
    if (storageIdx == null || storageIdx.length() > MAX_STORAGE_KEY_HEX_LEN) {
      // Match the latest path (TronJsonRpcImpl.getStorageAt), which rejects a null/oversized slot
      // up front rather than letting fromHexString(null) -> empty -> DataWord silently read slot 0,
      // or decoding an unbounded hex string before DataWord rejects it.
      throw new JsonRpcInvalidParamsException("invalid storage key value");
    }
    try {
      return new DataWord(ByteArray.fromHexString(storageIdx)).getData();
    } catch (RuntimeException e) {
      throw new JsonRpcInvalidParamsException("invalid storage slot");
    }
  }

  private static byte[] leftPad32(byte[] value) throws JsonRpcInternalException {
    try {
      return new DataWord(value).getData();
    } catch (RuntimeException e) {
      throw new JsonRpcInternalException("corrupt archive storage value");
    }
  }

  private static JsonRpcInternalException toInternal(ArchiveReaderException e) {
    return new JsonRpcInternalException(e.getMessage());
  }

  private static final class BlockHeaderNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;
  }
}
