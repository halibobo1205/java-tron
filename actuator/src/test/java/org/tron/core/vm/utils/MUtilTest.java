package org.tron.core.vm.utils;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.tron.core.archive.query.ArchiveQueryLimits;
import org.tron.core.archive.query.QueryContext;
import org.tron.core.archive.query.QueryContextHolder;
import org.tron.core.vm.archive.UnsupportedHistoricalStateException;
import org.tron.core.vm.repository.Repository;

public class MUtilTest {

  @Test
  public void archiveSelfdestructTokenSweepFailsBeforeReadingOverlayAccounts() {
    Repository archive = mock(Repository.class);
    when(archive.isHistoricalArchive()).thenReturn(true);
    QueryContext context = new QueryContext(ArchiveQueryLimits.unlimited());

    UnsupportedHistoricalStateException failure;
    try (QueryContextHolder.Scope ignored = QueryContextHolder.attach(context)) {
      failure = assertThrows(UnsupportedHistoricalStateException.class,
          () -> MUtil.transferAllToken(archive, new byte[21], new byte[21]));
    }

    assertSame(failure, context.getRecordedVmTerminalFailure());
    verify(archive, never()).getAccount(any(byte[].class));
  }
}
