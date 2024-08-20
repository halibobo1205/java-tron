package org.tron.core.services.http;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.api.GrpcAPI.PricesResponseMessage;
import org.tron.core.Wallet;
import org.tron.core.services.annotation.FullServlet;
import org.tron.core.services.annotation.SolidityNodeServlet;

@FullServlet("/wallet/getenergyprices")
@SolidityNodeServlet("/walletsolidity/getenergyprices")
@Component
@Slf4j(topic = "API")
public class GetEnergyPricesServlet extends RateLimiterServlet {

  @Autowired
  private Wallet wallet;

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response) {
    try {
      PricesResponseMessage reply = wallet.getEnergyPrices();
      response.getWriter().println(reply == null ? "{}" : JsonFormat.printToString(reply));
    } catch (Exception e) {
      Util.processError(e, response);
    }
  }

  @Override
  protected void doPost(HttpServletRequest request, HttpServletResponse response) {
    doGet(request, response);
  }
}
