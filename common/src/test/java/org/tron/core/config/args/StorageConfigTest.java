package org.tron.core.config.args;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import java.util.List;
import org.junit.Test;
import org.tron.common.math.StrictMathWrapper;
import org.tron.core.config.args.StorageConfig.PropertyConfig;

public class StorageConfigTest {

  private static Config withRef(String hocon) {
    return ConfigFactory.parseString(hocon).withFallback(ConfigFactory.defaultReference());
  }

  private static Config withRef() {
    return ConfigFactory.defaultReference();
  }

  @Test
  public void testDefaults() {
    Config empty = withRef();
    StorageConfig sc = StorageConfig.fromConfig(empty);
    assertEquals("LEVELDB", sc.getDb().getEngine());
    assertFalse(sc.getDb().isSync());
    assertEquals("database", sc.getDb().getDirectory());
    assertTrue(sc.isNeedToUpdateAsset());
    assertEquals(7, sc.getDbSettings().getLevelNumber());
    assertEquals(5000, sc.getDbSettings().getMaxOpenFiles());
  }

  @Test
  public void testFromConfig() {
    Config config = withRef(
        "storage { db { engine = ROCKSDB, sync = true, directory = mydb },"
            + " backup { enable = true, frequency = 5000 },"
            + " dbSettings { levelNumber = 5, maxOpenFiles = 3000 } }");
    StorageConfig sc = StorageConfig.fromConfig(config);
    assertEquals("ROCKSDB", sc.getDb().getEngine());
    assertTrue(sc.getDb().isSync());
    assertEquals("mydb", sc.getDb().getDirectory());
    assertEquals(5, sc.getDbSettings().getLevelNumber());
    assertEquals(3000, sc.getDbSettings().getMaxOpenFiles());
  }

  @Test
  public void testCheckpointDefaults() {
    Config empty = withRef();
    StorageConfig sc = StorageConfig.fromConfig(empty);
    assertEquals(1, sc.getCheckpoint().getVersion());
    assertTrue(sc.getCheckpoint().isSync());
  }

  @Test
  public void testDbSettingsDefaults() {
    // These defaults must match develop's Args.initRocksDbSettings() fallbacks so that
    // nodes with minimal configs retain the same RocksDB tuning. See
    // docs/plans/2026-04-21-001-fix-reference-conf-default-drift.md.
    Config empty = withRef();
    StorageConfig sc = StorageConfig.fromConfig(empty);
    StorageConfig.DbSettingsConfig ds = sc.getDbSettings();
    assertEquals(7, ds.getLevelNumber());
    // compactThreads default is 0 in reference.conf, auto-expanded by postProcess()
    assertEquals(StrictMathWrapper.max(Runtime.getRuntime().availableProcessors(), 1),
        ds.getCompactThreads());
    assertEquals(16, ds.getBlocksize());
    assertEquals(256, ds.getMaxBytesForLevelBase());
    assertEquals(10, ds.getMaxBytesForLevelMultiplier(), 0.01);
    assertEquals(2, ds.getLevel0FileNumCompactionTrigger());
    assertEquals(64, ds.getTargetFileSizeBase());
    assertEquals(1, ds.getTargetFileSizeMultiplier());
    assertEquals(5000, ds.getMaxOpenFiles());
  }

  @Test
  public void testCompactThreadsAutoExpand() {
    // compactThreads = 0 must be auto-expanded to availableProcessors (min 1)
    Config config = withRef("storage { dbSettings { compactThreads = 0 } }");
    StorageConfig sc = StorageConfig.fromConfig(config);
    assertEquals(StrictMathWrapper.max(Runtime.getRuntime().availableProcessors(), 1),
        sc.getDbSettings().getCompactThreads());
  }

  @Test
  public void testCompactThreadsExplicitPreserved() {
    // Non-zero compactThreads must be passed through untouched
    Config config = withRef("storage { dbSettings { compactThreads = 7 } }");
    StorageConfig sc = StorageConfig.fromConfig(config);
    assertEquals(7, sc.getDbSettings().getCompactThreads());
  }

  @Test
  public void testBalanceHistoryLookup() {
    Config config = withRef(
        "storage { balance { history { lookup = true } } }");
    StorageConfig sc = StorageConfig.fromConfig(config);
    assertTrue(sc.getBalance().getHistory().isLookup());
  }

  @Test
  public void testSnapshotMaxFlushCountZeroRejected() {
    assertThrows(IllegalArgumentException.class,
        () -> StorageConfig.fromConfig(withRef("storage.snapshot.maxFlushCount = 0")));
  }

  @Test
  public void testSnapshotMaxFlushCountNegativeRejected() {
    assertThrows(IllegalArgumentException.class,
        () -> StorageConfig.fromConfig(withRef("storage.snapshot.maxFlushCount = -1")));
  }

  @Test
  public void testSnapshotMaxFlushCountOver500Rejected() {
    assertThrows(IllegalArgumentException.class,
        () -> StorageConfig.fromConfig(withRef("storage.snapshot.maxFlushCount = 501")));
  }

  @Test
  public void testTxCacheEstimatedClampedBelowMin() {
    StorageConfig sc = StorageConfig.fromConfig(
        withRef("storage.txCache.estimatedTransactions = 50"));
    assertEquals(100, sc.getTxCache().getEstimatedTransactions());
  }

  @Test
  public void testTxCacheEstimatedClampedAboveMax() {
    StorageConfig sc = StorageConfig.fromConfig(
        withRef("storage.txCache.estimatedTransactions = 99999"));
    assertEquals(10000, sc.getTxCache().getEstimatedTransactions());
  }

  @Test
  public void testTxCacheEstimatedWithinRangePreserved() {
    StorageConfig sc = StorageConfig.fromConfig(
        withRef("storage.txCache.estimatedTransactions = 5000"));
    assertEquals(5000, sc.getTxCache().getEstimatedTransactions());
  }

  // ---- archive ----

  @Test
  public void testArchiveDefaults() {
    StorageConfig.ArchiveConfig a = StorageConfig.fromConfig(withRef()).getArchive();
    assertFalse(a.isEnable());
    assertEquals("archive", a.getDb().getDirectory());
    assertFalse(a.getDb().isFullScrubOnStartup());
    assertTrue(a.getTxnum().isEnable());
    assertTrue(a.getTemporal().isEnable());
    StorageConfig.ArchiveConfig.PublisherConfig publisher = a.getPublisher();
    assertFalse(publisher.isAsync());
    assertTrue(publisher.isBackpressure());
    assertEquals(32_768, publisher.getSoftInFlightBlocks());
    assertEquals(65_536, publisher.getHardInFlightBlocks());
    assertEquals(128L * 1024 * 1024, publisher.getSoftInFlightBytes());
    assertEquals(256L * 1024 * 1024, publisher.getHardInFlightBytes());
    assertEquals(1_000_000, publisher.getSoftInFlightRecords());
    assertEquals(2_000_000, publisher.getHardInFlightRecords());
    assertEquals(5L * 1024 * 1024 * 1024, publisher.getSoftMinFreeBytes());
    assertEquals(1L * 1024 * 1024 * 1024, publisher.getHardMinFreeBytes());
    assertEquals(30_000, publisher.getBackpressureTimeoutMs());
    StorageConfig.ArchiveConfig.QueryConfig query = a.getQuery();
    assertEquals(8, query.getMaxConcurrentQueries());
    assertEquals(16, query.getMaxPendingQueries());
    assertEquals(8, query.getMaxOpenSnapshots());
    assertEquals(0, query.getAcquireTimeoutMs());
    assertEquals(30_000, query.getDeadlineMs());
    assertEquals(1_000_000, query.getMaxLogicalReadsPerRequest());
    assertEquals(100_000, query.getMaxBackendReadsPerRequest());
    assertEquals(4_096, query.getMaxCachedEntries());
    assertEquals(4L * 1024 * 1024, query.getMaxCachedBytes());
    assertEquals(1_000_000, query.getMaxTraceSteps());
    assertEquals(64L * 1024 * 1024, query.getMaxTraceBytes());
    assertEquals(256L * 1024 * 1024, query.getMaxRetainedTraceBytes());
    assertEquals(24L * 1024 * 1024, query.getMaxTraceResponseBytes());
    assertFalse(a.getIdentity().isInitialize());
    assertFalse(a.getCommitment().isEnable());
    assertFalse(a.getCommitment().isPersistTxRoots());
    assertFalse(a.getDebug().isEnable());
    assertEquals("TVM_STATE_ONLY", a.getCoverage());
    assertTrue(a.isWarnUnclassifiedStoreWrites());
  }

  @Test
  public void testArchiveOverride() {
    StorageConfig.ArchiveConfig a = StorageConfig.fromConfig(withRef(
        "storage.archive { enable = true,"
            + " db { directory = arc, fullScrubOnStartup = true },"
            + " txnum { enable = true },"
            + " temporal { enable = true },"
            + " commitment { enable = false, persistTxRoots = false },"
            + " debug { enable = false }, coverage = TVM_STATE_ONLY }"))
        .getArchive();
    assertTrue(a.isEnable());
    assertEquals("arc", a.getDb().getDirectory());
    assertTrue(a.getDb().isFullScrubOnStartup());
    assertTrue(a.getTxnum().isEnable());
    assertTrue(a.getTemporal().isEnable());
    assertFalse(a.getCommitment().isEnable());
    assertFalse(a.getCommitment().isPersistTxRoots());
    assertFalse(a.getDebug().isEnable());
    assertEquals("TVM_STATE_ONLY", a.getCoverage());
    assertTrue(a.isWarnUnclassifiedStoreWrites());
  }

  @Test
  public void testArchiveQueryLimitsOverride() {
    StorageConfig.ArchiveConfig.QueryConfig query = StorageConfig.fromConfig(withRef(
        "storage.archive.query { maxConcurrentQueries = 1, maxPendingQueries = 2,"
            + " maxOpenSnapshots = 12,"
            + " acquireTimeoutMs = 3, deadlineMs = 4, maxQueriesPerBatch = 13,"
            + " batchDeadlineMs = 14, maxLogicalReadsPerRequest = 5,"
            + " maxBackendReadsPerRequest = 6, maxCachedEntries = 7, maxCachedBytes = 8,"
            + " maxTraceSteps = 9, maxTraceBytes = 10, maxRetainedTraceBytes = 11,"
            + " maxTraceResponseBytes = 12 }"))
        .getArchive().getQuery();

    assertEquals(1, query.getMaxConcurrentQueries());
    assertEquals(2, query.getMaxPendingQueries());
    assertEquals(12, query.getMaxOpenSnapshots());
    assertEquals(3, query.getAcquireTimeoutMs());
    assertEquals(4, query.getDeadlineMs());
    assertEquals(13, query.getMaxQueriesPerBatch());
    assertEquals(14, query.getBatchDeadlineMs());
    assertEquals(5, query.getMaxLogicalReadsPerRequest());
    assertEquals(6, query.getMaxBackendReadsPerRequest());
    assertEquals(7, query.getMaxCachedEntries());
    assertEquals(8, query.getMaxCachedBytes());
    assertEquals(9, query.getMaxTraceSteps());
    assertEquals(10, query.getMaxTraceBytes());
    assertEquals(11, query.getMaxRetainedTraceBytes());
    assertEquals(12, query.getMaxTraceResponseBytes());
  }

  @Test
  public void testArchivePublisherOverride() {
    StorageConfig.ArchiveConfig.PublisherConfig publisher = StorageConfig.fromConfig(withRef(
        "storage.archive.publisher { async = true, backpressure = false,"
            + " softInFlightBlocks = 2, hardInFlightBlocks = 3,"
            + " softInFlightBytes = 4, hardInFlightBytes = 5,"
            + " softInFlightRecords = 6, hardInFlightRecords = 7,"
            + " softMinFreeBytes = 9, hardMinFreeBytes = 8,"
            + " backpressureTimeoutMs = 10 }"))
        .getArchive().getPublisher();

    assertTrue(publisher.isAsync());
    assertFalse(publisher.isBackpressure());
    assertEquals(2, publisher.getSoftInFlightBlocks());
    assertEquals(3, publisher.getHardInFlightBlocks());
    assertEquals(4, publisher.getSoftInFlightBytes());
    assertEquals(5, publisher.getHardInFlightBytes());
    assertEquals(6, publisher.getSoftInFlightRecords());
    assertEquals(7, publisher.getHardInFlightRecords());
    assertEquals(9, publisher.getSoftMinFreeBytes());
    assertEquals(8, publisher.getHardMinFreeBytes());
    assertEquals(10, publisher.getBackpressureTimeoutMs());
  }

  @Test
  public void testArchivePublisherRejectsInvalidWatermarks() {
    assertThrows(IllegalArgumentException.class,
        () -> StorageConfig.fromConfig(withRef(
            "storage.archive.publisher.softInFlightBlocks = 0")));
    assertThrows(IllegalArgumentException.class,
        () -> StorageConfig.fromConfig(withRef(
            "storage.archive.publisher { softInFlightBlocks = 4, hardInFlightBlocks = 3 }")));
    assertThrows(IllegalArgumentException.class,
        () -> StorageConfig.fromConfig(withRef(
            "storage.archive.publisher.softInFlightBytes = 0")));
    assertThrows(IllegalArgumentException.class,
        () -> StorageConfig.fromConfig(withRef(
            "storage.archive.publisher { softInFlightBytes = 4, hardInFlightBytes = 3 }")));
    assertThrows(IllegalArgumentException.class,
        () -> StorageConfig.fromConfig(withRef(
            "storage.archive.publisher.softInFlightRecords = 0")));
    assertThrows(IllegalArgumentException.class,
        () -> StorageConfig.fromConfig(withRef(
            "storage.archive.publisher { softInFlightRecords = 4, hardInFlightRecords = 3 }")));
    assertThrows(IllegalArgumentException.class,
        () -> StorageConfig.fromConfig(withRef(
            "storage.archive.publisher.hardMinFreeBytes = -1")));
    assertThrows(IllegalArgumentException.class,
        () -> StorageConfig.fromConfig(withRef(
            "storage.archive.publisher { softMinFreeBytes = 3, hardMinFreeBytes = 4 }")));
    assertThrows(IllegalArgumentException.class,
        () -> StorageConfig.fromConfig(withRef(
            "storage.archive.publisher.backpressureTimeoutMs = -1")));
  }

  @Test
  public void testArchiveQueryAcceptsZeroForIntentionalDisableOrFailFastLimits() {
    StorageConfig.ArchiveConfig.QueryConfig query = StorageConfig.fromConfig(withRef(
        "storage.archive.query { maxConcurrentQueries = 1, maxPendingQueries = 0,"
            + " maxOpenSnapshots = 1,"
            + " acquireTimeoutMs = 0, deadlineMs = 1, maxLogicalReadsPerRequest = 1,"
            + " maxBackendReadsPerRequest = 1, maxCachedEntries = 0, maxCachedBytes = 0,"
            + " maxTraceSteps = 0, maxTraceBytes = 0, maxRetainedTraceBytes = 0,"
            + " maxTraceResponseBytes = 0 }"))
        .getArchive().getQuery();

    assertEquals(1, query.getMaxConcurrentQueries());
    assertEquals(0, query.getMaxPendingQueries());
    assertEquals(0, query.getAcquireTimeoutMs());
    assertEquals(0, query.getMaxCachedEntries());
    assertEquals(0, query.getMaxCachedBytes());
    assertEquals(0, query.getMaxRetainedTraceBytes());
    assertEquals(0, query.getMaxTraceResponseBytes());
  }

  @Test
  public void testArchiveQueryRejectsInvalidConcurrencyLimit() {
    assertArchiveQueryRejected("maxConcurrentQueries", 0);
    assertArchiveQueryRejected("maxConcurrentQueries", -2);
  }

  @Test
  public void testArchiveQueryRejectsZeroForRequiredOperationalLimits() {
    for (String key : new String[] {
        "maxOpenSnapshots",
        "deadlineMs",
        "batchDeadlineMs",
        "maxLogicalReadsPerRequest",
        "maxBackendReadsPerRequest"}) {
      assertArchiveQueryRejected(key, 0);
    }
  }

  @Test
  public void testArchiveQueryRejectsNegativeLimitsOtherThanUnlimited() {
    String[] keys = {
        "maxPendingQueries",
        "maxOpenSnapshots",
        "acquireTimeoutMs",
        "deadlineMs",
        "batchDeadlineMs",
        "maxLogicalReadsPerRequest",
        "maxBackendReadsPerRequest",
        "maxTraceSteps",
        "maxTraceBytes",
        "maxRetainedTraceBytes",
        "maxTraceResponseBytes"
    };
    for (String key : keys) {
      assertArchiveQueryRejected(key, -2);
    }
    assertArchiveQueryRejected("maxCachedEntries", -1);
    assertArchiveQueryRejected("maxCachedBytes", -1);
    assertArchiveQueryRejected("maxQueriesPerBatch", 0);
    assertArchiveQueryRejected("maxQueriesPerBatch", -2);
  }

  @Test
  public void testArchiveRejectsEmptyDirectory() {
    assertThrows(IllegalArgumentException.class,
        () -> StorageConfig.fromConfig(withRef("storage.archive.db.directory = \"\"")));
  }

  @Test
  public void testArchiveRejectsRemovedLayoutKey() {
    assertThrows(IllegalArgumentException.class,
        () -> StorageConfig.fromConfig(withRef("storage.archive.db.layout = FUTURE_V2")));
  }

  @Test
  public void testArchiveRejectsEmptyCoverage() {
    assertThrows(IllegalArgumentException.class,
        () -> StorageConfig.fromConfig(withRef("storage.archive.coverage = \"\"")));
  }

  @Test
  public void testArchiveRejectsUnsupportedCoverage() {
    assertThrows(IllegalArgumentException.class,
        () -> StorageConfig.fromConfig(withRef("storage.archive.coverage = FULL")));
  }

  @Test
  public void testArchiveRejectsWarnUnclassifiedDisabled() {
    assertThrows(IllegalArgumentException.class,
        () -> StorageConfig.fromConfig(withRef(
            "storage.archive.warnUnclassifiedStoreWrites = false")));
  }

  @Test
  public void testArchiveRejectsUnsupportedCommitmentEnabled() {
    assertThrows(IllegalArgumentException.class,
        () -> StorageConfig.fromConfig(withRef(
            "storage.archive { enable = true, commitment { enable = true } }")));
  }

  @Test
  public void testArchiveRejectsPersistTxRootsEnabled() {
    assertThrows(IllegalArgumentException.class,
        () -> StorageConfig.fromConfig(withRef(
            "storage.archive { enable = true, commitment { persistTxRoots = true } }")));
  }

  @Test
  public void testArchiveRejectsTxNumDisabled() {
    assertThrows(IllegalArgumentException.class,
        () -> StorageConfig.fromConfig(withRef(
            "storage.archive { enable = true, txnum { enable = false } }")));
  }

  @Test
  public void testArchiveRejectsTemporalDisabled() {
    assertThrows(IllegalArgumentException.class,
        () -> StorageConfig.fromConfig(withRef(
            "storage.archive { enable = true, temporal { enable = false } }")));
  }

  @Test
  public void testArchiveRejectsDebugEnabled() {
    assertThrows(IllegalArgumentException.class,
        () -> StorageConfig.fromConfig(withRef(
            "storage.archive { enable = true, debug { enable = true } }")));
  }

  @Test
  public void testArchiveRejectsUnknownKeys() {
    assertThrows(IllegalArgumentException.class,
        () -> StorageConfig.fromConfig(withRef("storage.archive.enabled = true")));
    assertThrows(IllegalArgumentException.class,
        () -> StorageConfig.fromConfig(withRef("storage.archive.debug.enabled = true")));
    assertThrows(IllegalArgumentException.class,
        () -> StorageConfig.fromConfig(withRef("storage.archive.query.maxReads = 1")));
    assertThrows(IllegalArgumentException.class,
        () -> StorageConfig.fromConfig(withRef("storage.archive.publisher.queueSize = 1")));
    assertThrows(IllegalArgumentException.class,
        () -> StorageConfig.fromConfig(withRef("storage.archive.db.layout = LEGACY_V1")));
    assertThrows(IllegalArgumentException.class,
        () -> StorageConfig.fromConfig(withRef("storage.archive.identity.adoptLegacy = true")));
  }

  private static void assertArchiveQueryRejected(String key, long value) {
    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
        () -> StorageConfig.fromConfig(withRef(
            "storage.archive.query." + key + " = " + value)));
    assertTrue(failure.getMessage().contains("storage.archive.query." + key));
  }

  // ---- readProperties() ----

  private static List<PropertyConfig> props(String storageProperties) {
    return StorageConfig.fromConfig(withRef(storageProperties)).getProperties();
  }

  @Test
  public void testPropertiesDefaultEmpty() {
    // reference.conf sets storage.properties = []
    assertTrue(StorageConfig.fromConfig(withRef()).getProperties().isEmpty());
    assertTrue(props("storage.properties = []").isEmpty());
  }

  @Test
  public void testPropertiesNameAndPathOnly() {
    // All LevelDB options omitted: name/path set, the four boxed fields stay null so
    // they inherit the per-tier defaults applied later by newDefaultDbOptions.
    List<PropertyConfig> list = props(
        "storage.properties = [ { name = account, path = some_path } ]");
    assertEquals(1, list.size());
    PropertyConfig p = list.get(0);
    assertEquals("account", p.getName());
    assertEquals("some_path", p.getPath());
    assertNull(p.getBlockSize());
    assertNull(p.getWriteBufferSize());
    assertNull(p.getCacheSize());
    assertNull(p.getMaxOpenFiles());
  }

  @Test
  public void testPropertiesNameOnlyKeepsEmptyPath() {
    PropertyConfig p = props("storage.properties = [ { name = account } ]").get(0);
    assertEquals("account", p.getName());
    assertEquals("", p.getPath());
  }

  @Test
  public void testPropertiesFullOverrideParsed() {
    PropertyConfig p = props(
        "storage.properties = [ { name = foo, path = bar,"
        + " blockSize = 2, writeBufferSize = 3, cacheSize = 4, maxOpenFiles = 5 } ]").get(0);
    assertEquals(Integer.valueOf(2), p.getBlockSize());
    assertEquals(Integer.valueOf(3), p.getWriteBufferSize());
    assertEquals(Long.valueOf(4L), p.getCacheSize());
    assertEquals(Integer.valueOf(5), p.getMaxOpenFiles());
  }

  @Test
  public void testPropertiesPartialOverrideLeavesOthersNull() {
    // Only blockSize is set; the other three stay null (inherit defaults).
    PropertyConfig p = props(
        "storage.properties = [ { name = foo, path = bar, blockSize = 8192 } ]").get(0);
    assertEquals(Integer.valueOf(8192), p.getBlockSize());
    assertNull(p.getWriteBufferSize());
    assertNull(p.getCacheSize());
    assertNull(p.getMaxOpenFiles());
  }

  @Test
  public void testPropertiesMultipleEntriesInOrder() {
    List<PropertyConfig> list = props(
        "storage.properties = ["
        + " { name = first, path = p1 },"
        + " { name = second, path = p2, maxOpenFiles = 7 } ]");
    assertEquals(2, list.size());
    assertEquals("first", list.get(0).getName());
    assertNull(list.get(0).getMaxOpenFiles());
    assertEquals("second", list.get(1).getName());
    assertEquals(Integer.valueOf(7), list.get(1).getMaxOpenFiles());
  }

  @Test
  public void testPropertiesMissingNameKeepsEmpty() {
    // readProperties does not require name (validation is deferred to Storage); name stays "".
    PropertyConfig p = props("storage.properties = [ { path = bar } ]").get(0);
    assertEquals("", p.getName());
    assertEquals("bar", p.getPath());
  }

  @Test
  public void testPropertiesInvalidIntegerRejected() {
    assertThrows(IllegalArgumentException.class,
        () -> props("storage.properties = [ { name = foo, blockSize = not_a_number } ]"));
  }

  @Test
  public void testPropertiesInvalidLongRejected() {
    assertThrows(IllegalArgumentException.class,
        () -> props("storage.properties = [ { name = foo, cacheSize = not_a_number } ]"));
  }
}
