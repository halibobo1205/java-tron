package org.tron.core.archive.reader;

import org.tron.core.archive.query.QueryContext;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.ContractCapsule;
import org.tron.core.capsule.ContractStateCapsule;

/**
 * Reads historical state from the archive at a fixed {@link ArchiveStatePoint}. Each method answers
 * purely from the archive temporal store -- it never falls back to the live (latest) state, so a
 * value absent from the archive is reported as {@code MISSING}, not the current value. Callers
 * (JSON-RPC adapters) translate the {@link ArchiveReadResult} into eth_* rendering.
 *
 * <p>Inputs: address is 21 bytes, slot is 32 bytes.
 */
public interface ArchiveStateReader extends AutoCloseable {

  ArchiveStatePoint getPoint();

  default QueryContext getQueryContext() {
    return null;
  }

  /** True only when this reader's archive proves continuous coverage from block zero. */
  default boolean isGenesisComplete() {
    return false;
  }

  ArchiveReadResult<AccountCapsule> getAccount(byte[] address) throws ArchiveReaderException;

  ArchiveReadResult<byte[]> getAccountAsset(byte[] address, byte[] assetId)
      throws ArchiveReaderException;

  ArchiveReadResult<ContractCapsule> getContract(byte[] address) throws ArchiveReaderException;

  ArchiveReadResult<ContractStateCapsule> getContractState(byte[] address)
      throws ArchiveReaderException;

  ArchiveReadResult<byte[]> getCode(byte[] address) throws ArchiveReaderException;

  ArchiveReadResult<byte[]> getStorage(byte[] address, byte[] slot) throws ArchiveReaderException;

  /**
   * Reads a DYNAMIC_PROPERTIES value (raw bytes, e.g. a {@code ByteArray.fromLong} flag) as of this
   * point. {@code key} is the property's ASCII key bytes (e.g. {@code ALLOW_TVM_LONDON}). MISSING
   * means the key was never explicitly written by a proposal as of this point -- which, for an
   * archive that captured from genesis, is unambiguously the in-memory default.
   */
  ArchiveReadResult<byte[]> getDynamicProperty(byte[] key) throws ArchiveReaderException;

  @Override
  void close();
}
