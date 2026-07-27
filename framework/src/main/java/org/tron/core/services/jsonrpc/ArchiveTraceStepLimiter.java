package org.tron.core.services.jsonrpc;

import org.tron.core.archive.query.HistoricalQueryLimitException;
import org.tron.core.vm.program.Program;
import org.tron.core.vm.trace.VmStructuredTraceListener;

/** Counts TVM opcodes for trace modes that do not otherwise capture per-op state. */
final class ArchiveTraceStepLimiter implements VmStructuredTraceListener {

  private final long maxTraceSteps;
  private long observedSteps;

  ArchiveTraceStepLimiter(long maxTraceSteps) {
    this.maxTraceSteps = maxTraceSteps;
  }

  @Override
  public void capture(Program program) {
    observedSteps++;
    if (observedSteps > maxTraceSteps) {
      throw new HistoricalQueryLimitException(
          HistoricalQueryLimitException.Reason.RESOURCE_EXHAUSTED,
          HistoricalQueryLimitException.Limit.VM_STEPS,
          maxTraceSteps,
          observedSteps,
          "historical debug trace step budget exceeded: limit="
              + maxTraceSteps + ", observed=" + observedSteps);
    }
  }

  @Override
  public void onProgramExit(Program program) {
  }
}
