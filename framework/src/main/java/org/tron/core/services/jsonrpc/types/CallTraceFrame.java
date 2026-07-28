package org.tron.core.services.jsonrpc.types;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import org.tron.common.utils.ByteArray;

/** Nested Geth/Erigon {@code callTracer} frame rendered with TVM energy quantities. */
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class CallTraceFrame {

  private static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";

  private final String type;
  private final String from;
  private String to;
  private final String gas;
  private final String value;
  private final String input;
  private String gasUsed = "0x0";
  private String output;
  private String error;
  private String revertReason;
  private List<CallTraceFrame> calls;

  public CallTraceFrame(String type, byte[] from, byte[] to, long energy, BigInteger callValue,
      byte[] input, boolean includeValue) {
    this.type = type;
    this.from = ByteArray.toJsonHexAddress(from);
    this.to = ByteArray.toJsonHexAddress(to);
    this.gas = ByteArray.toJsonHex(energy);
    this.value = includeValue ? toQuantity(callValue) : null;
    this.input = ByteArray.toJsonHex(input);
  }

  public void complete(byte[] completedOutput, long completedEnergyUsed, String completedError,
      String completedRevertReason) {
    gasUsed = ByteArray.toJsonHex(completedEnergyUsed);
    output = completedOutput == null || completedOutput.length == 0
        ? null : ByteArray.toJsonHex(completedOutput);
    error = completedError;
    revertReason = completedRevertReason;
    if (completedError != null && ("CREATE".equals(type) || "CREATE2".equals(type))) {
      to = ZERO_ADDRESS;
    }
  }

  public void addCall(CallTraceFrame child) {
    if (calls == null) {
      calls = new ArrayList<>();
    }
    calls.add(child);
  }

  private static String toQuantity(BigInteger value) {
    if (value == null || value.signum() <= 0) {
      return "0x0";
    }
    return "0x" + value.toString(16);
  }
}
