package org.tron.core.services.jsonrpc.types;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import lombok.Getter;

/** One Geth-compatible pre-op TVM state entry returned by the default debug tracer. */
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class StructLog {

  private final int pc;
  private final String op;
  private final long gas;
  private long gasCost;
  private final int depth;
  private final List<String> stack;
  private final List<String> memory;
  private final Map<String, String> storage;
  private final String returnData;
  private String error;

  public StructLog(int pc, String op, long gas, int depth, List<String> stack,
      List<String> memory, Map<String, String> storage, String returnData) {
    this.pc = pc;
    this.op = op;
    this.gas = gas;
    this.depth = depth;
    this.stack = stack;
    this.memory = memory;
    this.storage = storage;
    this.returnData = returnData;
  }

  public void setGasCost(long completedGasCost) {
    gasCost = completedGasCost;
  }

  public void setError(String completedError) {
    error = completedError;
  }
}
