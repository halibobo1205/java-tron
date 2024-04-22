package org.tron.plugins.utils.db;

import java.io.Closeable;
import java.io.IOException;
import org.iq80.leveldb.DBException;


public interface DBInterface extends Closeable {

  byte[] get(byte[] key);

  void put(byte[] key, byte[] value);

  void delete(byte[] key);

  DBIterator iterator();

  long size();

  void close() throws IOException;

  String getName();

  /**
   * Force a compaction of the specified key range.
   *
   * @param begin if null then compaction start from the first key
   * @param end if null then compaction ends at the last key
   */

  void compactRange(byte[] begin, byte[] end)
      throws DBException;

  default void compactRange() {
    compactRange(null, null);
  }

}
