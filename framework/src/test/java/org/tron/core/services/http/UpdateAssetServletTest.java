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
import org.tron.protos.contract.AssetIssueContractOuterClass;

public class UpdateAssetServletTest extends BaseHttpTest {

  private UpdateAssetServlet servlet;
  private final String ownerAddr = ByteArray.toHexString(new ECKey().getAddress());

  @Override
  protected void setUpMocks() throws Exception {
    servlet = new UpdateAssetServlet();
    injectWallet(servlet);
    when(wallet.createTransactionCapsule(
        any(AssetIssueContractOuterClass.UpdateAssetContract.class),
        eq(Protocol.Transaction.Contract.ContractType.UpdateAssetContract)))
        .thenReturn(new TransactionCapsule(MINIMAL_TX));
  }

  @Test
  public void testUpdateAsset() throws Exception {
    String jsonParam = "{"
        + "\"owner_address\": \"" + ownerAddr + "\","
        + "\"description\": \"746573745f64657363\","
        + "\"url\": \"746573745f75726c\","
        + "\"new_limit\": 100,"
        + "\"new_public_limit\": 200"
        + "}";
    MockHttpServletRequest request = postRequest(jsonParam);

    MockHttpServletResponse response = newResponse();
    servlet.doPost(request, response);
    verify(wallet).createTransactionCapsule(
        any(AssetIssueContractOuterClass.UpdateAssetContract.class),
        eq(Protocol.Transaction.Contract.ContractType.UpdateAssetContract));
    assertTransactionResponse(response);
  }
}
