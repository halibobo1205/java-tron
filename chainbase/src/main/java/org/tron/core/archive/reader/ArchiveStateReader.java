package org.tron.core.archive.reader;

import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.ContractCapsule;

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

  ArchiveReadResult<AccountCapsule> getAccount(byte[] address) throws ArchiveReaderException;

  ArchiveReadResult<ContractCapsule> getContract(byte[] address) throws ArchiveReaderException;

  ArchiveReadResult<byte[]> getCode(byte[] address) throws ArchiveReaderException;

  ArchiveReadResult<byte[]> getStorage(byte[] address, byte[] slot) throws ArchiveReaderException;

  @Override
  void close();
}
