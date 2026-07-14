package org.tron.core.archive.reader;

import com.google.common.primitives.Bytes;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.domain.ArchiveDomainCatalog;
import org.tron.core.archive.domain.ArchiveDomainDescriptor;
import org.tron.core.archive.domain.DynamicKeyPolicy;
import org.tron.core.archive.domain.ReaderPolicy;
import org.tron.core.archive.query.ArchiveQueryLimits;
import org.tron.core.archive.query.HistoricalQueryLimitException;
import org.tron.core.archive.query.QueryContext;
import org.tron.core.archive.temporal.ArchiveTemporalReadView;
import org.tron.core.archive.temporal.ArchiveTemporalStore;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.ContractCapsule;
import org.tron.core.capsule.ContractStateCapsule;
import org.tron.protos.Protocol.Account;
import org.tron.protos.contract.SmartContractOuterClass.ContractState;
import org.tron.protos.contract.SmartContractOuterClass.SmartContract;

/**
 * Reads historical state from an {@link ArchiveTemporalStore} at a fixed {@link ArchiveStatePoint},
 * mapping the store's PRESENT/TOMBSTONE/MISSING outcome (via {@code getAsOf}, inclusive-after) to a
 * typed {@link ArchiveReadResult}. When the temporal store has no row, an optional service-level
 * read-through may supply a guarded mid-chain baseline without exposing non-solidified writes.
 */
public final class DefaultArchiveStateReader implements ArchiveStateReader {

  private static final int ADDRESS_LEN = 21;
  private static final int SLOT_LEN = 32;
  private static final int MAX_STORAGE_VALUE_LEN = 32;
  private static final int DEFAULT_MAX_MEMO_ENTRIES = 4_096;
  private static final long DEFAULT_MAX_MEMO_BYTES = 4L * 1024 * 1024;

  private final ArchiveTemporalReadView temporalView;
  private final ArchiveDomainCatalog catalog;
  private final ArchiveReadThrough readThrough;
  private final DynamicKeyPolicy dynamicKeyPolicy = new DynamicKeyPolicy();
  private final ArchiveStatePoint point;
  private final boolean completeHistory;
  private final int maxMemoEntries;
  private final long maxMemoBytes;
  private final Map<RawKey, RawLookup> rawMemo =
      new LinkedHashMap<>(16, 0.75f, true);
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Thread ownerThread;
  private final QueryContext queryContext;
  private long memoBytes;
  // Runs on close() after the view is released -- e.g. releases the archive read lock a mid-chain
  // reader holds for its lifetime. A no-op for a snapshot reader (the lock was already released).
  private final Runnable onClose;

  DefaultArchiveStateReader(ArchiveTemporalStore temporalStore,
      ArchiveDomainCatalog catalog, ArchiveStatePoint point) {
    this(temporalStore, catalog, point, ArchiveReadThrough.NONE);
  }

  DefaultArchiveStateReader(ArchiveTemporalStore temporalStore,
      ArchiveDomainCatalog catalog, ArchiveStatePoint point, ArchiveReadThrough readThrough) {
    this(ArchiveTemporalReadView.passThrough(temporalStore), catalog, point, readThrough,
        () -> { }, true, DEFAULT_MAX_MEMO_ENTRIES, DEFAULT_MAX_MEMO_BYTES,
        new QueryContext(ArchiveQueryLimits.unlimited()));
  }

  DefaultArchiveStateReader(ArchiveTemporalReadView temporalView,
      ArchiveDomainCatalog catalog, ArchiveStatePoint point, ArchiveReadThrough readThrough) {
    this(temporalView, catalog, point, readThrough, () -> { }, true,
        DEFAULT_MAX_MEMO_ENTRIES, DEFAULT_MAX_MEMO_BYTES,
        new QueryContext(ArchiveQueryLimits.unlimited()));
  }

  DefaultArchiveStateReader(ArchiveTemporalReadView temporalView, ArchiveDomainCatalog catalog,
      ArchiveStatePoint point, ArchiveReadThrough readThrough, Runnable onClose) {
    this(temporalView, catalog, point, readThrough, onClose, true,
        DEFAULT_MAX_MEMO_ENTRIES, DEFAULT_MAX_MEMO_BYTES,
        new QueryContext(ArchiveQueryLimits.unlimited()));
  }

  DefaultArchiveStateReader(ArchiveTemporalReadView temporalView, ArchiveDomainCatalog catalog,
      ArchiveStatePoint point, ArchiveReadThrough readThrough, Runnable onClose,
      boolean completeHistory) {
    this(temporalView, catalog, point, readThrough, onClose, completeHistory,
        DEFAULT_MAX_MEMO_ENTRIES, DEFAULT_MAX_MEMO_BYTES,
        new QueryContext(ArchiveQueryLimits.unlimited()));
  }

  DefaultArchiveStateReader(ArchiveTemporalReadView temporalView, ArchiveDomainCatalog catalog,
      ArchiveStatePoint point, ArchiveReadThrough readThrough, Runnable onClose,
      boolean completeHistory, int maxMemoEntries, long maxMemoBytes) {
    this(temporalView, catalog, point, readThrough, onClose, completeHistory, maxMemoEntries,
        maxMemoBytes, new QueryContext(ArchiveQueryLimits.unlimited()));
  }

  DefaultArchiveStateReader(ArchiveTemporalReadView temporalView, ArchiveDomainCatalog catalog,
      ArchiveStatePoint point, ArchiveReadThrough readThrough, Runnable onClose,
      boolean completeHistory, int maxMemoEntries, long maxMemoBytes,
      QueryContext queryContext) {
    this.temporalView = temporalView;
    this.catalog = catalog;
    this.point = point;
    this.readThrough = readThrough == null ? ArchiveReadThrough.NONE : readThrough;
    this.onClose = onClose;
    this.completeHistory = completeHistory;
    this.maxMemoEntries = Math.max(0, maxMemoEntries);
    this.maxMemoBytes = Math.max(0, maxMemoBytes);
    this.queryContext = queryContext;
    this.ownerThread = Thread.currentThread();
  }

  @Override
  public ArchiveStatePoint getPoint() {
    requireOwnerAndOpen();
    return point;
  }

  @Override
  public QueryContext getQueryContext() {
    requireOwnerAndOpen();
    return queryContext;
  }

  @Override
  public boolean isGenesisComplete() {
    requireOwnerAndOpen();
    return completeHistory;
  }

  @Override
  public ArchiveReadResult<AccountCapsule> getAccount(byte[] address)
      throws ArchiveReaderException {
    requireOwnerAndOpen();
    queryContext.recordLogicalRead();
    requireLength(address, ADDRESS_LEN, "address");
    ArchiveReadResult<byte[]> raw = getRaw(ArchiveDomain.ACCOUNT, address);
    if (!raw.isPresent()) {
      return retype(raw);
    }
    try {
      return ArchiveReadResult.present(new AccountCapsule(Account.parseFrom(raw.getValue())));
    } catch (InvalidProtocolBufferException e) {
      forget(ArchiveDomain.ACCOUNT, address);
      throw new ArchiveReaderException(ArchiveReaderException.Reason.CODEC_ERROR,
          "archive ACCOUNT value is not a valid Account proto", e);
    }
  }

  @Override
  public ArchiveReadResult<byte[]> getAccountAsset(byte[] address, byte[] assetId)
      throws ArchiveReaderException {
    requireOwnerAndOpen();
    queryContext.recordLogicalRead();
    requireLength(address, ADDRESS_LEN, "address");
    if (assetId == null || assetId.length == 0) {
      return ArchiveReadResult.missing();
    }
    ArchiveReadResult<byte[]> raw = getRaw(
        ArchiveDomain.ACCOUNT_ASSET, Bytes.concat(address, assetId));
    if (raw.isPresent() && raw.getValue().length != Long.BYTES) {
      forget(ArchiveDomain.ACCOUNT_ASSET, Bytes.concat(address, assetId));
      throw new ArchiveReaderException(ArchiveReaderException.Reason.CORRUPT_VALUE,
          "archive account-asset value must be 8 bytes");
    }
    return raw;
  }

  @Override
  public ArchiveReadResult<ContractCapsule> getContract(byte[] address)
      throws ArchiveReaderException {
    requireOwnerAndOpen();
    queryContext.recordLogicalRead();
    requireLength(address, ADDRESS_LEN, "address");
    ArchiveReadResult<byte[]> raw = getRaw(ArchiveDomain.CONTRACT, address);
    if (!raw.isPresent()) {
      return retype(raw);
    }
    try {
      return ArchiveReadResult.present(
          new ContractCapsule(SmartContract.parseFrom(raw.getValue())));
    } catch (InvalidProtocolBufferException e) {
      forget(ArchiveDomain.CONTRACT, address);
      throw new ArchiveReaderException(ArchiveReaderException.Reason.CODEC_ERROR,
          "archive CONTRACT value is not a valid SmartContract proto", e);
    }
  }

  @Override
  public ArchiveReadResult<ContractStateCapsule> getContractState(byte[] address)
      throws ArchiveReaderException {
    requireOwnerAndOpen();
    queryContext.recordLogicalRead();
    requireLength(address, ADDRESS_LEN, "address");
    ArchiveReadResult<byte[]> raw = getRaw(ArchiveDomain.CONTRACT_STATE, address);
    if (!raw.isPresent()) {
      return retype(raw);
    }
    try {
      return ArchiveReadResult.present(
          new ContractStateCapsule(ContractState.parseFrom(raw.getValue())));
    } catch (InvalidProtocolBufferException e) {
      forget(ArchiveDomain.CONTRACT_STATE, address);
      throw new ArchiveReaderException(ArchiveReaderException.Reason.CODEC_ERROR,
          "archive CONTRACT_STATE value is not a valid ContractState proto", e);
    }
  }

  @Override
  public ArchiveReadResult<byte[]> getCode(byte[] address) throws ArchiveReaderException {
    requireOwnerAndOpen();
    queryContext.recordLogicalRead();
    requireLength(address, ADDRESS_LEN, "address");
    return getRaw(ArchiveDomain.CODE, address);
  }

  @Override
  public ArchiveReadResult<byte[]> getStorage(byte[] address, byte[] slot)
      throws ArchiveReaderException {
    requireOwnerAndOpen();
    queryContext.recordLogicalRead();
    requireLength(address, ADDRESS_LEN, "address");
    requireLength(slot, SLOT_LEN, "slot");
    ArchiveReadResult<ContractCapsule> contract = getContract(address);
    if (!contract.isPresent()) {
      return retype(contract);
    }
    byte[] primaryKey = ArchiveStorageKeyCodec.contractStorageKey(
        address, slot, contract.getValue().getTrxHash(),
        contract.getValue().getContractVersion());
    ArchiveReadResult<byte[]> raw = getRaw(ArchiveDomain.CONTRACT_STORAGE, primaryKey);
    if (raw.isPresent() && raw.getValue().length > MAX_STORAGE_VALUE_LEN) {
      forget(ArchiveDomain.CONTRACT_STORAGE, primaryKey);
      throw new ArchiveReaderException(ArchiveReaderException.Reason.CORRUPT_VALUE,
          "archive storage value exceeds 32 bytes");
    }
    return raw;
  }

  @Override
  public ArchiveReadResult<byte[]> getDynamicProperty(byte[] key) throws ArchiveReaderException {
    requireOwnerAndOpen();
    queryContext.recordLogicalRead();
    if (key == null || key.length == 0) {
      throw new IllegalArgumentException("dynamic property key must be non-empty");
    }
    ReaderPolicy keyPolicy = dynamicKeyPolicy.decision(key).getReaderPolicy();
    if (keyPolicy != ReaderPolicy.PUBLIC_STATE && keyPolicy != ReaderPolicy.HISTORICAL_VM) {
      throw new ArchiveReaderException(ArchiveReaderException.Reason.DOMAIN_UNSUPPORTED,
          "dynamic property not readable by archive state reader");
    }
    return getRaw(ArchiveDomain.DYNAMIC_PROPERTIES, key);
  }

  private ArchiveReadResult<byte[]> getRaw(ArchiveDomain domain, byte[] canonicalKey)
      throws ArchiveReaderException {
    ArchiveDomainDescriptor descriptor = catalog.descriptorFor(domain);
    if (descriptor == null || descriptor.getReaderPolicy() == ReaderPolicy.NOT_READABLE) {
      throw new ArchiveReaderException(ArchiveReaderException.Reason.DOMAIN_UNSUPPORTED,
          "domain not readable: " + domain);
    }
    RawKey memoKey = new RawKey(domain, canonicalKey);
    RawLookup memoized = rawMemo.get(memoKey);
    if (memoized != null) {
      queryContext.recordCacheHit();
      return toReadResult(domain, memoized);
    }
    RawLookup lookup;
    try {
      queryContext.recordBackendReads(temporalView.getAsOfBackendReadCost());
      Optional<DomainValue> stored = temporalView.getAsOf(
          domain, memoKey.canonicalKey(), point.getTxNum());
      if (stored.isPresent()) {
        lookup = RawLookup.from(stored.get());
      } else {
        queryContext.recordBackendReads(readThrough.backendReadCost(domain));
        Optional<DomainValue> readThroughValue = readThrough.read(
            domain, memoKey.canonicalKey(), point);
        lookup = readThroughValue.isPresent()
            ? RawLookup.from(readThroughValue.get())
            : completeHistory ? RawLookup.missing() : RawLookup.unknown();
      }
    } catch (HistoricalQueryLimitException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new ArchiveReaderException(ArchiveReaderException.Reason.INTERNAL_IO,
          "archive temporal read failed for " + domain, e);
    }
    remember(memoKey, lookup);
    return toReadResult(domain, lookup);
  }

  private void remember(RawKey key, RawLookup lookup) {
    if (maxMemoEntries == 0 || maxMemoBytes == 0) {
      return;
    }
    long bytes = key.estimatedBytes() + lookup.estimatedBytes();
    if (bytes > maxMemoBytes) {
      return;
    }
    while (!rawMemo.isEmpty()
        && (rawMemo.size() >= maxMemoEntries || memoBytes > maxMemoBytes - bytes)) {
      Iterator<Map.Entry<RawKey, RawLookup>> iterator = rawMemo.entrySet().iterator();
      Map.Entry<RawKey, RawLookup> eldest = iterator.next();
      memoBytes -= eldest.getKey().estimatedBytes() + eldest.getValue().estimatedBytes();
      iterator.remove();
    }
    rawMemo.put(key, lookup);
    memoBytes += bytes;
  }

  private void forget(ArchiveDomain domain, byte[] canonicalKey) {
    RawKey key = new RawKey(domain, canonicalKey);
    RawLookup removed = rawMemo.remove(key);
    if (removed != null) {
      memoBytes -= key.estimatedBytes() + removed.estimatedBytes();
    }
  }

  private ArchiveReadResult<byte[]> toReadResult(ArchiveDomain domain, RawLookup lookup)
      throws ArchiveReaderException {
    switch (lookup.status) {
      case PRESENT:
        return ArchiveReadResult.present(lookup.value());
      case TOMBSTONE:
        return ArchiveReadResult.tombstone();
      case MISSING:
        return ArchiveReadResult.missing();
      case UNKNOWN_BEFORE_COVERAGE:
        throw new ArchiveReaderException(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE,
            "archive " + domain.name().toLowerCase(java.util.Locale.ROOT)
                + " is unknown before mid-chain coverage");
      default:
        throw new IllegalStateException("unknown raw archive lookup status " + lookup.status);
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> ArchiveReadResult<T> retype(ArchiveReadResult<?> nonPresent) {
    // TOMBSTONE / MISSING carry no value, so the cast is safe.
    return (ArchiveReadResult<T>) nonPresent;
  }

  private static void requireLength(byte[] input, int expected, String name) {
    if (input == null || input.length != expected) {
      // Caller-contract violation (bad RPC param); the RPC layer maps this to invalid-params.
      throw new IllegalArgumentException(name + " must be " + expected + " bytes");
    }
  }

  @Override
  public void close() {
    requireOwner();
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    RuntimeException failure = null;
    try {
      temporalView.close();
    } catch (RuntimeException e) {
      failure = e;
    }
    try {
      onClose.run();
    } catch (RuntimeException e) {
      if (failure == null) {
        failure = e;
      } else {
        failure.addSuppressed(e);
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  private void requireOwnerAndOpen() {
    requireOwner();
    if (closed.get()) {
      throw new ArchiveException("archive state reader is closed");
    }
  }

  private void requireOwner() {
    if (Thread.currentThread() != ownerThread) {
      throw new ArchiveException("archive state reader used from a non-owner thread");
    }
  }

  private enum RawStatus {
    PRESENT,
    TOMBSTONE,
    MISSING,
    UNKNOWN_BEFORE_COVERAGE
  }

  private static final class RawLookup {

    private static final RawLookup TOMBSTONE = new RawLookup(RawStatus.TOMBSTONE, null);
    private static final RawLookup MISSING = new RawLookup(RawStatus.MISSING, null);
    private static final RawLookup UNKNOWN =
        new RawLookup(RawStatus.UNKNOWN_BEFORE_COVERAGE, null);

    private final RawStatus status;
    private final byte[] value;

    private RawLookup(RawStatus status, byte[] value) {
      this.status = status;
      this.value = value == null ? null : Arrays.copyOf(value, value.length);
    }

    private static RawLookup from(DomainValue value) {
      return value.isDeleted() ? TOMBSTONE : new RawLookup(RawStatus.PRESENT, value.getValue());
    }

    private static RawLookup missing() {
      return MISSING;
    }

    private static RawLookup unknown() {
      return UNKNOWN;
    }

    private byte[] value() {
      return Arrays.copyOf(value, value.length);
    }

    private long estimatedBytes() {
      return 24L + (value == null ? 0 : value.length);
    }
  }

  private static final class RawKey {

    private final ArchiveDomain domain;
    private final byte[] canonicalKey;

    private RawKey(ArchiveDomain domain, byte[] canonicalKey) {
      this.domain = domain;
      this.canonicalKey = Arrays.copyOf(canonicalKey, canonicalKey.length);
    }

    private byte[] canonicalKey() {
      return Arrays.copyOf(canonicalKey, canonicalKey.length);
    }

    private long estimatedBytes() {
      return 24L + canonicalKey.length;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (!(obj instanceof RawKey)) {
        return false;
      }
      RawKey other = (RawKey) obj;
      return domain == other.domain && Arrays.equals(canonicalKey, other.canonicalKey);
    }

    @Override
    public int hashCode() {
      return 31 * domain.hashCode() + Arrays.hashCode(canonicalKey);
    }
  }
}
