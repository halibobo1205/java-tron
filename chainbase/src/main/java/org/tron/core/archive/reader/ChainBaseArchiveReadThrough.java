package org.tron.core.archive.reader;

import com.google.common.primitives.Longs;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import org.tron.common.utils.ByteArray;
import org.tron.core.ChainBaseManager;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.codec.ContractStorageKeyCodec;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.domain.ArchiveDomainCatalog;
import org.tron.core.archive.domain.ArchiveDomainDescriptor;
import org.tron.core.archive.domain.DefaultArchiveDomainCatalog;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BytesCapsule;
import org.tron.core.capsule.CodeCapsule;
import org.tron.core.capsule.ContractCapsule;
import org.tron.core.capsule.ContractStateCapsule;
import org.tron.core.capsule.StorageRowCapsule;
import org.tron.core.store.DynamicPropertiesStore;

/**
 * Reads the current ChainBase state as a mid-chain archive baseline. The caller is responsible for
 * shielding reads from non-solidified future writes before delegating here.
 */
public final class ChainBaseArchiveReadThrough implements ArchiveReadThrough {

  private static final int ADDRESS_LEN = 21;

  private final ChainBaseManager chainBaseManager;
  private final ArchiveDomainCatalog catalog;

  public ChainBaseArchiveReadThrough(ChainBaseManager chainBaseManager) {
    this(chainBaseManager, new DefaultArchiveDomainCatalog());
  }

  public ChainBaseArchiveReadThrough(ChainBaseManager chainBaseManager,
      ArchiveDomainCatalog catalog) {
    if (chainBaseManager == null) {
      throw new IllegalArgumentException("chainBaseManager must not be null");
    }
    if (catalog == null) {
      throw new IllegalArgumentException("catalog must not be null");
    }
    this.chainBaseManager = chainBaseManager;
    this.catalog = catalog;
  }

  @Override
  public Optional<DomainValue> read(ArchiveDomain domain, byte[] canonicalKey,
      ArchiveStatePoint point) throws ArchiveReaderException {
    try {
      switch (domain) {
        case ACCOUNT:
          return readAccount(canonicalKey);
        case ACCOUNT_ASSET:
          return readAccountAsset(canonicalKey);
        case CODE:
          return readCode(canonicalKey);
        case CONTRACT:
          return readContract(canonicalKey);
        case CONTRACT_STATE:
          return readContractState(canonicalKey);
        case CONTRACT_STORAGE:
          return readContractStorage(canonicalKey);
        case DYNAMIC_PROPERTIES:
          return readDynamicProperty(canonicalKey);
        default:
          return Optional.empty();
      }
    } catch (ArchiveReaderException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new ArchiveReaderException(ArchiveReaderException.Reason.INTERNAL_IO,
          "archive read-through failed for " + domain, e);
    }
  }

  @Override
  public long backendReadCost(ArchiveDomain domain) {
    switch (domain) {
      case ACCOUNT:
        return 1L;
      case ACCOUNT_ASSET:
      case CODE:
      case CONTRACT:
      case CONTRACT_STATE:
      case CONTRACT_STORAGE:
      case DYNAMIC_PROPERTIES:
        return 2L;
      default:
        return 0L;
    }
  }

  private Optional<DomainValue> readAccount(byte[] canonicalKey) throws ArchiveReaderException {
    AccountCapsule account = chainBaseManager.getAccountStore().get(canonicalKey);
    return account == null ? tombstone(ArchiveDomain.ACCOUNT)
        : present(ArchiveDomain.ACCOUNT, account.getData());
  }

  private Optional<DomainValue> readAccountAsset(byte[] canonicalKey)
      throws ArchiveReaderException {
    byte[] address = Arrays.copyOfRange(canonicalKey, 0, ADDRESS_LEN);
    AccountCapsule account = chainBaseManager.getAccountStore().get(address);
    if (account == null) {
      return tombstone(ArchiveDomain.ACCOUNT_ASSET);
    }
    String assetId = new String(canonicalKey, ADDRESS_LEN,
        canonicalKey.length - ADDRESS_LEN, StandardCharsets.US_ASCII);
    Long overlay = account.getInstance().getAssetV2Map().get(assetId);
    long balance;
    if (overlay != null) {
      balance = overlay;
    } else if (account.getAssetOptimized()) {
      byte[] raw = chainBaseManager.getAccountAssetStore().get(canonicalKey);
      balance = raw == null || raw.length == 0 ? 0L : Longs.fromByteArray(raw);
    } else {
      balance = 0L;
    }
    if (balance == 0L) {
      return tombstone(ArchiveDomain.ACCOUNT_ASSET);
    }
    return present(ArchiveDomain.ACCOUNT_ASSET, Longs.toByteArray(balance));
  }

  private Optional<DomainValue> readCode(byte[] canonicalKey) throws ArchiveReaderException {
    if (!chainBaseManager.getCodeStore().has(canonicalKey)) {
      return tombstone(ArchiveDomain.CODE);
    }
    CodeCapsule code = chainBaseManager.getCodeStore().get(canonicalKey);
    return code == null || code.getData() == null ? tombstone(ArchiveDomain.CODE)
        : present(ArchiveDomain.CODE, code.getData());
  }

  private Optional<DomainValue> readContract(byte[] canonicalKey) throws ArchiveReaderException {
    if (!chainBaseManager.getContractStore().has(canonicalKey)) {
      return tombstone(ArchiveDomain.CONTRACT);
    }
    ContractCapsule contract = chainBaseManager.getContractStore().get(canonicalKey);
    return contract == null || contract.getData() == null ? tombstone(ArchiveDomain.CONTRACT)
        : present(ArchiveDomain.CONTRACT, contract.getData());
  }

  private Optional<DomainValue> readContractState(byte[] canonicalKey)
      throws ArchiveReaderException {
    if (!chainBaseManager.getContractStateStore().has(canonicalKey)) {
      return tombstone(ArchiveDomain.CONTRACT_STATE);
    }
    ContractStateCapsule state = chainBaseManager.getContractStateStore().get(canonicalKey);
    return state == null || state.getData() == null
        ? tombstone(ArchiveDomain.CONTRACT_STATE)
        : present(ArchiveDomain.CONTRACT_STATE, state.getData());
  }

  private Optional<DomainValue> readContractStorage(byte[] canonicalKey)
      throws ArchiveReaderException {
    ContractStorageKeyCodec codec = new ContractStorageKeyCodec();
    codec.validate(canonicalKey);
    if (!chainBaseManager.getStorageRowStore().has(canonicalKey)) {
      return tombstone(ArchiveDomain.CONTRACT_STORAGE);
    }
    StorageRowCapsule row = chainBaseManager.getStorageRowStore().getUnchecked(canonicalKey);
    return row == null || row.getInstance() == null
        ? tombstone(ArchiveDomain.CONTRACT_STORAGE)
        : present(ArchiveDomain.CONTRACT_STORAGE, row.getValue());
  }

  private Optional<DomainValue> readDynamicProperty(byte[] canonicalKey)
      throws ArchiveReaderException {
    BytesCapsule value = chainBaseManager.getDynamicPropertiesStore().getUnchecked(canonicalKey);
    if (value == null || value.getData() == null) {
      return readDynamicPropertyDefault(canonicalKey);
    }
    return present(ArchiveDomain.DYNAMIC_PROPERTIES, value.getData());
  }

  private Optional<DomainValue> readDynamicPropertyDefault(byte[] canonicalKey)
      throws ArchiveReaderException {
    String key = new String(canonicalKey, StandardCharsets.US_ASCII);
    DynamicPropertiesStore store = chainBaseManager.getDynamicPropertiesStore();
    switch (key) {
      case "ALLOW_TVM_SHANGHAI":
        return dynamicLong(store.getAllowTvmShangHai());
      case "ALLOW_ENERGY_ADJUSTMENT":
        return dynamicLong(store.getAllowEnergyAdjustment());
      case "ALLOW_STRICT_MATH":
        return dynamicLong(store.getAllowStrictMath());
      case "CONSENSUS_LOGIC_OPTIMIZATION":
        return dynamicLong(store.getConsensusLogicOptimization());
      case "ALLOW_TVM_CANCUN":
        return dynamicLong(store.getAllowTvmCancun());
      case "ALLOW_TVM_BLOB":
        return dynamicLong(store.getAllowTvmBlob());
      case "ALLOW_TVM_SELFDESTRUCT_RESTRICTION":
        return dynamicLong(store.getAllowTvmSelfdestructRestriction());
      case "ALLOW_TVM_OSAKA":
        return dynamicLong(store.getAllowTvmOsaka());
      case "ALLOW_TVM_PRAGUE":
        return dynamicLong(store.getAllowTvmPrague());
      case "ALLOW_HARDEN_RESOURCE_CALCULATION":
        return dynamicLong(store.getAllowHardenResourceCalculation());
      case "ALLOW_HARDEN_EXCHANGE_CALCULATION":
        return dynamicLong(store.getAllowHardenExchangeCalculation());
      default:
        return Optional.empty();
    }
  }

  private Optional<DomainValue> dynamicLong(long value) throws ArchiveReaderException {
    return present(ArchiveDomain.DYNAMIC_PROPERTIES, ByteArray.fromLong(value));
  }

  private Optional<DomainValue> present(ArchiveDomain domain, byte[] value)
      throws ArchiveReaderException {
    try {
      return Optional.of(descriptor(domain).getValueCodec().normalizePut(value));
    } catch (ArchiveException e) {
      throw new ArchiveReaderException(ArchiveReaderException.Reason.CODEC_ERROR,
          "archive read-through value is invalid for " + domain, e);
    }
  }

  private Optional<DomainValue> tombstone(ArchiveDomain domain) throws ArchiveReaderException {
    try {
      return Optional.of(descriptor(domain).getValueCodec().normalizeDelete());
    } catch (ArchiveException e) {
      throw new ArchiveReaderException(ArchiveReaderException.Reason.CODEC_ERROR,
          "archive read-through tombstone is invalid for " + domain, e);
    }
  }

  private ArchiveDomainDescriptor descriptor(ArchiveDomain domain) throws ArchiveReaderException {
    ArchiveDomainDescriptor descriptor = catalog.descriptorFor(domain);
    if (descriptor == null) {
      throw new ArchiveReaderException(ArchiveReaderException.Reason.DOMAIN_UNSUPPORTED,
          "domain not readable by archive read-through: " + domain);
    }
    return descriptor;
  }

}
