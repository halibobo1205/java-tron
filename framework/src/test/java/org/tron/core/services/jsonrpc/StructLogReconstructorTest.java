package org.tron.core.services.jsonrpc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
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
        op(0x60, 0, 0, 1000L, actions()),
        op(0x60, 2, 0, 990L, push(1)),
        op(0x60, 0, 1, 800L, actions()),
        op(0x00, 2, 1, 790L, push(2)),
        op(0x00, 4, 0, 900L, actions())));

    List<StructLog> logs = StructLogReconstructor.reconstruct(trace);

    assertTrue(logs.get(0).getStack().isEmpty());
    assertEquals(Arrays.asList(word(1)), logs.get(1).getStack());
    assertTrue("child frame starts with an empty stack, not the parent stack",
        logs.get(2).getStack().isEmpty());
    assertEquals(Arrays.asList(word(2)), logs.get(3).getStack());
    assertEquals("parent frame state survives the child frame",
        Arrays.asList(word(1)), logs.get(4).getStack());
    assertEquals("parent gasCost uses the next parent op after the child frame",
        90L, logs.get(1).getGasCost());
    assertEquals("child frame last op has no same-frame successor",
        0L, logs.get(3).getGasCost());
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

  private static OpActions actions() {
    return new OpActions();
  }

  private static OpActions push(long value) {
    OpActions actions = new OpActions();
    actions.addStackPush(new DataWord(value));
    return actions;
  }

  private static String word(long value) {
    return new DataWord(value).toString();
  }
}
