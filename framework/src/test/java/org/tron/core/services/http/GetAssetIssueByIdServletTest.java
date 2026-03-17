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
import org.tron.protos.contract.AssetIssueContractOuterClass.AssetIssueContract;

public class GetAssetIssueByIdServletTest {

  private GetAssetIssueByIdServlet servlet;
  @Mock
  private Wallet wallet;
  private AutoCloseable closeable;

  @Before
  public void setUp() throws Exception {
    closeable = MockitoAnnotations.openMocks(this);
    servlet = new GetAssetIssueByIdServlet();
    Field f = GetAssetIssueByIdServlet.class.getDeclaredField("wallet");
    f.setAccessible(true);
    f.set(servlet, wallet);
    when(wallet.getAssetIssueById(any())).thenReturn(null);
  }

  @After
  public void clearMocks() throws Exception {
    closeable.close();
  }

  @Test
  public void testGetAssetIssueByIdPost() throws Exception {
    String jsonParam = "{\"value\": \"100001\"}";
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setMethod("POST");
    request.setContentType("application/json");
    request.setContent(jsonParam.getBytes(UTF_8));

    MockHttpServletResponse response = new MockHttpServletResponse();
    servlet.doPost(request, response);
    org.mockito.Mockito.verify(wallet).getAssetIssueById("100001");
    assertTrue(response.getContentAsString().contains("{}"));
  }

  @Test
  public void testGetAssetIssueByIdGet() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setMethod("GET");
    request.addParameter("value", "100001");

    MockHttpServletResponse response = new MockHttpServletResponse();
    servlet.doGet(request, response);
    org.mockito.Mockito.verify(wallet).getAssetIssueById("100001");
    assertTrue(response.getContentAsString().contains("{}"));
  }
}
