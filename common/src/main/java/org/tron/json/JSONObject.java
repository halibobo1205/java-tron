package org.tron.json;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;

/**
 * Drop-in replacement for {@code com.alibaba.fastjson.JSONObject}.
 *
 * <p>Note: {@code put(key, null)} removes the key instead of storing a JSON
 * {@code null}. This matches Fastjson's default serialization output
 * ({@code WriteMapNullValue=OFF} omits null fields).
 */
public class JSONObject {

  private final ObjectNode node;

  public JSONObject(ObjectNode node) {
    this.node = node;
  }

  public JSONObject() {
    this.node = JSON.MAPPER.createObjectNode();
  }

  public static JSONObject parseObject(String text) {
    return JSON.parseObject(text);
  }

  public boolean containsKey(String key) {
    return node.has(key);
  }

  public String getString(String key) {
    JsonNode child = node.get(key);
    if (child == null || child.isNull()) {
      return null;
    }
    return child.asText(null);
  }

  public Boolean getBoolean(String key) {
    JsonNode child = node.get(key);
    if (child == null || child.isNull()) {
      return null;
    }
    return child.booleanValue();
  }

  public Integer getInteger(String key) {
    JsonNode child = node.get(key);
    if (child == null || child.isNull()) {
      return null;
    }
    return child.asInt();
  }

  public BigDecimal getBigDecimal(String key) {
    JsonNode child = node.get(key);
    if (child == null || child.isNull()) {
      return null;
    }
    return child.decimalValue();
  }

  public Object get(String key) {
    return convertNode(node.get(key));
  }

  static Object convertNode(JsonNode child) {
    if (child == null || child.isNull()) {
      return null;
    }
    if (child.isObject()) {
      return new JSONObject((ObjectNode) child);
    }
    if (child.isArray()) {
      return new JSONArray((ArrayNode) child);
    }
    return child.asText();
  }

  public JSONObject getJSONObject(String key) {
    JsonNode child = node.get(key);
    if (child == null || child.isNull()) {
      return null;
    }
    return new JSONObject((ObjectNode) child);
  }

  public JSONArray getJSONArray(String key) {
    JsonNode child = node.get(key);
    if (child == null || child.isNull()) {
      return null;
    }
    return new JSONArray((ArrayNode) child);
  }

  public JSONObject put(String key, String value) {
    if (value == null) {
      node.remove(key);
    } else {
      node.put(key, value);
    }
    return this;
  }

  public JSONObject put(String key, JSONObject value) {
    if (value == null) {
      node.remove(key);
    } else {
      node.set(key, value.unwrap());
    }
    return this;
  }

  public JSONObject put(String key, JSONArray value) {
    if (value == null) {
      node.remove(key);
    } else {
      node.set(key, value.unwrap());
    }
    return this;
  }

  public JSONObject put(String key, Object value) {
    if (value == null) {
      node.remove(key);
      return this;
    }
    if (value instanceof JSONObject) {
      return put(key, (JSONObject) value);
    }
    if (value instanceof JSONArray) {
      return put(key, (JSONArray) value);
    }
    if (value instanceof JsonNode) {
      node.set(key, (JsonNode) value);
      return this;
    }
    node.set(key, JSON.MAPPER.valueToTree(value));
    return this;
  }

  public void remove(String key) {
    node.remove(key);
  }

  @JsonValue
  public ObjectNode unwrap() {
    return node;
  }

  @Override
  public String toString() {
    try {
      return JSON.MAPPER.writeValueAsString(node);
    } catch (Exception e) {
      throw new JSONException("Serialization failed: " + e.getMessage(), e);
    }
  }

  public String toJSONString() {
    return toString();
  }
}
