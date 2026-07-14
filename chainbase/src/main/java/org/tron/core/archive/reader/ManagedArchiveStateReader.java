package org.tron.core.archive.reader;

import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.ArchiveWorkLease;
import org.tron.core.archive.query.ArchiveQueryTransportScope;
import org.tron.core.archive.query.ArchiveSnapshotPermit;
import org.tron.core.archive.query.QueryContext;
import org.tron.core.archive.query.QueryLease;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.ContractCapsule;
import org.tron.core.capsule.ContractStateCapsule;

/** Reader wrapper that owns lifecycle and query-admission leases. */
public final class ManagedArchiveStateReader implements ArchiveStateReader {

  private final ArchiveStateReader delegate;
  private final ArchiveWorkLease lifecycleLease;
  private final QueryLease queryLease;
  private final ArchiveSnapshotPermit snapshotPermit;
  private final Thread owner = Thread.currentThread();
  private boolean closed;

  public ManagedArchiveStateReader(ArchiveStateReader delegate, ArchiveWorkLease lifecycleLease,
      QueryLease queryLease, ArchiveSnapshotPermit snapshotPermit) {
    this.delegate = delegate;
    this.lifecycleLease = lifecycleLease;
    this.queryLease = queryLease;
    this.snapshotPermit = snapshotPermit;
  }

  @Override
  public ArchiveStatePoint getPoint() {
    requireOwnerOpen();
    return delegate.getPoint();
  }

  @Override
  public QueryContext getQueryContext() {
    requireOwnerOpen();
    return queryLease.getContext();
  }

  @Override
  public boolean isGenesisComplete() {
    requireOwnerOpen();
    return delegate.isGenesisComplete();
  }

  @Override
  public ArchiveReadResult<AccountCapsule> getAccount(byte[] address)
      throws ArchiveReaderException {
    requireOwnerOpen();
    return delegate.getAccount(address);
  }

  @Override
  public ArchiveReadResult<byte[]> getAccountAsset(byte[] address, byte[] assetId)
      throws ArchiveReaderException {
    requireOwnerOpen();
    return delegate.getAccountAsset(address, assetId);
  }

  @Override
  public ArchiveReadResult<ContractCapsule> getContract(byte[] address)
      throws ArchiveReaderException {
    requireOwnerOpen();
    return delegate.getContract(address);
  }

  @Override
  public ArchiveReadResult<ContractStateCapsule> getContractState(byte[] address)
      throws ArchiveReaderException {
    requireOwnerOpen();
    return delegate.getContractState(address);
  }

  @Override
  public ArchiveReadResult<byte[]> getCode(byte[] address) throws ArchiveReaderException {
    requireOwnerOpen();
    return delegate.getCode(address);
  }

  @Override
  public ArchiveReadResult<byte[]> getStorage(byte[] address, byte[] slot)
      throws ArchiveReaderException {
    requireOwnerOpen();
    return delegate.getStorage(address, slot);
  }

  @Override
  public ArchiveReadResult<byte[]> getDynamicProperty(byte[] key) throws ArchiveReaderException {
    requireOwnerOpen();
    return delegate.getDynamicProperty(key);
  }

  @Override
  public synchronized void close() {
    if (Thread.currentThread() != owner) {
      throw new ArchiveException("archive state reader used from a non-owner thread");
    }
    if (closed) {
      return;
    }
    closed = true;
    Throwable failure = null;
    failure = closeAndCollect(failure, delegate::close);
    if (snapshotPermit != null) {
      failure = closeAndCollect(failure, snapshotPermit::close);
    }
    failure = closeAndCollect(failure, lifecycleLease::close);
    failure = closeAndCollect(failure,
        () -> ArchiveQueryTransportScope.closeAfterResponse(queryLease));
    if (failure instanceof RuntimeException) {
      throw (RuntimeException) failure;
    }
    if (failure instanceof Error) {
      throw (Error) failure;
    }
    if (failure != null) {
      throw new ArchiveException("archive state reader close failed", failure);
    }
  }

  private static Throwable closeAndCollect(Throwable failure, Runnable closeAction) {
    try {
      closeAction.run();
    } catch (Throwable closeFailure) {
      if (failure == null) {
        return closeFailure;
      }
      if (failure != closeFailure) {
        failure.addSuppressed(closeFailure);
      }
    }
    return failure;
  }

  private void requireOwnerOpen() {
    if (Thread.currentThread() != owner) {
      throw new ArchiveException("archive state reader used from a non-owner thread");
    }
    if (closed) {
      throw new ArchiveException("archive state reader is closed");
    }
  }
}
