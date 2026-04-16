package org.tron.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Drop-in replacement for {@code com.alibaba.fastjson.JSON}.
 */
public final class JSON {

  public static final ObjectMapper MAPPER = JsonMapper.builder()
      // Fastjson omits null-valued fields by default (WriteMapNullValue is OFF by default)
      .serializationInclusion(JsonInclude.Include.NON_NULL)
      .build();

  private JSON() {
  }

  static boolean isNullLiteral(String text) {
    if (text == null) {
      return true;
    }
    String trimmed = text.trim();
    return trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed);
  }

  public static JSONObject parseObject(String text) {
    if (isNullLiteral(text)) {
      return null;
    }
    try {
      JsonNode node = MAPPER.readTree(text);
      if (node == null || node.isNull()) {
        return null;
      }
      if (!node.isObject()) {
        throw new JSONException("can not cast to JSONObject.");
      }
      return new JSONObject((ObjectNode) node);
    } catch (JSONException e) {
      throw e;
    } catch (Exception e) {
      throw new JSONException(e.getMessage(), e);
    }
  }

  public static JsonNode parse(String text) {
    if (isNullLiteral(text)) {
      return null;
    }
    try {
      JsonNode node = MAPPER.readTree(text);
      if (node == null || node.isNull()) {
        return null;
      }
      return node;
    } catch (Exception e) {
      throw new JSONException(e.getMessage(), e);
    }
  }

  public static String toJSONString(Object obj) {
    return toJSONString(obj, false);
  }

  public static String toJSONString(Object obj, boolean pretty) {
    if (obj == null) {
      return "null";
    }
    try {
      if (obj instanceof JSONObject) {
        return pretty ? MAPPER.writerWithDefaultPrettyPrinter()
            .writeValueAsString(((JSONObject) obj).unwrap())
            : MAPPER.writeValueAsString(((JSONObject) obj).unwrap());
      }
      if (obj instanceof JSONArray) {
        return pretty ? MAPPER.writerWithDefaultPrettyPrinter()
            .writeValueAsString(((JSONArray) obj).unwrap())
            : MAPPER.writeValueAsString(((JSONArray) obj).unwrap());
      }
      return pretty ? MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(obj)
          : MAPPER.writeValueAsString(obj);
    } catch (Exception e) {
      throw new JSONException(e.getMessage(), e);
    }
  }
}
