package org.tron.core.archive.domain;

import java.util.List;

/**
 * Catalog of {@link ArchiveDomainDescriptor}s: the per-domain codec + policy schema. Distinct from
 * {@link ArchiveDomainRegistry} (which maps store dbNames to bindings); the catalog maps captured
 * domains to how they are canonically encoded.
 */
public interface ArchiveDomainCatalog {

  /** Descriptor for a captured domain, or null if the domain is not in the catalog. */
  ArchiveDomainDescriptor descriptorFor(ArchiveDomain domain);

  /** All descriptors, sorted by domain id. */
  List<ArchiveDomainDescriptor> allDescriptors();

  /** Descriptors with {@link RootPolicy#IN_GLOBAL_ROOT}, sorted by domain id. */
  List<ArchiveDomainDescriptor> rootDescriptors();

  /** Deterministic 32-byte checksum over the catalog (domain ids, codec ids, policies). */
  byte[] checksum();
}
