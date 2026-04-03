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

public class TransferAssetServletTest extends BaseHttpTest {

  private TransferAssetServlet servlet;
  private final String ownerAddr = ByteArray.toHexString(new ECKey().getAddress());
  private final String toAddr = ByteArray.toHexString(new ECKey().getAddress());

  @Override
  protected void setUpMocks() throws Exception {
    servlet = new TransferAssetServlet();
    injectWallet(servlet);
    when(wallet.createTransactionCapsule(
        any(AssetIssueContractOuterClass.TransferAssetContract.class),
        eq(Protocol.Transaction.Contract.ContractType.TransferAssetContract)))
        .thenReturn(new TransactionCapsule(MINIMAL_TX));
  }

  @Test
  public void testTransferAsset() throws Exception {
    String jsonParam = "{"
        + "\"owner_address\": \"" + ownerAddr + "\","
        + "\"to_address\": \"" + toAddr + "\","
        + "\"asset_name\": \"74657374\","
        + "\"amount\": 100"
        + "}";
    MockHttpServletRequest request = postRequest(jsonParam);

    MockHttpServletResponse response = newResponse();
    servlet.doPost(request, response);
    verify(wallet).createTransactionCapsule(
        any(AssetIssueContractOuterClass.TransferAssetContract.class),
        eq(Protocol.Transaction.Contract.ContractType.TransferAssetContract));
    assertTransactionResponse(response);
  }
}
