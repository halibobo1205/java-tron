package org.tron.common.runtime;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.tron.core.vm.program.Program;
import org.tron.core.vm.program.VmResultCodeMapper;
import org.tron.protos.Protocol.Transaction.Result.contractResult;

public class RuntimeImplMockTest {

  @Test
  public void testResultCodeMapping() {
    ProgramResult programResult = new ProgramResult();

    Program.BadJumpDestinationException badJumpDestinationException
        = new Program.BadJumpDestinationException("Operation with pc isn't 'JUMPDEST': PC[%d];", 0);
    programResult.setException(badJumpDestinationException);
    assertEquals(
        contractResult.BAD_JUMP_DESTINATION,
        VmResultCodeMapper.resultCodeOf(programResult));

    Program.OutOfTimeException outOfTimeException
        = new Program.OutOfTimeException("CPU timeout for 0x0a executing");
    programResult.setException(outOfTimeException);
    assertEquals(contractResult.OUT_OF_TIME, VmResultCodeMapper.resultCodeOf(programResult));

    Program.PrecompiledContractException precompiledContractException
        = new Program.PrecompiledContractException("precompiled contract exception");
    programResult.setException(precompiledContractException);
    assertEquals(
        contractResult.PRECOMPILED_CONTRACT,
        VmResultCodeMapper.resultCodeOf(programResult));

    Program.StackTooSmallException stackTooSmallException
        = new Program.StackTooSmallException("Expected stack size %d but actual %d;", 100, 10);
    programResult.setException(stackTooSmallException);
    assertEquals(contractResult.STACK_TOO_SMALL, VmResultCodeMapper.resultCodeOf(programResult));

    Program.JVMStackOverFlowException jvmStackOverFlowException
        = new Program.JVMStackOverFlowException();
    programResult.setException(jvmStackOverFlowException);
    assertEquals(
        contractResult.JVM_STACK_OVER_FLOW,
        VmResultCodeMapper.resultCodeOf(programResult));
  }
}
