package org.tron.core.db2;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.tron.common.TestConstants.TEST_CONF;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;
import org.rocksdb.Logger;
import org.rocksdb.Options;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.Status;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;
import org.tron.common.error.TronDBException;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.setting.RocksDbSettings;
import org.tron.core.config.args.Args;
import org.tron.core.db2.common.DB;
import org.tron.core.db2.common.Value;
import org.tron.core.db2.core.Chainbase;
import org.tron.core.db2.core.Snapshot;
import org.tron.core.db2.core.SnapshotManager;
import org.tron.core.db2.core.SnapshotRoot;
import org.tron.core.exception.TronError;

public class CheckpointV2ResourceTest {

  private static final String STORE_NAME = "checkpoint-resource-test";
  private static final byte[] KEY = new byte[]{1, 2, 3};
  private static final Field DEFAULT_READ_OPTIONS_FIELD =
      ReflectionUtils.findField(RocksDB.class, "defaultReadOptions_");

  @Rule
  public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private final List<Options> options = new ArrayList<>();
  private final List<Logger> loggers = new ArrayList<>();
  private final List<RocksDB> databases = new ArrayList<>();
  private final List<ReadOptions> defaultReadOptions = new ArrayList<>();
  private SnapshotManager manager;
  private SnapshotRoot root;
  private Chainbase chain;
  private byte[] expectedValue;

  @Before
  @SuppressWarnings("unchecked")
  public void setUp() throws Exception {
    Args.setParam(new String[]{"--output-directory",
        temporaryFolder.newFolder().toString()}, TEST_CONF);
    CommonParameter.getInstance().getStorage().setDbEngine("ROCKSDB");
    CommonParameter.getInstance().getStorage().setCheckpointVersion(2);
    manager = new SnapshotManager("");
    ReflectionTestUtils.setField(manager, "checkpointVersion", 2);
    ReflectionTestUtils.setField(manager, "flushCount", 1);
    DB<byte[], byte[]> backing = mock(DB.class);
    when(backing.getDbName()).thenReturn(STORE_NAME);
    root = new SnapshotRoot(backing);
    chain = new Chainbase(root);
    manager.getDbs().add(chain);
  }

  @After
  public void tearDown() {
    try {
      closeCapturedResources();
    } finally {
      Args.clearParam();
    }
  }

  @Test
  public void shouldReleaseResourcesForEveryCheckpoint() throws Exception {
    try (CapturedFactories ignored = new CapturedFactories(Failure.NONE)) {
      for (int i = 1; i <= 5; i++) {
        prepareSnapshot((byte) i);
        manager.createCheckpoint();
        assertResourceCounts(i, i);
      }

      closeCapturedResources();
      closeCapturedResources();
      assertResourceCounts(5, 5);
    }
  }

  @Test
  public void shouldReleaseResourcesAfterCheckpointWriteFailure() throws Exception {
    prepareSnapshot((byte) 1);
    try (CapturedFactories factories = new CapturedFactories(Failure.WRITE)) {
      TronDBException failure = assertThrows(TronDBException.class, manager::createCheckpoint);
      assertTrue(hasCause(failure, factories.failure));
      verify(databases.get(0), times(2)).write(any(WriteOptions.class), any(WriteBatch.class));
      assertResourceCounts(1, 1);
    }
  }

  @Test
  public void shouldReleaseLoggerAfterCheckpointOpenFailure() throws Exception {
    prepareSnapshot((byte) 1);
    try (CapturedFactories factories = new CapturedFactories(Failure.OPEN)) {
      TronError failure = assertThrows(TronError.class, manager::createCheckpoint);
      assertEquals(TronError.ErrCode.ROCKSDB_INIT, failure.getErrCode());
      assertSame(factories.failure, failure.getCause());
      assertResourceCounts(1, 0);
    }
  }

  private void prepareSnapshot(byte value) {
    Snapshot head = root.advance();
    expectedValue = new byte[]{value};
    head.put(KEY, expectedValue);
    chain.setHead(head);
  }

  private void assertResourceCounts(int createdOptions, int openedDatabases) {
    assertEquals(createdOptions, options.size());
    assertEquals(createdOptions, loggers.size());
    assertEquals(openedDatabases, databases.size());
    assertEquals(DEFAULT_READ_OPTIONS_FIELD == null ? 0 : openedDatabases,
        defaultReadOptions.size());
    assertEquals(createdOptions, options.stream().filter(item -> !item.isOwningHandle()).count());
    assertEquals(createdOptions, loggers.stream().filter(item -> !item.isOwningHandle()).count());
    assertEquals(openedDatabases,
        databases.stream().filter(item -> !item.isOwningHandle()).count());
    assertEquals(defaultReadOptions.size(),
        defaultReadOptions.stream().filter(item -> !item.isOwningHandle()).count());
  }

  private void closeCapturedResources() {
    databases.forEach(RocksDB::close);
    defaultReadOptions.forEach(ReadOptions::close);
    options.forEach(Options::close);
  }

  private static boolean hasCause(Throwable failure, Throwable expected) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current == expected) {
        return true;
      }
    }
    return false;
  }

  private static byte[] encodedKey() {
    byte[] name = STORE_NAME.getBytes(StandardCharsets.UTF_8);
    return ByteBuffer.allocate(Integer.BYTES + name.length + KEY.length)
        .putInt(name.length).put(name).put(KEY).array();
  }

  private enum Failure {
    NONE,
    OPEN,
    WRITE
  }

  private final class CapturedFactories implements AutoCloseable {
    private final RocksDBException failure = new RocksDBException(
        new Status(Status.Code.IOError, Status.SubCode.None, "injected checkpoint failure"));
    private final MockedStatic<RocksDbSettings> settings;
    private final MockedStatic<RocksDB> opens;

    private CapturedFactories(Failure failureMode) {
      settings = mockStatic(RocksDbSettings.class, invocation -> {
        Object result = invocation.callRealMethod();
        if ("getOptionsByDbName".equals(invocation.getMethod().getName())) {
          Options created = (Options) result;
          options.add(created);
          loggers.add((Logger) ReflectionTestUtils.getField(created, "dbLogger"));
        }
        return result;
      });
      opens = mockStatic(RocksDB.class, invocation -> {
        boolean checkpointOpen = "open".equals(invocation.getMethod().getName())
            && invocation.getMethod().getParameterTypes().length == 2
            && invocation.getMethod().getParameterTypes()[0] == Options.class;
        if (checkpointOpen && failureMode == Failure.OPEN) {
          throw failure;
        }
        Object result = invocation.callRealMethod();
        if (!checkpointOpen) {
          return result;
        }
        RocksDB database = spy((RocksDB) result);
        databases.add(database);
        if (DEFAULT_READ_OPTIONS_FIELD != null) {
          ReflectionUtils.makeAccessible(DEFAULT_READ_OPTIONS_FIELD);
          ReadOptions readOptions =
              (ReadOptions) ReflectionUtils.getField(DEFAULT_READ_OPTIONS_FIELD, database);
          assertTrue(readOptions.isOwningHandle());
          defaultReadOptions.add(readOptions);
        }
        if (failureMode == Failure.WRITE) {
          doThrow(failure).when(database).write(any(WriteOptions.class), any(WriteBatch.class));
        } else {
          byte[] checkpointValue = Value.copyOf(Value.Operator.PUT, expectedValue).encode();
          doAnswer(write -> {
            Object written = write.callRealMethod();
            assertTrue(database.isOwningHandle());
            try (RocksIterator iterator = database.newIterator()) {
              iterator.seek(encodedKey());
              assertTrue(iterator.isValid());
              assertArrayEquals(encodedKey(), iterator.key());
              assertArrayEquals(checkpointValue, iterator.value());
            }
            return written;
          }).when(database).write(any(WriteOptions.class), any(WriteBatch.class));
        }
        return database;
      });
    }

    @Override
    public void close() {
      opens.close();
      settings.close();
    }
  }
}
