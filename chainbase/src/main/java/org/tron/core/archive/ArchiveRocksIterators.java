package org.tron.core.archive;

import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;

/**
 * Fail-stop helper for archive RocksDB iterators.
 *
 * <p>A {@link RocksIterator} that becomes {@code !isValid()} can mean either of two things: it ran
 * past the end of the scanned range (a normal, expected terminus), or the underlying read hit an
 * I/O / SST-checksum / corruption error. Callers distinguish the former with {@code isValid()}, so
 * an error is otherwise indistinguishable from "no such row" -- which for the archive would
 * surface as a silently wrong historical read (e.g. {@code getAsOf} falling through to
 * {@code latest}) or an incomplete validation / delete scan. {@link RocksIterator#status()} throws
 * {@link RocksDBException} exactly when such an error occurred, so calling it after every seek/loop
 * and converting a failure to a fail-stop {@link ArchiveException} keeps a corrupt archive from
 * being read as merely empty.
 */
public final class ArchiveRocksIterators {

  private ArchiveRocksIterators() {
  }

  /**
   * Rejects an iterator that stopped because of a read error rather than reaching the end of range.
   * A no-op on the happy path (status is OK); throws {@link ArchiveException} otherwise.
   *
   * @param it the iterator to inspect after its seek/loop has finished
   * @param context short description of the scan, for the fail-stop message
   */
  public static void requireOk(RocksIterator it, String context) {
    try {
      it.status();
    } catch (RocksDBException e) {
      throw new ArchiveException("archive rocksdb iterator error: " + context, e);
    }
  }
}
