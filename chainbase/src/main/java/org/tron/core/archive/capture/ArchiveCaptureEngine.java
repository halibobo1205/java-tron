package org.tron.core.archive.capture;

import com.google.common.primitives.Bytes;
import com.google.common.primitives.Longs;
import com.google.protobuf.InvalidProtocolBufferException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.ArchiveExecutionContext;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.domain.ArchiveDomainCatalog;
import org.tron.core.archive.domain.ArchiveDomainDescriptor;
import org.tron.core.archive.domain.ArchiveDomainRegistry;
import org.tron.core.archive.domain.DynamicKeyDecision;
import org.tron.core.archive.domain.DynamicKeyPolicy;
import org.tron.core.archive.domain.HistoryPolicy;
import org.tron.core.archive.domain.StoreBinding;
import org.tron.core.archive.txnum.ArchiveTxPosition;
import org.tron.protos.Protocol.Account;

/**
 * Routes store writes to domain change records. Given a {@code (dbName, key, value)} and the
 * current {@link ArchiveTxPosition}, it resolves the binding, and for generic-captured domains
 * encodes the canonical key/value via the descriptor, appending an {@link ArchiveChangeRecord}.
 *
 * <p>Writes outside a capture context (archive disabled, or not inside block apply) and writes to
 * store-specific / semantic / excluded stores are no-ops here. UNKNOWN stores fail closed inside a
 * capture context, because a newly introduced state store that is not classified by the archive
 * schema must not be silently omitted. STORE_SPECIFIC/SEMANTIC domains are captured by dedicated
 * hooks (L4b/L4c), not this generic path.
 *
 * <p>Not thread-safe: the buffer is written only by the single block-apply thread (the only thread
 * with a non-empty execution context). {@link #capturePut}/{@link #captureDelete} may throw on
 * encode failure; the store-facing {@link ArchiveCaptureHolder} records those failures so block
 * commit can fail-stop instead of publishing an incomplete archive.
 */
public final class ArchiveCaptureEngine {

  private final ArchiveDomainRegistry registry;
  private final ArchiveDomainCatalog catalog;
  private final DynamicKeyPolicy dynamicKeyPolicy;
  private final ArchiveExecutionContext context;
  private final long maxRawRecords;
  private final long maxRawBytes;
  private final Map<ChangeKey, ArchiveChangeRecord> records = new LinkedHashMap<>();
  private int rawRecordCount;
  private long rawRecordBytes;
  private long previousValueReads;
  private long previousValueReadFailures;
  private long previousValueReadNanos;
  private long accountAssetPrefixRows;
  private long accountAssetPointReads;
  private long accountAssetLookups;
  private long accountAssetLookupNanos;
  private ArchiveException failure;
  private boolean blockActive;

  public ArchiveCaptureEngine(ArchiveDomainRegistry registry, ArchiveDomainCatalog catalog,
      DynamicKeyPolicy dynamicKeyPolicy, ArchiveExecutionContext context) {
    this(registry, catalog, dynamicKeyPolicy, context, Long.MAX_VALUE, Long.MAX_VALUE);
  }

  public ArchiveCaptureEngine(ArchiveDomainRegistry registry, ArchiveDomainCatalog catalog,
      DynamicKeyPolicy dynamicKeyPolicy, ArchiveExecutionContext context,
      long maxRawRecords, long maxRawBytes) {
    this.registry = registry;
    this.catalog = catalog;
    this.dynamicKeyPolicy = dynamicKeyPolicy;
    this.context = context;
    if (maxRawRecords <= 0 || maxRawBytes <= 0) {
      throw new IllegalArgumentException("archive capture limits must be positive");
    }
    this.maxRawRecords = maxRawRecords;
    this.maxRawBytes = maxRawBytes;
  }

  public void capturePut(String dbName, byte[] key, byte[] prevValue, byte[] value) {
    capture(dbName, key, prevValue, value, false);
  }

  public void captureDelete(String dbName, byte[] key, byte[] prevValue) {
    capture(dbName, key, prevValue, null, true);
  }

  /**
   * Captures a record for a SEMANTIC domain (e.g. CONTRACT_STORAGE) whose canonical key is built by
   * a caller at the semantic write boundary. Resolves the codec by domain directly, bypassing the
   * dbName/registry lookup. {@code prevValue} is the value before the change (null = the key was
   * absent), feeding the Erigon prev-value history.
   */
  public void captureSemanticPut(ArchiveDomain domain, byte[] canonicalKey, byte[] prevValue,
      byte[] value) {
    captureSemantic(domain, canonicalKey, prevValue, value, false);
  }

  public void captureSemanticDelete(ArchiveDomain domain, byte[] canonicalKey, byte[] prevValue) {
    captureSemantic(domain, canonicalKey, prevValue, null, true);
  }

  private void captureSemantic(ArchiveDomain domain, byte[] canonicalKey, byte[] prevValue,
      byte[] value, boolean delete) {
    if (failure != null) {
      return;
    }
    Optional<ArchiveTxPosition> position = context.current();
    if (!position.isPresent()) {
      return;
    }
    ArchiveDomainDescriptor descriptor = catalog.descriptorFor(domain);
    if (descriptor == null) {
      throw missingDescriptor(domain);
    }
    reserveRawRecord(length(canonicalKey), length(prevValue), delete ? 0 : length(value));
    byte[] key = descriptor.getKeyCodec().normalize(canonicalKey);
    DomainValue prev = prevDomainValue(descriptor, prevValue);
    DomainValue domainValue = delete
        ? descriptor.getValueCodec().normalizeDelete()
        : descriptor.getValueCodec().normalizePut(value);
    append(new ArchiveChangeRecord(position.get(), domain, key, prev, domainValue));
  }

  /** Whether writes to {@code dbName} are archived; lets the Store path skip the prev-value read
   * (an extra get per write) for non-captured stores. Key-level allowlist nuance (a
   * DYNAMIC_PROPERTIES NO_ARCHIVE key) is intentionally ignored here -- an over-read of a
   * non-archived key is dropped in {@link #capture}, a wasted read but never a bug. */
  public boolean capturesStore(String dbName) {
    if (failure != null) {
      return false;
    }
    StoreBinding binding = registry.bindingForDbName(dbName);
    requireKnownStore(binding);
    if (!binding.getDomain().isPresent()) {
      return false;
    }
    switch (binding.getRawHookMode()) {
      case GENERIC_TRON_STORE:
      case STORE_SPECIFIC:
      case GENERIC_TRON_STORE_ALLOWLIST:
        return true;
      default:
        return false;
    }
  }

  /** Key-aware variant that avoids the prev-read for explicitly excluded allowlist rows. */
  public boolean capturesStore(String dbName, byte[] key) {
    if (failure != null) {
      return false;
    }
    StoreBinding binding = registry.bindingForDbName(dbName);
    requireKnownStore(binding);
    if (!binding.getDomain().isPresent()) {
      return false;
    }
    switch (binding.getRawHookMode()) {
      case GENERIC_TRON_STORE:
      case STORE_SPECIFIC:
        return true;
      case GENERIC_TRON_STORE_ALLOWLIST:
        return key != null
            && dynamicKeyPolicy.decision(key).getHistoryPolicy() != HistoryPolicy.NO_ARCHIVE;
      default:
        return false;
    }
  }

  public boolean hasCurrentPosition() {
    return context.current().isPresent();
  }

  public boolean hasActiveBlock() {
    return blockActive;
  }

  public void beginBlockCapture() {
    clear();
    blockActive = true;
  }

  private static DomainValue prevDomainValue(ArchiveDomainDescriptor descriptor, byte[] prevValue) {
    // A null prev means the key did not exist before this change -> tombstone (Erigon creation).
    return (prevValue == null)
        ? descriptor.getValueCodec().normalizeDelete()
        : descriptor.getValueCodec().normalizePut(prevValue);
  }

  /**
   * Derives ACCOUNT_ASSET (TRC10) records from an account write by value-diffing the old vs new
   * {@code assetV2} maps (decision 2). Only assetIds whose balance actually changed are emitted
   * (a balance == new value, or a tombstone when it drops to 0) -- value-diff, not map-presence, so
   * lazily-imported but unchanged assets are skipped. In the optimized regime the AccountStore hook
   * passes asset-imported old/new account bytes so stripped root-account rows do not masquerade as
   * tombstone-to-balance changes.
   */
  public void captureAccountAsset(byte[] addressKey, byte[] oldAccount, byte[] newAccount) {
    if (failure != null) {
      return;
    }
    Optional<ArchiveTxPosition> position = context.current();
    if (!position.isPresent()) {
      return;
    }
    Map<String, Long> oldAssets = assetV2(oldAccount);
    Map<String, Long> newAssets = assetV2(newAccount);
    if (oldAssets.isEmpty() && newAssets.isEmpty()) {
      return;
    }
    ArchiveDomainDescriptor descriptor = catalog.descriptorFor(ArchiveDomain.ACCOUNT_ASSET);
    if (descriptor == null) {
      throw missingDescriptor(ArchiveDomain.ACCOUNT_ASSET);
    }
    Set<String> assetIds = new TreeSet<>(); // sorted for deterministic capture order
    assetIds.addAll(oldAssets.keySet());
    assetIds.addAll(newAssets.keySet());
    for (String assetId : assetIds) {
      long oldBalance = oldAssets.getOrDefault(assetId, 0L);
      long newBalance = newAssets.getOrDefault(assetId, 0L);
      captureAccountAsset(addressKey, assetId.getBytes(StandardCharsets.US_ASCII),
          oldBalance, newBalance);
    }
  }

  /** Captures one effective TRC10 balance transition without materializing an Account proto. */
  public void captureAccountAsset(byte[] addressKey, byte[] assetId,
      long oldBalance, long newBalance) {
    if (failure != null) {
      return;
    }
    Optional<ArchiveTxPosition> position = context.current();
    if (!position.isPresent() || oldBalance == newBalance) {
      return;
    }
    ArchiveDomainDescriptor descriptor = catalog.descriptorFor(ArchiveDomain.ACCOUNT_ASSET);
    if (descriptor == null) {
      throw missingDescriptor(ArchiveDomain.ACCOUNT_ASSET);
    }
    reserveRawRecord(length(addressKey) + length(assetId), Long.BYTES, Long.BYTES);
    byte[] canonicalKey = descriptor.getKeyCodec().normalize(Bytes.concat(addressKey, assetId));
    DomainValue prev = oldBalance == 0
        ? descriptor.getValueCodec().normalizeDelete()
        : descriptor.getValueCodec().normalizePut(Longs.toByteArray(oldBalance));
    DomainValue value = newBalance == 0
        ? descriptor.getValueCodec().normalizeDelete()
        : descriptor.getValueCodec().normalizePut(Longs.toByteArray(newBalance));
    append(new ArchiveChangeRecord(
        position.get(), ArchiveDomain.ACCOUNT_ASSET, canonicalKey, prev, value));
  }

  private Map<String, Long> assetV2(byte[] accountBytes) {
    if (accountBytes == null || accountBytes.length == 0) {
      return Collections.emptyMap();
    }
    try {
      return Account.parseFrom(accountBytes).getAssetV2Map();
    } catch (InvalidProtocolBufferException e) {
      throw new ArchiveException("account-asset: value is not a valid Account proto", e);
    }
  }

  private void capture(String dbName, byte[] key, byte[] prevValue, byte[] value, boolean delete) {
    if (failure != null) {
      return;
    }
    Optional<ArchiveTxPosition> position = context.current();
    if (!position.isPresent()) {
      return; // outside block apply / archive disabled
    }
    StoreBinding binding = registry.bindingForDbName(dbName);
    requireKnownStore(binding);
    if (!isRawCaptured(binding, key)) {
      return;
    }
    ArchiveDomainDescriptor descriptor = catalog.descriptorFor(binding.getDomain().get());
    if (descriptor == null) {
      throw missingDescriptor(binding.getDomain().get());
    }
    reserveRawRecord(length(key), length(prevValue), delete ? 0 : length(value));
    byte[] canonicalKey = descriptor.getKeyCodec().normalize(key);
    DomainValue prev = prevDomainValue(descriptor, prevValue);
    DomainValue domainValue = delete
        ? descriptor.getValueCodec().normalizeDelete()
        : descriptor.getValueCodec().normalizePut(value);
    append(new ArchiveChangeRecord(position.get(), binding.getDomain().get(),
        canonicalKey, prev, domainValue));
  }

  /**
   * Whether a raw store write is captured. GENERIC and STORE_SPECIFIC domains are both raw-captured
   * (STORE_SPECIFIC stores bypass the base put and call from their own hook, so there is no
   * double-capture); ALLOWLIST domains capture per key policy; SEMANTIC_ONLY / IGNORE_RAW are
   * captured by semantic hooks (L4c), not here.
   */
  private boolean isRawCaptured(StoreBinding binding, byte[] key) {
    if (!binding.getDomain().isPresent()) {
      return false;
    }
    switch (binding.getRawHookMode()) {
      case GENERIC_TRON_STORE:
      case STORE_SPECIFIC:
        return true;
      case GENERIC_TRON_STORE_ALLOWLIST:
        // DYNAMIC_PROPERTIES: archive every key that keeps history; skip only NO_ARCHIVE keys
        // (migration markers / aggregate statistics). Unknown keys keep history by policy.
        DynamicKeyDecision decision = dynamicKeyPolicy.decision(key);
        return decision.getHistoryPolicy() != HistoryPolicy.NO_ARCHIVE;
      default:
        // SEMANTIC_ONLY / IGNORE_RAW are captured by semantic hooks, not the raw path.
        return false;
    }
  }

  private void requireKnownStore(StoreBinding binding) {
    if (!binding.isKnown() && (context.current().isPresent() || blockActive)) {
      throw new ArchiveException("archive store dbName is not classified: " + binding.getDbName());
    }
  }

  private static ArchiveException missingDescriptor(ArchiveDomain domain) {
    return new ArchiveException("archive catalog missing descriptor for captured domain: "
        + domain);
  }

  /** Captured records in capture order (append-only); L5 builds latest/history/changesets. */
  public List<ArchiveChangeRecord> records() {
    return Collections.unmodifiableList(new ArrayList<>(records.values()));
  }

  public int rawRecordCount() {
    return rawRecordCount;
  }

  public long rawRecordBytes() {
    return rawRecordBytes;
  }

  public long previousValueReads() {
    return previousValueReads;
  }

  public long previousValueReadFailures() {
    return previousValueReadFailures;
  }

  public long previousValueReadNanos() {
    return previousValueReadNanos;
  }

  public long accountAssetPrefixRows() {
    return accountAssetPrefixRows;
  }

  public long accountAssetPointReads() {
    return accountAssetPointReads;
  }

  public long accountAssetLookups() {
    return accountAssetLookups;
  }

  public long accountAssetLookupNanos() {
    return accountAssetLookupNanos;
  }

  /** Records raw timing only; the metrics reporter is invoked once when the block is committed. */
  public void recordPreviousValueRead(long startedNanos, boolean success) {
    if (success) {
      previousValueReads = incrementSaturated(previousValueReads);
    } else {
      previousValueReadFailures = incrementSaturated(previousValueReadFailures);
    }
    if (startedNanos != Long.MIN_VALUE) {
      long elapsed = Math.max(0L, System.nanoTime() - startedNanos);
      previousValueReadNanos = addSaturated(previousValueReadNanos, elapsed);
    }
  }

  /** Aggregates account-asset diff work for one block without reporter calls on Store writes. */
  public void recordAccountAssetLookup(boolean prefixScan, long physicalRowsRead,
      long startedNanos) {
    if (prefixScan) {
      accountAssetPrefixRows = addSaturated(accountAssetPrefixRows, physicalRowsRead);
    } else {
      accountAssetPointReads = addSaturated(accountAssetPointReads, physicalRowsRead);
    }
    accountAssetLookups = incrementSaturated(accountAssetLookups);
    if (startedNanos != Long.MIN_VALUE) {
      accountAssetLookupNanos = addSaturated(
          accountAssetLookupNanos, Math.max(0L, System.nanoTime() - startedNanos));
    }
  }

  public Optional<ArchiveException> failure() {
    return Optional.ofNullable(failure);
  }

  public void recordFailure(String operation, Exception cause) {
    if (failure == null) {
      failure = new ArchiveException("archive capture failed during " + operation, cause);
    }
  }

  public void clear() {
    records.clear();
    rawRecordCount = 0;
    rawRecordBytes = 0L;
    previousValueReads = 0L;
    previousValueReadFailures = 0L;
    previousValueReadNanos = 0L;
    accountAssetPrefixRows = 0L;
    accountAssetPointReads = 0L;
    accountAssetLookups = 0L;
    accountAssetLookupNanos = 0L;
    failure = null;
    blockActive = false;
  }

  private void append(ArchiveChangeRecord record) {
    ChangeKey key = new ChangeKey(record);
    ArchiveChangeRecord prior = records.get(key);
    if (prior == null) {
      records.put(key, record);
      return;
    }
    if (!prior.getValue().contentEquals(record.getPrevValue())) {
      if (failure == null) {
        failure = new ArchiveException("archive capture prev-value chain mismatch for txNum "
            + record.getTxNum());
      }
      return;
    }
    records.put(key, prior.withValue(record.getValue()));
  }

  private void reserveRawRecord(int keyBytes, int prevBytes, int valueBytes) {
    long estimated = 256L + keyBytes + prevBytes + valueBytes;
    long nextCount = (long) rawRecordCount + 1L;
    long nextBytes = rawRecordBytes > Long.MAX_VALUE - estimated
        ? Long.MAX_VALUE : rawRecordBytes + estimated;
    if (nextCount > maxRawRecords || nextBytes > maxRawBytes) {
      throw new ArchiveException("archive capture reached hard watermark: records="
          + nextCount + ", bytes=" + nextBytes);
    }
    rawRecordCount++;
    rawRecordBytes = nextBytes;
  }

  private static int length(byte[] value) {
    return value == null ? 0 : value.length;
  }

  private static long incrementSaturated(long value) {
    return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
  }

  private static long addSaturated(long left, long right) {
    return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
  }

  private static final class ChangeKey {

    private final long txNum;
    private final ArchiveDomain domain;
    private final byte[] canonicalKey;

    private ChangeKey(ArchiveChangeRecord record) {
      txNum = record.getTxNum();
      domain = record.getDomain();
      canonicalKey = record.canonicalKeyView();
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof ChangeKey)) {
        return false;
      }
      ChangeKey that = (ChangeKey) other;
      return txNum == that.txNum && domain == that.domain
          && java.util.Arrays.equals(canonicalKey, that.canonicalKey);
    }

    @Override
    public int hashCode() {
      int result = Long.hashCode(txNum);
      result = 31 * result + domain.hashCode();
      return 31 * result + java.util.Arrays.hashCode(canonicalKey);
    }
  }
}
