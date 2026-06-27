package org.tron.core.archive.reader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.tron.core.archive.domain.DefaultArchiveDomainCatalog;
import org.tron.core.archive.temporal.InMemoryArchiveTemporalStore;

public class ArchiveStateReaderFactoryTest {

  private final ArchiveStateReaderFactory factory = new DefaultArchiveStateReaderFactory(
      new InMemoryArchiveTemporalStore(), new DefaultArchiveDomainCatalog());

  @Test
  public void opensReaderBoundToThePoint() throws Exception {
    ArchiveStatePoint point = ArchiveStatePoint.blockEnd(7, new byte[] {1}, 42);
    ArchiveStateReader reader = factory.open(point);
    assertSame(point, reader.getPoint());
    reader.close();
  }

  @Test
  public void nullPointIsHistoryUnavailable() {
    ArchiveReaderException e = assertThrows(ArchiveReaderException.class, () -> factory.open(null));
    assertEquals(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE, e.getReason());
  }

  @Test
  public void nullStoreIsArchiveDisabled() {
    ArchiveStateReaderFactory disabled =
        new DefaultArchiveStateReaderFactory(null, new DefaultArchiveDomainCatalog());
    ArchiveReaderException e = assertThrows(ArchiveReaderException.class,
        () -> disabled.open(ArchiveStatePoint.blockEnd(1, null, 1)));
    assertEquals(ArchiveReaderException.Reason.ARCHIVE_DISABLED, e.getReason());
  }
}
