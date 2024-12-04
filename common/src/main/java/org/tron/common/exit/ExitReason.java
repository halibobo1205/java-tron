package org.tron.common.exit;

import lombok.Getter;

@Getter
public enum ExitReason {
  NORMAL_SHUTDOWN(0, "Normal"),
  CONFIG_ERROR(1, "Configuration"),
  INITIALIZATION_ERROR(2, "Initialization"),
  DATABASE_ERROR(3, "Database"),
  EVENT_ERROR(4, "Event Trigger"),
  FATAL_ERROR(-1, "Fatal");


  private final int code;
  private final String description;

  ExitReason(int code, String description) {
    this.code = code;
    this.description = description;
  }
}
