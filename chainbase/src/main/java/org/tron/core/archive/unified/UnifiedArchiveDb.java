package org.tron.core.archive.unified;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.BloomFilter;
import org.rocksdb.Cache;
import org.rocksdb.ChecksumType;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.LRUCache;
import org.rocksdb.Options;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.Snapshot;
import org.rocksdb.Statistics;
import org.rocksdb.StatsLevel;
import org.rocksdb.TableFormatConfig;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.ArchiveNativeResourceReleaseException;
import org.tron.core.archive.ArchiveRepairClearPermit;
import org.tron.core.archive.ArchiveRocksIterators;
import org.tron.core.archive.ArchiveRocksReadOptions;
import org.tron.core.archive.ArchiveSnapshotReleaseException;
import org.tron.core.archive.ArchiveStorageAccessException;
import org.tron.core.archive.query.ArchiveQueryLimits;
import org.tron.core.archive.query.ArchiveSnapshotPermit.SnapshotUse;
import org.tron.core.archive.query.HistoricalQueryLimitException;
import org.tron.core.archive.query.QueryContext;
import org.tron.core.archive.txnum.ArchiveBlockRangeCodec;

/**
 * Core UNIFIED_V1 storage owner: one RocksDB, exact column families, atomic block publication, and
 * snapshot reads spanning every logical archive table.
 */
public final class UnifiedArchiveDb implements AutoCloseable {

  static {
    RocksDB.loadLibrary();
  }

  private static final BatchWriter ROCKS_BATCH_WRITER = RocksDB::write;
  private static final byte[] EMPTY_VALUE_BUFFER = new byte[0];
  private static final Logger logger = LoggerFactory.getLogger("archive");
  private static final long SHARED_BLOCK_CACHE_BYTES = 72L * 1024L * 1024L;
  private static final long DB_WRITE_BUFFER_BYTES = 128L * 1024L * 1024L;
  private static final long MAX_TOTAL_WAL_BYTES = 256L * 1024L * 1024L;
  private static final int MAX_OPEN_FILES = 512;
  private static final int COLUMN_FAMILY_COUNT = UnifiedArchiveColumnFamily.values().length + 1;
  // Stabilizes SST encoding across the bundled RocksDB variants. RocksDB MANIFEST downgrade
  // compatibility is separate: a directory written by a newer native library may still require
  // that version or newer even when every SST uses this legacy-compatible format.
  private static final int STABLE_SST_TABLE_FORMAT_VERSION = 0;
  private static final long BACKGROUND_SYNC_BYTES = 1024L * 1024L;
  private static final byte[] MAINTENANCE_REPAIR_VALUE =
      ArchiveBlockRangeCodec.encodeRepairRequired(
          "UNIFIED_V1 maintenance write requires full startup scrub");
  private static final AtomicBoolean nativeDeadlineWarningReported = new AtomicBoolean();
  private static final RocksDbCloser DEFAULT_ROCKS_DB_CLOSER = resolveRocksDbCloser();

  private final Path path;
  private final byte[] schemaChecksum;
  private final DBOptions dbOptions;
  private final List<ColumnFamilyOptions> columnFamilyOptions;
  private final List<BloomFilter> bloomFilters;
  private final Cache blockCache;
  private final List<ColumnFamilyHandle> allHandles;
  private final ColumnFamilyHandle defaultHandle;
  private final EnumMap<UnifiedArchiveColumnFamily, ColumnFamilyHandle> handles;
  private final RocksDB db;
  private final BatchWriter batchWriter;
  // Enabled only by test bridges that assert native I/O behavior.
  private final Statistics statistics;
  private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock(true);
  private final ReentrantLock mutationLock = new ReentrantLock(true);
  private final ConcurrentMap<JournalMutationKey, JournalMutationLock> journalMutationLocks =
      new ConcurrentHashMap<>();
  private final AtomicInteger activeReadViews = new AtomicInteger();
  private final AtomicBoolean productionSealed = new AtomicBoolean();
  private final AtomicBoolean productionWritePermitClaimed = new AtomicBoolean();
  private final ProductionWritePermit productionWritePermit = new ProductionWritePermit();
  private final ThreadLocal<ProductionWritePermit> activeProductionWritePermit =
      new ThreadLocal<>();
  private final RocksDbCloser rocksDbCloser = DEFAULT_ROCKS_DB_CLOSER;

  private boolean closed;
  private Throwable closeFailure;

  private UnifiedArchiveDb(Path path, byte[] schemaChecksum, DBOptions dbOptions,
      List<ColumnFamilyOptions> columnFamilyOptions, List<BloomFilter> bloomFilters,
      Cache blockCache, List<ColumnFamilyHandle> allHandles, RocksDB db, BatchWriter batchWriter,
      Statistics statistics) throws RocksDBException {
    this.path = path;
    this.schemaChecksum = Arrays.copyOf(schemaChecksum, schemaChecksum.length);
    this.dbOptions = dbOptions;
    this.columnFamilyOptions = columnFamilyOptions;
    this.bloomFilters = bloomFilters;
    this.blockCache = blockCache;
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
    return open(path, expectedSchemaChecksum, batchWriter, false);
  }

  static UnifiedArchiveDb openWithStatisticsForTesting(
      Path path, byte[] expectedSchemaChecksum) {
    return open(path, expectedSchemaChecksum, ROCKS_BATCH_WRITER, true);
  }

  private static UnifiedArchiveDb open(Path path, byte[] expectedSchemaChecksum,
      BatchWriter batchWriter, boolean collectStatistics) {
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
    return openDatabase(
        target, immutableSchemaChecksum, false, batchWriter, false, collectStatistics);
  }

  /** Forced-sync journal write; the WAL is always enabled. */
  public void putJournalDurably(byte[] key, byte[] value) {
    runJournalMutation(key, () -> {
      requireProductionWriteAccess();
      putJournalDurablyLocked(key, value);
    });
  }

  private void putJournalDurablyLocked(byte[] key, byte[] value) {
    requireKey(key, "journal");
    requireValue(value, "journal");
    byte[] immutableKey = Arrays.copyOf(key, key.length);
    byte[] immutableValue = Arrays.copyOf(value, value.length);
    try (ReadOptions journalReads = journalReadOptions()) {
      byte[] current = readExpectedJournalValue(
          journalReads, immutableKey, immutableValue, true, "journal row");
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
  public void putJournalBlockDurably(byte[] journalKey, byte[] journalValue,
      byte[] tokenKey, byte[] tokenValue, byte[] acknowledgementKey,
      byte[] acknowledgementValue) {
    runJournalMutation(journalKey, () -> {
      requireProductionWriteAccess();
      putJournalBlockDurablyLocked(
          journalKey, journalValue, tokenKey, tokenValue,
          acknowledgementKey, acknowledgementValue);
    });
  }

  private void putJournalBlockDurablyLocked(byte[] journalKey, byte[] journalValue,
      byte[] tokenKey, byte[] tokenValue, byte[] acknowledgementKey,
      byte[] acknowledgementValue) {
    requireDistinctKeys(journalKey, tokenKey, acknowledgementKey);
    requireValue(journalValue, "journal");
    requireValue(tokenValue, "journal token");
    if (acknowledgementValue != null) {
      requireValue(acknowledgementValue, "journal acknowledgement");
      requireSameToken(tokenValue, acknowledgementValue);
    }
    try (ReadOptions journalReads = journalReadOptions()) {
      byte[] currentJournal = readExpectedJournalValue(
          journalReads, journalKey, journalValue, true, "journal payload");
      byte[] currentToken = readExpectedJournalValue(
          journalReads, tokenKey, tokenValue, true, "journal proof");
      byte[] currentAcknowledgement = readExpectedJournalValue(
          journalReads, acknowledgementKey, acknowledgementValue, true,
          "journal acknowledgement");
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

  /**
   * WAL-only compact acknowledgement. The forced-sync immutable payload remains untouched and is
   * revalidated before publication or during startup recovery.
   */
  public void acknowledgeJournalWalOnly(byte[] journalKey,
      byte[] tokenKey, byte[] expectedTokenValue, byte[] acknowledgementKey,
      byte[] acknowledgementValue) {
    runJournalMutation(journalKey, () -> {
      requireProductionWriteAccess();
      acknowledgeJournalWalOnlyLocked(
          journalKey, tokenKey, expectedTokenValue, acknowledgementKey, acknowledgementValue);
    });
  }

  private void acknowledgeJournalWalOnlyLocked(byte[] journalKey,
      byte[] tokenKey, byte[] expectedTokenValue,
      byte[] acknowledgementKey, byte[] acknowledgementValue) {
    requireDistinctKeys(journalKey, tokenKey, acknowledgementKey);
    requireValue(expectedTokenValue, "journal token");
    requireValue(acknowledgementValue, "journal acknowledgement");
    requireSameToken(expectedTokenValue, acknowledgementValue);
    try (ReadOptions journalReads = journalReadOptions()) {
      requireJournalValue(journalReads, tokenKey, expectedTokenValue);
      byte[] current = readExpectedJournalValue(
          journalReads, acknowledgementKey, acknowledgementValue, false,
          "journal acknowledgement");
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
  public void deleteJournalBlockDurably(byte[] journalKey, byte[] journalValue,
      byte[] tokenKey, byte[] tokenValue, byte[] acknowledgementKey,
      byte[] acknowledgementValue) {
    runJournalMutation(journalKey, () -> {
      requireProductionWriteAccess();
      deleteJournalBlockDurablyLocked(
          journalKey, journalValue, tokenKey, tokenValue,
          acknowledgementKey, acknowledgementValue);
    });
  }

  private void deleteJournalBlockDurablyLocked(byte[] journalKey, byte[] journalValue,
      byte[] tokenKey, byte[] tokenValue, byte[] acknowledgementKey,
      byte[] acknowledgementValue) {
    requireDistinctKeys(journalKey, tokenKey, acknowledgementKey);
    requireValue(journalValue, "journal");
    requireValue(tokenValue, "journal token");
    if (acknowledgementValue != null) {
      requireValue(acknowledgementValue, "journal acknowledgement");
    }
    try (ReadOptions journalReads = journalReadOptions()) {
      requireJournalValue(journalReads, journalKey, journalValue);
      requireJournalValue(journalReads, tokenKey, tokenValue);
      requireOptionalJournalValue(
          journalReads, acknowledgementKey, acknowledgementValue);
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

  /** Forced-sync compare-and-delete without retaining a caller and DB copy of the payload. */
  public void deleteVerifiedJournalBlockDurably(byte[] journalKey,
      UnifiedArchiveJournalVerifier verifier, long maxPayloadBytes,
      byte[] tokenKey, byte[] tokenValue, byte[] acknowledgementKey,
      byte[] acknowledgementValue) {
    runJournalMutation(journalKey, () -> {
      requireProductionWriteAccess();
      deleteVerifiedJournalBlockDurablyLocked(
          journalKey, verifier, maxPayloadBytes, tokenKey, tokenValue,
          acknowledgementKey, acknowledgementValue);
    });
  }

  private void deleteVerifiedJournalBlockDurablyLocked(byte[] journalKey,
      UnifiedArchiveJournalVerifier verifier, long maxPayloadBytes,
      byte[] tokenKey, byte[] tokenValue, byte[] acknowledgementKey,
      byte[] acknowledgementValue) {
    requireDistinctKeys(journalKey, tokenKey, acknowledgementKey);
    if (verifier == null) {
      throw new ArchiveException("UNIFIED_V1 journal verifier is required");
    }
    int admittedPayloadBytes = admitJournalPayloadBytes(verifier, maxPayloadBytes);
    requireValue(tokenValue, "journal token");
    if (acknowledgementValue != null) {
      requireValue(acknowledgementValue, "journal acknowledgement");
    }
    try (ReadOptions journalReads = journalReadOptions()) {
      requireJournalValue(journalReads, tokenKey, tokenValue);
      requireOptionalJournalValue(
          journalReads, acknowledgementKey, acknowledgementValue);
      requireJournalPayload(journalReads, journalKey, verifier, admittedPayloadBytes);
      try (WriteBatch batch = new WriteBatch();
           WriteOptions writeOptions = createWriteOptions(true)) {
        ColumnFamilyHandle inflight = handle(UnifiedArchiveColumnFamily.INFLIGHT);
        batch.delete(inflight, journalKey);
        batch.delete(inflight, tokenKey);
        batch.delete(inflight, acknowledgementKey);
        batchWriter.write(db, writeOptions, batch);
      }
    } catch (RocksDBException e) {
      throw new ArchiveException("UNIFIED_V1 verified journal bundle delete failed", e);
    }
  }

  /** Forced-sync operational metadata write for activation and repair state. */
  public void putMetaDurably(byte[] key, byte[] value) {
    runMutation(() -> {
      requireProductionWriteAccess();
      putMetaDurablyLocked(key, value);
    });
  }

  private void putMetaDurablyLocked(byte[] key, byte[] value) {
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
  public void deleteMetaDurably(byte[] key) {
    runMutation(() -> {
      requireProductionWriteAccess();
      deleteMetaDurablyLocked(key);
    });
  }

  private void deleteMetaDurablyLocked(byte[] key) {
    requireKey(key, "meta");
    byte[] immutableKey = Arrays.copyOf(key, key.length);
    if (Arrays.equals(immutableKey, UnifiedArchiveManifest.key())
        || Arrays.equals(immutableKey, UnifiedArchiveManifest.publishedCursorKey())
        || Arrays.equals(immutableKey, ArchiveBlockRangeCodec.repairRequiredKey())) {
      throw new ArchiveException("UNIFIED_V1 reserved meta row is immutable through this API");
    }
    try (WriteOptions writeOptions = createWriteOptions(true)) {
      db.delete(handle(UnifiedArchiveColumnFamily.META), writeOptions, immutableKey);
    } catch (RocksDBException e) {
      throw new ArchiveException("UNIFIED_V1 meta delete failed", e);
    }
  }

  /** Clears repair evidence only for the recovery owner after complete startup validation. */
  public void deleteRepairMetaDurably(byte[] key, ArchiveRepairClearPermit permit) {
    if (permit == null) {
      throw new ArchiveException("UNIFIED_V1 repair clear permit is required");
    }
    runMutation(() -> {
      requireProductionWriteAccess();
      deleteRepairMetaDurablyLocked(key);
    });
  }

  private void deleteRepairMetaDurablyLocked(byte[] key) {
    requireKey(key, "repair meta");
    byte[] immutableKey = Arrays.copyOf(key, key.length);
    if (!Arrays.equals(immutableKey, ArchiveBlockRangeCodec.repairRequiredKey())) {
      throw new ArchiveException("UNIFIED_V1 repair clear key is invalid");
    }
    try (WriteOptions writeOptions = createWriteOptions(true)) {
      db.delete(handle(UnifiedArchiveColumnFamily.META), writeOptions, immutableKey);
    } catch (RocksDBException e) {
      throw new ArchiveException("UNIFIED_V1 repair evidence delete failed", e);
    }
  }

  /** Point read used by typed adapters outside a long-lived snapshot. */
  public byte[] get(UnifiedArchiveColumnFamily columnFamily, byte[] key) {
    return callRead(() -> getLocked(columnFamily, key));
  }

  private byte[] getLocked(UnifiedArchiveColumnFamily columnFamily, byte[] key) {
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

  /**
   * Forced-sync restricted cross-CF batch for offline repair and test fault injection. The same
   * batch marks the archive repair-required so the next normal startup must complete a full scrub
   * before clearing the evidence.
   */
  void writeMaintenanceAtomically(UnifiedArchiveMaintenanceBatch mutations) {
    runMutation(() -> writeMaintenanceAtomicallyLocked(mutations));
  }

  private void writeMaintenanceAtomicallyLocked(UnifiedArchiveMaintenanceBatch mutations) {
    if (mutations == null) {
      throw new ArchiveException("UNIFIED_V1 maintenance batch is required");
    }
    if (productionSealed.get()) {
      throw new ArchiveException("UNIFIED_V1 maintenance writes are disabled in production mode");
    }
    byte[] repairRequiredKey = ArchiveBlockRangeCodec.repairRequiredKey();
    boolean repairAlreadyRequired = getLocked(
        UnifiedArchiveColumnFamily.META, repairRequiredKey) != null;
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
      if (!repairAlreadyRequired) {
        batch.put(handle(UnifiedArchiveColumnFamily.META),
            repairRequiredKey, MAINTENANCE_REPAIR_VALUE);
      }
      batchWriter.write(db, writeOptions, batch);
    } catch (RocksDBException e) {
      throw new ArchiveException("UNIFIED_V1 maintenance batch failed", e);
    }
  }

  /**
   * Claims the sole DB-bound owner capability used for mutations and coordinated query snapshots
   * after production sealing.
   */
  public ProductionWritePermit claimProductionWritePermit() {
    return callMutation(() -> {
      if (productionSealed.get()) {
        throw new ArchiveException("UNIFIED_V1 production write permit claim is sealed");
      }
      if (!productionWritePermitClaimed.compareAndSet(false, true)) {
        throw new ArchiveException("UNIFIED_V1 production write permit is already claimed");
      }
      return productionWritePermit;
    });
  }

  /** Runs one typed persistent mutation with this DB's production capability. */
  public void withProductionWritePermit(ProductionWritePermit permit, Runnable mutation) {
    if (mutation == null) {
      throw new NullPointerException("mutation");
    }
    if (permit != productionWritePermit || !productionWritePermitClaimed.get()) {
      throw new ArchiveException("UNIFIED_V1 production write permit does not own this DB");
    }
    if (activeProductionWritePermit.get() != null) {
      throw new ArchiveException("nested UNIFIED_V1 production write permits are not supported");
    }
    installThreadLocal(activeProductionWritePermit, permit);
    try {
      mutation.run();
    } finally {
      activeProductionWritePermit.remove();
    }
  }

  static <T> void installThreadLocal(ThreadLocal<T> threadLocal, T value) {
    try {
      threadLocal.set(value);
    } catch (RuntimeException | Error failure) {
      try {
        threadLocal.remove();
      } catch (RuntimeException | Error cleanupFailure) {
        addSuppressedSafely(failure, cleanupFailure);
      }
      throw failure;
    }
  }

  /** Irreversibly disables unowned and offline-maintenance writes for this production DB owner. */
  public void sealForProduction() {
    runMutation(() -> productionSealed.set(true));
  }

  /**
   * Publishes one block in a single cross-CF WriteBatch. Cursor and marker become visible with all
   * index/temporal rows at the same sequence, while the matched durable journal disappears.
   */
  public void publishBlockAtomically(UnifiedArchivePublish publish,
      boolean publishSync) {
    if (publish == null) {
      throw new ArchiveException("UNIFIED_V1 publish description is required");
    }
    runPublishMutation(publish.journalKey(), () -> {
      requireProductionWriteAccess();
      publishBlockAtomicallyLocked(publish, publishSync);
    });
  }

  private void publishBlockAtomicallyLocked(UnifiedArchivePublish publish,
      boolean publishSync) {
    if (publish == null) {
      throw new ArchiveException("UNIFIED_V1 publish description is required");
    }
    try (ReadOptions journalReads = journalReadOptions()) {
      requireJournalValue(
          journalReads, publish.journalKey(), publish.expectedJournalValue());
      requireJournalValue(
          journalReads, publish.journalTokenKey(), publish.expectedJournalTokenValue());
      requireJournalValue(
          journalReads, publish.acknowledgementKey(), publish.expectedAcknowledgementValue());
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
    } catch (RocksDBException e) {
      throw new ArchiveException("UNIFIED_V1 atomic block publish failed", e);
    }
  }

  /** Captures meta, inflight, index, temporal, marker, and commitment at one sequence number. */
  public UnifiedArchiveReadView openReadView() {
    return callRead(() -> openReadViewLocked(true, Long.MAX_VALUE, 0L, null));
  }

  /**
   * Captures one sequence for untrusted historical queries without admitting their random reads
   * into the shared archive block cache used by publication and maintenance point reads.
   */
  UnifiedArchiveReadView openQueryReadView() {
    return openQueryReadView(Long.MAX_VALUE);
  }

  /** Captures one query snapshot and propagates its remaining budget into native RocksDB reads. */
  UnifiedArchiveReadView openQueryReadView(long remainingNanos) {
    requireUnsealedQueryAccess();
    if (remainingNanos < 0L) {
      throw new IllegalArgumentException("query remaining time must be non-negative");
    }
    if (remainingNanos == 0L) {
      throw querySnapshotDeadline();
    }
    long startedNanos = System.nanoTime();
    return callQueryRead(remainingNanos,
        () -> openReadViewLocked(false, remainingNanos, startedNanos, null));
  }

  /** Captures a query snapshot using a freshly sampled budget after lock and snapshot work. */
  public UnifiedArchiveReadView openQueryReadView(QueryContext context) {
    return openQueryReadView(context, null);
  }

  /** Captures a coordinated query snapshot for the DB-bound production owner. */
  public UnifiedArchiveReadView openQueryReadView(QueryContext context,
      ProductionWritePermit productionPermit) {
    if (context == null) {
      throw new NullPointerException("context");
    }
    context.checkDeadline();
    requireProductionQueryAccess(productionPermit);
    SnapshotUse snapshotUse = context.tryClaimSnapshotUse();
    if (snapshotUse == null) {
      throw new ArchiveException(
          "UNIFIED_V1 query snapshot requires an available coordinator permit");
    }
    try {
      return callQueryRead(context,
          () -> openReadViewLocked(false, Long.MAX_VALUE, 0L, context, snapshotUse));
    } catch (RuntimeException | Error failure) {
      if (!ArchiveSnapshotReleaseException.contains(failure)) {
        releaseSnapshotUseAfterOpenFailure(snapshotUse, failure);
      }
      throw failure;
    }
  }

  /**
   * Captures one sequence for validation and maintenance scans without admitting scanned blocks
   * into the point-read cache.
   */
  public UnifiedArchiveReadView openScanView() {
    return callRead(() -> openReadViewLocked(false, Long.MAX_VALUE, 0L, null));
  }

  private UnifiedArchiveReadView openReadViewLocked(boolean fillCache, long remainingNanos,
      long budgetStartedNanos, QueryContext queryContext) {
    return openReadViewLocked(
        fillCache, remainingNanos, budgetStartedNanos, queryContext, null);
  }

  private UnifiedArchiveReadView openReadViewLocked(boolean fillCache, long remainingNanos,
      long budgetStartedNanos, QueryContext queryContext, SnapshotUse snapshotUse) {
    long timeoutNanos = remainingNanos;
    if (queryContext != null) {
      queryContext.checkDeadline();
    } else if (remainingNanos != Long.MAX_VALUE) {
      remainingNanos = remainingBudgetNanos(timeoutNanos, budgetStartedNanos);
      if (remainingNanos == 0L) {
        throw querySnapshotDeadline();
      }
    }
    Snapshot snapshot = db.getSnapshot();
    if (snapshot == null) {
      throw new ArchiveStorageAccessException(
          "UNIFIED_V1 failed to acquire a RocksDB snapshot");
    }
    activeReadViews.incrementAndGet();
    ReadOptions readOptions = null;
    try {
      if (queryContext != null) {
        queryContext.checkDeadline();
        remainingNanos = queryContext.getRemainingNanos();
      } else if (timeoutNanos != Long.MAX_VALUE) {
        remainingNanos = remainingBudgetNanos(timeoutNanos, budgetStartedNanos);
        if (remainingNanos == 0L) {
          throw querySnapshotDeadline();
        }
      }
      readOptions = configureSnapshotReadOptions(
          new ReadOptions(), snapshot, fillCache, remainingNanos);
      if (queryContext != null) {
        queryContext.checkDeadline();
      }
      UnifiedArchiveReadView view = new UnifiedArchiveReadView(
          db, handles, snapshot, readOptions, this::releaseReadView, snapshotUse);
      return view;
    } catch (RuntimeException | Error failure) {
      Throwable cleanupFailure = null;
      if (readOptions != null) {
        cleanupFailure = closeAndCollect(cleanupFailure, readOptions);
      }
      if (cleanupFailure != null) {
        ArchiveSnapshotReleaseException uncertainCleanup =
            new ArchiveSnapshotReleaseException(
                "UNIFIED_V1 snapshot owner cleanup outcome is unknown; restart is required",
                cleanupFailure);
        addSuppressedSafely(uncertainCleanup, failure);
        throw uncertainCleanup;
      }
      if (ArchiveSnapshotReleaseException.contains(failure)) {
        throw failure;
      }
      ArchiveSnapshotReleaseException uncertainRelease = null;
      try {
        db.releaseSnapshot(snapshot);
      } catch (Throwable snapshotFailure) {
        uncertainRelease = new ArchiveSnapshotReleaseException(
            "UNIFIED_V1 snapshot-open cleanup outcome is unknown; restart is required",
            snapshotFailure);
      }
      if (uncertainRelease == null) {
        cleanupFailure = closeAndCollect(cleanupFailure, this::releaseReadView);
      } else {
        addSuppressedSafely(uncertainRelease, failure);
        if (cleanupFailure != null) {
          addSuppressedSafely(uncertainRelease, cleanupFailure);
        }
        throw uncertainRelease;
      }
      if (cleanupFailure != null && failure != cleanupFailure) {
        addSuppressedSafely(failure, cleanupFailure);
      }
      throw failure;
    }
  }

  private static void releaseSnapshotUseAfterOpenFailure(
      SnapshotUse snapshotUse, Throwable openingFailure) {
    try {
      snapshotUse.close();
    } catch (Throwable useFailure) {
      ArchiveSnapshotReleaseException uncertain = new ArchiveSnapshotReleaseException(
          "UNIFIED_V1 query snapshot permit use could not be released", useFailure);
      addSuppressedSafely(uncertain, openingFailure);
      throw uncertain;
    }
  }

  public Path getPath() {
    return path;
  }

  public byte[] getSchemaChecksum() {
    return Arrays.copyOf(schemaChecksum, schemaChecksum.length);
  }

  /** Rejects a snapshot created by another Unified DB instance. */
  public void requireOwnedReadView(UnifiedArchiveReadView view) {
    if (view == null) {
      throw new NullPointerException("view");
    }
    if (!view.isBackedBy(db)) {
      throw new ArchiveException("UNIFIED_V1 snapshot read view belongs to another DB");
    }
  }

  /** True when any archive payload row beyond the immutable manifest is present. */
  public boolean hasArchiveData() {
    return callMutation(this::hasArchiveDataLocked);
  }

  private boolean hasArchiveDataLocked() {
    try (ReadOptions readOptions = createReadOptions(false)) {
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
  public void close() {
    Lock writeLock = lifecycleLock.writeLock();
    writeLock.lock();
    try {
      if (closed) {
        rethrowCloseFailure(closeFailure);
        return;
      }
      int activeViews = activeReadViews.get();
      if (activeViews != 0) {
        throw new ArchiveException("UNIFIED_V1 DB has active snapshot read views: "
            + activeViews);
      }
      closed = true;
      Throwable failure = closeResources(
          allHandles, db, columnFamilyOptions, bloomFilters, blockCache,
          dbOptions, statistics, null, rocksDbCloser);
      closeFailure = failure;
      rethrowCloseFailure(failure);
    } finally {
      writeLock.unlock();
    }
  }

  int ownedBloomFilterCount() {
    return bloomFilters.size();
  }

  int ownedBlockCacheCount() {
    return blockCache == null ? 0 : 1;
  }

  long configuredBlockCacheBytes() {
    return SHARED_BLOCK_CACHE_BYTES;
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

  boolean temporalPayloadMetadataUsesEvictableCache() {
    int optionsIndex = UnifiedArchiveColumnFamily.TEMPORAL_PAYLOAD.ordinal() + 1;
    TableFormatConfig tableFormatConfig =
        columnFamilyOptions.get(optionsIndex).tableFormatConfig();
    return tableFormatConfig instanceof BlockBasedTableConfig
        && !((BlockBasedTableConfig) tableFormatConfig).noBlockCache()
        && ((BlockBasedTableConfig) tableFormatConfig).cacheIndexAndFilterBlocks()
        && !((BlockBasedTableConfig) tableFormatConfig).pinL0FilterAndIndexBlocksInCache();
  }

  boolean usesStableSstTableFormat() {
    for (ColumnFamilyOptions options : columnFamilyOptions) {
      TableFormatConfig tableFormatConfig = options.tableFormatConfig();
      if (!(tableFormatConfig instanceof BlockBasedTableConfig)) {
        return false;
      }
      BlockBasedTableConfig tableConfig = (BlockBasedTableConfig) tableFormatConfig;
      if (tableConfig.formatVersion() != STABLE_SST_TABLE_FORMAT_VERSION
          || tableConfig.checksumType() != ChecksumType.kCRC32c) {
        return false;
      }
    }
    return true;
  }

  boolean usesDynamicLevelCompaction() {
    for (ColumnFamilyOptions options : columnFamilyOptions) {
      if (!options.levelCompactionDynamicLevelBytes()) {
        return false;
      }
    }
    return true;
  }

  boolean temporalPayloadOptimizesFiltersForHits() {
    int optionsIndex = UnifiedArchiveColumnFamily.TEMPORAL_PAYLOAD.ordinal() + 1;
    return columnFamilyOptions.get(optionsIndex).optimizeFiltersForHits();
  }

  int maxBackgroundJobs() {
    return dbOptions.maxBackgroundJobs();
  }

  long dbWriteBufferSize() {
    return dbOptions.dbWriteBufferSize();
  }

  long maxTotalWalSize() {
    return dbOptions.maxTotalWalSize();
  }

  int maxOpenFiles() {
    return dbOptions.maxOpenFiles();
  }

  static boolean nativeReadDeadlineSupported() {
    return ArchiveRocksReadOptions.nativeDeadlineSupported();
  }

  long bytesPerSync() {
    return dbOptions.bytesPerSync();
  }

  long walBytesPerSync() {
    return dbOptions.walBytesPerSync();
  }

  boolean usesDirectIo() {
    return dbOptions.useDirectReads() || dbOptions.useDirectIoForFlushAndCompaction();
  }

  boolean hasStatistics() {
    return statistics != null;
  }

  StatsLevel statisticsLevel() {
    return statistics == null ? null : statistics.statsLevel();
  }

  static WriteOptions createWriteOptions(boolean sync) {
    return configureWriteOptions(new WriteOptions(), sync);
  }

  static WriteOptions configureWriteOptions(WriteOptions options, boolean sync) {
    if (options == null) {
      throw new NullPointerException("options");
    }
    try {
      options.setDisableWAL(false);
      options.setSync(sync);
      return options;
    } catch (RuntimeException | Error failure) {
      Throwable closeFailure = closeAndCollect(null, options);
      if (closeFailure != null) {
        ArchiveNativeResourceReleaseException uncertain =
            unknownNativeRelease("UNIFIED_V1 WriteOptions configuration cleanup", closeFailure);
        addSuppressedSafely(uncertain, failure);
        throw uncertain;
      }
      throw failure;
    }
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
    return openDatabase(target, schemaChecksum, initialize, batchWriter, false, false);
  }

  private static UnifiedArchiveDb openDatabase(Path target, byte[] schemaChecksum,
      boolean initialize, BatchWriter batchWriter, boolean resumeEmptyInitialization) {
    return openDatabase(
        target, schemaChecksum, initialize, batchWriter, resumeEmptyInitialization, false);
  }

  private static UnifiedArchiveDb openDatabase(Path target, byte[] schemaChecksum,
      boolean initialize, BatchWriter batchWriter, boolean resumeEmptyInitialization,
      boolean collectStatistics) {
    Statistics statistics = null;
    DBOptions dbOptions = null;
    Cache blockCache = null;
    List<ColumnFamilyOptions> columnFamilyOptions = new ArrayList<>(COLUMN_FAMILY_COUNT);
    List<BloomFilter> bloomFilters = new ArrayList<>(COLUMN_FAMILY_COUNT);
    List<ColumnFamilyHandle> openedHandles = new ArrayList<>(COLUMN_FAMILY_COUNT);
    RocksDB openedDb = null;
    try {
      statistics = collectStatistics ? new Statistics() : null;
      if (statistics != null) {
        statistics.setStatsLevel(StatsLevel.EXCEPT_DETAILED_TIMERS);
      }
      dbOptions = new DBOptions();
      dbOptions
          .setCreateIfMissing(initialize)
          .setCreateMissingColumnFamilies(initialize)
          .setErrorIfExists(initialize)
          .setParanoidChecks(true)
          .setDbWriteBufferSize(DB_WRITE_BUFFER_BYTES)
          .setMaxTotalWalSize(MAX_TOTAL_WAL_BYTES)
          .setMaxOpenFiles(MAX_OPEN_FILES)
          .setMaxBackgroundJobs(2)
          .setBytesPerSync(BACKGROUND_SYNC_BYTES)
          .setWalBytesPerSync(0L)
          .setUseDirectReads(false)
          .setUseDirectIoForFlushAndCompaction(false);
      if (!nativeReadDeadlineSupported()
          && nativeDeadlineWarningReported.compareAndSet(false, true)) {
        logger.warn("archive RocksDB does not support native query deadlines; "
            + "Java deadline checks remain active around native reads");
      }
      if (statistics != null) {
        dbOptions.setStatistics(statistics);
      }
      blockCache = new LRUCache(SHARED_BLOCK_CACHE_BYTES, -1, false);
      List<ColumnFamilyDescriptor> descriptors =
          descriptors(columnFamilyOptions, bloomFilters, blockCache);
      openedDb = RocksDB.open(dbOptions, target.toString(), descriptors, openedHandles);
      UnifiedArchiveDb opened = new UnifiedArchiveDb(target, schemaChecksum, dbOptions,
          columnFamilyOptions, bloomFilters, blockCache,
          openedHandles, openedDb, batchWriter, statistics);
      if (initialize) {
        opened.installManifest();
      } else if (resumeEmptyInitialization) {
        opened.resumeEmptyManifestIfMissing();
      }
      opened.validateIdentity();
      return opened;
    } catch (RocksDBException e) {
      Throwable cleanupOutcome = closeResources(
          openedHandles, openedDb, columnFamilyOptions, bloomFilters, blockCache,
          dbOptions, statistics, e);
      if (cleanupOutcome != e) {
        rethrowCloseFailure(cleanupOutcome);
      }
      String operation = initialize ? "initialize" : "open";
      throw new ArchiveException("failed to " + operation + " UNIFIED_V1 archive at "
          + target, e);
    } catch (RuntimeException | Error failure) {
      Throwable cleanupOutcome = closeResources(
          openedHandles, openedDb, columnFamilyOptions, bloomFilters, blockCache,
          dbOptions, statistics, failure);
      if (cleanupOutcome != failure) {
        rethrowCloseFailure(cleanupOutcome);
      }
      throw failure;
    }
  }

  private static List<ColumnFamilyDescriptor> descriptors(
      List<ColumnFamilyOptions> optionsOwner, List<BloomFilter> bloomFilterOwner,
      Cache blockCache) {
    List<byte[]> names = expectedColumnFamilyNames();
    List<ColumnFamilyDescriptor> descriptors = new ArrayList<>(names.size());
    for (byte[] name : names) {
      BlockBasedTableConfig tableConfig = new BlockBasedTableConfig()
          .setBlockCache(blockCache)
          .setCacheIndexAndFilterBlocks(true)
          .setPinL0FilterAndIndexBlocksInCache(false)
          .setFormatVersion(STABLE_SST_TABLE_FORMAT_VERSION)
          .setChecksumType(ChecksumType.kCRC32c);
      BloomFilter bloomFilter = new BloomFilter(10, false);
      bloomFilterOwner.add(bloomFilter);
      tableConfig.setFilter(bloomFilter);
      ColumnFamilyOptions options = new ColumnFamilyOptions();
      optionsOwner.add(options);
      options.setLevelCompactionDynamicLevelBytes(true);
      if (Arrays.equals(name, UnifiedArchiveColumnFamily.TEMPORAL_PAYLOAD.nameBytes())) {
        options.setOptimizeFiltersForHits(true);
      }
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
    try (Options options = new Options()) {
      options.setCreateIfMissing(false);
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
    byte[] manifest = readManifestExact();
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
    byte[] manifest = readManifestExact();
    UnifiedArchiveManifest.validate(manifest, schemaChecksum);
  }

  private byte[] readManifestExact() throws RocksDBException {
    byte[] expected = UnifiedArchiveManifest.value(schemaChecksum);
    byte[] manifest = new byte[expected.length];
    int actualBytes = db.get(handle(UnifiedArchiveColumnFamily.META),
        UnifiedArchiveManifest.key(), manifest);
    if (actualBytes == RocksDB.NOT_FOUND) {
      return null;
    }
    if (actualBytes != manifest.length) {
      throw new ArchiveException("UNIFIED_V1 archive manifest has invalid byte length: "
          + actualBytes);
    }
    return manifest;
  }

  private void validateDefaultColumnFamilyEmpty() {
    try (ReadOptions readOptions = new ReadOptions()) {
      readOptions.setFillCache(false);
      try (RocksIterator iterator = db.newIterator(defaultHandle, readOptions)) {
        iterator.seekToFirst();
        ArchiveRocksIterators.requireOk(iterator,
            "UNIFIED_V1 validate empty default column family");
        if (iterator.isValid()) {
          throw new ArchiveException("UNIFIED_V1 default column family must be empty");
        }
      }
    }
  }

  private void requireJournalValue(ReadOptions readOptions, byte[] key, byte[] expectedValue)
      throws RocksDBException {
    byte[] current = readExpectedJournalValue(
        readOptions, key, expectedValue, false, "journal lifecycle row");
    if (current == null) {
      throw new ArchiveException("UNIFIED_V1 publish journal is missing");
    }
    if (!Arrays.equals(current, expectedValue)) {
      throw new ArchiveException("UNIFIED_V1 publish journal changed before publication");
    }
  }

  private void requireJournalPayload(ReadOptions readOptions, byte[] key,
      UnifiedArchiveJournalVerifier verifier, int expectedBytes) throws RocksDBException {
    ColumnFamilyHandle inflight = handle(UnifiedArchiveColumnFamily.INFLIGHT);
    byte[] payload = new byte[expectedBytes];
    int actualBytes = db.get(inflight, readOptions, key, payload);
    if (actualBytes == RocksDB.NOT_FOUND) {
      throw new ArchiveException("UNIFIED_V1 publish journal is missing");
    }
    requireExpectedLength("journal payload", expectedBytes, actualBytes);
    verifier.requireMatches(key, payload);
  }

  private static int admitJournalPayloadBytes(
      UnifiedArchiveJournalVerifier verifier, long maxPayloadBytes) {
    if (maxPayloadBytes <= 0L) {
      throw new ArchiveException("UNIFIED_V1 journal payload limit must be positive");
    }
    long expectedPayloadBytes = verifier.expectedPayloadBytes();
    if (expectedPayloadBytes <= 0L || expectedPayloadBytes > maxPayloadBytes
        || expectedPayloadBytes > Integer.MAX_VALUE) {
      throw new ArchiveException("UNIFIED_V1 journal proof exceeds admitted payload limit "
          + maxPayloadBytes + ": payloadBytes=" + expectedPayloadBytes);
    }
    return (int) expectedPayloadBytes;
  }

  private void requireOptionalJournalValue(ReadOptions readOptions,
      byte[] key, byte[] expectedValue) throws RocksDBException {
    byte[] current = readExpectedJournalValue(
        readOptions, key, expectedValue, false, "optional journal lifecycle row");
    if (!Arrays.equals(current, expectedValue)) {
      throw new ArchiveException("UNIFIED_V1 journal lifecycle row changed");
    }
  }

  private byte[] readExpectedJournalValue(ReadOptions readOptions, byte[] key,
      byte[] expectedValue, boolean probeBeforeAllocation, String what)
      throws RocksDBException {
    ColumnFamilyHandle inflight = handle(UnifiedArchiveColumnFamily.INFLIGHT);
    if (expectedValue == null) {
      int actualBytes = db.get(inflight, readOptions, key, EMPTY_VALUE_BUFFER);
      if (actualBytes == RocksDB.NOT_FOUND) {
        return null;
      }
      throw new ArchiveException("UNIFIED_V1 " + what
          + " exists unexpectedly: valueBytes=" + actualBytes);
    }
    if (probeBeforeAllocation) {
      int actualBytes = db.get(inflight, readOptions, key, EMPTY_VALUE_BUFFER);
      if (actualBytes == RocksDB.NOT_FOUND) {
        return null;
      }
      requireExpectedLength(what, expectedValue.length, actualBytes);
    }
    byte[] current = new byte[expectedValue.length];
    int actualBytes = db.get(inflight, readOptions, key, current);
    if (actualBytes == RocksDB.NOT_FOUND) {
      return null;
    }
    requireExpectedLength(what, expectedValue.length, actualBytes);
    return current;
  }

  private static void requireExpectedLength(
      String what, int expectedBytes, int actualBytes) {
    if (actualBytes != expectedBytes) {
      throw new ArchiveException("UNIFIED_V1 " + what + " byte length changed: expectedBytes="
          + expectedBytes + ", actualBytes=" + actualBytes);
    }
  }

  private static ReadOptions journalReadOptions() {
    return createReadOptions(false);
  }

  private static ReadOptions createReadOptions(boolean fillCache) {
    return configureReadOptions(new ReadOptions(), fillCache);
  }

  static ReadOptions configureReadOptions(ReadOptions options, boolean fillCache) {
    if (options == null) {
      throw new NullPointerException("options");
    }
    try {
      options.setFillCache(fillCache);
      return options;
    } catch (RuntimeException | Error failure) {
      Throwable closeFailure = closeAndCollect(null, options);
      if (closeFailure != null) {
        ArchiveNativeResourceReleaseException uncertain =
            unknownNativeRelease("UNIFIED_V1 ReadOptions configuration cleanup", closeFailure);
        addSuppressedSafely(uncertain, failure);
        throw uncertain;
      }
      throw failure;
    }
  }

  static ReadOptions configureSnapshotReadOptions(ReadOptions options, Snapshot snapshot,
      boolean fillCache, long remainingNanos) {
    if (options == null || snapshot == null) {
      throw new NullPointerException("options/snapshot");
    }
    try {
      options.setSnapshot(snapshot);
      options.setFillCache(fillCache);
      ArchiveRocksReadOptions.configureNativeDeadline(options, remainingNanos);
      return options;
    } catch (RuntimeException | Error failure) {
      try {
        options.close();
      } catch (Throwable closeFailure) {
        ArchiveSnapshotReleaseException uncertain = new ArchiveSnapshotReleaseException(
            "UNIFIED_V1 snapshot ReadOptions cleanup outcome is unknown; restart is required",
            closeFailure);
        addSuppressedSafely(uncertain, failure);
        throw uncertain;
      }
      throw failure;
    }
  }

  private ColumnFamilyHandle handle(UnifiedArchiveColumnFamily columnFamily) {
    return handles.get(columnFamily);
  }

  private void runMutation(Runnable operation) {
    callMutation(() -> {
      operation.run();
      return null;
    });
  }

  private void runJournalMutation(byte[] journalKey, Runnable operation) {
    callJournalMutation(journalKey, false, () -> {
      operation.run();
      return null;
    });
  }

  private void runPublishMutation(byte[] journalKey, Runnable operation) {
    callJournalMutation(journalKey, true, () -> {
      operation.run();
      return null;
    });
  }

  private <T> T callMutation(Supplier<T> operation) {
    Lock readLock = lifecycleLock.readLock();
    readLock.lock();
    try {
      mutationLock.lock();
      try {
        requireOpen();
        return operation.get();
      } finally {
        mutationLock.unlock();
      }
    } finally {
      readLock.unlock();
    }
  }

  /**
   * Serializes lifecycle changes and same-journal compare/write sequences without coupling tail
   * journal durability to an unrelated block publication stalled in RocksDB.
   */
  private <T> T callJournalMutation(byte[] journalKey, boolean serializePublishedState,
      Supplier<T> operation) {
    requireKey(journalKey, "journal mutation");
    JournalMutationKey identity = new JournalMutationKey(journalKey);
    Lock lifecycleReadLock = lifecycleLock.readLock();
    lifecycleReadLock.lock();
    JournalMutationLock journalLock = null;
    boolean publishedStateLocked = false;
    boolean journalLocked = false;
    try {
      if (serializePublishedState) {
        mutationLock.lock();
        publishedStateLocked = true;
      }
      journalLock = retainJournalMutationLock(identity);
      journalLock.lock.lock();
      journalLocked = true;
      requireOpen();
      return operation.get();
    } finally {
      if (journalLocked) {
        journalLock.lock.unlock();
      }
      if (journalLock != null) {
        releaseJournalMutationLock(identity, journalLock);
      }
      if (publishedStateLocked) {
        mutationLock.unlock();
      }
      lifecycleReadLock.unlock();
    }
  }

  private JournalMutationLock retainJournalMutationLock(JournalMutationKey identity) {
    return journalMutationLocks.compute(identity, (ignored, current) -> {
      JournalMutationLock retained = current == null ? new JournalMutationLock() : current;
      retained.references++;
      return retained;
    });
  }

  private void releaseJournalMutationLock(JournalMutationKey identity,
      JournalMutationLock retained) {
    journalMutationLocks.computeIfPresent(identity, (ignored, current) -> {
      if (current != retained || current.references <= 0) {
        throw new IllegalStateException("UNIFIED_V1 journal mutation lock accounting mismatch");
      }
      current.references--;
      return current.references == 0 ? null : current;
    });
  }

  private <T> T callRead(Supplier<T> operation) {
    Lock readLock = lifecycleLock.readLock();
    readLock.lock();
    try {
      requireOpen();
      return operation.get();
    } finally {
      readLock.unlock();
    }
  }

  private <T> T callQueryRead(long remainingNanos, Supplier<T> operation) {
    Lock readLock = lifecycleLock.readLock();
    boolean acquired;
    try {
      if (remainingNanos == Long.MAX_VALUE) {
        readLock.lockInterruptibly();
        acquired = true;
      } else {
        acquired = readLock.tryLock(remainingNanos, TimeUnit.NANOSECONDS);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HistoricalQueryLimitException(
          HistoricalQueryLimitException.Reason.RESOURCE_EXHAUSTED,
          HistoricalQueryLimitException.Limit.INTERRUPTED,
          ArchiveQueryLimits.UNLIMITED,
          ArchiveQueryLimits.UNLIMITED,
          "interrupted while opening archive query snapshot", e);
    }
    if (!acquired) {
      throw querySnapshotDeadline();
    }
    try {
      requireOpen();
      return operation.get();
    } finally {
      readLock.unlock();
    }
  }

  private <T> T callQueryRead(QueryContext context, Supplier<T> operation) {
    context.checkDeadline();
    long remainingNanos = context.getRemainingNanos();
    Lock readLock = lifecycleLock.readLock();
    boolean acquired;
    try {
      if (remainingNanos == Long.MAX_VALUE) {
        readLock.lockInterruptibly();
        acquired = true;
      } else {
        acquired = readLock.tryLock(remainingNanos, TimeUnit.NANOSECONDS);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HistoricalQueryLimitException(
          HistoricalQueryLimitException.Reason.RESOURCE_EXHAUSTED,
          HistoricalQueryLimitException.Limit.INTERRUPTED,
          ArchiveQueryLimits.UNLIMITED,
          ArchiveQueryLimits.UNLIMITED,
          "interrupted while opening archive query snapshot", e);
    }
    if (!acquired) {
      throw context.deadlineExceeded();
    }
    try {
      context.checkDeadline();
      requireOpen();
      return operation.get();
    } finally {
      readLock.unlock();
    }
  }

  private static HistoricalQueryLimitException querySnapshotDeadline() {
    return new HistoricalQueryLimitException(
        HistoricalQueryLimitException.Reason.DEADLINE,
        HistoricalQueryLimitException.Limit.DEADLINE,
        "historical query deadline exceeded while opening archive query snapshot");
  }

  private static long remainingBudgetNanos(long timeoutNanos, long startedNanos) {
    long elapsed = Math.max(0L, System.nanoTime() - startedNanos);
    return elapsed >= timeoutNanos ? 0L : timeoutNanos - elapsed;
  }

  private void releaseReadView() {
    int remaining = activeReadViews.decrementAndGet();
    if (remaining < 0) {
      activeReadViews.incrementAndGet();
      throw new ArchiveException("UNIFIED_V1 snapshot read view accounting underflow");
    }
  }

  private void requireOpen() {
    if (closed) {
      throw new ArchiveException("UNIFIED_V1 DB is closed");
    }
  }

  private void requireProductionWriteAccess() {
    if (productionSealed.get()
        && activeProductionWritePermit.get() != productionWritePermit) {
      throw new ArchiveException("UNIFIED_V1 production mutation requires its write permit");
    }
  }

  private void requireProductionQueryAccess(ProductionWritePermit permit) {
    if (productionSealed.get()
        && (permit != productionWritePermit || !productionWritePermitClaimed.get())) {
      throw new ArchiveException(
          "UNIFIED_V1 production query snapshot requires its DB owner permit");
    }
  }

  private void requireUnsealedQueryAccess() {
    if (productionSealed.get()) {
      throw new ArchiveException(
          "UNIFIED_V1 production query snapshot requires coordinated owner access");
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

  private static Throwable closeResources(List<ColumnFamilyHandle> handles, RocksDB db,
      List<ColumnFamilyOptions> columnFamilyOptions, List<BloomFilter> bloomFilters,
      Cache blockCache, DBOptions dbOptions, Statistics statistics, Throwable failure) {
    return closeResources(handles, db, columnFamilyOptions, bloomFilters, blockCache,
        dbOptions, statistics, failure, DEFAULT_ROCKS_DB_CLOSER);
  }

  static Throwable closeResources(List<ColumnFamilyHandle> handles, RocksDB db,
      List<ColumnFamilyOptions> columnFamilyOptions, List<BloomFilter> bloomFilters,
      Cache blockCache, DBOptions dbOptions, Statistics statistics, Throwable failure,
      RocksDbCloser rocksDbCloser) {
    Throwable handleFailure = null;
    for (int i = handles.size() - 1; i >= 0; i--) {
      handleFailure = closeAndCollect(handleFailure, handles.get(i));
    }
    if (handleFailure != null) {
      return unknownNativeRelease(
          "UNIFIED_V1 column-family handle release", handleFailure, failure);
    }

    if (db != null) {
      Throwable dbFailure = closeRocksDbAndCollect(null, db, rocksDbCloser);
      if (dbFailure != null) {
        return unknownNativeRelease("UNIFIED_V1 RocksDB release", dbFailure, failure);
      }
    }

    Throwable columnFamilyOptionsFailure = null;
    for (int i = columnFamilyOptions.size() - 1; i >= 0; i--) {
      columnFamilyOptionsFailure =
          closeAndCollect(columnFamilyOptionsFailure, columnFamilyOptions.get(i));
    }
    Throwable dbOptionsFailure = null;
    if (dbOptions != null) {
      dbOptionsFailure = closeAndCollect(null, dbOptions);
    }

    Throwable lowerFailure = null;
    if (columnFamilyOptionsFailure == null) {
      for (int i = bloomFilters.size() - 1; i >= 0; i--) {
        lowerFailure = closeAndCollect(lowerFailure, bloomFilters.get(i));
      }
      if (blockCache != null) {
        lowerFailure = closeAndCollect(lowerFailure, blockCache);
      }
    }
    if (dbOptionsFailure == null && statistics != null) {
      lowerFailure = closeAndCollect(lowerFailure, statistics);
    }

    Throwable nativeFailure = columnFamilyOptionsFailure;
    if (dbOptionsFailure != null) {
      nativeFailure = collectFailure(nativeFailure, dbOptionsFailure);
    }
    if (lowerFailure != null) {
      nativeFailure = collectFailure(nativeFailure, lowerFailure);
    }
    if (nativeFailure != null) {
      return unknownNativeRelease(
          "UNIFIED_V1 native option resource release", nativeFailure, failure);
    }
    return failure;
  }

  private static Throwable closeRocksDbAndCollect(Throwable firstFailure, RocksDB db,
      RocksDbCloser rocksDbCloser) {
    try {
      rocksDbCloser.close(db);
      return firstFailure;
    } catch (Throwable failure) {
      if (firstFailure == null) {
        return failure;
      }
      if (firstFailure != failure) {
        addSuppressedSafely(firstFailure, failure);
      }
      return firstFailure;
    }
  }

  private static Throwable collectFailure(Throwable firstFailure, Throwable failure) {
    if (firstFailure == null) {
      return failure;
    }
    if (firstFailure != failure) {
      addSuppressedSafely(firstFailure, failure);
    }
    return firstFailure;
  }

  private static ArchiveNativeResourceReleaseException unknownNativeRelease(
      String operation, Throwable releaseFailure) {
    return new ArchiveNativeResourceReleaseException(
        operation + " outcome is unknown; restart is required", releaseFailure);
  }

  private static ArchiveNativeResourceReleaseException unknownNativeRelease(
      String operation, Throwable releaseFailure, Throwable priorFailure) {
    ArchiveNativeResourceReleaseException uncertain =
        unknownNativeRelease(operation, releaseFailure);
    if (priorFailure != null) {
      addSuppressedSafely(uncertain, priorFailure);
    }
    return uncertain;
  }

  private static RocksDbCloser resolveRocksDbCloser() {
    MethodHandles.Lookup lookup = MethodHandles.publicLookup();
    MethodType closeType = MethodType.methodType(void.class);
    MethodHandle closeHandle;
    try {
      closeHandle = lookup.findVirtual(RocksDB.class, "closeE", closeType);
    } catch (NoSuchMethodException missingCloseE) {
      try {
        closeHandle = lookup.findVirtual(RocksDB.class, "close", closeType);
      } catch (NoSuchMethodException | IllegalAccessException impossible) {
        throw new ExceptionInInitializerError(impossible);
      }
    } catch (IllegalAccessException inaccessibleCloseE) {
      throw new ExceptionInInitializerError(inaccessibleCloseE);
    }
    MethodHandle resolvedCloseHandle = closeHandle;
    return rocksDb -> {
      resolvedCloseHandle.invokeExact(rocksDb);
    };
  }

  static Throwable closeAndCollect(Throwable firstFailure, AutoCloseable resource) {
    try {
      resource.close();
      return firstFailure;
    } catch (Throwable failure) {
      if (firstFailure == null) {
        return failure;
      }
      if (firstFailure != failure) {
        addSuppressedSafely(firstFailure, failure);
      }
      return firstFailure;
    }
  }

  static void rethrowCloseFailure(Throwable failure) {
    if (failure == null) {
      return;
    }
    if (failure instanceof RuntimeException) {
      throw (RuntimeException) failure;
    }
    if (failure instanceof Error) {
      throw (Error) failure;
    }
    throw new ArchiveException("UNIFIED_V1 resource close failed", failure);
  }

  private static void addSuppressedSafely(Throwable firstFailure, Throwable failure) {
    if (firstFailure == failure) {
      return;
    }
    try {
      firstFailure.addSuppressed(failure);
    } catch (Throwable ignored) {
      // Continue releasing native resources even when suppression cannot allocate.
    }
  }

  /** Opaque identity-bound production-owner capability; only this DB can mint and validate it. */
  public static final class ProductionWritePermit {

    private ProductionWritePermit() {
    }
  }

  private static final class JournalMutationKey {

    private final byte[] bytes;
    private final int hashCode;

    private JournalMutationKey(byte[] bytes) {
      this.bytes = Arrays.copyOf(bytes, bytes.length);
      this.hashCode = Arrays.hashCode(this.bytes);
    }

    @Override
    public boolean equals(Object other) {
      return this == other || other instanceof JournalMutationKey
          && Arrays.equals(bytes, ((JournalMutationKey) other).bytes);
    }

    @Override
    public int hashCode() {
      return hashCode;
    }
  }

  private static final class JournalMutationLock {

    private final ReentrantLock lock = new ReentrantLock(true);
    private int references;
  }

  @FunctionalInterface
  interface RocksDbCloser {

    void close(RocksDB rocksDb) throws Throwable;
  }

  interface BatchWriter {
    void write(RocksDB db, WriteOptions writeOptions, WriteBatch batch) throws RocksDBException;
  }
}
