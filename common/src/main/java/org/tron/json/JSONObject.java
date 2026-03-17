package org.tron.json;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Drop-in replacement for {@code com.alibaba.fastjson.JSONObject}.
 * Swap the import line; no other source changes required for basic usages.
 */
public class JSONObject {

  private final ObjectNode node;

  public JSONObject(ObjectNode node) {
    this.node = node;
  }

  public JSONObject() {
    this.node = JSON.MAPPER.createObjectNode();
  }

  // -------------------------------------------------------------------------
  // Static factories
  // -------------------------------------------------------------------------

  /** {@code JSONObject.parseObject(String)} */
  public static JSONObject parseObject(String text) {
    return JSON.parseObject(text);
  }

  /** {@code JSONObject.parseArray(String)} */
  public static JSONArray parseArray(String text) {
    return JSONArray.parseArray(text);
  }

  // -------------------------------------------------------------------------
  // Field access
  // -------------------------------------------------------------------------

  public boolean containsKey(String key) {
    return node.has(key);
  }

  /**
   * Returns the string representation of the value for the given key.
   * For object/array nodes, returns the JSON text (matching Fastjson behaviour).
   * Returns {@code null} if the key is missing or the value is a JSON {@code null}.
   */
  public String getString(String key) {
    JsonNode child = node.get(key);
    if (child == null || child.isNull()) {
      return null;
    }
    if (child.isObject() || child.isArray()) {
      return child.toString();
    }
    return child.asText(null);
  }

  public Boolean getBoolean(String key) {
    JsonNode child = node.get(key);
    if (child == null || child.isNull()) {
      return null;
    }
    if (child.isBoolean()) {
      return child.booleanValue();
    }
    if (child.isNumber()) {
      return child.longValue() != 0;
    }
    if (child.isTextual()) {
      String text = child.asText();
      if ("true".equalsIgnoreCase(text) || "1".equals(text)) {
        return Boolean.TRUE;
      }
      if ("false".equalsIgnoreCase(text) || "0".equals(text)) {
        return Boolean.FALSE;
      }
      throw new JSONException("Cannot cast '" + text + "' to Boolean");
    }
    throw new JSONException("Cannot cast " + child.getNodeType() + " to Boolean");
  }

  public Integer getInteger(String key) {
    JsonNode child = node.get(key);
    if (child == null || child.isNull()) {
      return null;
    }
    if (child.isNumber()) {
      return child.asInt();
    }
    if (child.isTextual()) {
      try {
        return Integer.parseInt(child.asText());
      } catch (NumberFormatException e) {
        throw new JSONException("Cannot cast '" + child.asText() + "' to Integer", e);
      }
    }
    throw new JSONException("Cannot cast " + child.getNodeType() + " to Integer");
  }

  public Long getLong(String key) {
    JsonNode child = node.get(key);
    if (child == null || child.isNull()) {
      return null;
    }
    if (child.isNumber()) {
      return child.longValue();
    }
    if (child.isTextual()) {
      try {
        return Long.parseLong(child.asText());
      } catch (NumberFormatException e) {
        throw new JSONException("Cannot cast '" + child.asText() + "' to Long", e);
      }
    }
    throw new JSONException("Cannot cast " + child.getNodeType() + " to Long");
  }

  public long getLongValue(String key) {
    JsonNode child = node.get(key);
    if (child == null || child.isNull()) {
      return 0L;
    }
    if (child.isNumber()) {
      return child.longValue();
    }
    if (child.isTextual()) {
      try {
        return Long.parseLong(child.asText());
      } catch (NumberFormatException e) {
        throw new JSONException("Cannot cast '" + child.asText() + "' to long", e);
      }
    }
    throw new JSONException("Cannot cast " + child.getNodeType() + " to long");
  }

  public int getIntValue(String key) {
    JsonNode child = node.get(key);
    if (child == null || child.isNull()) {
      return 0;
    }
    if (child.isNumber()) {
      return child.intValue();
    }
    if (child.isTextual()) {
      try {
        return Integer.parseInt(child.asText());
      } catch (NumberFormatException e) {
        throw new JSONException("Cannot cast '" + child.asText() + "' to int", e);
      }
    }
    throw new JSONException("Cannot cast " + child.getNodeType() + " to int");
  }

  public Double getDouble(String key) {
    JsonNode child = node.get(key);
    if (child == null || child.isNull()) {
      return null;
    }
    if (child.isNumber()) {
      return child.doubleValue();
    }
    if (child.isTextual()) {
      try {
        return Double.parseDouble(child.asText());
      } catch (NumberFormatException e) {
        throw new JSONException("Cannot cast '" + child.asText() + "' to Double", e);
      }
    }
    throw new JSONException("Cannot cast " + child.getNodeType() + " to Double");
  }

  public BigDecimal getBigDecimal(String key) {
    JsonNode child = node.get(key);
    if (child == null || child.isNull()) {
      return null;
    }
    if (child.isNumber()) {
      return child.decimalValue();
    }
    if (child.isTextual()) {
      String text = child.asText().trim();
      if (text.isEmpty()) {
        return null;
      }
      try {
        return new BigDecimal(text);
      } catch (NumberFormatException e) {
        throw new JSONException("Cannot cast '" + text + "' to BigDecimal", e);
      }
    }
    throw new JSONException("Cannot cast " + child.getNodeType() + " to BigDecimal");
  }

  /**
   * Returns the raw value for the given key.
   * Mirrors {@code Map.get(String)} from fastjson's JSONObject.
   */
  public Object get(String key) {
    return convertNode(node.get(key));
  }

  /**
   * Converts a Jackson {@link JsonNode} to the corresponding Java type,
   * matching Fastjson's return types from generic accessors.
   */
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
    if (child.isTextual()) {
      return child.asText();
    }
    if (child.isInt()) {
      return child.intValue();
    }
    if (child.isLong()) {
      return child.longValue();
    }
    if (child.isBigInteger()) {
      return child.bigIntegerValue();
    }
    if (child.isBigDecimal()) {
      return child.decimalValue();
    }
    if (child.isDouble() || child.isFloat()) {
      return child.doubleValue();
    }
    if (child.isBoolean()) {
      return child.booleanValue();
    }
    return child.asText();
  }

  public JSONObject getJSONObject(String key) {
    JsonNode child = node.get(key);
    if (child == null || child.isNull()) {
      return null;
    }
    if (!child.isObject()) {
      throw new JSONException("Field '" + key + "' is not an object");
    }
    return new JSONObject((ObjectNode) child);
  }

  public JSONArray getJSONArray(String key) {
    JsonNode child = node.get(key);
    if (child == null || child.isNull()) {
      return null;
    }
    if (!child.isArray()) {
      throw new JSONException("Field '" + key + "' is not an array");
    }
    return new JSONArray((ArrayNode) child);
  }

  /**
   * Deserializes the field value into the given Java type.
   * Mirrors {@code JSONObject.getObject(String, Class)}.
   */
  public <T> T getObject(String key, Class<T> clazz) {
    JsonNode child = node.get(key);
    if (child == null || child.isNull()) {
      return null;
    }
    try {
      return JSON.MAPPER.treeToValue(child, clazz);
    } catch (Exception e) {
      throw new JSONException("Failed to convert field '" + key + "' to " + clazz.getSimpleName(), e);
    }
  }

  // -------------------------------------------------------------------------
  // Mutation helpers (used minimally in the codebase)
  // -------------------------------------------------------------------------

  public JSONObject put(String key, String value) {
    node.put(key, value);
    return this;
  }

  public JSONObject put(String key, Boolean value) {
    node.put(key, value);
    return this;
  }

  public JSONObject put(String key, Integer value) {
    node.put(key, value);
    return this;
  }

  public JSONObject put(String key, Long value) {
    node.put(key, value);
    return this;
  }

  public JSONObject put(String key, JSONObject value) {
    node.set(key, value == null ? node.nullNode() : value.unwrap());
    return this;
  }

  public JSONObject put(String key, JSONArray value) {
    node.set(key, value == null ? node.nullNode() : value.unwrap());
    return this;
  }

  public JSONObject put(String key, Object value) {
    if (value instanceof JSONObject) {
      return put(key, (JSONObject) value);
    }
    if (value instanceof JSONArray) {
      return put(key, (JSONArray) value);
    }
    node.set(key, JSON.MAPPER.valueToTree(value));
    return this;
  }

  public JSONObject put(String key, List<?> value) {
    if (value == null) {
      node.set(key, node.nullNode());
      return this;
    }
    ArrayNode arr = JSON.MAPPER.createArrayNode();
    value.forEach(v -> arr.add(JSON.MAPPER.valueToTree(v)));
    node.set(key, arr);
    return this;
  }

  public Object remove(String key) {
    JsonNode removed = node.remove(key);
    return convertNode(removed);
  }

  public int size() {
    return node.size();
  }

  public Set<String> keySet() {
    Set<String> keys = new HashSet<>();
    node.fieldNames().forEachRemaining(keys::add);
    return keys;
  }

  // -------------------------------------------------------------------------
  // Conversion
  // -------------------------------------------------------------------------

  public Map<String, Object> toMap() {
    try {
      Map<String, Object> map = new HashMap<>();
      Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> entry = fields.next();
        map.put(entry.getKey(), JSON.MAPPER.treeToValue(entry.getValue(), Object.class));
      }
      return map;
    } catch (Exception e) {
      throw new JSONException("Failed to convert JSONObject to Map", e);
    }
  }

  /** Returns the underlying Jackson {@link ObjectNode}. */
  @JsonValue
  public ObjectNode unwrap() {
    return node;
  }

  @Override
  public String toString() {
    return node.toString();
  }

  public String toJSONString() {
    return node.toString();
  }
}
