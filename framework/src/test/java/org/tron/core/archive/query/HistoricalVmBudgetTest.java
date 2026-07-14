package org.tron.core.archive.query;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import org.tron.common.runtime.InternalTransaction;
import org.tron.core.vm.JumpTable;
import org.tron.core.vm.Op;
import org.tron.core.vm.Operation;
import org.tron.core.vm.VM;
import org.tron.core.vm.program.Program;
import org.tron.core.vm.program.invoke.ProgramInvokeMockImpl;
import org.tron.protos.Protocol;

public class HistoricalVmBudgetTest {

  @Test
  public void deadlineIsCheckedAfterANonInterruptiblePrecompileBoundary() throws Exception {
    AtomicLong now = new AtomicLong();
    QueryContext context = new QueryContext(
        ArchiveQueryLimits.builder().deadlineMs(1L).build(), now::get);
    AtomicBoolean executed = new AtomicBoolean();
    JumpTable jumpTable = new JumpTable();
    jumpTable.set(new Operation(Op.STATICCALL, 0, 0, program -> 0L, program -> {
      // Model a precompile body that cannot poll the query deadline internally.
      executed.set(true);
      now.set(1_000_000L);
      program.stop();
    }));
    byte[] code = {(byte) Op.STATICCALL};
    ProgramInvokeMockImpl invoke = new ProgramInvokeMockImpl() {
      @Override
      public long getVmShouldEndInUs() {
        return Long.MAX_VALUE;
      }
    };
    Program program = new Program(code, code, invoke, new InternalTransaction(
        Protocol.Transaction.getDefaultInstance(),
        InternalTransaction.TrxType.TRX_UNKNOWN_TYPE));
    program.setRootTransactionId(new byte[32]);

    try (QueryContextHolder.Scope ignored = QueryContextHolder.attach(context)) {
      VM.play(program, jumpTable);
    }

    HistoricalQueryLimitException terminal = context.getTerminalException();
    assertTrue(executed.get());
    assertEquals(HistoricalQueryLimitException.Limit.DEADLINE, terminal.getLimit());
    assertEquals(1L, context.getVmSteps());
    assertSame(terminal, program.getResult().getException());
  }
}
