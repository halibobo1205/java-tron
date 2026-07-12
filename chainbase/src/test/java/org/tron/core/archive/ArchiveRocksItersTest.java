package org.tron.core.archive;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import org.junit.Test;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;

public class ArchiveRocksItersTest {

  @Test
  public void requireOkWrapsIteratorReadErrorAsFailStop() throws Exception {
    // An iterator that stopped because of a read error (RocksDBException from status()) must become
    // a fail-stop ArchiveException, not be swallowed -- otherwise it reads as "no such row".
    RocksIterator it = mock(RocksIterator.class);
    doThrow(new RocksDBException("simulated SST checksum failure")).when(it).status();

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> ArchiveRocksIterators.requireOk(it, "unit probe"));

    assertTrue(ex.getMessage().contains("iterator error"));
    assertTrue(ex.getCause() instanceof RocksDBException);
  }

  @Test
  public void requireOkIsNoopWhenIteratorHealthy() {
    // Happy path: status() returns normally (past-the-end, not an error), so requireOk must not
    // throw -- this is what keeps the check a no-op on every normal scan.
    RocksIterator it = mock(RocksIterator.class);

    ArchiveRocksIterators.requireOk(it, "unit probe");
  }
}
