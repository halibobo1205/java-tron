package org.tron.core.services.jsonrpc;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.POJONode;
import com.googlecode.jsonrpc4j.HttpStatusCodeProvider;
import com.googlecode.jsonrpc4j.JsonRpcInterceptor;
import com.googlecode.jsonrpc4j.JsonRpcServer;
import com.googlecode.jsonrpc4j.ProxyUtil;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.common.parameter.CommonParameter;
import org.tron.core.Constant;
import org.tron.core.archive.query.ArchiveQueryRequestScope;
import org.tron.core.archive.query.ArchiveQueryTransportScope;
import org.tron.core.archive.query.HistoricalQueryLimitException;
import org.tron.core.services.filter.BufferedResponseWrapper;
import org.tron.core.services.http.RateLimiterServlet;

@Component
@Slf4j(topic = "API")
public class JsonRpcServlet extends RateLimiterServlet {

  private static final ObjectMapper MAPPER = buildMapper();
  private static final ObjectMapper RPC_MAPPER = buildRpcMapper();
  private static final PendingResponseLimiter PENDING_RESPONSES = new PendingResponseLimiter();
  private static final int MAX_DIRECT_ERROR_BYTES = 4 * 1024;
  private static final int MAX_ERROR_ID_CHARS = 256;
  private static final int MAX_ERROR_MESSAGE_CHARS = 512;

  private static ObjectMapper buildMapper() {
    return new ObjectMapper(buildJsonFactory());
  }

  static ObjectMapper buildRpcMapper() {
    return new StreamingResultObjectMapper(buildJsonFactory());
  }

  private static JsonFactory buildJsonFactory() {
    return JsonFactory.builder()
        .streamReadConstraints(StreamReadConstraints.builder()
            .maxNestingDepth(Constant.MAX_NESTING_DEPTH)
            .maxTokenCount(Constant.MAX_TOKEN_COUNT)
            .build())
        .build();
  }

  private enum JsonRpcError {
    PARSE_ERROR(-32700),
    INVALID_REQUEST(-32600),
    INTERNAL_ERROR(-32603),
    EXCEED_LIMIT(-32005),
    RESPONSE_TOO_LARGE(-32003);

    private final int code;

    JsonRpcError(int code) {
      this.code = code;
    }
  }

  private JsonRpcServer rpcServer = null;

  @Autowired
  private TronJsonRpc tronJsonRpc;

  @Autowired
  private JsonRpcInterceptor interceptor;

  @Override
  public void init(ServletConfig config) throws ServletException {
    super.init(config);

    ClassLoader cl = Thread.currentThread().getContextClassLoader();
    Object compositeService = ProxyUtil.createCompositeServiceProxy(
        cl,
        new Object[] {tronJsonRpc},
        new Class[] {TronJsonRpc.class},
        true);

    rpcServer = new JsonRpcServer(RPC_MAPPER, compositeService);
    rpcServer.setErrorResolver(JsonRpcErrorResolver.INSTANCE);

    HttpStatusCodeProvider httpStatusCodeProvider = new HttpStatusCodeProvider() {
      @Override
      public int getHttpStatusCode(int resultCode) {
        return 200;
      }

      @Override
      public Integer getJsonRpcCode(int httpStatusCode) {
        return null;
      }
    };
    rpcServer.setHttpStatusCodeProvider(httpStatusCodeProvider);

    rpcServer.setShouldLogInvocationErrors(false);
    if (CommonParameter.getInstance().isMetricsPrometheusEnable()) {
      rpcServer.setInterceptorList(Collections.singletonList(interceptor));
    }
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    CommonParameter parameter = CommonParameter.getInstance();

    // Transport IOException from readBody propagates as HTTP 500 (genuine IO failure).
    byte[] body = readBody(req.getInputStream());
    JsonNode rootNode;
    try {
      rootNode = MAPPER.readTree(body);
      if (rootNode == null || rootNode.isMissingNode()) {
        writeJsonRpcError(resp, JsonRpcError.PARSE_ERROR, "JSON parse error", null, false);
        return;
      }
    } catch (JsonProcessingException e) {
      if (e instanceof StreamConstraintsException) {
        writeJsonRpcError(resp, JsonRpcError.PARSE_ERROR, e.getMessage(), null, false);
      } else {
        writeJsonRpcError(resp, JsonRpcError.PARSE_ERROR, "JSON parse error", null, false);
      }
      return;
    }

    if (!rootNode.isObject() && !rootNode.isArray()) {
      writeJsonRpcError(resp, JsonRpcError.INVALID_REQUEST, "Invalid Request", null, false);
      return;
    }

    boolean isBatch = rootNode.isArray();
    if (isBatch && rootNode.isEmpty()) {
      writeJsonRpcError(resp, JsonRpcError.INVALID_REQUEST, "Invalid Request", null, false);
      return;
    }
    int batchSize = parameter.getJsonRpcMaxBatchSize();
    if (isBatch && batchSize > 0 && rootNode.size() > batchSize) {
      writeJsonRpcError(resp, JsonRpcError.EXCEED_LIMIT,
          "Batch size " + rootNode.size() + " exceeds the limit of " + batchSize, null, true);
      return;
    }

    int maxResponseSize = parameter.getJsonRpcMaxResponseSize();
    if (isBatch) {
      handleBatch(resp, rootNode, maxResponseSize);
    } else {
      handleSingle(resp, rootNode, body, maxResponseSize);
    }
  }

  private void handleSingle(HttpServletResponse resp,
      JsonNode rootNode, byte[] body, int maxResponseSize) throws IOException {
    try (PendingResponseLimiter.Reservation responseBytes =
             PENDING_RESPONSES.open(maxResponseSize);
         BoundedByteArrayOutputStream output =
             new BoundedByteArrayOutputStream(maxResponseSize, responseBytes, true)) {

      RuntimeException executionFailure = null;
      try (ArchiveQueryTransportScope ignored = ArchiveQueryTransportScope.open()) {
        try {
          rpcServer.handleRequest(new ByteArrayInputStream(body), output);
        } catch (ResponseSerializationAbortedException aborted) {
          // The bounded stream records whether this was a per-response or global-budget overflow.
        } catch (RuntimeException e) {
          executionFailure = e;
        }
      }
      if (isNotification(rootNode)) {
        output.discard();
        writeEmptyResponse(resp);
        return;
      }
      if (executionFailure != null) {
        output.discard();
        logger.error("RPC execution failed", executionFailure);
        writeJsonRpcError(resp, JsonRpcError.INTERNAL_ERROR, "Internal error",
            rootNode.get("id"), false);
        return;
      }
      if (output.isResourceExhausted()) {
        writeJsonRpcError(resp, JsonRpcError.EXCEED_LIMIT,
            "Response backlog limit reached", rootNode.get("id"), false);
        return;
      }
      if (output.isOverflow()) {
        writeResponseTooLarge(resp, rootNode.get("id"), false, maxResponseSize);
        return;
      }
      if (output.size() == 0) {
        writeEmptyResponse(resp);
        return;
      }
      // Archive readers and query permits are closed, while the independent byte reservation stays
      // held through the potentially slow network write.
      writeRetainedResponse(resp, output);
    }
  }

  private void handleBatch(HttpServletResponse resp, JsonNode rootNode, int maxResponseSize)
      throws IOException {

    try (PendingResponseLimiter.Reservation batchBytes =
             PENDING_RESPONSES.open(maxResponseSize);
         BoundedByteArrayOutputStream batchOutput =
             new BoundedByteArrayOutputStream(maxResponseSize, batchBytes, false)) {
      batchOutput.write('[');
      boolean hasResponse = false;

      try (ArchiveQueryRequestScope ignoredRequest = ArchiveQueryRequestScope.open()) {
        for (int i = 0; i < rootNode.size(); i++) {
          JsonNode subRequest = rootNode.get(i);
          boolean notification = isNotification(subRequest);
          try {
            ArchiveQueryRequestScope.checkCurrentDeadline();
          } catch (HistoricalQueryLimitException deadlineFailure) {
            if (notification) {
              finishBatchResponse(resp, batchOutput, hasResponse, maxResponseSize);
            } else {
              batchOutput.discard();
              writeJsonRpcError(resp, JsonRpcError.EXCEED_LIMIT,
                  deadlineFailure.getMessage(), subRequest.get("id"), true);
            }
            return;
          }
          if (!subRequest.isObject()) {
            byte[] errorBytes = MAPPER.writeValueAsBytes(
                buildErrorNode(JsonRpcError.INVALID_REQUEST, "Invalid Request", null));
            if (!appendBatchElement(batchOutput, errorBytes, hasResponse, maxResponseSize)) {
              boolean exhausted = batchOutput.isResourceExhausted();
              batchOutput.discard();
              writeBatchOverflow(resp, null, maxResponseSize, exhausted);
              return;
            }
            hasResponse = true;
            try {
              ArchiveQueryRequestScope.checkCurrentDeadline();
            } catch (HistoricalQueryLimitException deadlineFailure) {
              batchOutput.discard();
              writeJsonRpcError(resp, JsonRpcError.EXCEED_LIMIT,
                  deadlineFailure.getMessage(), null, true);
              return;
            }
            continue;
          }

          byte[] subBody = MAPPER.writeValueAsBytes(subRequest);
          int remaining = remainingElementBytes(batchOutput.size(), hasResponse, maxResponseSize);
          if (maxResponseSize > 0 && remaining <= 0) {
            batchOutput.discard();
            writeResponseTooLarge(resp, subRequest.get("id"), true, maxResponseSize);
            return;
          }

          RuntimeException executionFailure = null;
          boolean subOverflow;
          boolean subExhausted;
          boolean appended = true;
          int responseSize;
          try (PendingResponseLimiter.Reservation subBytes =
                   PENDING_RESPONSES.open(maxResponseSize);
               BoundedByteArrayOutputStream subOutput =
                   new BoundedByteArrayOutputStream(remaining, subBytes, true)) {
            // Retain each query permit through its own serialization, then release it before the
            // next element. The request scope separately caps aggregate historical work.
            try (ArchiveQueryTransportScope ignored = ArchiveQueryTransportScope.open()) {
              rpcServer.handleRequest(new ByteArrayInputStream(subBody), subOutput);
              ArchiveQueryRequestScope.checkCurrentDeadline();
            } catch (ResponseSerializationAbortedException aborted) {
              // Overflow state is inspected after the transport/query scopes release their leases.
            } catch (RuntimeException e) {
              executionFailure = e;
            }
            subOverflow = subOutput.isOverflow();
            subExhausted = subOutput.isResourceExhausted();
            responseSize = subOutput.size();
            if (executionFailure == null && !subOverflow && responseSize != 0
                && !notification) {
              appended = appendBatchElement(
                  batchOutput, subOutput, hasResponse, maxResponseSize);
              if (appended) {
                try {
                  ArchiveQueryRequestScope.checkCurrentDeadline();
                } catch (RuntimeException e) {
                  executionFailure = e;
                }
              }
            }
          }
          if (notification) {
            if (executionFailure instanceof HistoricalQueryLimitException
                && ((HistoricalQueryLimitException) executionFailure).getLimit()
                    == HistoricalQueryLimitException.Limit.BATCH_DEADLINE) {
              finishBatchResponse(resp, batchOutput, hasResponse, maxResponseSize);
              return;
            }
            continue;
          }
          if (executionFailure != null) {
            batchOutput.discard();
            if (executionFailure instanceof HistoricalQueryLimitException) {
              writeJsonRpcError(resp, JsonRpcError.EXCEED_LIMIT,
                  executionFailure.getMessage(), subRequest.get("id"), true);
              return;
            }
            logger.error("RPC execution failed for batch sub-request {}", i, executionFailure);
            writeJsonRpcError(resp, JsonRpcError.INTERNAL_ERROR, "Internal error", null, true);
            return;
          }
          if (subExhausted || batchOutput.isResourceExhausted()) {
            batchOutput.discard();
            writeJsonRpcError(resp, JsonRpcError.EXCEED_LIMIT,
                "Response backlog limit reached", subRequest.get("id"), true);
            return;
          }
          if (subOverflow || !appended) {
            batchOutput.discard();
            writeResponseTooLarge(resp, subRequest.get("id"), true, maxResponseSize);
            return;
          }
          if (responseSize != 0) {
            hasResponse = true;
          }
        }
        try {
          ArchiveQueryRequestScope.checkCurrentDeadline();
        } catch (HistoricalQueryLimitException deadlineFailure) {
          batchOutput.discard();
          if (hasResponse) {
            writeJsonRpcError(resp, JsonRpcError.EXCEED_LIMIT,
                deadlineFailure.getMessage(), null, true);
          } else {
            writeEmptyResponse(resp);
          }
          return;
        }
      }
      finishBatchResponse(resp, batchOutput, hasResponse, maxResponseSize);
    }
  }

  private void finishBatchResponse(HttpServletResponse resp,
      BoundedByteArrayOutputStream batchOutput, boolean hasResponse, int maxResponseSize)
      throws IOException {
    // JSON-RPC 2.0 section 6: never return an empty array for an all-notification batch.
    if (!hasResponse) {
      batchOutput.discard();
      writeEmptyResponse(resp);
      return;
    }
    batchOutput.write(']');
    if (batchOutput.isResourceExhausted()) {
      batchOutput.discard();
      writeJsonRpcError(resp, JsonRpcError.EXCEED_LIMIT,
          "Response backlog limit reached", null, true);
      return;
    }
    if (batchOutput.isOverflow()
        || maxResponseSize > 0 && batchOutput.size() > maxResponseSize) {
      batchOutput.discard();
      writeResponseTooLarge(resp, null, true, maxResponseSize);
      return;
    }
    writeRetainedResponse(resp, batchOutput);
  }

  private static boolean isNotification(JsonNode request) {
    return request != null && request.isObject() && !request.has("id");
  }

  private static int remainingElementBytes(
      int accumulatedBytes, boolean hasResponse, int maxResponseSize) {
    if (maxResponseSize <= 0) {
      return 0;
    }
    return maxResponseSize - accumulatedBytes - (hasResponse ? 1 : 0) - 1;
  }

  private static boolean appendBatchElement(BoundedByteArrayOutputStream output, byte[] element,
      boolean hasResponse, int maxResponseSize) {
    int separatorBytes = hasResponse ? 1 : 0;
    long finalSize = (long) output.size() + separatorBytes + element.length + 1L;
    if (maxResponseSize > 0 && finalSize > maxResponseSize) {
      return false;
    }
    if (hasResponse) {
      output.write(',');
    }
    output.write(element, 0, element.length);
    return !output.isOverflow();
  }

  private static boolean appendBatchElement(BoundedByteArrayOutputStream output,
      BoundedByteArrayOutputStream element, boolean hasResponse, int maxResponseSize)
      throws IOException {
    int separatorBytes = hasResponse ? 1 : 0;
    long finalSize = (long) output.size() + separatorBytes + element.size() + 1L;
    if (maxResponseSize > 0 && finalSize > maxResponseSize) {
      return false;
    }
    if (hasResponse) {
      output.write(',');
    }
    element.writeTo(output);
    return !output.isOverflow();
  }

  private void writeBatchOverflow(HttpServletResponse resp, JsonNode id, int maxResponseSize,
      boolean resourceExhausted) throws IOException {
    if (resourceExhausted) {
      writeJsonRpcError(resp, JsonRpcError.EXCEED_LIMIT,
          "Response backlog limit reached", id, true);
    } else {
      writeResponseTooLarge(resp, id, true, maxResponseSize);
    }
  }

  private void writeResponseTooLarge(HttpServletResponse resp, JsonNode id, boolean isBatch,
      int maxResponseSize) throws IOException {
    ObjectNode error = buildErrorNode(JsonRpcError.RESPONSE_TOO_LARGE,
        "Response exceeds the limit of " + maxResponseSize + " bytes", id);
    byte[] bytes;
    if (isBatch) {
      ArrayNode array = MAPPER.createArrayNode();
      array.add(error);
      bytes = MAPPER.writeValueAsBytes(array);
    } else {
      bytes = MAPPER.writeValueAsBytes(error);
    }
    writeDirectResponse(resp, bytes, maxResponseSize);
  }

  private static void writeRetainedResponse(HttpServletResponse resp,
      BoundedByteArrayOutputStream output) throws IOException {
    resp.setContentType("application/json-rpc");
    resp.setStatus(HttpServletResponse.SC_OK);
    resp.setContentLength(output.size());
    OutputStream network = resp.getOutputStream();
    output.writeTo(network);
    network.flush();
  }

  private static void writeEmptyResponse(HttpServletResponse resp) {
    resp.setContentType("application/json-rpc");
    resp.setStatus(HttpServletResponse.SC_OK);
    resp.setContentLength(0);
  }

  private static void writeDirectResponse(HttpServletResponse resp, byte[] bytes,
      int configuredMaxBytes)
      throws IOException {
    int maxBytes = configuredMaxBytes > 0
        ? Math.min(configuredMaxBytes, MAX_DIRECT_ERROR_BYTES)
        : MAX_DIRECT_ERROR_BYTES;
    if (bytes.length > maxBytes) {
      writeEmptyResponse(resp);
      return;
    }
    resp.setContentType("application/json-rpc");
    resp.setStatus(HttpServletResponse.SC_OK);
    resp.setContentLength(bytes.length);
    resp.getOutputStream().write(bytes);
    resp.getOutputStream().flush();
  }

  private byte[] readBody(InputStream in) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    byte[] tmp = new byte[4096];
    int n;
    while ((n = in.read(tmp)) != -1) {
      buffer.write(tmp, 0, n);
    }
    return buffer.toByteArray();
  }

  private ObjectNode buildErrorNode(JsonRpcError error, String message, JsonNode id) {
    ObjectNode errorObj = MAPPER.createObjectNode();
    errorObj.put("jsonrpc", "2.0");
    ObjectNode errNode = errorObj.putObject("error");
    errNode.put("code", error.code);
    errNode.put("message", boundedErrorMessage(message));
    JsonNode responseId = boundedErrorId(id);
    if (responseId != null) {
      errorObj.set("id", responseId);
    } else {
      errorObj.putNull("id");
    }
    return errorObj;
  }

  private static String boundedErrorMessage(String message) {
    if (message == null) {
      return "";
    }
    return message.length() <= MAX_ERROR_MESSAGE_CHARS
        ? message
        : message.substring(0, MAX_ERROR_MESSAGE_CHARS);
  }

  private static JsonNode boundedErrorId(JsonNode id) {
    if (id == null || id.isNull() || id.isMissingNode()) {
      return null;
    }
    if (id.isTextual()) {
      return id.textValue().length() <= MAX_ERROR_ID_CHARS ? id : null;
    }
    if (id.isIntegralNumber() && id.canConvertToLong()) {
      return id;
    }
    return null;
  }

  private static final class BoundedByteArrayOutputStream extends ByteArrayOutputStream {

    private final int maxBytes;
    private final PendingResponseLimiter.Reservation reservation;
    private final boolean abortOnOverflow;
    private boolean overflow;
    private boolean resourceExhausted;

    private BoundedByteArrayOutputStream(int maxBytes,
        PendingResponseLimiter.Reservation reservation, boolean abortOnOverflow) {
      this.maxBytes = maxBytes;
      this.reservation = reservation;
      this.abortOnOverflow = abortOnOverflow;
    }

    @Override
    public synchronized void write(int value) {
      if (overflow) {
        return;
      }
      if (maxBytes > 0 && count >= maxBytes) {
        markOverflow(false);
        return;
      }
      if (!reservation.tryReserve(1)) {
        markOverflow(true);
        return;
      }
      super.write(value);
    }

    @Override
    public synchronized void write(byte[] value, int offset, int length) {
      checkBounds(value, offset, length);
      if (overflow) {
        return;
      }
      if (maxBytes > 0 && (long) count + length > maxBytes) {
        markOverflow(false);
        return;
      }
      if (!reservation.tryReserve(length)) {
        markOverflow(true);
        return;
      }
      super.write(value, offset, length);
    }

    private void markOverflow(boolean exhausted) {
      overflow = true;
      resourceExhausted = exhausted;
      releaseBackingArray();
      reservation.releaseAll();
      if (abortOnOverflow) {
        throw ResponseSerializationAbortedException.INSTANCE;
      }
    }

    private synchronized void discard() {
      releaseBackingArray();
      reservation.releaseAll();
    }

    private void releaseBackingArray() {
      buf = new byte[32];
      count = 0;
    }

    private boolean isOverflow() {
      return overflow;
    }

    private boolean isResourceExhausted() {
      return resourceExhausted;
    }

    @Override
    public void close() {
      reservation.close();
    }

    private static void checkBounds(byte[] value, int offset, int length) {
      if (value == null) {
        throw new NullPointerException("value");
      }
      if ((offset | length) < 0 || length > value.length - offset) {
        throw new IndexOutOfBoundsException();
      }
    }
  }

  private static final class ResponseSerializationAbortedException extends RuntimeException {

    private static final ResponseSerializationAbortedException INSTANCE =
        new ResponseSerializationAbortedException();

    private ResponseSerializationAbortedException() {
      super(null, null, false, false);
    }
  }

  /** Keeps successful RPC results lazy until Jackson writes them into the bounded transport. */
  private static final class StreamingResultObjectMapper extends ObjectMapper {

    private StreamingResultObjectMapper(JsonFactory factory) {
      super(factory);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends JsonNode> T valueToTree(Object fromValue) {
      if (fromValue instanceof JsonNode) {
        return (T) fromValue;
      }
      return (T) (fromValue == null
          ? getNodeFactory().nullNode()
          : new POJONode(fromValue));
    }
  }

  /** Bounds response bytes while they are built and retained by concurrent JSON-RPC clients. */
  private static final class PendingResponseLimiter {

    private static final long DEFAULT_GLOBAL_CAPACITY_BYTES = 128L * 1024 * 1024;
    private long retainedBytes;

    private Reservation open(int perResponseLimit) {
      long capacity = perResponseLimit > 0
          ? Math.multiplyExact((long) perResponseLimit, 2L)
          : DEFAULT_GLOBAL_CAPACITY_BYTES;
      return new Reservation(this, capacity);
    }

    private synchronized boolean tryReserve(Reservation reservation, int bytes) {
      if (reservation.closed) {
        throw new IllegalStateException("JSON-RPC response reservation is closed");
      }
      if (bytes < 0) {
        throw new IllegalArgumentException("bytes must not be negative");
      }
      if (bytes == 0) {
        return true;
      }
      if (bytes > reservation.capacity || retainedBytes > reservation.capacity - bytes) {
        return false;
      }

      retainedBytes += bytes;
      reservation.bytes += bytes;
      return true;
    }

    private synchronized void releaseAll(Reservation reservation) {
      retainedBytes -= reservation.bytes;
      reservation.bytes = 0L;
      if (retainedBytes < 0) {
        throw new IllegalStateException("JSON-RPC pending response bytes underflow");
      }
    }

    private synchronized void close(Reservation reservation) {
      if (reservation.closed) {
        return;
      }
      releaseAll(reservation);
      reservation.closed = true;
    }

    private static final class Reservation
        implements AutoCloseable, BufferedResponseWrapper.ByteReservation {

      private final PendingResponseLimiter owner;
      private final long capacity;
      private long bytes;
      private boolean closed;

      private Reservation(PendingResponseLimiter owner, long capacity) {
        this.owner = owner;
        this.capacity = capacity;
      }

      @Override
      public boolean tryReserve(int additionalBytes) {
        return owner.tryReserve(this, additionalBytes);
      }

      @Override
      public void releaseAll() {
        owner.releaseAll(this);
      }

      @Override
      public void close() {
        owner.close(this);
      }
    }
  }

  private void writeJsonRpcError(HttpServletResponse resp, JsonRpcError error, String message,
      JsonNode id, boolean isBatch) throws IOException {
    ObjectNode errorObj = buildErrorNode(error, message, id);
    byte[] bytes;
    if (isBatch) {
      ArrayNode arr = MAPPER.createArrayNode();
      arr.add(errorObj);
      bytes = MAPPER.writeValueAsBytes(arr);
    } else {
      bytes = MAPPER.writeValueAsBytes(errorObj);
    }
    writeDirectResponse(resp, bytes, CommonParameter.getInstance().getJsonRpcMaxResponseSize());
  }
}
