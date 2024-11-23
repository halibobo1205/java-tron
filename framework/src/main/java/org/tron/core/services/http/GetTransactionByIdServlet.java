package org.tron.core.services.http;

import com.google.protobuf.ByteString;
import java.io.IOException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.api.GrpcAPI.BytesMessage;
import org.tron.common.utils.ByteArray;
import org.tron.core.Wallet;
import org.tron.core.services.annotation.FullServlet;
import org.tron.core.services.annotation.SolidityNodeServlet;
import org.tron.protos.Protocol.Transaction;


@FullServlet("/wallet/gettransactionbyid")
@SolidityNodeServlet("/walletsolidity/gettransactionbyid")
@Component
@Slf4j(topic = "API")
public class GetTransactionByIdServlet extends RateLimiterServlet {

  @Autowired
  private Wallet wallet;

  protected void doGet(HttpServletRequest request, HttpServletResponse response) {
    try {
      boolean visible = Util.getVisible(request);
      String input = request.getParameter("value");
      fillResponse(ByteString.copyFrom(ByteArray.fromHexString(input)), visible, response);
    } catch (Exception e) {
      Util.processError(e, response);
    }
  }

  protected void doPost(HttpServletRequest request, HttpServletResponse response) {
    try {
      PostParams params = PostParams.getPostParams(request);
      BytesMessage.Builder build = BytesMessage.newBuilder();
      JsonFormat.merge(params.getParams(), build, params.isVisible());
      fillResponse(build.getValue(), params.isVisible(), response);
    } catch (Exception e) {
      Util.processError(e, response);
    }
  }

  private void fillResponse(ByteString txId, boolean visible, HttpServletResponse response)
      throws IOException {
    Transaction reply = wallet.getTransactionById(txId);
    if (reply != null) {
      response.getWriter().println(Util.printTransaction(reply, visible));
    } else {
      response.getWriter().println("{}");
    }
  }
}