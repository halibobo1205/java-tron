package org.tron.core.db2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.apache.commons.lang3.ArrayUtils;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.tron.common.BaseMethodTest;
import org.tron.common.exit.ExitManager;
import org.tron.common.exit.ExitManagerTestUtil;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.storage.leveldb.LevelDbDataSourceImpl;
import org.tron.common.storage.rocksdb.RocksDbDataSourceImpl;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.Sha256Hash;
import org.tron.common.utils.StorageUtils;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BlockCapsule.BlockId;
import org.tron.core.capsule.utils.MarketUtils;
import org.tron.core.db.TronStoreWithRevoking;
import org.tron.core.db2.RevokingDbWithCacheNewValueTest.TestRevokingTronStore;
import org.tron.core.db2.SnapshotRootTest.ProtoCapsuleTest;
import org.tron.core.db2.common.DB;
import org.tron.core.db2.common.Flusher;
import org.tron.core.db2.common.LevelDB;
import org.tron.core.db2.common.RocksDB;
import org.tron.core.db2.common.WrappedByteArray;
import org.tron.core.db2.core.Chainbase;
import org.tron.core.db2.core.RefreshObserver;
import org.tron.core.db2.core.Snapshot;
import org.tron.core.db2.core.SnapshotManager;
import org.tron.core.db2.view.HeadLease;
import org.tron.core.db2.view.HeadView;
import org.tron.core.db2.view.HeadViewException;
import org.tron.core.db2.view.HeadViewException.Reason;
import org.tron.core.db2.view.HeadViewRegistry;
import org.tron.core.db2.view.QueryViewScope;
import org.tron.core.exception.ItemNotFoundException;
import org.tron.core.exception.TronError;
import org.tron.core.store.AccountAssetStore;
import org.tron.core.store.AccountStore;
import org.tron.core.store.DynamicPropertiesStore;
import org.tron.protos.Protocol.Account;
import org.tron.protos.Protocol.BlockHeader;

public class CommittedHeadViewTest extends BaseMethodTest {

  private static final String KEY = "key";
  private static final String TOKEN_A = "1000001";
  private static final String TOKEN_B = "1000002";

  private SnapshotManager snapshots;
  private TestRevokingTronStore store;
  private HeadViewRegistry registry;

  @Override
  protected void afterInit() {
    snapshots = context.getBean(SnapshotManager.class);
    snapshots.enable();
    store = new TestRevokingTronStore("committed-head-view-test");
    snapshots.add(store.getRevokingDB());
    while (snapshots.size() != 0) {
      snapshots.pop();
    }
    registry = new HeadViewRegistry(4);
    snapshots.setRefreshObserver(registry);
    registry.markReady();
  }

  @Override
  protected void beforeDestroy() {
    snapshots.setRefreshObserver(null);
    store.close();
  }

  @Test
  public void leaseIgnoresPendingAndLaterCommits() {
    commit(KEY, "v1");
    publish(1);
    HeadLease lease = registry.acquire();

    try (ISession pending = snapshots.buildSession()) {
      put(KEY, "pending");
      assertEquals("v1", readInScope(lease, KEY));
      assertEquals("pending", read(KEY));
    }

    commit(KEY, "v2");
    assertEquals("v1", readInScope(lease, KEY));
    assertTrue(lease.seal());
    lease.close();

    publish(2);
    HeadLease next = registry.acquire();
    assertEquals("v2", readInScope(next, KEY));
    assertTrue(next.seal());
    next.close();
  }

  @Test
  public void deletedKeyDoesNotFallBackToRoot() {
    put(KEY, "root");
    put("other", "root-only");
    try (ISession session = snapshots.buildSession()) {
      store.delete(bytes(KEY));
      session.commit();
    }
    publish(1);
    HeadLease lease = registry.acquire();
    try (ISession pending = snapshots.buildSession()) {
      put(KEY, "pending");
      assertEquals("pending", read(KEY));
      assertNull(readInScope(lease, KEY));
    }
    assertEquals("root", rootValue(KEY));
    assertEquals("root-only", readInScope(lease, "other"));
    assertTrue(lease.seal());
    lease.close();
  }

  @Test
  public void flushInsideTheViewKeepsTheLease() {
    snapshots.setMaxSize(2);
    commit(KEY, "v1");
    commit("only-in-1", "x");
    publish(2);
    HeadLease lease = registry.acquire();

    commit(KEY, "v3");
    commit(KEY, "v4");

    assertEquals("v1", rootValue(KEY));
    assertEquals("v4", read(KEY));
    assertEquals("v1", readInScope(lease, KEY));
    assertEquals("x", readInScope(lease, "only-in-1"));
    assertTrue(lease.seal());
    lease.close();
  }

  @Test
  public void flushPastTheViewExpiresIt() {
    snapshots.setMaxSize(2);
    commit(KEY, "v1");
    publish(1);
    HeadLease lease = registry.acquire();

    commit(KEY, "v2");
    commit(KEY, "v3");
    commit(KEY, "v4");
    assertEquals("v1", readInScope(lease, KEY));

    commit(KEY, "v5");
    HeadViewException expired =
        assertThrows(HeadViewException.class, () -> readInScope(lease, KEY));
    assertEquals(Reason.EXPIRED, expired.getReason());
    assertFalse(lease.seal());
    lease.close();

    HeadViewException unavailable = assertThrows(HeadViewException.class, registry::acquire);
    assertEquals(Reason.EXPIRED, unavailable.getReason());
  }

  @Test
  public void writeToPublishedLayerPoisonsViews() {
    commit(KEY, "v1");
    publish(1);
    HeadLease lease = registry.acquire();

    put(KEY, "unexpected");

    assertFalse(lease.seal());
    assertEquals(Reason.UNAVAILABLE, lease.getFault());
    lease.close();
    HeadViewException rejected = assertThrows(HeadViewException.class, registry::acquire);
    assertEquals(Reason.UNAVAILABLE, rejected.getReason());

    commit(KEY, "v2");
    publish(2);
    HeadLease recovered = registry.acquire();
    assertEquals("v2", readInScope(recovered, KEY));
    assertTrue(recovered.seal());
    recovered.close();
  }

  @Test
  public void writesAndRootReadsAreRejectedInScope() {
    commit(KEY, "v1");
    publish(1);
    HeadLease lease = registry.acquire();
    Chainbase db = (Chainbase) store.getRevokingDB();
    List<Runnable> rejected = new ArrayList<>();
    rejected.add(() -> {
      try {
        db.getFromRoot(bytes(KEY));
      } catch (ItemNotFoundException e) {
        throw new AssertionError(e);
      }
    });
    rejected.add(() -> db.put(bytes(KEY), bytes("x")));
    rejected.add(() -> db.delete(bytes(KEY)));
    try (QueryViewScope ignored = QueryViewScope.bind(lease)) {
      for (Runnable operation : rejected) {
        HeadViewException e = assertThrows(HeadViewException.class, operation::run);
        assertEquals(Reason.UNSUPPORTED, e.getReason());
      }
    }
    assertFalse(lease.seal());
    assertEquals(Reason.UNSUPPORTED, lease.getFault());
    lease.close();
    assertEquals("v1", read(KEY));
  }

  @Test
  public void rangeReadsInScopeStayOnTheView() {
    commitPairs("a", 1, 2, 3);
    try (ISession session = snapshots.buildSession()) {
      putPair(2, "b");
      store.delete(pair(3));
      session.commit();
    }
    publish(2);
    List<String> expected = rangeReads();
    HeadLease lease = registry.acquire();

    commitPairs("c", 1, 4);
    try (ISession pending = snapshots.buildSession()) {
      putPair(2, "p");
      store.delete(pair(4));
      assertNotEquals(expected, rangeReads());
      assertEquals(expected, rangeReadsInScope(lease));
    }
    assertTrue(lease.seal());
    lease.close();
  }

  @Test
  public void rangeReadsOnARootOnlyViewStayOnTheView() {
    putPair(1, "a");
    putPair(2, "a");
    publish(0);
    List<String> expected = rangeReads();
    HeadLease lease = registry.acquire();

    commitPairs("b", 1, 3);
    try (ISession pending = snapshots.buildSession()) {
      putPair(2, "p");
      store.delete(pair(3));
      assertNotEquals(expected, rangeReads());
      assertEquals(expected, rangeReadsInScope(lease));
    }
    assertTrue(lease.seal());
    lease.close();
  }

  @Test
  public void prefixQueryReadsTheLayersBeforeTheRoot() throws Exception {
    AtomicReference<Runnable> afterRootScan = new AtomicReference<>();
    FailingStore hooked = new FailingStore(hookedRoot("committed-head-prefix-test",
        afterRootScan));
    snapshots.add(hooked.getRevokingDB());
    snapshots.setMaxSize(2);
    ExecutorService writer = Executors.newSingleThreadExecutor();
    try {
      commitTo(hooked, pair(1), "k1");
      commitTo(hooked, pair(2), "x2");
      publish(2);
      HeadLease lease = registry.acquire();
      afterRootScan.set(() -> {
        try {
          writer.submit(() -> {
            commitTo(hooked, pair(3), "x3");
            commitTo(hooked, pair(4), "x4");
          }).get();
        } catch (Exception e) {
          throw new AssertionError(e);
        }
      });

      Map<WrappedByteArray, byte[]> found;
      try (QueryViewScope ignored = QueryViewScope.bind(lease)) {
        found = hooked.getRevokingDB().prefixQuery(
            MarketUtils.createPairKey(bytes(TOKEN_A), bytes(TOKEN_B)));
      }
      assertEquals("k1", rootValueOf(hooked, pair(1)));
      assertEquals(2, found.size());
      assertEquals("k1", new String(found.get(WrappedByteArray.of(pair(1))),
          StandardCharsets.UTF_8));
      assertTrue(lease.seal());
      lease.close();
    } finally {
      writer.shutdownNow();
      hooked.close();
    }
  }

  @Test
  public void latestValuesStayDistinctAfterTheViewTopIsFlushed() {
    snapshots.setMaxSize(2);
    for (long number = 1; number <= 3; number++) {
      commitNumbered(number);
    }
    publish(3);
    HeadLease lease = registry.acquire();
    for (long number = 4; number <= 6; number++) {
      commitNumbered(number);
    }
    assertEquals("b3", rootValue(ByteArray.fromLong(3)));

    List<String> latest;
    try (QueryViewScope ignored = QueryViewScope.bind(lease)) {
      latest = store.getRevokingDB().getlatestValues(3).stream()
          .map(value -> new String(value, StandardCharsets.UTF_8)).sorted()
          .collect(Collectors.toList());
    }
    assertEquals(Arrays.asList("b1", "b2", "b3"), latest);
    assertTrue(lease.seal());
    lease.close();
  }

  @Test
  public void rangeReadsSurviveSafeFlushesAndExpireAfterAnUnsafeOne() {
    snapshots.setMaxSize(2);
    commitPairs("a", 1, 2);
    commitPairs("b", 2, 3);
    publish(2);
    List<String> expected = stateReads();
    HeadLease lease = registry.acquire();

    commitPairs("c", 1, 4);
    commitPairs("d", 3);
    assertEquals("a1", rootValue(pair(1)));
    assertEquals(expected, stateReadsInScope(lease));

    commitPairs("e", 2);
    assertEquals("b3", rootValue(pair(3)));
    assertEquals(expected, stateReadsInScope(lease));

    commitPairs("f", 1);
    HeadViewException expired =
        assertThrows(HeadViewException.class, () -> stateReadsInScope(lease));
    assertEquals(Reason.EXPIRED, expired.getReason());
    assertFalse(lease.seal());
    lease.close();
  }

  @Test
  public void swallowedFaultStillFailsTheSeal() {
    commit(KEY, "v1");
    publish(1);
    HeadLease lease = registry.acquire();
    try (QueryViewScope ignored = QueryViewScope.bind(lease)) {
      try {
        store.getRevokingDB().put(bytes(KEY), bytes("x"));
      } catch (HeadViewException expected) {
        assertEquals(Reason.UNSUPPORTED, expected.getReason());
      }
      assertThrows(HeadViewException.class, () -> store.get(bytes(KEY)));
    }
    assertFalse(lease.seal());
    lease.close();
  }

  @Test
  public void incompleteRefreshDisablesTheRegistry() {
    commit(KEY, "v1");
    publish(1);
    HeadLease lease = registry.acquire();
    registry.refreshIncomplete();
    assertFalse(lease.seal());
    lease.close();
    commit(KEY, "v2");
    publish(2);
    assertThrows(HeadViewException.class, registry::acquire);
  }

  @Test
  public void admissionAndScopeNesting() {
    commit(KEY, "v1");
    publish(1);
    HeadLease[] leases = new HeadLease[4];
    for (int i = 0; i < leases.length; i++) {
      leases[i] = registry.acquire();
    }
    HeadViewException full = assertThrows(HeadViewException.class, registry::acquire);
    assertEquals(Reason.UNAVAILABLE, full.getReason());

    try (QueryViewScope outer = QueryViewScope.bind(leases[0])) {
      try (QueryViewScope inner = QueryViewScope.bind(leases[0])) {
        assertEquals("v1", read(KEY));
      }
      assertEquals(leases[0], QueryViewScope.current());
      assertThrows(HeadViewException.class, () -> QueryViewScope.bind(leases[1]));
    }
    assertNull(QueryViewScope.current());

    leases[0].close();
    leases[0].close();
    HeadLease reacquired = registry.acquire();
    HeadViewException stillFull = assertThrows(HeadViewException.class, registry::acquire);
    assertEquals(Reason.UNAVAILABLE, stillFull.getReason());
    reacquired.close();
    for (int i = 1; i < leases.length; i++) {
      leases[i].close();
    }
  }

  @Test
  public void notReadyBeforeMarkReady() {
    HeadViewRegistry starting = new HeadViewRegistry(4);
    snapshots.setRefreshObserver(starting);
    long generation = starting.currentGeneration();
    starting.publish(newView(0, starting), generation);
    assertThrows(HeadViewException.class, starting::acquire);
    starting.markReadyOnNextPublish();
    assertThrows(HeadViewException.class, starting::acquire);
    commit(KEY, "v1");
    generation = starting.currentGeneration();
    starting.publish(newView(1, starting), generation);
    HeadLease lease = starting.acquire();
    assertEquals(1, lease.getView().getBlockNum());
    lease.close();
    starting.beginClosing();
    assertThrows(HeadViewException.class, starting::acquire);
  }

  @Test
  public void multiLayerFlushUsesTheLastFlushedLayer() {
    snapshots.setMaxSize(2);
    snapshots.setMaxFlushCount(2);
    commit(KEY, "v1");
    HeadView first = publish(1);
    HeadLease onFirst = registry.acquire();
    commit(KEY, "v2");
    publish(2);
    HeadLease onSecond = registry.acquire();

    commit(KEY, "v3");
    commit(KEY, "v4");
    assertTrue(first.isValid());
    commit(KEY, "v5");

    assertEquals("v2", rootValue(KEY));
    assertFalse(first.isValid());
    assertFalse(onFirst.seal());
    onFirst.close();
    assertEquals("v2", readInScope(onSecond, KEY));
    assertTrue(onSecond.seal());
    onSecond.close();
  }

  @Test
  public void observerFailureDoesNotBlockTheFlush() {
    ObserverProbe probe = new ObserverProbe(registry, true);
    snapshots.setRefreshObserver(probe);
    snapshots.setMaxSize(2);
    commit(KEY, "v1");
    publish(1);
    HeadLease lease = registry.acquire();
    long generation = registry.currentGeneration();

    commit(KEY, "v2");
    commit(KEY, "v3");
    commit(KEY, "v4");

    assertEquals(1, probe.beforeCalls);
    assertEquals(1, probe.failures);
    assertEquals("v1", rootValue(KEY));
    assertTrue(registry.currentGeneration() > generation);
    assertFalse(lease.seal());
    lease.close();
    assertThrows(HeadViewException.class, registry::acquire);

    publish(4);
    HeadLease recovered = registry.acquire();
    assertEquals("v4", readInScope(recovered, KEY));
    assertTrue(recovered.seal());
    recovered.close();
  }

  @Test
  public void invalidationHappensBeforeTheRootIsWritten() {
    ObserverProbe probe = new ObserverProbe(registry, false);
    snapshots.setRefreshObserver(probe);
    snapshots.setMaxSize(2);
    commit(KEY, "v1");
    HeadView view = publish(1);
    List<String> observed = new ArrayList<>();
    probe.afterInvalidation = () -> observed.add(view.isValid() + ":" + rootValue(KEY));

    commit(KEY, "v2");
    commit(KEY, "v3");
    commit(KEY, "v4");
    commit(KEY, "v5");

    assertEquals(2, observed.size());
    assertEquals("true:null", observed.get(0));
    assertEquals("false:v1", observed.get(1));
    assertEquals("v2", rootValue(KEY));
  }

  @Test
  public void failedRefreshKeepsTheOriginalErrorAndDisablesTheRegistry() {
    ObserverProbe probe = new ObserverProbe(registry, false);
    snapshots.setRefreshObserver(probe);
    TestRevokingTronStore base = new TestRevokingTronStore("committed-head-failing-test");
    FailingDb failingDb = new FailingDb(base.getDb());
    FailingStore failing = new FailingStore(failingDb);
    snapshots.add(failing.getRevokingDB());
    snapshots.setMaxSize(2);
    try (MockedStatic<ExitManager> exit = ExitManagerTestUtil.suppressExit()) {
      commit(KEY, "v1");
      publish(1);
      commit(KEY, "v2");
      commit(KEY, "v3");
      failingDb.fail = true;

      TronError error = assertThrows(TronError.class, () -> commit(KEY, "v4"));
      assertEquals(TronError.ErrCode.DB_FLUSH, error.getErrCode());
      assertTrue(hasCause(error, FailingDb.INJECTED));
      exit.verify(() -> ExitManager.logAndExit(error));
      assertEquals(1, probe.incompleteCalls);
      assertThrows(HeadViewException.class, registry::acquire);
    } finally {
      failingDb.fail = false;
      base.close();
    }
  }

  @Test
  public void optimizedAssetsStayConsistentAcrossASafeFlush() {
    DynamicPropertiesStore properties = chainBaseManager.getDynamicPropertiesStore();
    AccountStore accounts = chainBaseManager.getAccountStore();
    AccountAssetStore assets = chainBaseManager.getAccountAssetStore();
    properties.setAllowAssetOptimization(1);
    byte[] address = ByteArray.fromHexString("41548794500882809695a8a687866e76d4271a1abc");
    accounts.put(address, new AccountCapsule(Account.newBuilder()
        .setAddress(ByteString.copyFrom(address))
        .putAssetV2(TOKEN_A, 100)
        .putAssetV2(TOKEN_B, 200)
        .build()));
    assertTrue(accounts.get(address).getAssetOptimized());
    assertTrue(accounts.get(address).getInstance().getAssetV2Map().isEmpty());

    snapshots.setMaxSize(2);
    try (ISession session = snapshots.buildSession()) {
      AccountCapsule account = accounts.get(address);
      account.importAsset(bytes(TOKEN_A));
      account.setInstance(account.getInstance().toBuilder().putAssetV2(TOKEN_A, 50).build());
      accounts.put(address, account);
      session.commit();
    }
    publish(1);
    HeadLease lease = registry.acquire();
    Map<String, Long> before = assetsInScope(lease, accounts, address);
    assertEquals(Long.valueOf(50), before.get(TOKEN_A));
    assertEquals(Long.valueOf(200), before.get(TOKEN_B));
    assertEquals(100, assets.getBalance(Account.newBuilder()
        .setAddress(ByteString.copyFrom(address)).setAssetOptimized(true).build(),
        bytes(TOKEN_A)));

    commit(KEY, "v2");
    commit(KEY, "v3");
    commit(KEY, "v4");

    assertEquals(50, assets.getBalance(Account.newBuilder()
        .setAddress(ByteString.copyFrom(address)).setAssetOptimized(true).build(),
        bytes(TOKEN_A)));
    assertEquals(before, assetsInScope(lease, accounts, address));
    assertTrue(lease.seal());
    lease.close();
  }

  private static Map<String, Long> assetsInScope(HeadLease lease, AccountStore accounts,
      byte[] address) {
    try (QueryViewScope ignored = QueryViewScope.bind(lease)) {
      return new HashMap<>(accounts.get(address).getAssetMapV2());
    }
  }

  private static boolean hasCause(Throwable error, String message) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      if (message.equals(t.getMessage())) {
        return true;
      }
    }
    return false;
  }

  private void commitPairs(String prefix, int... prices) {
    try (ISession session = snapshots.buildSession()) {
      for (int price : prices) {
        putPair(price, prefix);
      }
      session.commit();
    }
  }

  private void commitNumbered(long number) {
    try (ISession session = snapshots.buildSession()) {
      store.put(ByteArray.fromLong(number), new ProtoCapsuleTest(bytes("b" + number)));
      session.commit();
    }
  }

  private void putPair(int price, String prefix) {
    store.put(pair(price), new ProtoCapsuleTest(bytes(prefix + price)));
  }

  private static byte[] pair(int price) {
    return MarketUtils.createPairPriceKey(bytes(TOKEN_A), bytes(TOKEN_B), 1, price);
  }

  private List<String> rangeReadsInScope(HeadLease lease) {
    try (QueryViewScope ignored = QueryViewScope.bind(lease)) {
      return rangeReads();
    }
  }

  private List<String> stateReadsInScope(HeadLease lease) {
    try (QueryViewScope ignored = QueryViewScope.bind(lease)) {
      return stateReads();
    }
  }

  private List<String> rangeReads() {
    List<String> reads = stateReads();
    Chainbase db = (Chainbase) store.getRevokingDB();
    reads.add("getlatestValues " + sorted(db.getlatestValues(2)));
    return reads;
  }

  private List<String> stateReads() {
    Chainbase db = (Chainbase) store.getRevokingDB();
    List<String> iterated = new ArrayList<>();
    db.iterator().forEachRemaining(e -> iterated.add(entry(e.getKey(), e.getValue())));
    List<String> next = db.getNext(pair(1), 2).entrySet().stream()
        .map(e -> entry(e.getKey(), e.getValue())).collect(Collectors.toList());
    List<String> prefixed = db.prefixQuery(MarketUtils.createPairKey(bytes(TOKEN_A),
        bytes(TOKEN_B))).entrySet().stream()
        .map(e -> entry(e.getKey().getBytes(), e.getValue())).collect(Collectors.toList());
    List<String> reads = new ArrayList<>();
    reads.add("iterator " + sortedStrings(iterated));
    reads.add("getValuesNext " + sorted(db.getValuesNext(pair(1), 10)));
    reads.add("getKeysNext " + db.getKeysNext(pair(1), 10).stream()
        .map(CommittedHeadViewTest::hex).collect(Collectors.toList()));
    reads.add("getNext " + sortedStrings(next));
    reads.add("prefixQuery " + sortedStrings(prefixed));
    return reads;
  }

  private static List<String> sorted(Collection<byte[]> values) {
    return values.stream().map(CommittedHeadViewTest::hex).sorted().collect(Collectors.toList());
  }

  private static List<String> sortedStrings(List<String> values) {
    Collections.sort(values);
    return values;
  }

  private static String entry(byte[] key, byte[] value) {
    return hex(key) + "=" + hex(value);
  }

  private static String hex(byte[] value) {
    return value == null ? "null" : ByteArray.toHexString(value);
  }

  private void commitTo(TronStoreWithRevoking<ProtoCapsuleTest> target, byte[] key,
      String value) {
    try (ISession session = snapshots.buildSession()) {
      target.put(key, new ProtoCapsuleTest(bytes(value)));
      session.commit();
    }
  }

  private static String rootValueOf(TronStoreWithRevoking<ProtoCapsuleTest> target,
      byte[] key) {
    byte[] value = ((Chainbase) target.getRevokingDB()).getHead().getRoot().get(key);
    return value == null ? null : new String(value, StandardCharsets.UTF_8);
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

  private String rootValue(String key) {
    return rootValue(bytes(key));
  }

  private String rootValue(byte[] key) {
    byte[] value = ((Chainbase) store.getRevokingDB()).getHead().getRoot().get(key);
    return value == null ? null : new String(value, StandardCharsets.UTF_8);
  }

  private HeadView publish(long num) {
    long generation = registry.currentGeneration();
    HeadView view = newView(num, registry);
    registry.publish(view, generation);
    return view;
  }

  private HeadView newView(long num, HeadViewRegistry listener) {
    return new HeadView(new BlockId(Sha256Hash.ZERO_HASH, num), BlockHeader.getDefaultInstance(),
        snapshots.captureCommittedLayers(listener));
  }

  private static final class ObserverProbe implements RefreshObserver {

    private final HeadViewRegistry delegate;
    private final boolean failBeforeRefresh;
    private Runnable afterInvalidation;
    private int beforeCalls;
    private int failures;
    private int incompleteCalls;

    ObserverProbe(HeadViewRegistry delegate, boolean failBeforeRefresh) {
      this.delegate = delegate;
      this.failBeforeRefresh = failBeforeRefresh;
    }

    @Override
    public void beforeRefresh(Map<Chainbase, Snapshot> lastFlushedLayers) {
      beforeCalls++;
      if (failBeforeRefresh) {
        throw new IllegalStateException("injected observer failure");
      }
      delegate.beforeRefresh(lastFlushedLayers);
      if (afterInvalidation != null) {
        afterInvalidation.run();
      }
    }

    @Override
    public void refreshIncomplete() {
      incompleteCalls++;
      delegate.refreshIncomplete();
    }

    @Override
    public void observerFailed(Throwable cause) {
      failures++;
      delegate.observerFailed(cause);
    }

    @Override
    public void onFrozenLayerWrite(String dbName) {
      delegate.onFrozenLayerWrite(dbName);
    }
  }

  private static final class FailingStore extends TronStoreWithRevoking<ProtoCapsuleTest> {

    FailingStore(DB<byte[], byte[]> db) {
      super(db);
    }

    @Override
    public ProtoCapsuleTest get(byte[] key) {
      byte[] value = this.revokingDB.getUnchecked(key);
      return ArrayUtils.isEmpty(value) ? null : new ProtoCapsuleTest(value);
    }
  }

  private static final class FailingDb implements DB<byte[], byte[]>, Flusher {

    private static final String INJECTED = "injected flush failure";
    private final DB<byte[], byte[]> delegate;
    private volatile boolean fail;

    FailingDb(DB<byte[], byte[]> delegate) {
      this.delegate = delegate;
    }

    @Override
    public byte[] get(byte[] key) {
      return delegate.get(key);
    }

    @Override
    public void put(byte[] key, byte[] value) {
      delegate.put(key, value);
    }

    @Override
    public long size() {
      return delegate.size();
    }

    @Override
    public boolean isEmpty() {
      return delegate.isEmpty();
    }

    @Override
    public void remove(byte[] key) {
      delegate.remove(key);
    }

    @Override
    public Iterator<Map.Entry<byte[], byte[]>> iterator() {
      return delegate.iterator();
    }

    @Override
    public void close() {
      ((Flusher) delegate).close();
    }

    @Override
    public String getDbName() {
      return delegate.getDbName();
    }

    @Override
    public void stat() {
      delegate.stat();
    }

    @Override
    public DB<byte[], byte[]> newInstance() {
      return new FailingDb(delegate.newInstance());
    }

    @Override
    public void flush(Map<WrappedByteArray, WrappedByteArray> batch) {
      if (fail) {
        throw new IllegalStateException(INJECTED);
      }
      ((Flusher) delegate).flush(batch);
    }

    @Override
    public void reset() {
      ((Flusher) delegate).reset();
    }
  }

  private void commit(String key, String value) {
    try (ISession session = snapshots.buildSession()) {
      put(key, value);
      session.commit();
    }
  }

  private void put(String key, String value) {
    store.put(bytes(key), new ProtoCapsuleTest(bytes(value)));
  }

  private String read(String key) {
    ProtoCapsuleTest value = store.get(bytes(key));
    return value == null ? null : new String(value.getData(), StandardCharsets.UTF_8);
  }

  private String readInScope(HeadLease lease, String key) {
    try (QueryViewScope ignored = QueryViewScope.bind(lease)) {
      return read(key);
    }
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
