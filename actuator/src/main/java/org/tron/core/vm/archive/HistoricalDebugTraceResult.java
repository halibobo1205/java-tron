package org.tron.core.vm.archive;

import java.util.Arrays;

/** Terminal TVM outcome used to render either struct logs or a callTracer frame. */
public final class HistoricalDebugTraceResult {

  private final byte[] output;
  private final long energyUsed;
  private final boolean failed;
  private final boolean reverted;

  private HistoricalDebugTraceResult(
      byte[] output, long energyUsed, boolean failed, boolean reverted) {
    this.output = output == null ? new byte[0] : output;
    this.energyUsed = energyUsed;
    this.failed = failed;
    this.reverted = reverted;
  }

  public static HistoricalDebugTraceResult of(
      byte[] output, long energyUsed, boolean failed, boolean reverted) {
    return new HistoricalDebugTraceResult(output, energyUsed, failed, reverted);
  }

  public byte[] getOutput() {
    return Arrays.copyOf(output, output.length);
  }

  public int getOutputLength() {
    return output.length;
  }

  public long getEnergyUsed() {
    return energyUsed;
  }

  public boolean isFailed() {
    return failed;
  }

  public boolean isReverted() {
    return reverted;
  }
}
