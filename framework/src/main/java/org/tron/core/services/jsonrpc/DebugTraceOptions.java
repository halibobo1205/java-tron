package org.tron.core.services.jsonrpc;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import org.tron.core.exception.jsonrpc.JsonRpcInvalidParamsException;

/** Strict parser for the V1 subset of Geth/Erigon debug trace options. */
final class DebugTraceOptions {

  private static final Set<String> COMMON_KEYS =
      setOf("tracer", "tracerConfig", "enableMemory", "disableStack",
          "disableStorage", "enableReturnData", "limit");
  private static final Set<String> CALL_TRACER_KEYS =
      setOf("onlyTopCall", "withLog", "includePrecompiles");

  enum Kind {
    STRUCT_LOGS,
    CALL_TRACER
  }

  private final Kind kind;
  private final boolean enableMemory;
  private final boolean disableStack;
  private final boolean disableStorage;
  private final boolean enableReturnData;
  private final int limit;
  private final boolean onlyTopCall;
  private final boolean includePrecompiles;

  private DebugTraceOptions(Kind kind, boolean enableMemory, boolean disableStack,
      boolean disableStorage, boolean enableReturnData, int limit, boolean onlyTopCall,
      boolean includePrecompiles) {
    this.kind = kind;
    this.enableMemory = enableMemory;
    this.disableStack = disableStack;
    this.disableStorage = disableStorage;
    this.enableReturnData = enableReturnData;
    this.limit = limit;
    this.onlyTopCall = onlyTopCall;
    this.includePrecompiles = includePrecompiles;
  }

  static DebugTraceOptions parse(Object rawOptions) throws JsonRpcInvalidParamsException {
    if (rawOptions == null) {
      return structLogs(Collections.emptyMap());
    }
    if (!(rawOptions instanceof Map)) {
      throw invalid("trace options must be an object");
    }
    Map<?, ?> options = (Map<?, ?>) rawOptions;
    requireStringKeys(options);
    requireOnlyKeys(options, COMMON_KEYS, "trace options");
    Object tracerValue = options.get("tracer");
    if (tracerValue == null) {
      return structLogs(options);
    }
    if (!(tracerValue instanceof String)) {
      throw invalid("tracer must be a string");
    }
    if (!"callTracer".equals(tracerValue)) {
      throw invalid("unsupported tracer: " + tracerValue);
    }
    return callTracer(options);
  }

  private static DebugTraceOptions structLogs(Map<?, ?> options)
      throws JsonRpcInvalidParamsException {
    if (options.get("tracerConfig") != null) {
      throw invalid("tracerConfig requires callTracer");
    }
    return new DebugTraceOptions(
        Kind.STRUCT_LOGS,
        booleanOption(options, "enableMemory", false),
        booleanOption(options, "disableStack", false),
        booleanOption(options, "disableStorage", false),
        booleanOption(options, "enableReturnData", false),
        intOption(options, "limit", 0),
        false,
        true);
  }

  private static DebugTraceOptions callTracer(Map<?, ?> options)
      throws JsonRpcInvalidParamsException {
    rejectStructLoggerOption(options, "enableMemory");
    rejectStructLoggerOption(options, "disableStack");
    rejectStructLoggerOption(options, "disableStorage");
    rejectStructLoggerOption(options, "enableReturnData");
    rejectStructLoggerOption(options, "limit");

    Object rawTracerConfig = options.get("tracerConfig");
    Map<?, ?> tracerConfig;
    if (rawTracerConfig == null) {
      tracerConfig = Collections.emptyMap();
    } else if (rawTracerConfig instanceof Map) {
      tracerConfig = (Map<?, ?>) rawTracerConfig;
    } else {
      throw invalid("callTracer tracerConfig must be an object");
    }
    requireStringKeys(tracerConfig);
    requireOnlyKeys(tracerConfig, CALL_TRACER_KEYS, "callTracer tracerConfig");
    if (booleanOption(tracerConfig, "withLog", false)) {
      throw invalid("callTracer withLog=true is not supported");
    }
    return new DebugTraceOptions(
        Kind.CALL_TRACER,
        false,
        true,
        true,
        false,
        0,
        booleanOption(tracerConfig, "onlyTopCall", false),
        booleanOption(tracerConfig, "includePrecompiles", true));
  }

  private static void rejectStructLoggerOption(Map<?, ?> options, String key)
      throws JsonRpcInvalidParamsException {
    if (options.containsKey(key)) {
      throw invalid(key + " is only valid for the default struct logger");
    }
  }

  private static boolean booleanOption(Map<?, ?> options, String key, boolean defaultValue)
      throws JsonRpcInvalidParamsException {
    Object value = options.get(key);
    if (value == null) {
      return defaultValue;
    }
    if (!(value instanceof Boolean)) {
      throw invalid(key + " must be a boolean");
    }
    return (Boolean) value;
  }

  private static int intOption(Map<?, ?> options, String key, int defaultValue)
      throws JsonRpcInvalidParamsException {
    Object value = options.get(key);
    if (value == null) {
      return defaultValue;
    }
    if (!(value instanceof Number)) {
      throw invalid(key + " must be a non-negative integer");
    }
    try {
      long parsed = new BigDecimal(value.toString()).longValueExact();
      if (parsed < 0L || parsed > Integer.MAX_VALUE) {
        throw invalid(key + " is outside the supported range");
      }
      return (int) parsed;
    } catch (ArithmeticException | NumberFormatException failure) {
      throw invalid(key + " must be a non-negative integer");
    }
  }

  private static void requireStringKeys(Map<?, ?> values)
      throws JsonRpcInvalidParamsException {
    for (Object key : values.keySet()) {
      if (!(key instanceof String)) {
        throw invalid("trace option keys must be strings");
      }
    }
  }

  private static void requireOnlyKeys(Map<?, ?> values, Set<String> allowed, String label)
      throws JsonRpcInvalidParamsException {
    for (Object key : values.keySet()) {
      if (!allowed.contains(key)) {
        throw invalid(label + " contains unsupported key: " + key);
      }
    }
  }

  private static Set<String> setOf(String... values) {
    java.util.HashSet<String> set = new java.util.HashSet<>();
    Collections.addAll(set, values);
    return Collections.unmodifiableSet(set);
  }

  private static JsonRpcInvalidParamsException invalid(String message) {
    return new JsonRpcInvalidParamsException(message);
  }

  Kind getKind() {
    return kind;
  }

  boolean isEnableMemory() {
    return enableMemory;
  }

  boolean isDisableStack() {
    return disableStack;
  }

  boolean isDisableStorage() {
    return disableStorage;
  }

  boolean isEnableReturnData() {
    return enableReturnData;
  }

  int getLimit() {
    return limit;
  }

  boolean isOnlyTopCall() {
    return onlyTopCall;
  }

  boolean isIncludePrecompiles() {
    return includePrecompiles;
  }
}
