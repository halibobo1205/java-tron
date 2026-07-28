package org.tron.core.services.jsonrpc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.tron.common.runtime.ProgramResult;
import org.tron.common.runtime.vm.DataWord;
import org.tron.core.archive.query.HistoricalQueryLimitException;
import org.tron.core.services.jsonrpc.types.StructLog;
import org.tron.core.vm.Op;
import org.tron.core.vm.program.Program;
import org.tron.core.vm.program.Stack;

public class ArchiveStructLogCollectorTest {

  @Test
  public void snapshotsPreOpStateAndCompletesGasCostPerProgramFrame() throws Exception {
    Map<String, Object> rawOptions = new HashMap<>();
    rawOptions.put("enableMemory", true);
    ArchiveStructLogCollector collector = new ArchiveStructLogCollector(
        DebugTraceOptions.parse(rawOptions), 10L, new DebugTraceBudget(100_000L));
    Program root = program(Op.CALL, 7, 0, 100L, new byte[] {(byte) 0xab});
    Program child = program(Op.PUSH1, 0, 1, 50L, new byte[0]);

    collector.capture(root);
    collector.captureEnergyCost(root, 20L);
    collector.capture(child);
    collector.captureEnergyCost(child, 10L);
    when(child.getEnergylimitLeftLong()).thenReturn(40L);
    collector.onProgramExit(child);
    when(root.getCurrentOpIntValue()).thenReturn(Op.STOP);
    when(root.getPC()).thenReturn(8);
    when(root.getEnergylimitLeftLong()).thenReturn(80L);
    collector.capture(root);
    collector.captureEnergyCost(root, 5L);
    when(root.getEnergylimitLeftLong()).thenReturn(75L);
    collector.onProgramExit(root);

    assertEquals(3, collector.getLogs().size());
    StructLog rootCall = collector.getLogs().get(0);
    assertEquals("CALL", rootCall.getOp());
    assertEquals(20L, rootCall.getGasCost());
    assertEquals(1, rootCall.getDepth());
    assertEquals(1, rootCall.getStack().size());
    assertEquals(1, rootCall.getMemory().size());
    assertEquals(
        "0xab00000000000000000000000000000000000000000000000000000000000000",
        rootCall.getMemory().get(0));
    assertEquals("0x" + new DataWord(1L), rootCall.getStack().get(0));
    assertNull(rootCall.getStorage());

    StructLog childPush = collector.getLogs().get(1);
    assertEquals(10L, childPush.getGasCost());
    assertEquals(2, childPush.getDepth());
    assertNull(childPush.getError());

    StructLog rootStop = collector.getLogs().get(2);
    assertEquals(5L, rootStop.getGasCost());
  }

  @Test
  public void outputLimitDoesNotCorruptLastIncludedGasCost() throws Exception {
    Map<String, Object> rawOptions = new HashMap<>();
    rawOptions.put("limit", 1);
    ArchiveStructLogCollector collector = new ArchiveStructLogCollector(
        DebugTraceOptions.parse(rawOptions), 10L, new DebugTraceBudget(100_000L));
    Program program = program(Op.PUSH1, 0, 0, 100L, new byte[0]);

    collector.capture(program);
    collector.captureEnergyCost(program, 3L);
    when(program.getEnergylimitLeftLong()).thenReturn(97L, 90L);
    when(program.getCurrentOpIntValue()).thenReturn(Op.POP);
    collector.capture(program);
    collector.capture(program);
    collector.onProgramExit(program);

    assertEquals(1, collector.getLogs().size());
    assertEquals(3L, collector.getLogs().get(0).getGasCost());
  }

  @Test
  public void calculatedOpcodeCostWinsOverNetEnergyDeltaOnFailure() throws Exception {
    ArchiveStructLogCollector collector = new ArchiveStructLogCollector(
        DebugTraceOptions.parse(Collections.emptyMap()),
        10L,
        new DebugTraceBudget(100_000L));
    Program program = program(Op.PUSH1, 0, 0, 1L, new byte[0]);

    collector.capture(program);
    collector.captureEnergyCost(program, 3L);
    when(program.getEnergylimitLeftLong()).thenReturn(0L);
    collector.captureFault(program, new RuntimeException("out of energy"));
    collector.onProgramExit(program);

    assertEquals(3L, collector.getLogs().get(0).getGasCost());
    assertEquals("out of energy", collector.getLogs().get(0).getError());
  }

  @Test
  public void missingCalculatedCostStaysZeroForPreCostFault() throws Exception {
    ArchiveStructLogCollector collector = new ArchiveStructLogCollector(
        DebugTraceOptions.parse(Collections.emptyMap()),
        10L,
        new DebugTraceBudget(100_000L));
    Program program = program(0xfe, 0, 0, 10L, new byte[0]);

    collector.capture(program);
    when(program.getEnergylimitLeftLong()).thenReturn(0L);
    collector.captureFault(program, new RuntimeException("invalid opcode"));
    collector.onProgramExit(program);

    assertEquals(0L, collector.getLogs().get(0).getGasCost());
    assertEquals("invalid opcode", collector.getLogs().get(0).getError());
  }

  @Test
  public void normalRevertDoesNotAnnotateTheRevertOpcodeAsFaulting() throws Exception {
    ArchiveStructLogCollector collector = new ArchiveStructLogCollector(
        DebugTraceOptions.parse(Collections.emptyMap()),
        10L,
        new DebugTraceBudget(100_000L));
    Program program = program(Op.REVERT, 0, 0, 10L, new byte[0]);

    collector.capture(program);
    program.getResult().setRevert();
    collector.onProgramExit(program);

    assertNull(collector.getLogs().get(0).getError());
  }

  @Test
  public void mapsStandardWireNamesAndUndefinedOpcodes() throws Exception {
    ArchiveStructLogCollector collector = new ArchiveStructLogCollector(
        DebugTraceOptions.parse(Collections.emptyMap()),
        10L,
        new DebugTraceBudget(100_000L));
    Program program = program(Op.SHA3, 0, 0, 10L, new byte[0]);

    collector.capture(program);
    when(program.getCurrentOpIntValue()).thenReturn(Op.SUICIDE);
    collector.capture(program);
    when(program.getCurrentOpIntValue()).thenReturn(0xfe);
    collector.capture(program);

    assertEquals("KECCAK256", collector.getLogs().get(0).getOp());
    assertEquals("SELFDESTRUCT", collector.getLogs().get(1).getOp());
    assertEquals("opcode 0xfe not defined", collector.getLogs().get(2).getOp());
  }

  @Test
  public void faultTextIsChargedToResponseBudgetBeforeRetention() throws Exception {
    Map<String, Object> rawOptions = new HashMap<>();
    rawOptions.put("disableStack", true);
    ArchiveStructLogCollector collector = new ArchiveStructLogCollector(
        DebugTraceOptions.parse(rawOptions),
        10L,
        new DebugTraceBudget(180L));
    Program program = program(Op.PUSH1, 0, 0, 10L, new byte[0]);
    collector.capture(program);

    HistoricalQueryLimitException failure = assertThrows(
        HistoricalQueryLimitException.class,
        () -> collector.captureFault(program, new RuntimeException("budgeted failure")));

    assertEquals(HistoricalQueryLimitException.Limit.RESPONSE_BYTES, failure.getLimit());
    assertNull(collector.getLogs().get(0).getError());
  }

  @Test
  public void storageSnapshotTracksSloadAndPendingSstoreLikeErigon() throws Exception {
    ArchiveStructLogCollector collector = new ArchiveStructLogCollector(
        DebugTraceOptions.parse(Collections.emptyMap()),
        10L,
        new DebugTraceBudget(100_000L));
    Program program = program(Op.SLOAD, 0, 0, 100L, new byte[0]);
    DataWord key = new DataWord(1L);
    when(program.storageLoad(key)).thenReturn(new DataWord(2L));

    collector.capture(program);
    program.getStack().clear();
    program.getStack().push(new DataWord(3L));
    program.getStack().push(key);
    when(program.getCurrentOpIntValue()).thenReturn(Op.SSTORE);
    when(program.getPC()).thenReturn(1);
    collector.capture(program);

    assertEquals("0x" + new DataWord(2L),
        collector.getLogs().get(0).getStorage().get("0x" + key));
    assertEquals("0x" + new DataWord(3L),
        collector.getLogs().get(1).getStorage().get("0x" + key));
  }

  @Test
  public void returnDataUsesPrefixedLegacyTraceEncoding() throws Exception {
    Map<String, Object> rawOptions = new HashMap<>();
    rawOptions.put("enableReturnData", true);
    ArchiveStructLogCollector collector = new ArchiveStructLogCollector(
        DebugTraceOptions.parse(rawOptions),
        10L,
        new DebugTraceBudget(100_000L));
    Program program = program(Op.RETURNDATASIZE, 0, 0, 100L, new byte[0]);
    when(program.getReturnDataBufferLength()).thenReturn(1);
    when(program.getReturnDataBuffer()).thenReturn(new byte[] {(byte) 0xab});

    collector.capture(program);

    assertEquals("0xab", collector.getLogs().get(0).getReturnData());
  }

  @Test
  public void enabledButEmptyMemoryIsOmittedFromWireResult() throws Exception {
    Map<String, Object> rawOptions = new HashMap<>();
    rawOptions.put("enableMemory", true);
    ArchiveStructLogCollector collector = new ArchiveStructLogCollector(
        DebugTraceOptions.parse(rawOptions),
        10L,
        new DebugTraceBudget(100_000L));
    Program program = program(Op.PUSH1, 0, 0, 100L, new byte[0]);

    collector.capture(program);

    StructLog log = collector.getLogs().get(0);
    assertNull(log.getMemory());
    assertFalse(new ObjectMapper().writeValueAsString(log).contains("\"memory\""));
  }

  @Test
  public void hardStepLimitFailsInsteadOfReturningATruncatedTrace() throws Exception {
    ArchiveStructLogCollector collector = new ArchiveStructLogCollector(
        DebugTraceOptions.parse(Collections.emptyMap()),
        1L,
        new DebugTraceBudget(100_000L));
    Program program = program(Op.PUSH1, 0, 0, 100L, new byte[0]);

    collector.capture(program);
    HistoricalQueryLimitException failure = assertThrows(
        HistoricalQueryLimitException.class, () -> collector.capture(program));

    assertEquals(HistoricalQueryLimitException.Limit.VM_STEPS, failure.getLimit());
  }

  @Test
  public void materializationBudgetFailsBeforeCopyingLargeState() throws Exception {
    ArchiveStructLogCollector collector = new ArchiveStructLogCollector(
        DebugTraceOptions.parse(Collections.emptyMap()),
        10L,
        new DebugTraceBudget(1L));
    Program program = program(Op.PUSH1, 0, 0, 100L, new byte[0]);

    HistoricalQueryLimitException failure = assertThrows(
        HistoricalQueryLimitException.class, () -> collector.capture(program));

    assertEquals(HistoricalQueryLimitException.Limit.RESPONSE_BYTES, failure.getLimit());
  }

  private static Program program(int opCode, int pc, int depth, long energy, byte[] memory) {
    Program program = mock(Program.class);
    Stack stack = new Stack();
    stack.push(new DataWord(1L));
    ProgramResult result = ProgramResult.createEmpty();
    when(program.getCurrentOpIntValue()).thenReturn(opCode);
    when(program.getPC()).thenReturn(pc);
    when(program.getCallDeep()).thenReturn(depth);
    when(program.getEnergylimitLeftLong()).thenReturn(energy);
    when(program.getStack()).thenReturn(stack);
    when(program.getMemorySize()).thenReturn(memory.length);
    when(program.getMemory()).thenReturn(memory);
    when(program.getStorageDiff()).thenReturn(Collections.emptyMap());
    when(program.getReturnDataBufferLength()).thenReturn(0);
    when(program.getReturnDataBuffer()).thenReturn(new byte[0]);
    when(program.getResult()).thenReturn(result);
    when(program.getContextAddress()).thenReturn(new byte[21]);
    return program;
  }
}
