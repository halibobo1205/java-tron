package org.tron.core.db2;

import com.google.common.collect.Sets;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestName;
import org.springframework.util.CollectionUtils;
import org.tron.common.application.TronApplicationContext;
import org.tron.common.cache.CacheStrategies;
import org.tron.common.utils.SessionOptional;
import org.tron.core.Constant;
import org.tron.core.capsule.ProtoCapsule;
import org.tron.core.config.DefaultConfig;
import org.tron.core.config.args.Args;
import org.tron.core.db2.RevokingDbWithCacheNewValueTest.TestRevokingTronStore;
import org.tron.core.db2.core.Snapshot;
import org.tron.core.db2.core.SnapshotManager;
import org.tron.core.db2.core.SnapshotRoot;
import org.tron.core.exception.ItemNotFoundException;

public class SnapshotRootTest {

  private TestRevokingTronStore tronDatabase;
  private TronApplicationContext context;
  private SnapshotManager revokingDatabase;
  private final Set<String> noSecondCacheDBs = Sets.newHashSet(Arrays.asList("trans-cache",
          "exchange-v2","nullifier","accountTrie","transactionRetStore","accountid-index",
          "market_account","market_pair_to_price","recent-transaction","block-index","block",
          "market_pair_price_to_order","proposal","tree-block-index","IncrementalMerkleTree",
          "asset-issue","balance-trace","transactionHistoryStore","account-index","section-bloom",
          "exchange","market_order","account-trace","contract-state","trans"));
  private Set<String> allDBNames;
  private Set<String> allRevokingDBNames;

  @Rule
  public  final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Rule
  public TestName name = new TestName();

  @Before
  public void init() throws IOException {
    Args.setParam(new String[]{"-d", temporaryFolder.newFolder().toString()}, Constant.TEST_CONF);
    Args.getInstance().setFullNodeAllowShieldedTransactionArgs(false);
    context = new TronApplicationContext(DefaultConfig.class);
    revokingDatabase = context.getBean(SnapshotManager.class);
    revokingDatabase.enable();
    allRevokingDBNames = parseRevokingDBNames(context);
    allDBNames = Arrays.stream(Objects.requireNonNull(Paths.get(
            Args.getInstance().getOutputDirectory(), "database").toFile().list()))
        .collect(Collectors.toSet());
  }

  @After
  public void removeDb() {
    context.close();
    Args.clearParam();
  }

  @Test
  public synchronized void testRemove() {
    ProtoCapsuleTest testProtoCapsule = new ProtoCapsuleTest("test".getBytes());
    tronDatabase = new TestRevokingTronStore(name.getMethodName());
    tronDatabase.put("test".getBytes(), testProtoCapsule);
    Assert.assertEquals(testProtoCapsule, tronDatabase.get("test".getBytes()));

    tronDatabase.delete("test".getBytes());
    Assert.assertEquals(null, tronDatabase.get("test".getBytes()));
    tronDatabase.close();
  }

  @Test
  public synchronized void testMerge() {
    tronDatabase = new TestRevokingTronStore(name.getMethodName());
    revokingDatabase.add(tronDatabase.getRevokingDB());

    SessionOptional dialog = SessionOptional.instance().setValue(revokingDatabase.buildSession());
    ProtoCapsuleTest testProtoCapsule = new ProtoCapsuleTest("merge".getBytes());
    tronDatabase.put(testProtoCapsule.getData(), testProtoCapsule);
    revokingDatabase.getDbs().forEach(db -> db.getHead().getRoot().merge(db.getHead()));
    dialog.reset();
    Assert.assertEquals(tronDatabase.get(testProtoCapsule.getData()), testProtoCapsule);

    tronDatabase.close();
  }

  @Test
  public synchronized void testMergeList() {
    tronDatabase = new TestRevokingTronStore(name.getMethodName());
    revokingDatabase.add(tronDatabase.getRevokingDB());

    SessionOptional.instance().setValue(revokingDatabase.buildSession());
    ProtoCapsuleTest testProtoCapsule = new ProtoCapsuleTest("test".getBytes());
    tronDatabase.put("merge".getBytes(), testProtoCapsule);
    for (int i = 1; i < 11; i++) {
      ProtoCapsuleTest tmpProtoCapsule = new ProtoCapsuleTest(("mergeList" + i).getBytes());
      try (ISession tmpSession = revokingDatabase.buildSession()) {
        tronDatabase.put(tmpProtoCapsule.getData(), tmpProtoCapsule);
        tmpSession.commit();
      }
    }
    revokingDatabase.getDbs().forEach(db -> {
      List<Snapshot> snapshots = new ArrayList<>();
      SnapshotRoot root = (SnapshotRoot) db.getHead().getRoot();
      Snapshot next = root;
      for (int i = 0; i < 11; ++i) {
        next = next.getNext();
        snapshots.add(next);
      }
      root.merge(snapshots);
      root.resetSolidity();

      for (int i = 1; i < 11; i++) {
        ProtoCapsuleTest tmpProtoCapsule = new ProtoCapsuleTest(("mergeList" + i).getBytes());
        Assert.assertEquals(tmpProtoCapsule, tronDatabase.get(tmpProtoCapsule.getData()));
      }

    });
    revokingDatabase.updateSolidity(10);
    tronDatabase.close();
  }

  @Test
  public void testSecondCacheCheck()
      throws ItemNotFoundException {
    allRevokingDBNames = parseRevokingDBNames(context);
    allDBNames = Arrays.stream(Objects.requireNonNull(
        Paths.get(Args.getInstance().getOutputDirectory(), "database").toFile().list()))
            .collect(Collectors.toSet());
    if (CollectionUtils.isEmpty(allDBNames)) {
      throw new ItemNotFoundException("No DBs found");
    }
    allDBNames.removeAll(noSecondCacheDBs);
    allDBNames.removeAll(CacheStrategies.CACHE_DBS);
    allDBNames.retainAll(allRevokingDBNames);
    org.junit.Assert.assertEquals(String.format("New added dbs %s "
                    + "shall consider to add second cache or add to noNeedCheckDBs!",
        String.join(",", allDBNames)), 0, allDBNames.size());
  }

  @Test
  public void testSecondCacheCheckAddDb()
          throws ItemNotFoundException {
    revokingDatabase = context.getBean(SnapshotManager.class);
    allRevokingDBNames = parseRevokingDBNames(context);
    allRevokingDBNames.add("secondCheckTestDB");
    if (CollectionUtils.isEmpty(allDBNames)) {
      throw new ItemNotFoundException("No DBs found");
    }
    allDBNames = Arrays.stream(Objects.requireNonNull(
            Paths.get(Args.getInstance().getOutputDirectory(), "database").toFile().list()))
        .collect(Collectors.toSet());
    allDBNames.add("secondCheckTestDB");
    allDBNames.removeAll(noSecondCacheDBs);
    allDBNames.removeAll(CacheStrategies.CACHE_DBS);
    allDBNames.retainAll(allRevokingDBNames);
    Assert.assertEquals(String.format("New added dbs %s check second cache failed!",
        String.join(",", allDBNames)), 1, allDBNames.size());
  }

  private Set<String> parseRevokingDBNames(TronApplicationContext context) {
    SnapshotManager snapshotManager = context.getBean(SnapshotManager.class);
    return snapshotManager.getDbs().stream().map(chainbase ->
        chainbase.getDbName()).collect(Collectors.toSet());
  }


  @NoArgsConstructor
  @AllArgsConstructor
  @EqualsAndHashCode
  public static class ProtoCapsuleTest implements ProtoCapsule<Object> {

    private byte[] value;

    @Override
    public byte[] getData() {
      return value;
    }

    @Override
    public Object getInstance() {
      return value;
    }

    @Override
    public String toString() {
      return "ProtoCapsuleTest{"
          + "value=" + Arrays.toString(value)
          + ", string=" + (value == null ? "" : new String(value))
          + '}';
    }
  }
}
