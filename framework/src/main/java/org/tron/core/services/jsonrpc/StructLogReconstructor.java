package org.tron.core.services.jsonrpc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bouncycastle.util.encoders.DecoderException;
import org.bouncycastle.util.encoders.Hex;
import org.tron.common.math.StrictMathWrapper;
import org.tron.core.archive.query.QueryContext;
import org.tron.core.archive.query.QueryContextHolder;
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
 *   <li>{@code memory} is rebuilt from recorded write deltas. Legacy odd-nibble deltas are padded
 *       and malformed deltas are ignored, so traces produced by older nodes remain
 *       best-effort.</li>
 *   <li>{@code gasCost} uses the native per-op energy cost when available, falling back to the
 *       drop in remaining energy to the next same-frame op for legacy traces.</li>
 * </ul>
 */
public final class StructLogReconstructor {

  private static final int WORD_BYTES = 32;
  private static final long STRUCT_LOG_BASE_BYTES = 256L;
  private static final long JSON_SEQUENCE_ENTRY_BYTES = 3L;
  private static final long JSON_STORAGE_ENTRY_BYTES = 6L;
  private static final int HEX_WORD_CHARACTERS = WORD_BYTES * 2;

  private StructLogReconstructor() {
  }

  /** Replays the trace ops into Geth-shaped structLogs (empty list for a null/empty trace). */
  public static List<StructLog> reconstruct(ProgramTrace trace) {
    QueryContext queryContext = QueryContextHolder.current();
    QueryContext traceQueryContext = trace == null ? null : trace.historicalQueryContext();
    if (queryContext == null && traceQueryContext != null) {
      try (QueryContextHolder.Scope ignored = QueryContextHolder.attach(traceQueryContext)) {
        return reconstruct(trace, traceQueryContext);
      }
    }
    return reconstruct(trace, queryContext);
  }

  private static List<StructLog> reconstruct(ProgramTrace trace, QueryContext queryContext) {
    if (queryContext != null) {
      queryContext.checkDeadline();
    }
    List<StructLog> logs = new ArrayList<>();
    if (trace == null || trace.getOps() == null) {
      return logs;
    }
    List<org.tron.core.vm.trace.Op> ops = trace.getOps();
    Map<Integer, MachineState> frames = new LinkedHashMap<>();
    int previousDepth = -1;

    for (int i = 0; i < ops.size(); i++) {
      if (queryContext != null) {
        queryContext.checkDeadline();
      }
      org.tron.core.vm.trace.Op op = ops.get(i);
      int depth = op.getDeep();
      if (previousDepth < depth) {
        frames.put(depth, new MachineState());
      } else if (previousDepth > depth) {
        frames.keySet().removeIf(d -> d > depth);
      }
      MachineState frame = frames.computeIfAbsent(depth, ignored -> new MachineState());
      String name = Op.getNameOf(op.getCode());
      String opName = name == null ? "INVALID" : name;
      if (queryContext != null) {
        queryContext.recordResponseBytes(
            estimatedResponseBytes(opName, frame, op.getActions()));
      }
      // Apply this op's recorded deltas (the previous op's mutations) to reach op i's PRE-op state.
      applyActions(op.getActions(), frame.stack, frame.memory, frame.storage);
      if (queryContext != null) {
        queryContext.checkDeadline();
      }

      long gas = op.getEnergy() == null ? 0L : op.getEnergy().longValue();
      long gasCost = gasCost(ops, i, depth, gas, queryContext);
      logs.add(new StructLog(op.getPc(), opName, gas, gasCost,
          depth + 1, new ArrayList<>(frame.stack), toMemoryWords(frame.memory),
          new LinkedHashMap<>(frame.storage)));
      previousDepth = depth;
    }
    return logs;
  }

  private static long gasCost(List<org.tron.core.vm.trace.Op> ops, int index, int depth,
      long gas, QueryContext queryContext) {
    org.tron.core.vm.trace.Op op = ops.get(index);
    if (op.getEnergyCost() != null) {
      return op.getEnergyCost().longValue();
    }
    return gas - nextFrameGas(ops, index, depth, gas, queryContext);
  }

  private static long nextFrameGas(List<org.tron.core.vm.trace.Op> ops, int index, int depth,
      long currentGas, QueryContext queryContext) {
    for (int i = index + 1; i < ops.size(); i++) {
      if (queryContext != null) {
        queryContext.checkDeadline();
      }
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

  private static long estimatedResponseBytes(String opName, MachineState frame,
      OpActions actions) {
    long stackEntries = frame.stack.size();
    long stackCharacters = totalCharacters(frame.stack);
    long memoryBytes = frame.memory.size();
    long storageEntries = frame.storage.size();
    long storageCharacters = totalStorageCharacters(frame.storage);

    if (actions != null) {
      List<Action> stackActions = actions.getStack();
      if (stackActions != null) {
        for (Action action : stackActions) {
          if (action == null || action.getName() == null) {
            continue;
          }
          if (action.getName() == Action.Name.push) {
            stackEntries = addSaturated(stackEntries, 1L);
            stackCharacters = addSaturated(
                stackCharacters, param(action, "value").length());
          } else if (action.getName() == Action.Name.pop && stackEntries > 0) {
            // Retaining the popped word's characters is a small, safe overestimate.
            stackEntries--;
          }
        }
      }

      List<Action> memoryActions = actions.getMemory();
      if (memoryActions != null) {
        for (Action action : memoryActions) {
          if (action == null || action.getName() == null) {
            continue;
          }
          if (action.getName() == Action.Name.extend) {
            long delta = Long.parseLong(param(action, "delta"));
            if (delta > 0) {
              memoryBytes = addSaturated(memoryBytes, delta);
            }
          } else if (action.getName() == Action.Name.write) {
            int address = Integer.parseInt(param(action, "address"));
            if (address >= 0) {
              long encodedLength = param(action, "data").length();
              long decodedLength = (encodedLength + 1L) / 2L;
              memoryBytes = StrictMathWrapper.max(
                  memoryBytes, addSaturated(address, decodedLength));
            }
          }
        }
      }

      List<Action> storageActions = actions.getStorage();
      if (storageActions != null) {
        for (Action action : storageActions) {
          if (action == null || action.getName() == null) {
            continue;
          }
          switch (action.getName()) {
            case put:
              storageEntries = addSaturated(storageEntries, 1L);
              storageCharacters = addSaturated(storageCharacters,
                  addSaturated(param(action, "key").length(),
                      param(action, "value").length()));
              break;
            case remove:
              storageEntries = addSaturated(storageEntries, 1L);
              storageCharacters = addSaturated(storageCharacters,
                  addSaturated(param(action, "key").length(), HEX_WORD_CHARACTERS));
              break;
            case clear:
              storageEntries = 0L;
              storageCharacters = 0L;
              break;
            default:
              break;
          }
        }
      }
    }

    long estimated = addSaturated(STRUCT_LOG_BASE_BYTES, opName.length());
    estimated = addSaturated(estimated, stackCharacters);
    estimated = addSaturated(estimated,
        multiplySaturated(stackEntries, JSON_SEQUENCE_ENTRY_BYTES));
    long memoryWords = memoryBytes == 0 ? 0 : 1L + ((memoryBytes - 1L) / WORD_BYTES);
    estimated = addSaturated(estimated,
        multiplySaturated(memoryWords, HEX_WORD_CHARACTERS + JSON_SEQUENCE_ENTRY_BYTES));
    estimated = addSaturated(estimated, storageCharacters);
    return addSaturated(estimated,
        multiplySaturated(storageEntries, JSON_STORAGE_ENTRY_BYTES));
  }

  private static long totalCharacters(List<String> values) {
    long total = 0L;
    for (String value : values) {
      if (value != null) {
        total = addSaturated(total, value.length());
      }
    }
    return total;
  }

  private static long totalStorageCharacters(Map<String, String> storage) {
    long total = 0L;
    for (Map.Entry<String, String> entry : storage.entrySet()) {
      if (entry.getKey() != null) {
        total = addSaturated(total, entry.getKey().length());
      }
      if (entry.getValue() != null) {
        total = addSaturated(total, entry.getValue().length());
      }
    }
    return total;
  }

  private static long addSaturated(long left, long right) {
    return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
  }

  private static long multiplySaturated(long left, long right) {
    return left != 0 && right > Long.MAX_VALUE / left ? Long.MAX_VALUE : left * right;
  }

  private static void applyActions(OpActions actions, List<String> stack, MemoryBuffer memory,
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

  private static void applyMemoryAction(Action a, MemoryBuffer memory) {
    switch (a.getName()) {
      case extend:
        long delta = Long.parseLong(param(a, "delta"));
        memory.extend(delta);
        break;
      case write:
        int address = Integer.parseInt(param(a, "address"));
        byte[] data = decodeMemoryData(param(a, "data"));
        if (data.length == 0) {
          return;
        }
        memory.write(address, data);
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

  private static byte[] decodeMemoryData(String encoded) {
    // Old OpActions treated byte count as hex-char count. MSTORE8 therefore emitted one nibble;
    // pad on the right because the old truncation removed trailing nibbles.
    String normalized = (encoded.length() & 1) == 0 ? encoded : encoded + "0";
    try {
      return Hex.decode(normalized);
    } catch (DecoderException e) {
      return new byte[0];
    }
  }

  private static List<String> toMemoryWords(MemoryBuffer memory) {
    List<String> words = new ArrayList<>();
    int size = memory.size();
    for (int off = 0; off < size; off += WORD_BYTES) {
      byte[] word = new byte[WORD_BYTES];
      memory.copyTo(off, word, StrictMathWrapper.min(WORD_BYTES, size - off));
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
    private final MemoryBuffer memory = new MemoryBuffer();
    private final Map<String, String> storage = new LinkedHashMap<>();
  }

  /** Primitive, grow-only VM memory image; avoids one boxed object per byte during trace replay. */
  private static final class MemoryBuffer {

    private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;
    private byte[] bytes = new byte[0];
    private int size;

    private int size() {
      return size;
    }

    private void extend(long delta) {
      if (delta < 0 || delta > MAX_ARRAY_SIZE - (long) size) {
        throw new IllegalArgumentException("invalid historical trace memory extension");
      }
      int newSize = size + (int) delta;
      ensureCapacity(newSize);
      size = newSize;
    }

    private void write(int address, byte[] value) {
      if (address < 0 || value.length > MAX_ARRAY_SIZE - address) {
        throw new IllegalArgumentException("invalid historical trace memory write");
      }
      int end = address + value.length;
      ensureCapacity(end);
      System.arraycopy(value, 0, bytes, address, value.length);
      size = StrictMathWrapper.max(size, end);
    }

    private void copyTo(int sourceOffset, byte[] target, int length) {
      System.arraycopy(bytes, sourceOffset, target, 0, length);
    }

    private void ensureCapacity(int minimum) {
      if (minimum <= bytes.length) {
        return;
      }
      long grown = bytes.length + (bytes.length >> 1) + 1L;
      int capacity = (int) StrictMathWrapper.min(
          MAX_ARRAY_SIZE, StrictMathWrapper.max(grown, minimum));
      byte[] replacement = new byte[capacity];
      System.arraycopy(bytes, 0, replacement, 0, size);
      bytes = replacement;
    }
  }
}
