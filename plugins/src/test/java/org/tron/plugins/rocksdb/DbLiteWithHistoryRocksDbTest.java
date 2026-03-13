package org.tron.plugins.rocksdb;

import java.io.IOException;
import org.junit.Test;
import org.tron.plugins.DbLiteTest;

public class DbLiteWithHistoryRocksDbTest extends DbLiteTest {

  @Test
  public void testToolsWithTrimHistory() throws InterruptedException, IOException {
    testTools("ROCKSDB", 1, true, true);
  }
}
