package org.tron.plugins;

import java.io.IOException;
import org.junit.Test;

public class DbLiteExcludeHistoricalBalanceRocksDbTest extends DbLiteTest {

  @Test
  public void testToolsWithExcludeHistoricalBalance() throws InterruptedException, IOException {
    testTools("ROCKSDB", 1, true);
  }
}
