package org.tron.core.services.http;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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

public class DeployContractServletTest {

  private DeployContractServlet servlet;
  @Mock
  private Wallet wallet;
  private AutoCloseable closeable;

  @Before
  public void setUp() throws Exception {
    closeable = MockitoAnnotations.openMocks(this);
    servlet = new DeployContractServlet();
    Field f = DeployContractServlet.class.getDeclaredField("wallet");
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
  public void testDeployContract() throws Exception {
    String jsonParam = "{"
        + "\"owner_address\": \"4199357684BC659F5166046B56C95A0E99F1265CD1\","
        + "\"name\": \"TestContract\","
        + "\"abi\": \"[{\\\"inputs\\\":[],\\\"name\\\":\\\"test\\\","
        + "\\\"outputs\\\":[],\\\"type\\\":\\\"function\\\"}]\","
        + "\"bytecode\": \"608060405234801561001057600080fd5b50\","
        + "\"fee_limit\": 1000000000,"
        + "\"call_value\": 0,"
        + "\"consume_user_resource_percent\": 100,"
        + "\"origin_energy_limit\": 10000000"
        + "}";
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setMethod("POST");
    request.setContentType("application/json");
    request.setContent(jsonParam.getBytes(UTF_8));

    MockHttpServletResponse response = new MockHttpServletResponse();
    servlet.doPost(request, response);
    assertEquals(200, response.getStatus());
    assertNotNull(response.getContentAsString());
  }
}
