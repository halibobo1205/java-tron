package org.tron.common.storage.rocksdb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.tron.common.TestConstants.TEST_CONF;
import static org.tron.common.TestConstants.assumeLevelDbAvailable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDBException;
import org.rocksdb.Status;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.storage.leveldb.LevelDbDataSourceImpl;
import org.tron.common.utils.FileUtil;
import org.tron.common.utils.PropUtil;
import org.tron.common.utils.ReflectUtils;
import org.tron.common.utils.StorageUtils;
import org.tron.core.archive.ArchiveRocksReadOptions;
import org.tron.core.archive.query.ArchiveQueryLimits;
import org.tron.core.archive.query.HistoricalQueryLimitException;
import org.tron.core.archive.query.QueryContext;
import org.tron.core.archive.query.QueryContextHolder;
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

  @Test
  public void historicalPointReadDisablesBlockCacheAdmission() throws Exception {
    RocksDbDataSourceImpl dataSource = new RocksDbDataSourceImpl();
    org.rocksdb.RocksDB database = mock(org.rocksdb.RocksDB.class);
    ReflectUtils.setFieldValue(dataSource, "dataBaseName", "cacheless-read-test");
    ReflectUtils.setFieldValue(dataSource, "database", database);
    ReflectUtils.setFieldValue(dataSource, "alive", true);
    when(database.get(any(ReadOptions.class), same(key1))).thenAnswer(invocation -> {
      ReadOptions options = invocation.getArgument(0);
      Assert.assertFalse(options.fillCache());
      return value1;
    });

    Assert.assertArrayEquals(value1, dataSource.getDataWithoutCache(key1));
  }

  @Test
  public void historicalPointReadRejectsOversizedValueBeforeFullAllocation() throws Exception {
    RocksDbDataSourceImpl dataSource = new RocksDbDataSourceImpl();
    org.rocksdb.RocksDB database = mock(org.rocksdb.RocksDB.class);
    ReflectUtils.setFieldValue(dataSource, "dataBaseName", "bounded-cacheless-read-test");
    ReflectUtils.setFieldValue(dataSource, "database", database);
    ReflectUtils.setFieldValue(dataSource, "alive", true);
    when(database.get(any(ReadOptions.class), same(key1), any(byte[].class)))
        .thenAnswer(invocation -> {
          ReadOptions options = invocation.getArgument(0);
          byte[] probe = invocation.getArgument(2);
          Assert.assertFalse(options.fillCache());
          assertEquals(3, probe.length);
          return value1.length;
        });
    QueryContext context = new QueryContext(ArchiveQueryLimits.builder()
        .maxBackendValueBytes(3L)
        .build());

    HistoricalQueryLimitException failure;
    try (QueryContextHolder.Scope ignored = QueryContextHolder.attach(context);
        QueryContextHolder.Scope suspended = QueryContextHolder.suspend()) {
      Assert.assertNull(QueryContextHolder.current());
      failure = assertThrows(HistoricalQueryLimitException.class,
          () -> dataSource.getDataWithoutCache(key1));
    }

    assertEquals(HistoricalQueryLimitException.Limit.BACKEND_VALUE_BYTES, failure.getLimit());
    verify(database).get(any(ReadOptions.class), same(key1), any(byte[].class));
  }

  @Test
  public void historicalPointReadDeadlineBoundsResetLockWait() throws Exception {
    RocksDbDataSourceImpl dataSource = new RocksDbDataSourceImpl();
    ReentrantReadWriteLock resetLock = new ReentrantReadWriteLock();
    ReflectUtils.setFieldValue(dataSource, "resetDbLock", resetLock);
    FutureTask<HistoricalQueryLimitException> read = new FutureTask<>(() -> {
      QueryContext context = new QueryContext(
          ArchiveQueryLimits.builder().deadlineMs(20L).build());
      try (QueryContextHolder.Scope ignored = QueryContextHolder.attach(context)) {
        return assertThrows(HistoricalQueryLimitException.class,
            () -> dataSource.getDataWithoutCache(key1));
      }
    });
    Thread reader = new Thread(read, "rocksdb-cacheless-lock-deadline-test");
    resetLock.writeLock().lock();
    try {
      reader.start();
      HistoricalQueryLimitException failure = read.get(1L, TimeUnit.SECONDS);
      assertEquals(HistoricalQueryLimitException.Reason.DEADLINE, failure.getReason());
    } finally {
      resetLock.writeLock().unlock();
      reader.join(1_000L);
    }
  }

  @Test
  public void storageOnlyContextTranslatesNativeTimeoutToHistoricalDeadline() throws Exception {
    RocksDbDataSourceImpl dataSource = new RocksDbDataSourceImpl();
    org.rocksdb.RocksDB database = mock(org.rocksdb.RocksDB.class);
    ReflectUtils.setFieldValue(dataSource, "dataBaseName", "storage-deadline-read-test");
    ReflectUtils.setFieldValue(dataSource, "database", database);
    ReflectUtils.setFieldValue(dataSource, "alive", true);
    RocksDBException nativeFailure = new RocksDBException(
        new Status(Status.Code.TimedOut, Status.SubCode.None, "injected native timeout"));
    when(database.get(any(ReadOptions.class), same(key1), any(byte[].class)))
        .thenAnswer(invocation -> {
          Thread.sleep(150L);
          throw nativeFailure;
        });
    QueryContext context = new QueryContext(
        ArchiveQueryLimits.builder().deadlineMs(100L).build());

    try (QueryContextHolder.Scope ignored = QueryContextHolder.attach(context);
        QueryContextHolder.Scope suspended = QueryContextHolder.suspend()) {
      Assert.assertNull(QueryContextHolder.current());
      HistoricalQueryLimitException failure = assertThrows(
          HistoricalQueryLimitException.class,
          () -> dataSource.getDataWithoutCache(key1));
      assertEquals(HistoricalQueryLimitException.Limit.DEADLINE, failure.getLimit());
      Assert.assertSame(nativeFailure, failure.getSuppressed()[0]);
    }
  }

  @Test
  public void historicalLargePointReadPropagatesDeadlineAndAccountsBothNativeReads()
      throws Exception {
    org.junit.Assume.assumeTrue(ArchiveRocksReadOptions.nativeDeadlineSupported());
    RocksDbDataSourceImpl dataSource = new RocksDbDataSourceImpl();
    org.rocksdb.RocksDB database = mock(org.rocksdb.RocksDB.class);
    ReflectUtils.setFieldValue(dataSource, "dataBaseName", "deadline-cacheless-read-test");
    ReflectUtils.setFieldValue(dataSource, "database", database);
    ReflectUtils.setFieldValue(dataSource, "alive", true);
    byte[] value = new byte[64 * 1024 + 1];
    AtomicInteger nativeReads = new AtomicInteger();
    when(database.get(any(ReadOptions.class), same(key1), any(byte[].class)))
        .thenAnswer(invocation -> {
          ReadOptions options = invocation.getArgument(0);
          byte[] destination = invocation.getArgument(2);
          long deadlineMicros = (long) ReadOptions.class.getMethod("deadline").invoke(options);
          long ioTimeoutMicros =
              (long) ReadOptions.class.getMethod("ioTimeout").invoke(options);
          Assert.assertTrue(deadlineMicros
              > TimeUnit.MILLISECONDS.toMicros(System.currentTimeMillis()));
          Assert.assertTrue(ioTimeoutMicros > 0L);
          nativeReads.incrementAndGet();
          if (destination.length < value.length) {
            return value.length;
          }
          System.arraycopy(value, 0, destination, 0, value.length);
          return value.length;
        });
    QueryContext context = new QueryContext(ArchiveQueryLimits.builder()
        .deadlineMs(5_000L)
        .build());

    try (QueryContextHolder.Scope ignored = QueryContextHolder.attach(context)) {
      // TronStoreWithRevoking accounts the first root read before entering the engine.
      context.recordBackendRead();
      Assert.assertArrayEquals(value, dataSource.getDataWithoutCache(key1));
    }

    assertEquals(2L, context.getBackendReads());
    assertEquals(2, nativeReads.get());
  }

  private void makeExceptionDb(String dbName) {
    RocksDbDataSourceImpl dataSource = new RocksDbDataSourceImpl(
        Args.getInstance().getOutputDirectory(), "test_initDb");
    dataSource.closeDB();
    FileUtil.saveData(dataSource.getDbPath().toString() + "/CURRENT",
        "...", Boolean.FALSE);
  }
}
