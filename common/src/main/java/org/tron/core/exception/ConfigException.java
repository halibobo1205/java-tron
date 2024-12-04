package org.tron.core.exception;

public class ConfigException extends TronRuntimeException {

  public ConfigException() {
    super();
  }

  public ConfigException(String message) {
    super(message);
  }

  public ConfigException(String message, Throwable cause) {
    super(message, cause);
  }
}
