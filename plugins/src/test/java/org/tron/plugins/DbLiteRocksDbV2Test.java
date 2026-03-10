package org.tron.plugins;

import java.io.IOException;
import org.junit.Test;

public class DbLiteRocksDbV2Test extends DbLiteTest {

  @Test
  public void testToolsWithRocksDB() throws InterruptedException, IOException {
    testTools("ROCKSDB", 2);
  }
}
