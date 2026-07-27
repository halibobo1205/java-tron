package org.tron.core.services.jsonrpc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;

import org.junit.Test;
import org.tron.core.archive.query.HistoricalQueryLimitException;
import org.tron.core.vm.program.Program;

public class ArchiveTraceStepLimiterTest {

  @Test
  public void failsWhenCallTracerExceedsItsOpcodeBudget() {
    ArchiveTraceStepLimiter limiter = new ArchiveTraceStepLimiter(1L);
    Program program = mock(Program.class);

    limiter.capture(program);
    HistoricalQueryLimitException failure = assertThrows(
        HistoricalQueryLimitException.class, () -> limiter.capture(program));

    assertEquals(HistoricalQueryLimitException.Limit.VM_STEPS, failure.getLimit());
    assertEquals(1L, failure.getConfiguredLimit());
    assertEquals(2L, failure.getObserved());
  }
}
