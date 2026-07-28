package org.tron.core.vm.program;

import org.tron.common.runtime.ProgramResult;
import org.tron.core.vm.program.Program.BadJumpDestinationException;
import org.tron.core.vm.program.Program.IllegalOperationException;
import org.tron.core.vm.program.Program.JVMStackOverFlowException;
import org.tron.core.vm.program.Program.OutOfEnergyException;
import org.tron.core.vm.program.Program.OutOfMemoryException;
import org.tron.core.vm.program.Program.OutOfTimeException;
import org.tron.core.vm.program.Program.PrecompiledContractException;
import org.tron.core.vm.program.Program.StackTooLargeException;
import org.tron.core.vm.program.Program.StackTooSmallException;
import org.tron.protos.Protocol.Transaction.Result.contractResult;

/** Maps a terminal TVM program result to the consensus receipt code. */
public final class VmResultCodeMapper {

  private VmResultCodeMapper() {
  }

  public static contractResult resultCodeOf(ProgramResult result) {
    if (result == null) {
      throw new NullPointerException("result");
    }
    RuntimeException exception = result.getException();
    String runtimeError = result.getRuntimeError();
    if (exception == null && (runtimeError == null || runtimeError.isEmpty())
        && !result.isRevert()) {
      return contractResult.SUCCESS;
    }
    if (result.isRevert()) {
      return contractResult.REVERT;
    }
    if (exception instanceof IllegalOperationException) {
      return contractResult.ILLEGAL_OPERATION;
    }
    if (exception instanceof OutOfEnergyException) {
      return contractResult.OUT_OF_ENERGY;
    }
    if (exception instanceof BadJumpDestinationException) {
      return contractResult.BAD_JUMP_DESTINATION;
    }
    if (exception instanceof OutOfTimeException) {
      return contractResult.OUT_OF_TIME;
    }
    if (exception instanceof OutOfMemoryException) {
      return contractResult.OUT_OF_MEMORY;
    }
    if (exception instanceof PrecompiledContractException) {
      return contractResult.PRECOMPILED_CONTRACT;
    }
    if (exception instanceof StackTooSmallException) {
      return contractResult.STACK_TOO_SMALL;
    }
    if (exception instanceof StackTooLargeException) {
      return contractResult.STACK_TOO_LARGE;
    }
    if (exception instanceof JVMStackOverFlowException) {
      return contractResult.JVM_STACK_OVER_FLOW;
    }
    if (exception instanceof Program.TransferException) {
      return contractResult.TRANSFER_FAILED;
    }
    if (exception instanceof Program.InvalidCodeException) {
      return contractResult.INVALID_CODE;
    }
    return contractResult.UNKNOWN;
  }
}
