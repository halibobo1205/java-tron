package org.tron.core.archive.unified;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteOptions;
import org.tron.core.archive.ArchiveException;

public class UnifiedArchiveDbTest {

  private static final byte[] SCHEMA_CHECKSUM = repeated(0x5a);
  private static final byte[] JOURNAL_KEY = ascii("block-7");
  private static final byte[] JOURNAL_VALUE = ascii("journal-value");
  private static final byte[] CURSOR_KEY = ascii("published-cursor");
  private static final byte[] CURSOR_VALUE = ascii("cursor-8");
  private static final byte[] INDEX_KEY = ascii("index-7");
  private static final byte[] INDEX_VALUE = ascii("index-value");
  private static final byte[] LATEST_KEY = ascii("latest-key");
  private static final byte[] LATEST_VALUE = ascii("latest-value");
  private static final byte[] HISTORY_KEY = ascii("history-key");
  private static final byte[] HISTORY_VALUE = ascii("history-value");
  private static final byte[] CHANGESET_KEY = ascii("changeset-key");
  private static final byte[] CHANGESET_VALUE = ascii("changeset-value");
  private static final byte[] MARKER_KEY = ascii("marker-7");
  private static final byte[] MARKER_VALUE = ascii("marker-value");
  private static final byte[] COMMITMENT_KEY = ascii("commitment-7");
  private static final byte[] COMMITMENT_VALUE = ascii("commitment-value");

  private Path root;
  private Path dbPath;
  private UnifiedArchiveDb db;

  @Before
  public void setUp() throws IOException {
    root = Files.createTempDirectory("unified-archive-db-test");
    dbPath = root.resolve("unified");
    db = UnifiedArchiveDb.initialize(dbPath, SCHEMA_CHECKSUM);
  }

  @After
  public void tearDown() {
    if (db != null) {
      db.close();
    }
    deleteRecursively(root.toFile());
  }

  @Test
  public void publishedRowsAndColumnFamiliesSurviveReopen() throws Exception {
    db.putJournalDurably(JOURNAL_KEY, JOURNAL_VALUE);
    db.publishBlockAtomically(publish(), true);

    db.close();
    db = UnifiedArchiveDb.open(dbPath, SCHEMA_CHECKSUM);

    try (UnifiedArchiveReadView view = db.openReadView()) {
      assertPublished(view);
    }
    assertExactColumnFamilies();
  }

  @Test
  public void failedBatchLeavesJournalAndNoPublishedRowsThenRetrySucceeds() {
    db.putJournalDurably(JOURNAL_KEY, JOURNAL_VALUE);
    db.close();

    AtomicInteger attempts = new AtomicInteger();
    db = UnifiedArchiveDb.open(dbPath, SCHEMA_CHECKSUM, (rocksDb, writeOptions, batch) -> {
      assertFalse(writeOptions.disableWAL());
      assertEquals(8, batch.count());
      if (attempts.getAndIncrement() == 0) {
        assertFalse(writeOptions.sync());
        throw new RocksDBException("injected publish failure");
      }
      assertTrue(writeOptions.sync());
      rocksDb.write(writeOptions, batch);
    });

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> db.publishBlockAtomically(publish(), false));
    assertTrue(failure.getMessage().contains("atomic block publish failed"));
    assertEquals(1, attempts.get());
    try (UnifiedArchiveReadView view = db.openReadView()) {
      assertUnpublished(view);
    }

    db.publishBlockAtomically(publish(), true);
    assertEquals(2, attempts.get());
    try (UnifiedArchiveReadView view = db.openReadView()) {
      assertPublished(view);
    }
  }

  @Test
  public void snapshotKeepsMetaIndexTemporalMarkerAndJournalAtOneSequence() {
    db.putJournalDurably(JOURNAL_KEY, JOURNAL_VALUE);

    try (UnifiedArchiveReadView beforePublish = db.openReadView()) {
      long beforeSequence = beforePublish.getSequenceNumber();
      db.publishBlockAtomically(publish(), false);

      assertUnpublished(beforePublish);
      try (UnifiedArchiveReadView afterPublish = db.openReadView()) {
        assertTrue(afterPublish.getSequenceNumber() > beforeSequence);
        assertPublished(afterPublish);
      }
    }
  }

  @Test
  public void openDoesNotCreateMissingPathOrAdoptEmptyDirectory() throws IOException {
    Path missing = root.resolve("missing");
    assertThrows(ArchiveException.class,
        () -> UnifiedArchiveDb.open(missing, SCHEMA_CHECKSUM));
    assertFalse(Files.exists(missing));

    Path empty = root.resolve("empty");
    Files.createDirectory(empty);
    assertThrows(ArchiveException.class,
        () -> UnifiedArchiveDb.open(empty, SCHEMA_CHECKSUM));
    assertThrows(ArchiveException.class,
        () -> UnifiedArchiveDb.initialize(empty, SCHEMA_CHECKSUM));
    assertEquals(0, empty.toFile().list().length);
  }

  @Test
  public void openRejectsSchemaMismatch() {
    closeForRawEdit();
    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> UnifiedArchiveDb.open(dbPath, repeated(0x6b)));
    assertTrue(failure.getMessage().contains("schema checksum mismatch"));
  }

  @Test
  public void openRejectsMissingManifest() throws Exception {
    closeForRawEdit();
    editRaw((rocksDb, handles) -> {
      try (WriteOptions writeOptions = new WriteOptions().setDisableWAL(false).setSync(true)) {
        rocksDb.delete(handles.get(UnifiedArchiveColumnFamily.META.ordinal() + 1),
            writeOptions, UnifiedArchiveManifest.key());
      }
    });

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> UnifiedArchiveDb.open(dbPath, SCHEMA_CHECKSUM));
    assertTrue(failure.getMessage().contains("missing its manifest"));
  }

  @Test
  public void emptyInitializationResumesAfterManifestWriteWasInterrupted() throws Exception {
    closeForRawEdit();
    editRaw((rocksDb, handles) -> {
      try (WriteOptions writeOptions = new WriteOptions().setDisableWAL(false).setSync(true)) {
        rocksDb.delete(handles.get(UnifiedArchiveColumnFamily.META.ordinal() + 1),
            writeOptions, UnifiedArchiveManifest.key());
      }
    });

    db = UnifiedArchiveDb.initializeOrResumeEmpty(dbPath, SCHEMA_CHECKSUM);
    assertFalse(db.hasArchiveData());
    db.close();
    db = UnifiedArchiveDb.open(dbPath, SCHEMA_CHECKSUM);
  }

  @Test
  public void emptyInitializationDoesNotRepairAStoreContainingArchiveRows() throws Exception {
    closeForRawEdit();
    editRaw((rocksDb, handles) -> {
      try (WriteOptions writeOptions = new WriteOptions().setDisableWAL(false).setSync(true)) {
        rocksDb.delete(handles.get(UnifiedArchiveColumnFamily.META.ordinal() + 1),
            writeOptions, UnifiedArchiveManifest.key());
        rocksDb.put(handles.get(UnifiedArchiveColumnFamily.INDEX.ordinal() + 1),
            writeOptions, INDEX_KEY, INDEX_VALUE);
      }
    });

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> UnifiedArchiveDb.initializeOrResumeEmpty(dbPath, SCHEMA_CHECKSUM));
    assertTrue(failure.getMessage().contains("archive rows present"));
  }

  @Test
  public void openRejectsUnexpectedColumnFamily() throws Exception {
    closeForRawEdit();
    editRaw((rocksDb, handles) -> {
      try (ColumnFamilyOptions options = new ColumnFamilyOptions();
           ColumnFamilyHandle ignored = rocksDb.createColumnFamily(
               new ColumnFamilyDescriptor(ascii("unexpected"), options))) {
        assertEquals("unexpected",
            new String(ignored.getName(), StandardCharsets.US_ASCII));
      }
    });

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> UnifiedArchiveDb.open(dbPath, SCHEMA_CHECKSUM));
    assertTrue(failure.getMessage().contains("column family set mismatch"));
  }

  @Test
  public void everyWriteOptionKeepsWalEnabled() {
    try (WriteOptions asynchronous = UnifiedArchiveDb.createWriteOptions(false);
         WriteOptions synchronous = UnifiedArchiveDb.createWriteOptions(true)) {
      assertFalse(asynchronous.disableWAL());
      assertFalse(asynchronous.sync());
      assertFalse(synchronous.disableWAL());
      assertTrue(synchronous.sync());
    }
  }

  @Test
  public void publishRejectsReservedCursorAndDuplicateMutationKeys() {
    assertThrows(ArchiveException.class, () -> UnifiedArchivePublish.builder()
        .journal(JOURNAL_KEY, JOURNAL_VALUE)
        .cursor(ascii("not-the-published-cursor"), CURSOR_VALUE));

    assertThrows(ArchiveException.class, () -> UnifiedArchivePublish.builder()
        .journal(JOURNAL_KEY, JOURNAL_VALUE)
        .cursor(CURSOR_KEY, CURSOR_VALUE)
        .blockMarker(MARKER_KEY, MARKER_VALUE)
        .put(UnifiedArchiveColumnFamily.INDEX, INDEX_KEY, INDEX_VALUE)
        .put(UnifiedArchiveColumnFamily.LATEST, LATEST_KEY, LATEST_VALUE)
        .put(UnifiedArchiveColumnFamily.LATEST, LATEST_KEY, ascii("replacement"))
        .build());
  }

  @Test
  public void genericMetaWritesCannotReplaceManifestOrPublishedCursor() {
    assertThrows(ArchiveException.class,
        () -> db.putMetaDurably(UnifiedArchiveManifest.key(), ascii("replacement")));
    assertThrows(ArchiveException.class,
        () -> db.putMetaDurably(CURSOR_KEY, CURSOR_VALUE));
  }

  private static UnifiedArchivePublish publish() {
    return UnifiedArchivePublish.builder()
        .journal(JOURNAL_KEY, JOURNAL_VALUE)
        .cursor(CURSOR_KEY, CURSOR_VALUE)
        .blockMarker(MARKER_KEY, MARKER_VALUE)
        .put(UnifiedArchiveColumnFamily.INDEX, INDEX_KEY, INDEX_VALUE)
        .put(UnifiedArchiveColumnFamily.LATEST, LATEST_KEY, LATEST_VALUE)
        .put(UnifiedArchiveColumnFamily.HISTORY, HISTORY_KEY, HISTORY_VALUE)
        .put(UnifiedArchiveColumnFamily.CHANGESET, CHANGESET_KEY, CHANGESET_VALUE)
        .put(UnifiedArchiveColumnFamily.COMMITMENT, COMMITMENT_KEY, COMMITMENT_VALUE)
        .build();
  }

  private static void assertUnpublished(UnifiedArchiveReadView view) {
    assertArrayEquals(JOURNAL_VALUE,
        view.get(UnifiedArchiveColumnFamily.INFLIGHT, JOURNAL_KEY));
    assertNull(view.get(UnifiedArchiveColumnFamily.META, CURSOR_KEY));
    assertNull(view.get(UnifiedArchiveColumnFamily.INDEX, INDEX_KEY));
    assertNull(view.get(UnifiedArchiveColumnFamily.LATEST, LATEST_KEY));
    assertNull(view.get(UnifiedArchiveColumnFamily.HISTORY, HISTORY_KEY));
    assertNull(view.get(UnifiedArchiveColumnFamily.CHANGESET, CHANGESET_KEY));
    assertNull(view.get(UnifiedArchiveColumnFamily.BLOCK_MARKER, MARKER_KEY));
    assertNull(view.get(UnifiedArchiveColumnFamily.COMMITMENT, COMMITMENT_KEY));
  }

  private static void assertPublished(UnifiedArchiveReadView view) {
    assertNull(view.get(UnifiedArchiveColumnFamily.INFLIGHT, JOURNAL_KEY));
    assertArrayEquals(CURSOR_VALUE,
        view.get(UnifiedArchiveColumnFamily.META, CURSOR_KEY));
    assertArrayEquals(INDEX_VALUE,
        view.get(UnifiedArchiveColumnFamily.INDEX, INDEX_KEY));
    assertArrayEquals(LATEST_VALUE,
        view.get(UnifiedArchiveColumnFamily.LATEST, LATEST_KEY));
    assertArrayEquals(HISTORY_VALUE,
        view.get(UnifiedArchiveColumnFamily.HISTORY, HISTORY_KEY));
    assertArrayEquals(CHANGESET_VALUE,
        view.get(UnifiedArchiveColumnFamily.CHANGESET, CHANGESET_KEY));
    assertArrayEquals(MARKER_VALUE,
        view.get(UnifiedArchiveColumnFamily.BLOCK_MARKER, MARKER_KEY));
    assertArrayEquals(COMMITMENT_VALUE,
        view.get(UnifiedArchiveColumnFamily.COMMITMENT, COMMITMENT_KEY));
  }

  private void assertExactColumnFamilies() throws RocksDBException {
    List<byte[]> actual;
    try (Options options = new Options().setCreateIfMissing(false)) {
      actual = RocksDB.listColumnFamilies(options, dbPath.toString());
    }
    List<byte[]> expected = UnifiedArchiveDb.expectedColumnFamilyNames();
    assertEquals(expected.size(), actual.size());
    for (byte[] expectedName : expected) {
      assertTrue(contains(actual, expectedName));
    }
  }

  private static boolean contains(List<byte[]> names, byte[] expected) {
    for (byte[] name : names) {
      if (Arrays.equals(name, expected)) {
        return true;
      }
    }
    return false;
  }

  private void closeForRawEdit() {
    db.close();
    db = null;
  }

  private void editRaw(RawEditor editor) throws Exception {
    List<ColumnFamilyOptions> optionOwners = new ArrayList<>();
    List<ColumnFamilyDescriptor> descriptors = new ArrayList<>();
    for (byte[] name : UnifiedArchiveDb.expectedColumnFamilyNames()) {
      ColumnFamilyOptions options = new ColumnFamilyOptions();
      optionOwners.add(options);
      descriptors.add(new ColumnFamilyDescriptor(name, options));
    }
    List<ColumnFamilyHandle> handles = new ArrayList<>();
    DBOptions options = new DBOptions()
        .setCreateIfMissing(false)
        .setCreateMissingColumnFamilies(false);
    RocksDB rocksDb = null;
    try {
      rocksDb = RocksDB.open(options, dbPath.toString(), descriptors, handles);
      editor.edit(rocksDb, handles);
    } finally {
      for (int i = handles.size() - 1; i >= 0; i--) {
        handles.get(i).close();
      }
      if (rocksDb != null) {
        rocksDb.close();
      }
      options.close();
      for (int i = optionOwners.size() - 1; i >= 0; i--) {
        optionOwners.get(i).close();
      }
    }
  }

  private static byte[] repeated(int value) {
    byte[] bytes = new byte[32];
    Arrays.fill(bytes, (byte) value);
    return bytes;
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static void deleteRecursively(File file) {
    if (file == null || !file.exists()) {
      return;
    }
    File[] children = file.listFiles();
    if (children != null) {
      for (File child : children) {
        deleteRecursively(child);
      }
    }
    file.delete();
  }

  private interface RawEditor {
    void edit(RocksDB rocksDb, List<ColumnFamilyHandle> handles) throws Exception;
  }
}
