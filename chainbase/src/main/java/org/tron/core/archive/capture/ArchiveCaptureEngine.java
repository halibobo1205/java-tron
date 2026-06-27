package org.tron.core.archive.capture;

import com.google.common.primitives.Bytes;
import com.google.common.primitives.Longs;
import com.google.protobuf.InvalidProtocolBufferException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
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
 * store-specific / semantic / excluded / unknown stores are no-ops here. STORE_SPECIFIC/SEMANTIC
 * domains are captured by dedicated hooks (L4b/L4c), not this generic path.
 *
 * <p>Not thread-safe: the buffer is written only by the single block-apply thread (the only thread
 * with a non-empty execution context). {@link #capturePut}/{@link #captureDelete} may throw on
 * encode failure; the store-facing {@link ArchiveCaptureHolder} isolates those so block apply is
 * never affected.
 */
public final class ArchiveCaptureEngine {

  private final ArchiveDomainRegistry registry;
  private final ArchiveDomainCatalog catalog;
  private final DynamicKeyPolicy dynamicKeyPolicy;
  private final ArchiveExecutionContext context;
  private final List<ArchiveChangeRecord> records = new ArrayList<>();

  public ArchiveCaptureEngine(ArchiveDomainRegistry registry, ArchiveDomainCatalog catalog,
      DynamicKeyPolicy dynamicKeyPolicy, ArchiveExecutionContext context) {
    this.registry = registry;
    this.catalog = catalog;
    this.dynamicKeyPolicy = dynamicKeyPolicy;
    this.context = context;
  }

  public void capturePut(String dbName, byte[] key, byte[] value) {
    capture(dbName, key, value, false);
  }

  public void captureDelete(String dbName, byte[] key) {
    capture(dbName, key, null, true);
  }

  /**
   * Derives ACCOUNT_ASSET (TRC10) records from an account write by value-diffing the old vs new
   * {@code assetV2} maps (decision 2). Only assetIds whose balance actually changed are emitted
   * (a balance == new value, or a tombstone when it drops to 0) -- value-diff, not map-presence, so
   * lazily-imported but unchanged assets are skipped. Works in both asset_optimized regimes because
   * the account value written per-tx carries the post-mutation balances for the assets touched.
   */
  public void captureAccountAsset(byte[] addressKey, byte[] oldAccount, byte[] newAccount) {
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
      return;
    }
    Set<String> assetIds = new TreeSet<>(); // sorted for deterministic capture order
    assetIds.addAll(oldAssets.keySet());
    assetIds.addAll(newAssets.keySet());
    for (String assetId : assetIds) {
      long oldBalance = oldAssets.getOrDefault(assetId, 0L);
      long newBalance = newAssets.getOrDefault(assetId, 0L);
      if (oldBalance == newBalance) {
        continue;
      }
      byte[] canonicalKey = descriptor.getKeyCodec().normalize(
          Bytes.concat(addressKey, assetId.getBytes(StandardCharsets.US_ASCII)));
      DomainValue value = (newBalance == 0)
          ? descriptor.getValueCodec().normalizeDelete()
          : descriptor.getValueCodec().normalizePut(Longs.toByteArray(newBalance));
      records.add(new ArchiveChangeRecord(
          position.get(), ArchiveDomain.ACCOUNT_ASSET, canonicalKey, value));
    }
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

  private void capture(String dbName, byte[] key, byte[] value, boolean delete) {
    Optional<ArchiveTxPosition> position = context.current();
    if (!position.isPresent()) {
      return; // outside block apply / archive disabled
    }
    StoreBinding binding = registry.bindingForDbName(dbName);
    if (!isRawCaptured(binding, key)) {
      return;
    }
    ArchiveDomainDescriptor descriptor = catalog.descriptorFor(binding.getDomain().get());
    if (descriptor == null) {
      return; // captured binding with no descriptor: defensive, treat as not captured
    }
    byte[] canonicalKey = descriptor.getKeyCodec().normalize(key);
    DomainValue domainValue = delete
        ? descriptor.getValueCodec().normalizeDelete()
        : descriptor.getValueCodec().normalizePut(value);
    records.add(new ArchiveChangeRecord(position.get(), binding.getDomain().get(),
        canonicalKey, domainValue));
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

  /** Captured records in capture order (append-only); L5 builds latest/history/changesets. */
  public List<ArchiveChangeRecord> records() {
    return Collections.unmodifiableList(records);
  }

  public void clear() {
    records.clear();
  }
}
