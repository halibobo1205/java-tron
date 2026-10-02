package org.tron.core.services.http;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.json.JSONObject;
import org.tron.protos.Protocol;
import org.tron.protos.contract.SmartContractOuterClass.CreateSmartContract;

public class DeployContractServletTest extends BaseHttpTest {

  private DeployContractServlet servlet;

  @Override
  protected void setUpMocks() throws Exception {
    servlet = new DeployContractServlet();
    injectWallet(servlet);
    when(wallet.createTransactionCapsule(
            any(), eq(Protocol.Transaction.Contract.ContractType.CreateSmartContract)))
        .thenReturn(new TransactionCapsule(MINIMAL_TX));
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
    MockHttpServletRequest request = postRequest(jsonParam);

    MockHttpServletResponse response = newResponse();
    servlet.doPost(request, response);
    assertEquals(200, response.getStatus());
    verify(wallet).createTransactionCapsule(
        argThat(c -> c instanceof CreateSmartContract
            && addressEquals(((CreateSmartContract) c).getOwnerAddress(),
                "4199357684bc659f5166046b56c95a0e99f1265cd1")
            && ((CreateSmartContract) c).getNewContract().getName().equals("TestContract")
            && ((CreateSmartContract) c).getNewContract()
                .getOriginEnergyLimit() == 10000000),
        eq(Protocol.Transaction.Contract.ContractType.CreateSmartContract));
    assertTransactionResponse(response);
  }

  @Test
  public void testDeployContractOmitsNullAbiOutputs() throws Exception {
    String jsonParam = "{"
        + "\"owner_address\": \"4199357684BC659F5166046B56C95A0E99F1265CD1\","
        + "\"name\": \"TestContract\","
        + "\"abi\": [{\"inputs\":[],\"name\":\"test\",\"outputs\":null,"
        + "\"type\":\"function\"}],"
        + "\"bytecode\": \"608060405234801561001057600080fd5b50\","
        + "\"fee_limit\": 1000000000,"
        + "\"call_value\": 0,"
        + "\"consume_user_resource_percent\": 100,"
        + "\"origin_energy_limit\": 10000000"
        + "}";
    MockHttpServletRequest request = postRequest(jsonParam);

    MockHttpServletResponse response = newResponse();
    servlet.doPost(request, response);
    assertEquals(200, response.getStatus());
    verify(wallet).createTransactionCapsule(
        argThat(c -> c instanceof CreateSmartContract
            && ((CreateSmartContract) c).getNewContract().getAbi().getEntrysCount() == 1
            && ((CreateSmartContract) c).getNewContract().getAbi().getEntrys(0)
                .getOutputsCount() == 0),
        eq(Protocol.Transaction.Contract.ContractType.CreateSmartContract));
    assertTransactionResponse(response);
  }

  @Test
  public void testDeployContractRejectsStringAbiOverTokenLimit() throws Exception {
    // The outer document sees one string; its ABI expands to over 100,000 JSON tokens.
    String abi = "[" + String.join(",", Collections.nCopies(50_000, "{}")) + "]";
    String jsonParam = "{"
        + "\"owner_address\":\"4199357684BC659F5166046B56C95A0E99F1265CD1\","
        + "\"abi\":\"" + abi + "\","
        + "\"bytecode\":\"6000\""
        + "}";
    assertTrue(jsonParam.getBytes(UTF_8).length < 4 * 1024 * 1024);
    assertEquals(abi, JSONObject.parseObject(jsonParam).getString("abi"));

    MockHttpServletResponse response = newResponse();
    servlet.doPost(postRequest(jsonParam), response);

    assertEquals(200, response.getStatus());
    String content = response.getContentAsString();
    assertEquals("Token count exceeds the maximum allowed (100000).",
        JSONObject.parseObject(content).getString("Error"));
    assertFalse(content.contains("internal server error"));
    verify(wallet, never()).createTransactionCapsule(any(), any());
  }
}
