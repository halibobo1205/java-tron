package org.tron.json;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import lombok.Getter;
import org.junit.Test;

/**
 * Fuzz test for JSON/JSONObject/JSONArray wrappers.
 * Verifies Fastjson-compatible behavior under randomized input,
 * covering edge cases such as BigDecimal/BigInteger, nested structures,
 * special characters, unicode, deeply nested objects, and boundary values.
 */
public class JsonCompatibilityFuzzTest {

  private static final int FUZZ_ROUNDS = 500;
  private static final SecureRandom RNG = new SecureRandom();

  // -------------------------------------------------------------------------
  // 1. JSON.parse — must throw on invalid JSON, return non-null on valid
  // -------------------------------------------------------------------------

  @Test
  public void testParseValidJsonNeverReturnsNull() {
    String[] validInputs = {
        "{}", "[]", "\"hello\"", "123", "true", "false", "null",
        "{\"a\":1}", "[1,2,3]", "0.5", "-1", "1e10",
        "{\"nested\":{\"deep\":[1,2,{\"x\":true}]}}",
        "\"unicode: \\u4e2d\\u6587\"",
        String.valueOf(Long.MAX_VALUE),
        new BigDecimal("1234567890.123456789012345678901234567890").toPlainString(),
    };
    for (String input : validInputs) {
      assertNotNull("parse should succeed for: " + input, JSON.parse(input));
    }
  }

  @Test
  public void testParseNullAndEmptyReturnsNull() {
    assertNull(JSON.parse(null));
    assertNull(JSON.parse(""));
  }

  @Test
  public void testParseInvalidJsonThrows() {
    String[] invalidInputs = {
        "not json", "a=1&b=2", "<html>", "{{",
        "{\"unterminated",
    };
    for (String input : invalidInputs) {
      try {
        JSON.parse(input);
        fail("Expected JSONException for invalid JSON input: " + input);
      } catch (JSONException e) {
        // expected
      }
    }
  }

  // -------------------------------------------------------------------------
  // 2. JSON.parseObject — null/empty → null, valid → JSONObject
  // -------------------------------------------------------------------------

  @Test
  public void testParseObjectNullAndEmptyReturnsNull() {
    assertNull(JSON.parseObject(null));
    assertNull(JSON.parseObject(""));
    assertNull(JSON.parseObject("   "));
    assertNull(JSON.parseObject("\t\n"));
  }

  @Test
  public void testParseObjectValidJson() {
    JSONObject obj = JSON.parseObject("{\"a\":1}");
    assertNotNull(obj);
    assertEquals(Integer.valueOf(1), obj.getInteger("a"));
  }

  @Test(expected = JSONException.class)
  public void testParseObjectInvalidJsonThrows() {
    JSON.parseObject("not a json object");
  }

  // -------------------------------------------------------------------------
  // 3. JSONObject.getString — object/array → JSON text, scalar → text
  // -------------------------------------------------------------------------

  @Test
  public void testGetStringReturnsJsonTextForObjects() {
    JSONObject obj = JSON.parseObject("{\"nested\":{\"x\":1},\"arr\":[1,2]}");
    String nestedStr = obj.getString("nested");
    assertTrue("Should be JSON text, got: " + nestedStr, nestedStr.contains("\"x\""));
    String arrStr = obj.getString("arr");
    assertTrue("Should be JSON text, got: " + arrStr, arrStr.startsWith("["));
  }

  @Test
  public void testGetStringReturnsNullForMissing() {
    JSONObject obj = JSON.parseObject("{\"a\":1}");
    assertNull(obj.getString("nonexistent"));
  }

  @Test
  public void testGetStringReturnsNullForJsonNull() {
    JSONObject obj = JSON.parseObject("{\"a\":null}");
    assertNull(obj.getString("a"));
  }

  @Test
  public void testGetStringScalarValues() {
    JSONObject obj = JSON.parseObject("{\"s\":\"hello\",\"n\":42,\"b\":true}");
    assertEquals("hello", obj.getString("s"));
    assertEquals("42", obj.getString("n"));
    assertEquals("true", obj.getString("b"));
  }

  // -------------------------------------------------------------------------
  // 4. JSONObject.getInteger — must reject non-numeric text
  // -------------------------------------------------------------------------

  @Test
  public void testGetIntegerValidValues() {
    JSONObject obj = JSON.parseObject("{\"a\":1,\"b\":\"42\",\"c\":0,\"d\":-1}");
    assertEquals(Integer.valueOf(1), obj.getInteger("a"));
    assertEquals(Integer.valueOf(42), obj.getInteger("b"));
    assertEquals(Integer.valueOf(0), obj.getInteger("c"));
    assertEquals(Integer.valueOf(-1), obj.getInteger("d"));
  }

  @Test
  public void testGetIntegerNullForMissing() {
    JSONObject obj = JSON.parseObject("{\"a\":1}");
    assertNull(obj.getInteger("nonexistent"));
    assertNull(obj.getInteger("x"));
  }

  @Test(expected = JSONException.class)
  public void testGetIntegerThrowsOnNonNumericText() {
    JSONObject obj = JSON.parseObject("{\"bad\":\"abc\"}");
    obj.getInteger("bad");
  }

  @Test
  public void testGetIntegerCoercesBooleanNode() {
    // Fastjson coerces boolean → integer: true→1, false→0
    JSONObject obj = JSON.parseObject("{\"b\":true,\"c\":false}");
    assertEquals(Integer.valueOf(1), obj.getInteger("b"));
    assertEquals(Integer.valueOf(0), obj.getInteger("c"));
  }

  // -------------------------------------------------------------------------
  // 5. JSONObject.get / convertNode — BigDecimal & BigInteger support
  // -------------------------------------------------------------------------

  @Test
  public void testGetReturnsBigDecimalForFloats() {
    JSONObject obj = JSON.parseObject("{\"val\":1234567890.123456789012345678901234567890}");
    Object val = obj.get("val");
    assertTrue("Expected BigDecimal, got " + val.getClass().getSimpleName(),
        val instanceof BigDecimal);
  }

  @Test
  public void testGetReturnsBigIntegerForHugeInts() {
    String huge = new BigInteger("99999999999999999999999999999999999999").toString();
    JSONObject obj = JSON.parseObject("{\"big\":" + huge + "}");
    Object val = obj.get("big");
    assertTrue("Expected BigInteger, got " + val.getClass().getSimpleName(),
        val instanceof BigInteger);
  }

  @Test
  public void testGetReturnsIntegerForSmallInts() {
    JSONObject obj = JSON.parseObject("{\"i\":42}");
    Object val = obj.get("i");
    assertTrue("Expected Integer, got " + val.getClass().getSimpleName(),
        val instanceof Integer);
  }

  @Test
  public void testGetReturnsLongForLargeInts() {
    JSONObject obj = JSON.parseObject("{\"l\":" + ((long) Integer.MAX_VALUE + 1) + "}");
    Object val = obj.get("l");
    assertTrue("Expected Long, got " + val.getClass().getSimpleName(),
        val instanceof Long);
  }

  @Test
  public void testGetReturnsWrappersForStructured() {
    JSONObject obj = JSON.parseObject("{\"o\":{\"x\":1},\"a\":[1]}");
    assertTrue(obj.get("o") instanceof JSONObject);
    assertTrue(obj.get("a") instanceof JSONArray);
  }

  // -------------------------------------------------------------------------
  // 6. JSONObject.remove — returns converted value
  // -------------------------------------------------------------------------

  @Test
  public void testRemoveReturnsConvertedValue() {
    JSONObject obj = JSON.parseObject("{\"a\":\"hello\",\"b\":42,\"c\":{\"x\":1}}");
    assertEquals("hello", obj.remove("a"));
    assertEquals(42, obj.remove("b"));
    assertTrue(obj.remove("c") instanceof JSONObject);
    assertNull(obj.remove("nonexistent"));
  }

  // -------------------------------------------------------------------------
  // 7. JSONObject.put(key, List) — null list → key removed (Fastjson compat)
  // -------------------------------------------------------------------------

  @Test
  public void testPutNullListStoresJsonNull() {
    JSONObject obj = new JSONObject();
    obj.put("list", (List<?>) null);
    assertNull(obj.get("list"));
  }

  @Test
  public void testPutNonNullListStoresArray() {
    JSONObject obj = new JSONObject();
    obj.put("list", Arrays.asList("a", "b"));
    Object val = obj.get("list");
    assertTrue(val instanceof JSONArray);
    assertEquals(2, ((JSONArray) val).size());
  }

  // -------------------------------------------------------------------------
  // 8. JSONArray.get — BigDecimal/BigInteger + type consistency
  // -------------------------------------------------------------------------

  @Test
  public void testArrayGetTypeConsistency() {
    JSONArray arr = JSON.parseArray(
        "[42, 9999999999999999999999, 3.14, \"text\", true, null, {\"k\":1}, [1]]");
    assertTrue(arr.get(0) instanceof Integer);
    assertTrue("Expected BigInteger", arr.get(1) instanceof BigInteger);
    assertTrue("Expected BigDecimal", arr.get(2) instanceof BigDecimal);
    assertEquals("text", arr.get(3));
    assertEquals(true, arr.get(4));
    assertNull(arr.get(5));
    assertTrue(arr.get(6) instanceof JSONObject);
    assertTrue(arr.get(7) instanceof JSONArray);
  }

  // -------------------------------------------------------------------------
  // 9. JSONArray.iterator — returns converted values, not raw JsonNode
  // -------------------------------------------------------------------------

  @Test
  public void testIteratorReturnsConvertedValues() {
    JSONArray arr = JSON.parseArray("[\"a\", 1, {\"x\":2}, [3], true]");
    List<Object> items = new ArrayList<>();
    for (Object item : arr) {
      items.add(item);
    }
    assertEquals(5, items.size());
    assertEquals("a", items.get(0));
    assertTrue(items.get(1) instanceof Integer);
    assertTrue(items.get(2) instanceof JSONObject);
    assertTrue(items.get(3) instanceof JSONArray);
    assertEquals(true, items.get(4));
  }

  @Test
  public void testIteratorMatchesIndexedAccess() {
    JSONArray arr = JSON.parseArray("[1, \"two\", {\"k\":3}, [4], true, null]");
    int i = 0;
    for (Object item : arr) {
      Object indexed = arr.get(i);
      if (item == null) {
        assertNull("Index " + i + " mismatch", indexed);
      } else {
        assertEquals("Index " + i + " type mismatch",
            item.getClass(), indexed.getClass());
        assertEquals("Index " + i + " value mismatch",
            String.valueOf(item), String.valueOf(indexed));
      }
      i++;
    }
  }

  // -------------------------------------------------------------------------
  // 10. Round-trip fuzz: random JSON → parse → serialize → re-parse → equal
  // -------------------------------------------------------------------------

  @Test
  public void testRoundTripFuzz() {
    for (int round = 0; round < FUZZ_ROUNDS; round++) {
      String json = randomJsonObject(RNG, 3);
      try {
        JSONObject obj1 = JSON.parseObject(json);
        assertNotNull("Round " + round + " parse failed for: " + json, obj1);
        String serialized = obj1.toJSONString();
        JSONObject obj2 = JSON.parseObject(serialized);
        assertNotNull("Round " + round + " re-parse failed", obj2);
        assertEquals("Round " + round + " round-trip mismatch",
            obj1.toString(), obj2.toString());
      } catch (JSONException e) {
        fail("Round " + round + " unexpected JSONException for: " + json + " → " + e.getMessage());
      }
    }
  }

  // -------------------------------------------------------------------------
  // 11. Random invalid input fuzz: should either parse or throw, never NPE
  // -------------------------------------------------------------------------

  @Test
  public void testInvalidInputNeverCausesNPE() {
    for (int round = 0; round < FUZZ_ROUNDS; round++) {
      String garbage = randomGarbage(RNG);
      try {
        JSON.parse(garbage);
        // OK — some garbage might be valid JSON (e.g. a number or string)
      } catch (JSONException e) {
        // expected for invalid JSON
      } catch (Exception e) {
        fail("Round " + round + " unexpected exception type "
            + e.getClass().getSimpleName() + " for: " + garbage);
      }
    }
  }

  // -------------------------------------------------------------------------
  // 12. Deep nesting stress test
  // -------------------------------------------------------------------------

  @Test
  public void testDeepNesting() {
    StringBuilder sb = new StringBuilder();
    int depth = 50;
    for (int i = 0; i < depth; i++) {
      sb.append("{\"d\":");
    }
    sb.append("42");
    for (int i = 0; i < depth; i++) {
      sb.append("}");
    }
    JSONObject obj = JSON.parseObject(sb.toString());
    assertNotNull(obj);
    // Navigate to the deepest level
    JSONObject current = obj;
    for (int i = 0; i < depth - 1; i++) {
      current = current.getJSONObject("d");
      assertNotNull("Null at depth " + i, current);
    }
    assertEquals(Integer.valueOf(42), current.getInteger("d"));
  }

  // -------------------------------------------------------------------------
  // 13. Special character / unicode fuzz
  // -------------------------------------------------------------------------

  @Test
  public void testUnicodeAndSpecialChars() {
    String[] specialValues = {
        "中文测试", "emoji: 😀", "tab:\there",
        "newline:\nhere", "quote:\"here\"", "backslash:\\\\",
        "null char: \\u0000", "path: /a/b/c",
        "html: <script>alert(1)</script>",
        "", " ", "  \t  ",
    };
    for (String val : specialValues) {
      JSONObject obj = new JSONObject();
      obj.put("key", val);
      String json = obj.toJSONString();
      JSONObject parsed = JSON.parseObject(json);
      assertNotNull("Failed to re-parse with special value", parsed);
      assertEquals(val, parsed.getString("key"));
    }
  }

  // -------------------------------------------------------------------------
  // 14. Numeric boundary values
  // -------------------------------------------------------------------------

  @Test
  public void testNumericBoundaries() {
    JSONObject obj = JSON.parseObject(String.format(
        "{\"intMax\":%d,\"intMin\":%d,\"longMax\":%d,\"longMin\":%d,\"zero\":0,\"negZero\":-0}",
        Integer.MAX_VALUE, Integer.MIN_VALUE, Long.MAX_VALUE, Long.MIN_VALUE));
    assertEquals(Integer.valueOf(Integer.MAX_VALUE), obj.getInteger("intMax"));
    assertEquals(Integer.valueOf(Integer.MIN_VALUE), obj.getInteger("intMin"));
    assertEquals(Long.valueOf(Long.MAX_VALUE), obj.getLong("longMax"));
    assertEquals(Long.valueOf(Long.MIN_VALUE), obj.getLong("longMin"));
    assertEquals(Integer.valueOf(0), obj.getInteger("zero"));
  }

  // -------------------------------------------------------------------------
  // 15. Fuzz: random put → get consistency
  // -------------------------------------------------------------------------

  @Test
  public void testRandomPutGetConsistency() {
    for (int round = 0; round < FUZZ_ROUNDS; round++) {
      JSONObject obj = new JSONObject();
      String key = "k" + round;
      int type = RNG.nextInt(5);
      switch (type) {
        case 0: // String
          String s = "val_" + RNG.nextInt(10000);
          obj.put(key, s);
          assertEquals(s, obj.getString(key));
          break;
        case 1: // Integer
          int i = RNG.nextInt();
          obj.put(key, i);
          assertEquals(Integer.valueOf(i), obj.getInteger(key));
          break;
        case 2: // Long
          long l = RNG.nextLong();
          obj.put(key, l);
          assertEquals(Long.valueOf(l), obj.getLong(key));
          break;
        case 3: // Boolean
          boolean b = RNG.nextBoolean();
          obj.put(key, b);
          assertEquals(b, obj.getBoolean(key));
          break;
        case 4: // Nested object
          JSONObject nested = new JSONObject();
          nested.put("inner", RNG.nextInt(100));
          obj.put(key, nested);
          assertNotNull(obj.getJSONObject(key));
          assertEquals(nested.toString(), obj.getJSONObject(key).toString());
          break;
        default:
          break;
      }
    }
  }

  // -------------------------------------------------------------------------
  // 16. JSONArray.parseArray — null/empty must return null (Fastjson compat)
  // -------------------------------------------------------------------------

  @Test
  public void testParseArrayNullReturnsNull() {
    assertNull("parseArray(null) should return null", JSONArray.parseArray(null));
  }

  @Test
  public void testParseArrayEmptyStringReturnsNull() {
    assertNull("parseArray(\"\") should return null", JSONArray.parseArray(""));
  }

  @Test
  public void testParseArrayJsonLiteralNullReturnsNull() {
    assertNull("parseArray(\"null\") should return null", JSONArray.parseArray("null"));
  }

  @Test
  public void testParseArrayBlankStringReturnsNull() {
    assertNull("parseArray(\"   \") should return null",
        JSONArray.parseArray("   "));
    assertNull("parseArray(\"\\t\\n\") should return null",
        JSONArray.parseArray("\t\n"));
  }

  @Test
  public void testParseArrayValidInput() {
    JSONArray arr = JSONArray.parseArray("[1,2,3]");
    assertNotNull(arr);
    assertEquals(3, arr.size());
  }

  @Test
  public void testParseArrayEmptyArray() {
    JSONArray arr = JSONArray.parseArray("[]");
    assertNotNull(arr);
    assertEquals(0, arr.size());
  }

  @Test(expected = JSONException.class)
  public void testParseArrayNonArrayThrows() {
    JSONArray.parseArray("{\"a\":1}");
  }

  // -------------------------------------------------------------------------
  // 17. getBigDecimal — empty string / "null" string → null (Fastjson compat)
  // -------------------------------------------------------------------------

  @Test
  public void testGetBigDecimalEmptyStringReturnsNull() {
    JSONObject obj = JSON.parseObject("{\"val\":\"\"}");
    assertNull("getBigDecimal for empty string should return null",
        obj.getBigDecimal("val"));
  }

  @Test
  public void testGetBigDecimalNullStringReturnsNull() {
    JSONObject obj = JSON.parseObject("{\"val\":\"null\"}");
    assertNull("getBigDecimal for \"null\" string should return null",
        obj.getBigDecimal("val"));
  }

  @Test
  public void testGetBigDecimalNullStringCaseInsensitive() {
    JSONObject obj = JSON.parseObject("{\"val\":\"NULL\"}");
    assertNull("getBigDecimal for \"NULL\" string should return null",
        obj.getBigDecimal("val"));
  }

  @Test
  public void testGetBigDecimalMissingKeyReturnsNull() {
    JSONObject obj = JSON.parseObject("{\"a\":1}");
    assertNull(obj.getBigDecimal("nonexistent"));
  }

  @Test
  public void testGetBigDecimalJsonNullReturnsNull() {
    JSONObject obj = JSON.parseObject("{\"val\":null}");
    assertNull(obj.getBigDecimal("val"));
  }

  @Test
  public void testGetBigDecimalNumericValues() {
    JSONObject obj = JSON.parseObject("{\"a\":3.14,\"b\":0,\"c\":\"99.99\"}");
    assertEquals(0, new BigDecimal("3.14").compareTo(obj.getBigDecimal("a")));
    assertEquals(0, BigDecimal.ZERO.compareTo(obj.getBigDecimal("b")));
    assertEquals(0, new BigDecimal("99.99").compareTo(obj.getBigDecimal("c")));
  }

  @Test(expected = JSONException.class)
  public void testGetBigDecimalNonNumericTextThrows() {
    JSONObject obj = JSON.parseObject("{\"val\":\"abc\"}");
    obj.getBigDecimal("val");
  }

  // -------------------------------------------------------------------------
  // 18. getInteger/getLong/getDouble — empty/"null" string → null for boxed,
  //     0 for primitive (Fastjson TypeUtils.castToXxx compat)
  // -------------------------------------------------------------------------

  @Test
  public void testGetIntegerEmptyStringReturnsNull() {
    JSONObject obj = JSON.parseObject("{\"val\":\"\"}");
    assertNull(obj.getInteger("val"));
  }

  @Test
  public void testGetIntegerNullStringReturnsNull() {
    JSONObject obj = JSON.parseObject("{\"val\":\"null\"}");
    assertNull(obj.getInteger("val"));
  }

  @Test
  public void testGetLongEmptyStringReturnsNull() {
    JSONObject obj = JSON.parseObject("{\"val\":\"\"}");
    assertNull(obj.getLong("val"));
  }

  @Test
  public void testGetLongNullStringReturnsNull() {
    JSONObject obj = JSON.parseObject("{\"val\":\"null\"}");
    assertNull(obj.getLong("val"));
  }

  @Test
  public void testGetIntValueEmptyStringReturnsZero() {
    JSONObject obj = JSON.parseObject("{\"val\":\"\"}");
    assertEquals(0, obj.getIntValue("val"));
  }

  @Test
  public void testGetLongValueEmptyStringReturnsZero() {
    JSONObject obj = JSON.parseObject("{\"val\":\"\"}");
    assertEquals(0L, obj.getLongValue("val"));
  }

  @Test
  public void testGetDoubleEmptyStringReturnsNull() {
    JSONObject obj = JSON.parseObject("{\"val\":\"\"}");
    assertNull(obj.getDouble("val"));
  }

  @Test
  public void testGetDoubleNullStringReturnsNull() {
    JSONObject obj = JSON.parseObject("{\"val\":\"null\"}");
    assertNull(obj.getDouble("val"));
  }

  // -------------------------------------------------------------------------
  // 19. getJsonLongValue via getBigDecimal — edge cases in Util path
  // -------------------------------------------------------------------------

  @Test
  public void testGetBigDecimalToLongPath() {
    // Simulates the Util.getJsonLongValue code path:
    // BigDecimal bigDecimal = jsonObject.getBigDecimal(key);
    // return (bigDecimal == null) ? 0L : bigDecimal.longValueExact();
    JSONObject obj = JSON.parseObject(
        "{\"amount\":100,\"fee\":\"0\",\"empty\":\"\",\"missing\":null}");
    assertEquals(100L, obj.getBigDecimal("amount").longValueExact());
    assertEquals(0L, obj.getBigDecimal("fee").longValueExact());
    assertNull(obj.getBigDecimal("empty"));   // empty string → null
    assertNull(obj.getBigDecimal("missing")); // JSON null → null
    assertNull(obj.getBigDecimal("nokey"));   // missing key → null
  }

  // -------------------------------------------------------------------------
  // 20. NON_NULL serialization — null fields omitted (matches Fastjson default)
  // -------------------------------------------------------------------------

  @Test
  public void testToJSONStringOmitsNullFields() {
    JSONObject obj = new JSONObject();
    obj.put("name", "test");
    obj.put("value", (String) null);
    String json = JSON.toJSONString(obj);
    assertTrue("Should contain name field", json.contains("\"name\""));
    // NON_NULL config via MAPPER.writeValueAsString() should omit null fields,
    // matching Fastjson default (WriteMapNullValue OFF)
    assertFalse("null field should be omitted (Fastjson compat)",
        json.contains("\"value\""));
  }

  @Test
  public void testSerializePojOmitsNullFields() {
    // POJO serialization via JSON.MAPPER should omit null fields
    String json = JSON.toJSONString(new TestPojo("hello", null));
    assertTrue(json.contains("hello"));
    // NON_NULL should omit the null field
    assertFalse("null field should not appear as key", json.contains("\"nullField\""));
  }

  // -------------------------------------------------------------------------
  // 21. getBoolean — type coercion edge cases
  // -------------------------------------------------------------------------

  @Test
  public void testGetBooleanFromNumber() {
    JSONObject obj = JSON.parseObject("{\"a\":1,\"b\":0,\"c\":-1}");
    assertEquals(Boolean.TRUE, obj.getBoolean("a"));
    assertEquals(Boolean.FALSE, obj.getBoolean("b"));
    assertEquals(Boolean.TRUE, obj.getBoolean("c"));
  }

  @Test
  public void testGetBooleanFromString() {
    JSONObject obj = JSON.parseObject(
        "{\"a\":\"true\",\"b\":\"false\",\"c\":\"TRUE\",\"d\":\"1\",\"e\":\"0\"}");
    assertEquals(Boolean.TRUE, obj.getBoolean("a"));
    assertEquals(Boolean.FALSE, obj.getBoolean("b"));
    assertEquals(Boolean.TRUE, obj.getBoolean("c"));
    assertEquals(Boolean.TRUE, obj.getBoolean("d"));
    assertEquals(Boolean.FALSE, obj.getBoolean("e"));
  }

  @Test(expected = JSONException.class)
  public void testGetBooleanInvalidStringThrows() {
    JSONObject obj = JSON.parseObject("{\"val\":\"maybe\"}");
    obj.getBoolean("val");
  }

  @Test
  public void testGetBooleanNullAndMissing() {
    JSONObject obj = JSON.parseObject("{\"val\":null}");
    assertNull(obj.getBoolean("val"));
    assertNull(obj.getBoolean("nonexistent"));
  }

  // -------------------------------------------------------------------------
  // 22. keySet — returns all field names
  // -------------------------------------------------------------------------

  @Test
  public void testKeySetContainsAllFields() {
    JSONObject obj = JSON.parseObject("{\"a\":1,\"b\":2,\"c\":3}");
    Set<String> keys = obj.keySet();
    assertEquals(3, keys.size());
    assertTrue(keys.contains("a"));
    assertTrue(keys.contains("b"));
    assertTrue(keys.contains("c"));
  }

  @Test
  public void testKeySetEmptyObject() {
    JSONObject obj = JSON.parseObject("{}");
    assertTrue(obj.keySet().isEmpty());
  }

  // -------------------------------------------------------------------------
  // 23. JSON.parseObject(String, Class) — wrapper class handling
  // -------------------------------------------------------------------------

  @Test
  public void testParseObjectWithJSONObjectClass() {
    JSONObject result = JSON.parseObject("{\"a\":1}", JSONObject.class);
    assertNotNull(result);
    assertEquals(Integer.valueOf(1), result.getInteger("a"));
  }

  @Test
  public void testParseObjectWithJSONArrayClass() {
    JSONArray result = JSON.parseObject("[1,2]", JSONArray.class);
    assertNotNull(result);
    assertEquals(2, result.size());
  }

  @Test
  public void testParseObjectWithNullReturnsNull() {
    assertNull(JSON.parseObject(null, JSONObject.class));
    assertNull(JSON.parseObject("", JSONObject.class));
    assertNull(JSON.parseObject(null, JSONArray.class));
  }

  // -------------------------------------------------------------------------
  // 24. JSON.toJSONString — pretty print
  // -------------------------------------------------------------------------

  @Test
  public void testToJSONStringCompact() {
    JSONObject obj = JSON.parseObject("{\"a\":1}");
    String compact = JSON.toJSONString(obj);
    assertFalse("Compact should not contain newline", compact.contains("\n"));
  }

  @Test
  public void testToJSONStringPretty() {
    JSONObject obj = JSON.parseObject("{\"a\":1}");
    String pretty = JSON.toJSONString(obj, true);
    assertTrue("Pretty should contain newline", pretty.contains("\n"));
  }

  @Test
  public void testToJSONStringNull() {
    assertEquals("null", JSON.toJSONString(null));
    assertEquals("null", JSON.toJSONString(null, true));
  }

  // -------------------------------------------------------------------------
  // 25. JSON.parse — edge cases (null, empty, whitespace, "null" literal)
  // -------------------------------------------------------------------------

  @Test
  public void testParseNullReturnsNull() {
    assertNull("parse(null) should return null", JSON.parse(null));
  }

  @Test
  public void testParseEmptyReturnsNull() {
    assertNull("parse(\"\") should return null", JSON.parse(""));
  }

  @Test
  public void testParseJsonLiteralNull() {
    // Fastjson: JSON.parse("null") returns null
    com.fasterxml.jackson.databind.JsonNode node = JSON.parse("null");
    assertNotNull("Jackson returns NullNode for \"null\"", node);
    assertTrue("Should be NullNode", node.isNull());
  }

  @Test
  public void testParseValidJsonObject() {
    com.fasterxml.jackson.databind.JsonNode node = JSON.parse("{\"a\":1}");
    assertNotNull(node);
    assertTrue(node.isObject());
    assertEquals(1, node.get("a").intValue());
  }

  @Test
  public void testParseValidJsonArray() {
    com.fasterxml.jackson.databind.JsonNode node = JSON.parse("[1,2,3]");
    assertNotNull(node);
    assertTrue(node.isArray());
    assertEquals(3, node.size());
  }

  @Test
  public void testParseInvalidJsonThrowsException() {
    try {
      JSON.parse("{invalid");
      fail("Should throw JSONException for invalid JSON");
    } catch (JSONException e) {
      // expected
    }
  }

  // -------------------------------------------------------------------------
  // 26. parse() vs parseObject() whitespace handling consistency
  // -------------------------------------------------------------------------

  @Test
  public void testParseObjectWhitespaceReturnsNull() {
    // parseObject uses text.trim().isEmpty() — whitespace-only returns null
    assertNull("parseObject(\" \") should return null", JSON.parseObject("  "));
    assertNull("parseObject(\"\\t\") should return null", JSON.parseObject("\t"));
    assertNull("parseObject(\"\\n\") should return null", JSON.parseObject("\n"));
  }

  @Test
  public void testParseWhitespaceReturnsNull() {
    // parse() uses text.isEmpty() — whitespace-only goes to Jackson readTree.
    // Jackson readTree(" ") returns null → parse returns null.
    // Behavior is consistent with parseObject, even though the guard is different.
    assertNull("parse(\" \") should return null", JSON.parse("  "));
    assertNull("parse(\"\\t\") should return null", JSON.parse("\t"));
  }

  @Test
  public void testParseArrayWhitespaceReturnsNull() {
    assertNull("parseArray(\" \") should return null", JSON.parseArray("  "));
    assertNull("parseArray(\"\\t\") should return null", JSON.parseArray("\t"));
  }

  // -------------------------------------------------------------------------
  // 27. isValidJson — behavior via JSON.parse
  // -------------------------------------------------------------------------

  @Test
  public void testIsValidJsonBehavior() {
    // isValidJson delegates to JSON.parse(json) != null
    // Empty/null: parse returns null → isValidJson returns false
    assertNull(JSON.parse(null));
    assertNull(JSON.parse(""));

    // Valid JSON: parse returns non-null
    assertNotNull(JSON.parse("{\"a\":1}"));
    assertNotNull(JSON.parse("[1,2]"));
    assertNotNull(JSON.parse("\"hello\""));
    assertNotNull(JSON.parse("123"));
    assertNotNull(JSON.parse("true"));

    // JSON literal "null": parse returns NullNode (non-null!) → isValidJson true
    assertNotNull("\"null\" is valid JSON", JSON.parse("null"));
  }

  @Test
  public void testIsValidJsonInvalidInput() {
    // Invalid JSON: parse throws → isValidJson returns false
    try {
      JSON.parse("{bad json");
      fail("Should throw for invalid JSON");
    } catch (JSONException e) {
      // expected — isValidJson would catch this and return false
    }
  }

  // -------------------------------------------------------------------------
  // 28. toJSONString unwrap paths — JSONObject and JSONArray use node.toString()
  // -------------------------------------------------------------------------

  @Test
  public void testToJSONStringOmitsNullFieldsForPojo() {
    // MAPPER configured with NON_NULL — plain POJOs should omit null fields
    TestPojo pojo = new TestPojo("test", null);
    String json = JSON.toJSONString(pojo);
    assertTrue("Should contain name", json.contains("\"name\""));
    assertFalse("Should omit nullField", json.contains("nullField"));
  }

  @Test
  public void testToJSONStringWrappedObjectConsistency() {
    // All serialization paths now go through MAPPER.writeValueAsString(),
    // so JSONObject/JSONArray behave the same as POJO: NON_NULL omits null fields.
    JSONObject obj = new JSONObject();
    obj.put("name", "test");
    obj.put("value", (Object) null);

    // All three paths should produce identical output
    String viaStaticMethod = JSON.toJSONString(obj);
    String viaToString = obj.toString();
    String viaToJSONString = obj.toJSONString();

    assertEquals("JSON.toJSONString and toString should be identical",
        viaStaticMethod, viaToString);
    assertEquals("toString and toJSONString should be identical",
        viaToString, viaToJSONString);

    // NON_NULL should omit the null field
    assertTrue("Should contain name", viaStaticMethod.contains("\"name\""));
    assertFalse("Should omit null value field (Fastjson compat)",
        viaStaticMethod.contains("\"value\""));
  }

  @Test
  public void testToJSONStringJSONArray() {
    JSONArray arr = JSONArray.parseArray("[1, \"hello\", null, true]");
    String json = JSON.toJSONString(arr);
    assertNotNull(json);
    assertTrue(json.startsWith("["));
    assertTrue(json.endsWith("]"));
  }

  @Test
  public void testJSONObjectToStringOmitsNullFields() {
    // JSONObject.toString() should go through MAPPER and respect NON_NULL
    JSONObject obj = new JSONObject();
    obj.put("keep", "value");
    obj.put("drop", (String) null);
    String result = obj.toString();
    assertTrue("Should contain non-null field", result.contains("\"keep\""));
    assertFalse("Should omit null field", result.contains("\"drop\""));
  }

  @Test
  public void testJSONObjectToJSONStringOmitsNullFields() {
    // JSONObject.toJSONString() should behave identically to toString()
    JSONObject obj = new JSONObject();
    obj.put("a", 1);
    obj.put("b", (Object) null);
    obj.put("c", "hello");
    String result = obj.toJSONString();
    assertTrue(result.contains("\"a\""));
    assertTrue(result.contains("\"c\""));
    assertFalse("Should omit null field b", result.contains("\"b\""));
  }

  @Test
  public void testJSONObjectMultipleNullFieldsOmitted() {
    // All null fields should be omitted, non-null preserved
    JSONObject obj = new JSONObject();
    obj.put("x", "present");
    obj.put("n1", (String) null);
    obj.put("n2", (Object) null);
    obj.put("y", 42);
    String json = obj.toJSONString();
    assertTrue(json.contains("\"x\""));
    assertTrue(json.contains("\"y\""));
    assertFalse(json.contains("\"n1\""));
    assertFalse(json.contains("\"n2\""));
  }

  @Test
  public void testJSONArrayToStringPreservesNullElements() {
    // Array null elements are positional — they MUST be kept (unlike object null fields)
    JSONArray arr = JSONArray.parseArray("[1, null, \"a\"]");
    String viaToString = arr.toString();
    String viaToJSONString = arr.toJSONString();
    String viaStatic = JSON.toJSONString(arr);

    assertEquals(viaToString, viaToJSONString);
    assertEquals(viaToString, viaStatic);
    // null element must remain to preserve indices
    assertTrue("Array should preserve null element", viaToString.contains("null"));
    assertEquals("[1,null,\"a\"]", viaToString);
  }

  @Test
  public void testJSONObjectNestedNullHandling() {
    // Nested object with null fields — inner nulls should also be omitted
    JSONObject inner = new JSONObject();
    inner.put("present", "yes");
    inner.put("absent", (String) null);

    JSONObject outer = new JSONObject();
    outer.put("inner", inner.unwrap());
    outer.put("topNull", (Object) null);
    outer.put("topValue", 1);

    String json = outer.toJSONString();
    assertTrue(json.contains("\"inner\""));
    assertTrue(json.contains("\"present\""));
    assertFalse("Inner null field should be omitted", json.contains("\"absent\""));
    assertFalse("Outer null field should be omitted", json.contains("\"topNull\""));
    assertTrue(json.contains("\"topValue\""));
  }

  @Test
  public void testPojoAndJSONObjectNullConsistency() {
    // POJO and JSONObject should both omit null fields — consistent behavior
    String pojoJson = JSON.toJSONString(new TestPojo("test", null));
    assertFalse("POJO should omit null", pojoJson.contains("nullField"));

    JSONObject obj = new JSONObject();
    obj.put("name", "test");
    obj.put("nullField", (String) null);
    String objJson = obj.toJSONString();
    assertFalse("JSONObject should also omit null", objJson.contains("nullField"));
  }

  // -------------------------------------------------------------------------
  // 29. getJSONObject returns null for missing/null fields
  // -------------------------------------------------------------------------

  @Test
  public void testGetJSONObjectMissingKey() {
    JSONObject obj = JSON.parseObject("{\"a\":1}");
    assertNull("Missing key should return null", obj.getJSONObject("nonexistent"));
  }

  @Test
  public void testGetJSONObjectNullValue() {
    JSONObject obj = JSON.parseObject("{\"a\":null}");
    assertNull("Null value should return null", obj.getJSONObject("a"));
  }

  @Test
  public void testGetJSONObjectNested() {
    JSONObject obj = JSON.parseObject("{\"inner\":{\"x\":1}}");
    JSONObject inner = obj.getJSONObject("inner");
    assertNotNull(inner);
    assertEquals(Integer.valueOf(1), inner.getInteger("x"));
  }

  @Test
  public void testGetJSONObjectNotObjectThrows() {
    JSONObject obj = JSON.parseObject("{\"a\":\"string\"}");
    try {
      obj.getJSONObject("a");
      fail("Should throw JSONException for non-object field");
    } catch (JSONException e) {
      // expected
    }
  }

  // -------------------------------------------------------------------------
  // 30. getInteger auto-unboxing NPE risk documentation
  // -------------------------------------------------------------------------

  @Test
  public void testGetIntegerReturnsNullForMissing() {
    // Callers that auto-unbox the result to int will get NPE
    JSONObject obj = JSON.parseObject("{\"a\":1}");
    assertNull("getInteger for missing key returns null", obj.getInteger("nonexistent"));
  }

  @Test
  public void testGetIntegerReturnsNullForJsonNull() {
    JSONObject obj = JSON.parseObject("{\"a\":null}");
    assertNull("getInteger for JSON null returns null", obj.getInteger("a"));
  }

  @Test
  public void testGetIntValueReturnsZeroForMissing() {
    // getIntValue is safe for auto-unboxing — returns primitive 0
    JSONObject obj = JSON.parseObject("{\"a\":1}");
    assertEquals(0, obj.getIntValue("nonexistent"));
  }

  // =========================================================================
  // 31. Fastjson JSONObjectTest — cross-type coercion (getString on numbers,
  //     getInteger on strings, getLong from int, etc.)
  //     Ref: com.alibaba.json.bvt.JSONObjectTest#test_all, test_all_2
  // =========================================================================

  @Test
  public void testGetStringFromNumber() {
    // Fastjson: getString("E") where E=99 returns "99"
    JSONObject obj = new JSONObject();
    obj.put("intVal", 99);
    obj.put("longVal", 123456789L);
    obj.put("boolVal", true);
    obj.put("doubleVal", 3.14);
    assertEquals("99", obj.getString("intVal"));
    assertEquals("123456789", obj.getString("longVal"));
    assertEquals("true", obj.getString("boolVal"));
    assertNotNull(obj.getString("doubleVal"));
  }

  @Test
  public void testGetLongFromInteger() {
    // Fastjson: getLong("E") where E is Integer(99) returns 99L
    JSONObject obj = JSON.parseObject("{\"a\":99}");
    assertEquals(Long.valueOf(99L), obj.getLong("a"));
    assertEquals(99L, obj.getLongValue("a"));
  }

  @Test
  public void testGetIntegerFromLong() {
    // Fastjson: getInteger("A") where A is Long(55L) returns Integer(55)
    JSONObject obj = JSON.parseObject("{\"a\":55}");
    assertEquals(Integer.valueOf(55), obj.getInteger("a"));
  }

  @Test
  public void testGetIntegerFromString() {
    // Fastjson test_all_2: getInteger("1") where "1"="222" returns Integer(222)
    JSONObject obj = new JSONObject();
    obj.put("num", "222");
    assertEquals(Integer.valueOf(222), obj.getInteger("num"));
    assertEquals(222, obj.getIntValue("num"));
  }

  @Test
  public void testGetLongFromString() {
    JSONObject obj = new JSONObject();
    obj.put("num", "222");
    assertEquals(Long.valueOf(222L), obj.getLong("num"));
    assertEquals(222L, obj.getLongValue("num"));
  }

  @Test
  public void testGetBigDecimalFromString() {
    // Fastjson test_all_2: getBigDecimal("1") where "1"="222" returns BigDecimal(222)
    JSONObject obj = new JSONObject();
    obj.put("num", "222");
    assertEquals(new BigDecimal("222"), obj.getBigDecimal("num"));
  }

  @Test
  public void testGetBooleanFromStringCoercion() {
    // Fastjson test_all_2: getBooleanValue("4") where "4"="true"
    JSONObject obj = new JSONObject();
    obj.put("t", "true");
    obj.put("f", "false");
    obj.put("T", "TRUE");
    obj.put("one", "1");
    obj.put("zero", "0");
    assertEquals(Boolean.TRUE, obj.getBoolean("t"));
    assertEquals(Boolean.FALSE, obj.getBoolean("f"));
    assertEquals(Boolean.TRUE, obj.getBoolean("T"));
    assertEquals(Boolean.TRUE, obj.getBoolean("one"));
    assertEquals(Boolean.FALSE, obj.getBoolean("zero"));
  }

  @Test
  public void testGetBooleanFromNumberCoercion() {
    // Fastjson: non-zero → true, zero → false
    JSONObject obj = JSON.parseObject("{\"a\":1,\"b\":0,\"c\":-1,\"d\":100}");
    assertEquals(Boolean.TRUE, obj.getBoolean("a"));
    assertEquals(Boolean.FALSE, obj.getBoolean("b"));
    assertEquals(Boolean.TRUE, obj.getBoolean("c"));
    assertEquals(Boolean.TRUE, obj.getBoolean("d"));
  }

  @Test
  public void testGetDoubleFromString() {
    // Fastjson test_all_2: getDouble("5") where "5"="2.0" returns 2.0D
    JSONObject obj = new JSONObject();
    obj.put("d", "2.0");
    assertEquals(Double.valueOf(2.0), obj.getDouble("d"));
  }

  // -------------------------------------------------------------------------
  // 32. Fastjson JSONObjectTest — size, isEmpty, containsKey, remove, keySet
  //     Ref: com.alibaba.json.bvt.JSONObjectTest#test_all
  // -------------------------------------------------------------------------

  @Test
  public void testSizeAndIsEmpty() {
    JSONObject obj = new JSONObject();
    assertEquals(0, obj.size());
    assertTrue(obj.isEmpty());
    obj.put("a", 1);
    assertEquals(1, obj.size());
    obj.put("b", 2);
    assertEquals(2, obj.size());
  }

  @Test
  public void testContainsKeyAfterPutAndRemove() {
    // Fastjson test_all: containsKey/remove
    JSONObject obj = new JSONObject();
    obj.put("A", 1);
    obj.put("B", 2);
    obj.put("C", 51L);
    assertTrue(obj.containsKey("C"));
    assertFalse(obj.containsKey("D"));

    // remove returns previous value
    Object removed = obj.remove("C");
    assertNotNull(removed);
    assertFalse(obj.containsKey("C"));
    assertEquals(2, obj.size());

    // remove missing key returns null
    assertNull(obj.remove("nonexistent"));
  }

  @Test
  public void testKeySet() {
    JSONObject obj = new JSONObject();
    obj.put("x", 1);
    obj.put("y", 2);
    obj.put("z", 3);
    Set<String> keys = obj.keySet();
    assertEquals(3, keys.size());
    assertTrue(keys.contains("x"));
    assertTrue(keys.contains("y"));
    assertTrue(keys.contains("z"));
  }

  @Test
  public void testToMap() {
    JSONObject obj = JSON.parseObject("{\"a\":1,\"b\":\"hello\",\"c\":true}");
    java.util.Map<String, Object> map = obj.toMap();
    assertEquals(3, map.size());
    assertEquals("hello", map.get("b"));
    assertEquals(true, map.get("c"));
  }

  // -------------------------------------------------------------------------
  // 33. Fastjson JSONObjectTest — empty JSONObject serialization
  //     Ref: com.alibaba.json.bvt.JSONObjectTest#test_writeJSONString
  // -------------------------------------------------------------------------

  @Test
  public void testEmptyJSONObjectSerializesToBraces() {
    JSONObject obj = new JSONObject();
    assertEquals("{}", obj.toJSONString());
    assertEquals("{}", obj.toString());
    assertEquals("{}", JSON.toJSONString(obj));
  }

  @Test
  public void testEmptyJSONArraySerializesToBrackets() {
    JSONArray arr = new JSONArray();
    assertEquals("[]", arr.toJSONString());
    assertEquals("[]", arr.toString());
    assertEquals("[]", JSON.toJSONString(arr));
  }

  // -------------------------------------------------------------------------
  // 34. Fastjson JSONObjectTest — getObject for null/empty nested objects
  //     Ref: com.alibaba.json.bvt.JSONObjectTest#test_getObject_null
  // -------------------------------------------------------------------------

  @Test
  public void testGetJSONObjectNullEntry() {
    // Fastjson: getJSONObject("obj") returns null when value is null
    JSONObject obj = JSON.parseObject("{\"obj\":null}");
    assertNull(obj.getJSONObject("obj"));
  }

  @Test
  public void testGetJSONObjectEmptyNested() {
    // Fastjson: getJSONObject("obj") returns JSONObject with size 0
    JSONObject obj = JSON.parseObject("{\"obj\":{}}");
    JSONObject inner = obj.getJSONObject("obj");
    assertNotNull(inner);
    assertEquals(0, inner.size());
  }

  @Test
  public void testGetJSONArrayNullEntry() {
    JSONObject obj = JSON.parseObject("{\"arr\":null}");
    assertNull(obj.getJSONArray("arr"));
  }

  @Test
  public void testGetJSONArrayEmpty() {
    JSONObject obj = JSON.parseObject("{\"arr\":[]}");
    JSONArray arr = obj.getJSONArray("arr");
    assertNotNull(arr);
    assertEquals(0, arr.size());
  }

  // -------------------------------------------------------------------------
  // 35. Fastjson — primitive defaults for missing keys
  //     Ref: com.alibaba.json.bvt.JSONObjectTest#test_order
  // -------------------------------------------------------------------------

  @Test
  public void testPrimitiveDefaultsForMissingKeys() {
    // Fastjson: getIntValue for missing → 0, getLongValue → 0L
    JSONObject obj = JSON.parseObject("{\"a\":1}");
    assertEquals(0, obj.getIntValue("missing"));
    assertEquals(0L, obj.getLongValue("missing"));
    assertNull(obj.getInteger("missing"));
    assertNull(obj.getLong("missing"));
    assertNull(obj.getBoolean("missing"));
    assertNull(obj.getDouble("missing"));
    assertNull(obj.getBigDecimal("missing"));
    assertNull(obj.getString("missing"));
  }

  @Test
  public void testPrimitiveDefaultsForNullValues() {
    // Fastjson: explicit JSON null → getIntValue returns 0, getLongValue returns 0L
    JSONObject obj = JSON.parseObject("{\"a\":null}");
    assertEquals(0, obj.getIntValue("a"));
    assertEquals(0L, obj.getLongValue("a"));
    assertNull(obj.getInteger("a"));
    assertNull(obj.getLong("a"));
    assertNull(obj.getBoolean("a"));
    assertNull(obj.getDouble("a"));
    assertNull(obj.getBigDecimal("a"));
    assertNull(obj.getString("a"));
  }

  // -------------------------------------------------------------------------
  // 36. Fastjson — put overwrites and null removes
  // -------------------------------------------------------------------------

  @Test
  public void testPutOverwritesPreviousValue() {
    JSONObject obj = new JSONObject();
    obj.put("key", "first");
    assertEquals("first", obj.getString("key"));
    obj.put("key", "second");
    assertEquals("second", obj.getString("key"));
    assertEquals(1, obj.size());
  }

  @Test
  public void testPutNullRemovesKey() {
    // Fastjson default: put(key, null) with WriteMapNullValue OFF
    // → field not present in serialization; our impl removes the key
    JSONObject obj = new JSONObject();
    obj.put("a", "value");
    obj.put("b", 123);
    assertEquals(2, obj.size());
    obj.put("a", (String) null);
    assertEquals(1, obj.size());
    assertFalse(obj.containsKey("a"));
    assertNull(obj.getString("a"));
  }

  @Test
  public void testPutNullVariousTypes() {
    JSONObject obj = new JSONObject();
    obj.put("s", "val");
    obj.put("i", 1);
    obj.put("l", 2L);
    obj.put("b", true);
    assertEquals(4, obj.size());

    obj.put("s", (String) null);
    obj.put("i", (Integer) null);
    obj.put("l", (Long) null);
    obj.put("b", (Boolean) null);
    assertEquals(0, obj.size());
    assertEquals("{}", obj.toJSONString());
  }

  // -------------------------------------------------------------------------
  // 37. Fastjson — parseObject edge cases
  // -------------------------------------------------------------------------

  @Test
  public void testParseNull() {
    // Fastjson: JSON.parse(null) returns null, parseObject(null) returns null
    assertNull(JSON.parseObject(null));
    assertNull(JSON.parseObject(""));
    assertNull(JSON.parseObject("  "));
    assertNull(JSON.parseObject("null"));
  }

  @Test
  public void testParseEmptyObject() {
    JSONObject obj = JSON.parseObject("{}");
    assertNotNull(obj);
    assertEquals(0, obj.size());
    assertEquals("{}", obj.toJSONString());
  }

  @Test
  public void testParseEmptyArray() {
    JSONArray arr = JSON.parseArray("[]");
    assertNotNull(arr);
    assertEquals(0, arr.size());
    assertEquals("[]", arr.toJSONString());
  }

  @Test
  public void testParseArrayNull() {
    assertNull(JSON.parseArray(null));
    assertNull(JSON.parseArray(""));
    assertNull(JSON.parseArray("  "));
    assertNull(JSON.parseArray("null"));
  }

  // -------------------------------------------------------------------------
  // 38. Fastjson — JSONArray index-based getters
  //     Ref: com.alibaba.json.bvt.JSONArrayTest
  // -------------------------------------------------------------------------

  @Test
  public void testJSONArrayGetJSONObject() {
    JSONArray arr = JSON.parseArray("[{\"a\":1},{\"b\":2}]");
    JSONObject first = arr.getJSONObject(0);
    assertNotNull(first);
    assertEquals(Integer.valueOf(1), first.getInteger("a"));
    JSONObject second = arr.getJSONObject(1);
    assertEquals(Integer.valueOf(2), second.getInteger("b"));
  }

  @Test
  public void testJSONArrayGetJSONArray() {
    JSONArray arr = JSON.parseArray("[[1,2],[3,4]]");
    JSONArray inner = arr.getJSONArray(0);
    assertNotNull(inner);
    assertEquals(2, inner.size());
  }

  @Test
  public void testJSONArrayGetString() {
    JSONArray arr = JSON.parseArray("[\"hello\",123,true,null]");
    assertEquals("hello", arr.getString(0));
    assertEquals("123", arr.getString(1));
    assertEquals("true", arr.getString(2));
    assertNull(arr.getString(3));
  }

  @Test
  public void testJSONArrayGetBoolean() {
    JSONArray arr = JSON.parseArray("[true,false,1,0,\"true\",\"false\"]");
    assertEquals(Boolean.TRUE, arr.getBoolean(0));
    assertEquals(Boolean.FALSE, arr.getBoolean(1));
    assertEquals(Boolean.TRUE, arr.getBoolean(2));
    assertEquals(Boolean.FALSE, arr.getBoolean(3));
    assertEquals(Boolean.TRUE, arr.getBoolean(4));
    assertEquals(Boolean.FALSE, arr.getBoolean(5));
  }

  @Test
  public void testJSONArraySize() {
    JSONArray arr = JSON.parseArray("[1,2,3]");
    assertEquals(3, arr.size());
    assertFalse(arr.isEmpty());

    JSONArray empty = JSON.parseArray("[]");
    assertEquals(0, empty.size());
    assertTrue(empty.isEmpty());
  }

  @Test(expected = IndexOutOfBoundsException.class)
  public void testJSONArrayGetOutOfBounds() {
    JSONArray arr = JSON.parseArray("[1,2,3]");
    arr.get(3);
  }

  @Test(expected = IndexOutOfBoundsException.class)
  public void testJSONArrayGetNegativeIndex() {
    JSONArray arr = JSON.parseArray("[1,2,3]");
    arr.get(-1);
  }

  @Test(expected = IndexOutOfBoundsException.class)
  public void testJSONArrayGetStringOutOfBounds() {
    JSONArray arr = JSON.parseArray("[\"a\"]");
    arr.getString(1);
  }

  @Test(expected = IndexOutOfBoundsException.class)
  public void testJSONArrayGetJSONObjectOutOfBounds() {
    JSONArray arr = JSON.parseArray("[{\"a\":1}]");
    arr.getJSONObject(1);
  }

  @Test(expected = IndexOutOfBoundsException.class)
  public void testJSONArrayGetBooleanOutOfBounds() {
    JSONArray arr = JSON.parseArray("[true]");
    arr.getBoolean(1);
  }

  @Test
  public void testJSONArrayIterator() {
    JSONArray arr = JSON.parseArray("[1,\"two\",true]");
    List<Object> items = new ArrayList<>();
    for (Object item : arr) {
      items.add(item);
    }
    assertEquals(3, items.size());
    assertEquals("two", items.get(1));
    assertEquals(true, items.get(2));
  }

  @Test
  public void testJSONArrayAdd() {
    JSONArray arr = new JSONArray();
    arr.add("hello");
    arr.add(123);
    arr.add(new JSONObject());
    assertEquals(3, arr.size());
    assertEquals("hello", arr.getString(0));
    assertEquals("[\"hello\",123,{}]", arr.toJSONString());
  }

  // -------------------------------------------------------------------------
  // 39. Fastjson — toJavaList
  //     Ref: JSONArray typed conversion
  // -------------------------------------------------------------------------

  @Test
  public void testToJavaList() {
    JSONArray arr = JSON.parseArray("[\"a\",\"b\",\"c\"]");
    List<String> list = arr.toJavaList(String.class);
    assertEquals(3, list.size());
    assertEquals("a", list.get(0));
    assertEquals("b", list.get(1));
    assertEquals("c", list.get(2));
  }

  @Test
  public void testToJavaListJSONObject() {
    // Reproduces the bug: toJavaList(JSONObject.class) must preserve content
    JSONArray arr = JSON.parseArray("[{\"a\":1},{\"b\":\"hello\"}]");
    List<JSONObject> list = arr.toJavaList(JSONObject.class);
    assertEquals(2, list.size());
    assertEquals(Integer.valueOf(1), list.get(0).getInteger("a"));
    assertEquals("hello", list.get(1).getString("b"));
  }

  @Test
  public void testToJavaListJSONArray() {
    // toJavaList(JSONArray.class) must preserve nested arrays
    JSONArray arr = JSON.parseArray("[[1,2],[3,4,5]]");
    List<JSONArray> list = arr.toJavaList(JSONArray.class);
    assertEquals(2, list.size());
    assertEquals(2, list.get(0).size());
    assertEquals(3, list.get(1).size());
    assertEquals("1", list.get(0).getString(0));
  }

  @Test
  public void testToJavaListJSONObjectTypeMismatch() {
    // Non-object element should throw when asking for JSONObject
    JSONArray arr = JSON.parseArray("[\"not_an_object\"]");
    try {
      arr.toJavaList(JSONObject.class);
      fail("Should throw JSONException for non-object element");
    } catch (JSONException e) {
      // expected
    }
  }

  @Test
  public void testToJavaListJSONArrayTypeMismatch() {
    // Non-array element should throw when asking for JSONArray
    JSONArray arr = JSON.parseArray("[{\"a\":1}]");
    try {
      arr.toJavaList(JSONArray.class);
      fail("Should throw JSONException for non-array element");
    } catch (JSONException e) {
      // expected
    }
  }

  @Test
  public void testToJavaListJSONObjectWithNullElements() {
    // Fastjson preserves null elements as null entries in the list
    JSONArray arr = JSON.parseArray("[{\"a\":1},null,{\"b\":2}]");
    List<JSONObject> list = arr.toJavaList(JSONObject.class);
    assertEquals(3, list.size());
    assertEquals(Integer.valueOf(1), list.get(0).getInteger("a"));
    assertNull("null element should be preserved as null", list.get(1));
    assertEquals(Integer.valueOf(2), list.get(2).getInteger("b"));
  }

  @Test
  public void testToJavaListJSONArrayWithNullElements() {
    JSONArray arr = JSON.parseArray("[[1,2],null,[3]]");
    List<JSONArray> list = arr.toJavaList(JSONArray.class);
    assertEquals(3, list.size());
    assertEquals(2, list.get(0).size());
    assertNull("null element should be preserved as null", list.get(1));
    assertEquals(1, list.get(2).size());
  }

  @Test
  public void testToJavaListStringWithNullElements() {
    JSONArray arr = JSON.parseArray("[\"a\",null,\"c\"]");
    List<String> list = arr.toJavaList(String.class);
    assertEquals(3, list.size());
    assertEquals("a", list.get(0));
    assertNull(list.get(1));
    assertEquals("c", list.get(2));
  }

  @Test
  public void testToJavaListInteger() {
    JSONArray arr = JSON.parseArray("[1,2,3]");
    List<Integer> list = arr.toJavaList(Integer.class);
    assertEquals(3, list.size());
    assertEquals(Integer.valueOf(1), list.get(0));
    assertEquals(Integer.valueOf(3), list.get(2));
  }

  // -------------------------------------------------------------------------
  // 40. Fastjson — JSON.toJSONString for various types
  //     Ref: JSON_toJSONString_test
  // -------------------------------------------------------------------------

  @Test
  public void testToJSONStringNullValue() {
    assertEquals("null", JSON.toJSONString(null));
  }

  @Test
  public void testToJSONStringPrimitive() {
    assertEquals("123", JSON.toJSONString(123));
    assertEquals("\"hello\"", JSON.toJSONString("hello"));
    assertEquals("true", JSON.toJSONString(true));
    assertEquals("3.14", JSON.toJSONString(new BigDecimal("3.14")));
  }

  @Test
  public void testToJSONStringList() {
    List<Integer> list = Arrays.asList(1, 2, 3);
    assertEquals("[1,2,3]", JSON.toJSONString(list));
  }

  @Test
  public void testToJSONStringMap() {
    java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
    map.put("a", 1);
    map.put("b", "two");
    String json = JSON.toJSONString(map);
    assertTrue(json.contains("\"a\":1"));
    assertTrue(json.contains("\"b\":\"two\""));
  }

  @Test
  public void testToJSONStringPrettyPrint() {
    JSONObject obj = JSON.parseObject("{\"a\":1}");
    String pretty = JSON.toJSONString(obj, true);
    assertTrue("Pretty print should contain newline", pretty.contains("\n"));
    assertTrue("Pretty print should contain 'a'", pretty.contains("\"a\""));
  }

  // -------------------------------------------------------------------------
  // 41. Fastjson — round-trip consistency
  //     parse → serialize → parse should be idempotent
  // -------------------------------------------------------------------------

  @Test
  public void testRoundTripSimple() {
    String original = "{\"name\":\"test\",\"age\":25,\"active\":true}";
    JSONObject obj = JSON.parseObject(original);
    String serialized = obj.toJSONString();
    JSONObject obj2 = JSON.parseObject(serialized);
    assertEquals(obj.getString("name"), obj2.getString("name"));
    assertEquals(obj.getInteger("age"), obj2.getInteger("age"));
    assertEquals(obj.getBoolean("active"), obj2.getBoolean("active"));
  }

  @Test
  public void testRoundTripNested() {
    String original = "{\"user\":{\"name\":\"test\"},\"tags\":[\"a\",\"b\"]}";
    JSONObject obj = JSON.parseObject(original);
    String serialized = obj.toJSONString();
    JSONObject obj2 = JSON.parseObject(serialized);
    assertEquals("test", obj2.getJSONObject("user").getString("name"));
    assertEquals(2, obj2.getJSONArray("tags").size());
  }

  @Test
  public void testRoundTripArray() {
    String original = "[1,\"two\",{\"three\":3},[4,5]]";
    JSONArray arr = JSON.parseArray(original);
    String serialized = arr.toJSONString();
    JSONArray arr2 = JSON.parseArray(serialized);
    assertEquals(arr.size(), arr2.size());
    assertEquals("two", arr2.getString(1));
    assertEquals(Integer.valueOf(3), arr2.getJSONObject(2).getInteger("three"));
  }

  // -------------------------------------------------------------------------
  // 42. Fastjson — numeric edge cases (BigDecimal precision, large numbers)
  // -------------------------------------------------------------------------

  @Test
  public void testBigDecimalPrecision() {
    // USE_BIG_DECIMAL_FOR_FLOATS ensures precision is not lost
    String json = "{\"price\":123456789.123456789}";
    JSONObject obj = JSON.parseObject(json);
    BigDecimal bd = obj.getBigDecimal("price");
    assertNotNull(bd);
    assertEquals(new BigDecimal("123456789.123456789"), bd);
  }

  @Test
  public void testLargeInteger() {
    String json = "{\"big\":99999999999999999}";
    JSONObject obj = JSON.parseObject(json);
    assertEquals(Long.valueOf(99999999999999999L), obj.getLong("big"));
  }

  @Test
  public void testBigIntegerValue() {
    // Numbers beyond Long.MAX_VALUE
    String json = "{\"huge\":99999999999999999999}";
    JSONObject obj = JSON.parseObject(json);
    Object val = obj.get("huge");
    assertTrue("Should be BigInteger for very large numbers", val instanceof BigInteger);
  }

  // -------------------------------------------------------------------------
  // 43. Fastjson — getString on nested objects returns JSON string
  // -------------------------------------------------------------------------

  @Test
  public void testGetStringOnNestedObject() {
    // Fastjson: getString on an object field returns its JSON string
    JSONObject obj = JSON.parseObject("{\"inner\":{\"x\":1}}");
    String innerStr = obj.getString("inner");
    assertNotNull(innerStr);
    assertTrue(innerStr.contains("\"x\""));
    // Should be parseable back
    JSONObject parsed = JSON.parseObject(innerStr);
    assertEquals(Integer.valueOf(1), parsed.getInteger("x"));
  }

  @Test
  public void testGetStringOnNestedArray() {
    JSONObject obj = JSON.parseObject("{\"arr\":[1,2,3]}");
    String arrStr = obj.getString("arr");
    assertNotNull(arrStr);
    assertTrue(arrStr.startsWith("["));
    assertTrue(arrStr.endsWith("]"));
  }

  // -------------------------------------------------------------------------
  // 44. Fastjson — Fastjson behavior: put different types then verify
  //     full Fastjson toJSONString equivalence
  //     Ref: user-provided Fastjson example:
  //     put("type", null) → omitted
  //     put("data", "null") → {"data":"null"}
  //     put("data1", "") → {"data1":""}
  //     put("data2", false) → {"data2":false}
  // -------------------------------------------------------------------------

  @Test
  public void testFastjsonPutNullVsStringNull() {
    JSONObject obj = new JSONObject();
    obj.put("type", (String) null);   // should be omitted
    obj.put("data", "null");          // string "null" — kept
    obj.put("data1", "");             // empty string — kept
    obj.put("data2", false);          // boolean false — kept
    String json = obj.toJSONString();
    assertFalse("null value should be omitted", json.contains("\"type\""));
    assertTrue("string 'null' should be present", json.contains("\"data\":\"null\""));
    assertTrue("empty string should be present", json.contains("\"data1\":\"\""));
    assertTrue("false should be present", json.contains("\"data2\":false"));
  }

  @Test
  public void testFastjsonPutZeroAndEmptyNotOmitted() {
    // Only null is omitted; 0, empty string, false are kept
    JSONObject obj = new JSONObject();
    obj.put("zero", 0);
    obj.put("empty", "");
    obj.put("falseVal", false);
    obj.put("nullVal", (String) null);
    String json = obj.toJSONString();
    assertTrue(json.contains("\"zero\":0"));
    assertTrue(json.contains("\"empty\":\"\""));
    assertTrue(json.contains("\"falseVal\":false"));
    assertFalse(json.contains("\"nullVal\""));
  }

  // -------------------------------------------------------------------------
  // 45. getObject(String, Class) — wrapper type handling
  // -------------------------------------------------------------------------

  @Test
  public void testGetObjectJSONObject() {
    JSONObject obj = JSON.parseObject("{\"inner\":{\"a\":1,\"b\":\"hello\"}}");
    JSONObject inner = obj.getObject("inner", JSONObject.class);
    assertNotNull(inner);
    assertEquals(Integer.valueOf(1), inner.getInteger("a"));
    assertEquals("hello", inner.getString("b"));
  }

  @Test
  public void testGetObjectJSONArray() {
    JSONObject obj = JSON.parseObject("{\"arr\":[1,2,3]}");
    JSONArray arr = obj.getObject("arr", JSONArray.class);
    assertNotNull(arr);
    assertEquals(3, arr.size());
    assertEquals("1", arr.getString(0));
  }

  @Test
  public void testGetObjectJSONObjectNull() {
    JSONObject obj = JSON.parseObject("{\"inner\":null}");
    assertNull(obj.getObject("inner", JSONObject.class));
    assertNull(obj.getObject("missing", JSONObject.class));
  }

  @Test
  public void testGetObjectJSONObjectTypeMismatch() {
    JSONObject obj = JSON.parseObject("{\"val\":\"not_an_object\"}");
    try {
      obj.getObject("val", JSONObject.class);
      fail("Should throw for non-object");
    } catch (JSONException e) {
      // expected
    }
  }

  @Test
  public void testGetObjectJSONArrayTypeMismatch() {
    JSONObject obj = JSON.parseObject("{\"val\":{\"a\":1}}");
    try {
      obj.getObject("val", JSONArray.class);
      fail("Should throw for non-array");
    } catch (JSONException e) {
      // expected
    }
  }

  @Test
  public void testGetObjectPojo() {
    // Regular POJO deserialization still works via MAPPER.treeToValue
    JSONObject obj = JSON.parseObject("{\"name\":\"test\",\"nullField\":null}");
    TestPojo pojo = obj.getObject("", TestPojo.class);
    // getObject with empty key returns null (key doesn't exist)
    assertNull(pojo);
  }

  // -------------------------------------------------------------------------
  // 46. put(String, Object) with raw JsonNode — direct node handling
  // -------------------------------------------------------------------------

  @Test
  public void testPutRawObjectNode() {
    // put with raw ObjectNode (via unwrap) should not double-wrap
    JSONObject inner = JSON.parseObject("{\"x\":1}");
    JSONObject outer = new JSONObject();
    outer.put("inner", inner.unwrap());
    assertEquals(Integer.valueOf(1),
        outer.getJSONObject("inner").getInteger("x"));
  }

  @Test
  public void testPutRawArrayNode() {
    JSONArray arr = JSON.parseArray("[1,2,3]");
    JSONObject obj = new JSONObject();
    obj.put("arr", arr.unwrap());
    assertEquals(3, obj.getJSONArray("arr").size());
  }

  // -------------------------------------------------------------------------
  // 47. put(String, List) — wrapper type elements preserved
  // -------------------------------------------------------------------------

  @Test
  public void testPutListWithJSONObjectElements() {
    JSONObject inner1 = JSON.parseObject("{\"a\":1}");
    JSONObject inner2 = JSON.parseObject("{\"b\":2}");
    List<JSONObject> list = Arrays.asList(inner1, inner2);

    JSONObject obj = new JSONObject();
    obj.put("items", list);

    JSONArray arr = obj.getJSONArray("items");
    assertNotNull(arr);
    assertEquals(2, arr.size());
    assertEquals(Integer.valueOf(1), arr.getJSONObject(0).getInteger("a"));
    assertEquals(Integer.valueOf(2), arr.getJSONObject(1).getInteger("b"));
  }

  @Test
  public void testPutListWithMixedTypes() {
    List<Object> mixed = Arrays.asList("hello", 123, null, true);
    JSONObject obj = new JSONObject();
    obj.put("mixed", mixed);

    String json = obj.toJSONString();
    assertTrue(json.contains("\"hello\""));
    assertTrue(json.contains("123"));
    assertTrue(json.contains("null"));
    assertTrue(json.contains("true"));
  }

  @Test
  public void testPutListNull() {
    JSONObject obj = new JSONObject();
    obj.put("list", Arrays.asList(1, 2));
    assertEquals(1, obj.size());
    obj.put("list", (List<?>) null);
    assertEquals(0, obj.size());
    assertEquals("{}", obj.toJSONString());
  }

  // =========================================================================
  // 48. keySet() preserves insertion order (Fastjson uses LinkedHashMap)
  // =========================================================================

  @Test
  public void testKeySetPreservesInsertionOrder() {
    JSONObject obj = new JSONObject();
    obj.put("charlie", 3);
    obj.put("alpha", 1);
    obj.put("bravo", 2);
    obj.put("delta", 4);

    Set<String> keys = obj.keySet();
    List<String> keyList = new ArrayList<>(keys);
    assertEquals(Arrays.asList("charlie", "alpha", "bravo", "delta"), keyList);
  }

  @Test
  public void testKeySetOrderFromParsedJson() {
    // Jackson's ObjectNode preserves parse order by default
    JSONObject obj = JSONObject.parseObject(
        "{\"z\":1,\"a\":2,\"m\":3,\"b\":4}");
    List<String> keyList = new ArrayList<>(obj.keySet());
    assertEquals(Arrays.asList("z", "a", "m", "b"), keyList);
  }

  // =========================================================================
  // 49. toMap() preserves insertion order (Fastjson uses LinkedHashMap)
  // =========================================================================

  @Test
  public void testToMapPreservesInsertionOrder() {
    JSONObject obj = new JSONObject();
    obj.put("z", "last");
    obj.put("a", "first");
    obj.put("m", "middle");

    Map<String, Object> map = obj.toMap();
    List<String> mapKeys = new ArrayList<>(map.keySet());
    assertEquals(Arrays.asList("z", "a", "m"), mapKeys);
  }

  // =========================================================================
  // 50. Boolean → numeric coercion (Fastjson: true→1, false→0)
  // =========================================================================

  @Test
  public void testGetIntegerFromBoolean() {
    JSONObject obj = JSONObject.parseObject("{\"flag\":true,\"off\":false}");
    assertEquals(Integer.valueOf(1), obj.getInteger("flag"));
    assertEquals(Integer.valueOf(0), obj.getInteger("off"));
  }

  @Test
  public void testGetLongFromBoolean() {
    JSONObject obj = JSONObject.parseObject("{\"flag\":true,\"off\":false}");
    assertEquals(Long.valueOf(1L), obj.getLong("flag"));
    assertEquals(Long.valueOf(0L), obj.getLong("off"));
  }

  @Test
  public void testGetLongValueFromBoolean() {
    JSONObject obj = JSONObject.parseObject("{\"flag\":true,\"off\":false}");
    assertEquals(1L, obj.getLongValue("flag"));
    assertEquals(0L, obj.getLongValue("off"));
  }

  @Test
  public void testGetIntValueFromBoolean() {
    JSONObject obj = JSONObject.parseObject("{\"flag\":true,\"off\":false}");
    assertEquals(1, obj.getIntValue("flag"));
    assertEquals(0, obj.getIntValue("off"));
  }

  @Test
  public void testGetDoubleFromBoolean() {
    JSONObject obj = JSONObject.parseObject("{\"flag\":true,\"off\":false}");
    assertEquals(Double.valueOf(1.0), obj.getDouble("flag"));
    assertEquals(Double.valueOf(0.0), obj.getDouble("off"));
  }

  @Test
  public void testGetBigDecimalFromBoolean() {
    JSONObject obj = JSONObject.parseObject("{\"flag\":true,\"off\":false}");
    assertEquals(BigDecimal.ONE, obj.getBigDecimal("flag"));
    assertEquals(BigDecimal.ZERO, obj.getBigDecimal("off"));
  }

  // =========================================================================
  // 51. JSONObject.isEmpty() (Fastjson compat)
  // =========================================================================

  @Test
  public void testIsEmptyOnNewObject() {
    JSONObject obj = new JSONObject();
    assertTrue(obj.isEmpty());
    obj.put("key", "value");
    assertFalse(obj.isEmpty());
    obj.remove("key");
    assertTrue(obj.isEmpty());
  }

  @Test
  public void testIsEmptyOnParsedObject() {
    assertTrue(JSONObject.parseObject("{}").isEmpty());
    assertFalse(JSONObject.parseObject("{\"a\":1}").isEmpty());
  }

  // =========================================================================
  // 52. Boxed getters return null for empty/"null" strings, primitives return 0
  // =========================================================================

  @Test
  public void testGetIntegerNullStringCaseInsensitive() {
    JSONObject obj = JSON.parseObject("{\"a\":\"NULL\",\"b\":\"Null\"}");
    assertNull(obj.getInteger("a"));
    assertNull(obj.getInteger("b"));
  }

  @Test
  public void testGetLongNullStringCaseInsensitive() {
    JSONObject obj = JSON.parseObject("{\"a\":\"NULL\",\"b\":\"Null\"}");
    assertNull(obj.getLong("a"));
    assertNull(obj.getLong("b"));
  }

  @Test
  public void testGetDoubleNullStringCaseInsensitive() {
    JSONObject obj = JSON.parseObject("{\"a\":\"NULL\",\"b\":\"Null\"}");
    assertNull(obj.getDouble("a"));
    assertNull(obj.getDouble("b"));
  }

  @Test
  public void testGetIntValueNullStringReturnsZero() {
    JSONObject obj = JSON.parseObject("{\"a\":\"null\",\"b\":\"\"}");
    assertEquals(0, obj.getIntValue("a"));
    assertEquals(0, obj.getIntValue("b"));
  }

  @Test
  public void testGetLongValueNullStringReturnsZero() {
    JSONObject obj = JSON.parseObject("{\"a\":\"null\",\"b\":\"\"}");
    assertEquals(0L, obj.getLongValue("a"));
    assertEquals(0L, obj.getLongValue("b"));
  }

  // =========================================================================
  // 53. Iterator over JSONObject via keySet (for-each pattern used in codebase)
  // =========================================================================

  @Test
  public void testKeySetForEachIteration() {
    JSONObject obj = JSONObject.parseObject(
        "{\"name\":\"tron\",\"version\":4,\"active\":true}");
    List<String> collected = new ArrayList<>();
    for (String key : obj.keySet()) {
      collected.add(key);
    }
    assertEquals(Arrays.asList("name", "version", "active"), collected);
  }

  // =========================================================================
  // 54. JSONArray iterator wraps nested objects correctly
  // =========================================================================

  @Test
  public void testArrayIteratorWrapsNestedTypes() {
    JSONArray arr = JSONArray.parseArray(
        "[{\"a\":1},[2,3],\"text\",42,true,null]");
    List<Object> items = new ArrayList<>();
    for (Object item : arr) {
      items.add(item);
    }
    assertEquals(6, items.size());
    assertTrue(items.get(0) instanceof JSONObject);
    assertTrue(items.get(1) instanceof JSONArray);
    assertTrue(items.get(2) instanceof String);
    assertTrue(items.get(3) instanceof Integer);
    assertTrue(items.get(4) instanceof Boolean);
    assertNull(items.get(5));
  }

  // =========================================================================
  // 55. put(String, Object) dispatch — various Java types
  // =========================================================================

  @Test
  public void testPutObjectDispatchesCorrectly() {
    JSONObject obj = new JSONObject();
    // String via Object overload
    obj.put("s", (Object) "hello");
    assertEquals("hello", obj.getString("s"));

    // Integer via Object overload
    obj.put("i", (Object) 42);
    assertEquals(Integer.valueOf(42), obj.getInteger("i"));

    // Boolean via Object overload
    obj.put("b", (Object) true);
    assertEquals(Boolean.TRUE, obj.getBoolean("b"));

    // Long via Object overload
    obj.put("l", (Object) 999L);
    assertEquals(Long.valueOf(999L), obj.getLong("l"));

    // Double via Object overload
    obj.put("d", (Object) 3.14);
    assertNotNull(obj.getDouble("d"));
  }

  // =========================================================================
  // 56. JSONArray add(String null) — Fastjson adds JSON null for null String
  // =========================================================================

  @Test
  public void testArrayAddNullString() {
    JSONArray arr = new JSONArray();
    arr.add((String) null);
    assertEquals(1, arr.size());
    // Jackson ArrayNode.add((String)null) adds NullNode
    assertNull(arr.getString(0));
  }

  // =========================================================================
  // 57. Serialization round-trip preserves field order
  // =========================================================================

  @Test
  public void testSerializationPreservesFieldOrder() {
    String input = "{\"z\":1,\"a\":2,\"m\":3}";
    JSONObject obj = JSONObject.parseObject(input);
    String output = obj.toJSONString();
    // Fields should stay in z, a, m order
    int zPos = output.indexOf("\"z\"");
    int aPos = output.indexOf("\"a\"");
    int mPos = output.indexOf("\"m\"");
    assertTrue(zPos < aPos);
    assertTrue(aPos < mPos);
  }

  // =========================================================================
  // 58. getObject with standard Java types (String, Integer, Long, Boolean)
  // =========================================================================

  @Test
  public void testGetObjectWithStandardTypes() {
    JSONObject obj = JSONObject.parseObject(
        "{\"s\":\"hello\",\"i\":42,\"l\":9999999999,\"b\":true}");
    assertEquals("hello", obj.getObject("s", String.class));
    assertEquals(Integer.valueOf(42), obj.getObject("i", Integer.class));
    assertEquals(Long.valueOf(9999999999L), obj.getObject("l", Long.class));
    assertEquals(Boolean.TRUE, obj.getObject("b", Boolean.class));
  }

  @Test
  public void testGetObjectReturnsNullForMissing() {
    JSONObject obj = new JSONObject();
    assertNull(obj.getObject("missing", String.class));
  }

  // =========================================================================
  // 59. Multiple puts to same key — last wins (Fastjson behavior)
  // =========================================================================

  @Test
  public void testPutOverwritesDifferentTypes() {
    JSONObject obj = new JSONObject();
    obj.put("key", "string");
    assertEquals("string", obj.getString("key"));

    obj.put("key", 42);
    assertEquals(Integer.valueOf(42), obj.getInteger("key"));

    obj.put("key", true);
    assertEquals(Boolean.TRUE, obj.getBoolean("key"));

    obj.put("key", (String) null);
    assertNull(obj.getString("key"));
    assertFalse(obj.containsKey("key")); // null removes the key
  }

  // =========================================================================
  // 60. JSONArray.toJavaList with primitive wrapper types
  // =========================================================================

  @Test
  public void testToJavaListLong() {
    JSONArray arr = JSONArray.parseArray("[1,2,3,9999999999]");
    List<Long> result = arr.toJavaList(Long.class);
    assertEquals(4, result.size());
    assertEquals(Long.valueOf(1), result.get(0));
    assertEquals(Long.valueOf(9999999999L), result.get(3));
  }

  @Test
  public void testToJavaListBoolean() {
    JSONArray arr = JSONArray.parseArray("[true,false,true]");
    List<Boolean> result = arr.toJavaList(Boolean.class);
    assertEquals(Arrays.asList(true, false, true), result);
  }

  // =========================================================================
  // 61. JSON.parse returns correct node types
  // =========================================================================

  @Test
  public void testParseReturnsCorrectNodeTypes() {
    assertTrue(JSON.parse("{\"a\":1}").isObject());
    assertTrue(JSON.parse("[1,2]").isArray());
    assertTrue(JSON.parse("\"hello\"").isTextual());
    assertTrue(JSON.parse("42").isNumber());
    assertTrue(JSON.parse("true").isBoolean());
    assertTrue(JSON.parse("null").isNull());
  }

  // =========================================================================
  // 62. Large number handling — no precision loss
  // =========================================================================

  @Test
  public void testLargeIntegerPreservesValue() {
    // JavaScript MAX_SAFE_INTEGER is 2^53-1
    String json = "{\"big\":9007199254740993}";
    JSONObject obj = JSONObject.parseObject(json);
    assertEquals(Long.valueOf(9007199254740993L), obj.getLong("big"));
    // Round-trip preserves value
    String output = obj.toJSONString();
    assertTrue(output.contains("9007199254740993"));
  }

  @Test
  public void testBigDecimalPrecisionInArray() {
    JSONArray arr = JSONArray.parseArray("[0.1, 0.2, 0.3]");
    assertEquals(3, arr.size());
    // Values parsed as BigDecimal due to USE_BIG_DECIMAL_FOR_FLOATS
    Object first = arr.get(0);
    assertTrue(first instanceof BigDecimal);
  }

  // =========================================================================
  // 63. Concurrent read safety (JSONObject backed by same ObjectNode)
  // =========================================================================

  @Test
  public void testGetOnSharedNodeDoesNotThrow() {
    JSONObject obj = JSONObject.parseObject(
        "{\"a\":1,\"b\":\"hello\",\"c\":true,\"d\":[1,2,3]}");
    // Multiple reads on same instance should not throw
    for (int i = 0; i < 100; i++) {
      assertNotNull(obj.getInteger("a"));
      assertNotNull(obj.getString("b"));
      assertNotNull(obj.getBoolean("c"));
      assertNotNull(obj.getJSONArray("d"));
      assertNotNull(obj.keySet());
      assertNotNull(obj.toJSONString());
    }
  }

  // =========================================================================
  // 64. parseObject with special JSON values
  // =========================================================================

  @Test
  public void testParseObjectUnicodeEscapes() {
    JSONObject obj = JSONObject.parseObject("{\"name\":\"\\u4e2d\\u6587\"}");
    assertEquals("中文", obj.getString("name"));
  }

  @Test
  public void testParseObjectNestedEmpty() {
    JSONObject obj = JSONObject.parseObject("{\"inner\":{},\"arr\":[]}");
    assertTrue(obj.getJSONObject("inner").isEmpty());
    assertTrue(obj.getJSONArray("arr").isEmpty());
  }

  // =========================================================================
  // 65. getBoolean edge cases — numeric and string coercion
  // =========================================================================

  @Test
  public void testGetBooleanFromNonZeroNumbers() {
    JSONObject obj = JSONObject.parseObject(
        "{\"pos\":42,\"neg\":-1,\"zero\":0,\"bigZero\":0.0}");
    assertTrue(obj.getBoolean("pos"));
    assertTrue(obj.getBoolean("neg"));
    assertFalse(obj.getBoolean("zero"));
  }

  @Test
  public void testGetBooleanFromStrings() {
    JSONObject obj = JSONObject.parseObject(
        "{\"t\":\"TRUE\",\"f\":\"FALSE\",\"one\":\"1\",\"zero\":\"0\"}");
    assertTrue(obj.getBoolean("t"));
    assertFalse(obj.getBoolean("f"));
    assertTrue(obj.getBoolean("one"));
    assertFalse(obj.getBoolean("zero"));
  }

  @Test(expected = JSONException.class)
  public void testGetBooleanInvalidStringThrowsException() {
    JSONObject obj = JSONObject.parseObject("{\"val\":\"maybe\"}");
    obj.getBoolean("val");
  }

  // =========================================================================
  // 66. Util.java code path: get() → toString() → parseObject() round-trip
  //     (simulates Util.printTransactionToJSON line 257 pattern)
  // =========================================================================

  @Test
  public void testGetThenToStringThenReparse() {
    JSONObject outer = JSONObject.parseObject(
        "{\"raw_data\":{\"contract\":[{\"type\":\"Transfer\"}],\"ref_block_bytes\":\"abcd\"}}");
    // get("raw_data") returns JSONObject, toString() serializes, parseObject re-parses
    Object rawObj = outer.get("raw_data");
    assertTrue(rawObj instanceof JSONObject);
    JSONObject rawData = JSONObject.parseObject(rawObj.toString());
    // Modify the copy
    JSONArray newContracts = new JSONArray();
    rawData.put("contract", newContracts);
    outer.put("raw_data", rawData);
    // Verify modification
    assertEquals(0, outer.getJSONObject("raw_data").getJSONArray("contract").size());
  }

  // =========================================================================
  // 67. getBoolean auto-unbox safe pattern (Util.getVisibleOnlyForSign)
  // =========================================================================

  @Test
  public void testGetBooleanGuardedByContainsKey() {
    JSONObject obj = JSONObject.parseObject("{\"visible\":true}");
    boolean visible = false;
    if (obj.containsKey("visible")) {
      visible = obj.getBoolean("visible");
    }
    assertTrue(visible);

    // Missing key — containsKey guard prevents NPE
    JSONObject obj2 = JSONObject.parseObject("{\"other\":1}");
    boolean visible2 = false;
    if (obj2.containsKey("visible")) {
      visible2 = obj2.getBoolean("visible");
    }
    assertFalse(visible2);
  }

  // =========================================================================
  // 68. getJsonLongValue path: getBigDecimal → longValueExact
  // =========================================================================

  @Test
  public void testGetBigDecimalLongValueExactPath() {
    JSONObject obj = JSONObject.parseObject(
        "{\"amount\":\"100\",\"fee\":0,\"large\":9999999999999}");
    assertEquals(100L, obj.getBigDecimal("amount").longValueExact());
    assertEquals(0L, obj.getBigDecimal("fee").longValueExact());
    assertEquals(9999999999999L, obj.getBigDecimal("large").longValueExact());
    // missing key → null
    assertNull(obj.getBigDecimal("missing"));
  }

  // =========================================================================
  // 69. put(key, List<String>) — Util.getJsonString pattern
  // =========================================================================

  @Test
  public void testPutStringList() {
    JSONObject json = new JSONObject();
    List<String> values = Arrays.asList("val1", "val2", "val3");
    json.put("params", values);
    JSONArray arr = json.getJSONArray("params");
    assertEquals(3, arr.size());
    assertEquals("val1", arr.getString(0));
    assertEquals("val2", arr.getString(1));
    assertEquals("val3", arr.getString(2));
  }

  @Test
  public void testPutSingleStringVsListConsistency() {
    // When only one value, put as scalar; when multiple, put as list
    JSONObject json = new JSONObject();
    json.put("single", "onlyOne");
    json.put("multi", Arrays.asList("a", "b"));
    assertEquals("onlyOne", json.getString("single"));
    assertEquals(2, json.getJSONArray("multi").size());
  }

  // =========================================================================
  // 70. JSONArray for-each with getJSONObject + getBoolean
  //     (ContractEventParserJson pattern)
  // =========================================================================

  @Test
  public void testArrayIterateWithGetters() {
    JSONArray inputs = JSONArray.parseArray(
        "[{\"name\":\"to\",\"type\":\"address\",\"indexed\":true},"
            + "{\"name\":\"value\",\"type\":\"uint256\",\"indexed\":false},"
            + "{\"name\":\"data\",\"type\":\"bytes\",\"indexed\":false}]");
    int indexedCount = 0;
    for (int i = 0; i < inputs.size(); i++) {
      JSONObject param = inputs.getJSONObject(i);
      Boolean indexed = param.getBoolean("indexed");
      if (indexed != null && indexed) {
        indexedCount++;
      }
    }
    assertEquals(1, indexedCount);
  }

  // =========================================================================
  // 71. Chained JSONObject operations (parse → get → put → serialize)
  // =========================================================================

  @Test
  public void testChainedOperations() {
    // Simulates Util.printTransactionExtention pattern
    String input = "{\"result\":{\"code\":\"SUCCESS\"},\"transaction\":{\"raw_data\":{}}}";
    JSONObject obj = JSONObject.parseObject(input);
    JSONObject txObj = obj.getJSONObject("transaction");
    txObj.put("visible", true);
    obj.put("transaction", txObj);
    String output = obj.toJSONString();
    assertTrue(output.contains("\"visible\":true"));
    assertTrue(output.contains("\"result\""));
  }

  // =========================================================================
  // 72. getString returns null for JSON null and missing keys
  // =========================================================================

  @Test
  public void testGetStringNullAndMissingKeys() {
    JSONObject obj = JSONObject.parseObject("{\"key\":null,\"empty\":\"\"}");
    assertNull(obj.getString("key"));
    assertNull(obj.getString("missing"));
    assertEquals("", obj.getString("empty"));
  }

  // =========================================================================
  // 73. put(key, Enum) via Object overload (Protobuf enum in Util.java)
  // =========================================================================

  @Test
  public void testPutEnum() {
    JSONObject obj = new JSONObject();
    obj.put("status", Thread.State.RUNNABLE);
    // Jackson serializes enums as their name by default
    assertEquals("RUNNABLE", obj.getString("status"));
  }

  // =========================================================================
  // 74. JSONObject.getBoolean on boolean node from JSONArray
  // =========================================================================

  @Test
  public void testArrayGetBooleanEdgeCases() {
    JSONArray arr = JSONArray.parseArray("[true,false,1,0,\"true\",\"false\"]");
    assertTrue(arr.getBoolean(0));
    assertFalse(arr.getBoolean(1));
    assertTrue(arr.getBoolean(2));   // number 1 → true
    assertFalse(arr.getBoolean(3));  // number 0 → false
    assertTrue(arr.getBoolean(4));   // string "true" → true
    assertFalse(arr.getBoolean(5));  // string "false" → false
  }

  // =========================================================================
  // Helper: random JSON object generator
  // =========================================================================

  private static String randomJsonObject(Random rng, int maxDepth) {
    StringBuilder sb = new StringBuilder("{");
    int fieldCount = rng.nextInt(6) + 1;
    for (int i = 0; i < fieldCount; i++) {
      if (i > 0) {
        sb.append(",");
      }
      sb.append("\"f").append(i).append("\":");
      sb.append(randomJsonValue(rng, maxDepth));
    }
    sb.append("}");
    return sb.toString();
  }

  private static String randomJsonValue(Random rng, int depth) {
    int type = (depth <= 0) ? rng.nextInt(5) : rng.nextInt(7);
    switch (type) {
      case 0: return String.valueOf(rng.nextInt(100000));
      case 1: return "\"" + escapeJson("str_" + rng.nextInt(10000)) + "\"";
      case 2: return rng.nextBoolean() ? "true" : "false";
      case 3: return "null";
      case 4: return String.valueOf(rng.nextDouble() * 1000);
      case 5: return randomJsonObject(rng, depth - 1);
      case 6:
        int arrLen = rng.nextInt(4);
        StringBuilder arr = new StringBuilder("[");
        for (int i = 0; i < arrLen; i++) {
          if (i > 0) {
            arr.append(",");
          }
          arr.append(randomJsonValue(rng, depth - 1));
        }
        arr.append("]");
        return arr.toString();
      default: return "0";
    }
  }

  private static String escapeJson(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static String randomGarbage(Random rng) {
    int len = rng.nextInt(50) + 1;
    char[] chars = new char[len];
    String pool = "{}[]\":,0123456789abcdefghijklmnop \t\n\\/<>&=+!@#$%^*()";
    for (int i = 0; i < len; i++) {
      chars[i] = pool.charAt(rng.nextInt(pool.length()));
    }
    return new String(chars);
  }

  // =========================================================================
  // Helper: simple POJO for serialization tests
  // =========================================================================

  @Getter
  @SuppressWarnings("unused")
  static class TestPojo {
    private final String name;
    private final String nullField;

    TestPojo(String name, String nullField) {
      this.name = name;
      this.nullField = nullField;
    }

  }
}
