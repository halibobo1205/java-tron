package org.tron.common.exit;

import lombok.Getter;


@Getter
public class TronExitException extends RuntimeException {

  private final ExitReason exitReason;

  public TronExitException(ExitReason exitReason, String message) {
    super(message);
    this.exitReason = exitReason;
  }

  public TronExitException(ExitReason exitReason, String message, Throwable cause) {
    super(message, cause);
    this.exitReason = exitReason;
  }

}
