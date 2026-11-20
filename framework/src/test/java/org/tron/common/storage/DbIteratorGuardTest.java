package org.tron.common.storage;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.iq80.leveldb.DBException;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.rocksdb.RocksDB;
import org.tron.common.TestConstants;
import org.tron.common.arch.Arch;
import org.tron.common.error.TronDBException;
import org.tron.common.storage.leveldb.LevelDbDataSourceImpl;
import org.tron.common.storage.rocksdb.RocksDbDataSourceImpl;
import org.tron.common.utils.ByteArray;
import org.tron.core.config.args.Args;
import org.tron.core.db.common.DbSourceInter;
import org.tron.core.db.common.iterator.DBIterator;

@RunWith(Parameterized.class)
public class DbIteratorGuardTest {

  private static final int ENTRY_COUNT = 3;

  @Parameters(name = "engine={0}")
  public static Collection<Object[]> engines() {
    List<Object[]> params = new ArrayList<>();
    if (!Arch.isArm64()) {
      params.add(new Object[]{"LEVELDB"});
    }
    params.add(new Object[]{"ROCKSDB"});
    return params;
  }

  @ClassRule
  public static final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private final String engineName;
  private DbSourceInter<byte[]> dataSource;

  static {
    RocksDB.loadLibrary();
  }

  public DbIteratorGuardTest(String engineName) {
    this.engineName = engineName;
  }

  @AfterClass
  public static void destroy() {
    Args.clearParam();
  }

  @Before
  public void setUp() throws IOException {
    Args.setParam(new String[]{"--output-directory",
        temporaryFolder.newFolder().toString()}, TestConstants.TEST_CONF);
    String dir = Args.getInstance().getOutputDirectory() + File.separator;
    dataSource = "LEVELDB".equals(engineName)
        ? new LevelDbDataSourceImpl(dir, "iterator_guard")
        : new RocksDbDataSourceImpl(dir, "iterator_guard");
    put(0, ENTRY_COUNT);
  }

  @After
  public void tearDown() {
    dataSource.closeDB();
  }

  @Test
  public void openIteratorFailsAfterCloseDb() throws IOException {
    DBIterator iterator = iterator();
    iterator.seekToFirst();
    Assert.assertTrue(iterator.hasNext());
    iterator.next();

    dataSource.closeDB();

    Assert.assertThrows(TronDBException.class, iterator::hasNext);
    Assert.assertThrows(TronDBException.class, iterator::next);
    Assert.assertThrows(TronDBException.class, iterator::seekToFirst);
    Assert.assertThrows(TronDBException.class, iterator::valid);
    Assert.assertThrows(TronDBException.class, iterator::checkState);
    iterator.close();
  }

  @Test
  public void openIteratorFailsAfterResetDb() throws IOException {
    DBIterator stale = iterator();
    stale.seekToFirst();

    dataSource.resetDb();

    Assert.assertThrows(TronDBException.class, stale::hasNext);
    stale.close();
    try (DBIterator fresh = iterator()) {
      Assert.assertFalse(fresh.hasNext());
    }
  }

  @Test
  public void finishedIteratorIsUnaffectedByCloseDb() throws IOException {
    DBIterator iterator = iterator();
    int count = 0;
    while (iterator.hasNext()) {
      iterator.next();
      count++;
    }
    Assert.assertEquals(ENTRY_COUNT, count);

    dataSource.closeDB();

    Assert.assertFalse(iterator.hasNext());
    iterator.close();
  }

  @Test(timeout = 60000)
  public void closeDbWhileIteratingOnlyThrowsTronDbException() throws Exception {
    put(ENTRY_COUNT, 20000);
    int readers = 4;
    CountDownLatch started = new CountDownLatch(readers);
    ExecutorService pool = Executors.newFixedThreadPool(readers);
    try {
      List<Future<Void>> results = new ArrayList<>();
      for (int i = 0; i < readers; i++) {
        results.add(pool.submit(() -> iterateUntilClosed(started)));
      }
      Assert.assertTrue(started.await(10, TimeUnit.SECONDS));
      Thread.sleep(100);

      dataSource.closeDB();

      for (Future<Void> result : results) {
        result.get(30, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  private Void iterateUntilClosed(CountDownLatch started) throws IOException {
    started.countDown();
    while (true) {
      DBIterator iterator;
      try {
        iterator = iterator();
      } catch (TronDBException | DBException e) {
        return null;
      }
      try {
        while (iterator.hasNext()) {
          iterator.next();
        }
      } catch (TronDBException e) {
        return null;
      } finally {
        iterator.close();
      }
    }
  }

  private DBIterator iterator() {
    return (DBIterator) dataSource.iterator();
  }

  private void put(int from, int to) {
    for (int i = from; i < to; i++) {
      byte[] key = ByteArray.fromLong(i);
      dataSource.putData(key, key);
    }
  }
}
