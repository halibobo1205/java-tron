package org.tron.core.services.http;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.tron.core.Wallet;
import org.tron.protos.Protocol.MarketOrderList;

public class GetMarketOrderByAccountServletTest {

  private GetMarketOrderByAccountServlet servlet;
  @Mock
  private Wallet wallet;
  private AutoCloseable closeable;

  @Before
  public void setUp() throws Exception {
    closeable = MockitoAnnotations.openMocks(this);
    servlet = new GetMarketOrderByAccountServlet();
    Field f = GetMarketOrderByAccountServlet.class.getDeclaredField("wallet");
    f.setAccessible(true);
    f.set(servlet, wallet);
    when(wallet.getMarketOrderByAccount(any())).thenReturn(null);
  }

  @After
  public void clearMocks() throws Exception {
    closeable.close();
  }

  @Test
  public void testPost() throws Exception {
    String jsonParam = "{\"value\": \"4199357684BC659F5166046B56C95A0E99F1265CD1\"}";
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setMethod("POST");
    request.setContentType("application/json");
    request.setContent(jsonParam.getBytes(UTF_8));

    MockHttpServletResponse response = new MockHttpServletResponse();
    servlet.doPost(request, response);
    assertEquals(200, response.getStatus());
    assertTrue(response.getContentAsString().contains("{}"));
  }

  @Test
  public void testGet() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setMethod("GET");
    request.addParameter("value", "4199357684BC659F5166046B56C95A0E99F1265CD1");

    MockHttpServletResponse response = new MockHttpServletResponse();
    servlet.doGet(request, response);
    assertEquals(200, response.getStatus());
    assertTrue(response.getContentAsString().contains("{}"));
  }
}
