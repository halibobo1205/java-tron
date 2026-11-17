package org.tron.common.storage.leveldb;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.tron.common.TestConstants.TEST_CONF;
import static org.tron.common.TestConstants.assumeLevelDbAvailable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.fusesource.leveldbjni.internal.NativeCompressionType;
import org.fusesource.leveldbjni.internal.NativeDB;
import org.fusesource.leveldbjni.internal.NativeLogger;
import org.fusesource.leveldbjni.internal.NativeOptions;
import org.iq80.leveldb.CompressionType;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.DBComparator;
import org.iq80.leveldb.DBIterator;
import org.iq80.leveldb.Logger;
import org.iq80.leveldb.Options;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;
import org.rocksdb.RocksDB;
import org.springframework.test.util.ReflectionTestUtils;
import org.tron.common.parameter.CommonParameter;
import org.tron.core.config.args.Args;

public class LevelDbLoggerResourceTest {

  private static final byte[] KEY = new byte[]{1};
  private static final byte[] VALUE = new byte[]{2};

  @Rule
  public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private final List<DB> databases = new ArrayList<>();
  private final List<NativeDB> nativeDatabases = new ArrayList<>();
  private final List<NativeLogger> loggers = new ArrayList<>();
  private final List<NativeOptions> nativeOptions = new ArrayList<>();
  private LevelDbLoggerDeletes deletes;

  @Before
  public void setUp() throws Exception {
    assumeLevelDbAvailable();
    RocksDB.loadLibrary();
    Args.setParam(new String[]{"--output-directory",
        temporaryFolder.newFolder().toString()}, TEST_CONF);
    CommonParameter.getInstance().getStorage().setDbEngine("LEVELDB");
    deletes = new LevelDbLoggerDeletes();
  }

  @After
  public void tearDown() throws Exception {
    try (LevelDbLoggerDeletes ignored = deletes) {
      for (DB database : databases) {
        database.close();
      }
      for (NativeLogger logger : loggers) {
        logger.delete();
      }
    } finally {
      Args.clearParam();
    }
  }

  @Test
  public void shouldReleaseLoggerOnCloseAndRepeatedClose() throws Exception {
    AtomicInteger callbacks = new AtomicInteger();
    Logger callback = message -> callbacks.incrementAndGet();
    Options options = options().logger(callback);
    try (CapturedOpens ignored = new CapturedOpens()) {
      DB database = open(temporaryFolder.newFolder(), options);
      assertEquals(1, loggers.size());
      assertTrue(loggers.get(0).isAllocated());
      assertTrue(globalReference(loggers.get(0)) != 0);
      deletes.assertCounts(1, 0);
      database.put(KEY, VALUE);
      assertArrayEquals(VALUE, database.get(KEY));
      assertSame(callback, options.logger());

      database.close();
      assertReleased(1, 1);
      assertTrue("The original logging callback must still be invoked", callbacks.get() > 0);
      database.close();
      loggers.get(0).delete();
      loggers.get(0).delete();
      assertReleased(1, 1);
      assertSame(callback, options.logger());
    }
  }

  @Test
  public void shouldReleaseLoggerAfterLockedOpen() throws Exception {
    File path = temporaryFolder.newFolder();
    Options options = options().logger(message -> { });
    try (CapturedOpens ignored = new CapturedOpens()) {
      DB first = open(path, options);
      IOException failure = assertThrows(IOException.class, () -> open(path, options));
      assertTrue(failure.getMessage().toLowerCase(Locale.ROOT).contains("lock"));
      assertEquals(2, loggers.size());
      assertEquals(1, nativeDatabases.size());
      assertTrue(loggers.get(0).isAllocated());
      assertTrue(nativeDatabases.get(0).isAllocated());
      assertLoggerReleased(loggers.get(1));
      deletes.assertCounts(2, 1);
      first.put(KEY, VALUE);
      assertArrayEquals(VALUE, first.get(KEY));

      first.close();
      first.close();
      assertReleased(2, 1);
    }
  }

  @Test
  public void shouldReleaseLoggersAcrossResetAndReopen() throws Exception {
    String name = "leveldb-logger-reset";
    try (CapturedOpens ignored = new CapturedOpens()) {
      LevelDbDataSourceImpl source = new LevelDbDataSourceImpl(
          Args.getInstance().getOutputDirectory(), name);
      databases.add((DB) ReflectionTestUtils.getField(source, "database"));
      try {
        source.putData(KEY, VALUE);
        source.resetDb();
        databases.add((DB) ReflectionTestUtils.getField(source, "database"));
        assertEquals(2, loggers.size());
        assertLoggerReleased(loggers.get(0));
        deletes.assertCounts(2, 1);
        assertFalse(nativeDatabases.get(0).isAllocated());
        assertTrue(loggers.get(1).isAllocated());
        assertNull(source.getData(KEY));
        source.putData(KEY, VALUE);
      } finally {
        source.closeDB();
        source.closeDB();
      }
      assertReleased(2, 2);

      LevelDbDataSourceImpl reopened = new LevelDbDataSourceImpl(
          Args.getInstance().getOutputDirectory(), name);
      databases.add((DB) ReflectionTestUtils.getField(reopened, "database"));
      try {
        assertArrayEquals(VALUE, reopened.getData(KEY));
        assertEquals(3, loggers.size());
        assertTrue(loggers.get(2).isAllocated());
      } finally {
        reopened.closeDB();
        reopened.closeDB();
      }
      assertReleased(3, 3);
    }
  }

  @Test
  public void shouldOpenWithoutUserLogger() throws Exception {
    Options options = options();
    try (CapturedOpens ignored = new CapturedOpens()) {
      DB database = open(temporaryFolder.newFolder(), options);
      assertNull(nativeOptions.get(0).infoLog());
      database.put(KEY, VALUE);
      assertArrayEquals(VALUE, database.get(KEY));
      database.close();
      database.close();
      assertReleased(0, 1);
      assertNull(options.logger());
    }
  }

  @Test
  public void shouldPreserveOptionsAndComparator() throws Exception {
    DBComparator comparator = new ReverseComparator();
    Options options = options().errorIfExists(true).paranoidChecks(true)
        .writeBufferSize(2 * 1024 * 1024).blockSize(8192).blockRestartInterval(8)
        .maxOpenFiles(37).cacheSize(1024 * 1024).comparator(comparator)
        .logger(message -> { });
    try (CapturedOpens ignored = new CapturedOpens()) {
      DB database = open(temporaryFolder.newFolder(), options);
      NativeOptions mapped = nativeOptions.get(0);
      assertTrue(mapped.createIfMissing());
      assertTrue(mapped.errorIfExists());
      assertTrue(mapped.paranoidChecks());
      assertEquals(options.writeBufferSize(), mapped.writeBufferSize());
      assertEquals(options.blockSize(), mapped.blockSize());
      assertEquals(options.blockRestartInterval(), mapped.blockRestartInterval());
      assertEquals(options.maxOpenFiles(), mapped.maxOpenFiles());
      assertEquals(NativeCompressionType.kNoCompression, mapped.compression());
      assertNotNull(mapped.cache());
      assertTrue(mapped.cache().isAllocated());
      assertEquals(comparator.name(), mapped.comparator().name());
      database.put(new byte[]{1}, VALUE);
      database.put(new byte[]{2}, VALUE);
      try (DBIterator iterator = database.iterator()) {
        iterator.seekToFirst();
        assertArrayEquals(new byte[]{2}, iterator.next().getKey());
        assertArrayEquals(new byte[]{1}, iterator.next().getKey());
      }

      database.close();
      assertReleased(1, 1);
      assertFalse(mapped.cache().isAllocated());
    }
  }

  private static Options options() {
    return new Options().createIfMissing(true).cacheSize(0)
        .compressionType(CompressionType.NONE);
  }

  private DB open(File path, Options options) throws IOException {
    DB database = LevelDbFactory.open(path, options);
    databases.add(database);
    return database;
  }

  private void assertReleased(int createdLoggers, int openedDatabases) {
    assertEquals(createdLoggers, loggers.size());
    assertEquals(openedDatabases, nativeDatabases.size());
    assertEquals(createdLoggers, loggers.stream().filter(item -> !item.isAllocated()).count());
    assertEquals(openedDatabases,
        nativeDatabases.stream().filter(item -> !item.isAllocated()).count());
    loggers.forEach(this::assertLoggerReleased);
    deletes.assertCounts(createdLoggers, createdLoggers);
  }

  private void assertLoggerReleased(NativeLogger logger) {
    assertFalse(logger.isAllocated());
    assertEquals(0L, globalReference(logger));
    deletes.assertReleased(logger);
  }

  private static long globalReference(NativeLogger logger) {
    return (Long) ReflectionTestUtils.getField(logger, "globalRef");
  }

  private final class CapturedOpens implements AutoCloseable {
    private final MockedStatic<NativeDB> opens = mockStatic(NativeDB.class, invocation -> {
      if (!"open".equals(invocation.getMethod().getName())) {
        return invocation.callRealMethod();
      }
      NativeOptions options = invocation.getArgument(0);
      nativeOptions.add(options);
      NativeLogger logger = options.infoLog();
      if (logger != null) {
        deletes.track(logger);
        loggers.add(logger);
      }
      NativeDB database = (NativeDB) invocation.callRealMethod();
      nativeDatabases.add(database);
      return database;
    });

    @Override
    public void close() {
      opens.close();
    }
  }

  private static final class ReverseComparator implements DBComparator {
    @Override
    public String name() {
      return "leveldb-logger-resource-reverse";
    }

    @Override
    public int compare(byte[] left, byte[] right) {
      for (int i = 0; i < left.length && i < right.length; i++) {
        int difference = (right[i] & 0xff) - (left[i] & 0xff);
        if (difference != 0) {
          return difference;
        }
      }
      return right.length - left.length;
    }

    @Override
    public byte[] findShortestSeparator(byte[] start, byte[] limit) {
      return start;
    }

    @Override
    public byte[] findShortSuccessor(byte[] key) {
      return key;
    }
  }
}
