package org.tron.plugins.rocksdb;

import java.io.IOException;
import java.util.UUID;
import org.junit.Assert;
import org.junit.Test;
import org.rocksdb.RocksDBException;
import org.tron.plugins.DbTest;
import org.tron.plugins.Toolkit;
import org.tron.plugins.utils.db.DbTool;
import picocli.CommandLine;

public class DbCompactTest extends DbTest {

  @Test
  public void testRunForRocksDB() throws IOException, RocksDBException {
    init(DbTool.DbType.RocksDB);
    String[] args = new String[] {"db", "compact", INPUT_DIRECTORY,
        "--dbs", "account,market_pair_price_to_order"};
    Assert.assertEquals(0, cli.execute(args));
  }

  @Test
  public void testHelp() {
    String[] args = new String[] {"db", "compact", "-h"};
    CommandLine cli = new CommandLine(new Toolkit());
    Assert.assertEquals(0, cli.execute(args));
  }

  @Test
  public void testNotExist() {
    String[] args = new String[] {"db", "compact", UUID.randomUUID().toString(),
        "--dbs", "account"};
    Assert.assertEquals(404, cli.execute(args));
  }

  @Test
  public void testEmptyDbs() throws IOException {
    String[] args = new String[] {"db", "compact", temporaryFolder.newFolder().toString()};
    Assert.assertEquals(404, cli.execute(args));
  }

  @Test
  public void testMissingDb() throws IOException {
    String[] args = new String[] {"db", "compact", temporaryFolder.newFolder().toString(),
        "--dbs", "missing"};
    Assert.assertEquals(1, cli.execute(args));
  }
}
