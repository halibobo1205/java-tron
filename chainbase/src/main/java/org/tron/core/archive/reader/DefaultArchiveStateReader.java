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
 * typed {@link ArchiveReadResult}. It never consults live state, so absence in the archive is
 * MISSING -- not the current value (the "no fallback to latest" invariant).
 */
public final class DefaultArchiveStateReader implements ArchiveStateReader {

  private static final int ADDRESS_LEN = 21;
  private static final int SLOT_LEN = 32;
  private static final int MAX_STORAGE_VALUE_LEN = 32;

  private final ArchiveTemporalStore temporalStore;
  private final ArchiveDomainCatalog catalog;
  private final DynamicKeyPolicy dynamicKeyPolicy = new DynamicKeyPolicy();
  private final ArchiveStatePoint point;

  DefaultArchiveStateReader(ArchiveTemporalStore temporalStore,
      ArchiveDomainCatalog catalog, ArchiveStatePoint point) {
    this.temporalStore = temporalStore;
    this.catalog = catalog;
    this.point = point;
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
        getStorageRaw(address, slot, contract.getValue().getContractVersion());
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

  private ArchiveReadResult<byte[]> getStorageRaw(byte[] address, byte[] slot, int contractVersion)
      throws ArchiveReaderException {
    byte[] primaryKey = ArchiveStorageKeyCodec.contractStorageKey(address, slot, contractVersion);
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
      stored = temporalStore.getAsOf(domain, canonicalKey, point.getTxNum());
    } catch (RuntimeException e) {
      throw new ArchiveReaderException(ArchiveReaderException.Reason.INTERNAL_IO,
          "archive temporal read failed for " + domain, e);
    }
    if (!stored.isPresent()) {
      return ArchiveReadResult.missing();
    }
    DomainValue value = stored.get();
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
    // A lightweight view over a shared temporal store; nothing to release per read.
  }
}
