package org.tron.core.db.rocksdb;

import java.io.File;
import java.io.IOException;
import java.util.function.IntSupplier;
import java.util.stream.IntStream;
import lombok.extern.slf4j.Slf4j;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.tron.common.arch.Arch;

@Slf4j(topic = "DB")
public class RocksDbTest {

  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  private File tmp;

  @Before
  public void warmUP() throws RocksDBException, IOException {
    tmp = temporaryFolder.newFolder();
    if (!tmp.exists() && !tmp.mkdirs()) {
      throw new IOException("Unable to create temp folder");
    }
    for (int i = 0; i < 10; i++) {
      try (RocksDB db = RocksDB.open(tmp + "-" + i)) {
        db.compactRange();
      } finally {
        RocksDB.destroyDB(tmp + "-" + i, new Options());
      }
    }
  }

  @Test
  public void testNewDB() {
    logger.info("{}", Arch.withAll());
    IntStream.range(10, 60).mapToObj(i -> (IntSupplier) () -> {
      try {
        return Math.toIntExact(openDB(i));
      } catch (RocksDBException e) {
        throw new org.iq80.leveldb.DBException(e);
      }
    }).mapToDouble(IntSupplier::getAsInt).average()
        .ifPresent(avg -> logger.info("Agv cost: {} ms", avg));
  }

  private int openDB(int i) throws RocksDBException {
    long start = System.currentTimeMillis();
    try (RocksDB db = RocksDB.open(tmp + "-" + i)) {
      return StrictMath.toIntExact(System.currentTimeMillis() - start);
    } finally {
      RocksDB.destroyDB(tmp + "-" + i, new Options());
    }
  }
}
