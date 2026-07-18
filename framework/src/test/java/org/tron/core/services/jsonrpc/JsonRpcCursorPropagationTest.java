package org.tron.core.services.jsonrpc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.googlecode.jsonrpc4j.JsonRpcServer;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.mockito.Answers;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.tron.core.services.WalletOnCursor;
import org.tron.core.services.interfaceJsonRpcOnPBFT.JsonRpcOnPBFTServlet;
import org.tron.core.services.interfaceJsonRpcOnSolidity.JsonRpcOnSolidityServlet;
import org.tron.core.services.interfaceOnPBFT.WalletOnPBFT;
import org.tron.core.services.interfaceOnSolidity.WalletOnSolidity;

public class JsonRpcCursorPropagationTest {

  @Test
  public void pbftCursorIsBoundOnHistoricalBatchWorker() throws Exception {
    assertCursorBoundOnWorker(new JsonRpcOnPBFTServlet(), WalletOnPBFT.class,
        JsonRpcOnPBFTServlet.class, "walletOnPBFT", "PBFT");
  }

  @Test
  public void solidityCursorIsBoundOnHistoricalBatchWorker() throws Exception {
    assertCursorBoundOnWorker(new JsonRpcOnSolidityServlet(), WalletOnSolidity.class,
        JsonRpcOnSolidityServlet.class, "walletOnSolidity", "SOLIDITY");
  }

  private static <T> void assertCursorBoundOnWorker(JsonRpcServlet servlet,
      Class<T> walletType, Class<?> servletType, String walletField, String cursorName)
      throws Exception {
    ThreadLocal<String> cursor = new ThreadLocal<>();
    List<String> observedCursors = new ArrayList<>();
    List<Thread> observedThreads = new ArrayList<>();
    T wallet = mock(walletType, invocation -> {
      if ("futureGetWithIOException".equals(invocation.getMethod().getName())) {
        WalletOnCursor.IoRunnable work = invocation.getArgument(0);
        cursor.set(cursorName);
        try {
          work.run();
        } finally {
          cursor.remove();
        }
        return null;
      }
      return Answers.RETURNS_DEFAULTS.answer(invocation);
    });
    setField(servlet, servletType, walletField, wallet);

    JsonRpcServer rpcServer = mock(JsonRpcServer.class);
    doAnswer(invocation -> {
      observedCursors.add(cursor.get());
      observedThreads.add(Thread.currentThread());
      OutputStream output = invocation.getArgument(1);
      output.write(("{\"jsonrpc\":\"2.0\",\"result\":\"0x1\",\"id\":"
          + observedCursors.size() + "}").getBytes(StandardCharsets.UTF_8));
      return 0;
    }).when(rpcServer).handleRequest(any(InputStream.class), any(OutputStream.class));
    setField(servlet, JsonRpcServlet.class, "rpcServer", rpcServer);

    ArchiveJsonRpcExecutor executor = new ArchiveJsonRpcExecutor(1, 1_000L);
    setField(servlet, JsonRpcServlet.class, "archiveJsonRpcExecutor", executor);
    try {
      MockHttpServletRequest request = new MockHttpServletRequest("POST", "/jsonrpc");
      request.setContent(("[{\"jsonrpc\":\"2.0\",\"method\":\"eth_getBalance\","
          + "\"params\":[\"TAddress\",\"0x1\"],\"id\":1},"
          + "{\"jsonrpc\":\"2.0\",\"method\":\"eth_blockNumber\","
          + "\"params\":[],\"id\":2}]").getBytes(StandardCharsets.UTF_8));
      MockHttpServletResponse response = new MockHttpServletResponse();

      servlet.doPost(request, response);

      assertEquals(2, observedCursors.size());
      assertEquals(cursorName, observedCursors.get(0));
      assertEquals(cursorName, observedCursors.get(1));
      assertTrue(observedThreads.stream()
          .allMatch(thread -> thread.getName().startsWith("archive-jsonrpc-")));
      assertNull(cursor.get());
    } finally {
      executor.close();
    }
  }

  private static void setField(Object target, Class<?> owner, String name, Object value)
      throws Exception {
    Field field = owner.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }
}
