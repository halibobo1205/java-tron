package org.tron.json;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Drop-in replacement for {@code com.alibaba.fastjson.JSONArray}.
 */
public class JSONArray {

  private final ArrayNode node;

  public JSONArray(ArrayNode node) {
    this.node = node;
  }

  public JSONArray() {
    this.node = JSON.MAPPER.createArrayNode();
  }

  public int size() {
    return node.size();
  }

  private void rangeCheck(int index) {
    if (index < 0 || index >= node.size()) {
      throw new IndexOutOfBoundsException(
          "Index: " + index + ", Size: " + node.size());
    }
  }

  public JSONObject getJSONObject(int index) {
    rangeCheck(index);
    JsonNode child = node.get(index);
    if (child.isNull()) {
      return null;
    }
    return new JSONObject((ObjectNode) child);
  }

  public JSONArray add(JSONObject value) {
    node.add(value == null ? node.nullNode() : value.unwrap());
    return this;
  }

  @JsonValue
  public ArrayNode unwrap() {
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
