package org.tron.plugins.rocksdb;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.tron.plugins.DbLiteTest;

public class DbLiteRocksDbTest extends DbLiteTest {

  @Test
  public void testToolsWithRocksDB() throws InterruptedException, IOException {
    testTools("ROCKSDB", 1);
  }

  @Override
  protected List<String> movedDbs() {
    return Arrays.asList("block", "trans");
  }
}
