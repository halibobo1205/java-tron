package org.tron.core.archive.capture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.tron.core.archive.ArchiveExecutionContext;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomainCatalog;
import org.tron.core.archive.domain.ArchiveDomainDescriptor;
import org.tron.core.archive.domain.ArchiveDomainRegistry;
import org.tron.core.archive.domain.DynamicKeyDecision;
import org.tron.core.archive.domain.DynamicKeyPolicy;
import org.tron.core.archive.domain.HistoryPolicy;
import org.tron.core.archive.domain.StoreBinding;
import org.tron.core.archive.txnum.ArchiveTxPosition;

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

  private void capture(String dbName, byte[] key, byte[] value, boolean delete) {
    Optional<ArchiveTxPosition> position = context.current();
    if (!position.isPresent()) {
      return; // outside block apply / archive disabled
    }
    StoreBinding binding = registry.bindingForDbName(dbName);
    if (!isGenericCaptured(binding, key)) {
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

  private boolean isGenericCaptured(StoreBinding binding, byte[] key) {
    if (!binding.getDomain().isPresent()) {
      return false;
    }
    switch (binding.getRawHookMode()) {
      case GENERIC_TRON_STORE:
        return true;
      case GENERIC_TRON_STORE_ALLOWLIST:
        // DYNAMIC_PROPERTIES: archive every key that keeps history; skip only NO_ARCHIVE keys
        // (migration markers / aggregate statistics). Unknown keys keep history by policy.
        DynamicKeyDecision decision = dynamicKeyPolicy.decision(key);
        return decision.getHistoryPolicy() != HistoryPolicy.NO_ARCHIVE;
      default:
        // STORE_SPECIFIC / SEMANTIC_ONLY / IGNORE_RAW are captured by dedicated hooks.
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
