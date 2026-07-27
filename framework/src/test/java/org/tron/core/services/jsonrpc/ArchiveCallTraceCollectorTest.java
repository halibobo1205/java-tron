package org.tron.core.services.jsonrpc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.math.BigInteger;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.tron.core.archive.query.HistoricalQueryLimitException;
import org.tron.core.services.jsonrpc.types.CallTraceFrame;
import org.tron.core.vm.Op;
import org.tron.core.vm.trace.VmCallTraceCollector.TraceScope;

public class ArchiveCallTraceCollectorTest {

  @Test
  public void buildsNestedFramesInExecutionOrder() throws Exception {
    Map<String, Object> raw = new HashMap<>();
    raw.put("tracer", "callTracer");
    ArchiveCallTraceCollector collector = new ArchiveCallTraceCollector(
        DebugTraceOptions.parse(raw), new DebugTraceBudget(100_000L));

    TraceScope root = collector.enter(
        Op.CALL, address(1), address(2), new byte[] {0x01}, 1_000L,
        BigInteger.TEN, false);
    TraceScope child = collector.enter(
        Op.STATICCALL, address(2), address(3), new byte[] {0x02}, 500L,
        BigInteger.ZERO, false);
    child.complete(new byte[] {0x03}, 25L, false, null);
    child.close();
    root.complete(new byte[] {0x04}, 100L, false, null);
    root.close();

    CallTraceFrame frame = collector.getRoot();
    assertEquals("CALL", frame.getType());
    assertEquals("0x3e8", frame.getGas());
    assertEquals("0x64", frame.getGasUsed());
    assertEquals("0xa", frame.getValue());
    assertEquals(1, frame.getCalls().size());
    CallTraceFrame nested = frame.getCalls().get(0);
    assertEquals("STATICCALL", nested.getType());
    assertEquals("0x19", nested.getGasUsed());
    assertNull(nested.getValue());
  }

  @Test
  public void honorsOnlyTopCallAndPrecompileExclusion() throws Exception {
    Map<String, Object> tracerConfig = new HashMap<>();
    tracerConfig.put("onlyTopCall", true);
    tracerConfig.put("includePrecompiles", false);
    Map<String, Object> raw = new HashMap<>();
    raw.put("tracer", "callTracer");
    raw.put("tracerConfig", tracerConfig);
    ArchiveCallTraceCollector collector = new ArchiveCallTraceCollector(
        DebugTraceOptions.parse(raw), new DebugTraceBudget(100_000L));

    TraceScope root = collector.enter(
        Op.CALL, address(1), address(2), new byte[0], 100L,
        BigInteger.ZERO, false);
    TraceScope child = collector.enter(
        Op.CALL, address(2), address(3), new byte[0], 50L,
        BigInteger.ZERO, false);
    child.complete(new byte[0], 0L, false, null);
    child.close();
    TraceScope precompile = collector.enter(
        Op.STATICCALL, address(2), address(4), new byte[0], 25L,
        BigInteger.ZERO, true);
    precompile.complete(new byte[0], 0L, false, null);
    precompile.close();
    root.complete(new byte[0], 1L, false, null);
    root.close();

    assertNull(collector.getRoot().getCalls());
  }

  @Test
  public void delegateValueAndFailureFieldsMatchNativeCallTracerShape() throws Exception {
    Map<String, Object> raw = new HashMap<>();
    raw.put("tracer", "callTracer");
    ArchiveCallTraceCollector delegateCollector = new ArchiveCallTraceCollector(
        DebugTraceOptions.parse(raw), new DebugTraceBudget(100_000L));

    TraceScope delegate = delegateCollector.enter(
        Op.DELEGATECALL, address(1), address(2), new byte[0], 100L,
        BigInteger.valueOf(7L), false);
    delegate.complete(new byte[0], 5L, false, null);
    delegate.close();

    assertEquals("0x7", delegateCollector.getRoot().getValue());
    assertNull(delegateCollector.getRoot().getOutput());

    ArchiveCallTraceCollector createCollector = new ArchiveCallTraceCollector(
        DebugTraceOptions.parse(raw), new DebugTraceBudget(100_000L));
    TraceScope create = createCollector.enter(
        Op.CREATE, address(1), address(2), new byte[0], 100L,
        BigInteger.ZERO, false);
    create.complete(new byte[0], 100L, false, "execution failed");
    create.close();

    assertNull(createCollector.getRoot().getTo());
    assertNull(createCollector.getRoot().getOutput());
    assertEquals("execution failed", createCollector.getRoot().getError());
  }

  @Test
  public void materializationBudgetFailsBeforeCopyingFrameData() throws Exception {
    Map<String, Object> raw = new HashMap<>();
    raw.put("tracer", "callTracer");
    ArchiveCallTraceCollector collector = new ArchiveCallTraceCollector(
        DebugTraceOptions.parse(raw), new DebugTraceBudget(1L));

    HistoricalQueryLimitException failure = assertThrows(
        HistoricalQueryLimitException.class,
        () -> collector.enter(Op.CALL, address(1), address(2), new byte[0],
            100L, BigInteger.ZERO, false));

    assertEquals(HistoricalQueryLimitException.Limit.RESPONSE_BYTES, failure.getLimit());
  }

  @Test
  public void defaultOptionsUseStructLogger() throws Exception {
    assertEquals(DebugTraceOptions.Kind.STRUCT_LOGS,
        DebugTraceOptions.parse(Collections.emptyMap()).getKind());
  }

  private static byte[] address(int suffix) {
    byte[] address = new byte[21];
    address[0] = 0x41;
    address[address.length - 1] = (byte) suffix;
    return address;
  }
}
