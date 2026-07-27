package org.tron.core.services.jsonrpc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.bouncycastle.util.encoders.Hex;
import org.tron.common.runtime.ProgramResult;
import org.tron.common.runtime.vm.DataWord;
import org.tron.core.archive.query.HistoricalQueryLimitException;
import org.tron.core.db.ByteArrayWrapper;
import org.tron.core.services.jsonrpc.types.StructLog;
import org.tron.core.vm.Op;
import org.tron.core.vm.program.Program;
import org.tron.core.vm.trace.VmStructuredTraceListener;

/** Direct pre-op state collector for the default historical debug tracer. */
final class ArchiveStructLogCollector implements VmStructuredTraceListener {

  private static final int WORD_BYTES = 32;
  private static final long BASE_ENTRY_BYTES = 160L;
  private static final long HEX_WORD_BYTES = 69L;
  private static final long STORAGE_ENTRY_BYTES = 138L;

  private final DebugTraceOptions options;
  private final long maxTraceSteps;
  private final DebugTraceBudget budget;
  private final List<StructLog> logs = new ArrayList<>();
  private final IdentityHashMap<Program, StructLog> lastLogByProgram =
      new IdentityHashMap<>();
  private final Map<ByteArrayWrapper, Map<DataWord, DataWord>> storageByAddress =
      new HashMap<>();
  private long observedSteps;

  ArchiveStructLogCollector(DebugTraceOptions options, long maxTraceSteps,
      DebugTraceBudget budget) {
    this.options = options;
    this.maxTraceSteps = maxTraceSteps;
    this.budget = budget;
  }

  @Override
  public void capture(Program program) {
    observedSteps++;
    if (observedSteps > maxTraceSteps) {
      throw new HistoricalQueryLimitException(
          HistoricalQueryLimitException.Reason.RESOURCE_EXHAUSTED,
          HistoricalQueryLimitException.Limit.VM_STEPS,
          maxTraceSteps,
          observedSteps,
          "historical debug trace step budget exceeded: limit="
              + maxTraceSteps + ", observed=" + observedSteps);
    }

    long gas = nonNegative(program.getEnergylimitLeftLong());
    if (options.getLimit() > 0 && logs.size() >= options.getLimit()) {
      lastLogByProgram.remove(program);
      return;
    }

    int stackWords = options.isDisableStack() ? 0 : program.getStack().size();
    int memoryWords = options.isEnableMemory()
        ? (program.getMemorySize() + WORD_BYTES - 1) / WORD_BYTES : 0;
    Map<DataWord, DataWord> touchedStorage = options.isDisableStorage()
        ? null : captureTouchedStorage(program);
    int storageEntries = touchedStorage == null ? 0 : touchedStorage.size();
    int returnDataBytes = options.isEnableReturnData()
        ? program.getReturnDataBufferLength() : 0;
    budget.reserve(estimatedBytes(
        stackWords, memoryWords, storageEntries, returnDataBytes));

    List<String> stack = options.isDisableStack() ? null : snapshotStack(program);
    List<String> memory = options.isEnableMemory() ? snapshotMemory(program.getMemory()) : null;
    Map<String, String> storage =
        touchedStorage == null ? null : snapshotStorage(touchedStorage);
    String returnData = options.isEnableReturnData()
        && program.getReturnDataBufferLength() > 0
        ? "0x" + Hex.toHexString(program.getReturnDataBuffer()) : null;
    String opName = Op.getNameOf(program.getCurrentOpIntValue());
    StructLog log = new StructLog(
        program.getPC(),
        opName == null ? "INVALID" : opName,
        gas,
        program.getCallDeep() + 1,
        stack,
        memory,
        storage,
        returnData);
    logs.add(log);
    lastLogByProgram.put(program, log);
  }

  @Override
  public void captureEnergyCost(Program program, long energyCost) {
    StructLog current = lastLogByProgram.get(program);
    if (current == null) {
      return;
    }
    current.setGasCost(nonNegative(energyCost));
  }

  @Override
  public void onProgramExit(Program program) {
    StructLog lastLog = lastLogByProgram.get(program);
    String error = terminalError(program.getResult());
    if (lastLog != null && error != null) {
      lastLog.setError(error);
    }
    lastLogByProgram.remove(program);
  }

  List<StructLog> getLogs() {
    return logs;
  }

  private Map<DataWord, DataWord> captureTouchedStorage(Program program) {
    int opCode = program.getCurrentOpIntValue();
    if (opCode != Op.SLOAD && opCode != Op.SSTORE) {
      return null;
    }
    Map<DataWord, DataWord> touched = storageByAddress.computeIfAbsent(
        new ByteArrayWrapper(program.getContextAddress()), ignored -> new HashMap<>());
    int stackSize = program.getStack().size();
    if (opCode == Op.SLOAD && stackSize >= 1) {
      DataWord key = program.getStack().get(stackSize - 1).clone();
      DataWord value = program.storageLoad(key);
      touched.put(key, value == null ? DataWord.ZERO.clone() : value.clone());
    } else if (opCode == Op.SSTORE && stackSize >= 2) {
      DataWord key = program.getStack().get(stackSize - 1).clone();
      DataWord value = program.getStack().get(stackSize - 2).clone();
      touched.put(key, value);
    } else {
      return null;
    }
    return touched;
  }

  private static List<String> snapshotStack(Program program) {
    List<String> stack = new ArrayList<>(program.getStack().size());
    for (DataWord word : program.getStack()) {
      stack.add("0x" + word);
    }
    return stack;
  }

  private static List<String> snapshotMemory(byte[] memory) {
    List<String> words = new ArrayList<>(
        (memory.length + WORD_BYTES - 1) / WORD_BYTES);
    for (int offset = 0; offset < memory.length; offset += WORD_BYTES) {
      byte[] word = new byte[WORD_BYTES];
      int remaining = memory.length - offset;
      int length = remaining < WORD_BYTES ? remaining : WORD_BYTES;
      System.arraycopy(memory, offset, word, 0, length);
      words.add("0x" + Hex.toHexString(word));
    }
    return words;
  }

  private static Map<String, String> snapshotStorage(Map<DataWord, DataWord> storageDiff) {
    Map<String, String> sorted = new TreeMap<>();
    for (Map.Entry<DataWord, DataWord> entry : storageDiff.entrySet()) {
      sorted.put("0x" + entry.getKey(), "0x" + entry.getValue());
    }
    return new LinkedHashMap<>(sorted);
  }

  private static long estimatedBytes(int stackWords, int memoryWords,
      int storageEntries, int returnDataBytes) {
    long estimate = BASE_ENTRY_BYTES;
    estimate = saturatedAdd(estimate, saturatedMultiply(stackWords, HEX_WORD_BYTES));
    estimate = saturatedAdd(estimate, saturatedMultiply(memoryWords, HEX_WORD_BYTES));
    estimate = saturatedAdd(estimate, saturatedMultiply(storageEntries, STORAGE_ENTRY_BYTES));
    return saturatedAdd(estimate, saturatedMultiply(returnDataBytes, 2L));
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

  private static String terminalError(ProgramResult result) {
    if (result.getException() != null) {
      String message = result.getException().getMessage();
      return message == null || message.isEmpty()
          ? result.getException().getClass().getSimpleName() : message;
    }
    if (result.isRevert()) {
      return "execution reverted";
    }
    return result.getRuntimeError() == null || result.getRuntimeError().isEmpty()
        ? null : result.getRuntimeError();
  }
}
