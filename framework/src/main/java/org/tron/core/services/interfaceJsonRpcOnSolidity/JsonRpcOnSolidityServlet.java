package org.tron.core.services.interfaceJsonRpcOnSolidity;

import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.core.services.interfaceOnSolidity.WalletOnSolidity;
import org.tron.core.services.jsonrpc.ArchiveJsonRpcExecutor;
import org.tron.core.services.jsonrpc.JsonRpcServlet;

@Component
@Slf4j(topic = "API")
public class JsonRpcOnSolidityServlet extends JsonRpcServlet {

  @Autowired
  private WalletOnSolidity walletOnSolidity;

  @Override
  protected void executeWithStateCursor(ArchiveJsonRpcExecutor.RequestTask task)
      throws IOException {
    walletOnSolidity.futureGetWithIOException(task::run);
  }
}
