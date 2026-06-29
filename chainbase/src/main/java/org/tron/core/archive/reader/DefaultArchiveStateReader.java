package org.tron.core.archive.reader;

import com.google.protobuf.InvalidProtocolBufferException;
import java.util.Optional;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.domain.ArchiveDomainCatalog;
import org.tron.core.archive.domain.ArchiveDomainDescriptor;
import org.tron.core.archive.domain.ReaderPolicy;
import org.tron.core.archive.temporal.ArchiveTemporalStore;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.ContractCapsule;
import org.tron.protos.Protocol.Account;
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
  private final ArchiveStatePoint point;

  public DefaultArchiveStateReader(ArchiveTemporalStore temporalStore,
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
  public ArchiveReadResult<byte[]> getCode(byte[] address) throws ArchiveReaderException {
    requireLength(address, ADDRESS_LEN, "address");
    return getRaw(ArchiveDomain.CODE, address);
  }

  @Override
  public ArchiveReadResult<byte[]> getStorage(byte[] address, byte[] slot)
      throws ArchiveReaderException {
    requireLength(address, ADDRESS_LEN, "address");
    requireLength(slot, SLOT_LEN, "slot");
    // The storage-key version (0 or 1) is per-contract. Instead of reading the historical contract
    // to derive it (the code plan's approach), probe both versions and take whichever is archived,
    // staying strictly inside CONTRACT_STORAGE (more latest-isolated than a contract lookup). A
    // PRESENT on either version wins immediately; a version-0 TOMBSTONE does NOT short-circuit
    // so a create2 redeploy that bumped the version (v0 deleted, v1 written) still returns the live
    // v1 value rather than rendering the stale v0 tombstone as zero.
    ArchiveReadResult<byte[]> firstNonMissing = null;
    for (int contractVersion = 0; contractVersion <= 1; contractVersion++) {
      byte[] key = ArchiveStorageKeyCodec.contractStorageKey(address, slot, contractVersion);
      ArchiveReadResult<byte[]> raw = getRaw(ArchiveDomain.CONTRACT_STORAGE, key);
      if (raw.isPresent()) {
        if (raw.getValue().length > MAX_STORAGE_VALUE_LEN) {
          throw new ArchiveReaderException(ArchiveReaderException.Reason.CORRUPT_VALUE,
              "archive storage value exceeds 32 bytes");
        }
        return raw;
      }
      if (raw.getStatus() != ArchiveReadResult.Status.MISSING && firstNonMissing == null) {
        firstNonMissing = raw; // remember a tombstone, but keep probing for a PRESENT value
      }
    }
    return firstNonMissing != null ? firstNonMissing : ArchiveReadResult.missing();
  }

  @Override
  public ArchiveReadResult<byte[]> getDynamicProperty(byte[] key) throws ArchiveReaderException {
    if (key == null || key.length == 0) {
      throw new IllegalArgumentException("dynamic property key must be non-empty");
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
