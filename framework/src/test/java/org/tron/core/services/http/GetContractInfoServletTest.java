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
import org.tron.protos.contract.SmartContractOuterClass.SmartContract;
import org.tron.protos.contract.SmartContractOuterClass.SmartContractDataWrapper;

public class GetContractInfoServletTest {

  private GetContractInfoServlet servlet;
  @Mock
  private Wallet wallet;
  private AutoCloseable closeable;

  @Before
  public void setUp() throws Exception {
    closeable = MockitoAnnotations.openMocks(this);
    servlet = new GetContractInfoServlet();
    Field f = GetContractInfoServlet.class.getDeclaredField("wallet");
    f.setAccessible(true);
    f.set(servlet, wallet);
  }

  @After
  public void clearMocks() throws Exception {
    closeable.close();
  }

  @Test
  public void testGetContractInfoPost() throws Exception {
    when(wallet.getContractInfo(any())).thenReturn(
        SmartContractDataWrapper.newBuilder()
            .setSmartContract(SmartContract.newBuilder().setName("TestContract").build())
            .build());
    String jsonParam = "{\"value\": \"41B4750E2CD76E19DCA331BF5D089B71C3C2798548\"}";
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setMethod("POST");
    request.setContentType("application/json");
    request.setContent(jsonParam.getBytes(UTF_8));

    MockHttpServletResponse response = new MockHttpServletResponse();
    servlet.doPost(request, response);
    assertEquals(200, response.getStatus());
    assertTrue(response.getContentAsString().contains("TestContract"));
  }

  @Test
  public void testGetContractInfoGet() throws Exception {
    when(wallet.getContractInfo(any())).thenReturn(null);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setMethod("GET");
    request.addParameter("value", "41B4750E2CD76E19DCA331BF5D089B71C3C2798548");

    MockHttpServletResponse response = new MockHttpServletResponse();
    servlet.doGet(request, response);
    assertEquals(200, response.getStatus());
    assertEquals("{}", response.getContentAsString().trim());
  }
}
