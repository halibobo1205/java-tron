package org.tron.core.services.interfaceJsonRpcOnPBFT;

import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.core.services.interfaceOnPBFT.WalletOnPBFT;
import org.tron.core.services.jsonrpc.ArchiveJsonRpcExecutor;
import org.tron.core.services.jsonrpc.JsonRpcServlet;

@Component
@Slf4j(topic = "API")
public class JsonRpcOnPBFTServlet extends JsonRpcServlet {

  @Autowired
  private WalletOnPBFT walletOnPBFT;

  @Override
  protected void executeWithStateCursor(ArchiveJsonRpcExecutor.RequestTask task)
      throws IOException {
    walletOnPBFT.futureGetWithIOException(task::run);
  }
}
