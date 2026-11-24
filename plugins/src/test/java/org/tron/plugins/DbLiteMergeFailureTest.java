package org.tron.plugins;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.rocksdb.RocksDBException;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DbTool;

public class DbLiteMergeFailureTest {

  @Rule
  public final TemporaryFolder folder = new TemporaryFolder();

  @After
  public void closeDatabases() {
    DbTool.close();
  }

  @Test
  public void testBackupFailureLeavesOriginalDbs() throws IOException, RocksDBException {
    DbLiteMergeFixture fixture = movedFixture();
    byte[] originalInfo = Files.readAllBytes(fixture.lite.resolve("info.properties"));
    Path current = fixture.lite.resolve("block").resolve("CURRENT");
    IOException failure = new IOException("Injected archive backup copy failure");

    try (MockedStatic<Files> files = Mockito.mockStatic(Files.class, Mockito.CALLS_REAL_METHODS)) {
      files.when(() -> Files.copy(ArgumentMatchers.eq(current), ArgumentMatchers.any(Path.class),
          ArgumentMatchers.eq(StandardCopyOption.REPLACE_EXISTING))).thenThrow(failure);
      assertMergeFailed(fixture, originalInfo, failure);
      files.verify(() -> Files.copy(ArgumentMatchers.eq(current), ArgumentMatchers.any(Path.class),
          ArgumentMatchers.eq(StandardCopyOption.REPLACE_EXISTING)));
    }

    assertSnapshotData(fixture.movedRoot);
    for (String db : DbLiteMergeFixture.archiveDbs()) {
      assertTrue(db, Files.exists(fixture.movedDirectory(db)
          .resolve(DbLiteMergeFixture.STALE_FILE)));
    }
  }

  @Test
  public void testClearFailureKeepsRecoverableBackup()
      throws IOException, RocksDBException {
    DbLiteMergeFixture fixture = movedFixture();
    byte[] originalInfo = Files.readAllBytes(fixture.lite.resolve("info.properties"));
    Path current = fixture.movedDirectory("trans").resolve("CURRENT");
    IOException failure = new IOException("Injected moved archive deletion failure");

    try (MockedStatic<Files> files = Mockito.mockStatic(Files.class, Mockito.CALLS_REAL_METHODS)) {
      files.when(() -> Files.delete(ArgumentMatchers.eq(current))).thenThrow(failure);
      assertMergeFailed(fixture, originalInfo, failure);
      files.verify(() -> Files.delete(current));
    }

    assertTrue(Files.exists(current));
    assertFalse(Files.exists(fixture.movedDirectory("block")
        .resolve(DbLiteMergeFixture.STALE_FILE)));
    assertFalse(Files.exists(fixture.movedDirectory("block-index")
        .resolve(DbLiteMergeFixture.STALE_FILE)));
    assertRecoverableBackup(fixture);
  }

  @Test
  public void testHistoryCopyFailureKeepsRecoverableBackup()
      throws IOException, RocksDBException {
    DbLiteMergeFixture fixture = movedFixture();
    byte[] originalInfo = Files.readAllBytes(fixture.lite.resolve("info.properties"));
    Path target = fixture.movedDirectory("block");
    IOException failure = new IOException("Injected history directory creation failure");

    try (MockedStatic<Files> files = Mockito.mockStatic(Files.class, Mockito.CALLS_REAL_METHODS)) {
      files.when(() -> Files.createDirectories(ArgumentMatchers.eq(target))).thenThrow(failure);
      assertMergeFailed(fixture, originalInfo, failure);
      files.verify(() -> Files.createDirectories(target));
    }

    for (String db : DbLiteMergeFixture.archiveDbs()) {
      assertFalse(db, Files.exists(fixture.movedDirectory(db)
          .resolve(DbLiteMergeFixture.STALE_FILE)));
    }
    assertRecoverableBackup(fixture);
  }

  private DbLiteMergeFixture movedFixture() throws IOException, RocksDBException {
    DbLiteMergeFixture fixture = new DbLiteMergeFixture(folder,
        DbLiteMergeFixture.SNAPSHOT_MAX - 1);
    fixture.migrate(DbLiteMergeFixture.archiveDbs());
    return fixture;
  }

  private static void assertMergeFailed(DbLiteMergeFixture fixture, byte[] originalInfo,
      IOException failure) throws IOException {
    int exitCode = fixture.executeMerge();
    assertEquals(fixture.error(), 1, exitCode);
    assertTrue(fixture.error(), fixture.error().contains(failure.getMessage()));
    fixture.assertLinks();
    assertArrayEquals(originalInfo, Files.readAllBytes(fixture.lite.resolve("info.properties")));
    assertEquals(1, fixture.backupDirectories().size());
  }

  private static void assertRecoverableBackup(DbLiteMergeFixture fixture)
      throws IOException, RocksDBException {
    List<Path> backups = fixture.backupDirectories();
    assertEquals(1, backups.size());
    assertSnapshotData(backups.get(0));
    for (String db : DbLiteMergeFixture.archiveDbs()) {
      assertTrue(db, Files.exists(backups.get(0).resolve(db)
          .resolve(DbLiteMergeFixture.STALE_FILE)));
    }
  }

  private static void assertSnapshotData(Path database) throws IOException, RocksDBException {
    for (String db : DbLiteMergeFixture.archiveDbs()) {
      DBInterface store = DbTool.getDB(database.toString(), db);
      boolean containsGenesis = "block".equals(db) || "block-index".equals(db);
      if (containsGenesis) {
        assertArrayEquals(db + " snapshot genesis", DbLiteMergeFixture.value(db, 0),
            store.get(DbLiteMergeFixture.key(db, 0)));
      }
      for (long number = DbLiteMergeFixture.SNAPSHOT_MIN;
          number <= DbLiteMergeFixture.SNAPSHOT_MAX; number++) {
        assertArrayEquals(db + " snapshot block " + number,
            DbLiteMergeFixture.value(db, number), store.get(DbLiteMergeFixture.key(db, number)));
      }
      long expectedEntries = DbLiteMergeFixture.SNAPSHOT_MAX - DbLiteMergeFixture.SNAPSHOT_MIN + 1;
      assertEquals(db + " snapshot entry count", expectedEntries + (containsGenesis ? 1 : 0),
          store.size());
    }
  }
}
