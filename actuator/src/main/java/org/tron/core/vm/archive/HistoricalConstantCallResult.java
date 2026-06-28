package org.tron.core.vm.archive;

/**
 * Outcome of a historical constant call: the raw return data plus whether the call reverted and any
 * runtime error string. A hard VM error is not represented here; it is thrown as a
 * {@link HistoricalVmExecutionException} by the executor.
 */
public final class HistoricalConstantCallResult {

  private final byte[] result;
  private final boolean reverted;
  private final String runtimeError;

  private HistoricalConstantCallResult(byte[] result, boolean reverted, String runtimeError) {
    this.result = result;
    this.reverted = reverted;
    this.runtimeError = runtimeError;
  }

  public static HistoricalConstantCallResult of(byte[] result, boolean reverted,
      String runtimeError) {
    return new HistoricalConstantCallResult(result, reverted, runtimeError);
  }

  public byte[] getResult() {
    return result;
  }

  public boolean isReverted() {
    return reverted;
  }

  public String getRuntimeError() {
    return runtimeError;
  }
}
