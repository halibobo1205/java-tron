package org.tron.core.services.jsonrpc;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.Deque;
import org.tron.core.services.jsonrpc.types.CallTraceFrame;
import org.tron.core.vm.Op;
import org.tron.core.vm.trace.VmCallTraceCollector;

/** Synchronous request-owned nested call collector for the native {@code callTracer}. */
final class ArchiveCallTraceCollector implements VmCallTraceCollector {

  private static final long BASE_FRAME_BYTES = 320L;
  private static final TraceScope NOOP_SCOPE = new TraceScope() {
    @Override
    public void complete(byte[] output, long energyUsed, boolean reverted, String error) {
    }

    @Override
    public void close() {
    }
  };

  private final boolean onlyTopCall;
  private final boolean includePrecompiles;
  private final DebugTraceBudget budget;
  private final Deque<FrameScope> scopes = new ArrayDeque<>();
  private CallTraceFrame root;

  ArchiveCallTraceCollector(DebugTraceOptions options, DebugTraceBudget budget) {
    this.onlyTopCall = options.isOnlyTopCall();
    this.includePrecompiles = options.isIncludePrecompiles();
    this.budget = budget;
  }

  @Override
  public TraceScope enter(int opCode, byte[] from, byte[] to, byte[] input, long energy,
      BigInteger value, boolean precompile) {
    if (precompile && !includePrecompiles || onlyTopCall && !scopes.isEmpty()) {
      return NOOP_SCOPE;
    }
    budget.reserve(estimatedFrameBytes(from, to, input));
    String type = Op.getNameOf(opCode);
    if (type == null) {
      type = "CALL";
    }
    boolean includeValue = opCode != Op.STATICCALL;
    CallTraceFrame frame =
        new CallTraceFrame(type, from, to, nonNegative(energy), value, input, includeValue);
    FrameScope scope = new FrameScope(frame);
    scopes.push(scope);
    return scope;
  }

  CallTraceFrame getRoot() {
    if (!scopes.isEmpty()) {
      throw new IllegalStateException("call trace has unclosed frames");
    }
    if (root == null) {
      throw new IllegalStateException("call trace has no root frame");
    }
    return root;
  }

  private final class FrameScope implements TraceScope {

    private final CallTraceFrame frame;
    private boolean completed;
    private boolean closed;

    private FrameScope(CallTraceFrame frame) {
      this.frame = frame;
    }

    @Override
    public void complete(byte[] output, long energyUsed, boolean reverted, String error) {
      if (completed) {
        throw new IllegalStateException("call trace frame already completed");
      }
      completed = true;
      byte[] safeOutput = output == null ? new byte[0] : output;
      String revertReason = reverted ? decodeRevertReason(safeOutput) : null;
      budget.reserve(estimatedCompletionBytes(safeOutput, error, revertReason));
      frame.complete(safeOutput, nonNegative(energyUsed), error, revertReason);
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;
      if (!completed) {
        frame.complete(new byte[0], 0L, "execution aborted", null);
      }
      if (scopes.isEmpty() || scopes.peek() != this) {
        throw new IllegalStateException("call trace scopes closed out of order");
      }
      scopes.pop();
      if (scopes.isEmpty()) {
        if (root != null) {
          throw new IllegalStateException("call trace has multiple root frames");
        }
        root = frame;
      } else {
        scopes.peek().frame.addCall(frame);
      }
    }
  }

  private static String decodeRevertReason(byte[] output) {
    String decoded = TronJsonRpcImpl.tryDecodeRevertReason(output);
    return decoded.startsWith(": ") ? decoded.substring(2) : null;
  }

  private static long estimatedFrameBytes(byte[] from, byte[] to, byte[] input) {
    long estimate = BASE_FRAME_BYTES;
    estimate = saturatedAdd(estimate, hexBytes(from));
    estimate = saturatedAdd(estimate, hexBytes(to));
    return saturatedAdd(estimate, hexBytes(input));
  }

  private static long estimatedCompletionBytes(byte[] output, String error,
      String revertReason) {
    long estimate = output == null || output.length == 0 ? 0L : hexBytes(output);
    estimate = saturatedAdd(estimate, stringBytes(error));
    return saturatedAdd(estimate, stringBytes(revertReason));
  }

  private static long hexBytes(byte[] value) {
    return value == null || value.length == 0
        ? 2L : saturatedAdd(2L, saturatedMultiply(value.length, 2L));
  }

  private static long stringBytes(String value) {
    return value == null ? 0L : saturatedAdd(2L, saturatedMultiply(value.length(), 2L));
  }

  private static long saturatedMultiply(long left, long right) {
    if (left == 0L || right == 0L) {
      return 0L;
    }
    return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
  }

  private static long saturatedAdd(long left, long right) {
    return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
  }

  private static long nonNegative(long value) {
    return value < 0L ? 0L : value;
  }
}
