package org.tron.core.exception;

public class EventExitException extends TronExitException {

  public EventExitException(String message) {
    super(message);
    setExitCode(3);
  }

  public EventExitException(String message, Throwable cause) {
    super(message, cause);
    setExitCode(3);
  }

  public EventExitException(Throwable cause) {
    super(cause);
    setExitCode(3);
  }
}
