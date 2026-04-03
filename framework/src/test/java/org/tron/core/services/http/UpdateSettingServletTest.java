package org.tron.core.services.http;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.tron.common.crypto.ECKey;
import org.tron.common.utils.ByteArray;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.protos.Protocol;
import org.tron.protos.contract.SmartContractOuterClass;

public class UpdateSettingServletTest extends BaseHttpTest {

  private UpdateSettingServlet servlet;
  private final String ownerAddr = ByteArray.toHexString(new ECKey().getAddress());
  private final String contractAddr = ByteArray.toHexString(new ECKey().getAddress());

  @Override
  protected void setUpMocks() throws Exception {
    servlet = new UpdateSettingServlet();
    injectWallet(servlet);
    when(wallet.createTransactionCapsule(
        any(SmartContractOuterClass.UpdateSettingContract.class),
        eq(Protocol.Transaction.Contract.ContractType.UpdateSettingContract)))
        .thenReturn(new TransactionCapsule(MINIMAL_TX));
  }

  @Test
  public void testUpdateSetting() throws Exception {
    String jsonParam = "{"
        + "\"owner_address\": \"" + ownerAddr + "\","
        + "\"contract_address\": \"" + contractAddr + "\","
        + "\"consume_user_resource_percent\": 50"
        + "}";
    MockHttpServletRequest request = postRequest(jsonParam);

    MockHttpServletResponse response = newResponse();
    servlet.doPost(request, response);
    verify(wallet).createTransactionCapsule(
        any(SmartContractOuterClass.UpdateSettingContract.class),
        eq(Protocol.Transaction.Contract.ContractType.UpdateSettingContract));
    assertTransactionResponse(response);
  }
}
