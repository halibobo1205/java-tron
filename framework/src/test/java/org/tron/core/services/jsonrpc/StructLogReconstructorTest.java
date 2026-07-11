package org.tron.core.services.jsonrpc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bouncycastle.util.encoders.Hex;
import org.junit.Test;
import org.tron.common.runtime.vm.DataWord;
import org.tron.core.services.jsonrpc.types.StructLog;
import org.tron.core.vm.trace.OpActions;
import org.tron.core.vm.trace.ProgramTrace;

public class StructLogReconstructorTest {

  @Test
  public void nestedTraceKeepsMachineStatePerFrame() {
    ProgramTrace trace = new ProgramTrace();
    trace.setOps(Arrays.asList(
        op(0x60, 0, 0, 1000L, 3L, actions()),
        op(0x60, 2, 0, 990L, 4L, push(1)),
        op(0x60, 0, 1, 800L, 5L, actions()),
        op(0x00, 2, 1, 790L, 6L, push(2)),
        op(0x00, 4, 0, 900L, 7L, actions())));

    List<StructLog> logs = StructLogReconstructor.reconstruct(trace);

    assertTrue(logs.get(0).getStack().isEmpty());
    assertEquals(Arrays.asList(word(1)), logs.get(1).getStack());
    assertTrue("child frame starts with an empty stack, not the parent stack",
        logs.get(2).getStack().isEmpty());
    assertEquals(Arrays.asList(word(2)), logs.get(3).getStack());
    assertEquals("parent frame state survives the child frame",
        Arrays.asList(word(1)), logs.get(4).getStack());
    assertEquals("parent gasCost uses the recorded opcode cost, not child-frame net energy",
        4L, logs.get(1).getGasCost());
    assertEquals("child gasCost uses its recorded opcode cost",
        6L, logs.get(3).getGasCost());
  }

  @Test
  public void legacyTraceGasCostFallsBackToSameFrameDelta() {
    ProgramTrace trace = new ProgramTrace();
    trace.setOps(Arrays.asList(
        op(0x60, 0, 0, 1000L, actions()),
        op(0x60, 2, 0, 990L, push(1)),
        op(0x60, 0, 1, 800L, actions()),
        op(0x00, 2, 1, 790L, push(2)),
        op(0x00, 4, 0, 900L, actions())));

    List<StructLog> logs = StructLogReconstructor.reconstruct(trace);

    assertEquals(90L, logs.get(1).getGasCost());
    assertEquals(0L, logs.get(3).getGasCost());
  }

  @Test
  public void legacyOddLengthMemoryWriteIsPaddedInsteadOfCrashing() {
    ProgramTrace trace = new ProgramTrace();
    trace.setOps(Arrays.asList(op(0x00, 0, 0, 1000L, legacyMemoryWrite("a"))));

    List<StructLog> logs = StructLogReconstructor.reconstruct(trace);

    byte[] expected = new byte[32];
    expected[0] = (byte) 0xa0;
    assertEquals(Hex.toHexString(expected), logs.get(0).getMemory().get(0));
  }

  private static org.tron.core.vm.trace.Op op(int code, int pc, int deep, long energy,
      OpActions actions) {
    org.tron.core.vm.trace.Op op = new org.tron.core.vm.trace.Op();
    op.setCode(code);
    op.setPc(pc);
    op.setDeep(deep);
    op.setEnergy(BigInteger.valueOf(energy));
    op.setActions(actions);
    return op;
  }

  private static org.tron.core.vm.trace.Op op(int code, int pc, int deep, long energy,
      long energyCost, OpActions actions) {
    org.tron.core.vm.trace.Op op = op(code, pc, deep, energy, actions);
    op.setEnergyCost(BigInteger.valueOf(energyCost));
    return op;
  }

  private static OpActions actions() {
    return new OpActions();
  }

  private static OpActions push(long value) {
    OpActions actions = new OpActions();
    actions.addStackPush(new DataWord(value));
    return actions;
  }

  private static OpActions legacyMemoryWrite(String data) {
    OpActions.Action write = new OpActions.Action();
    write.setName(OpActions.Action.Name.write);
    Map<String, Object> params = new HashMap<>();
    params.put("address", "0");
    params.put("data", data);
    write.setParams(params);
    OpActions actions = new OpActions();
    actions.setMemory(Arrays.asList(write));
    return actions;
  }

  private static String word(long value) {
    return new DataWord(value).toString();
  }
}
