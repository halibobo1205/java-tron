package org.tron.core.vm.trace;

import org.tron.core.vm.program.Program;

/**
 * Request-owned structured trace sink. It is installed explicitly on one historical execution and
 * inherited by nested programs; canonical execution never consults a global or thread-local sink.
 */
public interface VmStructuredTraceListener {

  /** Captures the current program state immediately before its current opcode executes. */
  void capture(Program program);

  /** Records the TVM energy cost calculated for the captured opcode before it is charged. */
  default void captureEnergyCost(Program program, long energyCost) {
  }

  /** Attaches a fault raised while executing the most recently captured opcode. */
  default void captureFault(Program program, Throwable failure) {
  }

  /** Completes the last opcode in this program frame from its terminal state. */
  void onProgramExit(Program program);
}
