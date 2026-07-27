package org.tron.core.vm.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.tron.core.actuator.VMActuator;
import org.tron.core.archive.query.ArchiveQueryLimits;
import org.tron.core.archive.query.HistoricalQueryLimitException;
import org.tron.core.archive.query.QueryContext;
import org.tron.core.archive.query.QueryContextHolder;
import org.tron.core.archive.reader.ArchiveStateReader;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.db.TransactionContext;
import org.tron.core.store.VmDynamicProperties;
import org.tron.core.vm.program.Program;

public class HistoricalDebugTraceExecutorTest {

  @Test
  public void recordedTerminalFailureIsNotSuppressedOntoItself() throws Exception {
    QueryContext queryContext = new QueryContext(ArchiveQueryLimits.unlimited());
    HistoricalQueryLimitException terminal = limitFailure();
    queryContext.recordVmTerminalFailure(terminal);
    VMActuator vmActuator = mockActuator(terminal);
    HistoricalDebugTraceExecutor executor =
        new HistoricalDebugTraceExecutor(() -> vmActuator);

    HistoricalQueryLimitException failure = assertThrows(
        HistoricalQueryLimitException.class,
        () -> execute(executor, queryContext));

    assertSame(terminal, failure);
    assertEquals(0, failure.getSuppressed().length);
    assertNull(QueryContextHolder.current());
  }

  @Test
  public void hardFailureRetainsDistinctRecordedTerminalFailure() throws Exception {
    QueryContext queryContext = new QueryContext(ArchiveQueryLimits.unlimited());
    HistoricalQueryLimitException terminal = limitFailure();
    queryContext.recordVmTerminalFailure(terminal);
    AssertionError hardFailure = new AssertionError("hard VM failure");
    VMActuator vmActuator = mockActuator(hardFailure);
    HistoricalDebugTraceExecutor executor =
        new HistoricalDebugTraceExecutor(() -> vmActuator);

    AssertionError failure = assertThrows(
        AssertionError.class, () -> execute(executor, queryContext));

    assertSame(hardFailure, failure);
    assertEquals(1, failure.getSuppressed().length);
    assertSame(terminal, failure.getSuppressed()[0]);
    assertNull(QueryContextHolder.current());
  }

  private static VMActuator mockActuator(Throwable executionFailure) throws Exception {
    VMActuator vmActuator = mock(VMActuator.class);
    when(vmActuator.getProgram()).thenReturn(mock(Program.class));
    doThrow(executionFailure).when(vmActuator).execute(any(TransactionContext.class));
    return vmActuator;
  }

  private static void execute(HistoricalDebugTraceExecutor executor,
      QueryContext queryContext) throws Exception {
    ArchiveStateReader reader = mock(ArchiveStateReader.class);
    when(reader.getQueryContext()).thenReturn(queryContext);
    executor.execute(
        reader,
        mock(VmDynamicProperties.class),
        mock(BlockCapsule.class),
        mock(TransactionCapsule.class),
        true,
        true,
        null,
        null,
        null);
  }

  private static HistoricalQueryLimitException limitFailure() {
    return new HistoricalQueryLimitException(
        HistoricalQueryLimitException.Reason.RESOURCE_EXHAUSTED,
        HistoricalQueryLimitException.Limit.VM_STEPS,
        1L,
        2L,
        "step limit");
  }
}
