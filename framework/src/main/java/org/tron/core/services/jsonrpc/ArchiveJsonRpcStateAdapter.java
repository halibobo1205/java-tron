package org.tron.core.services.jsonrpc;

import java.util.Arrays;
import org.tron.common.runtime.vm.DataWord;
import org.tron.common.utils.ByteArray;
import org.tron.core.Wallet;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.ArchiveService;
import org.tron.core.archive.DefaultArchiveService;
import org.tron.core.archive.reader.ArchiveReadResult;
import org.tron.core.archive.reader.ArchiveReadResult.Status;
import org.tron.core.archive.reader.ArchiveReaderException;
import org.tron.core.archive.reader.ArchiveStatePoint;
import org.tron.core.archive.reader.ArchiveStateReader;
import org.tron.core.archive.reader.ArchiveStateReaderFactory;
import org.tron.core.archive.reader.JsonRpcArchiveStatePointResolver;
import org.tron.core.archive.reader.ResolvedArchiveStatePoint;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.exception.jsonrpc.JsonRpcInternalException;
import org.tron.core.exception.jsonrpc.JsonRpcInvalidParamsException;
import org.tron.protos.Protocol.Block;

/**
 * Bridges the historical {@code eth_getBalance}/{@code eth_getCode}/{@code eth_getStorageAt} paths
 * to the archive state reader, rendering the typed {@link ArchiveReadResult} into JSON-RPC hex.
 * {@code latest} is left to the caller's existing latest-only logic. Non-latest selectors enter
 * this adapter even when archive is disabled, so disabled archive fails closed with the archive
 * error surface rather than being misreported as an unsupported latest-only parameter.
 */
public final class ArchiveJsonRpcStateAdapter {

  private static final String EMPTY_CODE = "0x";
  private static final String ZERO_WORD = ByteArray.toJsonHex(new byte[32]);
  // "0x" + 64 hex chars; matches the latest getStorageAt cap so both paths reject oversized keys
  // before decoding, instead of allocating from an unbounded hex string.
  private static final int MAX_STORAGE_KEY_HEX_LEN = 66;

  private final ArchiveService archiveService;
  private final JsonRpcArchiveStatePointResolver resolver;
  private final Wallet wallet;

  public ArchiveJsonRpcStateAdapter(Wallet wallet, ArchiveService archiveService) {
    this.wallet = wallet;
    this.archiveService = archiveService;
    this.resolver = new JsonRpcArchiveStatePointResolver(wallet, archiveService);
  }

  /** True when the request is historical and must not fall back to latest state. */
  public boolean shouldUseArchive(String blockNumOrTag) {
    return !JsonRpcApiUtil.LATEST_STR.equalsIgnoreCase(blockNumOrTag);
  }

  public String getBalance(String address, String blockNumOrTag)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException {
    return getBalance(address, blockNumOrTag, null);
  }

  public String getBalance(String address, String blockNumOrTag, byte[] requestedBlockHash)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException {
    byte[] address21 = JsonRpcApiUtil.addressCompatibleToByteArray(address);
    try (ArchiveService.ReadGuard ignored = readGuard();
        ArchiveStateReader reader = openReader(blockNumOrTag, requestedBlockHash)) {
      ArchiveReadResult<AccountCapsule> account = reader.getAccount(address21);
      requireKnown(account, "account");
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
    try (ArchiveService.ReadGuard ignored = readGuard();
        ArchiveStateReader reader = openReader(blockNumOrTag, requestedBlockHash)) {
      ArchiveReadResult<byte[]> code = reader.getCode(address21);
      requireKnown(code, "code");
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
    try (ArchiveService.ReadGuard ignored = readGuard();
        ArchiveStateReader reader = openReader(blockNumOrTag, requestedBlockHash)) {
      ArchiveReadResult<byte[]> value = reader.getStorage(address21, slot32);
      requireKnown(value, "storage");
      return value.isPresent() ? ByteArray.toJsonHex(leftPad32(value.getValue())) : ZERO_WORD;
    } catch (ArchiveReaderException e) {
      throw toInternal(e);
    }
  }

  private ArchiveStateReader openReader(String blockNumOrTag)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException {
    return openReader(blockNumOrTag, null);
  }

  private ArchiveStateReader openReader(String blockNumOrTag, byte[] requestedBlockHash)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException {
    requireArchiveEnabled();
    ResolvedArchiveStatePoint resolved = resolver.resolveBlockEnd(blockNumOrTag);
    if (resolved.isLatest()) {
      // shouldUseArchive already filters latest; reaching here means a caller skipped that guard.
      throw new JsonRpcInternalException("archive adapter invoked for the latest tag");
    }
    ArchiveStatePoint point = resolved.getPoint();
    if (requestedBlockHash != null) {
      requireResolvedBlockHash(point, requestedBlockHash);
    }
    requireResolvedBlockHash(point);
    try {
      return readerFactory().open(point);
    } catch (ArchiveReaderException e) {
      throw toInternal(e);
    }
  }

  private void requireResolvedBlockHash(ArchiveStatePoint point) throws JsonRpcInternalException {
    Block block = wallet.getBlockByNum(point.getBlockNum());
    if (block == null) {
      throw new JsonRpcInternalException("archive history unavailable for block "
          + point.getBlockNum());
    }
    byte[] blockHash = new BlockCapsule(block).getBlockId().getBytes();
    requireResolvedBlockHash(point, blockHash);
  }

  private static void requireResolvedBlockHash(ArchiveStatePoint point, byte[] blockHash)
      throws JsonRpcInternalException {
    byte[] pointHash = point.getBlockHash();
    if (pointHash == null || pointHash.length != ArchiveBlockRange.BLOCK_HASH_LENGTH
        || blockHash == null || blockHash.length != ArchiveBlockRange.BLOCK_HASH_LENGTH
        || !Arrays.equals(pointHash, blockHash)) {
      throw new JsonRpcInternalException(
          "archive history hash mismatch for block " + point.getBlockNum());
    }
  }

  private void requireArchiveEnabled() throws JsonRpcInternalException {
    if (!archiveService.isEnabled()) {
      throw new JsonRpcInternalException("archive is not available");
    }
  }

  private void requireKnown(ArchiveReadResult<?> result, String what)
      throws JsonRpcInternalException {
    if (!isGenesisComplete() && result.getStatus() == Status.MISSING) {
      throw new JsonRpcInternalException(
          "archive " + what + " is unknown before mid-chain coverage");
    }
  }

  private boolean isGenesisComplete() {
    if (!(archiveService instanceof DefaultArchiveService)) {
      return false;
    }
    long first = ((DefaultArchiveService) archiveService).getTxNumIndex().getFirstArchivedBlock();
    return first == 0;
  }

  private ArchiveStateReaderFactory readerFactory() throws JsonRpcInternalException {
    if (!(archiveService instanceof DefaultArchiveService)) {
      throw new JsonRpcInternalException("archive is not available");
    }
    try {
      archiveService.validateAvailable();
    } catch (ArchiveException e) {
      throw new JsonRpcInternalException(e.getMessage());
    }
    ArchiveStateReaderFactory factory = ((DefaultArchiveService) archiveService).getReaderFactory();
    if (factory == null) {
      throw new JsonRpcInternalException("archive reader is not available");
    }
    return factory;
  }

  private ArchiveService.ReadGuard readGuard() throws JsonRpcInternalException {
    try {
      return archiveService.acquireReadGuard();
    } catch (ArchiveException e) {
      throw new JsonRpcInternalException(e.getMessage());
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
}
