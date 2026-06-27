package org.tron.core.archive.domain;

import java.util.List;

/**
 * Immutable archive schema: resolves java-tron store dbNames to their archive {@link StoreBinding}
 * (domain + policies), lists the root domains, and exposes a deterministic checksum. Downstream
 * modules (L4 collector, L5 store, L7 commitment) must consult this instead of hard-coding store
 * names. L3 ships the schema only; it does not capture writes or change runtime behaviour.
 */
public interface ArchiveDomainRegistry {

  /** Binding for a dbName; {@link StoreBinding#unknown} when the dbName is not registered. */
  StoreBinding bindingForDbName(String dbName);

  /** All registered store bindings, sorted by dbName. */
  List<StoreBinding> allStoreBindings();

  /** Domains with {@link RootPolicy#IN_GLOBAL_ROOT}, sorted by domain id (root aggregation order). */
  List<ArchiveDomain> rootDomains();

  /** Deterministic 32-byte checksum over the sorted schema (domain ids, bindings, policies). */
  byte[] checksum();
}
