package org.tron.json;

/**
 * Drop-in replacement for {@code com.alibaba.fastjson.JSONException}.
 * Swap the import line; no other source changes required.
 */
public class JSONException extends RuntimeException {

  public JSONException(String message) {
    super(message);
  }

  public JSONException(String message, Throwable cause) {
    super(message, cause);
  }

  public JSONException(Throwable cause) {
    super(cause);
  }
}
