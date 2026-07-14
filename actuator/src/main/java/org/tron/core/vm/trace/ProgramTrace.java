package org.tron.core.vm.trace;

import static java.lang.String.format;
import static org.tron.common.utils.ByteArray.toHexString;
import static org.tron.core.vm.trace.Serializers.serializeFieldsOnly;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bouncycastle.util.encoders.Hex;
import org.tron.common.runtime.vm.DataWord;
import org.tron.core.archive.query.QueryContext;
import org.tron.core.archive.query.QueryContextHolder;
import org.tron.core.vm.config.VMConfig;
import org.tron.core.vm.program.invoke.ProgramInvoke;

public class ProgramTrace {

  private static final long TRACE_OP_BASE_BYTES = 160L;
  private static final long ACTIONS_BASE_BYTES = 72L;
  private static final long ACTION_BASE_BYTES = 64L;
  private static final long LIST_BASE_BYTES = 24L;
  private static final long MAP_BASE_BYTES = 48L;
  private static final long MAP_ENTRY_BYTES = 40L;
  private static final long REFERENCE_BYTES = 8L;
  private static final long STRING_BASE_BYTES = 40L;
  private static final long BYTES_PER_CHARACTER = 2L;

  private final QueryContext historicalQueryContext;
  private List<Op> ops = new ArrayList<>();
  private String result;
  private String error;
  private String contractAddress;

  public ProgramTrace() {
    this(null);
  }

  public ProgramTrace(ProgramInvoke programInvoke) {
    historicalQueryContext = QueryContextHolder.current();
    if (programInvoke != null && VMConfig.vmTrace()) {
      contractAddress = Hex.toHexString(programInvoke.getContractAddress().toTronAddress());
    }
  }

  /** Returns the archive request that owns this trace, or {@code null} for a live trace. */
  public QueryContext historicalQueryContext() {
    return historicalQueryContext;
  }

  public List<Op> getOps() {
    return ops;
  }

  public void setOps(List<Op> ops) {
    this.ops = ops;
  }

  public String getResult() {
    return result;
  }

  public void setResult(String result) {
    this.result = result;
  }

  public String getError() {
    return error;
  }

  public void setError(String error) {
    this.error = error;
  }

  public String getContractAddress() {
    return contractAddress;
  }

  public void setContractAddress(String contractAddress) {
    this.contractAddress = contractAddress;
  }

  public ProgramTrace result(byte[] result) {
    accountTraceBytes(estimatedStringBytes(multiplySaturated(result.length, 2L)));
    setResult(toHexString(result));
    return this;
  }

  public ProgramTrace error(Exception error) {
    String message = error == null ? null : error.getMessage();
    long characters = error == null ? 0L : addSaturated(
        addSaturated(8L, error.getClass().getName().length()),
        message == null ? 4L : message.length());
    accountTraceBytes(estimatedStringBytes(characters));
    setError(error == null ? "" : format("%s: %s", error.getClass(), message));
    return this;
  }

  public Op addOp(byte code, int pc, int deep, DataWord energy, OpActions actions) {
    accountTraceBytes(estimatedOpBytes(actions));

    Op op = new Op();
    op.setActions(actions);
    op.setCode(code & 0xff);
    op.setDeep(deep);
    op.setEnergy(energy.value());
    op.setPc(pc);

    ops.add(op);

    return op;
  }

  /**
   * Used for merging sub calls execution.
   */
  public void merge(ProgramTrace programTrace) {
    accountTraceBytes(multiplySaturated(programTrace.ops.size(), REFERENCE_BYTES));
    this.ops.addAll(programTrace.ops);
  }

  private static void accountTraceBytes(long estimatedBytes) {
    QueryContext context = QueryContextHolder.current();
    if (context != null) {
      context.recordTraceBytes(estimatedBytes);
    }
  }

  private static long estimatedOpBytes(OpActions actions) {
    long estimated = TRACE_OP_BASE_BYTES;
    if (actions == null) {
      return estimated;
    }
    estimated = addSaturated(estimated, ACTIONS_BASE_BYTES);
    estimated = addSaturated(estimated, estimatedActionListBytes(actions.getStack()));
    estimated = addSaturated(estimated, estimatedActionListBytes(actions.getMemory()));
    return addSaturated(estimated, estimatedActionListBytes(actions.getStorage()));
  }

  private static long estimatedActionListBytes(List<OpActions.Action> actions) {
    if (actions == null) {
      return 0L;
    }
    long estimated = addSaturated(
        LIST_BASE_BYTES, multiplySaturated(actions.size(), REFERENCE_BYTES));
    for (OpActions.Action action : actions) {
      estimated = addSaturated(estimated, ACTION_BASE_BYTES);
      if (action == null || action.getParams() == null) {
        continue;
      }
      Map<String, Object> params = action.getParams();
      estimated = addSaturated(estimated, MAP_BASE_BYTES);
      estimated = addSaturated(
          estimated, multiplySaturated(params.size(), MAP_ENTRY_BYTES));
      for (Map.Entry<String, Object> entry : params.entrySet()) {
        estimated = addSaturated(estimated, estimatedObjectBytes(entry.getKey()));
        estimated = addSaturated(estimated, estimatedObjectBytes(entry.getValue()));
      }
    }
    return estimated;
  }

  private static long estimatedObjectBytes(Object value) {
    return value instanceof CharSequence
        ? estimatedStringBytes(((CharSequence) value).length())
        : REFERENCE_BYTES;
  }

  private static long estimatedStringBytes(long characters) {
    return addSaturated(STRING_BASE_BYTES, multiplySaturated(characters, BYTES_PER_CHARACTER));
  }

  private static long addSaturated(long left, long right) {
    return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
  }

  private static long multiplySaturated(long left, long right) {
    return left != 0 && right > Long.MAX_VALUE / left ? Long.MAX_VALUE : left * right;
  }

  @Override
  public String toString() {
    return serializeFieldsOnly(this);
  }
}
