package org.tron.core.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServlet;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.tron.common.BaseMethodTest;
import org.tron.common.crypto.ECKey;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.Sha256Hash;
import org.tron.common.utils.Utils;
import org.tron.consensus.dpos.DposSlot;
import org.tron.core.ChainBaseManager;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.AssetIssueCapsule;
import org.tron.core.capsule.BlockBalanceTraceCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.ContractCapsule;
import org.tron.core.capsule.DelegatedResourceCapsule;
import org.tron.core.capsule.ExchangeCapsule;
import org.tron.core.capsule.MarketAccountOrderCapsule;
import org.tron.core.capsule.MarketOrderCapsule;
import org.tron.core.capsule.MarketOrderIdListCapsule;
import org.tron.core.capsule.ProposalCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.capsule.TransactionInfoCapsule;
import org.tron.core.capsule.TransactionRetCapsule;
import org.tron.core.capsule.WitnessCapsule;
import org.tron.core.capsule.utils.MarketUtils;
import org.tron.core.config.args.Args;
import org.tron.core.consensus.ConsensusService;
import org.tron.core.db2.ISession;
import org.tron.core.services.headview.CommittedHeadQuery;
import org.tron.core.services.http.HttpApi.Surface;
import org.tron.core.services.http.HttpApiRegistry;
import org.tron.core.services.http.servlets.EstimateEnergyServlet;
import org.tron.core.services.http.servlets.GetAccountBalanceServlet;
import org.tron.core.services.http.servlets.GetAccountByIdServlet;
import org.tron.core.services.http.servlets.GetAccountNetServlet;
import org.tron.core.services.http.servlets.GetAccountResourceServlet;
import org.tron.core.services.http.servlets.GetAccountServlet;
import org.tron.core.services.http.servlets.GetAssetIssueByAccountServlet;
import org.tron.core.services.http.servlets.GetAssetIssueByIdServlet;
import org.tron.core.services.http.servlets.GetAssetIssueByNameServlet;
import org.tron.core.services.http.servlets.GetAssetIssueListByNameServlet;
import org.tron.core.services.http.servlets.GetAssetIssueListServlet;
import org.tron.core.services.http.servlets.GetAvailableUnfreezeCountServlet;
import org.tron.core.services.http.servlets.GetBandwidthPricesServlet;
import org.tron.core.services.http.servlets.GetBlockBalanceServlet;
import org.tron.core.services.http.servlets.GetBlockByIdServlet;
import org.tron.core.services.http.servlets.GetBlockByLatestNumServlet;
import org.tron.core.services.http.servlets.GetBlockByLimitNextServlet;
import org.tron.core.services.http.servlets.GetBlockByNumServlet;
import org.tron.core.services.http.servlets.GetBlockServlet;
import org.tron.core.services.http.servlets.GetBrokerageServlet;
import org.tron.core.services.http.servlets.GetBurnTrxServlet;
import org.tron.core.services.http.servlets.GetCanDelegatedMaxSizeServlet;
import org.tron.core.services.http.servlets.GetCanWithdrawUnfreezeAmountServlet;
import org.tron.core.services.http.servlets.GetChainParametersServlet;
import org.tron.core.services.http.servlets.GetContractInfoServlet;
import org.tron.core.services.http.servlets.GetContractServlet;
import org.tron.core.services.http.servlets.GetDelegatedResourceAccountIndexServlet;
import org.tron.core.services.http.servlets.GetDelegatedResourceAccountIndexV2Servlet;
import org.tron.core.services.http.servlets.GetDelegatedResourceServlet;
import org.tron.core.services.http.servlets.GetDelegatedResourceV2Servlet;
import org.tron.core.services.http.servlets.GetEnergyPricesServlet;
import org.tron.core.services.http.servlets.GetExchangeByIdServlet;
import org.tron.core.services.http.servlets.GetMarketOrderByAccountServlet;
import org.tron.core.services.http.servlets.GetMarketOrderByIdServlet;
import org.tron.core.services.http.servlets.GetMarketOrderListByPairServlet;
import org.tron.core.services.http.servlets.GetMarketPairListServlet;
import org.tron.core.services.http.servlets.GetMarketPriceByPairServlet;
import org.tron.core.services.http.servlets.GetMemoFeePricesServlet;
import org.tron.core.services.http.servlets.GetNextMaintenanceTimeServlet;
import org.tron.core.services.http.servlets.GetNowBlockServlet;
import org.tron.core.services.http.servlets.GetPaginatedAssetIssueListServlet;
import org.tron.core.services.http.servlets.GetPaginatedExchangeListServlet;
import org.tron.core.services.http.servlets.GetPaginatedNowWitnessListServlet;
import org.tron.core.services.http.servlets.GetPaginatedProposalListServlet;
import org.tron.core.services.http.servlets.GetProposalByIdServlet;
import org.tron.core.services.http.servlets.GetRewardServlet;
import org.tron.core.services.http.servlets.GetTransactionApprovedListServlet;
import org.tron.core.services.http.servlets.GetTransactionByIdServlet;
import org.tron.core.services.http.servlets.GetTransactionCountByBlockNumServlet;
import org.tron.core.services.http.servlets.GetTransactionInfoByBlockNumServlet;
import org.tron.core.services.http.servlets.GetTransactionInfoByIdServlet;
import org.tron.core.services.http.servlets.GetTransactionReceiptByIdServlet;
import org.tron.core.services.http.servlets.GetTransactionSignWeightServlet;
import org.tron.core.services.http.servlets.IsShieldedTRC20ContractNoteSpentServlet;
import org.tron.core.services.http.servlets.ListExchangesServlet;
import org.tron.core.services.http.servlets.ListProposalsServlet;
import org.tron.core.services.http.servlets.ListWitnessesServlet;
import org.tron.core.services.http.servlets.RateLimiterServlet;
import org.tron.core.services.http.servlets.ScanShieldedTRC20NotesByIvkServlet;
import org.tron.core.services.http.servlets.ScanShieldedTRC20NotesByOvkServlet;
import org.tron.core.services.http.servlets.TriggerConstantContractServlet;
import org.tron.core.services.http.servlets.Util;
import org.tron.core.store.DynamicPropertiesStore;
import org.tron.protos.Protocol.Account;
import org.tron.protos.Protocol.Account.FreezeV2;
import org.tron.protos.Protocol.Account.UnFreezeV2;
import org.tron.protos.Protocol.Key;
import org.tron.protos.Protocol.Permission;
import org.tron.protos.Protocol.Permission.PermissionType;
import org.tron.protos.Protocol.Proposal.State;
import org.tron.protos.Protocol.ResourceReceipt;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;
import org.tron.protos.Protocol.TransactionInfo;
import org.tron.protos.contract.AssetIssueContractOuterClass.AssetIssueContract;
import org.tron.protos.contract.BalanceContract.TransactionBalanceTrace;
import org.tron.protos.contract.BalanceContract.TransferContract;
import org.tron.protos.contract.Common.ResourceCode;
import org.tron.protos.contract.MarketContract.MarketSellAssetContract;
import org.tron.protos.contract.SmartContractOuterClass.SmartContract;

public class CommittedHeadPilotCoverageTest extends BaseMethodTest {

  private static final long BASE_TIME = 1533529947000L;
  private static final AtomicInteger PORT = new AtomicInteger(0);
  private static final String BLOCK_NUMBER_INITCODE = "4360005260206000f3";
  private static final String CALLER_BALANCE_INITCODE = "333160005260206000f3";
  private static final String TOKEN_ID = "1000001";
  private static final byte[] TRX = "_".getBytes(StandardCharsets.UTF_8);
  private static final byte[] TOKEN_NAME = "CommittedHead".getBytes(StandardCharsets.UTF_8);
  private static final byte[] ACCOUNT_ID = "committed-head".getBytes(StandardCharsets.UTF_8);
  private static final byte[] TX_ID = hash("committed-head-tx");
  private static final byte[] ORDER_ID = hash("committed-head-order");
  private static final byte[] CONTRACT = ByteArray.fromHexString(
      "41000000000000000000000000000000000000c0de");
  private static final String KEY_HEX = ByteArray.toHexString(hash("committed-head-key"));
  private static final Pattern CONSTANT_RESULT = Pattern.compile(
      "\"(constant_result|energy_required)\":[^,}]*");

  private ChainBaseManager chain;
  private DposSlot dposSlot;
  private Map<ByteString, String> witnessKeys;
  private ByteString owner;
  private ByteString receiver;
  private ByteString third;
  private BlockCapsule block1;
  private BlockCapsule head;
  private BlockCapsule next;
  private TransactionCapsule nextTx;

  @Override
  protected void beforeContext() {
    Args.getInstance().setCommittedHeadQueryEnable(true);
    Args.getInstance().setFullNodeHttpEnable(true);
    Args.getInstance().setHistoryBalanceLookup(true);
    Args.getInstance().setSupportConstant(true);
    Args.getInstance().setEstimateEnergy(true);
    Args.getInstance().setAllowShieldedTransactionApi(true);
    Args.getInstance().setConstantCallTimeoutMs(10_000);
  }

  @Override
  protected void afterInit() {
    Args.getInstance().setNodeListenPort(13500 + PORT.incrementAndGet());
    dposSlot = context.getBean(DposSlot.class);
    context.getBean(ConsensusService.class).start();
    chain = dbManager.getChainBaseManager();
  }

  @Test
  public void everyHttpPilotServesTheCommittedView() throws Exception {
    witnessKeys = addWitnesses();
    List<ByteString> witnesses = new ArrayList<>(witnessKeys.keySet());
    owner = witnesses.get(0);
    receiver = witnesses.get(1);
    third = witnesses.get(2);
    seed();
    dbManager.markCommittedHeadReady();
    block1 = pushNext(BASE_TIME + 3000);
    head = pushNext(BASE_TIME + 6000);
    nextTx = new TransactionCapsule(TransferContract.newBuilder().setOwnerAddress(receiver)
        .setToAddress(third).setAmount(2).build(), ContractType.TransferContract);
    next = createBlock(BASE_TIME + 9000, head.getNum() + 1, head.getBlockId().getByteString(),
        nextTx);
    String headNumber = String.valueOf(head.getNum());

    List<Call> calls = calls();
    assertEquals(HttpApiRegistry.committedHeadServlets(),
        calls.stream().map(call -> call.servlet).collect(Collectors.toSet()));
    List<String> before = new ArrayList<>();
    for (Call call : calls) {
      String plain = call.body(call.run(false));
      MockHttpServletResponse committed = call.run(true);
      assertEquals(call.name, 200, committed.getStatus());
      assertEquals(call.name, headNumber,
          committed.getHeader(CommittedHeadQuery.HTTP_BLOCK_NUMBER));
      assertEquals(call.name, plain, call.body(committed));
      before.add(plain);
    }

    mutateWithoutPublishing();

    for (int i = 0; i < calls.size(); i++) {
      Call call = calls.get(i);
      String plain = call.body(call.run(false));
      MockHttpServletResponse committed = call.run(true);
      assertEquals(call.name, headNumber,
          committed.getHeader(CommittedHeadQuery.HTTP_BLOCK_NUMBER));
      assertEquals(call.name, before.get(i), call.body(committed));
      if (call.changes) {
        assertNotEquals(call.name, before.get(i), plain);
      }
    }
  }

  private List<Call> calls() {
    String ownerHex = hex(owner);
    String block1Hex = ByteArray.toHexString(block1.getBlockId().getBytes());
    String pair = "sell_token_id=" + ByteArray.toHexString(TRX)
        + "&buy_token_id=" + ByteArray.toHexString(TOKEN_ID.getBytes(StandardCharsets.UTF_8));
    String page = "offset=0&limit=10";
    String constantCall = constantCall(BLOCK_NUMBER_INITCODE);
    String transfer = signedTransfer();
    return Arrays.asList(
        get(GetAccountServlet.class, "address=" + ownerHex, true),
        get(GetAccountResourceServlet.class, "address=" + ownerHex, true),
        get(GetAccountByIdServlet.class, "account_id=" + ByteArray.toHexString(ACCOUNT_ID), true),
        get(GetAccountNetServlet.class, "address=" + ownerHex, true),
        get(GetAvailableUnfreezeCountServlet.class, "owner_address=" + ownerHex, true),
        get(GetCanDelegatedMaxSizeServlet.class, "type=1&owner_address=" + ownerHex, true),
        get(GetCanWithdrawUnfreezeAmountServlet.class,
            "timestamp=" + (BASE_TIME + 10_000_000) + "&owner_address=" + ownerHex, true),
        get(GetDelegatedResourceServlet.class,
            "fromAddress=" + ownerHex + "&toAddress=" + hex(receiver), true),
        get(GetDelegatedResourceV2Servlet.class,
            "fromAddress=" + ownerHex + "&toAddress=" + hex(receiver), true),
        get(GetDelegatedResourceAccountIndexServlet.class, "value=" + ownerHex, true),
        get(GetDelegatedResourceAccountIndexV2Servlet.class, "value=" + ownerHex, true),
        post(GetTransactionApprovedListServlet.class, transfer, true),
        post(GetTransactionSignWeightServlet.class, transfer, true),
        get(GetChainParametersServlet.class, "", true),
        get(GetNextMaintenanceTimeServlet.class, "", true),
        get(GetBandwidthPricesServlet.class, "", true),
        get(GetEnergyPricesServlet.class, "", true),
        get(GetMemoFeePricesServlet.class, "", true),
        get(GetBurnTrxServlet.class, "", true),
        get(GetAssetIssueByAccountServlet.class, "address=" + ownerHex, true),
        get(GetAssetIssueByIdServlet.class, "value=" + TOKEN_ID, true),
        get(GetAssetIssueByNameServlet.class, "value=" + ByteArray.toHexString(TOKEN_NAME), true),
        get(GetAssetIssueListByNameServlet.class,
            "value=" + ByteArray.toHexString(TOKEN_NAME), true),
        get(GetPaginatedAssetIssueListServlet.class, page, true),
        get(GetExchangeByIdServlet.class, "id=1", true),
        get(ListExchangesServlet.class, "", true),
        get(GetPaginatedExchangeListServlet.class, page, true),
        get(GetProposalByIdServlet.class, "id=1", true),
        get(ListProposalsServlet.class, "", true),
        get(GetPaginatedProposalListServlet.class, page, true),
        get(GetMarketOrderByIdServlet.class, "value=" + ByteArray.toHexString(ORDER_ID), true),
        get(GetMarketOrderByAccountServlet.class, "value=" + ownerHex, true),
        get(GetMarketOrderListByPairServlet.class, pair, true),
        get(GetMarketPriceByPairServlet.class, pair, true),
        get(GetMarketPairListServlet.class, "", true),
        get(ListWitnessesServlet.class, "", true),
        get(GetPaginatedNowWitnessListServlet.class, page, true),
        get(GetContractServlet.class, "value=" + ByteArray.toHexString(CONTRACT), true),
        get(GetContractInfoServlet.class, "value=" + ByteArray.toHexString(CONTRACT), true),
        get(GetTransactionInfoByIdServlet.class, "value=" + ByteArray.toHexString(TX_ID), true),
        get(GetTransactionReceiptByIdServlet.class,
            "value=" + ByteArray.toHexString(TX_ID), true),
        get(GetNowBlockServlet.class, "", true),
        get(GetBlockByLimitNextServlet.class, "startNum=0&endNum=5", true),
        get(GetBlockByIdServlet.class, "value=" + block1Hex, false),
        get(GetBlockByNumServlet.class, "num=1", false),
        get(GetBlockByNumServlet.class, "num=" + next.getNum(), true),
        get(GetBlockServlet.class, "id_or_num=" + next.getNum() + "&detail=true", true),
        get(GetTransactionCountByBlockNumServlet.class, "num=" + next.getNum(), true),
        get(GetTransactionInfoByBlockNumServlet.class, "num=" + next.getNum(), true),
        get(GetTransactionByIdServlet.class,
            "value=" + ByteArray.toHexString(nextTx.getTransactionId().getBytes()), true),
        get(GetBrokerageServlet.class, "address=" + ownerHex, true),
        get(GetRewardServlet.class, "address=" + ownerHex, true),
        get(GetBlockByLatestNumServlet.class, "num=3", true),
        get(GetAssetIssueListServlet.class, "", true),
        get(GetBlockByIdServlet.class,
            "value=" + ByteArray.toHexString(next.getBlockId().getBytes()), true),
        post(GetBlockBalanceServlet.class,
            "{\"hash\":\"" + block1Hex + "\",\"number\":1}", true),
        post(GetAccountBalanceServlet.class, "{\"account_identifier\":{\"address\":\"" + ownerHex
            + "\"},\"block_identifier\":{\"hash\":\"" + block1Hex + "\",\"number\":1}}", true),
        post(TriggerConstantContractServlet.class, constantCall, true)
            .comparing(CommittedHeadPilotCoverageTest::vmResult),
        post(TriggerConstantContractServlet.class, constantCall(CALLER_BALANCE_INITCODE), true)
            .comparing(CommittedHeadPilotCoverageTest::vmResult),
        post(EstimateEnergyServlet.class, constantCall, false)
            .comparing(CommittedHeadPilotCoverageTest::vmResult),
        post(IsShieldedTRC20ContractNoteSpentServlet.class, "{\"ak\":\"" + KEY_HEX
            + "\",\"nk\":\"" + KEY_HEX + "\",\"position\":1,\"shielded_TRC20_contract_address\":\""
            + ByteArray.toHexString(CONTRACT) + "\"}", false),
        get(ScanShieldedTRC20NotesByIvkServlet.class, "start_block_index=0&end_block_index=2&ivk="
            + KEY_HEX + "&ak=" + KEY_HEX + "&nk=" + KEY_HEX
            + "&shielded_TRC20_contract_address=" + ByteArray.toHexString(CONTRACT), false),
        get(ScanShieldedTRC20NotesByOvkServlet.class, "start_block_index=0&end_block_index=2&ovk="
            + KEY_HEX + "&shielded_TRC20_contract_address=" + ByteArray.toHexString(CONTRACT),
            false));
  }

  private void seed() {
    DynamicPropertiesStore properties = chain.getDynamicPropertiesStore();
    Account account = chain.getAccountStore().get(owner.toByteArray()).getInstance().toBuilder()
        .setBalance(1_000_000_000L)
        .setAccountId(ByteString.copyFrom(ACCOUNT_ID))
        .addFrozenV2(FreezeV2.newBuilder().setType(ResourceCode.ENERGY).setAmount(5_000_000L))
        .addUnfrozenV2(UnFreezeV2.newBuilder().setType(ResourceCode.BANDWIDTH)
            .setUnfreezeAmount(1_000_000L).setUnfreezeExpireTime(BASE_TIME))
        .build();
    AccountCapsule ownerAccount = new AccountCapsule(account);
    chain.getAccountStore().put(owner.toByteArray(), ownerAccount);
    chain.getAccountIdIndexStore().put(ownerAccount);

    AssetIssueCapsule asset = new AssetIssueCapsule(AssetIssueContract.newBuilder()
        .setId(TOKEN_ID).setName(ByteString.copyFrom(TOKEN_NAME)).setOwnerAddress(owner)
        .setTotalSupply(1_000_000L).setTrxNum(1).setNum(1).setPrecision(0)
        .setDescription(ByteString.copyFromUtf8("before")).build());
    putAsset(asset);

    ExchangeCapsule exchange = new ExchangeCapsule(owner, 1, BASE_TIME, TRX,
        TOKEN_ID.getBytes(StandardCharsets.UTF_8));
    exchange.setBalance(100, 100);
    putExchange(exchange);
    properties.saveLatestExchangeNum(1);

    ProposalCapsule proposal = new ProposalCapsule(owner, 1);
    proposal.setExpirationTime(BASE_TIME + 1_000_000);
    proposal.setState(State.PENDING);
    chain.getProposalStore().put(proposal.createDbKey(), proposal);
    properties.saveLatestProposalNum(1);

    putDelegation(owner, receiver, 1_000_000L);
    chain.getDelegatedResourceAccountIndexStore()
        .delegate(owner.toByteArray(), receiver.toByteArray(), BASE_TIME);
    chain.getDelegatedResourceAccountIndexStore()
        .delegateV2(owner.toByteArray(), receiver.toByteArray(), BASE_TIME);

    MarketOrderCapsule order = new MarketOrderCapsule(ORDER_ID, MarketSellAssetContract
        .newBuilder().setOwnerAddress(owner).setSellTokenId(ByteString.copyFrom(TRX))
        .setSellTokenQuantity(100).setBuyTokenId(ByteString.copyFromUtf8(TOKEN_ID))
        .setBuyTokenQuantity(200).build());
    chain.getMarketOrderStore().put(ORDER_ID, order);
    MarketAccountOrderCapsule accountOrders = new MarketAccountOrderCapsule(owner);
    accountOrders.addOrders(ByteString.copyFrom(ORDER_ID));
    chain.getMarketAccountStore().put(owner.toByteArray(), accountOrders);
    addPrice(100, 200);

    properties.saveChangeDelegation(1);
    properties.saveMemoFeeHistory("0:1000000");
    properties.saveEnergyPriceHistory("0:420");
    properties.saveBandwidthPriceHistory("0:1000");

    chain.getAccountStore().put(CONTRACT, new AccountCapsule(Account.newBuilder()
        .setAddress(ByteString.copyFrom(CONTRACT)).build()));
    chain.getContractStore().put(CONTRACT, new ContractCapsule(SmartContract.newBuilder()
        .setContractAddress(ByteString.copyFrom(CONTRACT)).setOriginAddress(owner)
        .setName("before").build()));

    TransactionCapsule recorded = new TransactionCapsule(TransferContract.newBuilder()
        .setOwnerAddress(owner).setToAddress(receiver).setAmount(1).build(),
        ContractType.TransferContract);
    recorded.setBlockNum(1);
    chain.getTransactionStore().put(TX_ID, recorded);
    putTransactionInfo(1);
    chain.getAccountTraceStore().recordBalanceWithBlock(owner.toByteArray(), 1, 5);
  }

  private void mutateWithoutPublishing() throws Exception {
    DynamicPropertiesStore properties = chain.getDynamicPropertiesStore();
    try (ISession session = dbManager.getRevokingStore().buildSession()) {
      Account account = chain.getAccountStore().get(owner.toByteArray()).getInstance()
          .toBuilder()
          .setBalance(2_000_000_000L)
          .setAllowance(123)
          .setFreeNetUsage(500)
          .setLatestConsumeFreeTime(chain.getHeadSlot())
          .setFrozenV2(0, FreezeV2.newBuilder().setType(ResourceCode.ENERGY)
              .setAmount(7_000_000L))
          .addUnfrozenV2(UnFreezeV2.newBuilder().setType(ResourceCode.ENERGY)
              .setUnfreezeAmount(2_000_000L).setUnfreezeExpireTime(BASE_TIME))
          .addUnfrozenV2(UnFreezeV2.newBuilder().setType(ResourceCode.ENERGY)
              .setUnfreezeAmount(3_000_000L).setUnfreezeExpireTime(BASE_TIME + 100_000_000L))
          .setOwnerPermission(Permission.newBuilder().setType(PermissionType.Owner)
              .setPermissionName("owner").setThreshold(1)
              .addKeys(Key.newBuilder().setAddress(receiver).setWeight(1)))
          .build();
      chain.getAccountStore().put(owner.toByteArray(), new AccountCapsule(account));

      AssetIssueCapsule asset = chain.getAssetIssueV2Store()
          .get(TOKEN_ID.getBytes(StandardCharsets.UTF_8));
      asset.setDescription(ByteString.copyFromUtf8("after"));
      putAsset(asset);

      ExchangeCapsule exchange = chain.getExchangeV2Store().get(ExchangeCapsule.calculateDbKey(1));
      exchange.setBalance(150, 70);
      putExchange(exchange);

      ProposalCapsule proposal = chain.getProposalStore().get(ProposalCapsule.calculateDbKey(1));
      proposal.setState(State.APPROVED);
      chain.getProposalStore().put(proposal.createDbKey(), proposal);

      putDelegation(owner, receiver, 3_000_000L);
      chain.getDelegatedResourceAccountIndexStore()
          .delegate(owner.toByteArray(), third.toByteArray(), BASE_TIME + 1);
      chain.getDelegatedResourceAccountIndexStore()
          .delegateV2(owner.toByteArray(), third.toByteArray(), BASE_TIME + 1);

      MarketOrderCapsule order = chain.getMarketOrderStore().get(ORDER_ID);
      order.setSellTokenQuantityRemain(40);
      chain.getMarketOrderStore().put(ORDER_ID, order);
      addPrice(100, 150);
      chain.getMarketPairToPriceStore().setPriceNum(TRX, "1000002".getBytes(StandardCharsets.UTF_8),
          1);

      properties.saveMemoFeeHistory("0:1000000,1:2000000");
      properties.saveEnergyPriceHistory("0:420,1:210");
      properties.saveBandwidthPriceHistory("0:1000,1:500");
      properties.burnTrx(1);
      properties.saveNextMaintenanceTime(properties.getNextMaintenanceTime() + 1000);
      properties.saveEnergyFee(properties.getEnergyFee() + 1);

      WitnessCapsule witness = chain.getWitnessStore().get(owner.toByteArray());
      witness.setVoteCount(witness.getVoteCount() + 1_000);
      chain.getWitnessStore().put(owner.toByteArray(), witness);

      chain.getContractStore().put(CONTRACT, new ContractCapsule(SmartContract.newBuilder()
          .setContractAddress(ByteString.copyFrom(CONTRACT)).setOriginAddress(owner)
          .setName("after").build()));

      putTransactionInfo(2);
      chain.getAccountTraceStore().recordBalanceWithBlock(owner.toByteArray(), 1, 7);
      BlockBalanceTraceCapsule trace =
          chain.getBalanceTraceStore().getBlockBalanceTrace(block1.getBlockId());
      trace.addTransactionBalanceTrace(TransactionBalanceTrace.newBuilder()
          .setTransactionIdentifier(ByteString.copyFrom(TX_ID)).build());
      chain.getBalanceTraceStore().put(ByteArray.fromLong(1), trace);

      chain.getBlockStore().put(next.getBlockId().getBytes(), next);
      chain.getBlockIndexStore().put(next.getBlockId());
      byte[] nextTxId = nextTx.getTransactionId().getBytes();
      nextTx.setBlockNum(next.getNum());
      chain.getTransactionStore().put(nextTxId, nextTx);
      chain.getTransactionHistoryStore().put(nextTxId, new TransactionInfoCapsule(
          TransactionInfo.newBuilder().setId(ByteString.copyFrom(nextTxId)).setFee(3)
              .setBlockNumber(next.getNum()).build()));
      properties.saveLatestBlockHeaderNumber(next.getNum());
      chain.getDelegationStore().setBrokerage(properties.getCurrentCycleNumber(),
          owner.toByteArray(), 50);
      session.commit();
    }
  }

  private void putTransactionInfo(long fee) {
    TransactionRetCapsule ret = new TransactionRetCapsule();
    ret.addTransactionInfo(TransactionInfo.newBuilder().setId(ByteString.copyFrom(TX_ID))
        .setFee(fee).setReceipt(ResourceReceipt.newBuilder().setEnergyFee(fee))
        .setBlockNumber(1).build());
    chain.getTransactionRetStore().put(ByteArray.fromLong(1), ret);
  }

  private String constantCall(String initcode) {
    return "{\"owner_address\":\"" + hex(owner) + "\",\"data\":\"" + initcode + "\"}";
  }

  private void putAsset(AssetIssueCapsule asset) {
    chain.getAssetIssueV2Store().put(asset.createDbV2Key(), asset);
    chain.getAssetIssueStore().put(asset.createDbKey(), asset);
  }

  private void putExchange(ExchangeCapsule exchange) {
    chain.getExchangeV2Store().put(exchange.createDbKey(), exchange);
    chain.getExchangeStore().put(exchange.createDbKey(), exchange);
  }

  private void putDelegation(ByteString from, ByteString to, long energy) {
    DelegatedResourceCapsule v1 = new DelegatedResourceCapsule(from, to);
    v1.setFrozenBalanceForEnergy(energy, BASE_TIME + 1_000_000);
    chain.getDelegatedResourceStore().put(v1.createDbKey(), v1);
    DelegatedResourceCapsule v2 = new DelegatedResourceCapsule(from, to);
    v2.setFrozenBalanceForEnergy(energy, 0);
    chain.getDelegatedResourceStore().put(DelegatedResourceCapsule.createDbKeyV2(
        from.toByteArray(), to.toByteArray(), false), v2);
  }

  private void addPrice(long sellQuantity, long buyQuantity) {
    byte[] buyToken = TOKEN_ID.getBytes(StandardCharsets.UTF_8);
    chain.getMarketPairToPriceStore().addNewPriceKey(TRX, buyToken,
        chain.getMarketPairPriceToOrderStore());
    byte[] priceKey = MarketUtils.createPairPriceKey(TRX, buyToken, sellQuantity, buyQuantity);
    chain.getMarketPairPriceToOrderStore().put(priceKey,
        new MarketOrderIdListCapsule(ORDER_ID, ORDER_ID));
  }

  private String signedTransfer() {
    TransactionCapsule transfer = new TransactionCapsule(TransferContract.newBuilder()
        .setOwnerAddress(owner).setToAddress(receiver).setAmount(1).build(),
        ContractType.TransferContract);
    transfer.sign(ByteArray.fromHexString(witnessKeys.get(owner)));
    return Util.printCreateTransaction(transfer.getInstance(), false);
  }

  private Call get(Class<? extends HttpServlet> servlet, String query, boolean changes) {
    return new Call(servlet, "GET", query, null, changes);
  }

  private Call post(Class<? extends HttpServlet> servlet, String body, boolean changes) {
    return new Call(servlet, "POST", "", body, changes);
  }

  private final class Call {

    private final String name;
    private final Class<? extends HttpServlet> servlet;
    private final String method;
    private final String query;
    private final String body;
    private final boolean changes;
    private UnaryOperator<String> comparable = UnaryOperator.identity();

    private Call(Class<? extends HttpServlet> servlet, String method, String query, String body,
        boolean changes) {
      this.name = servlet.getSimpleName();
      this.servlet = servlet;
      this.method = method;
      this.query = query;
      this.body = body;
      this.changes = changes;
    }

    private Call comparing(UnaryOperator<String> extractor) {
      comparable = extractor;
      return this;
    }

    private String body(MockHttpServletResponse response) throws Exception {
      return comparable.apply(response.getContentAsString());
    }

    private MockHttpServletResponse run(boolean selector) throws Exception {
      MockServletContext servletContext = new MockServletContext();
      servletContext.setAttribute(RateLimiterServlet.SURFACE_ATTRIBUTE, Surface.FULL);
      MockHttpServletRequest request = new MockHttpServletRequest(servletContext);
      request.setMethod(method);
      for (String pair : query.split("&")) {
        if (!pair.isEmpty()) {
          int split = pair.indexOf('=');
          request.addParameter(pair.substring(0, split), pair.substring(split + 1));
        }
      }
      if (body != null) {
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
      }
      if (selector) {
        request.addHeader(CommittedHeadQuery.HTTP_SELECTOR, CommittedHeadQuery.COMMITTED_HEAD);
      }
      MockHttpServletResponse response = new MockHttpServletResponse();
      context.getBean(servlet).service(request, response);
      return response;
    }
  }

  private BlockCapsule pushNext(long time) throws Exception {
    BlockCapsule block = createBlock(time, chain.getHeadBlockNum() + 1,
        chain.getHeadBlockId().getByteString());
    dbManager.pushBlock(block);
    return block;
  }

  private BlockCapsule createBlock(long time, long number, ByteString parent,
      TransactionCapsule... transactions) {
    ByteString witness = dposSlot.getScheduledWitness(dposSlot.getSlot(time));
    BlockCapsule block = new BlockCapsule(number, Sha256Hash.wrap(parent), time, witness);
    for (TransactionCapsule transaction : transactions) {
      block.addTransaction(transaction);
    }
    block.generatedByMyself = true;
    block.setMerkleRoot();
    block.sign(ByteArray.fromHexString(witnessKeys.get(witness)));
    return block;
  }

  private Map<ByteString, String> addWitnesses() {
    chain.getWitnessScheduleStore().saveActiveWitnesses(new ArrayList<>());
    Map<ByteString, String> keys = new LinkedHashMap<>();
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

  private static String vmResult(String body) {
    Matcher matcher = CONSTANT_RESULT.matcher(body);
    return matcher.find() ? matcher.group() : body;
  }

  private static String hex(ByteString address) {
    return ByteArray.toHexString(address.toByteArray());
  }

  private static byte[] hash(String value) {
    return Sha256Hash.of(value.getBytes(StandardCharsets.UTF_8)).getBytes();
  }
}
