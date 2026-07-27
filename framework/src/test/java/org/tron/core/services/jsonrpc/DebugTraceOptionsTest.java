package org.tron.core.services.jsonrpc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.tron.core.exception.jsonrpc.JsonRpcInvalidParamsException;

public class DebugTraceOptionsTest {

  @Test
  public void parsesDefaultStructLoggerOptions() throws Exception {
    Map<String, Object> raw = new HashMap<>();
    raw.put("enableMemory", true);
    raw.put("disableStorage", true);
    raw.put("limit", 123);

    DebugTraceOptions options = DebugTraceOptions.parse(raw);

    assertEquals(DebugTraceOptions.Kind.STRUCT_LOGS, options.getKind());
    assertTrue(options.isEnableMemory());
    assertTrue(options.isDisableStorage());
    assertFalse(options.isDisableStack());
    assertEquals(123, options.getLimit());
  }

  @Test
  public void parsesCallTracerOptions() throws Exception {
    Map<String, Object> tracerConfig = new HashMap<>();
    tracerConfig.put("onlyTopCall", true);
    tracerConfig.put("includePrecompiles", false);
    Map<String, Object> raw = new HashMap<>();
    raw.put("tracer", "callTracer");
    raw.put("tracerConfig", tracerConfig);

    DebugTraceOptions options = DebugTraceOptions.parse(raw);

    assertEquals(DebugTraceOptions.Kind.CALL_TRACER, options.getKind());
    assertTrue(options.isOnlyTopCall());
    assertFalse(options.isIncludePrecompiles());
  }

  @Test
  public void rejectsUnsupportedOrAmbiguousOptions() {
    assertInvalid(singleton("tracer", "javascriptTracer"));
    assertInvalid(singleton("timeout", "5s"));
    assertInvalid(singleton("limit", 1.5d));

    Map<String, Object> tracerConfig = new HashMap<>();
    tracerConfig.put("withLog", true);
    Map<String, Object> callTracer = new HashMap<>();
    callTracer.put("tracer", "callTracer");
    callTracer.put("tracerConfig", tracerConfig);
    assertInvalid(callTracer);
  }

  private static Map<String, Object> singleton(String key, Object value) {
    Map<String, Object> values = new HashMap<>();
    values.put(key, value);
    return values;
  }

  private static void assertInvalid(Object options) {
    assertThrows(JsonRpcInvalidParamsException.class,
        () -> DebugTraceOptions.parse(options));
  }
}
