package org.tron.common.runtime.vm;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.After;
import org.junit.Test;
import org.tron.core.vm.EnergyCost;
import org.tron.core.vm.JumpTable;
import org.tron.core.vm.Op;
import org.tron.core.vm.OperationRegistry;
import org.tron.core.vm.config.VMConfig;
import org.tron.core.vm.program.Program;
import org.tron.core.vm.program.Stack;

public class OperationRegistryIsolationTest {

  @After
  public void resetVmConfig() {
    VMConfig.clearLocalSnapshot();
    VMConfig.initAllowHigherLimitForMaxCpuTimeOfOneTx(0);
  }

  @Test
  public void getTableDoesNotLeakMemoryCostAdjustmentAcrossVmConfigViews() {
    VMConfig.clearLocalSnapshot();
    VMConfig.initAllowHigherLimitForMaxCpuTimeOfOneTx(1);
    JumpTable postForkTable = OperationRegistry.getTable();
    assertEquals(EnergyCost.getMloadCost2(mloadProgram()),
        postForkTable.get(Op.MLOAD).getEnergyCost(mloadProgram()));

    VMConfig.initAllowHigherLimitForMaxCpuTimeOfOneTx(0);
    JumpTable preForkTable = OperationRegistry.getTable();
    assertEquals(EnergyCost.getMloadCost(mloadProgram()),
        preForkTable.get(Op.MLOAD).getEnergyCost(mloadProgram()));
  }

  private Program mloadProgram() {
    Program program = mock(Program.class);
    Stack stack = new Stack();
    stack.push(DataWord.ZERO());
    when(program.getStack()).thenReturn(stack);
    when(program.getMemSize()).thenReturn(0);
    return program;
  }
}
