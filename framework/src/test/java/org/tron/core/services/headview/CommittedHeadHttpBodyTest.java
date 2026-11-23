package org.tron.core.services.headview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.io.ByteStreams;
import com.google.protobuf.ByteString;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.tron.api.GrpcAPI.WitnessList;
import org.tron.common.TestConstants;
import org.tron.common.application.RequestSizeLimitHandler;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.Sha256Hash;
import org.tron.common.utils.StringUtil;
import org.tron.core.Wallet;
import org.tron.core.capsule.BlockCapsule.BlockId;
import org.tron.core.config.args.Args;
import org.tron.core.db.Manager;
import org.tron.core.db2.view.HeadView;
import org.tron.core.db2.view.HeadViewRegistry;
import org.tron.core.services.http.HttpApi.Surface;
import org.tron.core.services.http.servlets.GetAssetIssueByNameServlet;
import org.tron.core.services.http.servlets.ListWitnessesServlet;
import org.tron.core.services.http.servlets.RateLimiterServlet;
import org.tron.core.services.http.servlets.TriggerConstantContractServlet;
import org.tron.core.services.interfaceOnPBFT.WalletOnPBFT;
import org.tron.core.services.ratelimiter.RateLimiterContainer;
import org.tron.protos.Protocol.BlockHeader;
import org.tron.protos.Protocol.Witness;
import org.tron.protos.contract.AssetIssueContractOuterClass.AssetIssueContract;

public class CommittedHeadHttpBodyTest {

  private static final String PATH = "/wallet/listwitnesses";
  private static final String ASSET_BY_NAME = "/wallet/getassetissuebyname";
  private static final String TRIGGER_CONSTANT = "/wallet/triggerconstantcontract";
  private static final String FORM = "application/x-www-form-urlencoded";
  private static final byte[] VISIBLE = "visible=true".getBytes(StandardCharsets.UTF_8);
  private static final byte[] WITNESS =
      ByteArray.fromHexString("41" + "1111111111111111111111111111111111111111");

  private final List<ByteString> assetNames = new ArrayList<>();
  private Server server;
  private int port;

  @Before
  public void startJetty() throws Exception {
    Args.setParam(new String[0], TestConstants.TEST_CONF);
    HeadViewRegistry registry = new HeadViewRegistry(8);
    registry.publish(new HeadView(new BlockId(Sha256Hash.ZERO_HASH, 7),
        BlockHeader.getDefaultInstance(), Collections.emptyMap()), registry.currentGeneration());
    registry.markReady();
    Manager manager = mock(Manager.class);
    when(manager.getHeadViewRegistry()).thenReturn(registry);
    CommittedHeadQuery query = new CommittedHeadQuery();
    ReflectionTestUtils.setField(query, "manager", manager);

    Wallet wallet = mock(Wallet.class);
    when(wallet.getWitnessList()).thenReturn(WitnessList.newBuilder()
        .addWitnesses(Witness.newBuilder().setAddress(ByteString.copyFrom(WITNESS))).build());
    when(wallet.getAssetIssueByName(any())).thenAnswer(invocation -> {
      assetNames.add(invocation.getArgument(0));
      return AssetIssueContract.getDefaultInstance();
    });

    ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
    context.setContextPath("/");
    context.setAttribute(RateLimiterServlet.SURFACE_ATTRIBUTE, Surface.FULL);
    register(context, new ListWitnessesServlet(), PATH, query, wallet);
    register(context, new GetAssetIssueByNameServlet(), ASSET_BY_NAME, query, wallet);
    register(context, new TriggerConstantContractServlet(), TRIGGER_CONSTANT, query, wallet);
    RequestSizeLimitHandler limit = new RequestSizeLimitHandler(4 * 1024 * 1024);
    limit.setHandler(context);
    server = new Server();
    ServerConnector connector = new ServerConnector(server);
    connector.setHost("127.0.0.1");
    connector.setPort(0);
    server.addConnector(connector);
    server.setHandler(limit);
    server.start();
    port = connector.getLocalPort();
  }

  @After
  public void stopJetty() throws Exception {
    server.stop();
    Args.clearParam();
  }

  @Test
  public void formPostWithTheSelectorIsRejected() throws Exception {
    for (String contentType : new String[] {FORM, FORM + "; charset=UTF-8",
        "Application/X-WWW-Form-Urlencoded"}) {
      Reply selected = post(PATH, contentType, VISIBLE, true);
      assertEquals(contentType, 400, selected.status);
      assertTrue(contentType, selected.body.contains("INVALID_STATE_VIEW"));
      assertNull(contentType, selected.blockNumber);

      Reply plain = post(PATH, contentType, VISIBLE, false);
      assertEquals(contentType, 200, plain.status);
      assertTrue(contentType, plain.body.contains(base58Witness()));
    }
  }

  @Test
  public void nonFormContentTypeIsNotParsedOnEitherPath() throws Exception {
    String contentType = FORM + "x";
    Reply selected = post(PATH, contentType, VISIBLE, true);
    Reply plain = post(PATH, contentType, VISIBLE, false);

    assertEquals(200, selected.status);
    assertEquals("7", selected.blockNumber);
    assertEquals(plain.body, selected.body);
    assertTrue(selected.body.contains(ByteArray.toHexString(WITNESS)));
  }

  @Test
  public void queryParametersSurviveTheCachedBody() throws Exception {
    byte[] json = "{}".getBytes(StandardCharsets.UTF_8);
    Reply selected = post(PATH + "?visible=true", "application/json", json, true);

    assertEquals(200, selected.status);
    assertNotNull(selected.blockNumber);
    assertTrue(selected.body.contains(base58Witness()));
  }

  @Test
  public void jsonBodyIsDecodedLikeJettyWithAndWithoutTheSelector() throws Exception {
    String name = new String(Character.toChars(0x4E2D));
    byte[] json = ("{\"value\":\"" + name + "\",\"visible\":true}")
        .getBytes(StandardCharsets.UTF_8);
    for (String contentType : new String[] {"application/json", "application/json;v=1",
        "application/json; charset=utf-8", "text/plain"}) {
      assetNames.clear();
      Reply plain = post(ASSET_BY_NAME, contentType, json, false);
      Reply selected = post(ASSET_BY_NAME, contentType, json, true);

      assertEquals(contentType, 200, selected.status);
      assertNotNull(contentType, selected.blockNumber);
      assertEquals(contentType, plain.body, selected.body);
      assertEquals(contentType, 2, assetNames.size());
      assertEquals(contentType, assetNames.get(0), assetNames.get(1));
    }
  }

  @Test
  public void unsupportedBodyCharsetFailsLikeJetty() throws Exception {
    byte[] json = "{}".getBytes(StandardCharsets.UTF_8);
    String failure = ByteArray.toHexString(("class " + UnsupportedEncodingException.class.getName())
        .getBytes(StandardCharsets.UTF_8));
    for (String contentType : new String[] {"application/json; charset=nosuchcharset",
        "application/json; charset=\"no such charset\""}) {
      Reply plain = post(TRIGGER_CONSTANT, contentType, json, false);
      Reply selected = post(TRIGGER_CONSTANT, contentType, json, true);

      assertEquals(contentType, 200, plain.status);
      assertTrue(contentType, plain.body.contains(failure));
      assertEquals(contentType, 200, selected.status);
      assertNotNull(contentType, selected.blockNumber);
      assertEquals(contentType, plain.body, selected.body);
    }
  }

  private static void register(ServletContextHandler context, RateLimiterServlet servlet,
      String path, CommittedHeadQuery query, Wallet wallet) {
    ReflectionTestUtils.setField(servlet, "container", new RateLimiterContainer());
    ReflectionTestUtils.setField(servlet, "walletOnPBFT", mock(WalletOnPBFT.class));
    ReflectionTestUtils.setField(servlet, "committedHeadQuery", query);
    ReflectionTestUtils.setField(servlet, "wallet", wallet);
    context.addServlet(new ServletHolder(servlet), path);
  }

  private static String base58Witness() {
    return StringUtil.encode58Check(WITNESS);
  }

  private Reply post(String path, String contentType, byte[] body, boolean selector)
      throws Exception {
    HttpURLConnection connection =
        (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
    try {
      connection.setRequestMethod("POST");
      connection.setDoOutput(true);
      connection.setRequestProperty("Content-Type", contentType);
      if (selector) {
        connection.setRequestProperty(CommittedHeadQuery.HTTP_SELECTOR,
            CommittedHeadQuery.COMMITTED_HEAD);
      }
      connection.setFixedLengthStreamingMode(body.length);
      try (OutputStream out = connection.getOutputStream()) {
        out.write(body);
      }
      Reply reply = new Reply();
      reply.status = connection.getResponseCode();
      InputStream in = reply.status < 400
          ? connection.getInputStream() : connection.getErrorStream();
      reply.body = in == null ? "" : new String(ByteStreams.toByteArray(in),
          StandardCharsets.UTF_8);
      reply.blockNumber = connection.getHeaderField(CommittedHeadQuery.HTTP_BLOCK_NUMBER);
      return reply;
    } finally {
      connection.disconnect();
    }
  }

  private static final class Reply {

    private int status;
    private String body;
    private String blockNumber;
  }
}
