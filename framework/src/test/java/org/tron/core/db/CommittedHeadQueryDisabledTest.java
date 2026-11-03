package org.tron.core.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.tron.common.BaseMethodTest;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BlockCapsule.BlockId;
import org.tron.core.config.args.Args;
import org.tron.core.db2.view.HeadLease;
import org.tron.core.db2.view.HeadView;
import org.tron.core.db2.view.HeadViewRegistry;
import org.tron.core.db2.view.QueryViewScope;
import org.tron.core.services.headview.CommittedHeadQuery;
import org.tron.core.services.http.HttpApi.Surface;
import org.tron.core.services.http.servlets.GetAccountServlet;
import org.tron.core.services.http.servlets.RateLimiterServlet;
import org.tron.protos.Protocol.Account;
import org.tron.protos.Protocol.BlockHeader;

public class CommittedHeadQueryDisabledTest extends BaseMethodTest {

  private static final ByteString ADDRESS = ByteString.copyFrom(
      ByteArray.fromHexString("41548794500882809695a8a687866e76d4271a1abc"));

  @Override
  protected void beforeContext() {
    Args.getInstance().setFullNodeHttpEnable(true);
  }

  @Test
  public void disabledFeatureLeavesEveryPathUnchanged() throws Exception {
    assertFalse(Args.getInstance().isCommittedHeadQueryEnable());
    assertNull(dbManager.getHeadViewRegistry());
    assertFalse(context.getBean(CommittedHeadQuery.class).isEnabled());

    chainBaseManager.getAccountStore().put(ADDRESS.toByteArray(),
        new AccountCapsule(Account.newBuilder().setAddress(ADDRESS).setBalance(7).build()));

    MockHttpServletResponse withSelector = post(true);
    MockHttpServletResponse plain = post(false);
    assertEquals(200, withSelector.getStatus());
    assertNull(withSelector.getHeader(CommittedHeadQuery.HTTP_BLOCK_NUMBER));
    assertEquals(plain.getContentAsString(), withSelector.getContentAsString());

    try (HeadLease foreign = foreignLease();
        QueryViewScope ignored = QueryViewScope.bind(foreign)) {
      assertEquals(7, chainBaseManager.getAccountStore().get(ADDRESS.toByteArray())
          .getBalance());
    }
  }

  private MockHttpServletResponse post(boolean selector) throws Exception {
    MockServletContext servletContext = new MockServletContext();
    servletContext.setAttribute(RateLimiterServlet.SURFACE_ATTRIBUTE, Surface.FULL);
    MockHttpServletRequest request = new MockHttpServletRequest(servletContext);
    request.setMethod("POST");
    request.setContentType("application/json");
    request.setContent(("{\"address\":\"" + ByteArray.toHexString(ADDRESS.toByteArray())
        + "\"}").getBytes(StandardCharsets.UTF_8));
    if (selector) {
      request.addHeader(CommittedHeadQuery.HTTP_SELECTOR, CommittedHeadQuery.COMMITTED_HEAD);
    }
    MockHttpServletResponse response = new MockHttpServletResponse();
    context.getBean(GetAccountServlet.class).service(request, response);
    return response;
  }

  private static HeadLease foreignLease() {
    HeadViewRegistry registry = new HeadViewRegistry(1);
    long generation = registry.currentGeneration();
    registry.publish(new HeadView(new BlockId(Sha256Hash.ZERO_HASH, 0),
        BlockHeader.getDefaultInstance(), Collections.emptyMap()), generation);
    registry.markReady();
    return registry.acquire();
  }
}
