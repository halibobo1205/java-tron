package org.tron.common.storage.rocksdb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.tron.common.TestConstants.TEST_CONF;
import static org.tron.common.TestConstants.assumeLevelDbAvailable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;
import org.rocksdb.Logger;
import org.rocksdb.Options;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDBException;
import org.rocksdb.Status;
import org.springframework.test.util.ReflectionTestUtils;
import org.tron.common.arch.Arch;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.setting.RocksDbSettings;
import org.tron.common.storage.leveldb.LevelDbDataSourceImpl;
import org.tron.common.utils.FileUtil;
import org.tron.common.utils.PropUtil;
import org.tron.common.utils.StorageUtils;
import org.tron.core.config.args.Args;
import org.tron.core.exception.TronError;

/**
 * RocksDB-specific tests. Common DB tests are in {@link
 * org.tron.common.storage.DbDataSourceImplTest}.
 */
public class RocksDbDataSourceImplTest {

  @ClassRule
  public static final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Rule
  public final ExpectedException expectedException = ExpectedException.none();

  private byte[] key1 = "00000001aa".getBytes();
  private byte[] value1 = "10000".getBytes();

  @AfterClass
  public static void destroy() {
    Args.clearParam();
  }

  @BeforeClass
  public static void initDb() throws IOException {
    Args.setParam(new String[]{"--output-directory",
        temporaryFolder.newFolder().toString()}, TEST_CONF);
    CommonParameter.getInstance().storage.setDbEngine("ROCKSDB");
  }

  @Test
  public void initDbTest() {
    makeExceptionDb("test_initDb");
    TronError thrown = assertThrows(TronError.class, () -> new RocksDbDataSourceImpl(
        Args.getInstance().getOutputDirectory(), "test_initDb"));
    assertEquals(TronError.ErrCode.ROCKSDB_INIT, thrown.getErrCode());
  }

  @Test
  public void shouldKeepNativeBlockTableDefaults() throws IOException {
    RocksDbDataSourceImpl dataSource = new RocksDbDataSourceImpl(
        Args.getInstance().getOutputDirectory(), "table-defaults");
    Path optionsFile;
    try (Stream<Path> files = Files.list(dataSource.getDbPath())) {
      optionsFile = files
          .filter(path -> path.getFileName().toString().startsWith("OPTIONS-"))
          .max(Comparator.comparing(path -> path.getFileName().toString()))
          .orElseThrow(() -> new AssertionError("RocksDB OPTIONS file not found"));
    } finally {
      dataSource.closeDB();
    }
    String nativeOptions = new String(Files.readAllBytes(optionsFile), StandardCharsets.UTF_8);
    assertTrue(nativeOptions.contains("block_size=4096"));
    assertTrue(nativeOptions.contains("pin_l0_filter_and_index_blocks_in_cache=false"));
    assertTrue(nativeOptions.contains("filter_policy=nullptr"));
  }

  @Test
  public void testCloseReleasesLoggerAfterDatabase() {
    String name = "logger_close";
    try (Options options = spy(RocksDbSettings.getOptionsByDbName(name));
         MockedStatic<RocksDbSettings> settings = mockStatic(RocksDbSettings.class)) {
      settings.when(() -> RocksDbSettings.getOptionsByDbName(name)).thenReturn(options);
      Logger dbLogger = getLogger(options);
      RocksDbDataSourceImpl dataSource = new RocksDbDataSourceImpl(
          Args.getInstance().getOutputDirectory(), name);
      ReadOptions defaultReadOptions = getDefaultReadOptions(dataSource);
      ReadOptions defaultReadOptionsSpy = defaultReadOptions == null
          ? null : spy(defaultReadOptions);
      try {
        dataSource.putData(key1, value1);
        assertTrue(dbLogger.isOwningHandle());
        if (defaultReadOptionsSpy != null) {
          ReflectionTestUtils.setField(dataSource, "defaultReadOptions", defaultReadOptionsSpy);
          doAnswer(invocation -> {
            assertFalse(dataSource.getDatabase().isOwningHandle());
            assertTrue(options.isOwningHandle());
            assertTrue(dbLogger.isOwningHandle());
            return invocation.callRealMethod();
          }).when(defaultReadOptionsSpy).close();
        }
        doAnswer(invocation -> {
          if (options.isOwningHandle()) {
            assertFalse(dataSource.getDatabase().isOwningHandle());
            assertDefaultReadOptionsClosed(defaultReadOptions);
            assertTrue(dbLogger.isOwningHandle());
          }
          return invocation.callRealMethod();
        }).when(options).close();

        dataSource.closeDB();
        assertFalse(dataSource.isAlive());
        assertOptionsAndLoggerClosed(options);
        assertDefaultReadOptionsClosed(defaultReadOptions);
        assertResourceReferencesCleared(dataSource);
        dataSource.closeDB();
        verify(options, times(1)).close();
        if (defaultReadOptionsSpy != null) {
          verify(defaultReadOptionsSpy, times(1)).close();
        }
      } finally {
        dataSource.getDatabase().close();
        dataSource.closeDB();
      }
    }
  }

  @Test
  public void testFailedOpenReleasesLogger() {
    String name = "logger_failed_open";
    try (Options firstOptions = RocksDbSettings.getOptionsByDbName(name);
         Options failedOptions = RocksDbSettings.getOptionsByDbName(name);
         MockedStatic<RocksDbSettings> settings = mockStatic(RocksDbSettings.class)) {
      settings.when(() -> RocksDbSettings.getOptionsByDbName(name))
          .thenReturn(firstOptions, failedOptions);
      RocksDbDataSourceImpl dataSource = new RocksDbDataSourceImpl(
          Args.getInstance().getOutputDirectory(), name);
      ReadOptions defaultReadOptions = getDefaultReadOptions(dataSource);
      try {
        RocksDbDataSourceImpl failedSource = new RocksDbDataSourceImpl();
        failedSource.setDBName(name);
        ReflectionTestUtils.setField(failedSource, "parentPath",
            Args.getInstance().getOutputDirectory());
        TronError thrown = assertThrows(TronError.class,
            () -> ReflectionTestUtils.invokeMethod(failedSource, "initDB"));
        assertEquals(TronError.ErrCode.ROCKSDB_INIT, thrown.getErrCode());
        RocksDBException cause = (RocksDBException) thrown.getCause();
        assertEquals(Status.Code.IOError, cause.getStatus().getCode());
        assertTrue(cause.getMessage().toLowerCase(Locale.ROOT).contains("lock"));
        assertOptionsAndLoggerClosed(failedOptions);
        assertFalse(failedSource.isAlive());
        Assert.assertNull(failedSource.getDatabase());
        assertResourceReferencesCleared(failedSource);
        failedSource.closeDB();
        failedSource.closeDB();
        assertDefaultReadOptionsOpen(defaultReadOptions);
        assertTrue(getLogger(firstOptions).isOwningHandle());
        dataSource.putData(key1, value1);
        Assert.assertArrayEquals(value1, dataSource.getData(key1));
      } finally {
        dataSource.closeDB();
      }
      assertOptionsAndLoggerClosed(firstOptions);
      assertDefaultReadOptionsClosed(defaultReadOptions);
      assertResourceReferencesCleared(dataSource);
    }
  }

  @Test
  public void testResetAndReopenReleaseLoggers() {
    String name = "logger_reset_reopen";
    try (Options firstOptions = RocksDbSettings.getOptionsByDbName(name);
         Options resetOptions = RocksDbSettings.getOptionsByDbName(name);
         Options reopenedOptions = RocksDbSettings.getOptionsByDbName(name);
         MockedStatic<RocksDbSettings> settings = mockStatic(RocksDbSettings.class)) {
      settings.when(() -> RocksDbSettings.getOptionsByDbName(name))
          .thenReturn(firstOptions, resetOptions, reopenedOptions);
      RocksDbDataSourceImpl dataSource = new RocksDbDataSourceImpl(
          Args.getInstance().getOutputDirectory(), name);
      ReadOptions firstReadOptions = getDefaultReadOptions(dataSource);
      ReadOptions resetReadOptions = null;
      try {
        dataSource.putData(key1, value1);
        dataSource.resetDb();
        assertOptionsAndLoggerClosed(firstOptions);
        assertDefaultReadOptionsClosed(firstReadOptions);
        resetReadOptions = getDefaultReadOptions(dataSource);
        if (firstReadOptions != null) {
          Assert.assertNotSame(firstReadOptions, resetReadOptions);
        }
        assertTrue(getLogger(resetOptions).isOwningHandle());
        Assert.assertNull(dataSource.getData(key1));
        dataSource.putData(key1, value1);
        Assert.assertArrayEquals(value1, dataSource.getData(key1));
      } finally {
        dataSource.closeDB();
      }
      assertOptionsAndLoggerClosed(resetOptions);
      assertDefaultReadOptionsClosed(resetReadOptions);
      assertResourceReferencesCleared(dataSource);

      RocksDbDataSourceImpl reopened = new RocksDbDataSourceImpl(
          Args.getInstance().getOutputDirectory(), name);
      ReadOptions reopenedReadOptions = getDefaultReadOptions(reopened);
      try {
        assertTrue(getLogger(reopenedOptions).isOwningHandle());
        Assert.assertArrayEquals(value1, reopened.getData(key1));
      } finally {
        reopened.closeDB();
      }
      assertOptionsAndLoggerClosed(reopenedOptions);
      assertDefaultReadOptionsClosed(reopenedReadOptions);
      assertResourceReferencesCleared(reopened);
    }
  }

  @Test
  public void testManagedCloseAfterDatabaseClosedExternally() {
    RocksDbDataSourceImpl dataSource = new RocksDbDataSourceImpl(
        Args.getInstance().getOutputDirectory(), "externally_closed_database");
    Options options = (Options) ReflectionTestUtils.getField(dataSource, "options");
    ReadOptions defaultReadOptions = getDefaultReadOptions(dataSource);
    try {
      dataSource.putData(key1, value1);
      dataSource.getDatabase().close();
      assertFalse(dataSource.getDatabase().isOwningHandle());
      assertTrue(getLogger(options).isOwningHandle());

      dataSource.closeDB();
      dataSource.closeDB();
      assertFalse(dataSource.isAlive());
      assertDefaultReadOptionsClosed(defaultReadOptions);
      assertOptionsAndLoggerClosed(options);
      assertResourceReferencesCleared(dataSource);
    } finally {
      dataSource.closeDB();
    }
  }

  private static ReadOptions getDefaultReadOptions(RocksDbDataSourceImpl dataSource) {
    ReadOptions readOptions =
        (ReadOptions) ReflectionTestUtils.getField(dataSource, "defaultReadOptions");
    if (Arch.isArm64()) {
      Assert.assertNotNull(readOptions);
      Assert.assertSame(
          ReflectionTestUtils.getField(dataSource.getDatabase(), "defaultReadOptions_"),
          readOptions);
      assertTrue(readOptions.isOwningHandle());
    } else {
      // RocksDB JNI 5.15.10 has no per-database default ReadOptions field.
      Assert.assertNull(readOptions);
    }
    return readOptions;
  }

  private static void assertDefaultReadOptionsOpen(ReadOptions readOptions) {
    if (readOptions != null) {
      assertTrue(readOptions.isOwningHandle());
    }
  }

  private static void assertDefaultReadOptionsClosed(ReadOptions readOptions) {
    if (readOptions != null) {
      assertFalse(readOptions.isOwningHandle());
    }
  }

  private static void assertResourceReferencesCleared(RocksDbDataSourceImpl dataSource) {
    Assert.assertNull(ReflectionTestUtils.getField(dataSource, "defaultReadOptions"));
    Assert.assertNull(ReflectionTestUtils.getField(dataSource, "options"));
  }

  private static Logger getLogger(Options options) {
    return (Logger) ReflectionTestUtils.getField(options, "dbLogger");
  }

  private static void assertOptionsAndLoggerClosed(Options options) {
    assertFalse(options.isOwningHandle());
    assertFalse(getLogger(options).isOwningHandle());
  }

  @Test
  public void testCheckOrInitEngine() {
    String dir =
        Args.getInstance().getOutputDirectory() + Args.getInstance().getStorage().getDbDirectory();
    String enginePath = dir + File.separator + "test_engine" + File.separator + "engine.properties";
    FileUtil.createDirIfNotExists(dir + File.separator + "test_engine");
    FileUtil.createFileIfNotExists(enginePath);
    PropUtil.writeProperty(enginePath, "ENGINE", "ROCKSDB");
    Assert.assertEquals("ROCKSDB", PropUtil.readProperty(enginePath, "ENGINE"));

    RocksDbDataSourceImpl dataSource;
    dataSource = new RocksDbDataSourceImpl(dir, "test_engine");
    Assert.assertNotNull(dataSource.getDatabase());
    dataSource.closeDB();

    PropUtil.writeProperty(enginePath, "ENGINE", "LEVELDB");
    Assert.assertEquals("LEVELDB", PropUtil.readProperty(enginePath, "ENGINE"));

    try {
      new RocksDbDataSourceImpl(dir, "test_engine");
    } catch (TronError e) {
      Assert.assertEquals("Cannot open LEVELDB database with ROCKSDB engine.", e.getMessage());
    }
    PropUtil.writeProperty(enginePath, "ENGINE", "ROCKSDB");
  }

  @Test
  public void testRocksDbOpenLevelDb() {
    assumeLevelDbAvailable();
    String name = "test_openLevelDb";
    String output = Paths
        .get(StorageUtils.getOutputDirectoryByDbName(name), CommonParameter
            .getInstance().getStorage().getDbDirectory()).toString();
    LevelDbDataSourceImpl levelDb = new LevelDbDataSourceImpl(
        StorageUtils.getOutputDirectoryByDbName(name), name);
    levelDb.putData(key1, value1);
    levelDb.closeDB();
    expectedException.expectMessage("Cannot open LEVELDB database with ROCKSDB engine.");
    new RocksDbDataSourceImpl(output, name);
  }

  @Test
  public void testRocksDbOpenLevelDb2() {
    assumeLevelDbAvailable();
    String name = "test_openLevelDb2";
    String output = Paths
        .get(StorageUtils.getOutputDirectoryByDbName(name), CommonParameter
            .getInstance().getStorage().getDbDirectory()).toString();
    LevelDbDataSourceImpl levelDb = new LevelDbDataSourceImpl(
        StorageUtils.getOutputDirectoryByDbName(name), name);
    levelDb.putData(key1, value1);
    levelDb.closeDB();
    File engineFile = Paths.get(output, name, "engine.properties").toFile();
    if (engineFile.exists()) {
      engineFile.delete();
    }
    Assert.assertFalse(engineFile.exists());

    expectedException.expectMessage("Cannot open LEVELDB database with ROCKSDB engine.");
    new RocksDbDataSourceImpl(output, name);
  }

  @Test
  public void backupAndDelete() throws RocksDBException {
    RocksDbDataSourceImpl dataSource = new RocksDbDataSourceImpl(
        Args.getInstance().getOutputDirectory(), "backupAndDelete");
    dataSource.putData(key1, value1);
    Path dir = Paths.get(Args.getInstance().getOutputDirectory(), "backup");
    String path = dir + File.separator;
    FileUtil.createDirIfNotExists(path);
    dataSource.backup(path);
    File backDB = Paths.get(dir.toString(), dataSource.getDBName()).toFile();
    Assert.assertTrue(backDB.exists());
    dataSource.deleteDbBakPath(path);
    Assert.assertFalse(backDB.exists());
    dataSource.closeDB();
  }

  private void makeExceptionDb(String dbName) {
    RocksDbDataSourceImpl dataSource = new RocksDbDataSourceImpl(
        Args.getInstance().getOutputDirectory(), "test_initDb");
    dataSource.closeDB();
    FileUtil.saveData(dataSource.getDbPath().toString() + "/CURRENT",
        "...", Boolean.FALSE);
  }
}
