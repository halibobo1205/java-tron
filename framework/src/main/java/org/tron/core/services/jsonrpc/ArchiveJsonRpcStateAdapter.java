package org.tron.core.services.jsonrpc;

import org.tron.common.runtime.vm.DataWord;
import org.tron.common.utils.ByteArray;
import org.tron.core.Wallet;
import org.tron.core.archive.ArchiveService;
import org.tron.core.archive.DefaultArchiveService;
import org.tron.core.archive.reader.ArchiveReadResult;
import org.tron.core.archive.reader.ArchiveReadResult.Status;
import org.tron.core.archive.reader.ArchiveReaderException;
import org.tron.core.archive.reader.ArchiveStateReader;
import org.tron.core.archive.reader.ArchiveStateReaderFactory;
import org.tron.core.archive.reader.JsonRpcArchiveStatePointResolver;
import org.tron.core.archive.reader.ResolvedArchiveStatePoint;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.exception.jsonrpc.JsonRpcInternalException;
import org.tron.core.exception.jsonrpc.JsonRpcInvalidParamsException;

/**
 * Bridges the historical {@code eth_getBalance}/{@code eth_getCode}/{@code eth_getStorageAt} paths
 * to the archive state reader, rendering the typed {@link ArchiveReadResult} into JSON-RPC hex.
 * {@code latest} and archive-disabled are left to the caller's existing latest-only logic
 * ({@link #shouldUseArchive} returns false), so default-OFF behaviour is unchanged.
 */
public final class ArchiveJsonRpcStateAdapter {

  private static final String EMPTY_CODE = "0x";
  private static final String ZERO_WORD = ByteArray.toJsonHex(new byte[32]);
  // "0x" + 64 hex chars; matches the latest getStorageAt cap so both paths reject oversized keys
  // before decoding, instead of allocating from an unbounded hex string.
  private static final int MAX_STORAGE_KEY_HEX_LEN = 66;

  private final ArchiveService archiveService;
  private final JsonRpcArchiveStatePointResolver resolver;

  public ArchiveJsonRpcStateAdapter(Wallet wallet, ArchiveService archiveService) {
    this.archiveService = archiveService;
    this.resolver = new JsonRpcArchiveStatePointResolver(wallet, archiveService);
  }

  /** True when the request must be served from the archive (enabled + a non-latest selector). */
  public boolean shouldUseArchive(String blockNumOrTag) {
    return archiveService.isEnabled()
        && !JsonRpcApiUtil.LATEST_STR.equalsIgnoreCase(blockNumOrTag);
  }

  public String getBalance(String address, String blockNumOrTag)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException {
    byte[] address21 = JsonRpcApiUtil.addressCompatibleToByteArray(address);
    try (ArchiveStateReader reader = openReader(blockNumOrTag)) {
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
    byte[] address21 = JsonRpcApiUtil.addressCompatibleToByteArray(address);
    try (ArchiveStateReader reader = openReader(blockNumOrTag)) {
      ArchiveReadResult<byte[]> code = reader.getCode(address21);
      requireKnown(code, "code");
      return code.isPresent() ? ByteArray.toJsonHex(code.getValue()) : EMPTY_CODE;
    } catch (ArchiveReaderException e) {
      throw toInternal(e);
    }
  }

  public String getStorageAt(String address, String storageIdx, String blockNumOrTag)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException {
    byte[] address21 = JsonRpcApiUtil.addressCompatibleToByteArray(address);
    byte[] slot32 = normalizeSlot(storageIdx);
    try (ArchiveStateReader reader = openReader(blockNumOrTag)) {
      ArchiveReadResult<byte[]> value = reader.getStorage(address21, slot32);
      requireKnown(value, "storage");
      return value.isPresent() ? ByteArray.toJsonHex(leftPad32(value.getValue())) : ZERO_WORD;
    } catch (ArchiveReaderException e) {
      throw toInternal(e);
    }
  }

  private ArchiveStateReader openReader(String blockNumOrTag)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException {
    ResolvedArchiveStatePoint resolved = resolver.resolveBlockEnd(blockNumOrTag);
    if (resolved.isLatest()) {
      // shouldUseArchive already filters latest; reaching here means a caller skipped that guard.
      throw new JsonRpcInternalException("archive adapter invoked for the latest tag");
    }
    try {
      return readerFactory().open(resolved.getPoint());
    } catch (ArchiveReaderException e) {
      throw toInternal(e);
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
    return first >= 0 && first <= 1;
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
