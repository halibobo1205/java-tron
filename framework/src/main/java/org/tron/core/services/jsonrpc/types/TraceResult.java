package org.tron.core.services.jsonrpc.types;

import java.util.List;
import lombok.Getter;

/** Top-level default-logger response for a debug trace. Gas fields contain TVM energy. */
@Getter
public final class TraceResult {

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
