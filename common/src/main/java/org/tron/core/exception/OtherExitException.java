package org.tron.core.exception;

public class OtherExitException extends TronExitException {

  public OtherExitException(String message) {
    super(message);
    setExitCode(99);
  }

  public OtherExitException(String message, Throwable cause) {
    super(message, cause);
    setExitCode(99);
  }

  public OtherExitException(Throwable cause) {
    super(cause);
    setExitCode(99);
  }
}
