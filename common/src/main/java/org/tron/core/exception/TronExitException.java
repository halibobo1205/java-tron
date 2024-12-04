package org.tron.core.exception;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TronExitException extends RuntimeException {

  private int exitCode;

  public TronExitException(String message) {
    super(message);
  }

  public TronExitException(String message, Throwable cause) {
    super(message, cause);
  }

  public TronExitException(Throwable cause) {
    super(cause);
  }
}
