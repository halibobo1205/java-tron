package org.tron.core.config.args;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigBeanFactory;
import com.typesafe.config.ConfigObject;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.tron.common.math.StrictMathWrapper;

/**
 * Storage configuration bean.
 * Field names match config.conf keys under the "storage" section.
 * Covers db, index, properties, dbSettings, backup, checkpoint, txCache, etc.
 */
@Slf4j
@Getter
@Setter
public class StorageConfig {

  private DbConfig db = new DbConfig();
  private TransHistoryConfig transHistory = new TransHistoryConfig();
  private boolean needToUpdateAsset = true;
  private DbSettingsConfig dbSettings = new DbSettingsConfig();
  private BalanceConfig balance = new BalanceConfig();
  private CheckpointConfig checkpoint = new CheckpointConfig();
  private SnapshotConfig snapshot = new SnapshotConfig();
  private TxCacheConfig txCache = new TxCacheConfig();
  private ArchiveConfig archive = new ArchiveConfig();
  // ConfigBeanFactory requires all bean fields present per item, so we parse manually.
  @Setter(lombok.AccessLevel.NONE)
  private List<PropertyConfig> properties = new ArrayList<>();

  // merkleRoot is a nested object (e.g. { reward-vi = "hash..." }) not a string.
  // Excluded from auto-binding, handled by Storage class directly.
  @Getter(lombok.AccessLevel.NONE)
  @Setter(lombok.AccessLevel.NONE)
  private Object merkleRoot;

  // Raw storage config sub-tree, kept for setCacheStrategies/setDbRoots which
  // have dynamic keys that ConfigBeanFactory cannot bind.
  @Setter(lombok.AccessLevel.NONE)
  private Config rawStorageConfig;

  // LevelDB per-database option overrides (default, defaultM, defaultL).
  // @Setter(NONE): optional keys commented out in reference.conf; ConfigBeanFactory
  // would throw if it required them. Values are assigned in fromConfig().
  @Setter(lombok.AccessLevel.NONE)
  private DbOptionOverride defaultDbOption;
  @Setter(lombok.AccessLevel.NONE)
  private DbOptionOverride defaultMDbOption;
  @Setter(lombok.AccessLevel.NONE)
  private DbOptionOverride defaultLDbOption;

  @Getter
  @Setter
  public static class DbConfig {

    private String engine = "LEVELDB";
    private boolean sync = false;
    private String directory = "database";
  }

  @Getter
  @Setter
  public static class TransHistoryConfig {

    // "switch" is a reserved Java keyword; ConfigBeanFactory calls setSwitch() which works fine
    @Getter(lombok.AccessLevel.NONE)
    @Setter(lombok.AccessLevel.NONE)
    private String switchValue = "on";

    public String getSwitch() {
      return switchValue;
    }

    public void setSwitch(String v) {
      this.switchValue = v;
    }
  }

  @Getter
  @Setter
  public static class DbSettingsConfig {

    private int levelNumber = 7;
    private int compactThreads = 0; // 0 = auto: max(availableProcessors, 1)
    private int blocksize = 16;
    private long maxBytesForLevelBase = 256;
    private double maxBytesForLevelMultiplier = 10;
    private int level0FileNumCompactionTrigger = 2;
    private long targetFileSizeBase = 64;
    private int targetFileSizeMultiplier = 1;
    private int maxOpenFiles = 5000;

    // Expand 0 → auto-detected processor count. Mirrors develop Args.java:1609-1611.
    void postProcess() {
      if (compactThreads == 0) {
        compactThreads = StrictMathWrapper.max(Runtime.getRuntime().availableProcessors(), 1);
      }
    }
  }

  @Getter
  @Setter
  public static class BalanceConfig {

    private HistoryConfig history = new HistoryConfig();

    @Getter
    @Setter
    public static class HistoryConfig {

      private boolean lookup = false;
    }
  }

  @Getter
  @Setter
  public static class CheckpointConfig {

    private int version = 1;
    private boolean sync = true;
  }

  @Getter
  @Setter
  public static class SnapshotConfig {

    private int maxFlushCount = 1;

    // Reject out-of-range values. Mirrors develop Storage.getSnapshotMaxFlushCountFromConfig.
    void postProcess() {
      if (maxFlushCount <= 0) {
        throw new IllegalArgumentException("MaxFlushCount value can not be negative or zero!");
      }
      if (maxFlushCount > 500) {
        throw new IllegalArgumentException("MaxFlushCount value must not exceed 500!");
      }
    }
  }

  @Getter
  @Setter
  public static class TxCacheConfig {

    private int estimatedTransactions = 1000;
    private boolean initOptimization = false;

    // Clamp to [100, 10000]. Mirrors develop Storage.getEstimatedTransactionsFromConfig.
    void postProcess() {
      if (estimatedTransactions > 10000) {
        estimatedTransactions = 10000;
      } else if (estimatedTransactions < 100) {
        estimatedTransactions = 100;
      }
    }
  }

  // Archive (transaction-level historical state) sidecar config. Default disabled = pure no-op.
  // Nested beans bind 1:1 via ConfigBeanFactory; keys/defaults mirror
  // reference.conf storage.archive.
  @Getter
  @Setter
  public static class ArchiveConfig {

    private static final String SUPPORTED_COVERAGE = "TVM_STATE_ONLY";

    private boolean enable = false;
    private DbConfig db = new DbConfig();
    private TxNumConfig txnum = new TxNumConfig();
    private TemporalConfig temporal = new TemporalConfig();
    private PublisherConfig publisher = new PublisherConfig();
    private QueryConfig query = new QueryConfig();
    private IdentityConfig identity = new IdentityConfig();
    private CommitmentConfig commitment = new CommitmentConfig();
    private DebugConfig debug = new DebugConfig();
    private String coverage = "TVM_STATE_ONLY";
    private boolean warnUnclassifiedStoreWrites = true;

    void postProcess() {
      if (db == null) {
        throw new IllegalArgumentException("storage.archive.db must not be null");
      }
      if (db.directory == null || db.directory.trim().isEmpty()) {
        throw new IllegalArgumentException("storage.archive.db.directory must not be empty");
      }
      if (coverage == null || coverage.trim().isEmpty()) {
        throw new IllegalArgumentException("storage.archive.coverage must not be empty");
      }
      coverage = coverage.trim();
      if (!SUPPORTED_COVERAGE.equals(coverage)) {
        throw new IllegalArgumentException(
            "storage.archive.coverage supports only " + SUPPORTED_COVERAGE + " in P0");
      }
      if (!warnUnclassifiedStoreWrites) {
        throw new IllegalArgumentException(
            "storage.archive.warnUnclassifiedStoreWrites cannot be false in P0");
      }
      if (enable && (txnum == null || !txnum.isEnable())) {
        throw new IllegalArgumentException("storage.archive.txnum.enable cannot be false in P0");
      }
      if (enable && (temporal == null || !temporal.isEnable())) {
        throw new IllegalArgumentException("storage.archive.temporal.enable cannot be false in P0");
      }
      if (publisher == null) {
        throw new IllegalArgumentException("storage.archive.publisher must not be null");
      }
      publisher.postProcess();
      if (query == null) {
        throw new IllegalArgumentException("storage.archive.query must not be null");
      }
      query.postProcess();
      if (identity == null) {
        throw new IllegalArgumentException("storage.archive.identity must not be null");
      }
      if (enable && commitment != null && commitment.isEnable()) {
        throw new IllegalArgumentException(
            "storage.archive.commitment.enable is not supported in P0");
      }
      if (enable && commitment != null && commitment.isPersistTxRoots()) {
        throw new IllegalArgumentException(
            "storage.archive.commitment.persistTxRoots cannot be true in P0");
      }
      if (enable && debug != null && debug.isEnable()) {
        throw new IllegalArgumentException("storage.archive.debug.enable is not supported in P0");
      }
    }

    @Getter
    @Setter
    public static class DbConfig {

      private String directory = "archive";
      private boolean fullScrubOnStartup;
    }

    /** One-time opt-in for creating or resuming the canonical/archive ACTIVE identity pair. */
    @Getter
    @Setter
    public static class IdentityConfig {

      private boolean initialize;
    }

    @Getter
    @Setter
    public static class TxNumConfig {

      private boolean enable = true;
    }

    @Getter
    @Setter
    public static class TemporalConfig {

      private boolean enable = true;
    }

    /** Runtime solidified-journal publisher. Async is opt-in until the soak gate is complete. */
    @Getter
    @Setter
    public static class PublisherConfig {

      private boolean async;
      private boolean backpressure = true;
      private int softInFlightBlocks = 32_768;
      private int hardInFlightBlocks = 65_536;
      private long softInFlightBytes = 128L * 1024 * 1024;
      private long hardInFlightBytes = 256L * 1024 * 1024;
      private long softInFlightRecords = 1_000_000L;
      private long hardInFlightRecords = 2_000_000L;
      private long softMinFreeBytes = 5L * 1024 * 1024 * 1024;
      private long hardMinFreeBytes = 1L * 1024 * 1024 * 1024;
      private long backpressureTimeoutMs = 30_000L;

      void postProcess() {
        if (softInFlightBlocks <= 0) {
          throw new IllegalArgumentException(
              "storage.archive.publisher.softInFlightBlocks must be positive");
        }
        if (hardInFlightBlocks < softInFlightBlocks) {
          throw new IllegalArgumentException(
              "storage.archive.publisher.hardInFlightBlocks must be greater than or equal to "
                  + "softInFlightBlocks");
        }
        if (softInFlightBytes <= 0) {
          throw new IllegalArgumentException(
              "storage.archive.publisher.softInFlightBytes must be positive");
        }
        if (hardInFlightBytes < softInFlightBytes) {
          throw new IllegalArgumentException(
              "storage.archive.publisher.hardInFlightBytes must be greater than or equal to "
                  + "softInFlightBytes");
        }
        if (softInFlightRecords <= 0) {
          throw new IllegalArgumentException(
              "storage.archive.publisher.softInFlightRecords must be positive");
        }
        if (hardInFlightRecords < softInFlightRecords) {
          throw new IllegalArgumentException(
              "storage.archive.publisher.hardInFlightRecords must be greater than or equal to "
                  + "softInFlightRecords");
        }
        if (hardMinFreeBytes < 0) {
          throw new IllegalArgumentException(
              "storage.archive.publisher.hardMinFreeBytes must be non-negative");
        }
        if (softMinFreeBytes < hardMinFreeBytes) {
          throw new IllegalArgumentException(
              "storage.archive.publisher.softMinFreeBytes must be greater than or equal to "
                  + "hardMinFreeBytes");
        }
        if (backpressureTimeoutMs < 0) {
          throw new IllegalArgumentException(
              "storage.archive.publisher.backpressureTimeoutMs must be non-negative");
        }
      }
    }

    /** Historical-query admission and per-request budgets. -1 is the unlimited sentinel. */
    @Getter
    @Setter
    public static class QueryConfig {

      private static final long UNLIMITED = -1L;
      private static final long DEFAULT_MAX_CONCURRENT_QUERIES = 8L;
      private static final long DEFAULT_MAX_PENDING_QUERIES = 16L;
      private static final long DEFAULT_MAX_OPEN_SNAPSHOTS = 8L;
      private static final long DEFAULT_ACQUIRE_TIMEOUT_MS = 0L;
      private static final long DEFAULT_DEADLINE_MS = 30_000L;
      private static final long DEFAULT_MAX_QUERIES_PER_BATCH = 8L;
      private static final long DEFAULT_BATCH_DEADLINE_MS = 30_000L;
      private static final long DEFAULT_MAX_LOGICAL_READS = 1_000_000L;
      private static final long DEFAULT_MAX_BACKEND_READS = 100_000L;
      private static final long DEFAULT_MAX_TRACE_STEPS = 1_000_000L;
      private static final long DEFAULT_MAX_TRACE_BYTES = 64L * 1024 * 1024;
      private static final long DEFAULT_MAX_TRACE_RESPONSE_BYTES = 24L * 1024 * 1024;

      private long maxConcurrentQueries = DEFAULT_MAX_CONCURRENT_QUERIES;
      private long maxPendingQueries = DEFAULT_MAX_PENDING_QUERIES;
      private long maxOpenSnapshots = DEFAULT_MAX_OPEN_SNAPSHOTS;
      private long acquireTimeoutMs = DEFAULT_ACQUIRE_TIMEOUT_MS;
      private long deadlineMs = DEFAULT_DEADLINE_MS;
      private long maxQueriesPerBatch = DEFAULT_MAX_QUERIES_PER_BATCH;
      private long batchDeadlineMs = DEFAULT_BATCH_DEADLINE_MS;
      private long maxLogicalReadsPerRequest = DEFAULT_MAX_LOGICAL_READS;
      private long maxBackendReadsPerRequest = DEFAULT_MAX_BACKEND_READS;
      private int maxCachedEntries = 4_096;
      private long maxCachedBytes = 4L * 1024 * 1024;
      private long maxTraceSteps = DEFAULT_MAX_TRACE_STEPS;
      private long maxTraceBytes = DEFAULT_MAX_TRACE_BYTES;
      private long maxTraceResponseBytes = DEFAULT_MAX_TRACE_RESPONSE_BYTES;

      void postProcess() {
        requirePositiveOrUnlimited("maxConcurrentQueries", maxConcurrentQueries);
        requireNonNegativeOrUnlimited("maxPendingQueries", maxPendingQueries);
        requirePositiveOrUnlimited("maxOpenSnapshots", maxOpenSnapshots);
        requireNonNegativeOrUnlimited("acquireTimeoutMs", acquireTimeoutMs);
        requirePositiveOrUnlimited("deadlineMs", deadlineMs);
        requirePositiveOrUnlimited("maxQueriesPerBatch", maxQueriesPerBatch);
        requirePositiveOrUnlimited("batchDeadlineMs", batchDeadlineMs);
        requirePositiveOrUnlimited(
            "maxLogicalReadsPerRequest", maxLogicalReadsPerRequest);
        requirePositiveOrUnlimited(
            "maxBackendReadsPerRequest", maxBackendReadsPerRequest);
        requireNonNegative("maxCachedEntries", maxCachedEntries);
        requireNonNegative("maxCachedBytes", maxCachedBytes);
        requireNonNegativeOrUnlimited("maxTraceSteps", maxTraceSteps);
        requireNonNegativeOrUnlimited("maxTraceBytes", maxTraceBytes);
        requireNonNegativeOrUnlimited("maxTraceResponseBytes", maxTraceResponseBytes);
      }

      private static void requirePositiveOrUnlimited(String key, long value) {
        if (value != UNLIMITED && value <= 0) {
          throw invalidLimit(key, "must be positive or -1");
        }
      }

      private static void requireNonNegativeOrUnlimited(String key, long value) {
        if (value < 0 && value != UNLIMITED) {
          throw invalidLimit(key, "must be non-negative or -1");
        }
      }

      private static void requireNonNegative(String key, long value) {
        if (value < 0) {
          throw invalidLimit(key, "must be non-negative");
        }
      }

      private static IllegalArgumentException invalidLimit(String key, String requirement) {
        return new IllegalArgumentException(
            "storage.archive.query." + key + " " + requirement);
      }
    }

    @Getter
    @Setter
    public static class CommitmentConfig {

      private boolean enable = false;
      private boolean persistTxRoots = false;
    }

    @Getter
    @Setter
    public static class DebugConfig {

      private boolean enable = false;
    }
  }

  // A named database entry: name/path plus the optional LevelDB option overrides
  // inherited from DbOptionOverride (boxed types, null = "inherit per-tier defaults").
  @Getter
  @Setter
  public static class PropertyConfig extends DbOptionOverride {

    private String name = "";
    private String path = "";
  }

  // Defaults come from reference.conf (loaded globally via Configuration.java)

  public static StorageConfig fromConfig(Config config) {
    Config section = config.getConfig("storage");
    validateArchiveConfigKeys(section);

    StorageConfig sc = ConfigBeanFactory.create(section, StorageConfig.class);
    sc.rawStorageConfig = section;
    sc.properties = readProperties(section);

    // Read optional LevelDB option overrides (default, defaultM, defaultL).
    sc.defaultDbOption = readDbOption(section, "default");
    sc.defaultMDbOption = readDbOption(section, "defaultM");
    sc.defaultLDbOption = readDbOption(section, "defaultL");

    sc.dbSettings.postProcess();
    sc.snapshot.postProcess();
    sc.txCache.postProcess();
    sc.archive.postProcess();
    return sc;
  }

  private static void validateArchiveConfigKeys(Config section) {
    if (!section.hasPath("archive")) {
      return;
    }
    Config archive = section.getConfig("archive");
    requireOnlyKeys("storage.archive", archive.root(), "enable", "db", "txnum", "temporal",
        "publisher", "query", "identity", "commitment", "debug", "coverage",
        "warnUnclassifiedStoreWrites");
    if (archive.hasPath("db")) {
      requireOnlyKeys("storage.archive.db", archive.getConfig("db").root(), "directory",
          "fullScrubOnStartup");
    }
    if (archive.hasPath("txnum")) {
      requireOnlyKeys("storage.archive.txnum", archive.getConfig("txnum").root(), "enable");
    }
    if (archive.hasPath("temporal")) {
      requireOnlyKeys("storage.archive.temporal", archive.getConfig("temporal").root(), "enable");
    }
    if (archive.hasPath("publisher")) {
      requireOnlyKeys("storage.archive.publisher", archive.getConfig("publisher").root(),
          "async", "backpressure", "softInFlightBlocks", "hardInFlightBlocks",
          "softInFlightBytes", "hardInFlightBytes", "softInFlightRecords",
          "hardInFlightRecords", "softMinFreeBytes", "hardMinFreeBytes",
          "backpressureTimeoutMs");
    }
    if (archive.hasPath("query")) {
      requireOnlyKeys("storage.archive.query", archive.getConfig("query").root(),
          "maxConcurrentQueries", "maxPendingQueries", "acquireTimeoutMs", "deadlineMs",
          "maxQueriesPerBatch", "batchDeadlineMs",
          "maxOpenSnapshots", "maxLogicalReadsPerRequest", "maxBackendReadsPerRequest",
          "maxCachedEntries", "maxCachedBytes", "maxTraceSteps", "maxTraceBytes",
          "maxTraceResponseBytes");
    }
    if (archive.hasPath("identity")) {
      requireOnlyKeys("storage.archive.identity", archive.getConfig("identity").root(),
          "initialize");
    }
    if (archive.hasPath("commitment")) {
      requireOnlyKeys("storage.archive.commitment", archive.getConfig("commitment").root(),
          "enable", "persistTxRoots");
    }
    if (archive.hasPath("debug")) {
      requireOnlyKeys("storage.archive.debug", archive.getConfig("debug").root(), "enable");
    }
  }

  private static void requireOnlyKeys(String path, ConfigObject object, String... allowedKeys) {
    for (String key : object.keySet()) {
      boolean allowed = false;
      for (String allowedKey : allowedKeys) {
        if (allowedKey.equals(key)) {
          allowed = true;
          break;
        }
      }
      if (!allowed) {
        throw new IllegalArgumentException(path + "." + key + " is not supported");
      }
    }
  }

  // Partial LevelDB option override for default/defaultM/defaultL.
  // Uses boxed types so null means "not set by user, keep existing value".
  @Getter
  @Setter
  public static class DbOptionOverride {

    private Integer blockSize;
    private Integer writeBufferSize;
    private Long cacheSize;
    private Integer maxOpenFiles;
  }

  // Shared LevelDB option parser used by both readDbOption and readProperties.
  // Fills the given target (boxed fields, null means "not specified by user") so the
  // same parser can populate a plain DbOptionOverride or a PropertyConfig (which extends it).
  private static void readLevelDbOptions(ConfigObject conf, DbOptionOverride o) {
    if (conf.containsKey("blockSize")) {
      String param = conf.get("blockSize").unwrapped().toString();
      try {
        o.setBlockSize(Integer.parseInt(param));
      } catch (NumberFormatException e) {
        throwIllegalArgumentException("blockSize", Integer.class, param);
      }
    }
    if (conf.containsKey("writeBufferSize")) {
      String param = conf.get("writeBufferSize").unwrapped().toString();
      try {
        o.setWriteBufferSize(Integer.parseInt(param));
      } catch (NumberFormatException e) {
        throwIllegalArgumentException("writeBufferSize", Integer.class, param);
      }
    }
    if (conf.containsKey("cacheSize")) {
      String param = conf.get("cacheSize").unwrapped().toString();
      try {
        o.setCacheSize(Long.parseLong(param));
      } catch (NumberFormatException e) {
        throwIllegalArgumentException("cacheSize", Long.class, param);
      }
    }
    if (conf.containsKey("maxOpenFiles")) {
      String param = conf.get("maxOpenFiles").unwrapped().toString();
      try {
        o.setMaxOpenFiles(Integer.parseInt(param));
      } catch (NumberFormatException e) {
        throwIllegalArgumentException("maxOpenFiles", Integer.class, param);
      }
    }
  }

  // Read optional LevelDB option override for default/defaultM/defaultL keys.
  private static DbOptionOverride readDbOption(Config section, String key) {
    if (!section.hasPath(key)) {
      return null;
    }
    DbOptionOverride o = new DbOptionOverride();
    readLevelDbOptions(section.getObject(key), o);
    return o;
  }

  // Parse storage.properties list manually: ConfigBeanFactory requires every bean field to be
  // present in each list item, but name+path-only entries (all LevelDB opts commented out) are
  // valid — missing fields fall back to PropertyConfig Java defaults.
  private static List<PropertyConfig> readProperties(Config section) {
    if (!section.hasPath("properties")) {
      return new ArrayList<>();
    }
    List<? extends ConfigObject> items = section.getObjectList("properties");
    List<PropertyConfig> result = new ArrayList<>(items.size());
    for (ConfigObject obj : items) {
      PropertyConfig p = new PropertyConfig();
      if (obj.containsKey("name")) {
        p.setName(obj.get("name").unwrapped().toString());
      }
      if (obj.containsKey("path")) {
        p.setPath(obj.get("path").unwrapped().toString());
      }
      // Boxed nullable fields: unset options stay null so they inherit the per-tier
      // defaults applied by newDefaultDbOptions instead of resetting them.
      readLevelDbOptions(obj, p);
      result.add(p);
    }
    return result;
  }

  private static void throwIllegalArgumentException(String param, Class<?> type, String actual) {
    throw new IllegalArgumentException(
        String.format("[storage.properties] %s must be %s type, actual: %s.",
            param, type.getSimpleName(), actual));
  }
}
