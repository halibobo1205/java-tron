package org.tron.core.exception;

public class DatabaseExitException extends TronExitException {

  public DatabaseExitException(String message) {
    super(message);
    setExitCode(2);
  }

  public DatabaseExitException(String message, Throwable cause) {
    super(message, cause);
    setExitCode(2);
  }

  public DatabaseExitException(Throwable cause) {
    super(cause);
    setExitCode(2);
  }
}
