package org.tron.plugins;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.tron.common.utils.PublicMethod.getRandomPrivateKey;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.junit.After;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.rocksdb.RocksDBException;
import org.tron.api.GrpcAPI.EmptyMessage;
import org.tron.api.WalletGrpc;
import org.tron.common.TestConstants;
import org.tron.common.application.Application;
import org.tron.common.application.ApplicationFactory;
import org.tron.common.application.TronApplicationContext;
import org.tron.common.crypto.ECKey;
import org.tron.common.utils.FileUtil;
import org.tron.common.utils.PublicMethod;
import org.tron.common.utils.TimeoutInterceptor;
import org.tron.common.utils.Utils;
import org.tron.core.config.DefaultConfig;
import org.tron.core.config.args.Args;
import org.tron.plugins.utils.ByteArray;
import org.tron.plugins.utils.DBUtils;
import org.tron.plugins.utils.FileUtils;
import org.tron.plugins.utils.db.DbTool;
import picocli.CommandLine;

@Slf4j
public class DbLiteTest {

  private TronApplicationContext context;
  private WalletGrpc.WalletBlockingStub blockingStubFull = null;
  private ManagedChannel channelFull;
  private Application appTest;
  private String databaseDir;

  @Rule
  public final TemporaryFolder folder = new TemporaryFolder();

  private static final String STALE_FILE = "STALE";
  private static final int RECENT_BLKS = 3;
  private static final long HEAD_BLOCK_TIMEOUT_MS = 60_000;

  private String dbPath;
  CommandLine cli = new CommandLine(new DbLite());

  /**
   * init logic.
   */
  public void startApp() {
    Args.getInstance().setRpcPort(PublicMethod.chooseRandomPort());
    context = new TronApplicationContext(DefaultConfig.class);
    appTest = ApplicationFactory.create(context);
    appTest.startup();

    String fullNode = String.format("%s:%d", "127.0.0.1",
        Args.getInstance().getRpcPort());
    channelFull = ManagedChannelBuilder.forTarget(fullNode)
        .usePlaintext()
        .intercept(new TimeoutInterceptor(5000))
        .build();
    blockingStubFull = WalletGrpc.newBlockingStub(channelFull);
  }

  /**
   * shutdown the fullNode.
   */
  public void shutdown() throws InterruptedException {
    if (channelFull != null) {
      channelFull.shutdownNow();
    }
    context.close();
  }

  public void init(String dbType, boolean historyBalanceLookup) throws IOException {
    dbPath = folder.newFolder().toString();
    Args.setParam(new String[] {
        "-d", dbPath, "-w", "--p2p-disable", "true", "--storage-db-engine", dbType},
        TestConstants.SHIELD_CONF);
    // allow account root
    Args.getInstance().setAllowAccountStateRoot(1);
    Args.getInstance().setRpcPort(PublicMethod.chooseRandomPort());
    Args.getInstance().setRpcEnable(true);
    Args.getInstance().setHistoryBalanceLookup(historyBalanceLookup);
    databaseDir = Args.getInstance().getStorage().getDbDirectory();
  }

  @After
  public void clear() {
    Args.clearParam();
  }

  public void testTools(String dbType, int checkpointVersion)
      throws InterruptedException, IOException {
    testTools(dbType, checkpointVersion, false);
  }

  public void testTools(String dbType, int checkpointVersion, boolean excludeHistoricalBalance)
      throws InterruptedException, IOException {
    logger.info("dbType {}, checkpointVersion {}, excludeHistoricalBalance {}",
        dbType, checkpointVersion, excludeHistoricalBalance);
    boolean historyBalanceLookup = excludeHistoricalBalance;
    init(dbType, historyBalanceLookup);
    final String[] argsForSnapshot = excludeHistoricalBalance
        ? new String[] {"-o", "split", "-t", "snapshot", "--fn-data-path",
            dbPath + File.separator + databaseDir, "--dataset-path",
            dbPath, "--exclude-historical-balance"}
        : new String[] {"-o", "split", "-t", "snapshot", "--fn-data-path",
            dbPath + File.separator + databaseDir, "--dataset-path",
            dbPath};
    final String[] argsForHistory =
        new String[] {"-o", "split", "-t", "history", "--fn-data-path",
            dbPath + File.separator + databaseDir, "--dataset-path",
            dbPath};
    final String[] argsForMerge =
        new String[] {"-o", "merge", "--fn-data-path", dbPath + File.separator + databaseDir,
            "--dataset-path", dbPath + File.separator + "history"};
    Args.getInstance().getStorage().setCheckpointVersion(checkpointVersion);
    DbLite.setRecentBlks(RECENT_BLKS);
    // start fullNode
    startApp();
    // produce transactions for 18 seconds
    generateSomeTransactions(18);
    awaitHeadBlock(RECENT_BLKS + 2);
    // stop the node
    shutdown();
    // delete tran-cache
    FileUtil.deleteDir(Paths.get(dbPath, databaseDir, "trans-cache").toFile());
    // generate snapshot
    cli.execute(argsForSnapshot);
    Path snapshotDir = Paths.get(dbPath, "snapshot");
    if (excludeHistoricalBalance) {
      // when --exclude-historical-balance=true, the lite snapshot must not ship
      // balance-trace / account-trace
      assertFalse(snapshotDir.resolve("balance-trace").toFile().exists());
      assertFalse(snapshotDir.resolve("account-trace").toFile().exists());
    } else {
      assertTrue(snapshotDir.resolve("balance-trace").toFile().exists());
      assertTrue(snapshotDir.resolve("account-trace").toFile().exists());
    }
    // start fullNode
    startApp();
    // produce transactions
    generateSomeTransactions(checkpointVersion == 1 ? 6 : 18);
    // stop the node
    shutdown();
    // generate history
    cli.execute(argsForHistory);
    // backup original database to database_bak
    File database = new File(Paths.get(dbPath, databaseDir).toString());
    if (!database.renameTo(new File(Paths.get(dbPath, databaseDir + "_bak").toString()))) {
      throw new RuntimeException(
          String.format("rename %s to %s failed", database.getPath(),
              Paths.get(dbPath, databaseDir)));
    }
    // change snapshot to the new database
    File snapshot = new File(Paths.get(dbPath, "snapshot").toString());
    if (!snapshot.renameTo(new File(Paths.get(dbPath, databaseDir).toString()))) {
      throw new RuntimeException(
          String.format("rename snapshot to %s failed",
              Paths.get(dbPath, databaseDir)));
    }
    // start and validate the snapshot
    startApp();
    generateSomeTransactions(checkpointVersion == 1 ? 18 : 6);
    // stop the node
    shutdown();
    // merge history
    Path liteDatabase = Paths.get(dbPath, databaseDir);
    long snapshotMin = Long.parseLong(FileUtils.readProperty(
        liteDatabase.resolve("info.properties").toString(), DBUtils.SPLIT_BLOCK_NUM));
    Path hdd = moveDbs(liteDatabase);
    assertEquals(0, cli.execute(argsForMerge));
    assertMergeCompleted(liteDatabase);
    assertMovedDbsMerged(liteDatabase, hdd, snapshotMin);
    // start and validate
    startApp();
    generateSomeTransactions(6);
    shutdown();
    DbLite.reSetRecentBlks();
  }

  protected List<String> movedDbs() {
    return Collections.emptyList();
  }

  private Path moveDbs(Path database) throws IOException {
    if (movedDbs().isEmpty()) {
      return null;
    }
    Path hdd = folder.newFolder("hdd").toPath().toRealPath();
    for (String db : movedDbs()) {
      Path target = hdd.resolve(db);
      Files.move(database.resolve(db), target);
      Files.write(target.resolve(STALE_FILE), new byte[] {1});
      Files.createSymbolicLink(database.resolve(db), target);
    }
    return hdd;
  }

  private static void assertMergeCompleted(Path database) throws IOException {
    assertFalse(Files.exists(database.resolve("info.properties")));
    try (Stream<Path> entries = Files.list(database)) {
      assertTrue(entries.noneMatch(entry -> entry.getFileName().toString().startsWith(".bak_")));
    }
  }

  private void assertMovedDbsMerged(Path database, Path hdd, long snapshotMin)
      throws IOException {
    if (movedDbs().isEmpty()) {
      return;
    }
    for (String db : movedDbs()) {
      assertEquals(hdd.resolve(db), Files.readSymbolicLink(database.resolve(db)));
      assertFalse(Files.exists(hdd.resolve(db).resolve(STALE_FILE)));
    }
    assertTrue("the snapshot must start after block 1, got " + snapshotMin, snapshotMin > 1);
    try {
      byte[] historyOnly = DbTool.getDB(database.toString(), "block-index")
          .get(ByteArray.fromLong(snapshotMin - 1));
      assertNotNull(historyOnly);
      assertNotNull(DbTool.getDB(hdd.toString(), "block").get(historyOnly));
    } catch (RocksDBException e) {
      throw new IOException(e);
    } finally {
      DbTool.close();
    }
  }

  private void awaitHeadBlock(long number) throws InterruptedException {
    long deadline = System.currentTimeMillis() + HEAD_BLOCK_TIMEOUT_MS;
    while (headBlockNumber() < number) {
      assertTrue("head block did not reach " + number, System.currentTimeMillis() < deadline);
      Thread.sleep(500);
    }
  }

  private long headBlockNumber() {
    return blockingStubFull.getNowBlock(EmptyMessage.getDefaultInstance())
        .getBlockHeader().getRawData().getNumber();
  }

  private void generateSomeTransactions(int during) {
    during *= 1000; // ms
    int runTime = 0;
    int sleepOnce = 100;
    while (true) {
      ECKey ecKey2 = new ECKey(Utils.getRandom());
      byte[] address = ecKey2.getAddress();

      String sunPri = getRandomPrivateKey();
      byte[] sunAddress = PublicMethod.getFinalAddress(sunPri);
      PublicMethod.sendcoin(address, 1L,
          sunAddress, sunPri, blockingStubFull);
      try {
        Thread.sleep(sleepOnce);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      if ((runTime += sleepOnce) > during) {
        return;
      }
    }
  }
}
