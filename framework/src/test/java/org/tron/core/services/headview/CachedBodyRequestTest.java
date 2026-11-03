package org.tron.core.services.headview;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;

public class CachedBodyRequestTest {

  @Test
  public void formBodyParametersFollowTheQueryParameters() throws Exception {
    byte[] body = "visible=true&a=2&name=%E4%B8%AD&flag".getBytes(StandardCharsets.UTF_8);
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/wallet/listwitnesses");
    request.setContentType("application/x-www-form-urlencoded; charset=UTF-8");
    request.addParameter("a", "1");

    CachedBodyRequest cached = new CachedBodyRequest(request, body);

    assertEquals("true", cached.getParameter("visible"));
    assertEquals("1", cached.getParameter("a"));
    assertArrayEquals(new String[] {"1", "2"}, cached.getParameterValues("a"));
    assertEquals("中", cached.getParameter("name"));
    assertEquals("", cached.getParameter("flag"));
    assertNull(cached.getParameter("missing"));
    assertEquals(4, Collections.list(cached.getParameterNames()).size());
    assertEquals(new String(body, StandardCharsets.UTF_8),
        cached.getReader().lines().collect(Collectors.joining()));
  }

  @Test
  public void formAtTheKeyLimitIsDecoded() {
    CachedBodyRequest cached = formRequest(formWithKeys(ContextHandler.DEFAULT_MAX_FORM_KEYS));

    assertEquals(ContextHandler.DEFAULT_MAX_FORM_KEYS, cached.getParameterMap().size());
  }

  @Test
  public void formOverTheKeyLimitIsRejected() {
    CachedBodyRequest cached = formRequest(formWithKeys(ContextHandler.DEFAULT_MAX_FORM_KEYS + 1));

    assertThrows(BadMessageException.class, () -> cached.getParameter("k0"));
  }

  @Test
  public void formOverTheContentLimitIsRejected() {
    char[] value = new char[ContextHandler.DEFAULT_MAX_FORM_CONTENT_SIZE];
    Arrays.fill(value, 'x');
    CachedBodyRequest cached = formRequest("a=" + new String(value));

    assertThrows(BadMessageException.class, () -> cached.getParameter("a"));
  }

  @Test
  public void malformedFormIsRejected() {
    CachedBodyRequest cached = formRequest("a=%zz");

    assertThrows(BadMessageException.class, () -> cached.getParameter("a"));
  }

  @Test
  public void formBodyOnGetIsNotDecoded() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/wallet/listwitnesses");
    request.setContentType("application/x-www-form-urlencoded");

    CachedBodyRequest cached = new CachedBodyRequest(request,
        "visible=true".getBytes(StandardCharsets.UTF_8));

    assertNull(cached.getParameter("visible"));
  }

  @Test
  public void nonFormBodyIsNotParsedAsParameters() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/wallet/getaccount");
    request.setContentType("application/json");
    request.addParameter("visible", "false");

    CachedBodyRequest cached = new CachedBodyRequest(request,
        "visible=true".getBytes(StandardCharsets.UTF_8));

    assertEquals("false", cached.getParameter("visible"));
    assertEquals(1, cached.getParameterMap().size());
  }

  private static CachedBodyRequest formRequest(String body) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/wallet/listwitnesses");
    request.setContentType("application/x-www-form-urlencoded");
    return new CachedBodyRequest(request, body.getBytes(StandardCharsets.UTF_8));
  }

  private static String formWithKeys(int keys) {
    return IntStream.range(0, keys).mapToObj(i -> "k" + i + "=")
        .collect(Collectors.joining("&"));
  }
}
