package org.tron.core.archive.unified;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
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
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.tron.core.archive.ArchiveException;
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

  private final Path path;
  private final byte[] schemaChecksum;
  private final DBOptions dbOptions;
  private final List<ColumnFamilyOptions> columnFamilyOptions;
  private final List<ColumnFamilyHandle> allHandles;
  private final ColumnFamilyHandle defaultHandle;
  private final EnumMap<UnifiedArchiveColumnFamily, ColumnFamilyHandle> handles;
  private final RocksDB db;
  private final BatchWriter batchWriter;

  private boolean closed;
  private int activeReadViews;

  private UnifiedArchiveDb(Path path, byte[] schemaChecksum, DBOptions dbOptions,
      List<ColumnFamilyOptions> columnFamilyOptions, List<ColumnFamilyHandle> allHandles,
      RocksDB db, BatchWriter batchWriter) throws RocksDBException {
    this.path = path;
    this.schemaChecksum = Arrays.copyOf(schemaChecksum, schemaChecksum.length);
    this.dbOptions = dbOptions;
    this.columnFamilyOptions = columnFamilyOptions;
    this.allHandles = allHandles;
    this.defaultHandle = allHandles.get(0);
    this.handles = mapHandles(allHandles);
    this.db = db;
    this.batchWriter = batchWriter;
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

  /** Forced-sync compare-and-replace used to acknowledge one durable journal row. */
  public synchronized void replaceJournalDurably(byte[] key, byte[] expectedValue,
      byte[] newValue) {
    requireOpen();
    requireKey(key, "journal");
    requireValue(expectedValue, "journal expected");
    requireValue(newValue, "journal replacement");
    byte[] immutableKey = Arrays.copyOf(key, key.length);
    byte[] immutableExpected = Arrays.copyOf(expectedValue, expectedValue.length);
    byte[] immutableReplacement = Arrays.copyOf(newValue, newValue.length);
    try {
      requireJournalValue(immutableKey, immutableExpected);
      try (WriteOptions writeOptions = createWriteOptions(true)) {
        db.put(handle(UnifiedArchiveColumnFamily.INFLIGHT), writeOptions,
            immutableKey, immutableReplacement);
      }
    } catch (RocksDBException e) {
      throw new ArchiveException("UNIFIED_V1 journal replace failed", e);
    }
  }

  /** Forced-sync compare-and-delete used by journal rollback paths outside publication. */
  public synchronized void deleteJournalDurably(byte[] key, byte[] expectedValue) {
    requireOpen();
    requireKey(key, "journal");
    requireValue(expectedValue, "journal");
    byte[] immutableKey = Arrays.copyOf(key, key.length);
    byte[] immutableExpectedValue = Arrays.copyOf(expectedValue, expectedValue.length);
    try {
      requireJournalValue(immutableKey, immutableExpectedValue);
      try (WriteOptions writeOptions = createWriteOptions(true)) {
        db.delete(handle(UnifiedArchiveColumnFamily.INFLIGHT), writeOptions, immutableKey);
      }
    } catch (RocksDBException e) {
      throw new ArchiveException("UNIFIED_V1 journal delete failed", e);
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
        batchWriter.write(db, writeOptions, batch);
      }
    } catch (RocksDBException e) {
      throw new ArchiveException("UNIFIED_V1 atomic block publish failed", e);
    }
  }

  /** Captures meta, inflight, index, temporal, marker, and commitment at one sequence number. */
  public synchronized UnifiedArchiveReadView openReadView() {
    requireOpen();
    Snapshot snapshot = db.getSnapshot();
    ReadOptions readOptions = null;
    try {
      readOptions = new ReadOptions().setSnapshot(snapshot);
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
    for (UnifiedArchiveColumnFamily columnFamily : UnifiedArchiveColumnFamily.values()) {
      try (RocksIterator iterator = db.newIterator(handle(columnFamily))) {
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

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    if (activeReadViews != 0) {
      throw new ArchiveException("UNIFIED_V1 DB has active snapshot read views: "
          + activeReadViews);
    }
    closed = true;
    closeResources(allHandles, db, columnFamilyOptions, dbOptions);
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
    DBOptions dbOptions = new DBOptions()
        .setCreateIfMissing(initialize)
        .setCreateMissingColumnFamilies(initialize)
        .setErrorIfExists(initialize)
        .setParanoidChecks(true);
    List<ColumnFamilyOptions> columnFamilyOptions = new ArrayList<>();
    List<ColumnFamilyDescriptor> descriptors = descriptors(columnFamilyOptions);
    List<ColumnFamilyHandle> openedHandles = new ArrayList<>();
    RocksDB openedDb = null;
    try {
      openedDb = RocksDB.open(dbOptions, target.toString(), descriptors, openedHandles);
      UnifiedArchiveDb opened = new UnifiedArchiveDb(target, schemaChecksum, dbOptions,
          columnFamilyOptions, openedHandles, openedDb, batchWriter);
      if (initialize) {
        opened.installManifest();
      } else if (resumeEmptyInitialization) {
        opened.resumeEmptyManifestIfMissing();
      }
      opened.validateIdentity();
      return opened;
    } catch (RocksDBException e) {
      closeResources(openedHandles, openedDb, columnFamilyOptions, dbOptions);
      String operation = initialize ? "initialize" : "open";
      throw new ArchiveException("failed to " + operation + " UNIFIED_V1 archive at "
          + target, e);
    } catch (RuntimeException | Error failure) {
      closeResources(openedHandles, openedDb, columnFamilyOptions, dbOptions);
      throw failure;
    }
  }

  private static List<ColumnFamilyDescriptor> descriptors(
      List<ColumnFamilyOptions> optionsOwner) {
    List<ColumnFamilyDescriptor> descriptors = new ArrayList<>();
    for (byte[] name : expectedColumnFamilyNames()) {
      ColumnFamilyOptions options = new ColumnFamilyOptions();
      optionsOwner.add(options);
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
    try (RocksIterator iterator = db.newIterator(defaultHandle)) {
      iterator.seekToFirst();
      ArchiveRocksIterators.requireOk(iterator,
          "UNIFIED_V1 validate empty default column family");
      if (iterator.isValid()) {
        throw new ArchiveException("UNIFIED_V1 default column family must be empty");
      }
    }
  }

  private void requireJournalValue(byte[] key, byte[] expectedValue) throws RocksDBException {
    byte[] current = db.get(handle(UnifiedArchiveColumnFamily.INFLIGHT), key);
    if (current == null) {
      throw new ArchiveException("UNIFIED_V1 publish journal is missing");
    }
    if (!Arrays.equals(current, expectedValue)) {
      throw new ArchiveException("UNIFIED_V1 publish journal changed before publication");
    }
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

  private static void closeResources(List<ColumnFamilyHandle> handles, RocksDB db,
      List<ColumnFamilyOptions> columnFamilyOptions, DBOptions dbOptions) {
    for (int i = handles.size() - 1; i >= 0; i--) {
      handles.get(i).close();
    }
    if (db != null) {
      db.close();
    }
    for (int i = columnFamilyOptions.size() - 1; i >= 0; i--) {
      columnFamilyOptions.get(i).close();
    }
    dbOptions.close();
  }

  interface BatchWriter {
    void write(RocksDB db, WriteOptions writeOptions, WriteBatch batch) throws RocksDBException;
  }
}
