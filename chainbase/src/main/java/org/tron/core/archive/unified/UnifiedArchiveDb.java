package org.tron.core.archive.unified;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.BloomFilter;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.Options;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.Snapshot;
import org.rocksdb.Statistics;
import org.rocksdb.StatsLevel;
import org.rocksdb.TableFormatConfig;
import org.rocksdb.TickerType;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.ArchiveMetrics;
import org.tron.core.archive.ArchiveRocksIterators;

/**
 * Core UNIFIED_V1 storage owner: one RocksDB, exact column families, atomic block publication, and
 * snapshot reads spanning every logical archive table.
 */
public final class UnifiedArchiveDb implements AutoCloseable {

  static {
    RocksDB.loadLibrary();
  }

  private static final BatchWriter ROCKS_BATCH_WRITER = RocksDB::write;
  private static final Logger logger = LoggerFactory.getLogger("archive");
  private static final long STATISTICS_SAMPLE_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10L);
  private static final String PENDING_COMPACTION_BYTES =
      "rocksdb.estimate-pending-compaction-bytes";
  private static final String RUNNING_COMPACTIONS = "rocksdb.num-running-compactions";
  private static final String RUNNING_FLUSHES = "rocksdb.num-running-flushes";
  private static final TickerMetric[] TICKER_METRICS = {
      new TickerMetric(TickerType.STALL_MICROS, "rocksdb_stall_micros"),
      new TickerMetric(TickerType.BLOOM_FILTER_USEFUL, "rocksdb_bloom_filter_useful"),
      new TickerMetric(TickerType.BLOCK_CACHE_HIT, "rocksdb_block_cache_hit"),
      new TickerMetric(TickerType.BLOCK_CACHE_MISS, "rocksdb_block_cache_miss"),
      new TickerMetric(TickerType.COMPACT_READ_BYTES, "rocksdb_compact_read_bytes"),
      new TickerMetric(TickerType.COMPACT_WRITE_BYTES, "rocksdb_compact_write_bytes"),
      new TickerMetric(TickerType.FLUSH_WRITE_BYTES, "rocksdb_flush_write_bytes")
  };

  private final Path path;
  private final byte[] schemaChecksum;
  private final DBOptions dbOptions;
  private final List<ColumnFamilyOptions> columnFamilyOptions;
  private final List<BloomFilter> bloomFilters;
  private final List<ColumnFamilyHandle> allHandles;
  private final ColumnFamilyHandle defaultHandle;
  private final EnumMap<UnifiedArchiveColumnFamily, ColumnFamilyHandle> handles;
  private final RocksDB db;
  private final BatchWriter batchWriter;
  private final Statistics statistics;
  private final EnumMap<TickerType, Long> lastTickerCounts = new EnumMap<>(TickerType.class);
  private final Set<String> disabledStatisticsProperties = new HashSet<>();
  private final AtomicBoolean statisticsFailureReported = new AtomicBoolean();

  private boolean closed;
  private int activeReadViews;
  private long lastStatisticsSampleNanos = Long.MIN_VALUE;

  private UnifiedArchiveDb(Path path, byte[] schemaChecksum, DBOptions dbOptions,
      List<ColumnFamilyOptions> columnFamilyOptions, List<BloomFilter> bloomFilters,
      List<ColumnFamilyHandle> allHandles, RocksDB db, BatchWriter batchWriter,
      Statistics statistics) throws RocksDBException {
    this.path = path;
    this.schemaChecksum = Arrays.copyOf(schemaChecksum, schemaChecksum.length);
    this.dbOptions = dbOptions;
    this.columnFamilyOptions = columnFamilyOptions;
    this.bloomFilters = bloomFilters;
    this.allHandles = allHandles;
    this.defaultHandle = allHandles.get(0);
    this.handles = mapHandles(allHandles);
    this.db = db;
    this.batchWriter = batchWriter;
    this.statistics = statistics;
  }

  /** Explicitly creates a new unified DB. The target itself must not already exist. */
  public static UnifiedArchiveDb initialize(Path path, byte[] schemaChecksum) {
    Path target = normalizePath(path);
    UnifiedArchiveManifest.requireSchemaChecksum(schemaChecksum);
    byte[] immutableSchemaChecksum = Arrays.copyOf(schemaChecksum, schemaChecksum.length);
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      throw new ArchiveException("UNIFIED_V1 initialization requires a nonexistent target: "
          + target);
    }
    Path parent = target.getParent();
    if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
      throw new ArchiveException("UNIFIED_V1 initialization parent is not a directory: "
          + parent);
    }
    return openDatabase(target, immutableSchemaChecksum, true, ROCKS_BATCH_WRITER);
  }

  /** Opens only a fully initialized UNIFIED_V1 DB; this method never creates files or CFs. */
  public static UnifiedArchiveDb open(Path path, byte[] expectedSchemaChecksum) {
    return open(path, expectedSchemaChecksum, ROCKS_BATCH_WRITER);
  }

  /**
   * Creates a new empty DB or resumes the narrow initialization window before the manifest was
   * installed. Existing archive rows are never repaired or overwritten by this method.
   */
  public static UnifiedArchiveDb initializeOrResumeEmpty(Path path, byte[] schemaChecksum) {
    Path target = normalizePath(path);
    UnifiedArchiveManifest.requireSchemaChecksum(schemaChecksum);
    byte[] immutableSchemaChecksum = Arrays.copyOf(schemaChecksum, schemaChecksum.length);
    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      return initialize(target, immutableSchemaChecksum);
    }
    if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
      throw new ArchiveException("UNIFIED_V1 initialization target is not a directory: "
          + target);
    }
    validateColumnFamiliesOnDisk(target);
    return openDatabase(target, immutableSchemaChecksum, false, ROCKS_BATCH_WRITER, true);
  }

  static UnifiedArchiveDb open(Path path, byte[] expectedSchemaChecksum,
      BatchWriter batchWriter) {
    Path target = normalizePath(path);
    UnifiedArchiveManifest.requireSchemaChecksum(expectedSchemaChecksum);
    byte[] immutableSchemaChecksum =
        Arrays.copyOf(expectedSchemaChecksum, expectedSchemaChecksum.length);
    if (batchWriter == null) {
      throw new ArchiveException("UNIFIED_V1 batch writer is required");
    }
    if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
      throw new ArchiveException("UNIFIED_V1 archive is not initialized at " + target);
    }
    validateColumnFamiliesOnDisk(target);
    return openDatabase(target, immutableSchemaChecksum, false, batchWriter);
  }

  /** Forced-sync journal write; the WAL is always enabled. */
  public synchronized void putJournalDurably(byte[] key, byte[] value) {
    requireOpen();
    requireKey(key, "journal");
    requireValue(value, "journal");
    byte[] immutableKey = Arrays.copyOf(key, key.length);
    byte[] immutableValue = Arrays.copyOf(value, value.length);
    try {
      byte[] current = db.get(handle(UnifiedArchiveColumnFamily.INFLIGHT), immutableKey);
      if (current != null && !Arrays.equals(current, immutableValue)) {
        throw new ArchiveException("UNIFIED_V1 refuses to replace a different journal row");
      }
      if (current != null) {
        return;
      }
      try (WriteOptions writeOptions = createWriteOptions(true)) {
        db.put(handle(UnifiedArchiveColumnFamily.INFLIGHT), writeOptions,
            immutableKey, immutableValue);
      }
    } catch (RocksDBException e) {
      throw new ArchiveException("UNIFIED_V1 journal write failed", e);
    }
  }

  /** Forced-sync atomic write of an immutable journal payload and its compact token header. */
  public synchronized void putJournalBlockDurably(byte[] journalKey, byte[] journalValue,
      byte[] tokenKey, byte[] tokenValue, byte[] acknowledgementKey,
      byte[] acknowledgementValue) {
    requireOpen();
    requireDistinctKeys(journalKey, tokenKey, acknowledgementKey);
    requireValue(journalValue, "journal");
    requireValue(tokenValue, "journal token");
    if (acknowledgementValue != null) {
      requireValue(acknowledgementValue, "journal acknowledgement");
      requireSameToken(tokenValue, acknowledgementValue);
    }
    try {
      byte[] currentJournal = journalValue(journalKey);
      byte[] currentToken = journalValue(tokenKey);
      byte[] currentAcknowledgement = journalValue(acknowledgementKey);
      if (currentJournal != null || currentToken != null || currentAcknowledgement != null) {
        if (Arrays.equals(currentJournal, journalValue)
            && Arrays.equals(currentToken, tokenValue)
            && Arrays.equals(currentAcknowledgement, acknowledgementValue)) {
          return;
        }
        throw new ArchiveException("UNIFIED_V1 refuses to replace a different journal bundle");
      }
      try (WriteBatch batch = new WriteBatch();
           WriteOptions writeOptions = createWriteOptions(true)) {
        ColumnFamilyHandle inflight = handle(UnifiedArchiveColumnFamily.INFLIGHT);
        batch.put(inflight, journalKey, journalValue);
        batch.put(inflight, tokenKey, tokenValue);
        if (acknowledgementValue != null) {
          batch.put(inflight, acknowledgementKey, acknowledgementValue);
        }
        batchWriter.write(db, writeOptions, batch);
      }
    } catch (RocksDBException e) {
      throw new ArchiveException("UNIFIED_V1 journal bundle write failed", e);
    }
  }

  /** WAL-only compact acknowledgement; the forced-sync immutable payload remains untouched. */
  public synchronized void acknowledgeJournalWalOnly(byte[] journalKey, byte[] tokenKey,
      byte[] expectedTokenValue, byte[] acknowledgementKey, byte[] acknowledgementValue) {
    requireOpen();
    requireDistinctKeys(journalKey, tokenKey, acknowledgementKey);
    requireValue(expectedTokenValue, "journal token");
    requireValue(acknowledgementValue, "journal acknowledgement");
    requireSameToken(expectedTokenValue, acknowledgementValue);
    try {
      requireJournalPresent(journalKey);
      requireJournalValue(tokenKey, expectedTokenValue);
      byte[] current = journalValue(acknowledgementKey);
      if (current != null && !Arrays.equals(current, acknowledgementValue)) {
        throw new ArchiveException("UNIFIED_V1 journal acknowledgement changed");
      }
      if (current != null) {
        return;
      }
      try (WriteOptions writeOptions = createWriteOptions(false)) {
        db.put(handle(UnifiedArchiveColumnFamily.INFLIGHT), writeOptions,
            acknowledgementKey, acknowledgementValue);
      }
    } catch (RocksDBException e) {
      throw new ArchiveException("UNIFIED_V1 journal acknowledgement failed", e);
    }
  }

  /** Forced-sync compare-and-delete used by journal rollback paths outside publication. */
  public synchronized void deleteJournalBlockDurably(byte[] journalKey, byte[] journalValue,
      byte[] tokenKey, byte[] tokenValue, byte[] acknowledgementKey,
      byte[] acknowledgementValue) {
    requireOpen();
    requireDistinctKeys(journalKey, tokenKey, acknowledgementKey);
    requireValue(journalValue, "journal");
    requireValue(tokenValue, "journal token");
    if (acknowledgementValue != null) {
      requireValue(acknowledgementValue, "journal acknowledgement");
    }
    try {
      requireJournalValue(journalKey, journalValue);
      requireJournalValue(tokenKey, tokenValue);
      requireOptionalJournalValue(acknowledgementKey, acknowledgementValue);
      try (WriteBatch batch = new WriteBatch();
           WriteOptions writeOptions = createWriteOptions(true)) {
        ColumnFamilyHandle inflight = handle(UnifiedArchiveColumnFamily.INFLIGHT);
        batch.delete(inflight, journalKey);
        batch.delete(inflight, tokenKey);
        batch.delete(inflight, acknowledgementKey);
        batchWriter.write(db, writeOptions, batch);
      }
    } catch (RocksDBException e) {
      throw new ArchiveException("UNIFIED_V1 journal bundle delete failed", e);
    }
  }

  /** Forced-sync operational metadata write for activation and repair state. */
  public synchronized void putMetaDurably(byte[] key, byte[] value) {
    requireOpen();
    requireKey(key, "meta");
    requireValue(value, "meta");
    byte[] immutableKey = Arrays.copyOf(key, key.length);
    byte[] immutableValue = Arrays.copyOf(value, value.length);
    if (Arrays.equals(immutableKey, UnifiedArchiveManifest.key())
        || Arrays.equals(immutableKey, UnifiedArchiveManifest.publishedCursorKey())) {
      throw new ArchiveException("UNIFIED_V1 reserved meta row is immutable through this API");
    }
    try (WriteOptions writeOptions = createWriteOptions(true)) {
      db.put(handle(UnifiedArchiveColumnFamily.META), writeOptions,
          immutableKey, immutableValue);
    } catch (RocksDBException e) {
      throw new ArchiveException("UNIFIED_V1 meta write failed", e);
    }
  }

  /** Forced-sync operational metadata delete; manifest and published cursor remain reserved. */
  public synchronized void deleteMetaDurably(byte[] key) {
    requireOpen();
    requireKey(key, "meta");
    byte[] immutableKey = Arrays.copyOf(key, key.length);
    if (Arrays.equals(immutableKey, UnifiedArchiveManifest.key())
        || Arrays.equals(immutableKey, UnifiedArchiveManifest.publishedCursorKey())) {
      throw new ArchiveException("UNIFIED_V1 reserved meta row is immutable through this API");
    }
    try (WriteOptions writeOptions = createWriteOptions(true)) {
      db.delete(handle(UnifiedArchiveColumnFamily.META), writeOptions, immutableKey);
    } catch (RocksDBException e) {
      throw new ArchiveException("UNIFIED_V1 meta delete failed", e);
    }
  }

  /** Point read used by typed adapters outside a long-lived snapshot. */
  public synchronized byte[] get(UnifiedArchiveColumnFamily columnFamily, byte[] key) {
    requireOpen();
    if (columnFamily == null) {
      throw new ArchiveException("UNIFIED_V1 column family is required");
    }
    requireKey(key, "read");
    try {
      return db.get(handle(columnFamily), key);
    } catch (RocksDBException e) {
      throw new ArchiveException("UNIFIED_V1 read failed for " + columnFamily.getName(), e);
    }
  }

  /** Forced-sync restricted cross-CF batch for temporal unwind and offline validation repair. */
  public synchronized void writeMaintenanceAtomically(UnifiedArchiveMaintenanceBatch mutations) {
    requireOpen();
    if (mutations == null) {
      throw new ArchiveException("UNIFIED_V1 maintenance batch is required");
    }
    try (WriteBatch batch = new WriteBatch();
         WriteOptions writeOptions = createWriteOptions(true)) {
      for (UnifiedArchiveMaintenanceBatch.Mutation mutation : mutations.mutations()) {
        ColumnFamilyHandle mutationHandle = handle(mutation.columnFamily());
        if (mutation.isDelete()) {
          batch.delete(mutationHandle, mutation.key());
        } else {
          batch.put(mutationHandle, mutation.key(), mutation.value());
        }
      }
      batchWriter.write(db, writeOptions, batch);
      maybeReportStatistics();
    } catch (RocksDBException e) {
      throw new ArchiveException("UNIFIED_V1 maintenance batch failed", e);
    }
  }

  /**
   * Publishes one block in a single cross-CF WriteBatch. Cursor and marker become visible with all
   * index/temporal rows at the same sequence, while the matched durable journal disappears.
   */
  public synchronized void publishBlockAtomically(UnifiedArchivePublish publish,
      boolean publishSync) {
    requireOpen();
    if (publish == null) {
      throw new ArchiveException("UNIFIED_V1 publish description is required");
    }
    try {
      requireJournalValue(publish.journalKey(), publish.expectedJournalValue());
      requireJournalValue(
          publish.journalTokenKey(), publish.expectedJournalTokenValue());
      requireJournalValue(
          publish.acknowledgementKey(), publish.expectedAcknowledgementValue());
      try (WriteBatch batch = new WriteBatch();
           WriteOptions writeOptions = createWriteOptions(publishSync)) {
        for (UnifiedArchivePublish.Mutation mutation : publish.mutations()) {
          ColumnFamilyHandle mutationHandle = handle(mutation.columnFamily());
          if (mutation.isDelete()) {
            batch.delete(mutationHandle, mutation.key());
          } else {
            batch.put(mutationHandle, mutation.key(), mutation.value());
          }
        }
        batch.put(handle(UnifiedArchiveColumnFamily.BLOCK_MARKER),
            publish.blockMarkerKey(), publish.blockMarkerValue());
        batch.put(handle(UnifiedArchiveColumnFamily.META),
            publish.cursorKey(), publish.cursorValue());
        batch.delete(handle(UnifiedArchiveColumnFamily.INFLIGHT), publish.journalKey());
        batch.delete(handle(UnifiedArchiveColumnFamily.INFLIGHT), publish.journalTokenKey());
        batch.delete(handle(UnifiedArchiveColumnFamily.INFLIGHT), publish.acknowledgementKey());
        batchWriter.write(db, writeOptions, batch);
      }
      maybeReportStatistics();
    } catch (RocksDBException e) {
      throw new ArchiveException("UNIFIED_V1 atomic block publish failed", e);
    }
  }

  /** Captures meta, inflight, index, temporal, marker, and commitment at one sequence number. */
  public synchronized UnifiedArchiveReadView openReadView() {
    return openReadView(true);
  }

  /**
   * Captures one sequence for validation and maintenance scans without admitting scanned blocks
   * into the point-read cache.
   */
  public synchronized UnifiedArchiveReadView openScanView() {
    return openReadView(false);
  }

  private UnifiedArchiveReadView openReadView(boolean fillCache) {
    requireOpen();
    Snapshot snapshot = db.getSnapshot();
    ReadOptions readOptions = null;
    try {
      readOptions = new ReadOptions().setSnapshot(snapshot).setFillCache(fillCache);
      UnifiedArchiveReadView view = new UnifiedArchiveReadView(
          db, handles, snapshot, readOptions, this::releaseReadView);
      activeReadViews++;
      return view;
    } catch (RuntimeException | Error failure) {
      if (readOptions != null) {
        readOptions.close();
      }
      db.releaseSnapshot(snapshot);
      throw failure;
    }
  }

  public Path getPath() {
    return path;
  }

  public byte[] getSchemaChecksum() {
    return Arrays.copyOf(schemaChecksum, schemaChecksum.length);
  }

  /** True when any archive payload row beyond the immutable manifest is present. */
  public synchronized boolean hasArchiveData() {
    requireOpen();
    try (ReadOptions readOptions = new ReadOptions().setFillCache(false)) {
      for (UnifiedArchiveColumnFamily columnFamily : UnifiedArchiveColumnFamily.values()) {
        try (RocksIterator iterator = db.newIterator(handle(columnFamily), readOptions)) {
          iterator.seekToFirst();
          while (iterator.isValid()) {
            if (columnFamily != UnifiedArchiveColumnFamily.META
                || !Arrays.equals(iterator.key(), UnifiedArchiveManifest.key())) {
              return true;
            }
            iterator.next();
          }
          ArchiveRocksIterators.requireOk(iterator,
              "UNIFIED_V1 scan for archive payload in " + columnFamily.getName());
        }
      }
      return false;
    }
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    if (activeReadViews != 0) {
      throw new ArchiveException("UNIFIED_V1 DB has active snapshot read views: "
          + activeReadViews);
    }
    maybeReportStatistics();
    closed = true;
    closeResources(allHandles, db, columnFamilyOptions, bloomFilters, dbOptions, statistics);
  }

  int ownedBloomFilterCount() {
    return bloomFilters.size();
  }

  boolean usesEvictableIndexAndFilterCache() {
    for (ColumnFamilyOptions options : columnFamilyOptions) {
      TableFormatConfig tableFormatConfig = options.tableFormatConfig();
      if (!(tableFormatConfig instanceof BlockBasedTableConfig)) {
        return false;
      }
      BlockBasedTableConfig tableConfig = (BlockBasedTableConfig) tableFormatConfig;
      if (!tableConfig.cacheIndexAndFilterBlocks()
          || tableConfig.pinL0FilterAndIndexBlocksInCache()) {
        return false;
      }
    }
    return true;
  }

  boolean hasStatistics() {
    return statistics != null;
  }

  StatsLevel statisticsLevel() {
    return statistics == null ? null : statistics.statsLevel();
  }

  static WriteOptions createWriteOptions(boolean sync) {
    return new WriteOptions().setDisableWAL(false).setSync(sync);
  }

  static List<byte[]> expectedColumnFamilyNames() {
    List<byte[]> names = new ArrayList<>();
    names.add(Arrays.copyOf(RocksDB.DEFAULT_COLUMN_FAMILY,
        RocksDB.DEFAULT_COLUMN_FAMILY.length));
    for (UnifiedArchiveColumnFamily columnFamily : UnifiedArchiveColumnFamily.values()) {
      names.add(columnFamily.nameBytes());
    }
    return names;
  }

  private static UnifiedArchiveDb openDatabase(Path target, byte[] schemaChecksum,
      boolean initialize, BatchWriter batchWriter) {
    return openDatabase(target, schemaChecksum, initialize, batchWriter, false);
  }

  private static UnifiedArchiveDb openDatabase(Path target, byte[] schemaChecksum,
      boolean initialize, BatchWriter batchWriter, boolean resumeEmptyInitialization) {
    Statistics statistics = null;
    DBOptions dbOptions = null;
    List<ColumnFamilyOptions> columnFamilyOptions = new ArrayList<>();
    List<BloomFilter> bloomFilters = new ArrayList<>();
    List<ColumnFamilyHandle> openedHandles = new ArrayList<>();
    RocksDB openedDb = null;
    try {
      statistics = ArchiveMetrics.enabled() ? new Statistics() : null;
      if (statistics != null) {
        statistics.setStatsLevel(StatsLevel.EXCEPT_DETAILED_TIMERS);
      }
      dbOptions = new DBOptions()
          .setCreateIfMissing(initialize)
          .setCreateMissingColumnFamilies(initialize)
          .setErrorIfExists(initialize)
          .setParanoidChecks(true);
      if (statistics != null) {
        dbOptions.setStatistics(statistics);
      }
      List<ColumnFamilyDescriptor> descriptors =
          descriptors(columnFamilyOptions, bloomFilters);
      openedDb = RocksDB.open(dbOptions, target.toString(), descriptors, openedHandles);
      UnifiedArchiveDb opened = new UnifiedArchiveDb(target, schemaChecksum, dbOptions,
          columnFamilyOptions, bloomFilters, openedHandles, openedDb, batchWriter, statistics);
      if (initialize) {
        opened.installManifest();
      } else if (resumeEmptyInitialization) {
        opened.resumeEmptyManifestIfMissing();
      }
      opened.validateIdentity();
      return opened;
    } catch (RocksDBException e) {
      closeResources(
          openedHandles, openedDb, columnFamilyOptions, bloomFilters, dbOptions, statistics);
      String operation = initialize ? "initialize" : "open";
      throw new ArchiveException("failed to " + operation + " UNIFIED_V1 archive at "
          + target, e);
    } catch (RuntimeException | Error failure) {
      closeResources(
          openedHandles, openedDb, columnFamilyOptions, bloomFilters, dbOptions, statistics);
      throw failure;
    }
  }

  private static List<ColumnFamilyDescriptor> descriptors(
      List<ColumnFamilyOptions> optionsOwner, List<BloomFilter> bloomFilterOwner) {
    List<ColumnFamilyDescriptor> descriptors = new ArrayList<>();
    for (byte[] name : expectedColumnFamilyNames()) {
      BloomFilter bloomFilter = new BloomFilter(10, false);
      bloomFilterOwner.add(bloomFilter);
      BlockBasedTableConfig tableConfig = new BlockBasedTableConfig()
          .setFilter(bloomFilter)
          .setCacheIndexAndFilterBlocks(true)
          .setPinL0FilterAndIndexBlocksInCache(false);
      ColumnFamilyOptions options = new ColumnFamilyOptions();
      optionsOwner.add(options);
      options.setTableFormatConfig(tableConfig);
      descriptors.add(new ColumnFamilyDescriptor(name, options));
    }
    return descriptors;
  }

  private static EnumMap<UnifiedArchiveColumnFamily, ColumnFamilyHandle> mapHandles(
      List<ColumnFamilyHandle> openedHandles) throws RocksDBException {
    List<byte[]> expectedNames = expectedColumnFamilyNames();
    if (openedHandles.size() != expectedNames.size()) {
      throw new ArchiveException("UNIFIED_V1 opened an unexpected number of column families");
    }
    for (int i = 0; i < openedHandles.size(); i++) {
      if (!Arrays.equals(openedHandles.get(i).getName(), expectedNames.get(i))) {
        throw new ArchiveException("UNIFIED_V1 column family handle order mismatch");
      }
    }
    EnumMap<UnifiedArchiveColumnFamily, ColumnFamilyHandle> mapped =
        new EnumMap<>(UnifiedArchiveColumnFamily.class);
    for (UnifiedArchiveColumnFamily columnFamily : UnifiedArchiveColumnFamily.values()) {
      mapped.put(columnFamily, openedHandles.get(columnFamily.ordinal() + 1));
    }
    return mapped;
  }

  private static void validateColumnFamiliesOnDisk(Path target) {
    List<byte[]> actual;
    try (Options options = new Options().setCreateIfMissing(false)) {
      actual = RocksDB.listColumnFamilies(options, target.toString());
    } catch (RocksDBException e) {
      throw new ArchiveException("UNIFIED_V1 archive has no readable RocksDB manifest at "
          + target, e);
    }
    List<byte[]> expected = expectedColumnFamilyNames();
    if (actual.size() != expected.size()) {
      throw new ArchiveException("UNIFIED_V1 archive column family set mismatch");
    }
    boolean[] seen = new boolean[expected.size()];
    for (byte[] actualName : actual) {
      int expectedIndex = indexOf(expected, actualName);
      if (expectedIndex < 0 || seen[expectedIndex]) {
        throw new ArchiveException("UNIFIED_V1 archive has an unknown or duplicate column family: "
            + displayName(actualName));
      }
      seen[expectedIndex] = true;
    }
    for (boolean present : seen) {
      if (!present) {
        throw new ArchiveException("UNIFIED_V1 archive is missing a column family");
      }
    }
  }

  private static int indexOf(List<byte[]> names, byte[] candidate) {
    for (int i = 0; i < names.size(); i++) {
      if (Arrays.equals(names.get(i), candidate)) {
        return i;
      }
    }
    return -1;
  }

  private static String displayName(byte[] name) {
    for (byte current : name) {
      if (current < 0x20 || current > 0x7e) {
        return Arrays.toString(name);
      }
    }
    return new String(name, StandardCharsets.US_ASCII);
  }

  private void installManifest() throws RocksDBException {
    try (WriteOptions writeOptions = createWriteOptions(true)) {
      db.put(handle(UnifiedArchiveColumnFamily.META), writeOptions,
          UnifiedArchiveManifest.key(), UnifiedArchiveManifest.value(schemaChecksum));
    }
  }

  private void resumeEmptyManifestIfMissing() throws RocksDBException {
    validateDefaultColumnFamilyEmpty();
    byte[] manifest = db.get(handle(UnifiedArchiveColumnFamily.META),
        UnifiedArchiveManifest.key());
    if (manifest != null) {
      return;
    }
    if (hasArchiveData()) {
      throw new ArchiveException(
          "UNIFIED_V1 cannot resume initialization with archive rows present");
    }
    installManifest();
  }

  private void validateIdentity() throws RocksDBException {
    validateDefaultColumnFamilyEmpty();
    byte[] manifest = db.get(handle(UnifiedArchiveColumnFamily.META),
        UnifiedArchiveManifest.key());
    UnifiedArchiveManifest.validate(manifest, schemaChecksum);
  }

  private void validateDefaultColumnFamilyEmpty() {
    try (ReadOptions readOptions = new ReadOptions().setFillCache(false);
         RocksIterator iterator = db.newIterator(defaultHandle, readOptions)) {
      iterator.seekToFirst();
      ArchiveRocksIterators.requireOk(iterator,
          "UNIFIED_V1 validate empty default column family");
      if (iterator.isValid()) {
        throw new ArchiveException("UNIFIED_V1 default column family must be empty");
      }
    }
  }

  private void requireJournalValue(byte[] key, byte[] expectedValue) throws RocksDBException {
    byte[] current = journalValue(key);
    if (current == null) {
      throw new ArchiveException("UNIFIED_V1 publish journal is missing");
    }
    if (!Arrays.equals(current, expectedValue)) {
      throw new ArchiveException("UNIFIED_V1 publish journal changed before publication");
    }
  }

  private void requireJournalPresent(byte[] key) throws RocksDBException {
    if (journalValue(key) == null) {
      throw new ArchiveException("UNIFIED_V1 journal payload is missing");
    }
  }

  private void requireOptionalJournalValue(byte[] key, byte[] expectedValue)
      throws RocksDBException {
    byte[] current = journalValue(key);
    if (!Arrays.equals(current, expectedValue)) {
      throw new ArchiveException("UNIFIED_V1 journal lifecycle row changed");
    }
  }

  private byte[] journalValue(byte[] key) throws RocksDBException {
    return db.get(handle(UnifiedArchiveColumnFamily.INFLIGHT), key);
  }

  private ColumnFamilyHandle handle(UnifiedArchiveColumnFamily columnFamily) {
    return handles.get(columnFamily);
  }

  private synchronized void releaseReadView() {
    if (activeReadViews <= 0) {
      throw new ArchiveException("UNIFIED_V1 snapshot read view accounting underflow");
    }
    activeReadViews--;
  }

  private void requireOpen() {
    if (closed) {
      throw new ArchiveException("UNIFIED_V1 DB is closed");
    }
  }

  private static Path normalizePath(Path path) {
    if (path == null) {
      throw new ArchiveException("UNIFIED_V1 archive path is required");
    }
    return path.toAbsolutePath().normalize();
  }

  private static void requireKey(byte[] key, String what) {
    if (key == null || key.length == 0) {
      throw new ArchiveException("UNIFIED_V1 " + what + " key is required");
    }
  }

  private static void requireValue(byte[] value, String what) {
    if (value == null || value.length == 0) {
      throw new ArchiveException("UNIFIED_V1 " + what + " value is required");
    }
  }

  private static void requireDistinctKeys(byte[] first, byte[] second, byte[] third) {
    requireKey(first, "journal");
    requireKey(second, "journal token");
    requireKey(third, "journal acknowledgement");
    if (Arrays.equals(first, second) || Arrays.equals(first, third)
        || Arrays.equals(second, third)) {
      throw new ArchiveException("UNIFIED_V1 journal lifecycle keys must be distinct");
    }
  }

  private static void requireSameToken(byte[] token, byte[] acknowledgement) {
    if (!Arrays.equals(token, acknowledgement)) {
      throw new ArchiveException(
          "UNIFIED_V1 journal acknowledgement must match its token header");
    }
  }

  private static void closeResources(List<ColumnFamilyHandle> handles, RocksDB db,
      List<ColumnFamilyOptions> columnFamilyOptions, List<BloomFilter> bloomFilters,
      DBOptions dbOptions, Statistics statistics) {
    for (int i = handles.size() - 1; i >= 0; i--) {
      handles.get(i).close();
    }
    if (db != null) {
      db.close();
    }
    for (int i = columnFamilyOptions.size() - 1; i >= 0; i--) {
      columnFamilyOptions.get(i).close();
    }
    if (dbOptions != null) {
      dbOptions.close();
    }
    for (int i = bloomFilters.size() - 1; i >= 0; i--) {
      bloomFilters.get(i).close();
    }
    if (statistics != null) {
      statistics.close();
    }
  }

  private void maybeReportStatistics() {
    if (statistics == null) {
      return;
    }
    long now = System.nanoTime();
    if (lastStatisticsSampleNanos != Long.MIN_VALUE
        && now - lastStatisticsSampleNanos < STATISTICS_SAMPLE_INTERVAL_NANOS) {
      return;
    }
    lastStatisticsSampleNanos = now;
    for (TickerMetric metric : TICKER_METRICS) {
      try {
        long current = statistics.getTickerCount(metric.ticker);
        Long previous = lastTickerCounts.put(metric.ticker, current);
        long delta = previous == null || current < previous ? current : current - previous;
        ArchiveMetrics.addRocksDbCounter(metric.metricName, delta);
      } catch (RuntimeException | LinkageError failure) {
        reportStatisticsFailure(failure);
      }
    }
    reportColumnFamilySum(PENDING_COMPACTION_BYTES, "rocksdb_pending_compaction_bytes");
    reportDbProperty(RUNNING_COMPACTIONS, "rocksdb_running_compactions");
    reportDbProperty(RUNNING_FLUSHES, "rocksdb_running_flushes");
  }

  private void reportColumnFamilySum(String property, String metricName) {
    if (disabledStatisticsProperties.contains(property)) {
      return;
    }
    try {
      long total = 0L;
      for (ColumnFamilyHandle handle : allHandles) {
        total = addSaturated(total, db.getLongProperty(handle, property));
      }
      ArchiveMetrics.setRocksDbState(metricName, total);
    } catch (RocksDBException | RuntimeException | LinkageError failure) {
      disabledStatisticsProperties.add(property);
      reportStatisticsFailure(failure);
    }
  }

  private void reportDbProperty(String property, String metricName) {
    if (disabledStatisticsProperties.contains(property)) {
      return;
    }
    try {
      ArchiveMetrics.setRocksDbState(
          metricName, db.getLongProperty(defaultHandle, property));
    } catch (RocksDBException | RuntimeException | LinkageError failure) {
      disabledStatisticsProperties.add(property);
      reportStatisticsFailure(failure);
    }
  }

  private static long addSaturated(long left, long right) {
    return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
  }

  private void reportStatisticsFailure(Throwable failure) {
    if (statisticsFailureReported.compareAndSet(false, true)) {
      logger.warn("archive RocksDB statistics probe failed: {}", failure.getMessage());
    }
  }

  private static final class TickerMetric {

    private final TickerType ticker;
    private final String metricName;

    private TickerMetric(TickerType ticker, String metricName) {
      this.ticker = ticker;
      this.metricName = metricName;
    }
  }

  interface BatchWriter {
    void write(RocksDB db, WriteOptions writeOptions, WriteBatch batch) throws RocksDBException;
  }
}
