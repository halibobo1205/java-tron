package org.tron.core.exception;

public class GenesisBlockException extends TronRuntimeException {

  public GenesisBlockException() {
    super();
  }

  public GenesisBlockException(String message) {
    super(message);
  }

  public GenesisBlockException(String message, Throwable cause) {
    super(message, cause);
  }
}
