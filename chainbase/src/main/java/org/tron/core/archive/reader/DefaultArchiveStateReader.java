package org.tron.core.archive.reader;

import com.google.common.primitives.Bytes;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.Optional;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.domain.ArchiveDomainCatalog;
import org.tron.core.archive.domain.ArchiveDomainDescriptor;
import org.tron.core.archive.domain.DynamicKeyPolicy;
import org.tron.core.archive.domain.ReaderPolicy;
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

  private final ArchiveTemporalReadView temporalView;
  private final ArchiveDomainCatalog catalog;
  private final ArchiveReadThrough readThrough;
  private final DynamicKeyPolicy dynamicKeyPolicy = new DynamicKeyPolicy();
  private final ArchiveStatePoint point;
  // Runs on close() after the view is released -- e.g. releases the archive read lock a mid-chain
  // reader holds for its lifetime. A no-op for a snapshot reader (the lock was already released).
  private final Runnable onClose;

  DefaultArchiveStateReader(ArchiveTemporalStore temporalStore,
      ArchiveDomainCatalog catalog, ArchiveStatePoint point) {
    this(temporalStore, catalog, point, ArchiveReadThrough.NONE);
  }

  DefaultArchiveStateReader(ArchiveTemporalStore temporalStore,
      ArchiveDomainCatalog catalog, ArchiveStatePoint point, ArchiveReadThrough readThrough) {
    // A live-store reader (mid-chain / lock-held path) wraps the store in a pass-through view.
    this(ArchiveTemporalReadView.passThrough(temporalStore), catalog, point, readThrough);
  }

  DefaultArchiveStateReader(ArchiveTemporalReadView temporalView,
      ArchiveDomainCatalog catalog, ArchiveStatePoint point, ArchiveReadThrough readThrough) {
    this(temporalView, catalog, point, readThrough, () -> {
    });
  }

  DefaultArchiveStateReader(ArchiveTemporalReadView temporalView, ArchiveDomainCatalog catalog,
      ArchiveStatePoint point, ArchiveReadThrough readThrough, Runnable onClose) {
    this.temporalView = temporalView;
    this.catalog = catalog;
    this.point = point;
    this.readThrough = readThrough == null ? ArchiveReadThrough.NONE : readThrough;
    this.onClose = onClose;
  }

  @Override
  public ArchiveStatePoint getPoint() {
    return point;
  }

  @Override
  public ArchiveReadResult<AccountCapsule> getAccount(byte[] address)
      throws ArchiveReaderException {
    requireLength(address, ADDRESS_LEN, "address");
    ArchiveReadResult<byte[]> raw = getRaw(ArchiveDomain.ACCOUNT, address);
    if (!raw.isPresent()) {
      return retype(raw);
    }
    try {
      return ArchiveReadResult.present(new AccountCapsule(Account.parseFrom(raw.getValue())));
    } catch (InvalidProtocolBufferException e) {
      throw new ArchiveReaderException(ArchiveReaderException.Reason.CODEC_ERROR,
          "archive ACCOUNT value is not a valid Account proto", e);
    }
  }

  @Override
  public ArchiveReadResult<byte[]> getAccountAsset(byte[] address, byte[] assetId)
      throws ArchiveReaderException {
    requireLength(address, ADDRESS_LEN, "address");
    if (assetId == null || assetId.length == 0) {
      return ArchiveReadResult.missing();
    }
    ArchiveReadResult<byte[]> raw = getRaw(
        ArchiveDomain.ACCOUNT_ASSET, Bytes.concat(address, assetId));
    if (raw.isPresent() && raw.getValue().length != Long.BYTES) {
      throw new ArchiveReaderException(ArchiveReaderException.Reason.CORRUPT_VALUE,
          "archive account-asset value must be 8 bytes");
    }
    return raw;
  }

  @Override
  public ArchiveReadResult<ContractCapsule> getContract(byte[] address)
      throws ArchiveReaderException {
    requireLength(address, ADDRESS_LEN, "address");
    ArchiveReadResult<byte[]> raw = getRaw(ArchiveDomain.CONTRACT, address);
    if (!raw.isPresent()) {
      return retype(raw);
    }
    try {
      return ArchiveReadResult.present(
          new ContractCapsule(SmartContract.parseFrom(raw.getValue())));
    } catch (InvalidProtocolBufferException e) {
      throw new ArchiveReaderException(ArchiveReaderException.Reason.CODEC_ERROR,
          "archive CONTRACT value is not a valid SmartContract proto", e);
    }
  }

  @Override
  public ArchiveReadResult<ContractStateCapsule> getContractState(byte[] address)
      throws ArchiveReaderException {
    requireLength(address, ADDRESS_LEN, "address");
    ArchiveReadResult<byte[]> raw = getRaw(ArchiveDomain.CONTRACT_STATE, address);
    if (!raw.isPresent()) {
      return retype(raw);
    }
    try {
      return ArchiveReadResult.present(
          new ContractStateCapsule(ContractState.parseFrom(raw.getValue())));
    } catch (InvalidProtocolBufferException e) {
      throw new ArchiveReaderException(ArchiveReaderException.Reason.CODEC_ERROR,
          "archive CONTRACT_STATE value is not a valid ContractState proto", e);
    }
  }

  @Override
  public ArchiveReadResult<byte[]> getCode(byte[] address) throws ArchiveReaderException {
    requireLength(address, ADDRESS_LEN, "address");
    return getRaw(ArchiveDomain.CODE, address);
  }

  @Override
  public ArchiveReadResult<byte[]> getStorage(byte[] address, byte[] slot)
      throws ArchiveReaderException {
    requireLength(address, ADDRESS_LEN, "address");
    requireLength(slot, SLOT_LEN, "slot");
    ArchiveReadResult<ContractCapsule> contract = getContract(address);
    if (!contract.isPresent()) {
      return retype(contract);
    }
    ArchiveReadResult<byte[]> raw =
        getStorageRaw(address, slot, contract.getValue().getTrxHash(),
            contract.getValue().getContractVersion());
    if (raw.isPresent() && raw.getValue().length > MAX_STORAGE_VALUE_LEN) {
      throw new ArchiveReaderException(ArchiveReaderException.Reason.CORRUPT_VALUE,
          "archive storage value exceeds 32 bytes");
    }
    return raw;
  }

  @Override
  public ArchiveReadResult<byte[]> getDynamicProperty(byte[] key) throws ArchiveReaderException {
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

  private ArchiveReadResult<byte[]> getStorageRaw(byte[] address, byte[] slot, byte[] trxHash,
      int contractVersion) throws ArchiveReaderException {
    byte[] primaryKey =
        ArchiveStorageKeyCodec.contractStorageKey(address, slot, trxHash, contractVersion);
    return getRaw(ArchiveDomain.CONTRACT_STORAGE, primaryKey);
  }

  private ArchiveReadResult<byte[]> getRaw(ArchiveDomain domain, byte[] canonicalKey)
      throws ArchiveReaderException {
    ArchiveDomainDescriptor descriptor = catalog.descriptorFor(domain);
    if (descriptor == null || descriptor.getReaderPolicy() == ReaderPolicy.NOT_READABLE) {
      throw new ArchiveReaderException(ArchiveReaderException.Reason.DOMAIN_UNSUPPORTED,
          "domain not readable: " + domain);
    }
    Optional<DomainValue> stored;
    try {
      stored = temporalView.getAsOf(domain, canonicalKey, point.getTxNum());
    } catch (RuntimeException e) {
      throw new ArchiveReaderException(ArchiveReaderException.Reason.INTERNAL_IO,
          "archive temporal read failed for " + domain, e);
    }
    if (!stored.isPresent()) {
      Optional<DomainValue> readThroughValue = readThrough.read(domain, canonicalKey, point);
      return readThroughValue.isPresent()
          ? toReadResult(readThroughValue.get())
          : ArchiveReadResult.missing();
    }
    return toReadResult(stored.get());
  }

  private ArchiveReadResult<byte[]> toReadResult(DomainValue value) {
    return value.isDeleted()
        ? ArchiveReadResult.tombstone()
        : ArchiveReadResult.present(value.getValue());
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
    // Release the temporal read view (a no-op for a live pass-through, or the RocksDB snapshot for
    // a snapshot reader), then run onClose (e.g. release the read lock a mid-chain reader held).
    try {
      temporalView.close();
    } finally {
      onClose.run();
    }
  }
}
