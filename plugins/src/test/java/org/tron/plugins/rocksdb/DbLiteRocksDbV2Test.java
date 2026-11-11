package org.tron.plugins.rocksdb;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.tron.plugins.DbLiteTest;

public class DbLiteRocksDbV2Test extends DbLiteTest {

  @Test
  public void testToolsWithRocksDbV2() throws InterruptedException, IOException {
    testTools("ROCKSDB", 2);
  }

  @Override
  protected List<String> movedDbs() {
    return Arrays.asList("block", "trans");
  }
}
