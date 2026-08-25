package org.tron.plugins;

import java.io.IOException;
import org.junit.Test;

public class DbLiteRocksDbV2Test extends DbLiteTest {

  @Test
  public void testToolsWithRocksDbV2() throws InterruptedException, IOException {
    testTools("ROCKSDB", 2);
  }
}
