package org.tron.plugins;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.rocksdb.RocksDBException;
import org.tron.plugins.utils.db.DbTool;

public class DbLiteMergeTest {

  @Rule
  public final TemporaryFolder folder = new TemporaryFolder();

  @After
  public void closeDatabases() {
    DbTool.close();
  }

  @Test
  public void testMovedDbsMergeNewerSnapshot() throws IOException, RocksDBException {
    assertMovedMerge(DbLiteMergeFixture.SNAPSHOT_MAX - 1);
  }

  @Test
  public void testMovedDbsMergeMatchingHistory() throws IOException, RocksDBException {
    assertMovedMerge(DbLiteMergeFixture.SNAPSHOT_MAX);
  }

  @Test
  public void testMovedDbsTrimNewerHistory() throws IOException, RocksDBException {
    assertMovedMerge(DbLiteMergeFixture.SNAPSHOT_MAX + 1);
  }

  private void assertMovedMerge(long historyMax) throws IOException, RocksDBException {
    DbLiteMergeFixture fixture = new DbLiteMergeFixture(folder, historyMax);
    fixture.migrate(DbLiteMergeFixture.archiveDbs());
    assertEquals(fixture.error(), 0, fixture.executeMerge());
    fixture.assertMergedData();
  }
}
