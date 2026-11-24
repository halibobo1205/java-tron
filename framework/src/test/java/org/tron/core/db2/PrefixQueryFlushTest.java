package org.tron.core.db2;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.lang3.ArrayUtils;
import org.junit.Test;
import org.tron.common.BaseMethodTest;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.storage.leveldb.LevelDbDataSourceImpl;
import org.tron.common.storage.rocksdb.RocksDbDataSourceImpl;
import org.tron.common.utils.StorageUtils;
import org.tron.core.db.TronStoreWithRevoking;
import org.tron.core.db2.SnapshotRootTest.ProtoCapsuleTest;
import org.tron.core.db2.common.DB;
import org.tron.core.db2.common.LevelDB;
import org.tron.core.db2.common.RocksDB;
import org.tron.core.db2.common.WrappedByteArray;
import org.tron.core.db2.core.Chainbase;
import org.tron.core.db2.core.SnapshotManager;

public class PrefixQueryFlushTest extends BaseMethodTest {

  private static final byte[] PREFIX = bytes("prefix-");

  private SnapshotManager snapshots;

  @Override
  protected void afterInit() {
    snapshots = context.getBean(SnapshotManager.class);
    snapshots.enable();
    while (snapshots.size() != 0) {
      snapshots.pop();
    }
  }

  @Test
  public void prefixQueryKeepsLayersFlushedDuringTheRootScan() throws Exception {
    AtomicReference<Runnable> afterRootScan = new AtomicReference<>();
    HookedStore store = new HookedStore(hookedRoot("prefix-flush-test", afterRootScan));
    snapshots.add(store.getRevokingDB());
    snapshots.setMaxSize(2);
    ExecutorService writer = Executors.newSingleThreadExecutor();
    try {
      commit(store, "prefix-1", "k1");
      commit(store, "prefix-2", "x2");
      afterRootScan.set(() -> {
        try {
          writer.submit(() -> {
            commit(store, "prefix-3", "x3");
            commit(store, "prefix-4", "x4");
          }).get();
        } catch (Exception e) {
          throw new AssertionError(e);
        }
      });

      Map<WrappedByteArray, byte[]> found = store.getRevokingDB().prefixQuery(PREFIX);

      assertArrayEquals(bytes("k1"), ((Chainbase) store.getRevokingDB()).getHead().getRoot()
          .get(bytes("prefix-1")));
      byte[] flushed = found.get(WrappedByteArray.of(bytes("prefix-1")));
      assertNotNull(flushed);
      assertArrayEquals(bytes("k1"), flushed);
    } finally {
      writer.shutdownNow();
      store.close();
    }
  }

  private void commit(HookedStore store, String key, String value) {
    try (ISession session = snapshots.buildSession()) {
      store.put(bytes(key), new ProtoCapsuleTest(bytes(value)));
      session.commit();
    }
  }

  private static DB<byte[], byte[]> hookedRoot(String name, AtomicReference<Runnable> hook) {
    String dir = StorageUtils.getOutputDirectoryByDbName(name);
    if ("LEVELDB".equalsIgnoreCase(CommonParameter.getInstance().getStorage().getDbEngine())) {
      return new LevelDB(new LevelDbDataSourceImpl(dir, name) {
        @Override
        public Map<WrappedByteArray, byte[]> prefixQuery(byte[] key) {
          return runAfter(super.prefixQuery(key), hook);
        }
      });
    }
    String parent = Paths.get(dir, CommonParameter.getInstance().getStorage().getDbDirectory())
        .toString();
    return new RocksDB(new RocksDbDataSourceImpl(parent, name) {
      @Override
      public Map<WrappedByteArray, byte[]> prefixQuery(byte[] key) {
        return runAfter(super.prefixQuery(key), hook);
      }
    });
  }

  private static <T> T runAfter(T result, AtomicReference<Runnable> hook) {
    Runnable action = hook.getAndSet(null);
    if (action != null) {
      action.run();
    }
    return result;
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static final class HookedStore extends TronStoreWithRevoking<ProtoCapsuleTest> {

    private HookedStore(DB<byte[], byte[]> db) {
      super(db);
    }

    @Override
    public ProtoCapsuleTest get(byte[] key) {
      byte[] value = this.revokingDB.getUnchecked(key);
      return ArrayUtils.isEmpty(value) ? null : new ProtoCapsuleTest(value);
    }
  }
}
