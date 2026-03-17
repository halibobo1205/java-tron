package org.tron.core.services.http;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import org.tron.core.capsule.TransactionCapsule;
import org.tron.protos.Protocol.Transaction;

public class DelegateResourceServletTest {

  private DelegateResourceServlet servlet;
  @Mock
  private Wallet wallet;
  private AutoCloseable closeable;

  @Before
  public void setUp() throws Exception {
    closeable = MockitoAnnotations.openMocks(this);
    servlet = new DelegateResourceServlet();
    Field f = DelegateResourceServlet.class.getDeclaredField("wallet");
    f.setAccessible(true);
    f.set(servlet, wallet);
    when(wallet.createTransactionCapsule(any(), any()))
        .thenReturn(new TransactionCapsule(Transaction.getDefaultInstance()));
  }

  @After
  public void clearMocks() throws Exception {
    closeable.close();
  }

  @Test
  public void testDelegateResource() throws Exception {
    String jsonParam = "{"
        + "\"owner_address\": \"4199357684BC659F5166046B56C95A0E99F1265CD1\","
        + "\"receiver_address\": \"41B4750E2CD76E19DCA331BF5D089B71C3C2798548\","
        + "\"balance\": 1000000,"
        + "\"resource\": \"BANDWIDTH\""
        + "}";
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setMethod("POST");
    request.setContentType("application/json");
    request.setContent(jsonParam.getBytes(UTF_8));

    MockHttpServletResponse response = new MockHttpServletResponse();
    servlet.doPost(request, response);
    assertEquals(200, response.getStatus());
    String content = response.getContentAsString();
    assertFalse(content.contains("\"Error\""));
    assertTrue(content.contains("txID"));
  }
}
