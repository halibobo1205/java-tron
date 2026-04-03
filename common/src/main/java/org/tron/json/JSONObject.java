package org.tron.json;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
      try {
        return JSON.MAPPER.writeValueAsString(child);
      } catch (Exception e) {
        throw new JSONException("Serialization failed: " + e.getMessage(), e);
      }
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
    if (child.isBoolean()) {
      return child.booleanValue() ? 1 : 0;
    }
    if (child.isTextual()) {
      String text = child.asText().trim();
      if (text.isEmpty() || "null".equalsIgnoreCase(text)) {
        return null;
      }
      try {
        return Integer.parseInt(text);
      } catch (NumberFormatException e) {
        throw new JSONException("Cannot cast '" + text + "' to Integer", e);
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
    if (child.isBoolean()) {
      return child.booleanValue() ? 1L : 0L;
    }
    if (child.isTextual()) {
      String text = child.asText().trim();
      if (text.isEmpty() || "null".equalsIgnoreCase(text)) {
        return null;
      }
      try {
        return Long.parseLong(text);
      } catch (NumberFormatException e) {
        throw new JSONException("Cannot cast '" + text + "' to Long", e);
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
    if (child.isBoolean()) {
      return child.booleanValue() ? 1L : 0L;
    }
    if (child.isTextual()) {
      String text = child.asText().trim();
      if (text.isEmpty() || "null".equalsIgnoreCase(text)) {
        return 0L;
      }
      try {
        return Long.parseLong(text);
      } catch (NumberFormatException e) {
        throw new JSONException("Cannot cast '" + text + "' to long", e);
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
    if (child.isBoolean()) {
      return child.booleanValue() ? 1 : 0;
    }
    if (child.isTextual()) {
      String text = child.asText().trim();
      if (text.isEmpty() || "null".equalsIgnoreCase(text)) {
        return 0;
      }
      try {
        return Integer.parseInt(text);
      } catch (NumberFormatException e) {
        throw new JSONException("Cannot cast '" + text + "' to int", e);
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
    if (child.isBoolean()) {
      return child.booleanValue() ? 1.0 : 0.0;
    }
    if (child.isTextual()) {
      String text = child.asText().trim();
      if (text.isEmpty() || "null".equalsIgnoreCase(text)) {
        return null;
      }
      try {
        return Double.parseDouble(text);
      } catch (NumberFormatException e) {
        throw new JSONException("Cannot cast '" + text + "' to Double", e);
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
    if (child.isBoolean()) {
      return child.booleanValue() ? BigDecimal.ONE : BigDecimal.ZERO;
    }
    if (child.isTextual()) {
      String text = child.asText().trim();
      if (text.isEmpty() || "null".equalsIgnoreCase(text)) {
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
    if (child.isObject()) {
      return new JSONObject((ObjectNode) child);
    }
    // Fastjson auto-parses stringified JSON objects
    if (child.isTextual()) {
      return JSON.parseObject(child.asText());
    }
    throw new JSONException("Field '" + key + "' is not an object");
  }

  public JSONArray getJSONArray(String key) {
    JsonNode child = node.get(key);
    if (child == null || child.isNull()) {
      return null;
    }
    if (child.isArray()) {
      return new JSONArray((ArrayNode) child);
    }
    // Fastjson auto-parses stringified JSON arrays
    if (child.isTextual()) {
      return JSON.parseArray(child.asText());
    }
    throw new JSONException("Field '" + key + "' is not an array");
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
      if (clazz == JSONObject.class) {
        if (!child.isObject()) {
          throw new JSONException(
              "Field '" + key + "' is not an object, cannot convert to JSONObject");
        }
        return clazz.cast(new JSONObject((ObjectNode) child));
      }
      if (clazz == JSONArray.class) {
        if (!child.isArray()) {
          throw new JSONException(
              "Field '" + key + "' is not an array, cannot convert to JSONArray");
        }
        return clazz.cast(new JSONArray((ArrayNode) child));
      }
      return JSON.MAPPER.treeToValue(child, clazz);
    } catch (JSONException e) {
      throw e;
    } catch (Exception e) {
      throw new JSONException(
          "Failed to convert field '" + key + "' to " + clazz.getSimpleName(), e);
    }
  }

  // -------------------------------------------------------------------------
  // Mutation helpers (used minimally in the codebase)
  // -------------------------------------------------------------------------

  public JSONObject put(String key, String value) {
    if (value == null) {
      node.remove(key);
    } else {
      node.put(key, value);
    }
    return this;
  }

  public JSONObject put(String key, Boolean value) {
    if (value == null) {
      node.remove(key);
    } else {
      node.put(key, value);
    }
    return this;
  }

  public JSONObject put(String key, Integer value) {
    if (value == null) {
      node.remove(key);
    } else {
      node.put(key, value);
    }
    return this;
  }

  public JSONObject put(String key, Long value) {
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

  public JSONObject put(String key, List<?> value) {
    if (value == null) {
      node.remove(key);
      return this;
    }
    ArrayNode arr = JSON.MAPPER.createArrayNode();
    for (Object v : value) {
      if (v == null) {
        arr.addNull();
      } else if (v instanceof JSONObject) {
        arr.add(((JSONObject) v).unwrap());
      } else if (v instanceof JSONArray) {
        arr.add(((JSONArray) v).unwrap());
      } else if (v instanceof JsonNode) {
        arr.add((JsonNode) v);
      } else {
        arr.add(JSON.MAPPER.valueToTree(v));
      }
    }
    node.set(key, arr);
    return this;
  }

  public Object remove(String key) {
    JsonNode removed = node.remove(key);
    return convertNode(removed);
  }

  public boolean isEmpty() {
    return node.isEmpty();
  }

  public int size() {
    return node.size();
  }

  public Set<String> keySet() {
    Set<String> keys = new LinkedHashSet<>();
    node.fieldNames().forEachRemaining(keys::add);
    return keys;
  }

  // -------------------------------------------------------------------------
  // Conversion
  // -------------------------------------------------------------------------

  public Map<String, Object> toMap() {
    try {
      Map<String, Object> map = new LinkedHashMap<>();
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
