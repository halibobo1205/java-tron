package org.tron.core.services.headview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.servlet.ServletResponseWrapper;
import javax.servlet.http.HttpServletResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.tron.common.TestConstants;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.capsule.BlockCapsule.BlockId;
import org.tron.core.config.args.Args;
import org.tron.core.db.Manager;
import org.tron.core.db2.view.HeadLease;
import org.tron.core.db2.view.HeadView;
import org.tron.core.db2.view.HeadViewException;
import org.tron.core.db2.view.HeadViewException.Reason;
import org.tron.core.db2.view.HeadViewRegistry;
import org.tron.core.services.http.HttpApi.Surface;
import org.tron.core.services.http.servlets.ListWitnessesServlet;
import org.tron.protos.Protocol.BlockHeader;

public class BufferedHttpResponseTest {

  private static final List<String> BUFFERED = Arrays.asList(
      "getWriter()", "setStatus(int)", "sendError(int)", "sendError(int,java.lang.String)");

  private HeadViewRegistry registry;
  private CommittedHeadQuery query;

  @Before
  public void publishView() {
    Args.setParam(new String[0], TestConstants.TEST_CONF);
    registry = new HeadViewRegistry(64);
    registry.publish(new HeadView(new BlockId(Sha256Hash.ZERO_HASH, 7),
        BlockHeader.getDefaultInstance(), Collections.emptyMap()), registry.currentGeneration());
    registry.markReady();
    Manager manager = mock(Manager.class);
    when(manager.getHeadViewRegistry()).thenReturn(registry);
    query = new CommittedHeadQuery();
    ReflectionTestUtils.setField(query, "manager", manager);
  }

  @After
  public void clearArgs() {
    Args.clearParam();
  }

  @Test
  public void everyResponseMutatorIsDeclaredByTheWrapper() {
    for (Method method : mutators()) {
      try {
        BufferedHttpResponse.class.getDeclaredMethod(method.getName(),
            method.getParameterTypes());
      } catch (NoSuchMethodException e) {
        fail("BufferedHttpResponse must declare " + signature(method));
      }
    }
  }

  @Test
  public void unsupportedMutatorsFailTheLeaseWithoutTouchingTheResponse() throws Exception {
    for (Method method : mutators()) {
      if (BUFFERED.contains(signature(method))) {
        continue;
      }
      MockHttpServletResponse real = new MockHttpServletResponse();
      try (HeadLease lease = registry.acquire()) {
        BufferedHttpResponse buffered = new BufferedHttpResponse(real, lease, 1024);
        try {
          method.invoke(buffered, defaultArguments(method));
          fail(signature(method) + " must be rejected");
        } catch (InvocationTargetException e) {
          assertTrue(signature(method), e.getCause() instanceof HeadViewException);
        }
        assertEquals(signature(method), Reason.UNSUPPORTED, lease.getFault());
      }
      assertEquals(signature(method), 200, real.getStatus());
      assertTrue(signature(method), real.getHeaderNames().isEmpty());
      assertFalse(signature(method), real.isCommitted());
      assertEquals(signature(method), 0, real.getContentAsByteArray().length);
    }
  }

  @Test
  public void bufferedBodyIsDroppedWhenTheViewFailsBeforeTheSeal() throws Exception {
    MockHttpServletResponse real = serve((request, response) -> {
      response.getWriter().println("{\"partial\":true}");
      registry.onFrozenLayerWrite("account");
    });

    assertEquals(503, real.getStatus());
    assertTrue(real.getContentAsString().contains(Reason.UNAVAILABLE.getCode()));
    assertFalse(real.getContentAsString().contains("partial"));
    assertNoBlockHeaders(real);
  }

  @Test
  public void unsupportedWriteSwallowedByTheServletStillFailsTheRequest() throws Exception {
    MockHttpServletResponse real = serve((request, response) -> {
      try {
        response.setHeader("X-Test", "1");
      } catch (RuntimeException e) {
        response.getWriter().println("{\"Error\":\"swallowed\"}");
      }
    });

    assertEquals(400, real.getStatus());
    assertTrue(real.getContentAsString().contains(Reason.UNSUPPORTED.getCode()));
    assertNull(real.getHeader("X-Test"));
    assertNoBlockHeaders(real);
  }

  @Test
  public void sendErrorIsReplayedAfterTheSeal() throws Exception {
    MockHttpServletResponse real = serve((request, response) ->
        response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED, "not here"));

    assertEquals(405, real.getStatus());
    assertEquals("not here", real.getErrorMessage());
    assertNoBlockHeaders(real);
  }

  @Test
  public void tailStillInTheWriterCountsTowardsTheLimit() throws Exception {
    Args.getInstance().setCommittedHeadQueryMaxResponseSize(16);

    MockHttpServletResponse atLimit = serve((request, response) ->
        response.getWriter().print(text(16)));
    MockHttpServletResponse overLimit = serve((request, response) ->
        response.getWriter().print(text(17)));

    assertEquals(200, atLimit.getStatus());
    assertEquals(text(16), atLimit.getContentAsString());
    assertEquals("7", atLimit.getHeader(CommittedHeadQuery.HTTP_BLOCK_NUMBER));
    assertEquals(500, overLimit.getStatus());
    assertTrue(overLimit.getContentAsString().contains(Reason.RESPONSE_TOO_LARGE.getCode()));
    assertNoBlockHeaders(overLimit);
  }

  private MockHttpServletResponse serve(CommittedHeadQuery.HttpInvocation invocation)
      throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/wallet/listwitnesses");
    request.addHeader(CommittedHeadQuery.HTTP_SELECTOR, CommittedHeadQuery.COMMITTED_HEAD);
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setContentType("application/json; charset=utf-8");
    query.serviceHttp(ListWitnessesServlet.class, Surface.FULL, request, response, invocation);
    return response;
  }

  private static void assertNoBlockHeaders(MockHttpServletResponse response) {
    assertNull(response.getHeader(CommittedHeadQuery.HTTP_BLOCK_NUMBER));
    assertNull(response.getHeader(CommittedHeadQuery.HTTP_BLOCK_ID));
  }

  private static String text(int length) {
    char[] chars = new char[length];
    Arrays.fill(chars, 'x');
    return new String(chars);
  }

  private static List<Method> mutators() {
    List<Method> methods = new ArrayList<>(Arrays.asList(HttpServletResponse.class.getMethods()));
    for (Method method : ServletResponseWrapper.class.getDeclaredMethods()) {
      if (method.getName().endsWith("Response")) {
        methods.add(method);
      }
    }
    methods.removeIf(method -> !isMutator(method.getName()));
    return methods;
  }

  private static boolean isMutator(String name) {
    return name.startsWith("set") || name.startsWith("add") || name.startsWith("send")
        || name.startsWith("reset") || name.equals("flushBuffer")
        || name.equals("getOutputStream") || name.equals("getWriter")
        || name.equals("getResponse");
  }

  private static Object[] defaultArguments(Method method) {
    Class<?>[] types = method.getParameterTypes();
    Object[] arguments = new Object[types.length];
    for (int i = 0; i < types.length; i++) {
      if (types[i] == int.class) {
        arguments[i] = 0;
      } else if (types[i] == long.class) {
        arguments[i] = 0L;
      }
    }
    return arguments;
  }

  private static String signature(Method method) {
    StringBuilder builder = new StringBuilder(method.getName()).append('(');
    Class<?>[] types = method.getParameterTypes();
    for (int i = 0; i < types.length; i++) {
      builder.append(i == 0 ? "" : ",").append(types[i].getName());
    }
    return builder.append(')').toString();
  }
}
