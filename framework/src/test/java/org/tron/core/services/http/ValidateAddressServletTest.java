package org.tron.core.services.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

public class ValidateAddressServletTest {

  private ValidateAddressServlet servlet;

  @Before
  public void setUp() {
    servlet = new ValidateAddressServlet();
  }

  @Test
  public void testValidateAddressGet() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setMethod("GET");
    request.addParameter("address", "4199357684BC659F5166046B56C95A0E99F1265CD1");

    MockHttpServletResponse response = new MockHttpServletResponse();
    servlet.doGet(request, response);
    assertEquals(200, response.getStatus());
    assertNotNull(response.getContentAsString());
  }
}
