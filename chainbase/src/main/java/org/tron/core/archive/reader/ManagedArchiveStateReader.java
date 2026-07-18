package org.tron.core.archive.reader;

import java.util.Objects;
import java.util.function.Consumer;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.ArchiveMutationLease;
import org.tron.core.archive.ArchiveService;
import org.tron.core.archive.ArchiveSnapshotReleaseException;
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
  private final ArchiveMutationLease mutationLease;
  private final Runnable responseValidator;
  private final Consumer<Throwable> integrityFailureHandler;
  private final Thread owner = Thread.currentThread();
  private boolean closed;

  public ManagedArchiveStateReader(ArchiveStateReader delegate, ArchiveWorkLease lifecycleLease,
      QueryLease queryLease, ArchiveSnapshotPermit snapshotPermit) {
    this(delegate, lifecycleLease, queryLease, snapshotPermit, ignored -> {
    });
  }

  public ManagedArchiveStateReader(ArchiveStateReader delegate, ArchiveWorkLease lifecycleLease,
      QueryLease queryLease, ArchiveSnapshotPermit snapshotPermit,
      Consumer<Throwable> integrityFailureHandler) {
    this(delegate, lifecycleLease, queryLease, snapshotPermit,
        ArchiveService.NOOP_MUTATION_LEASE, () -> { }, integrityFailureHandler);
  }

  public ManagedArchiveStateReader(ArchiveStateReader delegate, ArchiveWorkLease lifecycleLease,
      QueryLease queryLease, ArchiveSnapshotPermit snapshotPermit,
      ArchiveMutationLease mutationLease, Consumer<Throwable> integrityFailureHandler) {
    this(delegate, lifecycleLease, queryLease, snapshotPermit, mutationLease, () -> { },
        integrityFailureHandler);
  }

  public ManagedArchiveStateReader(ArchiveStateReader delegate, ArchiveWorkLease lifecycleLease,
      QueryLease queryLease, ArchiveSnapshotPermit snapshotPermit,
      ArchiveMutationLease mutationLease, Runnable responseValidator,
      Consumer<Throwable> integrityFailureHandler) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.lifecycleLease = Objects.requireNonNull(lifecycleLease, "lifecycleLease");
    this.queryLease = Objects.requireNonNull(queryLease, "queryLease");
    this.snapshotPermit = snapshotPermit;
    this.mutationLease = Objects.requireNonNull(mutationLease, "mutationLease");
    this.responseValidator = Objects.requireNonNull(responseValidator, "responseValidator");
    this.integrityFailureHandler = Objects.requireNonNull(
        integrityFailureHandler, "integrityFailureHandler");
  }

  @Override
  public ArchiveStatePoint getPoint() {
    return readUnchecked(delegate::getPoint);
  }

  @Override
  public QueryContext getQueryContext() {
    requireOwnerOpen();
    return queryLease.getContext();
  }

  @Override
  public boolean isGenesisComplete() {
    return readUnchecked(delegate::isGenesisComplete);
  }

  @Override
  public byte[] getBlockHash(long blockNum) throws ArchiveReaderException {
    return read(() -> delegate.getBlockHash(blockNum));
  }

  @Override
  public ArchiveReadResult<AccountCapsule> getAccount(byte[] address)
      throws ArchiveReaderException {
    return read(() -> delegate.getAccount(address));
  }

  @Override
  public ArchiveReadResult<byte[]> getAccountAsset(byte[] address, byte[] assetId)
      throws ArchiveReaderException {
    return read(() -> delegate.getAccountAsset(address, assetId));
  }

  @Override
  public ArchiveReadResult<ContractCapsule> getContract(byte[] address)
      throws ArchiveReaderException {
    return read(() -> delegate.getContract(address));
  }

  @Override
  public ArchiveReadResult<ContractStateCapsule> getContractState(byte[] address)
      throws ArchiveReaderException {
    return read(() -> delegate.getContractState(address));
  }

  @Override
  public ArchiveReadResult<byte[]> getCode(byte[] address) throws ArchiveReaderException {
    return read(() -> delegate.getCode(address));
  }

  @Override
  public ArchiveReadResult<byte[]> getStorage(byte[] address, byte[] slot)
      throws ArchiveReaderException {
    return read(() -> delegate.getStorage(address, slot));
  }

  @Override
  public ArchiveReadResult<byte[]> getDynamicProperty(byte[] key) throws ArchiveReaderException {
    return read(() -> delegate.getDynamicProperty(key));
  }

  @Override
  public void close() {
    if (Thread.currentThread() != owner) {
      throw new ArchiveException("archive state reader used from a non-owner thread");
    }
    if (closed) {
      return;
    }
    closed = true;
    Throwable failure = null;
    failure = closeAndCollect(failure, delegate);
    boolean snapshotReleaseUncertain = snapshotPermit != null
        && ArchiveSnapshotReleaseException.contains(failure);
    if (snapshotPermit != null) {
      if (snapshotReleaseUncertain) {
        snapshotPermit.retainAfterUncertainRelease();
      }
      failure = closeAndCollect(failure, snapshotPermit);
    }
    failure = closeAndCollect(failure, mutationLease);
    failure = closeAndCollect(failure, lifecycleLease);
    if (failure != null) {
      try {
        queryLease.getContext().recordFailure(failure);
      } catch (Throwable recordFailure) {
        failure = collectFailure(failure, recordFailure);
      }
    }
    if (snapshotReleaseUncertain) {
      try {
        integrityFailureHandler.accept(failure);
      } catch (Throwable handlerFailure) {
        failure = collectFailure(failure, handlerFailure);
      }
    }
    try {
      ArchiveQueryTransportScope.closeAfterResponse(queryLease, responseValidator);
    } catch (Throwable settlementFailure) {
      failure = collectFailure(failure, settlementFailure);
      try {
        queryLease.getContext().recordFailure(settlementFailure);
      } catch (Throwable recordFailure) {
        failure = collectFailure(failure, recordFailure);
      }
    }
    rethrowCloseFailure(failure);
  }

  private static Throwable closeAndCollect(Throwable failure, AutoCloseable resource) {
    try {
      resource.close();
    } catch (Throwable closeFailure) {
      return collectFailure(failure, closeFailure);
    }
    return failure;
  }

  private static Throwable collectFailure(Throwable failure, Throwable candidate) {
    if (failure == null) {
      return candidate;
    }
    addSuppressedSafely(failure, candidate);
    return failure;
  }

  private static void addSuppressedSafely(Throwable primary, Throwable candidate) {
    if (primary == candidate) {
      return;
    }
    try {
      primary.addSuppressed(candidate);
    } catch (Throwable ignored) {
      // Preserve the first failure and continue releasing the remaining leases.
    }
  }

  private static void rethrowCloseFailure(Throwable failure) {
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

  private <T> T read(ReaderOperation<T> operation) throws ArchiveReaderException {
    try {
      requireOwnerOpen();
      return operation.run();
    } catch (ArchiveReaderException | RuntimeException | Error e) {
      queryLease.getContext().recordFailure(e);
      if (e instanceof ArchiveReaderException
          && ((ArchiveReaderException) e).isIntegrityFailure()) {
        reportIntegrityFailure(e);
      }
      throw e;
    }
  }

  private void reportIntegrityFailure(Throwable failure) {
    try {
      integrityFailureHandler.accept(failure);
    } catch (RuntimeException | Error handlerFailure) {
      addSuppressedSafely(handlerFailure, failure);
      throw handlerFailure;
    }
  }

  private <T> T readUnchecked(UncheckedReaderOperation<T> operation) {
    try {
      requireOwnerOpen();
      return operation.run();
    } catch (RuntimeException | Error e) {
      queryLease.getContext().recordFailure(e);
      throw e;
    }
  }

  @FunctionalInterface
  private interface ReaderOperation<T> {

    T run() throws ArchiveReaderException;
  }

  @FunctionalInterface
  private interface UncheckedReaderOperation<T> {

    T run();
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
