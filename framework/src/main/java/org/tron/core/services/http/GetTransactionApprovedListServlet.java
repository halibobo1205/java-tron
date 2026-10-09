package org.tron.core.services.http;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.api.GrpcAPI.TransactionApprovedList;
import org.tron.core.Wallet;
import org.tron.core.exception.ContractSizeNotEqualToOneException;
import org.tron.protos.Protocol.Transaction;


@Component
@Slf4j(topic = "API")
public class GetTransactionApprovedListServlet extends RateLimiterServlet {

  @Autowired
  private Wallet wallet;

  protected void doGet(HttpServletRequest request, HttpServletResponse response) {

  }

  protected void doPost(HttpServletRequest request, HttpServletResponse response) {
    try {
      PostParams params = PostParams.getPostParams(request);
      TransactionApprovedList reply;
      try {
        Transaction transaction = Util.packTransaction(params.getParams(), params.isVisible());
        reply = wallet.getTransactionApprovedList(transaction);
      } catch (ContractSizeNotEqualToOneException e) {
        TransactionApprovedList.Result result = TransactionApprovedList.Result.newBuilder()
            .setCode(TransactionApprovedList.Result.response_code.OTHER_ERROR)
            .setMessage("Invalid transaction: " + e.getMessage())
            .build();
        reply = TransactionApprovedList.newBuilder().setResult(result).build();
      }
      if (reply != null) {
        response.getWriter().println(Util.printTransactionApprovedList(reply, params.isVisible()));
      } else {
        response.getWriter().println("{}");
      }
    } catch (Exception e) {
      Util.processError(e, response);
    }
  }
}
