package org.tron.core.services.jsonrpc.types;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import lombok.Getter;

/**
 * One entry of a Geth/Besu {@code structLogs} trace, as produced by {@code debug_traceCall}. Field
 * names match Geth exactly so existing tooling can parse the response. Values are the PRE-op
 * machine state, rebuilt by replaying the native tracer's per-op deltas (see
 * StructLogReconstructor).
 *
 * <p>{@code stack} / {@code memory} are 32-byte hex words WITHOUT a {@code 0x} prefix (Geth form).
 * {@code storage} maps slot hex to value hex, and -- because the native tracer only records storage
 * WRITES -- it contains the write-touched slots seen so far, not every slot the call read.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
public class StructLog {

  private final int pc;
  private final String op;
  private final long gas;
  private final long gasCost;
  private final int depth;
  private final List<String> stack;
  private final List<String> memory;
  private final Map<String, String> storage;

  public StructLog(int pc, String op, long gas, long gasCost, int depth, List<String> stack,
      List<String> memory, Map<String, String> storage) {
    this.pc = pc;
    this.op = op;
    this.gas = gas;
    this.gasCost = gasCost;
    this.depth = depth;
    this.stack = stack;
    this.memory = memory;
    this.storage = storage;
  }
}
