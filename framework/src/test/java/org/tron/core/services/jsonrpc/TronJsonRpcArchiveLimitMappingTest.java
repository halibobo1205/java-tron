package org.tron.core.services.jsonrpc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.googlecode.jsonrpc4j.ErrorResolver.JsonError;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.tron.core.archive.query.HistoricalQueryLimitException;
import org.tron.core.archive.query.HistoricalQueryLimitException.Limit;
import org.tron.core.archive.query.HistoricalQueryLimitException.Reason;
import org.tron.core.services.jsonrpc.types.CallArguments;

public class TronJsonRpcArchiveLimitMappingTest {

  private static final List<JsonNode> NO_ARGUMENTS = Collections.emptyList();

  @Test
  public void archiveStateMethodsMapEveryTypedLimitReasonToResourceLimit() throws Exception {
    for (Method method : archiveStateMethods()) {
      assertResourceLimit(method, Reason.RESOURCE_EXHAUSTED, Limit.BACKEND_READS);
      assertResourceLimit(method, Reason.DEADLINE, Limit.DEADLINE);
    }
  }

  @Test
  public void liveOnlyMethodDoesNotClaimHistoricalLimitMapping() throws Exception {
    Method estimateGas = TronJsonRpc.class.getMethod("estimateGas", CallArguments.class);
    HistoricalQueryLimitException failure = new HistoricalQueryLimitException(
        Reason.RESOURCE_EXHAUSTED, Limit.BACKEND_READS, "historical query limit reached");

    assertNull(JsonRpcErrorResolver.INSTANCE.resolveError(failure, estimateGas, NO_ARGUMENTS));
  }

  private static List<Method> archiveStateMethods() throws NoSuchMethodException {
    return Arrays.asList(
        TronJsonRpc.class.getMethod("getTrxBalance", String.class, Object.class),
        TronJsonRpc.class.getMethod(
            "getStorageAt", String.class, String.class, Object.class),
        TronJsonRpc.class.getMethod("getABIOfSmartContract", String.class, Object.class),
        TronJsonRpc.class.getMethod("getCall", CallArguments.class, Object.class));
  }

  private static void assertResourceLimit(Method method, Reason reason, Limit limit) {
    String message = "historical query " + reason.name();
    HistoricalQueryLimitException failure =
        new HistoricalQueryLimitException(reason, limit, message);

    JsonError error = JsonRpcErrorResolver.INSTANCE.resolveError(
        failure, method, NO_ARGUMENTS);

    assertNotNull(method.getName(), error);
    assertEquals(method.getName(), -32005, error.code);
    assertEquals(method.getName(), message, error.message);
    assertEquals(method.getName(), "{}", error.data);
  }
}
