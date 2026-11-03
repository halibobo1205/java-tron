package org.tron.core.db2.view;

import lombok.Getter;

@Getter
public class HeadViewException extends RuntimeException {

  public enum Reason {
    EXPIRED("HEAD_VIEW_EXPIRED"),
    UNAVAILABLE("HEAD_VIEW_UNAVAILABLE"),
    UNSUPPORTED("INVALID_STATE_VIEW"),
    RESPONSE_TOO_LARGE("HEAD_VIEW_RESPONSE_TOO_LARGE");

    @Getter
    private final String code;

    Reason(String code) {
      this.code = code;
    }
  }

  private final Reason reason;

  public HeadViewException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }
}
