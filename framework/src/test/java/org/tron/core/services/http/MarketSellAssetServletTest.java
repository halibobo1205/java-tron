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
import org.tron.protos.contract.MarketContract;

public class MarketSellAssetServletTest extends BaseHttpTest {

  private MarketSellAssetServlet servlet;
  private final String ownerAddr = ByteArray.toHexString(new ECKey().getAddress());

  @Override
  protected void setUpMocks() throws Exception {
    servlet = new MarketSellAssetServlet();
    injectWallet(servlet);
    when(wallet.createTransactionCapsule(
        any(MarketContract.MarketSellAssetContract.class),
        eq(Protocol.Transaction.Contract.ContractType.MarketSellAssetContract)))
        .thenReturn(new TransactionCapsule(MINIMAL_TX));
  }

  @Test
  public void testMarketSellAsset() throws Exception {
    String jsonParam = "{"
        + "\"owner_address\": \"" + ownerAddr + "\","
        + "\"sell_token_id\": \"5f\","
        + "\"sell_token_quantity\": 100,"
        + "\"buy_token_id\": \"60\","
        + "\"buy_token_quantity\": 200"
        + "}";
    MockHttpServletRequest request = postRequest(jsonParam);

    MockHttpServletResponse response = newResponse();
    servlet.doPost(request, response);
    verify(wallet).createTransactionCapsule(
        any(MarketContract.MarketSellAssetContract.class),
        eq(Protocol.Transaction.Contract.ContractType.MarketSellAssetContract));
    assertTransactionResponse(response);
  }
}
