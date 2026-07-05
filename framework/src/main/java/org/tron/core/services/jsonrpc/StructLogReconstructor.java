package org.tron.core.services.jsonrpc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bouncycastle.util.encoders.Hex;
import org.tron.core.services.jsonrpc.types.StructLog;
import org.tron.core.vm.Op;
import org.tron.core.vm.trace.OpActions;
import org.tron.core.vm.trace.OpActions.Action;
import org.tron.core.vm.trace.ProgramTrace;

/**
 * Reconstructs a Geth/Besu {@code structLogs} trace from java-tron's native {@link ProgramTrace}
 * WITHOUT touching the interpreter loop. The native tracer records, per executed op, the energy
 * remaining and a set of DELTAS (stack push/pop/swap, memory write/extend, storage put/remove) that
 * CARRY their values; replaying those deltas in order rebuilds each op's full pre-op machine state.
 *
 * <p>Timing subtlety: the interpreter calls {@code saveOpTrace()} at the TOP of each iteration, so
 * the {@link OpActions} attached to op {@code i} are the mutations produced by executing op
 * {@code i-1}. Therefore, to land on the PRE-op state for op {@code i}, this replayer applies op
 * {@code i}'s recorded deltas FIRST and then snapshots -- which yields the state the previous op
 * left behind, i.e. exactly the input state of op {@code i}.
 *
 * <p>Fidelity caveats, documented for reviewers:
 * <ul>
 *   <li>{@code storage} reflects WRITE-touched slots only (put/remove); the native tracer does not
 *       capture SLOAD reads, so a read-only slot never appears.</li>
 *   <li>{@code memory} is rebuilt from the recorded write deltas, whose {@code data} field is the
 *       hex the native tracer captured for that write; large writes may be truncated by the tracer
 *       (a pre-existing native-tracer property), so memory is best-effort, not byte-exact.</li>
 *   <li>{@code gasCost} is the drop in remaining energy to the next op (0 for the last op).</li>
 * </ul>
 */
public final class StructLogReconstructor {

  private static final int WORD_BYTES = 32;

  private StructLogReconstructor() {
  }

  /** Replays the trace ops into Geth-shaped structLogs (empty list for a null/empty trace). */
  public static List<StructLog> reconstruct(ProgramTrace trace) {
    List<StructLog> logs = new ArrayList<>();
    if (trace == null || trace.getOps() == null) {
      return logs;
    }
    List<org.tron.core.vm.trace.Op> ops = trace.getOps();
    Map<Integer, MachineState> frames = new LinkedHashMap<>();
    int previousDepth = -1;

    for (int i = 0; i < ops.size(); i++) {
      org.tron.core.vm.trace.Op op = ops.get(i);
      int depth = op.getDeep();
      if (previousDepth < depth) {
        frames.put(depth, new MachineState());
      } else if (previousDepth > depth) {
        frames.keySet().removeIf(d -> d > depth);
      }
      MachineState frame = frames.computeIfAbsent(depth, ignored -> new MachineState());
      // Apply this op's recorded deltas (the previous op's mutations) to reach op i's PRE-op state.
      applyActions(op.getActions(), frame.stack, frame.memory, frame.storage);

      long gas = op.getEnergy() == null ? 0L : op.getEnergy().longValue();
      long gasCost = gas - nextFrameGas(ops, i, depth, gas);
      String name = Op.getNameOf(op.getCode());
      logs.add(new StructLog(op.getPc(), name == null ? "INVALID" : name, gas, gasCost,
          depth + 1, new ArrayList<>(frame.stack), toMemoryWords(frame.memory),
          new LinkedHashMap<>(frame.storage)));
      previousDepth = depth;
    }
    return logs;
  }

  private static long nextFrameGas(List<org.tron.core.vm.trace.Op> ops, int index, int depth,
      long currentGas) {
    for (int i = index + 1; i < ops.size(); i++) {
      org.tron.core.vm.trace.Op next = ops.get(i);
      if (next.getDeep() < depth) {
        break;
      }
      if (next.getDeep() == depth) {
        return next.getEnergy() == null ? 0L : next.getEnergy().longValue();
      }
    }
    return currentGas;
  }

  private static void applyActions(OpActions actions, List<String> stack, List<Byte> memory,
      Map<String, String> storage) {
    if (actions == null) {
      return;
    }
    for (Action a : actions.getStack()) {
      applyStackAction(a, stack);
    }
    for (Action a : actions.getMemory()) {
      applyMemoryAction(a, memory);
    }
    for (Action a : actions.getStorage()) {
      applyStorageAction(a, storage);
    }
  }

  private static void applyStackAction(Action a, List<String> stack) {
    switch (a.getName()) {
      case push:
        // value is DataWord.toString() == 64-char hex (no 0x), already the Geth stack word form.
        stack.add(param(a, "value"));
        break;
      case pop:
        if (!stack.isEmpty()) {
          stack.remove(stack.size() - 1);
        }
        break;
      case swap:
        int from = Integer.parseInt(param(a, "from"));
        int to = Integer.parseInt(param(a, "to"));
        if (from >= 0 && from < stack.size() && to >= 0 && to < stack.size()) {
          String tmp = stack.get(from);
          stack.set(from, stack.get(to));
          stack.set(to, tmp);
        }
        break;
      default:
        break;
    }
  }

  private static void applyMemoryAction(Action a, List<Byte> memory) {
    switch (a.getName()) {
      case extend:
        long delta = Long.parseLong(param(a, "delta"));
        for (long n = 0; n < delta; n++) {
          memory.add((byte) 0);
        }
        break;
      case write:
        int address = Integer.parseInt(param(a, "address"));
        byte[] data = Hex.decode(param(a, "data"));
        ensureCapacity(memory, address + data.length);
        for (int k = 0; k < data.length; k++) {
          memory.set(address + k, data[k]);
        }
        break;
      default:
        break;
    }
  }

  private static void applyStorageAction(Action a, Map<String, String> storage) {
    switch (a.getName()) {
      case put:
        // key / value are DataWord.toString() == 64-char hex (no 0x), the Geth storage form.
        storage.put(param(a, "key"), param(a, "value"));
        break;
      case remove:
        // A zeroing SSTORE clears the slot; Geth shows it as a zero word for the touched slot.
        storage.put(param(a, "key"), zeroWord());
        break;
      case clear:
        storage.clear();
        break;
      default:
        break;
    }
  }

  private static void ensureCapacity(List<Byte> memory, int minSize) {
    while (memory.size() < minSize) {
      memory.add((byte) 0);
    }
  }

  private static List<String> toMemoryWords(List<Byte> memory) {
    List<String> words = new ArrayList<>();
    int size = memory.size();
    for (int off = 0; off < size; off += WORD_BYTES) {
      byte[] word = new byte[WORD_BYTES];
      int end = Math.min(off + WORD_BYTES, size);
      for (int k = off; k < end; k++) {
        word[k - off] = memory.get(k);
      }
      words.add(Hex.toHexString(word));
    }
    return words;
  }

  private static String zeroWord() {
    return Hex.toHexString(new byte[WORD_BYTES]);
  }

  private static String param(Action a, String name) {
    Object v = a.getParams() == null ? null : a.getParams().get(name);
    return v == null ? "" : v.toString();
  }

  private static final class MachineState {
    private final List<String> stack = new ArrayList<>();
    private final List<Byte> memory = new ArrayList<>();
    private final Map<String, String> storage = new LinkedHashMap<>();
  }
}
