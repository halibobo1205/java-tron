package org.tron.core.exception;

public class EventTriggerException extends TronRuntimeException {

  public EventTriggerException() {
    super();
  }

  public EventTriggerException(String message) {
    super(message);
  }

  public EventTriggerException(String message, Throwable cause) {
    super(message, cause);
  }
}
