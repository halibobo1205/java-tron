package org.tron.json;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Drop-in replacement for {@code com.alibaba.fastjson.JSONArray}.
 * Swap the import line; no other source changes required for basic usages.
 */
public class JSONArray implements Iterable<Object> {

  private final ArrayNode node;

  public JSONArray(ArrayNode node) {
    this.node = node;
  }

  public JSONArray() {
    this.node = JSON.MAPPER.createArrayNode();
  }

  /** Static factory — mirrors {@code JSONArray.parseArray(String)}. */
  public static JSONArray parseArray(String text) {
    if (text == null || text.trim().isEmpty()) {
      return null;
    }
    try {
      JsonNode node = JSON.MAPPER.readTree(text);
      if (node == null || node.isNull()) {
        return null;
      }
      if (!node.isArray()) {
        throw new JSONException("Expected JSON array but got: " + node.getNodeType());
      }
      return new JSONArray((ArrayNode) node);
    } catch (JSONException e) {
      throw e;
    } catch (Exception e) {
      throw new JSONException("Failed to parse JSON array: " + e.getMessage(), e);
    }
  }

  public int size() {
    return node.size();
  }

  public boolean isEmpty() {
    return node.isEmpty();
  }

  private void rangeCheck(int index) {
    if (index < 0 || index >= node.size()) {
      throw new IndexOutOfBoundsException(
          "Index: " + index + ", Size: " + node.size());
    }
  }

  public Object get(int index) {
    rangeCheck(index);
    return JSONObject.convertNode(node.get(index));
  }

  public JSONObject getJSONObject(int index) {
    rangeCheck(index);
    JsonNode child = node.get(index);
    if (child.isNull()) {
      return null;
    }
    if (child.isObject()) {
      return new JSONObject((ObjectNode) child);
    }
    // Fastjson auto-parses stringified JSON objects
    if (child.isTextual()) {
      return JSON.parseObject(child.asText());
    }
    throw new JSONException("Element at index " + index + " is not an object");
  }

  public JSONArray getJSONArray(int index) {
    rangeCheck(index);
    JsonNode child = node.get(index);
    if (child.isNull()) {
      return null;
    }
    if (child.isArray()) {
      return new JSONArray((ArrayNode) child);
    }
    // Fastjson auto-parses stringified JSON arrays
    if (child.isTextual()) {
      return JSON.parseArray(child.asText());
    }
    throw new JSONException("Element at index " + index + " is not an array");
  }

  public String getString(int index) {
    rangeCheck(index);
    JsonNode child = node.get(index);
    if (child.isNull()) {
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

  public Boolean getBoolean(int index) {
    rangeCheck(index);
    JsonNode child = node.get(index);
    if (child.isNull()) {
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

  public <T> List<T> toJavaList(Class<T> clazz) {
    try {
      List<T> result = new ArrayList<>();
      for (JsonNode element : node) {
        if (element == null || element.isNull()) {
          result.add(null);
          continue;
        }
        if (clazz == JSONObject.class) {
          if (!element.isObject()) {
            throw new JSONException(
                "Element is not an object, cannot convert to JSONObject");
          }
          result.add(clazz.cast(new JSONObject((ObjectNode) element)));
        } else if (clazz == JSONArray.class) {
          if (!element.isArray()) {
            throw new JSONException(
                "Element is not an array, cannot convert to JSONArray");
          }
          result.add(clazz.cast(new JSONArray((ArrayNode) element)));
        } else {
          result.add(JSON.MAPPER.treeToValue(element, clazz));
        }
      }
      return result;
    } catch (JSONException e) {
      throw e;
    } catch (Exception e) {
      throw new JSONException(
          "Failed to convert JSONArray to List<" + clazz.getSimpleName() + ">", e);
    }
  }

  // -------------------------------------------------------------------------
  // Mutation helpers
  // -------------------------------------------------------------------------

  public JSONArray add(JSONObject value) {
    node.add(value == null ? node.nullNode() : value.unwrap());
    return this;
  }

  public JSONArray add(JSONArray value) {
    node.add(value == null ? node.nullNode() : value.unwrap());
    return this;
  }

  public JSONArray add(String value) {
    node.add(value);
    return this;
  }

  public JSONArray add(Object value) {
    if (value == null) {
      node.add(node.nullNode());
      return this;
    }
    if (value instanceof JSONObject) {
      return add((JSONObject) value);
    }
    if (value instanceof JSONArray) {
      return add((JSONArray) value);
    }
    if (value instanceof JsonNode) {
      node.add((JsonNode) value);
      return this;
    }
    node.add(JSON.MAPPER.valueToTree(value));
    return this;
  }

  /** Returns the underlying Jackson {@link ArrayNode}. */
  @JsonValue
  public ArrayNode unwrap() {
    return node;
  }

  @Override
  public Iterator<Object> iterator() {
    List<Object> list = new ArrayList<>();
    node.forEach(child -> list.add(JSONObject.convertNode(child)));
    return list.iterator();
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
