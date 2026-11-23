package org.tron.core.db;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Properties;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.tron.common.TestConstants;
import org.tron.common.application.TronApplicationContext;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.FileUtil;
import org.tron.common.utils.JsonUtil;
import org.tron.core.capsule.BytesCapsule;
import org.tron.core.config.DefaultConfig;
import org.tron.core.config.args.Args;
import org.tron.core.store.DynamicPropertiesStore;
import org.tron.keystore.Wallet;

public class TxCacheDBRecoveryTest {

  private static final long HEAD = 65_600L;
  private static final long STALE_FILTER_START = 1L;
  private static final int STALE_FILTER_INDEX = 0;

  @ClassRule
  public static final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private static TronApplicationContext context;
  private static RecentTransactionStore recentTransactionStore;
  private static DynamicPropertiesStore dynamicPropertiesStore;

  @BeforeClass
  public static void init() throws IOException {
    Args.setParam(new String[]{"--output-directory", temporaryFolder.newFolder().toString(),
        "--p2p-disable", "true"}, TestConstants.TEST_CONF);
    context = new TronApplicationContext(DefaultConfig.class);
    recentTransactionStore = context.getBean(RecentTransactionStore.class);
    dynamicPropertiesStore = context.getBean(DynamicPropertiesStore.class);
    dynamicPropertiesStore.saveLatestBlockHeaderNumber(HEAD);
  }

  @AfterClass
  public static void destroy() {
    context.destroy();
    Args.clearParam();
  }

  @After
  public void removeCacheDir() {
    FileUtil.deleteDir(cacheDir().toFile());
  }

  @Test
  public void staleCacheHeightKeepsRebuiltWindowAfterNextBlock() throws IOException {
    writeCacheProperties(HEAD - 1);
    assertRebuiltWindowSurvivesNextBlock("tx-cache-stale-height");
  }

  @Test
  public void missingFilterFileKeepsRebuiltWindowAfterNextBlock() throws IOException {
    writeCacheProperties(HEAD);
    assertRebuiltWindowSurvivesNextBlock("tx-cache-missing-filter");
  }

  private void assertRebuiltWindowSurvivesNextBlock(String dbName) {
    byte[] confirmedTxId = Wallet.generateRandomBytes(32);
    putRecentTransaction(HEAD, confirmedTxId);
    TransactionCache cache =
        new TransactionCache(dbName, recentTransactionStore, dynamicPropertiesStore);
    try {
      cache.initCache();
      assertTrue(cache.has(confirmedTxId));
      cache.put(Wallet.generateRandomBytes(32), new BytesCapsule(ByteArray.fromLong(HEAD + 1)));
      assertTrue(cache.has(confirmedTxId));
    } finally {
      cache.close();
    }
  }

  private static void putRecentTransaction(long blockNum, byte[] txId) {
    RecentTransactionItem item = new RecentTransactionItem(blockNum,
        Collections.singletonList(ByteArray.toHexString(txId)));
    recentTransactionStore.put(ByteArray.subArray(ByteArray.fromLong(blockNum), 6, 8),
        new BytesCapsule(JsonUtil.obj2Json(item).getBytes(StandardCharsets.UTF_8)));
  }

  private static void writeCacheProperties(long currentBlockNum) throws IOException {
    Files.createDirectories(cacheDir());
    Properties properties = new Properties();
    properties.setProperty("filterStartBlock", String.valueOf(STALE_FILTER_START));
    properties.setProperty("currentBlockNum", String.valueOf(currentBlockNum));
    properties.setProperty("currentFilterIndex", String.valueOf(STALE_FILTER_INDEX));
    try (Writer writer = Files.newBufferedWriter(
        cacheDir().resolve("txCache.properties"), StandardCharsets.UTF_8)) {
      properties.store(writer, null);
    }
  }

  private static Path cacheDir() {
    return Paths.get(CommonParameter.getInstance().getOutputDirectory(), ".cache");
  }
}
