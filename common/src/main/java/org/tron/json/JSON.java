package org.tron.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Drop-in replacement for {@code com.alibaba.fastjson.JSON}.
 * Swap the import line; no other source changes required for basic usages.
 *
 * <p>All static methods delegate to a shared, thread-safe Jackson {@link ObjectMapper} that is
 * configured to match the lenient parsing behavior historically provided by Fastjson:
 * <ul>
 *   <li>Unquoted field names and single-quoted strings are accepted.</li>
 *   <li>Unknown properties are ignored (Fastjson default).</li>
 *   <li>Floating-point numbers are mapped to {@link java.math.BigDecimal} for precision.</li>
 *   <li>Case-insensitive property matching is enabled.</li>
 * </ul>
 */
public final class JSON {

  /**
   * Shared, fully-configured Jackson mapper. Exposed for callers that hold a mapper reference.
   *
   * <p>Each setting mirrors a Fastjson 1.x default.
   */
  public static final ObjectMapper MAPPER = JsonMapper.builder()
      // Fastjson Feature.AllowUnQuotedFieldNames (default ON)
      .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
      // Fastjson Feature.AllowSingleQuotes (default ON)
      .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
      // Fastjson tolerates trailing commas (e.g. {"a":1,}) by default
      .configure(JsonParser.Feature.ALLOW_TRAILING_COMMA, true)
      // Fastjson Feature.UseBigDecimal (default ON)
      // https://github.com/alibaba/fastjson/wiki/deserialize_disable_bigdecimal_cn
      .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true)
      // Fastjson Feature.IgnoreNotMatch (default ON) — unknown fields silently ignored
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
      // Fastjson serializes empty beans as "{}" without error
      .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
      // Fastjson omits null-valued fields by default (WriteMapNullValue is OFF by default)
      // https://github.com/alibaba/fastjson/wiki/WriteNull_cn
      .serializationInclusion(JsonInclude.Include.NON_NULL)
      // Fastjson uses WriteDateUseDateFormat (string) not timestamps by default
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
      // Fastjson smart-match: field names are matched ignoring case/underscores by default
      // (DisableFieldSmartMatch is OFF by default → smart match ON)
      .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true)
      .build();

  private JSON() {
  }

  // -------------------------------------------------------------------------
  // parseObject
  // -------------------------------------------------------------------------

  /**
   * Parses a JSON object string and returns a {@link JSONObject}.
   * Mirrors {@code JSON.parseObject(String)} and {@code JSONObject.parseObject(String)}.
   */
  public static JSONObject parseObject(String text) {
    if (text == null || text.trim().isEmpty()) {
      return null;
    }
    try {
      JsonNode node = MAPPER.readTree(text);
      if (node == null || node.isNull()) {
        return null;
      }
      if (!node.isObject()) {
        throw new JSONException("Expected JSON object but got: " + node.getNodeType());
      }
      return new JSONObject((ObjectNode) node);
    } catch (JSONException e) {
      throw e;
    } catch (Exception e) {
      throw new JSONException("Failed to parse JSON object: " + e.getMessage(), e);
    }
  }

  /**
   * Parses a JSON string and deserializes it into the given Java type.
   * Mirrors {@code JSON.parseObject(String, Class)}.
   */
  public static <T> T parseObject(String text, Class<T> clazz) {
    if (text == null || text.trim().isEmpty()) {
      return null;
    }
    if (clazz == JSONObject.class) {
      return clazz.cast(parseObject(text));
    }
    if (clazz == JSONArray.class) {
      return clazz.cast(parseArray(text));
    }
    try {
      return MAPPER.readValue(text, clazz);
    } catch (Exception e) {
      throw new JSONException("Failed to parse JSON object: " + e.getMessage(), e);
    }
  }

  // -------------------------------------------------------------------------
  // parse  (validate / generic parse — callers typically ignore the return value)
  // -------------------------------------------------------------------------

  /**
   * Parses any valid JSON value. Throws {@link JSONException} on invalid input
   * so callers can detect malformed JSON (e.g. via try/catch), mirroring
   * Fastjson's observable behaviour in this codebase.
   * Mirrors {@code JSON.parse(String)}.
   */
  public static JsonNode parse(String text) {
    if (text == null || text.trim().isEmpty()) {
      return null;
    }
    try {
      return MAPPER.readTree(text);
    } catch (Exception e) {
      throw new JSONException("Failed to parse JSON: " + e.getMessage(), e);
    }
  }

  /**
   * Parses a JSON array string and returns a {@link JSONArray}.
   * Mirrors {@code JSON.parseArray(String)} and {@code JSONArray.parseArray(String)}.
   */
  public static JSONArray parseArray(String text) {
    return JSONArray.parseArray(text);
  }

  // -------------------------------------------------------------------------
  // toJSONString
  // -------------------------------------------------------------------------

  /**
   * Serializes an object to a compact JSON string.
   * Mirrors {@code JSON.toJSONString(Object)}.
   */
  public static String toJSONString(Object obj) {
    if (obj == null) {
      return "null";
    }
    try {
      // Unwrap our own wrapper types so the inner Jackson node is serialized
      if (obj instanceof JSONObject) {
        return MAPPER.writeValueAsString(((JSONObject) obj).unwrap());
      }
      if (obj instanceof JSONArray) {
        return MAPPER.writeValueAsString(((JSONArray) obj).unwrap());
      }
      return MAPPER.writeValueAsString(obj);
    } catch (Exception e) {
      throw new JSONException("Failed to serialise object: " + e.getMessage(), e);
    }
  }

  /**
   * Serializes an object to a JSON string, optionally pretty-printed.
   * Mirrors {@code JSON.toJSONString(Object, boolean)}.
   */
  public static String toJSONString(Object obj, boolean prettyFormat) {
    if (!prettyFormat) {
      return toJSONString(obj);
    }
    if (obj == null) {
      return "null";
    }
    try {
      if (obj instanceof JSONObject) {
        return MAPPER.writerWithDefaultPrettyPrinter()
            .writeValueAsString(((JSONObject) obj).unwrap());
      }
      if (obj instanceof JSONArray) {
        return MAPPER.writerWithDefaultPrettyPrinter()
            .writeValueAsString(((JSONArray) obj).unwrap());
      }
      return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
    } catch (Exception e) {
      throw new JSONException("Failed to serialise object: " + e.getMessage(), e);
    }
  }
}
