package org.tron.core.archive.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A read-only snapshot of archive coverage: which domains are in the global root vs history-only,
 * which store bindings are captured / excluded / unknown, and the registry + catalog checksums.
 * Intended for startup diagnostics and a future {@code debug_getArchiveCoverage}.
 */
public final class ArchiveCoverage {

  private final List<ArchiveDomainDescriptor> rootDomains;
  private final List<ArchiveDomainDescriptor> historyDomains;
  private final List<StoreBinding> capturedBindings;
  private final List<StoreBinding> excludedBindings;
  private final List<StoreBinding> unknownBindings;
  private final byte[] registryChecksum;
  private final byte[] catalogChecksum;

  private ArchiveCoverage(List<ArchiveDomainDescriptor> rootDomains,
      List<ArchiveDomainDescriptor> historyDomains, List<StoreBinding> capturedBindings,
      List<StoreBinding> excludedBindings, List<StoreBinding> unknownBindings,
      byte[] registryChecksum, byte[] catalogChecksum) {
    this.rootDomains = Collections.unmodifiableList(rootDomains);
    this.historyDomains = Collections.unmodifiableList(historyDomains);
    this.capturedBindings = Collections.unmodifiableList(capturedBindings);
    this.excludedBindings = Collections.unmodifiableList(excludedBindings);
    this.unknownBindings = Collections.unmodifiableList(unknownBindings);
    this.registryChecksum = registryChecksum;
    this.catalogChecksum = catalogChecksum;
  }

  public static ArchiveCoverage of(ArchiveDomainRegistry registry, ArchiveDomainCatalog catalog) {
    List<ArchiveDomainDescriptor> root = catalog.rootDescriptors();
    List<ArchiveDomainDescriptor> history = new ArrayList<>();
    for (ArchiveDomainDescriptor d : catalog.allDescriptors()) {
      if (d.getRootPolicy() == RootPolicy.HISTORY_ONLY) {
        history.add(d);
      }
    }
    List<StoreBinding> captured = new ArrayList<>();
    List<StoreBinding> excluded = new ArrayList<>();
    List<StoreBinding> unknown = new ArrayList<>();
    for (StoreBinding b : registry.allStoreBindings()) {
      switch (b.getKind()) {
        case EXCLUDED:
          excluded.add(b);
          break;
        case UNKNOWN:
          unknown.add(b);
          break;
        default:
          captured.add(b);
      }
    }
    return new ArchiveCoverage(root, history, captured, excluded, unknown,
        registry.checksum(), catalog.checksum());
  }

  public List<ArchiveDomainDescriptor> getRootDomains() {
    return rootDomains;
  }

  public List<ArchiveDomainDescriptor> getHistoryDomains() {
    return historyDomains;
  }

  public List<StoreBinding> getCapturedBindings() {
    return capturedBindings;
  }

  public List<StoreBinding> getExcludedBindings() {
    return excludedBindings;
  }

  /** Store dbNames not classified by the registry; empty for a complete default registry. */
  public List<StoreBinding> getUnknownBindings() {
    return unknownBindings;
  }

  public byte[] getRegistryChecksum() {
    return Arrays.copyOf(registryChecksum, registryChecksum.length);
  }

  public byte[] getCatalogChecksum() {
    return Arrays.copyOf(catalogChecksum, catalogChecksum.length);
  }
}
