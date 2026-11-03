package org.tron.core.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import io.grpc.BindableService;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerInterceptors;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.tron.api.GrpcAPI.Return;
import org.tron.api.GrpcAPI.TransactionExtention;
import org.tron.api.GrpcAPI.WitnessList;
import org.tron.api.WalletGrpc;
import org.tron.common.BaseMethodTest;
import org.tron.common.crypto.ECKey;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.Sha256Hash;
import org.tron.common.utils.StringUtil;
import org.tron.common.utils.Utils;
import org.tron.consensus.dpos.DposSlot;
import org.tron.core.ChainBaseManager;
import org.tron.core.Wallet;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.capsule.WitnessCapsule;
import org.tron.core.config.args.Args;
import org.tron.core.consensus.ConsensusService;
import org.tron.core.db2.view.HeadLease;
import org.tron.core.db2.view.HeadViewException;
import org.tron.core.db2.view.HeadViewException.Reason;
import org.tron.core.db2.view.HeadViewRegistry;
import org.tron.core.db2.view.QueryViewScope;
import org.tron.core.exception.ValidateSignatureException;
import org.tron.core.services.RpcApiService;
import org.tron.core.services.headview.CommittedHeadGrpcInterceptor;
import org.tron.core.services.headview.CommittedHeadQuery;
import org.tron.core.services.http.HttpApi.Surface;
import org.tron.core.services.http.servlets.GetAccountServlet;
import org.tron.core.services.http.servlets.GetNodeInfoServlet;
import org.tron.core.services.http.servlets.GetNowBlockServlet;
import org.tron.core.services.http.servlets.ListWitnessesServlet;
import org.tron.core.services.http.servlets.RateLimiterServlet;
import org.tron.protos.Protocol.Account;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;
import org.tron.protos.contract.SmartContractOuterClass.TriggerSmartContract;

public class CommittedHeadQueryIntegrationTest extends BaseMethodTest {

  private static final long BASE_TIME = 1533529947000L;
  private static final String BLOCK_NUMBER_INITCODE = "4360005260206000f3";
  private static final AtomicInteger PORT = new AtomicInteger(0);

  private ChainBaseManager chain;
  private DposSlot dposSlot;
  private Map<ByteString, String> witnessKeys;

  @Override
  protected void beforeContext() {
    Args.getInstance().setCommittedHeadQueryEnable(true);
    Args.getInstance().setFullNodeHttpEnable(true);
  }

  @Override
  protected void afterInit() {
    Args.getInstance().setNodeListenPort(12000 + PORT.incrementAndGet());
    dposSlot = context.getBean(DposSlot.class);
    context.getBean(ConsensusService.class).start();
    chain = dbManager.getChainBaseManager();
  }

  @Test
  public void registryStartsClosedUntilReady() {
    HeadViewRegistry registry = dbManager.getHeadViewRegistry();
    assertNotNull(registry);
    HeadViewException starting = assertThrows(HeadViewException.class, registry::acquire);
    assertEquals(Reason.UNAVAILABLE, starting.getReason());

    dbManager.markCommittedHeadReady();
    try (HeadLease lease = registry.acquire()) {
      assertEquals(0, lease.getView().getBlockNum());
      assertEquals(0, headNumberIn(lease));
      assertTrue(lease.seal());
    }
  }

  @Test
  public void committedBlocksPublishAndLeasesStayPinned() throws Exception {
    witnessKeys = addWitnesses();
    dbManager.markCommittedHeadReady();
    HeadViewRegistry registry = dbManager.getHeadViewRegistry();

    long generation = registry.currentGeneration();
    BlockCapsule block1 = pushNext(BASE_TIME + 3000);
    HeadLease pinned = registry.acquire();
    assertEquals(block1.getBlockId(), pinned.getView().getBlockId());

    BlockCapsule block2 = pushNext(BASE_TIME + 6000);
    assertEquals(2, chain.getHeadBlockNum());
    assertEquals(1, headNumberIn(pinned));
    assertTrue(pinned.seal());
    pinned.close();

    try (HeadLease latest = registry.acquire()) {
      assertEquals(block2.getBlockId(), latest.getView().getBlockId());
      assertEquals(2, headNumberIn(latest));
      assertTrue(latest.seal());
    }
    assertEquals(generation, registry.currentGeneration());
  }

  @Test
  public void receivedBlocksPublishOnlyAfterAdmission() throws Exception {
    witnessKeys = addWitnesses();
    dbManager.markCommittedHeadReady();
    HeadViewRegistry registry = dbManager.getHeadViewRegistry();
    BlockCapsule block1 = pushNext(BASE_TIME + 3000);
    long generation = registry.currentGeneration();
    HeadLease pinned = registry.acquire();

    BlockCapsule forged = received(BASE_TIME + 6000, block1);
    forged.sign(new ECKey(Utils.getRandom()).getPrivKey().toByteArray());
    assertThrows(ValidateSignatureException.class, () -> dbManager.pushBlock(forged));
    assertEquals(1, chain.getHeadBlockNum());
    assertEquals(generation, registry.currentGeneration());
    assertTrue(pinned.seal());
    try (HeadLease afterReject = registry.acquire()) {
      assertEquals(block1.getBlockId(), afterReject.getView().getBlockId());
    }

    BlockCapsule block2 = received(BASE_TIME + 6000, block1);
    dbManager.pushBlock(block2);
    try (HeadLease afterAdmission = registry.acquire()) {
      assertEquals(block2.getBlockId(), afterAdmission.getView().getBlockId());
      assertEquals(2, headNumberIn(afterAdmission));
      assertTrue(afterAdmission.seal());
    }
    assertEquals(1, headNumberIn(pinned));
    assertTrue(pinned.seal());
    pinned.close();
    assertEquals(generation, registry.currentGeneration());
  }

  @Test
  public void switchForkPublishesTheFinalHead() throws Exception {
    witnessKeys = addWitnesses();
    dbManager.markCommittedHeadReady();
    HeadViewRegistry registry = dbManager.getHeadViewRegistry();

    long generation = registry.currentGeneration();
    pushNext(BASE_TIME + 3000);
    BlockCapsule forkBase = pushNext(BASE_TIME + 6000);
    BlockCapsule losing = pushNext(BASE_TIME + 9000);
    HeadLease onLosingBranch = registry.acquire();
    assertEquals(losing.getBlockId(), onLosingBranch.getView().getBlockId());

    BlockCapsule sibling = createBlock(BASE_TIME + 12000, 3,
        forkBase.getBlockId().getByteString());
    dbManager.pushBlock(sibling);
    assertEquals(losing.getBlockId(), chain.getHeadBlockId());
    BlockCapsule winning = createBlock(BASE_TIME + 15000, 4,
        sibling.getBlockId().getByteString());
    dbManager.pushBlock(winning);
    assertEquals(winning.getBlockId(), chain.getHeadBlockId());

    try (HeadLease afterSwitch = registry.acquire()) {
      assertEquals(winning.getBlockId(), afterSwitch.getView().getBlockId());
      assertEquals(4, headNumberIn(afterSwitch));
      assertTrue(afterSwitch.seal());
    }
    assertEquals(3, headNumberIn(onLosingBranch));
    assertEquals(losing.getBlockId(), onLosingBranch.getView().getBlockId());
    assertTrue(onLosingBranch.seal());
    onLosingBranch.close();
    assertEquals(generation, registry.currentGeneration());
  }

  @Test
  public void genesisWithPendingDefersReadinessToTheFirstBlock() throws Exception {
    witnessKeys = addWitnesses();
    HeadViewRegistry registry = dbManager.getHeadViewRegistry();
    dbManager.getSession().setValue(dbManager.getRevokingStore().buildSession());
    dbManager.markCommittedHeadReady();
    assertThrows(HeadViewException.class, registry::acquire);

    BlockCapsule block1 = pushNext(BASE_TIME + 3000);
    try (HeadLease lease = registry.acquire()) {
      assertEquals(block1.getBlockId(), lease.getView().getBlockId());
      assertTrue(lease.seal());
    }
  }

  @Test
  public void httpCommittedHeadIgnoresPendingState() throws Exception {
    witnessKeys = addWitnesses();
    dbManager.markCommittedHeadReady();
    pushNext(BASE_TIME + 3000);
    ByteString address = witnessKeys.keySet().iterator().next();
    String committedBody = postAccount(address, true).getContentAsString();

    dbManager.getSession().setValue(dbManager.getRevokingStore().buildSession());
    try {
      AccountCapsule account = chain.getAccountStore().get(address.toByteArray());
      account.setBalance(123_456_789L);
      chain.getAccountStore().put(address.toByteArray(), account);

      MockHttpServletResponse plain = postAccount(address, false);
      MockHttpServletResponse committed = postAccount(address, true);
      assertTrue(plain.getContentAsString().contains("123456789"));
      assertEquals(committedBody, committed.getContentAsString());
      assertEquals("1", committed.getHeader(CommittedHeadQuery.HTTP_BLOCK_NUMBER));
    } finally {
      dbManager.getSession().reset();
    }
  }

  @Test
  public void httpFailurePaths() throws Exception {
    witnessKeys = addWitnesses();
    ByteString address = witnessKeys.keySet().iterator().next();

    MockHttpServletResponse starting = postAccount(address, true);
    assertEquals(503, starting.getStatus());
    assertTrue(starting.getContentAsString().contains("HEAD_VIEW_UNAVAILABLE"));
    assertNull(starting.getHeader(CommittedHeadQuery.HTTP_BLOCK_NUMBER));

    dbManager.markCommittedHeadReady();
    pushNext(BASE_TIME + 3000);

    MockHttpServletRequest duplicate = accountRequest(address, true, Surface.FULL);
    duplicate.addHeader(CommittedHeadQuery.HTTP_SELECTOR, CommittedHeadQuery.COMMITTED_HEAD);
    MockHttpServletResponse duplicated = new MockHttpServletResponse();
    context.getBean(GetAccountServlet.class).service(duplicate, duplicated);
    assertEquals(400, duplicated.getStatus());

    MockHttpServletRequest wrongValue = accountRequest(address, false, Surface.FULL);
    wrongValue.addHeader(CommittedHeadQuery.HTTP_SELECTOR, "latest");
    MockHttpServletResponse rejected = new MockHttpServletResponse();
    context.getBean(GetAccountServlet.class).service(wrongValue, rejected);
    assertEquals(400, rejected.getStatus());

    Args.getInstance().setCommittedHeadQueryMaxResponseSize(8);
    MockHttpServletResponse tooLarge = postAccount(address, true);
    assertEquals(500, tooLarge.getStatus());
    assertTrue(tooLarge.getContentAsString().contains("HEAD_VIEW_RESPONSE_TOO_LARGE"));
    Args.getInstance().setCommittedHeadQueryMaxResponseSize(8388608);

    HeadViewRegistry registry = dbManager.getHeadViewRegistry();
    HeadLease[] held = new HeadLease[Args.getInstance().getCommittedHeadQueryMaxActiveRequests()];
    for (int i = 0; i < held.length; i++) {
      held[i] = registry.acquire();
    }
    try {
      MockHttpServletResponse full = postAccount(address, true);
      assertEquals(503, full.getStatus());
    } finally {
      for (HeadLease lease : held) {
        lease.close();
      }
    }
    assertEquals(200, postAccount(address, true).getStatus());
  }

  @Test
  public void grpcGetAccountThroughTheRealService() throws Exception {
    witnessKeys = addWitnesses();
    dbManager.markCommittedHeadReady();
    BlockCapsule block1 = pushNext(BASE_TIME + 3000);
    ByteString address = witnessKeys.keySet().iterator().next();
    BindableService walletApi = (BindableService) readField(
        context.getBean(RpcApiService.class), "walletApi");
    ServerServiceDefinition service = ServerInterceptors.intercept(walletApi,
        context.getBean(CommittedHeadQuery.class).grpcInterceptor());

    RecordingCall committed = callGetAccount(service, address, true);
    assertTrue(committed.status.isOk());
    assertEquals("1", committed.headers.get(CommittedHeadGrpcInterceptor.BLOCK_NUMBER));
    assertEquals(ByteArray.toHexString(block1.getBlockId().getBytes()),
        committed.headers.get(CommittedHeadGrpcInterceptor.BLOCK_ID));
    assertEquals(address, committed.message.getAddress());

    RecordingCall plain = callGetAccount(service, address, false);
    assertTrue(plain.status.isOk());
    assertNull(plain.headers.get(CommittedHeadGrpcInterceptor.BLOCK_NUMBER));
    assertEquals(committed.message, plain.message);
  }

  @SuppressWarnings("unchecked")
  private static RecordingCall callGetAccount(ServerServiceDefinition service,
      ByteString address, boolean selector) {
    ServerMethodDefinition<Account, Account> method = (ServerMethodDefinition<Account, Account>)
        service.getMethod(WalletGrpc.getGetAccountMethod().getFullMethodName());
    RecordingCall call = new RecordingCall(method.getMethodDescriptor());
    Metadata headers = new Metadata();
    if (selector) {
      headers.put(CommittedHeadGrpcInterceptor.SELECTOR, CommittedHeadQuery.COMMITTED_HEAD);
    }
    ServerCall.Listener<Account> listener =
        method.getServerCallHandler().startCall(call, headers);
    listener.onMessage(Account.newBuilder().setAddress(address).build());
    listener.onHalfClose();
    listener.onComplete();
    return call;
  }

  private static Object readField(Object target, String name) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private MockHttpServletResponse postAccount(ByteString address, boolean selector)
      throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    context.getBean(GetAccountServlet.class)
        .service(accountRequest(address, selector, Surface.FULL), response);
    return response;
  }

  private static final class RecordingCall extends ServerCall<Account, Account> {

    private final MethodDescriptor<Account, Account> method;
    private Metadata headers;
    private Account message;
    private Status status;

    RecordingCall(MethodDescriptor<Account, Account> method) {
      this.method = method;
    }

    @Override
    public void request(int numMessages) {
    }

    @Override
    public void sendHeaders(Metadata headers) {
      this.headers = headers;
    }

    @Override
    public void sendMessage(Account message) {
      this.message = message;
    }

    @Override
    public void close(Status status, Metadata trailers) {
      this.status = status;
    }

    @Override
    public boolean isCancelled() {
      return false;
    }

    @Override
    public MethodDescriptor<Account, Account> getMethodDescriptor() {
      return method;
    }
  }

  @Test
  public void httpSelectorReturnsResolvedBlock() throws Exception {
    witnessKeys = addWitnesses();
    dbManager.markCommittedHeadReady();
    BlockCapsule block1 = pushNext(BASE_TIME + 3000);
    ByteString address = witnessKeys.keySet().iterator().next();

    MockHttpServletResponse committed = new MockHttpServletResponse();
    context.getBean(GetAccountServlet.class)
        .service(accountRequest(address, true, Surface.FULL), committed);
    assertEquals(200, committed.getStatus());
    assertEquals("1", committed.getHeader(CommittedHeadQuery.HTTP_BLOCK_NUMBER));
    assertEquals(ByteArray.toHexString(block1.getBlockId().getBytes()),
        committed.getHeader(CommittedHeadQuery.HTTP_BLOCK_ID));
    assertTrue(committed.getContentAsString().contains(ByteArray.toHexString(
        address.toByteArray())));

    MockHttpServletResponse plain = new MockHttpServletResponse();
    context.getBean(GetAccountServlet.class)
        .service(accountRequest(address, false, Surface.FULL), plain);
    assertNull(plain.getHeader(CommittedHeadQuery.HTTP_BLOCK_NUMBER));
    assertEquals(committed.getContentAsString(), plain.getContentAsString());

    MockHttpServletResponse solidity = new MockHttpServletResponse();
    context.getBean(GetAccountServlet.class)
        .service(accountRequest(address, true, Surface.SOLIDITY), solidity);
    assertEquals(400, solidity.getStatus());
    assertTrue(solidity.getContentAsString().contains("INVALID_STATE_VIEW"));

    MockHttpServletRequest nonPilot = new MockHttpServletRequest(fullContext(Surface.FULL));
    nonPilot.setMethod("GET");
    nonPilot.addHeader(CommittedHeadQuery.HTTP_SELECTOR, CommittedHeadQuery.COMMITTED_HEAD);
    MockHttpServletResponse rejected = new MockHttpServletResponse();
    context.getBean(GetNodeInfoServlet.class).service(nonPilot, rejected);
    assertEquals(400, rejected.getStatus());
  }

  @Test
  public void rangeReadsSeeTheBlockOfTheView() throws Exception {
    witnessKeys = addWitnesses();
    dbManager.markCommittedHeadReady();
    HeadViewRegistry registry = dbManager.getHeadViewRegistry();
    Wallet wallet = context.getBean(Wallet.class);

    pushNext(BASE_TIME + 3000);
    HashSet<Object> witnessesAt1 = witnessSet(wallet.getWitnessList());
    HeadLease pinned = registry.acquire();
    pushNext(BASE_TIME + 6000);
    assertNotEquals(witnessesAt1, witnessSet(wallet.getWitnessList()));
    assertEquals(2, new BlockCapsule(wallet.getNowBlock()).getNum());

    try (QueryViewScope ignored = QueryViewScope.bind(pinned)) {
      assertEquals(1, new BlockCapsule(wallet.getNowBlock()).getNum());
      assertEquals(witnessesAt1, witnessSet(wallet.getWitnessList()));
    }
    assertTrue(pinned.seal());
    pinned.close();

    MockHttpServletResponse committed = new MockHttpServletResponse();
    context.getBean(GetNowBlockServlet.class).service(getRequest(true), committed);
    assertEquals(200, committed.getStatus());
    assertEquals("2", committed.getHeader(CommittedHeadQuery.HTTP_BLOCK_NUMBER));
    MockHttpServletResponse plain = new MockHttpServletResponse();
    context.getBean(GetNowBlockServlet.class).service(getRequest(false), plain);
    assertEquals(plain.getContentAsString(), committed.getContentAsString());

    MockHttpServletResponse witnesses = new MockHttpServletResponse();
    context.getBean(ListWitnessesServlet.class).service(getRequest(true), witnesses);
    assertEquals(200, witnesses.getStatus());
    assertEquals("2", witnesses.getHeader(CommittedHeadQuery.HTTP_BLOCK_NUMBER));
    MockHttpServletResponse plainWitnesses = new MockHttpServletResponse();
    context.getBean(ListWitnessesServlet.class).service(getRequest(false), plainWitnesses);
    assertEquals(plainWitnesses.getContentAsString(), witnesses.getContentAsString());
  }

  @Test
  public void formPostKeepsItsParameters() throws Exception {
    witnessKeys = addWitnesses();
    dbManager.markCommittedHeadReady();
    pushNext(BASE_TIME + 3000);

    MockHttpServletRequest request = new MockHttpServletRequest(fullContext(Surface.FULL));
    request.setMethod("POST");
    request.setContentType("application/x-www-form-urlencoded");
    request.setContent("visible=true".getBytes(StandardCharsets.UTF_8));
    request.addHeader(CommittedHeadQuery.HTTP_SELECTOR, CommittedHeadQuery.COMMITTED_HEAD);
    MockHttpServletResponse response = new MockHttpServletResponse();
    context.getBean(ListWitnessesServlet.class).service(request, response);

    assertEquals(200, response.getStatus());
    ByteString witness = witnessKeys.keySet().iterator().next();
    assertTrue(response.getContentAsString()
        .contains(StringUtil.encode58Check(witness.toByteArray())));
  }

  private static HashSet<Object> witnessSet(WitnessList witnesses) {
    return new HashSet<>(witnesses.getWitnessesList());
  }

  private static MockHttpServletRequest getRequest(boolean selector) {
    MockHttpServletRequest request = new MockHttpServletRequest(fullContext(Surface.FULL));
    request.setMethod("GET");
    if (selector) {
      request.addHeader(CommittedHeadQuery.HTTP_SELECTOR, CommittedHeadQuery.COMMITTED_HEAD);
    }
    return request;
  }

  @Test
  public void constantCallSeesTheBlockOfTheView() throws Exception {
    Args.getInstance().setSupportConstant(true);
    Args.getInstance().setConstantCallTimeoutMs(10_000);
    witnessKeys = addWitnesses();
    chain.getDynamicPropertiesStore().saveAllowCreationOfContracts(1);
    dbManager.markCommittedHeadReady();
    HeadViewRegistry registry = dbManager.getHeadViewRegistry();
    ByteString owner = witnessKeys.keySet().iterator().next();

    pushNext(BASE_TIME + 3000);
    HeadLease pinned = registry.acquire();
    pushNext(BASE_TIME + 6000);

    assertEquals(2, constantBlockNumber(owner));
    long pinnedNumber;
    try (QueryViewScope ignored = QueryViewScope.bind(pinned)) {
      pinnedNumber = constantBlockNumber(owner);
    }
    assertTrue(pinned.seal());
    pinned.close();
    assertEquals(1, pinnedNumber);
  }

  private long constantBlockNumber(ByteString owner) throws Exception {
    Wallet wallet = context.getBean(Wallet.class);
    TriggerSmartContract trigger = TriggerSmartContract.newBuilder()
        .setOwnerAddress(owner)
        .setData(ByteString.copyFrom(ByteArray.fromHexString(BLOCK_NUMBER_INITCODE)))
        .build();
    TransactionCapsule trxCap =
        wallet.createTransactionCapsule(trigger, ContractType.TriggerSmartContract);
    TransactionExtention.Builder builder = TransactionExtention.newBuilder();
    wallet.triggerConstantContract(trigger, trxCap, builder, Return.newBuilder());
    return new BigInteger(1, builder.getConstantResult(0).toByteArray()).longValueExact();
  }

  private static long headNumberIn(HeadLease lease) {
    try (QueryViewScope ignored = QueryViewScope.bind(lease)) {
      return ChainBaseManager.getInstance().getHeadBlockNum();
    }
  }

  private MockHttpServletRequest accountRequest(ByteString address, boolean selector,
      Surface surface) {
    MockHttpServletRequest request = new MockHttpServletRequest(fullContext(surface));
    request.setMethod("POST");
    request.setContentType("application/json");
    request.setContent(("{\"address\":\"" + ByteArray.toHexString(address.toByteArray())
        + "\"}").getBytes(StandardCharsets.UTF_8));
    if (selector) {
      request.addHeader(CommittedHeadQuery.HTTP_SELECTOR, CommittedHeadQuery.COMMITTED_HEAD);
    }
    return request;
  }

  private static MockServletContext fullContext(Surface surface) {
    MockServletContext servletContext = new MockServletContext();
    servletContext.setAttribute(RateLimiterServlet.SURFACE_ATTRIBUTE, surface);
    return servletContext;
  }

  private BlockCapsule pushNext(long time) throws Exception {
    BlockCapsule block = createBlock(time, chain.getHeadBlockNum() + 1,
        chain.getHeadBlockId().getByteString());
    dbManager.pushBlock(block);
    return block;
  }

  private BlockCapsule createBlock(long time, long number, ByteString parent) {
    ByteString witness = dposSlot.getScheduledWitness(dposSlot.getSlot(time));
    BlockCapsule block = new BlockCapsule(number, Sha256Hash.wrap(parent), time, witness);
    block.generatedByMyself = true;
    block.setMerkleRoot();
    block.sign(ByteArray.fromHexString(witnessKeys.get(witness)));
    return block;
  }

  private BlockCapsule received(long time, BlockCapsule parent) {
    BlockCapsule block = createBlock(time, parent.getNum() + 1,
        parent.getBlockId().getByteString());
    block.generatedByMyself = false;
    return block;
  }

  private Map<ByteString, String> addWitnesses() {
    chain.getWitnessScheduleStore().saveActiveWitnesses(new ArrayList<>());
    Map<ByteString, String> keys = new HashMap<>();
    for (int i = 0; i < 3; i++) {
      ECKey key = new ECKey(Utils.getRandom());
      ByteString address = ByteString.copyFrom(key.getAddress());
      chain.getWitnessStore().put(address.toByteArray(), new WitnessCapsule(address));
      chain.getAccountStore().put(address.toByteArray(),
          new AccountCapsule(Account.newBuilder().setAddress(address).build()));
      chain.addWitness(address);
      keys.put(address, ByteArray.toHexString(key.getPrivKey().toByteArray()));
    }
    return keys;
  }
}
