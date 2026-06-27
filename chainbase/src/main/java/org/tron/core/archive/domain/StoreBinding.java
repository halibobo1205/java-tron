package org.tron.core.archive.domain;

import java.util.Optional;

/**
 * Immutable result of resolving a java-tron store dbName against the archive schema. An unknown
 * dbName is an explicit {@link StoreBindingKind#UNKNOWN} state (never silently "excluded"), so the
 * L4 collector can warn instead of dropping a write.
 */
public final class StoreBinding {

  private final String dbName;
  private final StoreBindingKind kind;
  private final ArchiveDomain domain;
  private final RawHookMode rawHookMode;
  private final RootPolicy rootPolicy;
  private final HistoryPolicy historyPolicy;
  private final String reason;

  public StoreBinding(String dbName, StoreBindingKind kind, ArchiveDomain domain,
      RawHookMode rawHookMode, RootPolicy rootPolicy, HistoryPolicy historyPolicy, String reason) {
    this.dbName = dbName;
    this.kind = kind;
    this.domain = domain;
    this.rawHookMode = rawHookMode;
    this.rootPolicy = rootPolicy;
    this.historyPolicy = historyPolicy;
    this.reason = reason;
  }

  /** Binding for a dbName not registered in the schema: ignore raw writes, excluded, no archive. */
  public static StoreBinding unknown(String dbName) {
    return new StoreBinding(dbName, StoreBindingKind.UNKNOWN, null,
        RawHookMode.IGNORE_RAW, RootPolicy.EXCLUDED, HistoryPolicy.NO_ARCHIVE,
        "dbName not registered in ArchiveDomainRegistry");
  }

  public boolean isKnown() {
    return kind != StoreBindingKind.UNKNOWN;
  }

  public String getDbName() {
    return dbName;
  }

  public StoreBindingKind getKind() {
    return kind;
  }

  public Optional<ArchiveDomain> getDomain() {
    return Optional.ofNullable(domain);
  }

  public RawHookMode getRawHookMode() {
    return rawHookMode;
  }

  public RootPolicy getRootPolicy() {
    return rootPolicy;
  }

  public HistoryPolicy getHistoryPolicy() {
    return historyPolicy;
  }

  public String getReason() {
    return reason;
  }
}
