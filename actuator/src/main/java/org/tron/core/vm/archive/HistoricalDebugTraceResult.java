package org.tron.core.vm.archive;

import java.util.Arrays;
import org.tron.protos.Protocol.Transaction.Result.contractResult;

/** Terminal TVM outcome used to render either struct logs or a callTracer frame. */
public final class HistoricalDebugTraceResult {

  private final byte[] output;
  private final long energyUsed;
  private final contractResult resultCode;
  private final boolean failed;
  private final boolean reverted;

  private HistoricalDebugTraceResult(
      byte[] output, long energyUsed, contractResult resultCode,
      boolean failed, boolean reverted) {
    this.output = output == null ? new byte[0] : output;
    this.energyUsed = energyUsed;
    this.resultCode = resultCode;
    this.failed = failed;
    this.reverted = reverted;
  }

  public static HistoricalDebugTraceResult of(
      byte[] output, long energyUsed, contractResult resultCode,
      boolean failed, boolean reverted) {
    if (resultCode == null) {
      throw new NullPointerException("resultCode");
    }
    return new HistoricalDebugTraceResult(output, energyUsed, resultCode, failed, reverted);
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

  public contractResult getResultCode() {
    return resultCode;
  }

  public boolean isFailed() {
    return failed;
  }

  public boolean isReverted() {
    return reverted;
  }
}
