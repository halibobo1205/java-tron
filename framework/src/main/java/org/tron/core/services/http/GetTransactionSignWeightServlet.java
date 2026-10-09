package org.tron.core.services.http;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.api.GrpcAPI.TransactionSignWeight;
import org.tron.core.exception.ContractSizeNotEqualToOneException;
import org.tron.core.utils.TransactionUtil;
import org.tron.protos.Protocol.Transaction;


@Component
@Slf4j(topic = "API")
public class GetTransactionSignWeightServlet extends RateLimiterServlet {

  @Autowired
  private TransactionUtil transactionUtil;

  protected void doGet(HttpServletRequest request, HttpServletResponse response) {

  }

  protected void doPost(HttpServletRequest request, HttpServletResponse response) {
    try {
      PostParams params = PostParams.getPostParams(request);
      TransactionSignWeight reply;
      try {
        Transaction transaction = Util.packTransaction(params.getParams(), params.isVisible());
        reply = transactionUtil.getTransactionSignWeight(transaction);
      } catch (ContractSizeNotEqualToOneException e) {
        TransactionSignWeight.Result result = TransactionSignWeight.Result.newBuilder()
            .setCode(TransactionSignWeight.Result.response_code.OTHER_ERROR)
            .setMessage("Invalid transaction: " + e.getMessage())
            .build();
        reply = TransactionSignWeight.newBuilder().setResult(result).build();
      }
      if (reply != null) {
        response.getWriter().println(Util.printTransactionSignWeight(reply, params.isVisible()));
      } else {
        response.getWriter().println("{}");
      }
    } catch (Exception e) {
      Util.processError(e, response);
    }
  }
}
