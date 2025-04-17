package org.tron.core.services.jsonrpc.types;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.Map;
import lombok.EqualsAndHashCode;
import lombok.Getter;


@Getter
@JsonPropertyOrder
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@EqualsAndHashCode(callSuper = false)
public class Token10Result extends Result {
  private final String key;
  private final String value;

  public Token10Result(Map.Entry<String, Long> entry) {
    this.key = toHex(Long.parseUnsignedLong(entry.getKey()));
    this.value = toHex(entry.getValue());
  }

  public Token10Result(String key, long value) {
    this.key = toHex(Long.parseUnsignedLong(key));
    this.value = toHex(value);
  }
}
