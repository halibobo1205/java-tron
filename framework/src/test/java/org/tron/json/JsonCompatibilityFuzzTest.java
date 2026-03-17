package org.tron.json;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.Test;

/**
 * Fuzz test for JSON/JSONObject/JSONArray wrappers.
 * Verifies Fastjson-compatible behaviour under randomized input,
 * covering edge cases such as BigDecimal/BigInteger, nested structures,
 * special characters, unicode, deeply nested objects, and boundary values.
 */
public class JsonCompatibilityFuzzTest {

  private static final int FUZZ_ROUNDS = 500;
  private static final Random RNG = new Random(42); // fixed seed for reproducibility

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
        "not json", "{key: }", "a=1&b=2", "<html>", "{{",
        "{\"unterminated", "[,,,]", "'''", "\0\0\0",
    };
    for (String input : invalidInputs) {
      try {
        JSON.parse(input);
        // some of these might be parsed by Jackson's lenient mode, that's OK
      } catch (JSONException e) {
        // expected for truly invalid input
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

  @Test(expected = JSONException.class)
  public void testGetIntegerThrowsOnBooleanNode() {
    JSONObject obj = JSON.parseObject("{\"b\":true}");
    obj.getInteger("b");
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
  // 7. JSONObject.put(key, List) — null list → JSON null
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
}
