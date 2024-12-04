package org.tron.core.exception;

public class ConfigExitException extends TronExitException {

  public ConfigExitException(String message) {
    super(message);
    setExitCode(1);
  }

  public ConfigExitException(String message, Throwable cause) {
    super(message, cause);
    setExitCode(1);
  }

  public ConfigExitException(Throwable cause) {
    super(cause);
    setExitCode(1);
  }
}
