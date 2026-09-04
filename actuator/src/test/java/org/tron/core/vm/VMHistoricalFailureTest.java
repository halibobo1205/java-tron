package org.tron.core.vm;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.tron.core.archive.query.HistoricalQueryLimitException;
import org.tron.core.vm.program.Program;
import org.tron.core.vm.repository.Repository;

public class VMHistoricalFailureTest {

  @Test
  public void historicalQueryLimitEscapesVmCatchAll() {
    HistoricalQueryLimitException injected = queryLimit();
    Program program = program(true);

    HistoricalQueryLimitException failure = assertThrows(
        HistoricalQueryLimitException.class, () -> VM.play(program, tableThrowing(injected)));

    assertSame(injected, failure);
  }

  @Test
  public void nonHistoricalRuntimeBehaviorRemainsUnchanged() {
    HistoricalQueryLimitException injected = queryLimit();
    Program program = program(false);

    VM.play(program, tableThrowing(injected));

    verify(program).setRuntimeFailure(injected);
  }

  private static Program program(boolean historical) {
    Program program = mock(Program.class);
    Repository repository = mock(Repository.class);
    when(repository.isHistoricalArchive()).thenReturn(historical);
    when(program.getContractState()).thenReturn(repository);
    when(program.isStopped()).thenReturn(false);
    when(program.getCurrentOpIntValue()).thenReturn(0);
    return program;
  }

  private static JumpTable tableThrowing(RuntimeException failure) {
    JumpTable table = new JumpTable();
    table.set(new Operation(0, 0, 0, ignored -> 0L, ignored -> {
      throw failure;
    }));
    return table;
  }

  private static HistoricalQueryLimitException queryLimit() {
    return new HistoricalQueryLimitException(
        HistoricalQueryLimitException.Reason.RESOURCE_EXHAUSTED,
        HistoricalQueryLimitException.Limit.QUERY_ADMISSION,
        "historical proof worker capacity exhausted");
  }
}
