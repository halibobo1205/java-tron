package org.tron.plugins.leveldb;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.tron.plugins.DbLiteTest;

public class DbLiteLevelDbV2Test extends DbLiteTest {

  @Test
  public void testToolsWithLevelDBV2() throws InterruptedException, IOException {
    testTools("LEVELDB", 2);
  }

  @Override
  protected List<String> movedDbs() {
    return Arrays.asList("block", "trans");
  }
}
