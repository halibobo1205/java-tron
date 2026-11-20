package org.tron.common.storage.leveldb;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
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
import static org.tron.common.TestConstants.assumeLevelDbAvailable;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.fusesource.leveldbjni.internal.JniDB;
import org.fusesource.leveldbjni.internal.NativeDB;
import org.fusesource.leveldbjni.internal.NativeLogger;
import org.fusesource.leveldbjni.internal.NativeOptions;
import org.iq80.leveldb.DBException;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.WriteBatch;
import org.iq80.leveldb.WriteOptions;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;
import org.tron.common.error.TronDBException;
import org.tron.common.parameter.CommonParameter;
import org.tron.core.config.args.Args;
import org.tron.core.db2.common.DB;
import org.tron.core.db2.common.Value;
import org.tron.core.db2.core.Chainbase;
import org.tron.core.db2.core.Snapshot;
import org.tron.core.db2.core.SnapshotManager;
import org.tron.core.db2.core.SnapshotRoot;
import org.tron.core.exception.TronError;

public class CheckpointV2LevelDbResourceTest {

  private static final String STORE_NAME = "checkpoint-leveldb-resource-test";
  private static final byte[] KEY = new byte[]{1, 2, 3};

  @Rule
  public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private final List<NativeLogger> loggers = new ArrayList<>();
  private final List<JniDB> databases = new ArrayList<>();
  private LevelDbLoggerDeletes deletes;
  private SnapshotManager manager;
  private SnapshotRoot root;
  private Chainbase chain;
  private byte[] expectedValue;

  @Before
  @SuppressWarnings("unchecked")
  public void setUp() throws Exception {
    assumeLevelDbAvailable();
    Args.setParam(new String[]{"--output-directory",
        temporaryFolder.newFolder().toString()}, TEST_CONF);
    CommonParameter.getInstance().getStorage().setDbEngine("LEVELDB");
    CommonParameter.getInstance().getStorage().setCheckpointVersion(2);
    manager = new SnapshotManager("");
    ReflectionTestUtils.setField(manager, "checkpointVersion", 2);
    ReflectionTestUtils.setField(manager, "flushCount", 1);
    DB<byte[], byte[]> backing = mock(DB.class);
    when(backing.getDbName()).thenReturn(STORE_NAME);
    root = new SnapshotRoot(backing);
    chain = new Chainbase(root);
    manager.getDbs().add(chain);
    deletes = new LevelDbLoggerDeletes();
  }

  @After
  public void tearDown() throws Exception {
    try (LevelDbLoggerDeletes ignored = deletes) {
      closeCapturedResources();
    } finally {
      Args.clearParam();
    }
  }

  @Test
  public void shouldReleaseLoggerForEveryCheckpoint() throws Exception {
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
  public void shouldReleaseLoggerAfterCheckpointWriteFailure() throws Exception {
    prepareSnapshot((byte) 1);
    try (CapturedFactories factories = new CapturedFactories(Failure.WRITE)) {
      TronDBException failure = assertThrows(TronDBException.class, manager::createCheckpoint);
      assertTrue(hasCause(failure, factories.writeFailure));
      verify(databases.get(0), times(2)).write(any(WriteBatch.class), any(WriteOptions.class));
      assertResourceCounts(1, 1);
    }
  }

  @Test
  public void shouldReleaseLoggerAfterCheckpointOpenFailure() throws Exception {
    prepareSnapshot((byte) 1);
    try (CapturedFactories factories = new CapturedFactories(Failure.OPEN)) {
      TronError failure = assertThrows(TronError.class, manager::createCheckpoint);
      assertEquals(TronError.ErrCode.LEVELDB_INIT, failure.getErrCode());
      assertSame(factories.openFailure, failure.getCause());
      assertResourceCounts(1, 0);
      loggers.get(0).delete();
      loggers.get(0).delete();
      assertResourceCounts(1, 0);
    }
  }

  private void prepareSnapshot(byte value) {
    Snapshot head = root.advance();
    expectedValue = new byte[]{value};
    head.put(KEY, expectedValue);
    chain.setHead(head);
  }

  private void assertResourceCounts(int createdLoggers, int openedDatabases) {
    assertEquals(createdLoggers, loggers.size());
    assertEquals(openedDatabases, databases.size());
    assertEquals(createdLoggers, loggers.stream().filter(item -> nativeAddress(item) == 0).count());
    assertEquals(createdLoggers, loggers.stream().filter(item -> globalRef(item) == 0).count());
    loggers.forEach(deletes::assertReleased);
    deletes.assertCounts(createdLoggers, createdLoggers);
    for (JniDB database : databases) {
      assertNull(ReflectionTestUtils.getField(database, "db"));
      assertNull(ReflectionTestUtils.getField(database, "logger"));
    }
  }

  private void closeCapturedResources() {
    databases.forEach(JniDB::close);
    loggers.forEach(NativeLogger::delete);
  }

  private static long nativeAddress(NativeLogger logger) {
    return (Long) ReflectionTestUtils.getField(logger, "self");
  }

  private static long globalRef(NativeLogger logger) {
    return (Long) ReflectionTestUtils.getField(logger, "globalRef");
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
    private final IOException openFailure = new IOException("injected checkpoint open failure");
    private final DBException writeFailure = new DBException("injected checkpoint write failure");
    private final MockedStatic<NativeDB> nativeOpens;
    private final MockedStatic<LevelDbFactory> opens;

    private CapturedFactories(Failure failureMode) {
      nativeOpens = mockStatic(NativeDB.class, invocation -> {
        boolean nativeOpen = "open".equals(invocation.getMethod().getName())
            && invocation.getMethod().getParameterTypes().length == 2
            && invocation.getMethod().getParameterTypes()[0] == NativeOptions.class;
        if (nativeOpen) {
          NativeLogger logger = ((NativeOptions) invocation.getArgument(0)).infoLog();
          assertTrue(nativeAddress(logger) != 0);
          assertTrue(globalRef(logger) != 0);
          deletes.track(logger);
          loggers.add(logger);
          if (failureMode == Failure.OPEN) {
            throw openFailure;
          }
        }
        return invocation.callRealMethod();
      });
      opens = mockStatic(LevelDbFactory.class, invocation -> {
        Object result = invocation.callRealMethod();
        boolean checkpointOpen = "open".equals(invocation.getMethod().getName())
            && invocation.getMethod().getParameterTypes().length == 2
            && invocation.getMethod().getParameterTypes()[0] == File.class
            && invocation.getMethod().getParameterTypes()[1] == Options.class;
        if (!checkpointOpen) {
          return result;
        }
        JniDB database = spy((JniDB) result);
        databases.add(database);
        if (failureMode == Failure.WRITE) {
          doThrow(writeFailure).when(database)
              .write(any(WriteBatch.class), any(WriteOptions.class));
        } else {
          byte[] checkpointValue = Value.copyOf(Value.Operator.PUT, expectedValue).encode();
          doAnswer(write -> {
            Object written = write.callRealMethod();
            assertArrayEquals(checkpointValue, database.get(encodedKey()));
            return written;
          }).when(database).write(any(WriteBatch.class), any(WriteOptions.class));
        }
        return database;
      });
    }

    @Override
    public void close() {
      opens.close();
      nativeOpens.close();
    }
  }
}
