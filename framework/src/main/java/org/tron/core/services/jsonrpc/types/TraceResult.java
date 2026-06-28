package org.tron.core.services.jsonrpc.types;

import java.util.List;
import lombok.Getter;

/**
 * Top-level result of a Geth/Besu {@code debug_traceCall} with the default struct-log tracer. Field
 * names match Geth: {@code gas} is the total energy used, {@code failed} is true when the call
 * reverted or errored, {@code returnValue} is the return data as hex WITHOUT a {@code 0x} prefix
 * (Geth form -- empty string when no data), and {@code structLogs} is the per-op trace.
 */
@Getter
public class TraceResult {

  private final long gas;
  private final boolean failed;
  private final String returnValue;
  private final List<StructLog> structLogs;

  public TraceResult(long gas, boolean failed, String returnValue, List<StructLog> structLogs) {
    this.gas = gas;
    this.failed = failed;
    this.returnValue = returnValue;
    this.structLogs = structLogs;
  }
}
