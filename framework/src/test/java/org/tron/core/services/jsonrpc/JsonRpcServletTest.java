package org.tron.core.services.jsonrpc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.googlecode.jsonrpc4j.JsonRpcServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.tron.common.parameter.CommonParameter;
import org.tron.core.Constant;
import org.tron.core.archive.query.ArchiveQueryCoordinator;
import org.tron.core.archive.query.ArchiveQueryLimits;
import org.tron.core.archive.query.ArchiveQueryTransportScope;
import org.tron.core.archive.query.HistoricalQueryLimitException;
import org.tron.core.archive.query.QueryLease;

public class JsonRpcServletTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private TestableServlet servlet;
  private JsonRpcServer mockRpcServer;
  private int savedMaxBatchSize;
  private int savedMaxResponseSize;

  @Before
  public void setUp() throws Exception {
    servlet = new TestableServlet();
    mockRpcServer = mock(JsonRpcServer.class);
    Field f = JsonRpcServlet.class.getDeclaredField("rpcServer");
    f.setAccessible(true);
    f.set(servlet, mockRpcServer);
    savedMaxBatchSize = CommonParameter.getInstance().jsonRpcMaxBatchSize;
    savedMaxResponseSize = CommonParameter.getInstance().jsonRpcMaxResponseSize;
  }

  @After
  public void tearDown() {
    CommonParameter.getInstance().jsonRpcMaxBatchSize = savedMaxBatchSize;
    CommonParameter.getInstance().jsonRpcMaxResponseSize = savedMaxResponseSize;
  }

  // --- parse error paths ---

  @Test
  public void invalidJson_returnsParseError() throws Exception {
    MockHttpServletResponse resp = doPost("not {{ valid json");
    assertEquals(200, resp.getStatus());
    JsonNode body = MAPPER.readTree(resp.getContentAsString());
    assertFalse(body.isArray());
    assertEquals(-32700, body.get("error").get("code").asInt());
    // A non-constraint JsonProcessingException keeps the generic message (else branch).
    assertEquals("JSON parse error", body.get("error").get("message").asText());
    assertEquals("2.0", body.get("jsonrpc").asText());
    assertTrue(body.get("id").isNull());
  }

  @Test
  public void emptyBody_returnsParseError() throws Exception {
    MockHttpServletResponse resp = doPost("");
    assertEquals(200, resp.getStatus());
    JsonNode body = MAPPER.readTree(resp.getContentAsString());
    assertEquals(-32700, body.get("error").get("code").asInt());
  }

  // --- batch size limit ---

  @Test
  public void batchExceedsLimit_returnsExceedLimitAsArray() throws Exception {
    CommonParameter.getInstance().jsonRpcMaxBatchSize = 2;
    MockHttpServletResponse resp = doPost("[{\"id\":1},{\"id\":2},{\"id\":3}]");
    assertEquals(200, resp.getStatus());
    JsonNode body = MAPPER.readTree(resp.getContentAsString());
    assertTrue("batch error response must be a JSON array", body.isArray());
    assertEquals(1, body.size());
    assertEquals(-32005, body.get(0).get("error").get("code").asInt());
  }

  @Test
  public void batchWithinLimit_proceedsToRpcServer() throws Exception {
    CommonParameter.getInstance().jsonRpcMaxBatchSize = 5;
    byte[] singleResp = "{\"jsonrpc\":\"2.0\",\"result\":\"ok\",\"id\":1}"
        .getBytes(StandardCharsets.UTF_8);
    doAnswer(inv -> {
      OutputStream out = inv.getArgument(1);
      out.write(singleResp);
      return 0;
    }).when(mockRpcServer).handleRequest(any(InputStream.class), any(OutputStream.class));

    MockHttpServletResponse resp = doPost("[{\"id\":1},{\"id\":2}]");
    assertEquals(200, resp.getStatus());
    JsonNode body = MAPPER.readTree(resp.getContentAsByteArray());
    assertTrue("batch response must be a JSON array", body.isArray());
    assertEquals("each sub-request must produce a response", 2, body.size());
    assertEquals("ok", body.get(0).get("result").asText());
  }

  @Test
  public void emptyBatch_returnsInvalidRequest() throws Exception {
    MockHttpServletResponse resp = doPost("[]");
    assertEquals(200, resp.getStatus());
    JsonNode body = MAPPER.readTree(resp.getContentAsString());
    assertFalse("empty-batch error response must be a single object, not an array", body.isArray());
    assertEquals(-32600, body.get("error").get("code").asInt());
    assertEquals("2.0", body.get("jsonrpc").asText());
    assertTrue(body.get("id").isNull());
  }

  @Test
  public void batchLimitDisabled_largeBatchAllowed() throws Exception {
    CommonParameter.getInstance().jsonRpcMaxBatchSize = 0;
    // write nothing — simulates notifications (no response expected)
    doAnswer(inv -> 0).when(mockRpcServer)
        .handleRequest(any(InputStream.class), any(OutputStream.class));

    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < 500; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append("{}");
    }
    sb.append("]");
    MockHttpServletResponse resp = doPost(sb.toString());
    assertEquals(200, resp.getStatus());
    assertEquals("all-notification batch must return empty body per JSON-RPC 2.0 §6",
        0, resp.getContentLength());
    assertEquals("", resp.getContentAsString());
  }

  // --- rpcServer.handle exceptions ---

  @Test
  public void rpcServerThrowsRuntimeException_returnsInternalError() throws Exception {
    doThrow(new RuntimeException("server exploded")).when(mockRpcServer)
        .handleRequest(any(InputStream.class), any(OutputStream.class));
    MockHttpServletResponse resp = doPost("{\"method\":\"eth_blockNumber\",\"id\":42}");
    assertEquals(200, resp.getStatus());
    JsonNode body = MAPPER.readTree(resp.getContentAsString());
    assertFalse(body.isArray());
    assertEquals(-32603, body.get("error").get("code").asInt());
  }

  @Test
  public void rpcServerFailureReleasesArchivePermitBeforeWritingError() throws Exception {
    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator();
    doAnswer(inv -> {
      QueryLease lease = coordinator.acquire();
      ArchiveQueryTransportScope.closeAfterResponse(lease);
      throw new RuntimeException("server exploded");
    }).when(mockRpcServer).handleRequest(any(InputStream.class), any(OutputStream.class));
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/jsonrpc");
    req.setContent("{\"method\":\"eth_getBalance\",\"id\":42}"
        .getBytes(StandardCharsets.UTF_8));
    MockHttpServletResponse resp = new MockHttpServletResponse() {
      @Override
      public ServletOutputStream getOutputStream() {
        assertEquals(0, coordinator.getActiveLeaseCount());
        return super.getOutputStream();
      }
    };

    servlet.callDoPost(req, resp);

    assertEquals(0, coordinator.getActiveLeaseCount());
    assertEquals(-32603,
        MAPPER.readTree(resp.getContentAsString()).get("error").get("code").asInt());
  }

  @Test
  public void batchRpcServerThrows_internalErrorIsArray() throws Exception {
    doThrow(new RuntimeException("boom")).when(mockRpcServer)
        .handleRequest(any(InputStream.class), any(OutputStream.class));
    MockHttpServletResponse resp = doPost(
        "[{\"method\":\"eth_blockNumber\",\"id\":1}]");
    assertEquals(200, resp.getStatus());
    JsonNode body = MAPPER.readTree(resp.getContentAsString());
    assertTrue("batch internal error must be an array", body.isArray());
    assertEquals(-32603, body.get(0).get("error").get("code").asInt());
  }

  @Test
  public void singleNotificationOuterFailureReturnsNoResponse() throws Exception {
    doThrow(new RuntimeException("boom")).when(mockRpcServer)
        .handleRequest(any(InputStream.class), any(OutputStream.class));

    MockHttpServletResponse resp = doPost(
        "{\"jsonrpc\":\"2.0\",\"method\":\"eth_blockNumber\"}");

    assertEquals(0, resp.getContentAsByteArray().length);
  }

  @Test
  public void batchNotificationDeadlineFailureReturnsNoResponse() throws Exception {
    HistoricalQueryLimitException deadline = new HistoricalQueryLimitException(
        HistoricalQueryLimitException.Reason.DEADLINE,
        HistoricalQueryLimitException.Limit.BATCH_DEADLINE,
        "batch deadline");
    doThrow(deadline).when(mockRpcServer)
        .handleRequest(any(InputStream.class), any(OutputStream.class));

    MockHttpServletResponse resp = doPost(
        "[{\"jsonrpc\":\"2.0\",\"method\":\"eth_blockNumber\"}]");

    assertEquals(0, resp.getContentAsByteArray().length);
  }

  // --- response size limit ---

  @Test
  public void responseTooLarge_returnsSingleErrorObject() throws Exception {
    int limit = 256;
    CommonParameter.getInstance().jsonRpcMaxResponseSize = limit;
    doAnswer(inv -> {
      OutputStream out = inv.getArgument(1);
      out.write(new byte[limit + 1]);
      return 0;
    }).when(mockRpcServer).handleRequest(any(InputStream.class), any(OutputStream.class));

    MockHttpServletResponse resp = doPost("{\"method\":\"eth_getLogs\",\"id\":1}");
    assertEquals(200, resp.getStatus());
    JsonNode body = MAPPER.readTree(resp.getContentAsString());
    assertFalse(body.isArray());
    assertEquals(-32003, body.get("error").get("code").asInt());
  }

  @Test
  public void realJsonRpcServerSingleResponseIsBoundedDuringSerialization() throws Exception {
    int limit = 256;
    CommonParameter.getInstance().jsonRpcMaxResponseSize = limit;
    setRpcServer(new JsonRpcServer(
        JsonRpcServlet.buildRpcMapper(), new LargeResponseApiImpl(), LargeResponseApi.class));

    MockHttpServletResponse resp = doPost(
        "{\"jsonrpc\":\"2.0\",\"method\":\"large\",\"params\":[],\"id\":1}");

    assertTrue(resp.getContentAsByteArray().length <= limit);
    assertEquals(-32003,
        MAPPER.readTree(resp.getContentAsByteArray()).get("error").get("code").asInt());
  }

  @Test
  public void realJsonRpcServerStopsLazyCollectionSerializationAtTheLimit() throws Exception {
    int limit = 256;
    CommonParameter.getInstance().jsonRpcMaxResponseSize = limit;
    LargeIterableApiImpl api = new LargeIterableApiImpl();
    setRpcServer(new JsonRpcServer(
        JsonRpcServlet.buildRpcMapper(), api, LargeIterableApi.class));

    MockHttpServletResponse resp = doPost(
        "{\"jsonrpc\":\"2.0\",\"method\":\"largeIterable\",\"params\":[],\"id\":1}");

    assertEquals(-32003,
        MAPPER.readTree(resp.getContentAsByteArray()).get("error").get("code").asInt());
    assertTrue("bounded streaming must stop before materializing the full result",
        api.emitted.get() < 1_000);
  }

  @Test
  public void batchResponseTooLarge_returnsErrorArray() throws Exception {
    int limit = 256;
    CommonParameter.getInstance().jsonRpcMaxResponseSize = limit;
    doAnswer(inv -> {
      OutputStream out = inv.getArgument(1);
      out.write(new byte[limit + 1]);
      return 0;
    }).when(mockRpcServer).handleRequest(any(InputStream.class), any(OutputStream.class));

    MockHttpServletResponse resp = doPost(
        "[{\"method\":\"eth_getLogs\",\"id\":1}]");
    assertEquals(200, resp.getStatus());
    JsonNode body = MAPPER.readTree(resp.getContentAsString());
    assertTrue("batch response-too-large must be an array", body.isArray());
    assertEquals(-32003, body.get(0).get("error").get("code").asInt());
  }

  @Test
  public void batchShortCircuitsOnOverflow() throws Exception {
    int limit = 256;
    CommonParameter.getInstance().jsonRpcMaxResponseSize = limit;
    int[] callCount = {0};
    doAnswer(inv -> {
      OutputStream out = inv.getArgument(1);
      callCount[0]++;
      if (callCount[0] == 1) {
        out.write("{\"result\":\"ok\"}".getBytes(StandardCharsets.UTF_8));
      } else {
        out.write(new byte[limit]); // triggers overflow when added to accumulated size
      }
      return 0;
    }).when(mockRpcServer).handleRequest(any(InputStream.class), any(OutputStream.class));

    MockHttpServletResponse resp = doPost("[{\"id\":1},{\"id\":2},{\"id\":3}]");
    assertEquals(200, resp.getStatus());
    JsonNode body = MAPPER.readTree(resp.getContentAsString());
    assertTrue("overflow response must be an array", body.isArray());
    // Once the immutable batch cannot fit, discard partial successes and return one bounded error.
    assertEquals(1, body.size());
    assertEquals(-32003, body.get(0).get("error").get("code").asInt());
    assertEquals(2, body.get(0).get("id").asInt());
    assertEquals("third sub-request must not be executed after overflow", 2, callCount[0]);
  }

  @Test
  public void batchResponseNeverExceedsEvenAnImpossiblySmallConfiguredLimit() throws Exception {
    int limit = 50;
    CommonParameter.getInstance().jsonRpcMaxResponseSize = limit;
    doAnswer(inv -> {
      OutputStream out = inv.getArgument(1);
      out.write(new byte[1024 * 1024]);
      return 0;
    }).when(mockRpcServer).handleRequest(any(InputStream.class), any(OutputStream.class));

    MockHttpServletResponse resp = doPost("[{\"id\":1},{\"id\":2},{\"id\":3}]");

    assertTrue(resp.getContentAsByteArray().length <= limit);
  }

  @Test
  public void slowClientsCannotRetainMoreThanTheGlobalResponseByteBudget() throws Exception {
    int limit = 256;
    CommonParameter.getInstance().jsonRpcMaxResponseSize = limit;
    doAnswer(inv -> {
      OutputStream output = inv.getArgument(1);
      output.write(new byte[200]);
      return 0;
    }).when(mockRpcServer).handleRequest(any(InputStream.class), any(OutputStream.class));

    CountDownLatch enteredNetworkWrite = new CountDownLatch(2);
    CountDownLatch releaseNetworkWrite = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    Future<?> first = executor.submit(
        () -> postTo(new BlockingResponse(enteredNetworkWrite, releaseNetworkWrite), 1));
    Future<?> second = executor.submit(
        () -> postTo(new BlockingResponse(enteredNetworkWrite, releaseNetworkWrite), 2));
    try {
      assertTrue(enteredNetworkWrite.await(5, TimeUnit.SECONDS));

      MockHttpServletResponse third = doPost(
          "{\"method\":\"eth_getBalance\",\"id\":3}");

      assertEquals(-32005,
          MAPPER.readTree(third.getContentAsByteArray()).get("error").get("code").asInt());
    } finally {
      releaseNetworkWrite.countDown();
      first.get(5, TimeUnit.SECONDS);
      second.get(5, TimeUnit.SECONDS);
      executor.shutdownNow();
    }
  }

  @Test
  public void concurrentBatchConstructionUsesGlobalResponseByteBudget() throws Exception {
    int limit = 256;
    CommonParameter.getInstance().jsonRpcMaxResponseSize = limit;
    doAnswer(inv -> {
      OutputStream output = inv.getArgument(1);
      output.write(new byte[200]);
      return 0;
    }).when(mockRpcServer).handleRequest(any(InputStream.class), any(OutputStream.class));

    CountDownLatch enteredNetworkWrite = new CountDownLatch(2);
    CountDownLatch releaseNetworkWrite = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    Future<?> first = executor.submit(
        () -> postBatchTo(new BlockingResponse(enteredNetworkWrite, releaseNetworkWrite), 1));
    Future<?> second = executor.submit(
        () -> postBatchTo(new BlockingResponse(enteredNetworkWrite, releaseNetworkWrite), 2));
    try {
      assertTrue(enteredNetworkWrite.await(5, TimeUnit.SECONDS));

      MockHttpServletResponse third = doPost("[{\"id\":3}]");

      JsonNode body = MAPPER.readTree(third.getContentAsByteArray());
      assertTrue(body.isArray());
      assertEquals(-32005, body.get(0).get("error").get("code").asInt());
    } finally {
      releaseNetworkWrite.countDown();
      first.get(5, TimeUnit.SECONDS);
      second.get(5, TimeUnit.SECONDS);
      executor.shutdownNow();
    }
  }

  @Test
  public void batchDeadlineIsRecheckedAfterSubResponseSerialization() throws Exception {
    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator(
        ArchiveQueryLimits.builder()
            .maxConcurrentQueries(1)
            .deadlineMs(5_000)
            .batchDeadlineMs(20)
            .build());
    doAnswer(inv -> {
      QueryLease lease = coordinator.acquire();
      ArchiveQueryTransportScope.closeAfterResponse(lease);
      OutputStream output = inv.getArgument(1);
      output.write("{\"jsonrpc\":\"2.0\",\"result\":\"late\",\"id\":1}"
          .getBytes(StandardCharsets.UTF_8));
      Thread.sleep(80L);
      return 0;
    }).when(mockRpcServer).handleRequest(any(InputStream.class), any(OutputStream.class));

    MockHttpServletResponse response = doPost("[{\"id\":1}]");

    JsonNode body = MAPPER.readTree(response.getContentAsByteArray());
    assertTrue(body.isArray());
    assertEquals(-32005, body.get(0).get("error").get("code").asInt());
    assertEquals(1, body.get(0).get("id").asInt());
    assertEquals(0, coordinator.getActiveLeaseCount());
  }

  @Test
  public void batchOverflowDoesNotEchoAnOversizedId() throws Exception {
    int limit = 256;
    CommonParameter.getInstance().jsonRpcMaxResponseSize = limit;
    int[] calls = {0};
    doAnswer(inv -> {
      OutputStream output = inv.getArgument(1);
      output.write(new byte[calls[0]++ == 0 ? 180 : 100]);
      return 0;
    }).when(mockRpcServer).handleRequest(any(InputStream.class), any(OutputStream.class));
    StringBuilder oversizedId = new StringBuilder();
    for (int i = 0; i < 10_000; i++) {
      oversizedId.append('x');
    }

    MockHttpServletResponse response = doPost(
        "[{\"id\":1},{\"id\":\"" + oversizedId + "\"}]");

    assertTrue(response.getContentAsByteArray().length <= limit);
    JsonNode body = MAPPER.readTree(response.getContentAsByteArray());
    assertTrue(body.isArray());
    assertEquals(-32003, body.get(0).get("error").get("code").asInt());
    assertTrue(body.get(0).get("id").isNull());
  }

  // --- normal path ---

  @Test
  public void normalRequest_commitsRpcServerResponse() throws Exception {
    byte[] rpcResp = "{\"result\":\"0x1\"}".getBytes(StandardCharsets.UTF_8);
    doAnswer(inv -> {
      OutputStream output = inv.getArgument(1);
      output.write(rpcResp);
      return 0;
    }).when(mockRpcServer).handleRequest(any(InputStream.class), any(OutputStream.class));

    MockHttpServletResponse resp = doPost("{\"method\":\"eth_blockNumber\",\"id\":1}");
    assertEquals(200, resp.getStatus());
    assertArrayEquals(rpcResp, resp.getContentAsByteArray());
  }

  @Test
  public void normalRequestRetainsArchivePermitThroughResponseSerialization() throws Exception {
    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator();
    doAnswer(inv -> {
      QueryLease lease = coordinator.acquire();
      ArchiveQueryTransportScope.closeAfterResponse(lease);
      assertFalse(lease.isClosed());
      assertEquals(1, coordinator.getActiveLeaseCount());
      OutputStream output = inv.getArgument(1);
      output.write("{\"result\":\"ok\"}"
          .getBytes(StandardCharsets.UTF_8));
      return 0;
    }).when(mockRpcServer).handleRequest(any(InputStream.class), any(OutputStream.class));

    doPost("{\"method\":\"eth_getBalance\",\"id\":1}");

    assertEquals(0, coordinator.getActiveLeaseCount());
  }

  @Test
  public void normalRequestReleasesArchivePermitBeforeNetworkWrite() throws Exception {
    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator();
    doAnswer(inv -> {
      QueryLease lease = coordinator.acquire();
      ArchiveQueryTransportScope.closeAfterResponse(lease);
      OutputStream output = inv.getArgument(1);
      output.write("{\"result\":\"ok\"}"
          .getBytes(StandardCharsets.UTF_8));
      return 0;
    }).when(mockRpcServer).handleRequest(any(InputStream.class), any(OutputStream.class));
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/jsonrpc");
    req.setContent("{\"method\":\"eth_getBalance\",\"id\":1}"
        .getBytes(StandardCharsets.UTF_8));
    MockHttpServletResponse resp = new MockHttpServletResponse() {
      @Override
      public ServletOutputStream getOutputStream() {
        assertEquals(0, coordinator.getActiveLeaseCount());
        return super.getOutputStream();
      }
    };

    servlet.callDoPost(req, resp);

    assertEquals(0, coordinator.getActiveLeaseCount());
  }

  @Test
  public void batchReleasesEachArchivePermitAfterItsSerializedSubResponse() throws Exception {
    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator(
        ArchiveQueryLimits.builder()
            .maxConcurrentQueries(1)
            .maxPendingQueries(0)
            .acquireTimeoutMs(0)
            .build());
    int[] calls = {0};
    doAnswer(inv -> {
      QueryLease lease = coordinator.acquire();
      ArchiveQueryTransportScope.closeAfterResponse(lease);
      assertFalse(lease.isClosed());
      assertEquals(1, coordinator.getActiveLeaseCount());
      OutputStream output = inv.getArgument(1);
      output.write(("{\"jsonrpc\":\"2.0\",\"result\":\"ok\",\"id\":"
          + ++calls[0] + "}").getBytes(StandardCharsets.UTF_8));
      return 0;
    }).when(mockRpcServer).handleRequest(any(InputStream.class), any(OutputStream.class));

    doPost("[{\"id\":1},{\"id\":2}]");

    assertEquals(2, calls[0]);
    assertEquals(0, coordinator.getActiveLeaseCount());
  }

  // --- Content-Type header: must be application/json-rpc (no charset suffix) ---

  @Test
  public void errorResponse_contentTypeIsApplicationJsonRpc() throws Exception {
    MockHttpServletResponse resp = doPost("not valid json");
    assertEquals("application/json-rpc", resp.getContentType());
  }

  @Test
  public void batchResponse_contentTypeIsApplicationJsonRpc() throws Exception {
    byte[] singleResp = "{\"jsonrpc\":\"2.0\",\"result\":\"ok\",\"id\":1}"
        .getBytes(StandardCharsets.UTF_8);
    doAnswer(inv -> {
      OutputStream out = inv.getArgument(1);
      out.write(singleResp);
      return 0;
    }).when(mockRpcServer).handleRequest(any(InputStream.class), any(OutputStream.class));

    MockHttpServletResponse resp = doPost("[{\"id\":1}]");
    assertEquals("application/json-rpc", resp.getContentType());
  }

  @Test
  public void allNotificationBatch_contentTypeIsApplicationJsonRpc() throws Exception {
    // notification: rpcServer returns 0 bytes → empty batchResult → early return path
    doAnswer(inv -> 0).when(mockRpcServer)
        .handleRequest(any(InputStream.class), any(OutputStream.class));

    MockHttpServletResponse resp = doPost("[{\"method\":\"eth_blockNumber\"}]");
    assertEquals(200, resp.getStatus());
    assertEquals(0, resp.getContentLength());
    assertEquals("application/json-rpc", resp.getContentType());
  }

  // --- Primitive root node → Invalid Request (-32600), id must be JSON null ---

  @Test
  public void primitiveRootNull_returnsInvalidRequestWithJsonNullId() throws Exception {
    MockHttpServletResponse resp = doPost("null");
    assertEquals(200, resp.getStatus());
    JsonNode body = MAPPER.readTree(resp.getContentAsString());
    assertFalse(body.isArray());
    assertEquals("2.0", body.get("jsonrpc").asText());
    assertEquals(-32600, body.get("error").get("code").asInt());
    assertTrue("id must be JSON null, not the string \"null\"", body.get("id").isNull());
    assertFalse("id must not be a string", body.get("id").isTextual());
  }

  @Test
  public void primitiveRootBoolean_returnsInvalidRequest() throws Exception {
    MockHttpServletResponse resp = doPost("true");
    assertEquals(200, resp.getStatus());
    assertEquals(-32600,
        MAPPER.readTree(resp.getContentAsString()).get("error").get("code").asInt());
  }

  @Test
  public void primitiveRootNumber_returnsInvalidRequest() throws Exception {
    MockHttpServletResponse resp = doPost("123");
    assertEquals(200, resp.getStatus());
    assertEquals(-32600,
        MAPPER.readTree(resp.getContentAsString()).get("error").get("code").asInt());
  }

  @Test
  public void primitiveRootString_returnsInvalidRequest() throws Exception {
    MockHttpServletResponse resp = doPost("\"hello\"");
    assertEquals(200, resp.getStatus());
    assertEquals(-32600,
        MAPPER.readTree(resp.getContentAsString()).get("error").get("code").asInt());
  }

  // --- Non-object element inside a batch → Invalid Request per element ---

  @Test
  public void batchWithNestedArray_returnsInvalidRequestArray() throws Exception {
    MockHttpServletResponse resp = doPost("[[]]");
    assertEquals(200, resp.getStatus());
    JsonNode body = MAPPER.readTree(resp.getContentAsString());
    assertTrue("response must be a JSON array", body.isArray());
    assertEquals(1, body.size());
    assertEquals(-32600, body.get(0).get("error").get("code").asInt());
    assertTrue("id in batch error must be JSON null", body.get(0).get("id").isNull());
  }

  @Test
  public void batchWithMixedObjectAndArray_objectProcessedArrayRejected() throws Exception {
    byte[] singleResp = "{\"jsonrpc\":\"2.0\",\"result\":\"ok\",\"id\":1}"
        .getBytes(StandardCharsets.UTF_8);
    doAnswer(inv -> {
      OutputStream out = inv.getArgument(1);
      out.write(singleResp);
      return 0;
    }).when(mockRpcServer).handleRequest(any(InputStream.class), any(OutputStream.class));

    MockHttpServletResponse resp = doPost("[{\"id\":1}, []]");
    assertEquals(200, resp.getStatus());
    JsonNode body = MAPPER.readTree(resp.getContentAsString());
    assertTrue("response must be a JSON array", body.isArray());
    assertEquals(2, body.size());
    assertEquals("ok", body.get(0).get("result").asText());
    assertEquals(-32600, body.get(1).get("error").get("code").asInt());
  }

  @Test
  public void batchWithNumericAndStringElements_allGetInvalidRequest() throws Exception {
    MockHttpServletResponse resp = doPost("[42, \"foo\", true]");
    assertEquals(200, resp.getStatus());
    JsonNode body = MAPPER.readTree(resp.getContentAsString());
    assertTrue("response must be a JSON array", body.isArray());
    assertEquals(3, body.size());
    for (int i = 0; i < 3; i++) {
      assertEquals(-32600, body.get(i).get("error").get("code").asInt());
    }
  }

  // --- StreamReadConstraints: maxNestingDepth and maxTokenCount must be enforced ---

  @Test
  public void excessivelyNestedRequest_returnsParseError() throws Exception {
    int limit = Constant.MAX_NESTING_DEPTH;
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i <= limit; i++) {
      sb.append('[');
    }
    sb.append('0');
    for (int i = 0; i <= limit; i++) {
      sb.append(']');
    }

    MockHttpServletResponse resp = doPost(sb.toString());
    assertEquals(200, resp.getStatus());
    JsonNode error = MAPPER.readTree(resp.getContentAsString()).get("error");
    assertEquals(-32700, error.get("code").asInt());
    // StreamConstraintsException message must be surfaced verbatim, not the generic text,
    // so callers can tell which constraint (nesting depth) was hit.
    String message = error.get("message").asText();
    assertNotEquals("JSON parse error", message);
    assertTrue("expected a nesting-depth constraint message, got: " + message,
        message.contains("nesting depth") && message.contains("exceeds the maximum allowed"));
  }

  @Test
  public void tooManyTokens_returnsParseError() throws Exception {
    int limit = Constant.MAX_TOKEN_COUNT;
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < limit; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append('0');
    }
    sb.append(']');

    MockHttpServletResponse resp = doPost(sb.toString());
    assertEquals(200, resp.getStatus());
    JsonNode error = MAPPER.readTree(resp.getContentAsString()).get("error");
    assertEquals(-32700, error.get("code").asInt());
    // StreamConstraintsException message must be surfaced verbatim, not the generic text,
    // so callers can tell which constraint (token count) was hit.
    String message = error.get("message").asText();
    assertNotEquals("JSON parse error", message);
    assertTrue("expected a token-count constraint message, got: " + message,
        message.contains("Token count") && message.contains("exceeds the maximum allowed"));
  }

  // --- helpers ---

  private MockHttpServletResponse doPost(String body) throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/jsonrpc");
    req.setContent(body.getBytes(StandardCharsets.UTF_8));
    MockHttpServletResponse resp = new MockHttpServletResponse();
    servlet.callDoPost(req, resp);
    return resp;
  }

  private void setRpcServer(JsonRpcServer server) throws Exception {
    Field field = JsonRpcServlet.class.getDeclaredField("rpcServer");
    field.setAccessible(true);
    field.set(servlet, server);
  }

  private void postTo(MockHttpServletResponse response, int id) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/jsonrpc");
    request.setContent(("{\"method\":\"eth_getBalance\",\"id\":" + id + "}")
        .getBytes(StandardCharsets.UTF_8));
    try {
      servlet.callDoPost(request, response);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private void postBatchTo(MockHttpServletResponse response, int id) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/jsonrpc");
    request.setContent(("[{\"id\":" + id + "}]").getBytes(StandardCharsets.UTF_8));
    try {
      servlet.callDoPost(request, response);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static final class BlockingResponse extends MockHttpServletResponse {

    private final CountDownLatch entered;
    private final CountDownLatch release;
    private final AtomicBoolean blocked = new AtomicBoolean();
    private final ServletOutputStream delegate = super.getOutputStream();

    private BlockingResponse(CountDownLatch entered, CountDownLatch release) {
      this.entered = entered;
      this.release = release;
    }

    @Override
    public ServletOutputStream getOutputStream() {
      return new ServletOutputStream() {
        @Override
        public void write(int value) throws IOException {
          blockOnce();
          delegate.write(value);
        }

        @Override
        public void write(byte[] value, int offset, int length) throws IOException {
          blockOnce();
          delegate.write(value, offset, length);
        }

        @Override
        public void flush() throws IOException {
          delegate.flush();
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {
        }

        private void blockOnce() throws IOException {
          if (!blocked.compareAndSet(false, true)) {
            return;
          }
          entered.countDown();
          try {
            release.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while simulating a slow client", e);
          }
        }
      };
    }
  }

  public interface LargeResponseApi {

    String large();
  }

  private static final class LargeResponseApiImpl implements LargeResponseApi {

    @Override
    public String large() {
      char[] value = new char[1024 * 1024];
      java.util.Arrays.fill(value, 'x');
      return new String(value);
    }
  }

  public interface LargeIterableApi {

    Iterable<String> largeIterable();
  }

  private static final class LargeIterableApiImpl implements LargeIterableApi {

    private static final int ITEM_COUNT = 100_000;
    private static final String ITEM = new String(new char[128]).replace('\0', 'x');
    private final AtomicInteger emitted = new AtomicInteger();

    @Override
    public Iterable<String> largeIterable() {
      return () -> new Iterator<String>() {
        @Override
        public boolean hasNext() {
          return emitted.get() < ITEM_COUNT;
        }

        @Override
        public String next() {
          emitted.incrementAndGet();
          return ITEM;
        }
      };
    }
  }

  private static class TestableServlet extends JsonRpcServlet {

    void callDoPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
      doPost(req, resp);
    }
  }
}
