package org.tron.core.db;

import static org.fusesource.leveldbjni.JniDBFactory.factory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.Options;
import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.tron.common.TestConstants;
import org.tron.core.db.common.iterator.DBIterator;
import org.tron.core.db.common.iterator.IteratorGuard;

public class DBIteratorTest {

  @ClassRule
  public static final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Rule
  public final ExpectedException thrown = ExpectedException.none();


  @Test
  public void testLevelDb() throws IOException {
    TestConstants.assumeLevelDbAvailable();
    File file = temporaryFolder.newFolder();
    try (DB db = factory.open(file, new Options().createIfMissing(true))) {
      db.put("1".getBytes(StandardCharsets.UTF_8), "1".getBytes(StandardCharsets.UTF_8));
      db.put("2".getBytes(StandardCharsets.UTF_8), "2".getBytes(StandardCharsets.UTF_8));
      DBIterator iterator = newGuard().openLevelDb(db::iterator);
      iterator.seekToFirst();
      Assert.assertArrayEquals("1".getBytes(StandardCharsets.UTF_8), iterator.getKey());
      Assert.assertArrayEquals("1".getBytes(StandardCharsets.UTF_8), iterator.next().getValue());
      Assert.assertTrue(iterator.hasNext());

      Assert.assertArrayEquals("2".getBytes(StandardCharsets.UTF_8), iterator.getValue());
      Assert.assertArrayEquals("2".getBytes(StandardCharsets.UTF_8), iterator.next().getKey());
      Assert.assertFalse(iterator.hasNext());

      Assert.assertThrows(IllegalStateException.class, iterator::seekToLast);

      iterator = newGuard().openLevelDb(db::iterator);
      iterator.seekToLast();
      Assert.assertArrayEquals("2".getBytes(StandardCharsets.UTF_8), iterator.getKey());
      Assert.assertArrayEquals("2".getBytes(StandardCharsets.UTF_8), iterator.getValue());
      iterator.seekToFirst();
      while (iterator.hasNext()) {
        iterator.next();
      }
      Assert.assertFalse(iterator.hasNext());
      Assert.assertThrows(IllegalStateException.class, iterator::getKey);
      Assert.assertThrows(IllegalStateException.class, iterator::getValue);
      thrown.expect(NoSuchElementException.class);
      iterator.next();
    }


  }

  @Test
  public void testRocksDb() throws RocksDBException, IOException {
    File file = temporaryFolder.newFolder();
    try (org.rocksdb.Options options = new org.rocksdb.Options().setCreateIfMissing(true);
         RocksDB db = RocksDB.open(options, file.toString())) {
      db.put("1".getBytes(StandardCharsets.UTF_8), "1".getBytes(StandardCharsets.UTF_8));
      db.put("2".getBytes(StandardCharsets.UTF_8), "2".getBytes(StandardCharsets.UTF_8));
      try (DBIterator iterator = newGuard().openRocksDb(ReadOptions::new, db::newIterator)) {
        iterator.seekToFirst();
        Assert.assertArrayEquals("1".getBytes(StandardCharsets.UTF_8), iterator.getKey());
        Assert.assertArrayEquals("1".getBytes(StandardCharsets.UTF_8), iterator.next().getValue());
        Assert.assertTrue(iterator.hasNext());

        Assert.assertArrayEquals("2".getBytes(StandardCharsets.UTF_8), iterator.getValue());
        Assert.assertArrayEquals("2".getBytes(StandardCharsets.UTF_8), iterator.next().getKey());
        Assert.assertFalse(iterator.hasNext());

        Assert.assertThrows(IllegalStateException.class, iterator::seekToLast);
      }

      try (DBIterator iterator = newGuard().openRocksDb(ReadOptions::new, db::newIterator)) {
        iterator.seekToLast();
        Assert.assertArrayEquals("2".getBytes(StandardCharsets.UTF_8), iterator.getKey());
        Assert.assertArrayEquals("2".getBytes(StandardCharsets.UTF_8), iterator.getValue());
        iterator.seekToFirst();
        while (iterator.hasNext()) {
          iterator.next();
        }
        Assert.assertFalse(iterator.hasNext());
        Assert.assertThrows(IllegalStateException.class, iterator::getKey);
        Assert.assertThrows(IllegalStateException.class, iterator::getValue);
        thrown.expect(NoSuchElementException.class);
        iterator.next();
      }
    }
  }

  private static IteratorGuard newGuard() {
    return new IteratorGuard(() -> "test", new ReentrantReadWriteLock());
  }
}
