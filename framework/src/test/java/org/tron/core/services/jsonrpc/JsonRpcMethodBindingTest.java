package org.tron.core.services.jsonrpc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.googlecode.jsonrpc4j.JsonRpcMethod;
import com.googlecode.jsonrpc4j.JsonRpcServer;
import com.googlecode.jsonrpc4j.ProxyUtil;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class JsonRpcMethodBindingTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  public void tronJsonRpcInterfaceDoesNotExposeDebugTraceMethods() {
    for (Method method : TronJsonRpc.class.getMethods()) {
      JsonRpcMethod annotation = method.getAnnotation(JsonRpcMethod.class);
      if (annotation == null) {
        continue;
      }
      assertFalse("debug_traceCall must remain unavailable",
          "debug_traceCall".equals(annotation.value()));
      assertFalse("debug_traceTransaction must remain unavailable",
          "debug_traceTransaction".equals(annotation.value()));
      assertFalse("eth_getProof must remain unavailable",
          "eth_getProof".equals(annotation.value()));
    }
  }

  @Test
  public void jsonRpcServerReturnsMethodNotFoundForDebugTraceMethods() throws Exception {
    Object compositeService = ProxyUtil.createCompositeServiceProxy(
        Thread.currentThread().getContextClassLoader(),
        new Object[] {new TronJsonRpcImpl(null, null)},
        new Class[] {TronJsonRpc.class},
        true);
    JsonRpcServer server = new JsonRpcServer(compositeService);
    server.setErrorResolver(JsonRpcErrorResolver.INSTANCE);

    assertMethodNotFound(server, "debug_traceCall");
    assertMethodNotFound(server, "debug_traceTransaction");
    assertMethodNotFound(server, "traceCall");
    assertMethodNotFound(server, "traceTransaction");
    assertMethodNotFound(server, "eth_getProof");
    assertMethodNotFound(server, "ethGetProof");
  }

  private static void assertMethodNotFound(JsonRpcServer server, String method) throws Exception {
    String request = "{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\",\"params\":[],"
        + "\"id\":1}";
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    server.handleRequest(new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8)),
        output);

    JsonNode response = MAPPER.readTree(output.toByteArray());
    assertEquals(method, -32601, response.get("error").get("code").asInt());
  }
}
