package org.tron.core.vm.archive;

import org.tron.core.vm.trace.ProgramTrace;

/**
 * Outcome of a historical {@code debug_traceCall}: the raw return data, total energy used, the
 * revert / runtime-error status, plus the native {@link ProgramTrace} captured during execution.
 * The structLog reconstruction reads the trace ops; the top-level trace result reads the rest.
 *
 * <p>Unlike {@link HistoricalConstantCallResult}, a reverted call is NOT thrown here: a trace of a
 * reverting call is still useful (Geth returns {@code failed=true} with the structLogs), so the
 * executor returns it and lets the caller render {@code failed}.
 */
public final class HistoricalTraceCallResult {

  private final byte[] hReturn;
  private final long energyUsed;
  private final boolean failed;
  private final String runtimeError;
  private final ProgramTrace trace;

  private HistoricalTraceCallResult(byte[] hReturn, long energyUsed, boolean failed,
      String runtimeError, ProgramTrace trace) {
    this.hReturn = hReturn;
    this.energyUsed = energyUsed;
    this.failed = failed;
    this.runtimeError = runtimeError;
    this.trace = trace;
  }

  public static HistoricalTraceCallResult of(byte[] hReturn, long energyUsed, boolean failed,
      String runtimeError, ProgramTrace trace) {
    return new HistoricalTraceCallResult(hReturn, energyUsed, failed, runtimeError, trace);
  }

  public byte[] getHReturn() {
    return hReturn;
  }

  public long getEnergyUsed() {
    return energyUsed;
  }

  public boolean isFailed() {
    return failed;
  }

  public String getRuntimeError() {
    return runtimeError;
  }

  public ProgramTrace getTrace() {
    return trace;
  }
}
