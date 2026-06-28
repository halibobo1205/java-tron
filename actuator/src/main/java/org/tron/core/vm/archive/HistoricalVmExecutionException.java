package org.tron.core.vm.archive;

/**
 * Thrown when a historical constant call aborts inside the TVM with a hard execution error (for
 * example out-of-time or an illegal operation) rather than a clean return or revert. The cause is
 * the original {@code ProgramResult} exception.
 */
public class HistoricalVmExecutionException extends RuntimeException {

  public HistoricalVmExecutionException(String message, Throwable cause) {
    super(message, cause);
  }
}
