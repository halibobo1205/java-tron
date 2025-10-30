package org.tron.core.services;

import com.google.common.base.Preconditions;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.ProtocolStringList;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.api.DatabaseGrpc.DatabaseImplBase;
import org.tron.api.GrpcAPI;
import org.tron.api.GrpcAPI.AccountNetMessage;
import org.tron.api.GrpcAPI.AccountResourceMessage;
import org.tron.api.GrpcAPI.AssetIssueList;
import org.tron.api.GrpcAPI.BlockExtention;
import org.tron.api.GrpcAPI.BlockLimit;
import org.tron.api.GrpcAPI.BlockList;
import org.tron.api.GrpcAPI.BlockListExtention;
import org.tron.api.GrpcAPI.BlockReference;
import org.tron.api.GrpcAPI.BytesMessage;
import org.tron.api.GrpcAPI.CanWithdrawUnfreezeAmountRequestMessage;
import org.tron.api.GrpcAPI.DecryptNotes;
import org.tron.api.GrpcAPI.DecryptNotesMarked;
import org.tron.api.GrpcAPI.DecryptNotesTRC20;
import org.tron.api.GrpcAPI.DelegatedResourceList;
import org.tron.api.GrpcAPI.DelegatedResourceMessage;
import org.tron.api.GrpcAPI.DiversifierMessage;
import org.tron.api.GrpcAPI.EmptyMessage;
import org.tron.api.GrpcAPI.EstimateEnergyMessage;
import org.tron.api.GrpcAPI.ExchangeList;
import org.tron.api.GrpcAPI.ExpandedSpendingKeyMessage;
import org.tron.api.GrpcAPI.IncomingViewingKeyDiversifierMessage;
import org.tron.api.GrpcAPI.IncomingViewingKeyMessage;
import org.tron.api.GrpcAPI.IvkDecryptTRC20Parameters;
import org.tron.api.GrpcAPI.NfTRC20Parameters;
import org.tron.api.GrpcAPI.NodeList;
import org.tron.api.GrpcAPI.NoteParameters;
import org.tron.api.GrpcAPI.NumberMessage;
import org.tron.api.GrpcAPI.OvkDecryptTRC20Parameters;
import org.tron.api.GrpcAPI.PaginatedMessage;
import org.tron.api.GrpcAPI.PaymentAddressMessage;
import org.tron.api.GrpcAPI.PricesResponseMessage;
import org.tron.api.GrpcAPI.PrivateParameters;
import org.tron.api.GrpcAPI.PrivateParametersWithoutAsk;
import org.tron.api.GrpcAPI.PrivateShieldedTRC20Parameters;
import org.tron.api.GrpcAPI.PrivateShieldedTRC20ParametersWithoutAsk;
import org.tron.api.GrpcAPI.ProposalList;
import org.tron.api.GrpcAPI.Return;
import org.tron.api.GrpcAPI.Return.response_code;
import org.tron.api.GrpcAPI.ShieldedAddressInfo;
import org.tron.api.GrpcAPI.ShieldedTRC20Parameters;
import org.tron.api.GrpcAPI.ShieldedTRC20TriggerContractParameters;
import org.tron.api.GrpcAPI.SpendAuthSigParameters;
import org.tron.api.GrpcAPI.SpendResult;
import org.tron.api.GrpcAPI.TransactionApprovedList;
import org.tron.api.GrpcAPI.TransactionExtention;
import org.tron.api.GrpcAPI.TransactionIdList;
import org.tron.api.GrpcAPI.TransactionInfoList;
import org.tron.api.GrpcAPI.TransactionList;
import org.tron.api.GrpcAPI.TransactionListExtention;
import org.tron.api.GrpcAPI.TransactionSignWeight;
import org.tron.api.GrpcAPI.ViewingKeyMessage;
import org.tron.api.GrpcAPI.WitnessList;
import org.tron.api.MonitorGrpc;
import org.tron.api.WalletExtensionGrpc;
import org.tron.api.WalletGrpc.WalletImplBase;
import org.tron.api.WalletSolidityGrpc.WalletSolidityImplBase;
import org.tron.common.application.RpcService;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.Sha256Hash;
import org.tron.common.utils.StringUtil;
import org.tron.core.ChainBaseManager;
import org.tron.core.Wallet;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.capsule.WitnessCapsule;
import org.tron.core.config.args.Args;
import org.tron.core.db.Manager;
import org.tron.core.exception.BadItemException;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ContractValidateException;
import org.tron.core.exception.ItemNotFoundException;
import org.tron.core.exception.MaintenanceUnavailableException;
import org.tron.core.exception.NonUniqueObjectException;
import org.tron.core.exception.StoreException;
import org.tron.core.exception.VMIllegalException;
import org.tron.core.exception.ZksnarkException;
import org.tron.core.metrics.MetricsApiService;
import org.tron.core.utils.TransactionUtil;
import org.tron.core.zen.address.DiversifierT;
import org.tron.core.zen.address.IncomingViewingKey;
import org.tron.protos.Protocol;
import org.tron.protos.Protocol.Account;
import org.tron.protos.Protocol.Block;
import org.tron.protos.Protocol.DynamicProperties;
import org.tron.protos.Protocol.Exchange;
import org.tron.protos.Protocol.MarketOrder;
import org.tron.protos.Protocol.MarketOrderList;
import org.tron.protos.Protocol.MarketOrderPair;
import org.tron.protos.Protocol.MarketOrderPairList;
import org.tron.protos.Protocol.MarketPriceList;
import org.tron.protos.Protocol.NodeInfo;
import org.tron.protos.Protocol.Proposal;
import org.tron.protos.Protocol.Transaction;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;
import org.tron.protos.Protocol.TransactionInfo;
import org.tron.protos.contract.AccountContract.AccountCreateContract;
import org.tron.protos.contract.AccountContract.AccountPermissionUpdateContract;
import org.tron.protos.contract.AccountContract.AccountUpdateContract;
import org.tron.protos.contract.AccountContract.SetAccountIdContract;
import org.tron.protos.contract.AssetIssueContractOuterClass.AssetIssueContract;
import org.tron.protos.contract.AssetIssueContractOuterClass.ParticipateAssetIssueContract;
import org.tron.protos.contract.AssetIssueContractOuterClass.TransferAssetContract;
import org.tron.protos.contract.AssetIssueContractOuterClass.UnfreezeAssetContract;
import org.tron.protos.contract.AssetIssueContractOuterClass.UpdateAssetContract;
import org.tron.protos.contract.BalanceContract;
import org.tron.protos.contract.BalanceContract.AccountBalanceRequest;
import org.tron.protos.contract.BalanceContract.AccountBalanceResponse;
import org.tron.protos.contract.BalanceContract.BlockBalanceTrace;
import org.tron.protos.contract.BalanceContract.CancelAllUnfreezeV2Contract;
import org.tron.protos.contract.BalanceContract.DelegateResourceContract;
import org.tron.protos.contract.BalanceContract.FreezeBalanceContract;
import org.tron.protos.contract.BalanceContract.TransferContract;
import org.tron.protos.contract.BalanceContract.UnDelegateResourceContract;
import org.tron.protos.contract.BalanceContract.UnfreezeBalanceContract;
import org.tron.protos.contract.BalanceContract.WithdrawBalanceContract;
import org.tron.protos.contract.BalanceContract.WithdrawExpireUnfreezeContract;
import org.tron.protos.contract.ExchangeContract.ExchangeCreateContract;
import org.tron.protos.contract.ExchangeContract.ExchangeInjectContract;
import org.tron.protos.contract.ExchangeContract.ExchangeTransactionContract;
import org.tron.protos.contract.ExchangeContract.ExchangeWithdrawContract;
import org.tron.protos.contract.MarketContract.MarketCancelOrderContract;
import org.tron.protos.contract.MarketContract.MarketSellAssetContract;
import org.tron.protos.contract.ProposalContract.ProposalApproveContract;
import org.tron.protos.contract.ProposalContract.ProposalCreateContract;
import org.tron.protos.contract.ProposalContract.ProposalDeleteContract;
import org.tron.protos.contract.ShieldContract.IncrementalMerkleVoucherInfo;
import org.tron.protos.contract.ShieldContract.OutputPointInfo;
import org.tron.protos.contract.SmartContractOuterClass.ClearABIContract;
import org.tron.protos.contract.SmartContractOuterClass.CreateSmartContract;
import org.tron.protos.contract.SmartContractOuterClass.SmartContract;
import org.tron.protos.contract.SmartContractOuterClass.SmartContractDataWrapper;
import org.tron.protos.contract.SmartContractOuterClass.TriggerSmartContract;
import org.tron.protos.contract.SmartContractOuterClass.UpdateEnergyLimitContract;
import org.tron.protos.contract.SmartContractOuterClass.UpdateSettingContract;
import org.tron.protos.contract.StorageContract.UpdateBrokerageContract;
import org.tron.protos.contract.WitnessContract.VoteWitnessContract;
import org.tron.protos.contract.WitnessContract.WitnessCreateContract;
import org.tron.protos.contract.WitnessContract.WitnessUpdateContract;

@Component
@Slf4j(topic = "grpcServer")
public class RpcApiService extends RpcService {

  public static final String CONTRACT_VALIDATE_EXCEPTION = "ContractValidateException: {}";
  private static final String EXCEPTION_CAUGHT = "exception caught";
  private static final String UNKNOWN_EXCEPTION_CAUGHT = "unknown exception caught: ";
  private static final long BLOCK_LIMIT_NUM = 100;
  private static final long TRANSACTION_LIMIT_NUM = 1000;
  @Autowired
  private Manager dbManager;
  @Autowired
  private ChainBaseManager chainBaseManager;
  @Autowired
  private Wallet wallet;
  @Autowired
  private TransactionUtil transactionUtil;
  @Autowired
  private NodeInfoService nodeInfoService;
  @Autowired
  private MetricsApiService metricsApiService;
  @Getter
  private DatabaseApi databaseApi = new DatabaseApi();
  private WalletApi walletApi = new WalletApi();
  @Getter
  private WalletSolidityApi walletSolidityApi = new WalletSolidityApi();
  @Getter
  private MonitorApi monitorApi = new MonitorApi();

  public RpcApiService() {
    port = Args.getInstance().getRpcPort();
    enable = Args.getInstance().isRpcEnable();
    executorName = "rpc-full-executor";
  }

  @Override
  protected void addService(NettyServerBuilder serverBuilder) {
    serverBuilder.addService(databaseApi);
    CommonParameter parameter = Args.getInstance();
    if (parameter.isSolidityNode()) {
      serverBuilder.addService(walletSolidityApi);
      if (parameter.isWalletExtensionApi()) {
        serverBuilder.addService(new WalletExtensionApi());
      }
    } else {
      serverBuilder.addService(walletApi);
    }

    if (parameter.isNodeMetricsEnable()) {
      serverBuilder.addService(monitorApi);
    }
  }


  private void callContract(TriggerSmartContract request,
      StreamObserver<TransactionExtention> responseObserver, boolean isConstant) {
    logger.debug("callContract received request: isConstant={}", isConstant);
    TransactionExtention.Builder trxExtBuilder = TransactionExtention.newBuilder();
    Return.Builder retBuilder = Return.newBuilder();
    try {
      TransactionCapsule trxCap = createTransactionCapsule(request,
          ContractType.TriggerSmartContract);
      Transaction trx;
      if (isConstant) {
        trx = wallet.triggerConstantContract(request, trxCap, trxExtBuilder, retBuilder);
      } else {
        trx = wallet.triggerContract(request, trxCap, trxExtBuilder, retBuilder);
      }
      trxExtBuilder.setTransaction(trx);
      trxExtBuilder.setTxid(trxCap.getTransactionId().getByteString());
      retBuilder.setResult(true).setCode(response_code.SUCCESS);
      trxExtBuilder.setResult(retBuilder);
    } catch (ContractValidateException | VMIllegalException e) {
      retBuilder.setResult(false).setCode(response_code.CONTRACT_VALIDATE_ERROR)
          .setMessage(ByteString.copyFromUtf8(Wallet.CONTRACT_VALIDATE_ERROR + e.getMessage()));
      trxExtBuilder.setResult(retBuilder);
      logger.warn(CONTRACT_VALIDATE_EXCEPTION, e.getMessage());
    } catch (RuntimeException e) {
      retBuilder.setResult(false).setCode(response_code.CONTRACT_EXE_ERROR)
          .setMessage(ByteString.copyFromUtf8(e.getClass() + " : " + e.getMessage()));
      trxExtBuilder.setResult(retBuilder);
      logger.warn("When run constant call in VM, have RuntimeException: " + e.getMessage());
    } catch (Exception e) {
      retBuilder.setResult(false).setCode(response_code.OTHER_ERROR)
          .setMessage(ByteString.copyFromUtf8(e.getClass() + " : " + e.getMessage()));
      trxExtBuilder.setResult(retBuilder);
      logger.warn(UNKNOWN_EXCEPTION_CAUGHT + e.getMessage(), e);
    } finally {
      responseObserver.onNext(trxExtBuilder.build());
      responseObserver.onCompleted();
      logger.debug("callContract completed");
    }
  }

  private TransactionCapsule createTransactionCapsule(com.google.protobuf.Message message,
      ContractType contractType) throws ContractValidateException {
    return wallet.createTransactionCapsule(message, contractType);
  }


  private TransactionExtention transaction2Extention(Transaction transaction) {
    if (transaction == null) {
      return null;
    }
    TransactionExtention.Builder trxExtBuilder = TransactionExtention.newBuilder();
    Return.Builder retBuilder = Return.newBuilder();
    trxExtBuilder.setTransaction(transaction);
    trxExtBuilder.setTxid(Sha256Hash.of(CommonParameter.getInstance()
        .isECKeyCryptoEngine(), transaction.getRawData().toByteArray()).getByteString());
    retBuilder.setResult(true).setCode(response_code.SUCCESS);
    trxExtBuilder.setResult(retBuilder);
    return trxExtBuilder.build();
  }

  private BlockExtention block2Extention(Block block) {
    if (block == null) {
      return null;
    }
    BlockExtention.Builder builder = BlockExtention.newBuilder();
    BlockCapsule blockCapsule = new BlockCapsule(block);
    builder.setBlockHeader(block.getBlockHeader());
    builder.setBlockid(ByteString.copyFrom(blockCapsule.getBlockId().getBytes()));
    for (int i = 0; i < block.getTransactionsCount(); i++) {
      Transaction transaction = block.getTransactions(i);
      builder.addTransactions(transaction2Extention(transaction));
    }
    return builder.build();
  }

  private StatusRuntimeException getRunTimeException(Exception e) {
    if (e != null) {
      return Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException();
    } else {
      return Status.INTERNAL.withDescription("unknown").asRuntimeException();
    }
  }

  /**
   * DatabaseApi.
   */
  public class DatabaseApi extends DatabaseImplBase {

    @Override
    public void getBlockReference(org.tron.api.GrpcAPI.EmptyMessage request,
        io.grpc.stub.StreamObserver<org.tron.api.GrpcAPI.BlockReference> responseObserver) {
      logger.debug("DatabaseApi.getBlockReference received request");
      long headBlockNum = dbManager.getDynamicPropertiesStore()
          .getLatestBlockHeaderNumber();
      byte[] blockHeaderHash = dbManager.getDynamicPropertiesStore()
          .getLatestBlockHeaderHash().getBytes();
      BlockReference ref = BlockReference.newBuilder()
          .setBlockHash(ByteString.copyFrom(blockHeaderHash))
          .setBlockNum(headBlockNum)
          .build();
      responseObserver.onNext(ref);
      responseObserver.onCompleted();
      logger.debug("DatabaseApi.getBlockReference completed");
    }

    @Override
    public void getNowBlock(EmptyMessage request, StreamObserver<Block> responseObserver) {
      logger.debug("DatabaseApi.getNowBlock received request");
      Block block = null;
      try {
        block = chainBaseManager.getHead().getInstance();
      } catch (StoreException e) {
        logger.error(e.getMessage());
      }
      responseObserver.onNext(block);
      responseObserver.onCompleted();
      logger.debug("DatabaseApi.getNowBlock completed");
    }

    @Override
    public void getBlockByNum(NumberMessage request, StreamObserver<Block> responseObserver) {
      logger.debug("DatabaseApi.getBlockByNum received request: num={}", request.getNum());
      Block block = null;
      try {
        block = chainBaseManager.getBlockByNum(request.getNum()).getInstance();
      } catch (StoreException e) {
        logger.error(e.getMessage());
      }
      responseObserver.onNext(block);
      responseObserver.onCompleted();
      logger.debug("DatabaseApi.getBlockByNum completed");
    }

    @Override
    public void getDynamicProperties(EmptyMessage request,
        StreamObserver<DynamicProperties> responseObserver) {
      logger.debug("DatabaseApi.getDynamicProperties received request");
      DynamicProperties.Builder builder = DynamicProperties.newBuilder();
      builder.setLastSolidityBlockNum(
          dbManager.getDynamicPropertiesStore().getLatestSolidifiedBlockNum());
      DynamicProperties dynamicProperties = builder.build();
      responseObserver.onNext(dynamicProperties);
      responseObserver.onCompleted();
      logger.debug("DatabaseApi.getDynamicProperties completed");
    }
  }

  /**
   * WalletSolidityApi.
   */
  public class WalletSolidityApi extends WalletSolidityImplBase {

    @Override
    public void getAccount(Account request, StreamObserver<Account> responseObserver) {
      logger.debug("WalletSolidityApi.getAccount received request: address={}",
          StringUtil.createReadableString(request.getAddress().toByteArray()));
      ByteString addressBs = request.getAddress();
      if (addressBs != null) {
        Account reply = wallet.getAccount(request);
        responseObserver.onNext(reply);
      } else {
        responseObserver.onNext(null);
      }
      responseObserver.onCompleted();
      logger.debug("WalletSolidityApi.getAccount completed");
    }

    @Override
    public void getAccountById(Account request, StreamObserver<Account> responseObserver) {
      logger.debug("WalletSolidityApi.getAccountById received request: accountId={}",
          request.getAccountId().toStringUtf8());
      ByteString id = request.getAccountId();
      if (id != null) {
        Account reply = wallet.getAccountById(request);
        responseObserver.onNext(reply);
      } else {
        responseObserver.onNext(null);
      }
      responseObserver.onCompleted();
      logger.debug("WalletSolidityApi.getAccountById completed");
    }

    @Override
    public void listWitnesses(EmptyMessage request, StreamObserver<WitnessList> responseObserver) {
      logger.debug("WalletSolidityApi.listWitnesses received request");
      responseObserver.onNext(wallet.getWitnessList());
      responseObserver.onCompleted();
      logger.debug("WalletSolidityApi.listWitnesses completed");
    }

    @Override
    public void getPaginatedNowWitnessList(PaginatedMessage request,
        StreamObserver<WitnessList> responseObserver) {
      logger.debug(
          "WalletSolidityApi.getPaginatedNowWitnessList received request: offset={}, limit={}",
          request.getOffset(), request.getLimit());
      try {
        responseObserver.onNext(
            wallet.getPaginatedNowWitnessList(request.getOffset(), request.getLimit()));
      } catch (MaintenanceUnavailableException e) {
        responseObserver.onError(getRunTimeException(e));
      }
      responseObserver.onCompleted();
      logger.debug("WalletSolidityApi.getPaginatedNowWitnessList completed");
    }

    @Override
    public void getAssetIssueList(EmptyMessage request,
        StreamObserver<AssetIssueList> responseObserver) {
      logger.debug("WalletSolidityApi.getAssetIssueList received request");
      responseObserver.onNext(wallet.getAssetIssueList());
      responseObserver.onCompleted();
      logger.debug("WalletSolidityApi.getAssetIssueList completed");
    }

    @Override
    public void getPaginatedAssetIssueList(PaginatedMessage request,
        StreamObserver<AssetIssueList> responseObserver) {
      logger.debug(
          "WalletSolidityApi.getPaginatedAssetIssueList received request: offset={}, limit={}",
          request.getOffset(), request.getLimit());
      responseObserver.onNext(wallet.getAssetIssueList(request.getOffset(), request.getLimit()));
      responseObserver.onCompleted();
      logger.debug("WalletSolidityApi.getPaginatedAssetIssueList completed");
    }

    @Override
    public void getAssetIssueByName(BytesMessage request,
        StreamObserver<AssetIssueContract> responseObserver) {
      logger.debug("WalletSolidityApi.getAssetIssueByName received request");
      ByteString assetName = request.getValue();
      if (assetName != null) {
        try {
          responseObserver.onNext(wallet.getAssetIssueByName(assetName));
        } catch (NonUniqueObjectException e) {
          responseObserver.onNext(null);
          logger.error("Solidity NonUniqueObjectException: {}", e.getMessage());
        }
      } else {
        responseObserver.onNext(null);
      }
      responseObserver.onCompleted();
      logger.debug("WalletSolidityApi.getAssetIssueByName completed");
    }

    @Override
    public void getAssetIssueListByName(BytesMessage request,
        StreamObserver<AssetIssueList> responseObserver) {
      logger.debug("WalletSolidityApi.getAssetIssueListByName received request");
      ByteString assetName = request.getValue();

      if (assetName != null) {
        responseObserver.onNext(wallet.getAssetIssueListByName(assetName));
      } else {
        responseObserver.onNext(null);
      }
      responseObserver.onCompleted();
      logger.debug("WalletSolidityApi.getAssetIssueListByName completed");
    }

    @Override
    public void getAssetIssueById(BytesMessage request,
        StreamObserver<AssetIssueContract> responseObserver) {
      logger.debug("WalletSolidityApi.getAssetIssueById received request");
      ByteString assetId = request.getValue();

      if (assetId != null) {
        responseObserver.onNext(wallet.getAssetIssueById(assetId.toStringUtf8()));
      } else {
        responseObserver.onNext(null);
      }
      responseObserver.onCompleted();
      logger.debug("WalletSolidityApi.getAssetIssueById completed");
    }

    @Override
    public void getNowBlock(EmptyMessage request, StreamObserver<Block> responseObserver) {
      logger.debug("WalletSolidityApi.getNowBlock received request");
      responseObserver.onNext(wallet.getNowBlock());
      responseObserver.onCompleted();
      logger.debug("WalletSolidityApi.getNowBlock completed");
    }

    @Override
    public void getNowBlock2(EmptyMessage request,
        StreamObserver<BlockExtention> responseObserver) {
      logger.debug("WalletSolidityApi.getNowBlock2 received request");
      responseObserver.onNext(block2Extention(wallet.getNowBlock()));
      responseObserver.onCompleted();
      logger.debug("WalletSolidityApi.getNowBlock2 completed");
    }

    @Override
    public void getBlockByNum(NumberMessage request, StreamObserver<Block> responseObserver) {
      logger.debug("WalletSolidityApi.getBlockByNum received request: num={}", request.getNum());
      long num = request.getNum();
      if (num >= 0) {
        Block reply = wallet.getBlockByNum(num);
        responseObserver.onNext(reply);
      } else {
        responseObserver.onNext(null);
      }
      responseObserver.onCompleted();
      logger.debug("WalletSolidityApi.getBlockByNum completed");
    }

    @Override
    public void getBlockByNum2(NumberMessage request,
        StreamObserver<BlockExtention> responseObserver) {
      logger.debug("WalletSolidityApi.getBlockByNum2 received request: num={}", request.getNum());
      long num = request.getNum();
      if (num >= 0) {
        Block reply = wallet.getBlockByNum(num);
        responseObserver.onNext(block2Extention(reply));
      } else {
        responseObserver.onNext(null);
      }
      responseObserver.onCompleted();
      logger.debug("WalletSolidityApi.getBlockByNum2 completed");
    }


    @Override
    public void getDelegatedResource(DelegatedResourceMessage request,
        StreamObserver<DelegatedResourceList> responseObserver) {
      logger.debug(
          "WalletSolidityApi.getDelegatedResource received request: fromAddress={}, toAddress={}",
          StringUtil.createReadableString(request.getFromAddress().toByteArray()),
          StringUtil.createReadableString(request.getToAddress().toByteArray()));
      responseObserver
          .onNext(wallet.getDelegatedResource(request.getFromAddress(), request.getToAddress()));
      responseObserver.onCompleted();
      logger.debug("WalletSolidityApi.getDelegatedResource completed");

    }

    @Override
    public void getDelegatedResourceV2(DelegatedResourceMessage request,
        StreamObserver<DelegatedResourceList> responseObserver) {
      logger.debug(
              "WalletSolidityApi.getDelegatedResourceV2 received request: fromAddress={}, toAddress={}",
              StringUtil.createReadableString(request.getFromAddress().toByteArray()),
              StringUtil.createReadableString(request.getToAddress().toByteArray()));
      try {
        responseObserver.onNext(wallet.getDelegatedResourceV2(
                request.getFromAddress(), request.getToAddress())
        );
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
      }
      responseObserver.onCompleted();
      logger.debug("WalletSolidityApi.getDelegatedResourceV2 completed");
    }

    @Override
    public void getDelegatedResourceAccountIndex(BytesMessage request,
        StreamObserver<org.tron.protos.Protocol.DelegatedResourceAccountIndex> responseObserver) {
      logger.debug("WalletSolidityApi.getDelegatedResourceAccountIndex received request");
      try {
        responseObserver
          .onNext(wallet.getDelegatedResourceAccountIndex(request.getValue()));
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e)); //TODO Fix ME
      }
      responseObserver.onCompleted();
      logger.debug("WalletSolidityApi.getDelegatedResourceAccountIndex completed");
    }

    @Override
    public void getDelegatedResourceAccountIndexV2(BytesMessage request,
        StreamObserver<org.tron.protos.Protocol.DelegatedResourceAccountIndex> responseObserver) {
      logger.debug("WalletSolidityApi.getDelegatedResourceAccountIndexV2 received request");
      try {
        responseObserver
                .onNext(wallet.getDelegatedResourceAccountIndexV2(request.getValue()));
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
      }
      responseObserver.onCompleted();
      logger.debug("WalletSolidityApi.getDelegatedResourceAccountIndexV2 completed");
    }

    @Override
    public void getCanDelegatedMaxSize(GrpcAPI.CanDelegatedMaxSizeRequestMessage request,
        StreamObserver<GrpcAPI.CanDelegatedMaxSizeResponseMessage> responseObserver) {
      logger.debug("WalletSolidityApi.getCanDelegatedMaxSize received request");
      try {
        responseObserver.onNext(wallet.getCanDelegatedMaxSize(
                        request.getOwnerAddress(),request.getType()));
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
      }
      responseObserver.onCompleted();
      logger.debug("WalletSolidityApi.getCanDelegatedMaxSize completed");
    }

    @Override
    public void getAvailableUnfreezeCount(GrpcAPI.GetAvailableUnfreezeCountRequestMessage request,
        StreamObserver<GrpcAPI.GetAvailableUnfreezeCountResponseMessage> responseObserver) {
      logger.debug("WalletSolidityApi.getAvailableUnfreezeCount received request");
      try {
        responseObserver.onNext(wallet.getAvailableUnfreezeCount(
                request.getOwnerAddress()));
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
      }
      responseObserver.onCompleted();
      logger.debug("WalletSolidityApi.getAvailableUnfreezeCount completed");
    }

    @Override
    public void getCanWithdrawUnfreezeAmount(CanWithdrawUnfreezeAmountRequestMessage request,
        StreamObserver<GrpcAPI.CanWithdrawUnfreezeAmountResponseMessage> responseObserver) {
      logger.debug("WalletSolidityApi.getCanWithdrawUnfreezeAmount received request");
      try {
        responseObserver
                .onNext(wallet.getCanWithdrawUnfreezeAmount(
                        request.getOwnerAddress(), request.getTimestamp())
        );
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
      }
      responseObserver.onCompleted();
      logger.debug("WalletSolidityApi.getCanWithdrawUnfreezeAmount completed");
    }

    @Override
    public void getExchangeById(BytesMessage request,
        StreamObserver<Exchange> responseObserver) {
      logger.debug("WalletSolidityApi.getExchangeById received request");
      ByteString exchangeId = request.getValue();

      if (Objects.nonNull(exchangeId)) {
        responseObserver.onNext(wallet.getExchangeById(exchangeId));
      } else {
        responseObserver.onNext(null);
      }
      responseObserver.onCompleted();
      logger.debug("WalletSolidityApi.getExchangeById completed");
    }

    @Override
    public void listExchanges(EmptyMessage request,
        StreamObserver<ExchangeList> responseObserver) {
      logger.debug("WalletSolidityApi.listExchanges received request");
      responseObserver.onNext(wallet.getExchangeList());
      responseObserver.onCompleted();
      logger.debug("WalletSolidityApi.listExchanges completed");
    }

    @Override
    public void getTransactionCountByBlockNum(NumberMessage request,
        StreamObserver<NumberMessage> responseObserver) {
      getTransactionCountByBlockNumCommon(request, responseObserver);
    }

    @Override
    public void getTransactionById(BytesMessage request,
        StreamObserver<Transaction> responseObserver) {
      logger.debug("WalletSolidityApi.getTransactionById received request");
      ByteString id = request.getValue();
      if (null != id) {
        Transaction reply = wallet.getTransactionById(id);

        responseObserver.onNext(reply);
      } else {
        responseObserver.onNext(null);
      }
      responseObserver.onCompleted();
      logger.debug("WalletSolidityApi.getTransactionById completed");
    }

    @Override
    public void getTransactionInfoById(BytesMessage request,
        StreamObserver<TransactionInfo> responseObserver) {
      logger.debug("WalletSolidityApi.getTransactionInfoById received request");
      ByteString id = request.getValue();
      if (null != id) {
        TransactionInfo reply = wallet.getTransactionInfoById(id);

        responseObserver.onNext(reply);
      } else {
        responseObserver.onNext(null);
      }
      responseObserver.onCompleted();
      logger.debug("WalletSolidityApi.getTransactionInfoById completed");
    }

    @Override
    public void getRewardInfo(BytesMessage request,
        StreamObserver<NumberMessage> responseObserver) {
      getRewardInfoCommon(request, responseObserver);
    }

    @Override
    public void getBrokerageInfo(BytesMessage request,
        StreamObserver<NumberMessage> responseObserver) {
      getBrokerageInfoCommon(request, responseObserver);
    }

    @Override
    public void getBurnTrx(EmptyMessage request, StreamObserver<NumberMessage> responseObserver) {
      getBurnTrxCommon(request, responseObserver);
    }

    @Override
    public void getMerkleTreeVoucherInfo(OutputPointInfo request,
        StreamObserver<IncrementalMerkleVoucherInfo> responseObserver) {
      logger.debug("WalletSolidityApi.getMerkleTreeVoucherInfo received request");
      try {
        IncrementalMerkleVoucherInfo witnessInfo = wallet
            .getMerkleTreeVoucherInfo(request);
        responseObserver.onNext(witnessInfo);
      } catch (Exception ex) {
        responseObserver.onError(getRunTimeException(ex));
      }
      responseObserver.onCompleted();
      logger.debug("WalletSolidityApi.getMerkleTreeVoucherInfo completed");
    }

    @Override
    public void scanNoteByIvk(GrpcAPI.IvkDecryptParameters request,
        StreamObserver<GrpcAPI.DecryptNotes> responseObserver) {
      logger.debug("WalletSolidityApi.scanNoteByIvk received request");
      long startNum = request.getStartBlockIndex();
      long endNum = request.getEndBlockIndex();

      try {
        DecryptNotes decryptNotes = wallet
            .scanNoteByIvk(startNum, endNum, request.getIvk().toByteArray());
        responseObserver.onNext(decryptNotes);
      } catch (BadItemException | ZksnarkException e) {
        responseObserver.onError(getRunTimeException(e));
      }
      responseObserver.onCompleted();
      logger.debug("WalletSolidityApi.scanNoteByIvk completed");
    }

    @Override
    public void scanAndMarkNoteByIvk(GrpcAPI.IvkDecryptAndMarkParameters request,
        StreamObserver<GrpcAPI.DecryptNotesMarked> responseObserver) {
      logger.debug("WalletSolidityApi.scanAndMarkNoteByIvk received request");
      long startNum = request.getStartBlockIndex();
      long endNum = request.getEndBlockIndex();

      try {
        DecryptNotesMarked decryptNotes = wallet.scanAndMarkNoteByIvk(startNum, endNum,
            request.getIvk().toByteArray(),
            request.getAk().toByteArray(),
            request.getNk().toByteArray());
        responseObserver.onNext(decryptNotes);
      } catch (BadItemException | ZksnarkException | InvalidProtocolBufferException
          | ItemNotFoundException e) {
        responseObserver.onError(getRunTimeException(e));
      }
      responseObserver.onCompleted();
      logger.debug("WalletSolidityApi.scanAndMarkNoteByIvk completed");
    }

    @Override
    public void scanNoteByOvk(GrpcAPI.OvkDecryptParameters request,
        StreamObserver<GrpcAPI.DecryptNotes> responseObserver) {
      logger.debug("WalletSolidityApi.scanNoteByOvk received request");
      long startNum = request.getStartBlockIndex();
      long endNum = request.getEndBlockIndex();
      try {
        DecryptNotes decryptNotes = wallet
            .scanNoteByOvk(startNum, endNum, request.getOvk().toByteArray());
        responseObserver.onNext(decryptNotes);
      } catch (BadItemException | ZksnarkException e) {
        responseObserver.onError(getRunTimeException(e));
      }
      responseObserver.onCompleted();
      logger.debug("WalletSolidityApi.scanNoteByOvk completed");
    }

    @Override
    public void isSpend(NoteParameters request, StreamObserver<SpendResult> responseObserver) {
      logger.debug("WalletSolidityApi.isSpend received request");
      try {
        responseObserver.onNext(wallet.isSpend(request));
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
      }
      responseObserver.onCompleted();
      logger.debug("WalletSolidityApi.isSpend completed");
    }

    @Override
    public void scanShieldedTRC20NotesByIvk(IvkDecryptTRC20Parameters request,
        StreamObserver<DecryptNotesTRC20> responseObserver) {
      logger.debug("WalletSolidityApi.scanShieldedTRC20NotesByIvk received request");
      long startNum = request.getStartBlockIndex();
      long endNum = request.getEndBlockIndex();
      byte[] contractAddress = request.getShieldedTRC20ContractAddress().toByteArray();
      byte[] ivk = request.getIvk().toByteArray();
      byte[] ak = request.getAk().toByteArray();
      byte[] nk = request.getNk().toByteArray();
      ProtocolStringList topicsList = request.getEventsList();

      try {
        responseObserver.onNext(
            wallet.scanShieldedTRC20NotesByIvk(startNum, endNum, contractAddress, ivk, ak, nk,
                topicsList));

      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
      }
      responseObserver.onCompleted();
      logger.debug("WalletSolidityApi.scanShieldedTRC20NotesByIvk completed");
    }

    @Override
    public void scanShieldedTRC20NotesByOvk(OvkDecryptTRC20Parameters request,
        StreamObserver<DecryptNotesTRC20> responseObserver) {
      logger.debug("WalletSolidityApi.scanShieldedTRC20NotesByOvk received request");
      long startNum = request.getStartBlockIndex();
      long endNum = request.getEndBlockIndex();
      byte[] contractAddress = request.getShieldedTRC20ContractAddress().toByteArray();
      byte[] ovk = request.getOvk().toByteArray();
      ProtocolStringList topicList = request.getEventsList();
      try {
        responseObserver
            .onNext(wallet
                .scanShieldedTRC20NotesByOvk(startNum, endNum, ovk, contractAddress, topicList));
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
      }
      responseObserver.onCompleted();
      logger.debug("WalletSolidityApi.scanShieldedTRC20NotesByOvk completed");
    }

    @Override
    public void isShieldedTRC20ContractNoteSpent(NfTRC20Parameters request,
        StreamObserver<GrpcAPI.NullifierResult> responseObserver) {
      logger.debug("WalletSolidityApi.isShieldedTRC20ContractNoteSpent received request");
      try {
        responseObserver.onNext(wallet.isShieldedTRC20ContractNoteSpent(request));
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
      }
      responseObserver.onCompleted();
      logger.debug("WalletSolidityApi.isShieldedTRC20ContractNoteSpent completed");
    }

    @Override
    public void getMarketOrderByAccount(BytesMessage request,
        StreamObserver<MarketOrderList> responseObserver) {
      logger.debug("WalletSolidityApi.getMarketOrderByAccount received request");
      try {
        ByteString address = request.getValue();

        MarketOrderList marketOrderList = wallet
            .getMarketOrderByAccount(address);
        responseObserver.onNext(marketOrderList);
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
      }
      responseObserver.onCompleted();
      logger.debug("WalletSolidityApi.getMarketOrderByAccount completed");
    }

    @Override
    public void getMarketOrderById(BytesMessage request,
        StreamObserver<MarketOrder> responseObserver) {
      logger.debug("WalletSolidityApi.getMarketOrderById received request");
      try {
        ByteString address = request.getValue();

        MarketOrder marketOrder = wallet
            .getMarketOrderById(address);
        responseObserver.onNext(marketOrder);
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
      }
      responseObserver.onCompleted();
      logger.debug("WalletSolidityApi.getMarketOrderById completed");
    }

    @Override
    public void getMarketPriceByPair(MarketOrderPair request,
        StreamObserver<MarketPriceList> responseObserver) {
      logger.debug("WalletSolidityApi.getMarketPriceByPair received request");
      try {
        MarketPriceList marketPriceList = wallet
            .getMarketPriceByPair(request.getSellTokenId().toByteArray(),
                request.getBuyTokenId().toByteArray());
        responseObserver.onNext(marketPriceList);
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
      }
      responseObserver.onCompleted();
      logger.debug("WalletSolidityApi.getMarketPriceByPair completed");
    }

    @Override
    public void getMarketOrderListByPair(org.tron.protos.Protocol.MarketOrderPair request,
        StreamObserver<MarketOrderList> responseObserver) {
      logger.debug("WalletSolidityApi.getMarketOrderListByPair received request");
      try {
        MarketOrderList orderPairList = wallet
            .getMarketOrderListByPair(request.getSellTokenId().toByteArray(),
                request.getBuyTokenId().toByteArray());
        responseObserver.onNext(orderPairList);
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
      }
      responseObserver.onCompleted();
      logger.debug("WalletSolidityApi.getMarketOrderListByPair completed");
    }

    @Override
    public void getMarketPairList(EmptyMessage request,
        StreamObserver<MarketOrderPairList> responseObserver) {
      logger.debug("WalletSolidityApi.getMarketPairList received request");
      try {
        MarketOrderPairList pairList = wallet.getMarketPairList();
        responseObserver.onNext(pairList);
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
      }
      responseObserver.onCompleted();
      logger.debug("WalletSolidityApi.getMarketPairList completed");
    }

    @Override
    public void triggerConstantContract(TriggerSmartContract request,
        StreamObserver<TransactionExtention> responseObserver) {

      callContract(request, responseObserver, true);
    }

    @Override
    public void estimateEnergy(TriggerSmartContract request,
        StreamObserver<EstimateEnergyMessage> responseObserver) {
      logger.debug("WalletSolidityApi.estimateEnergy received request");
      TransactionExtention.Builder trxExtBuilder = TransactionExtention.newBuilder();
      Return.Builder retBuilder = Return.newBuilder();
      EstimateEnergyMessage.Builder estimateBuilder
          = EstimateEnergyMessage.newBuilder();

      try {
        TransactionCapsule trxCap = createTransactionCapsule(request,
            ContractType.TriggerSmartContract);
        wallet.estimateEnergy(request, trxCap, trxExtBuilder, retBuilder, estimateBuilder);
      } catch (ContractValidateException | VMIllegalException e) {
        retBuilder.setResult(false).setCode(response_code.CONTRACT_VALIDATE_ERROR)
            .setMessage(ByteString.copyFromUtf8(Wallet
                .CONTRACT_VALIDATE_ERROR + e.getMessage()));
        logger.warn(CONTRACT_VALIDATE_EXCEPTION, e.getMessage());
      } catch (RuntimeException e) {
        retBuilder.setResult(false).setCode(response_code.CONTRACT_EXE_ERROR)
            .setMessage(ByteString.copyFromUtf8(e.getClass() + " : " + e.getMessage()));
        logger.warn("When run estimate energy in VM, have Runtime Exception: " + e.getMessage());
      } catch (Exception e) {
        retBuilder.setResult(false).setCode(response_code.OTHER_ERROR)
            .setMessage(ByteString.copyFromUtf8(e.getClass() + " : " + e.getMessage()));
        logger.warn(UNKNOWN_EXCEPTION_CAUGHT + e.getMessage(), e);
      } finally {
        estimateBuilder.setResult(retBuilder);
        responseObserver.onNext(estimateBuilder.build());
        responseObserver.onCompleted();
        logger.debug("WalletSolidityApi.estimateEnergy completed");
      }
    }

    @Override
    public void getTransactionInfoByBlockNum(NumberMessage request,
        StreamObserver<TransactionInfoList> responseObserver) {
      logger.debug("WalletSolidityApi.getTransactionInfoByBlockNum received request: num={}",
          request.getNum());
      try {
        responseObserver.onNext(wallet.getTransactionInfoByBlockNum(request.getNum()));
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
      }

      responseObserver.onCompleted();
      logger.debug("WalletSolidityApi.getTransactionInfoByBlockNum completed");
    }

    @Override
    public void getBlock(GrpcAPI.BlockReq  request,
        StreamObserver<BlockExtention> responseObserver) {
      getBlockCommon(request, responseObserver);
    }

    @Override
    public void getBandwidthPrices(EmptyMessage request,
        StreamObserver<PricesResponseMessage> responseObserver) {
      logger.debug("WalletSolidityApi.getBandwidthPrices received request");
      try {
        responseObserver.onNext(wallet.getBandwidthPrices());
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
      }
      responseObserver.onCompleted();
      logger.debug("WalletSolidityApi.getBandwidthPrices completed");
    }

    @Override
    public void getEnergyPrices(EmptyMessage request,
        StreamObserver<PricesResponseMessage> responseObserver) {
      logger.debug("WalletSolidityApi.getEnergyPrices received request");
      try {
        responseObserver.onNext(wallet.getEnergyPrices());
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
      }
      responseObserver.onCompleted();
      logger.debug("WalletSolidityApi.getEnergyPrices completed");
    }
  }

  /**
   * WalletExtensionApi.
   */
  public class WalletExtensionApi extends WalletExtensionGrpc.WalletExtensionImplBase {

    private TransactionListExtention transactionList2Extention(TransactionList transactionList) {
      if (transactionList == null) {
        return null;
      }
      TransactionListExtention.Builder builder = TransactionListExtention.newBuilder();
      for (Transaction transaction : transactionList.getTransactionList()) {
        builder.addTransaction(transaction2Extention(transaction));
      }
      return builder.build();
    }
  }

  /**
   * WalletApi.
   */
  public class WalletApi extends WalletImplBase {

    private BlockListExtention blockList2Extention(BlockList blockList) {
      if (blockList == null) {
        return null;
      }
      BlockListExtention.Builder builder = BlockListExtention.newBuilder();
      for (Block block : blockList.getBlockList()) {
        builder.addBlock(block2Extention(block));
      }
      return builder.build();
    }

    @Override
    public void getAccount(Account req, StreamObserver<Account> responseObserver) {
      logger.debug("WalletApi.getAccount received request: address={}",
          StringUtil.createReadableString(req.getAddress().toByteArray()));
      ByteString addressBs = req.getAddress();
      if (addressBs != null) {
        Account reply = wallet.getAccount(req);
        responseObserver.onNext(reply);
      } else {
        responseObserver.onNext(null);
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.getAccount completed");
    }

    @Override
    public void getAccountById(Account req, StreamObserver<Account> responseObserver) {
      logger.debug("WalletApi.getAccountById received request: accountId={}",
          req.getAccountId().toStringUtf8());
      ByteString accountId = req.getAccountId();
      if (accountId != null) {
        Account reply = wallet.getAccountById(req);
        responseObserver.onNext(reply);
      } else {
        responseObserver.onNext(null);
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.getAccountById completed");
    }

    /**
     *
     */
    public void getAccountBalance(AccountBalanceRequest request,
        StreamObserver<AccountBalanceResponse> responseObserver) {
      logger.debug("WalletApi.getAccountBalance received request");
      try {
        AccountBalanceResponse accountBalanceResponse = wallet.getAccountBalance(request);
        responseObserver.onNext(accountBalanceResponse);
        responseObserver.onCompleted();
      } catch (Exception e) {
        responseObserver.onError(e);
      }
      logger.debug("WalletApi.getAccountBalance completed");
    }

    /**
     *
     */
    public void getBlockBalanceTrace(BlockBalanceTrace.BlockIdentifier request,
        StreamObserver<BlockBalanceTrace> responseObserver) {
      logger.debug("WalletApi.getBlockBalanceTrace received request");
      try {
        BlockBalanceTrace blockBalanceTrace = wallet.getBlockBalance(request);
        responseObserver.onNext(blockBalanceTrace);
        responseObserver.onCompleted();
      } catch (Exception e) {
        responseObserver.onError(e);
      }
      logger.debug("WalletApi.getBlockBalanceTrace completed");
    }

    @Override
    public void createTransaction(TransferContract request,
        StreamObserver<Transaction> responseObserver) {
      logger.debug("WalletApi.createTransaction received request");
      try {
        responseObserver
            .onNext(
                createTransactionCapsule(request, ContractType.TransferContract).getInstance());
      } catch (ContractValidateException e) {
        responseObserver
            .onNext(null);
        logger.debug(CONTRACT_VALIDATE_EXCEPTION, e.getMessage());
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.createTransaction response completed");
    }

    @Override
    public void createTransaction2(TransferContract request,
        StreamObserver<TransactionExtention> responseObserver) {
      createTransactionExtention(request, ContractType.TransferContract, responseObserver);
    }

    private void createTransactionExtention(Message request, ContractType contractType,
        StreamObserver<TransactionExtention> responseObserver) {
      logger.debug("WalletApi.createTransactionExtention received request for contract type: {}",
          contractType);
      TransactionExtention.Builder trxExtBuilder = TransactionExtention.newBuilder();
      Return.Builder retBuilder = Return.newBuilder();
      try {
        TransactionCapsule trx = createTransactionCapsule(request, contractType);
        trxExtBuilder.setTransaction(trx.getInstance());
        trxExtBuilder.setTxid(trx.getTransactionId().getByteString());
        retBuilder.setResult(true).setCode(response_code.SUCCESS);
      } catch (ContractValidateException e) {
        retBuilder.setResult(false).setCode(response_code.CONTRACT_VALIDATE_ERROR)
            .setMessage(ByteString
                .copyFromUtf8(Wallet.CONTRACT_VALIDATE_ERROR + e.getMessage()));
        logger.debug(CONTRACT_VALIDATE_EXCEPTION, e.getMessage());
      } catch (Exception e) {
        retBuilder.setResult(false).setCode(response_code.OTHER_ERROR)
            .setMessage(ByteString.copyFromUtf8(e.getClass() + " : " + e.getMessage()));
        logger.info(EXCEPTION_CAUGHT + e.getMessage());
      }
      trxExtBuilder.setResult(retBuilder);
      responseObserver.onNext(trxExtBuilder.build());
      responseObserver.onCompleted();
      logger.debug("WalletApi.createTransactionExtention completed for contract type: {}",
          contractType);
    }

    @Override
    public void getTransactionSignWeight(Transaction req,
        StreamObserver<TransactionSignWeight> responseObserver) {
      logger.debug("WalletApi.getTransactionSignWeight received request");
      TransactionSignWeight tsw = transactionUtil.getTransactionSignWeight(req);
      responseObserver.onNext(tsw);
      responseObserver.onCompleted();
      logger.debug("WalletApi.getTransactionSignWeight response completed");
    }

    @Override
    public void getTransactionApprovedList(Transaction req,
        StreamObserver<TransactionApprovedList> responseObserver) {
      logger.debug("WalletApi.getTransactionApprovedList received request");
      TransactionApprovedList tal = wallet.getTransactionApprovedList(req);
      responseObserver.onNext(tal);
      responseObserver.onCompleted();
      logger.debug("WalletApi.getTransactionApprovedList response completed");
    }

    @Override
    public void broadcastTransaction(Transaction req,
        StreamObserver<GrpcAPI.Return> responseObserver) {
      logger.debug("WalletApi.broadcastTransaction received request");
      GrpcAPI.Return result = wallet.broadcastTransaction(req);
      responseObserver.onNext(result);
      responseObserver.onCompleted();
      logger.debug("WalletApi.broadcastTransaction response completed");
    }

    @Override
    public void createAssetIssue(AssetIssueContract request,
        StreamObserver<Transaction> responseObserver) {
      logger.debug("WalletApi.createAssetIssue received request");
      try {
        responseObserver.onNext(
            createTransactionCapsule(request, ContractType.AssetIssueContract).getInstance());
      } catch (ContractValidateException e) {
        responseObserver.onNext(null);
        logger.debug(CONTRACT_VALIDATE_EXCEPTION, e.getMessage());
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.createAssetIssue response completed");
    }

    @Override
    public void createAssetIssue2(AssetIssueContract request,
        StreamObserver<TransactionExtention> responseObserver) {
      createTransactionExtention(request, ContractType.AssetIssueContract, responseObserver);
    }

    @Override
    public void unfreezeAsset(UnfreezeAssetContract request,
        StreamObserver<Transaction> responseObserver) {
      logger.debug("WalletApi.unfreezeAsset received request");
      try {
        responseObserver.onNext(
            createTransactionCapsule(request, ContractType.UnfreezeAssetContract).getInstance());
      } catch (ContractValidateException e) {
        responseObserver.onNext(null);
        logger.debug(CONTRACT_VALIDATE_EXCEPTION, e.getMessage());
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.unfreezeAsset response completed");
    }

    @Override
    public void unfreezeAsset2(UnfreezeAssetContract request,
        StreamObserver<TransactionExtention> responseObserver) {
      createTransactionExtention(request, ContractType.UnfreezeAssetContract, responseObserver);
    }

    //refactor、test later
    private void checkVoteWitnessAccount(VoteWitnessContract req) {
      //send back to cli
      ByteString ownerAddress = req.getOwnerAddress();
      Preconditions.checkNotNull(ownerAddress, "OwnerAddress is null");

      AccountCapsule account = dbManager.getAccountStore().get(ownerAddress.toByteArray());
      Preconditions.checkNotNull(account,
          "OwnerAddress[" + StringUtil.createReadableString(ownerAddress) + "] not exists");

      int votesCount = req.getVotesCount();
      Preconditions.checkArgument(votesCount <= 0, "VotesCount[" + votesCount + "] <= 0");
      if (dbManager.getDynamicPropertiesStore().supportAllowNewResourceModel()) {
        Preconditions.checkArgument(account.getAllTronPower() < votesCount,
            "tron power[" + account.getAllTronPower() + "] <  VotesCount[" + votesCount + "]");
      } else {
        Preconditions.checkArgument(account.getTronPower() < votesCount,
            "tron power[" + account.getTronPower() + "] <  VotesCount[" + votesCount + "]");
      }

      req.getVotesList().forEach(vote -> {
        ByteString voteAddress = vote.getVoteAddress();
        WitnessCapsule witness = dbManager.getWitnessStore()
            .get(voteAddress.toByteArray());
        String readableWitnessAddress = StringUtil.createReadableString(voteAddress);

        Preconditions.checkNotNull(witness, "witness[" + readableWitnessAddress + "] not exists");
        Preconditions.checkArgument(vote.getVoteCount() <= 0,
            "VoteAddress[" + readableWitnessAddress + "], VotesCount[" + vote
                .getVoteCount() + "] <= 0");
      });
    }

    @Override
    public void voteWitnessAccount(VoteWitnessContract request,
        StreamObserver<Transaction> responseObserver) {
      logger.debug("WalletApi.voteWitnessAccount received request");
      try {
        responseObserver.onNext(
            createTransactionCapsule(request, ContractType.VoteWitnessContract).getInstance());
      } catch (ContractValidateException e) {
        responseObserver
            .onNext(null);
        logger.debug(CONTRACT_VALIDATE_EXCEPTION, e.getMessage());
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.voteWitnessAccount response completed");
    }

    @Override
    public void voteWitnessAccount2(VoteWitnessContract request,
        StreamObserver<TransactionExtention> responseObserver) {
      createTransactionExtention(request, ContractType.VoteWitnessContract, responseObserver);
    }

    @Override
    public void updateSetting(UpdateSettingContract request,
        StreamObserver<TransactionExtention> responseObserver) {
      createTransactionExtention(request, ContractType.UpdateSettingContract,
          responseObserver);
    }

    @Override
    public void updateEnergyLimit(UpdateEnergyLimitContract request,
        StreamObserver<TransactionExtention> responseObserver) {
      createTransactionExtention(request, ContractType.UpdateEnergyLimitContract,
          responseObserver);
    }

    @Override
    public void clearContractABI(ClearABIContract request,
        StreamObserver<TransactionExtention> responseObserver) {
      createTransactionExtention(request, ContractType.ClearABIContract,
          responseObserver);
    }

    @Override
    public void createWitness(WitnessCreateContract request,
        StreamObserver<Transaction> responseObserver) {
      logger.debug("WalletApi.createWitness received request");
      try {
        responseObserver.onNext(
            createTransactionCapsule(request, ContractType.WitnessCreateContract).getInstance());
      } catch (ContractValidateException e) {
        responseObserver
            .onNext(null);
        logger.debug(CONTRACT_VALIDATE_EXCEPTION, e.getMessage());
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.createWitness response completed");
    }

    @Override
    public void createWitness2(WitnessCreateContract request,
        StreamObserver<TransactionExtention> responseObserver) {
      createTransactionExtention(request, ContractType.WitnessCreateContract, responseObserver);
    }

    @Override
    public void createAccount(AccountCreateContract request,
        StreamObserver<Transaction> responseObserver) {
      logger.debug("WalletApi.createAccount received request");
      try {
        responseObserver.onNext(
            createTransactionCapsule(request, ContractType.AccountCreateContract).getInstance());
      } catch (ContractValidateException e) {
        responseObserver
            .onNext(null);
        logger.debug(CONTRACT_VALIDATE_EXCEPTION, e.getMessage());
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.createAccount response completed");
    }

    @Override
    public void createAccount2(AccountCreateContract request,
        StreamObserver<TransactionExtention> responseObserver) {
      createTransactionExtention(request, ContractType.AccountCreateContract, responseObserver);
    }

    @Override
    public void updateWitness(WitnessUpdateContract request,
        StreamObserver<Transaction> responseObserver) {
      logger.debug("WalletApi.updateWitness received request");
      try {
        responseObserver.onNext(
            createTransactionCapsule(request, ContractType.WitnessUpdateContract).getInstance());
      } catch (ContractValidateException e) {
        responseObserver
            .onNext(null);
        logger.debug(CONTRACT_VALIDATE_EXCEPTION, e.getMessage());
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.updateWitness response completed");
    }

    @Override
    public void updateWitness2(WitnessUpdateContract request,
        StreamObserver<TransactionExtention> responseObserver) {
      createTransactionExtention(request, ContractType.WitnessUpdateContract, responseObserver);
    }

    @Override
    public void updateAccount(AccountUpdateContract request,
        StreamObserver<Transaction> responseObserver) {
      logger.debug("WalletApi.updateAccount received request");
      try {
        responseObserver.onNext(
            createTransactionCapsule(request, ContractType.AccountUpdateContract).getInstance());
      } catch (ContractValidateException e) {
        responseObserver
            .onNext(null);
        logger.debug(CONTRACT_VALIDATE_EXCEPTION, e.getMessage());
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.updateAccount response completed");
    }

    @Override
    public void setAccountId(SetAccountIdContract request,
        StreamObserver<Transaction> responseObserver) {
      logger.debug("WalletApi.setAccountId received request");
      try {
        responseObserver.onNext(
            createTransactionCapsule(request, ContractType.SetAccountIdContract).getInstance());
      } catch (ContractValidateException e) {
        responseObserver
            .onNext(null);
        logger.debug(CONTRACT_VALIDATE_EXCEPTION, e.getMessage());
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.setAccountId response completed");
    }

    @Override
    public void updateAccount2(AccountUpdateContract request,
        StreamObserver<TransactionExtention> responseObserver) {
      createTransactionExtention(request, ContractType.AccountUpdateContract, responseObserver);
    }

    @Override
    public void updateAsset(UpdateAssetContract request,
        StreamObserver<Transaction> responseObserver) {
      logger.debug("WalletApi.updateAsset received request");
      try {
        responseObserver.onNext(
            createTransactionCapsule(request,
                ContractType.UpdateAssetContract).getInstance());
      } catch (ContractValidateException e) {
        responseObserver
            .onNext(null);
        logger.debug("ContractValidateException", e.getMessage());
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.updateAsset response completed");
    }

    @Override
    public void updateAsset2(UpdateAssetContract request,
        StreamObserver<TransactionExtention> responseObserver) {
      createTransactionExtention(request, ContractType.UpdateAssetContract, responseObserver);
    }

    @Override
    public void freezeBalance(FreezeBalanceContract request,
        StreamObserver<Transaction> responseObserver) {
      logger.debug("WalletApi.freezeBalance received request");
      try {
        responseObserver.onNext(
            createTransactionCapsule(request, ContractType.FreezeBalanceContract).getInstance());
      } catch (ContractValidateException e) {
        responseObserver
            .onNext(null);
        logger.debug(CONTRACT_VALIDATE_EXCEPTION, e.getMessage());
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.freezeBalance response completed");
    }

    @Override
    public void freezeBalance2(FreezeBalanceContract request,
        StreamObserver<TransactionExtention> responseObserver) {
      createTransactionExtention(request, ContractType.FreezeBalanceContract, responseObserver);
    }

    @Override
    public void freezeBalanceV2(BalanceContract.FreezeBalanceV2Contract request,
                                StreamObserver<TransactionExtention> responseObserver) {
      createTransactionExtention(request, ContractType.FreezeBalanceV2Contract, responseObserver);
    }

    @Override
    public void unfreezeBalance(UnfreezeBalanceContract request,
        StreamObserver<Transaction> responseObserver) {
      logger.debug("WalletApi.unfreezeBalance received request");
      try {
        responseObserver.onNext(
            createTransactionCapsule(request, ContractType.UnfreezeBalanceContract)
                .getInstance());
      } catch (ContractValidateException e) {
        responseObserver
            .onNext(null);
        logger.debug(CONTRACT_VALIDATE_EXCEPTION, e.getMessage());
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.unfreezeBalance response completed");
    }

    @Override
    public void unfreezeBalance2(UnfreezeBalanceContract request,
        StreamObserver<TransactionExtention> responseObserver) {
      createTransactionExtention(request, ContractType.UnfreezeBalanceContract, responseObserver);
    }

    @Override
    public void unfreezeBalanceV2(BalanceContract.UnfreezeBalanceV2Contract request,
                                  StreamObserver<TransactionExtention> responseObserver) {
      createTransactionExtention(request, ContractType.UnfreezeBalanceV2Contract, responseObserver);
    }

    @Override
    public void withdrawBalance(WithdrawBalanceContract request,
        StreamObserver<Transaction> responseObserver) {
      logger.debug("WalletApi.withdrawBalance received request");
      try {
        responseObserver.onNext(
            createTransactionCapsule(request, ContractType.WithdrawBalanceContract)
                .getInstance());
      } catch (ContractValidateException e) {
        responseObserver
            .onNext(null);
        logger.debug(CONTRACT_VALIDATE_EXCEPTION, e.getMessage());
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.withdrawBalance response completed");
    }

    @Override
    public void withdrawBalance2(WithdrawBalanceContract request,
        StreamObserver<TransactionExtention> responseObserver) {
      createTransactionExtention(request, ContractType.WithdrawBalanceContract, responseObserver);
    }

    @Override
    public void withdrawExpireUnfreeze(WithdrawExpireUnfreezeContract request,
                                       StreamObserver<TransactionExtention> responseObserver) {
      createTransactionExtention(request, ContractType.WithdrawExpireUnfreezeContract,
              responseObserver);
    }

    @Override
    public void delegateResource(DelegateResourceContract request,
                                 StreamObserver<TransactionExtention> responseObserver) {
      createTransactionExtention(request, ContractType.DelegateResourceContract,
          responseObserver);
    }

    @Override
    public void unDelegateResource(UnDelegateResourceContract request,
                                       StreamObserver<TransactionExtention> responseObserver) {
      createTransactionExtention(request, ContractType.UnDelegateResourceContract,
          responseObserver);
    }

    @Override
    public void cancelAllUnfreezeV2(CancelAllUnfreezeV2Contract request,
                                    StreamObserver<TransactionExtention> responseObserver) {
      createTransactionExtention(request, ContractType.CancelAllUnfreezeV2Contract,
          responseObserver);
    }

    @Override
    public void proposalCreate(ProposalCreateContract request,
        StreamObserver<TransactionExtention> responseObserver) {
      createTransactionExtention(request, ContractType.ProposalCreateContract, responseObserver);
    }


    @Override
    public void proposalApprove(ProposalApproveContract request,
        StreamObserver<TransactionExtention> responseObserver) {
      createTransactionExtention(request, ContractType.ProposalApproveContract, responseObserver);
    }

    @Override
    public void proposalDelete(ProposalDeleteContract request,
        StreamObserver<TransactionExtention> responseObserver) {
      createTransactionExtention(request, ContractType.ProposalDeleteContract, responseObserver);
    }

    @Override
    public void exchangeCreate(ExchangeCreateContract request,
        StreamObserver<TransactionExtention> responseObserver) {
      createTransactionExtention(request, ContractType.ExchangeCreateContract, responseObserver);
    }


    @Override
    public void exchangeInject(ExchangeInjectContract request,
        StreamObserver<TransactionExtention> responseObserver) {
      createTransactionExtention(request, ContractType.ExchangeInjectContract, responseObserver);
    }

    @Override
    public void exchangeWithdraw(ExchangeWithdrawContract request,
        StreamObserver<TransactionExtention> responseObserver) {
      createTransactionExtention(request, ContractType.ExchangeWithdrawContract, responseObserver);
    }

    @Override
    public void exchangeTransaction(ExchangeTransactionContract request,
        StreamObserver<TransactionExtention> responseObserver) {
      createTransactionExtention(request, ContractType.ExchangeTransactionContract,
          responseObserver);
    }

    @Override
    public void getNowBlock(EmptyMessage request, StreamObserver<Block> responseObserver) {
      logger.debug("WalletApi.getNowBlock received request");
      responseObserver.onNext(wallet.getNowBlock());
      responseObserver.onCompleted();
      logger.debug("WalletApi.getNowBlock response completed");
    }

    @Override
    public void getNowBlock2(EmptyMessage request,
        StreamObserver<BlockExtention> responseObserver) {
      logger.debug("WalletApi.getNowBlock2 received request");
      Block block = wallet.getNowBlock();
      responseObserver.onNext(block2Extention(block));
      responseObserver.onCompleted();
      logger.debug("WalletApi.getNowBlock2 response completed");
    }

    @Override
    public void getBlockByNum(NumberMessage request, StreamObserver<Block> responseObserver) {
      logger.debug("WalletApi.getBlockByNum received request for block num: {}",
          request.getNum());
      responseObserver.onNext(wallet.getBlockByNum(request.getNum()));
      responseObserver.onCompleted();
      logger.debug("WalletApi.getBlockByNum response completed for block num: {}",
          request.getNum());
    }

    @Override
    public void getBlockByNum2(NumberMessage request,
        StreamObserver<BlockExtention> responseObserver) {
      logger.debug("WalletApi.getBlockByNum2 received request for block num: {}",
          request.getNum());
      Block block = wallet.getBlockByNum(request.getNum());
      responseObserver.onNext(block2Extention(block));
      responseObserver.onCompleted();
      logger.debug("WalletApi.getBlockByNum2 response completed for block num: {}",
          request.getNum());
    }

    @Override
    public void getTransactionCountByBlockNum(NumberMessage request,
        StreamObserver<NumberMessage> responseObserver) {
      getTransactionCountByBlockNumCommon(request, responseObserver);
    }

    @Override
    public void listNodes(EmptyMessage request, StreamObserver<NodeList> responseObserver) {
      logger.debug("WalletApi.listNodes received request");
      responseObserver.onNext(wallet.listNodes());
      responseObserver.onCompleted();
      logger.debug("WalletApi.listNodes response completed");
    }

    @Override
    public void transferAsset(TransferAssetContract request,
        StreamObserver<Transaction> responseObserver) {
      logger.debug("WalletApi.transferAsset received request");
      try {
        responseObserver
            .onNext(createTransactionCapsule(request, ContractType.TransferAssetContract)
                .getInstance());
      } catch (ContractValidateException e) {
        responseObserver
            .onNext(null);
        logger.debug(CONTRACT_VALIDATE_EXCEPTION, e.getMessage());
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.transferAsset response completed");
    }

    @Override
    public void transferAsset2(TransferAssetContract request,
        StreamObserver<TransactionExtention> responseObserver) {
      createTransactionExtention(request, ContractType.TransferAssetContract, responseObserver);
    }

    @Override
    public void participateAssetIssue(ParticipateAssetIssueContract request,
        StreamObserver<Transaction> responseObserver) {
      logger.debug("WalletApi.participateAssetIssue received request");
      try {
        responseObserver
            .onNext(createTransactionCapsule(request, ContractType.ParticipateAssetIssueContract)
                .getInstance());
      } catch (ContractValidateException e) {
        responseObserver
            .onNext(null);
        logger.debug(CONTRACT_VALIDATE_EXCEPTION, e.getMessage());
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.participateAssetIssue response completed");
    }

    @Override
    public void participateAssetIssue2(ParticipateAssetIssueContract request,
        StreamObserver<TransactionExtention> responseObserver) {
      createTransactionExtention(request, ContractType.ParticipateAssetIssueContract,
          responseObserver);
    }

    @Override
    public void getAssetIssueByAccount(Account request,
        StreamObserver<AssetIssueList> responseObserver) {
      logger.debug("WalletApi.getAssetIssueByAccount received request");
      ByteString fromBs = request.getAddress();

      if (fromBs != null) {
        responseObserver.onNext(wallet.getAssetIssueByAccount(fromBs));
      } else {
        responseObserver.onNext(null);
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.getAssetIssueByAccount response completed");
    }

    @Override
    public void getAccountNet(Account request,
        StreamObserver<AccountNetMessage> responseObserver) {
      logger.debug("WalletApi.getAccountNet received request");
      ByteString fromBs = request.getAddress();

      if (fromBs != null) {
        responseObserver.onNext(wallet.getAccountNet(fromBs));
      } else {
        responseObserver.onNext(null);
      }
      responseObserver.onCompleted();
      logger.debug("gWalletApi.etAccountNet response completed");
    }

    @Override
    public void getAccountResource(Account request,
        StreamObserver<AccountResourceMessage> responseObserver) {
      logger.debug("WalletApi.getAccountResource received request");
      ByteString fromBs = request.getAddress();

      if (fromBs != null) {
        responseObserver.onNext(wallet.getAccountResource(fromBs));
      } else {
        responseObserver.onNext(null);
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.getAccountResource response completed");
    }

    @Override
    public void getAssetIssueByName(BytesMessage request,
        StreamObserver<AssetIssueContract> responseObserver) {
      logger.debug("WalletApi.getAssetIssueByName received request");
      ByteString assetName = request.getValue();
      if (assetName != null) {
        try {
          responseObserver.onNext(wallet.getAssetIssueByName(assetName));
        } catch (NonUniqueObjectException e) {
          responseObserver.onNext(null);
          logger.debug("FullNode NonUniqueObjectException: {}", e.getMessage());
        }
      } else {
        responseObserver.onNext(null);
      }
      responseObserver.onCompleted();
      logger.debug("gWalletApi.etAssetIssueByName response completed");
    }

    @Override
    public void getAssetIssueListByName(BytesMessage request,
        StreamObserver<AssetIssueList> responseObserver) {
      logger.debug("WalletApi.getAssetIssueListByName received request");
      ByteString assetName = request.getValue();

      if (assetName != null) {
        responseObserver.onNext(wallet.getAssetIssueListByName(assetName));
      } else {
        responseObserver.onNext(null);
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.getAssetIssueListByName response completed");
    }

    @Override
    public void getAssetIssueById(BytesMessage request,
        StreamObserver<AssetIssueContract> responseObserver) {
      logger.debug("WalletApi.getAssetIssueById received request");
      ByteString assetId = request.getValue();

      if (assetId != null) {
        responseObserver.onNext(wallet.getAssetIssueById(assetId.toStringUtf8()));
      } else {
        responseObserver.onNext(null);
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.getAssetIssueById response completed");
    }

    @Override
    public void getBlockById(BytesMessage request, StreamObserver<Block> responseObserver) {
      logger.debug("WalletApi.getBlockById received request");
      ByteString blockId = request.getValue();

      if (Objects.nonNull(blockId)) {
        responseObserver.onNext(wallet.getBlockById(blockId));
      } else {
        responseObserver.onNext(null);
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.getBlockById response completed");
    }

    @Override
    public void getProposalById(BytesMessage request,
        StreamObserver<Proposal> responseObserver) {
      logger.debug("WalletApi.getProposalById received request");
      ByteString proposalId = request.getValue();

      if (Objects.nonNull(proposalId)) {
        responseObserver.onNext(wallet.getProposalById(proposalId));
      } else {
        responseObserver.onNext(null);
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.getProposalById response completed");
    }

    @Override
    public void getExchangeById(BytesMessage request,
        StreamObserver<Exchange> responseObserver) {
      logger.debug("WalletApi.getExchangeById received request");
      ByteString exchangeId = request.getValue();

      if (Objects.nonNull(exchangeId)) {
        responseObserver.onNext(wallet.getExchangeById(exchangeId));
      } else {
        responseObserver.onNext(null);
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.getExchangeById response completed");
    }

    @Override
    public void getBlockByLimitNext(BlockLimit request,
        StreamObserver<BlockList> responseObserver) {
      logger.debug("WalletApi.getBlockByLimitNext received request");
      long startNum = request.getStartNum();
      long endNum = request.getEndNum();

      if (endNum > 0 && endNum > startNum && endNum - startNum <= BLOCK_LIMIT_NUM) {
        responseObserver.onNext(wallet.getBlocksByLimitNext(startNum, endNum - startNum));
      } else {
        responseObserver.onNext(null);
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.getBlockByLimitNext response completed");
    }

    @Override
    public void getBlockByLimitNext2(BlockLimit request,
        StreamObserver<BlockListExtention> responseObserver) {
      logger.debug("WalletApi.getBlockByLimitNext2 received request");
      long startNum = request.getStartNum();
      long endNum = request.getEndNum();

      if (endNum > 0 && endNum > startNum && endNum - startNum <= BLOCK_LIMIT_NUM) {
        responseObserver
            .onNext(blockList2Extention(wallet.getBlocksByLimitNext(startNum, endNum - startNum)));
      } else {
        responseObserver.onNext(null);
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.getBlockByLimitNext2 response completed");
    }

    @Override
    public void getBlockByLatestNum(NumberMessage request,
        StreamObserver<BlockList> responseObserver) {
      logger.debug("WalletApi.getBlockByLatestNum received request");
      long getNum = request.getNum();

      if (getNum > 0 && getNum < BLOCK_LIMIT_NUM) {
        responseObserver.onNext(wallet.getBlockByLatestNum(getNum));
      } else {
        responseObserver.onNext(null);
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.getBlockByLatestNum response completed");
    }

    @Override
    public void getBlockByLatestNum2(NumberMessage request,
        StreamObserver<BlockListExtention> responseObserver) {
      logger.debug("WalletApi.getBlockByLatestNum2 received request");
      long getNum = request.getNum();

      if (getNum > 0 && getNum < BLOCK_LIMIT_NUM) {
        responseObserver.onNext(blockList2Extention(wallet.getBlockByLatestNum(getNum)));
      } else {
        responseObserver.onNext(null);
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.getBlockByLatestNum2 response completed");
    }

    @Override
    public void getTransactionById(BytesMessage request,
        StreamObserver<Transaction> responseObserver) {
      logger.debug("WalletApi.getTransactionById received request");
      ByteString transactionId = request.getValue();

      if (Objects.nonNull(transactionId)) {
        responseObserver.onNext(wallet.getTransactionById(transactionId));
      } else {
        responseObserver.onNext(null);
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.getTransactionById response completed");
    }

    @Override
    public void deployContract(CreateSmartContract request,
        StreamObserver<TransactionExtention> responseObserver) {
      createTransactionExtention(request, ContractType.CreateSmartContract, responseObserver);
    }

    @Override
    public void totalTransaction(EmptyMessage request,
        StreamObserver<NumberMessage> responseObserver) {
      logger.debug("WalletApi.totalTransaction received request");
      responseObserver.onNext(wallet.totalTransaction());
      responseObserver.onCompleted();
      logger.debug("WalletApi.totalTransaction response completed");
    }

    @Override
    public void getNextMaintenanceTime(EmptyMessage request,
        StreamObserver<NumberMessage> responseObserver) {
      logger.debug("WalletApi.getNextMaintenanceTime received request");
      responseObserver.onNext(wallet.getNextMaintenanceTime());
      responseObserver.onCompleted();
      logger.debug("WalletApi.getNextMaintenanceTime response completed");
    }

    @Override
    public void getAssetIssueList(EmptyMessage request,
        StreamObserver<AssetIssueList> responseObserver) {
      logger.debug("WalletApi.getAssetIssueList received request");
      responseObserver.onNext(wallet.getAssetIssueList());
      responseObserver.onCompleted();
      logger.debug("WalletApi.getAssetIssueList response completed");
    }

    @Override
    public void triggerContract(TriggerSmartContract request,
        StreamObserver<TransactionExtention> responseObserver) {

      callContract(request, responseObserver, false);
    }

    @Override
    public void estimateEnergy(TriggerSmartContract request,
        StreamObserver<EstimateEnergyMessage> responseObserver) {
      logger.debug("WalletApi.estimateEnergy received request");
      TransactionExtention.Builder trxExtBuilder = TransactionExtention.newBuilder();
      Return.Builder retBuilder = Return.newBuilder();
      EstimateEnergyMessage.Builder estimateBuilder
          = EstimateEnergyMessage.newBuilder();

      try {
        TransactionCapsule trxCap = createTransactionCapsule(request,
            ContractType.TriggerSmartContract);
        wallet.estimateEnergy(request, trxCap, trxExtBuilder, retBuilder, estimateBuilder);
      } catch (ContractValidateException | VMIllegalException e) {
        retBuilder.setResult(false).setCode(response_code.CONTRACT_VALIDATE_ERROR)
            .setMessage(ByteString.copyFromUtf8(Wallet
                .CONTRACT_VALIDATE_ERROR + e.getMessage()));
        logger.warn(CONTRACT_VALIDATE_EXCEPTION, e.getMessage());
      } catch (RuntimeException e) {
        retBuilder.setResult(false).setCode(response_code.CONTRACT_EXE_ERROR)
            .setMessage(ByteString.copyFromUtf8(e.getClass() + " : " + e.getMessage()));
        logger.warn("When run estimate energy in VM, have Runtime Exception: " + e.getMessage());
      } catch (Exception e) {
        retBuilder.setResult(false).setCode(response_code.OTHER_ERROR)
            .setMessage(ByteString.copyFromUtf8(e.getClass() + " : " + e.getMessage()));
        logger.warn(UNKNOWN_EXCEPTION_CAUGHT + e.getMessage(), e);
      } finally {
        estimateBuilder.setResult(retBuilder);
        responseObserver.onNext(estimateBuilder.build());
        responseObserver.onCompleted();
        logger.debug("WalletApi.estimateEnergy response completed");
      }
    }

    @Override
    public void triggerConstantContract(TriggerSmartContract request,
        StreamObserver<TransactionExtention> responseObserver) {

      callContract(request, responseObserver, true);
    }

    private void callContract(TriggerSmartContract request,
        StreamObserver<TransactionExtention> responseObserver, boolean isConstant) {
      logger.debug("WalletApi.callContract received request, isConstant: {}", isConstant);
      TransactionExtention.Builder trxExtBuilder = TransactionExtention.newBuilder();
      Return.Builder retBuilder = Return.newBuilder();
      try {
        TransactionCapsule trxCap = createTransactionCapsule(request,
            ContractType.TriggerSmartContract);
        Transaction trx;
        if (isConstant) {
          trx = wallet.triggerConstantContract(request, trxCap, trxExtBuilder, retBuilder);
        } else {
          trx = wallet.triggerContract(request, trxCap, trxExtBuilder, retBuilder);
        }
        trxExtBuilder.setTransaction(trx);
        trxExtBuilder.setTxid(trxCap.getTransactionId().getByteString());
        retBuilder.setResult(true).setCode(response_code.SUCCESS);
        trxExtBuilder.setResult(retBuilder);
      } catch (ContractValidateException | VMIllegalException e) {
        retBuilder.setResult(false).setCode(response_code.CONTRACT_VALIDATE_ERROR)
            .setMessage(ByteString.copyFromUtf8(Wallet
                .CONTRACT_VALIDATE_ERROR + e.getMessage()));
        trxExtBuilder.setResult(retBuilder);
        logger.warn(CONTRACT_VALIDATE_EXCEPTION, e.getMessage());
      } catch (RuntimeException e) {
        retBuilder.setResult(false).setCode(response_code.CONTRACT_EXE_ERROR)
            .setMessage(ByteString.copyFromUtf8(e.getClass() + " : " + e.getMessage()));
        trxExtBuilder.setResult(retBuilder);
        logger.warn("When run constant call in VM, have Runtime Exception: " + e.getMessage());
      } catch (Exception e) {
        retBuilder.setResult(false).setCode(response_code.OTHER_ERROR)
            .setMessage(ByteString.copyFromUtf8(e.getClass() + " : " + e.getMessage()));
        trxExtBuilder.setResult(retBuilder);
        logger.warn(UNKNOWN_EXCEPTION_CAUGHT + e.getMessage(), e);
      } finally {
        responseObserver.onNext(trxExtBuilder.build());
        responseObserver.onCompleted();
        logger.debug("WalletApi.callContract response completed, isConstant: {}", isConstant);
      }
    }

    public void getPaginatedAssetIssueList(PaginatedMessage request,
        StreamObserver<AssetIssueList> responseObserver) {
      logger.debug("WalletApi.getPaginatedAssetIssueList request received");
      responseObserver.onNext(wallet.getAssetIssueList(request.getOffset(), request.getLimit()));
      responseObserver.onCompleted();
      logger.debug("WalletApi.getPaginatedAssetIssueList response completed");
    }

    @Override
    public void getContract(BytesMessage request,
        StreamObserver<SmartContract> responseObserver) {
      logger.debug("WalletApi.getContract request received");
      SmartContract contract = wallet.getContract(request);
      responseObserver.onNext(contract);
      responseObserver.onCompleted();
      logger.debug("WalletApi.getContract response completed");
    }

    @Override
    public void getContractInfo(BytesMessage request,
        StreamObserver<SmartContractDataWrapper> responseObserver) {
      logger.debug("WalletApi.getContractInfo request received");
      SmartContractDataWrapper contract = wallet.getContractInfo(request);
      responseObserver.onNext(contract);
      responseObserver.onCompleted();
      logger.debug("WalletApi.getContractInfo response completed");
    }

    public void listWitnesses(EmptyMessage request,
        StreamObserver<WitnessList> responseObserver) {
      logger.debug("WalletApi.listWitnesses request received");
      responseObserver.onNext(wallet.getWitnessList());
      responseObserver.onCompleted();
      logger.debug("WalletApi.listWitnesses response completed");
    }

    @Override
    public void getPaginatedNowWitnessList(PaginatedMessage request,
        StreamObserver<WitnessList> responseObserver) {
      logger.debug("WalletApi.getPaginatedNowWitnessList request received");
      try {
        responseObserver.onNext(
            wallet.getPaginatedNowWitnessList(request.getOffset(), request.getLimit()));
      } catch (MaintenanceUnavailableException e) {
        responseObserver.onError(getRunTimeException(e));
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.getPaginatedNowWitnessList response completed");
    }

    @Override
    public void listProposals(EmptyMessage request,
        StreamObserver<ProposalList> responseObserver) {
      logger.debug("WalletApi.listProposals request received");
      responseObserver.onNext(wallet.getProposalList());
      responseObserver.onCompleted();
      logger.debug("WalletApi.listProposals response completed");
    }


    @Override
    public void getDelegatedResource(DelegatedResourceMessage request,
        StreamObserver<DelegatedResourceList> responseObserver) {
      logger.debug("WalletApi.getDelegatedResource request received");
      responseObserver
          .onNext(wallet.getDelegatedResource(request.getFromAddress(), request.getToAddress()));
      responseObserver.onCompleted();
      logger.debug("WalletApi.getDelegatedResource response completed");
    }

    @Override
    public void getDelegatedResourceV2(DelegatedResourceMessage request,
        StreamObserver<DelegatedResourceList> responseObserver) {
      logger.debug("WalletApi.getDelegatedResourceV2 request received");
      try {
        responseObserver.onNext(wallet.getDelegatedResourceV2(
                request.getFromAddress(), request.getToAddress())
        );
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.getDelegatedResourceV2 response completed");
    }

    @Override
    public void getDelegatedResourceAccountIndex(BytesMessage request,
        StreamObserver<org.tron.protos.Protocol.DelegatedResourceAccountIndex> responseObserver) {
      logger.debug("WalletApi.getDelegatedResourceAccountIndex request received");
      try {
        responseObserver
          .onNext(wallet.getDelegatedResourceAccountIndex(request.getValue()));
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.getDelegatedResourceAccountIndex response completed");
    }

    @Override
    public void getDelegatedResourceAccountIndexV2(BytesMessage request,
        StreamObserver<org.tron.protos.Protocol.DelegatedResourceAccountIndex> responseObserver) {
      logger.debug("WalletApi.getDelegatedResourceAccountIndexV2 request received");
      try {
        responseObserver
                .onNext(wallet.getDelegatedResourceAccountIndexV2(request.getValue()));
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.getDelegatedResourceAccountIndexV2 response completed");
    }

    @Override
    public void getCanDelegatedMaxSize(GrpcAPI.CanDelegatedMaxSizeRequestMessage request,
        StreamObserver<GrpcAPI.CanDelegatedMaxSizeResponseMessage> responseObserver) {
      logger.debug("WalletApi.getCanDelegatedMaxSize request received");
      try {
        responseObserver.onNext(wallet.getCanDelegatedMaxSize(
                        request.getOwnerAddress(), request.getType()));
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
      }

      responseObserver.onCompleted();
      logger.debug("WalletApi.getCanDelegatedMaxSize response completed");
    }

    @Override
    public void getAvailableUnfreezeCount(GrpcAPI.GetAvailableUnfreezeCountRequestMessage request,
         StreamObserver<GrpcAPI.GetAvailableUnfreezeCountResponseMessage> responseObserver) {
      logger.debug("WalletApi.getAvailableUnfreezeCount request received");
      try {
        responseObserver.onNext(wallet.getAvailableUnfreezeCount(
                request.getOwnerAddress()));
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
      }

      responseObserver.onCompleted();
      logger.debug("WalletApi.getAvailableUnfreezeCount response completed");
    }

    @Override
    public void getCanWithdrawUnfreezeAmount(CanWithdrawUnfreezeAmountRequestMessage request,
        StreamObserver<GrpcAPI.CanWithdrawUnfreezeAmountResponseMessage> responseObserver) {
      logger.debug("WalletApi.getCanWithdrawUnfreezeAmount request received");
      try {
        responseObserver
                .onNext(wallet.getCanWithdrawUnfreezeAmount(
                        request.getOwnerAddress(), request.getTimestamp()
        ));
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.getCanWithdrawUnfreezeAmount response completed");
    }

    @Override
    public void getBandwidthPrices(EmptyMessage request,
        StreamObserver<PricesResponseMessage> responseObserver) {
      logger.debug("WalletApi.getBandwidthPrices request received");
      try {
        responseObserver.onNext(wallet.getBandwidthPrices());
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.getBandwidthPrices response completed");
    }

    @Override
    public void getEnergyPrices(EmptyMessage request,
        StreamObserver<PricesResponseMessage> responseObserver) {
      logger.debug("WalletApi.getEnergyPrices request received");
      try {
        responseObserver.onNext(wallet.getEnergyPrices());
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.getEnergyPrices response completed");
    }

    @Override
    public void getMemoFee(EmptyMessage request,
        StreamObserver<PricesResponseMessage> responseObserver) {
      logger.debug("WalletApi.getMemoFee request received");
      try {
        responseObserver.onNext(wallet.getMemoFeePrices());
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.getMemoFee response completed");
    }

    @Override
    public void getPaginatedProposalList(PaginatedMessage request,
        StreamObserver<ProposalList> responseObserver) {
      logger.debug("WalletApi.getPaginatedProposalList request received");
      responseObserver
          .onNext(wallet.getPaginatedProposalList(request.getOffset(), request.getLimit()));
      responseObserver.onCompleted();
      logger.debug("WalletApi.getPaginatedProposalList response completed");

    }

    @Override
    public void getPaginatedExchangeList(PaginatedMessage request,
        StreamObserver<ExchangeList> responseObserver) {
      logger.debug("WalletApi.getPaginatedExchangeList request received");
      responseObserver
          .onNext(wallet.getPaginatedExchangeList(request.getOffset(), request.getLimit()));
      responseObserver.onCompleted();
      logger.debug("WalletApi.getPaginatedExchangeList response completed");

    }

    @Override
    public void listExchanges(EmptyMessage request,
        StreamObserver<ExchangeList> responseObserver) {
      logger.debug("WalletApi.listExchanges request received");
      responseObserver.onNext(wallet.getExchangeList());
      responseObserver.onCompleted();
      logger.debug("WalletApi.listExchanges response completed");
    }

    @Override
    public void getChainParameters(EmptyMessage request,
        StreamObserver<Protocol.ChainParameters> responseObserver) {
      logger.debug("WalletApi.getChainParameters request received");
      responseObserver.onNext(wallet.getChainParameters());
      responseObserver.onCompleted();
      logger.debug("WalletApi.getChainParameters response completed");
    }

    @Override
    public void getTransactionInfoById(BytesMessage request,
        StreamObserver<TransactionInfo> responseObserver) {
      logger.debug("WalletApi.getTransactionInfoById request received");
      ByteString id = request.getValue();
      if (null != id) {
        TransactionInfo reply = wallet.getTransactionInfoById(id);

        responseObserver.onNext(reply);
      } else {
        responseObserver.onNext(null);
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.getTransactionInfoById response completed");
    }

    @Override
    public void getNodeInfo(EmptyMessage request, StreamObserver<NodeInfo> responseObserver) {
      logger.debug("WalletApi.getNodeInfo request received");
      try {
        responseObserver.onNext(nodeInfoService.getNodeInfo().transferToProtoEntity());
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.getNodeInfo response completed");
    }

    @Override
    public void accountPermissionUpdate(AccountPermissionUpdateContract request,
        StreamObserver<TransactionExtention> responseObserver) {
      createTransactionExtention(request, ContractType.AccountPermissionUpdateContract,
          responseObserver);
    }

    @Override
    public void getMerkleTreeVoucherInfo(OutputPointInfo request,
        StreamObserver<IncrementalMerkleVoucherInfo> responseObserver) {
      logger.debug("WalletApi.getMerkleTreeVoucherInfo request received");
      try {
        IncrementalMerkleVoucherInfo witnessInfo = wallet
            .getMerkleTreeVoucherInfo(request);
        responseObserver.onNext(witnessInfo);
      } catch (Exception ex) {
        responseObserver.onError(getRunTimeException(ex));
        logger.debug("WalletApi.getMerkleTreeVoucherInfo response completed");
        return;
      }

      responseObserver.onCompleted();
      logger.debug("WalletApi.getMerkleTreeVoucherInfo response completed");
    }

    @Override
    public void createShieldedTransaction(PrivateParameters request,
        StreamObserver<TransactionExtention> responseObserver) {
      logger.debug("WalletApi.createShieldedTransaction request received");
      TransactionExtention.Builder trxExtBuilder = TransactionExtention.newBuilder();
      Return.Builder retBuilder = Return.newBuilder();

      try {
        TransactionCapsule trx = wallet.createShieldedTransaction(request);
        trxExtBuilder.setTransaction(trx.getInstance());
        trxExtBuilder.setTxid(trx.getTransactionId().getByteString());
        retBuilder.setResult(true).setCode(response_code.SUCCESS);
      } catch (ContractValidateException | ZksnarkException e) {
        retBuilder.setResult(false).setCode(response_code.CONTRACT_VALIDATE_ERROR)
            .setMessage(ByteString
                .copyFromUtf8(Wallet.CONTRACT_VALIDATE_ERROR + e.getMessage()));
        logger.debug(CONTRACT_VALIDATE_EXCEPTION, e.getMessage());
      } catch (Exception e) {
        retBuilder.setResult(false).setCode(response_code.OTHER_ERROR)
            .setMessage(ByteString.copyFromUtf8(e.getClass() + " : " + e.getMessage()));
        logger.info("createShieldedTransaction exception caught: " + e.getMessage());
      }

      trxExtBuilder.setResult(retBuilder);
      responseObserver.onNext(trxExtBuilder.build());
      responseObserver.onCompleted();
      logger.debug("WalletApi.createShieldedTransaction response completed");

    }

    @Override
    public void createShieldedTransactionWithoutSpendAuthSig(PrivateParametersWithoutAsk request,
        StreamObserver<TransactionExtention> responseObserver) {
      logger.debug("WalletApi.createShieldedTransactionWithoutSpendAuthSig request received");
      TransactionExtention.Builder trxExtBuilder = TransactionExtention.newBuilder();
      Return.Builder retBuilder = Return.newBuilder();

      try {
        TransactionCapsule trx = wallet.createShieldedTransactionWithoutSpendAuthSig(request);
        trxExtBuilder.setTransaction(trx.getInstance());
        trxExtBuilder.setTxid(trx.getTransactionId().getByteString());
        retBuilder.setResult(true).setCode(response_code.SUCCESS);
      } catch (ContractValidateException | ZksnarkException e) {
        retBuilder.setResult(false).setCode(response_code.CONTRACT_VALIDATE_ERROR)
            .setMessage(ByteString
                .copyFromUtf8(Wallet.CONTRACT_VALIDATE_ERROR + e.getMessage()));
        logger.debug(CONTRACT_VALIDATE_EXCEPTION, e.getMessage());
      } catch (Exception e) {
        retBuilder.setResult(false).setCode(response_code.OTHER_ERROR)
            .setMessage(ByteString.copyFromUtf8(e.getClass() + " : " + e.getMessage()));
        logger.info(
            "createShieldedTransactionWithoutSpendAuthSig exception caught: " + e.getMessage());
      }

      trxExtBuilder.setResult(retBuilder);
      responseObserver.onNext(trxExtBuilder.build());
      responseObserver.onCompleted();
      logger.debug("WalletApi.createShieldedTransactionWithoutSpendAuthSig response completed");

    }

    @Override
    public void getNewShieldedAddress(EmptyMessage request,
        StreamObserver<ShieldedAddressInfo> responseObserver) {
      logger.debug("WalletApi.getNewShieldedAddress request received");
      try {
        responseObserver.onNext(wallet.getNewShieldedAddress());
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
        logger.debug("WalletApi.getNewShieldedAddress response completed");
        return;
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.getNewShieldedAddress response completed");
    }

    @Override
    public void getSpendingKey(EmptyMessage request,
        StreamObserver<BytesMessage> responseObserver) {
      logger.debug("WalletApi.getSpendingKey request received");
      try {
        responseObserver.onNext(wallet.getSpendingKey());
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
        logger.debug("WalletApi.getSpendingKey response completed");
        return;
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.getSpendingKey response completed");
    }

    @Override
    public void getRcm(EmptyMessage request,
        StreamObserver<BytesMessage> responseObserver) {
      logger.debug("WalletApi.getRcm request received");
      try {
        responseObserver.onNext(wallet.getRcm());
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
        logger.debug("WalletApi.getRcm response completed");
        return;
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.getRcm response completed");
    }

    @Override
    public void getExpandedSpendingKey(BytesMessage request,
        StreamObserver<ExpandedSpendingKeyMessage> responseObserver) {
      logger.debug("WalletApi.getExpandedSpendingKey request received");
      ByteString spendingKey = request.getValue();

      try {
        ExpandedSpendingKeyMessage response = wallet.getExpandedSpendingKey(spendingKey);
        responseObserver.onNext(response);
      } catch (BadItemException | ZksnarkException e) {
        responseObserver.onError(getRunTimeException(e));
        logger.debug("WalletApi.getExpandedSpendingKey response completed");
        return;
      }

      responseObserver.onCompleted();
      logger.debug("WalletApi.getExpandedSpendingKey response completed");
    }

    @Override
    public void getAkFromAsk(BytesMessage request, StreamObserver<BytesMessage> responseObserver) {
      logger.debug("WalletApi.getAkFromAsk request received");
      ByteString ak = request.getValue();

      try {
        responseObserver.onNext(wallet.getAkFromAsk(ak));
      } catch (BadItemException | ZksnarkException e) {
        responseObserver.onError(getRunTimeException(e));
        logger.debug("WalletApi.getAkFromAsk response completed");
        return;
      }

      responseObserver.onCompleted();
      logger.debug("WalletApi.getAkFromAsk response completed");
    }

    @Override
    public void getNkFromNsk(BytesMessage request, StreamObserver<BytesMessage> responseObserver) {
      logger.debug("WalletApi.getNkFromNsk request received");
      ByteString nk = request.getValue();

      try {
        responseObserver.onNext(wallet.getNkFromNsk(nk));
      } catch (BadItemException | ZksnarkException e) {
        responseObserver.onError(getRunTimeException(e));
        logger.debug("WalletApi.getNkFromNsk response completed");
        return;
      }

      responseObserver.onCompleted();
      logger.debug("WalletApi.getNkFromNsk response completed");
    }

    @Override
    public void getIncomingViewingKey(ViewingKeyMessage request,
        StreamObserver<IncomingViewingKeyMessage> responseObserver) {
      logger.debug("WalletApi.getIncomingViewingKey request received");
      ByteString ak = request.getAk();
      ByteString nk = request.getNk();

      try {
        responseObserver.onNext(wallet.getIncomingViewingKey(ak.toByteArray(), nk.toByteArray()));
      } catch (ZksnarkException e) {
        responseObserver.onError(getRunTimeException(e));
        logger.debug("WalletApi.getIncomingViewingKey response completed");
        return;
      }

      responseObserver.onCompleted();
      logger.debug("WalletApi.getIncomingViewingKey response completed");
    }

    @Override
    public void getDiversifier(EmptyMessage request,
        StreamObserver<DiversifierMessage> responseObserver) {
      logger.debug("WalletApi.getDiversifier request received");
      try {
        DiversifierMessage d = wallet.getDiversifier();
        responseObserver.onNext(d);
      } catch (ZksnarkException e) {
        responseObserver.onError(getRunTimeException(e));
        logger.debug("WalletApi.getDiversifier response completed");
        return;
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.getDiversifier response completed");
    }


    @Override
    public void getZenPaymentAddress(IncomingViewingKeyDiversifierMessage request,
        StreamObserver<PaymentAddressMessage> responseObserver) {
      logger.debug("WalletApi.getZenPaymentAddress request received");
      IncomingViewingKeyMessage ivk = request.getIvk();
      DiversifierMessage d = request.getD();

      try {
        PaymentAddressMessage saplingPaymentAddressMessage =
            wallet.getPaymentAddress(new IncomingViewingKey(ivk.getIvk().toByteArray()),
                new DiversifierT(d.getD().toByteArray()));

        responseObserver.onNext(saplingPaymentAddressMessage);
      } catch (BadItemException | ZksnarkException e) {
        responseObserver.onError(getRunTimeException(e));
        logger.debug("WalletApi.getZenPaymentAddress response completed");
        return;
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.getZenPaymentAddress response completed");

    }

    @Override
    public void scanNoteByIvk(GrpcAPI.IvkDecryptParameters request,
        StreamObserver<GrpcAPI.DecryptNotes> responseObserver) {
      logger.debug("WalletApi.scanNoteByIvk request received");
      long startNum = request.getStartBlockIndex();
      long endNum = request.getEndBlockIndex();

      try {
        DecryptNotes decryptNotes = wallet
            .scanNoteByIvk(startNum, endNum, request.getIvk().toByteArray());
        responseObserver.onNext(decryptNotes);
      } catch (BadItemException | ZksnarkException e) {
        responseObserver.onError(getRunTimeException(e));
        logger.debug("WalletApi.scanNoteByIvk response completed");
        return;
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.scanNoteByIvk response completed");

    }

    @Override
    public void scanAndMarkNoteByIvk(GrpcAPI.IvkDecryptAndMarkParameters request,
        StreamObserver<GrpcAPI.DecryptNotesMarked> responseObserver) {
      logger.debug("WalletApi.scanAndMarkNoteByIvk request received");
      long startNum = request.getStartBlockIndex();
      long endNum = request.getEndBlockIndex();

      try {
        DecryptNotesMarked decryptNotes = wallet.scanAndMarkNoteByIvk(startNum, endNum,
            request.getIvk().toByteArray(),
            request.getAk().toByteArray(),
            request.getNk().toByteArray());
        responseObserver.onNext(decryptNotes);
      } catch (BadItemException | ZksnarkException | InvalidProtocolBufferException
          | ItemNotFoundException e) {
        responseObserver.onError(getRunTimeException(e));
        logger.debug("WalletApi.scanAndMarkNoteByIvk response completed");
        return;
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.scanAndMarkNoteByIvk response completed");
    }

    @Override
    public void scanNoteByOvk(GrpcAPI.OvkDecryptParameters request,
        StreamObserver<GrpcAPI.DecryptNotes> responseObserver) {
      logger.debug("WalletApi.scanNoteByOvk request received");
      long startNum = request.getStartBlockIndex();
      long endNum = request.getEndBlockIndex();

      try {
        DecryptNotes decryptNotes = wallet
            .scanNoteByOvk(startNum, endNum, request.getOvk().toByteArray());
        responseObserver.onNext(decryptNotes);
      } catch (BadItemException | ZksnarkException e) {
        responseObserver.onError(getRunTimeException(e));
        logger.debug("WalletApi.scanNoteByOvk response completed");
        return;
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.scanNoteByOvk response completed");
    }

    @Override
    public void isSpend(NoteParameters request, StreamObserver<SpendResult> responseObserver) {
      logger.debug("WalletApi.isSpend request received");
      try {
        responseObserver.onNext(wallet.isSpend(request));
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
        logger.debug("WalletApi.isSpend response completed");
        return;
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.isSpend response completed");
    }

    @Override
    public void createShieldNullifier(GrpcAPI.NfParameters request,
        StreamObserver<GrpcAPI.BytesMessage> responseObserver) {
      logger.debug("WalletApi.createShieldNullifier request received");
      try {
        BytesMessage nf = wallet
            .createShieldNullifier(request);
        responseObserver.onNext(nf);
      } catch (ZksnarkException e) {
        responseObserver.onError(getRunTimeException(e));
        logger.debug("WalletApi.createShieldNullifier response completed");
        return;
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.createShieldNullifier response completed");
    }

    @Override
    public void createSpendAuthSig(SpendAuthSigParameters request,
        StreamObserver<GrpcAPI.BytesMessage> responseObserver) {
      logger.debug("WalletApi.createSpendAuthSig request received");
      try {
        BytesMessage spendAuthSig = wallet.createSpendAuthSig(request);
        responseObserver.onNext(spendAuthSig);
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
        logger.debug("WalletApi.createSpendAuthSig response completed");
        return;
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.createSpendAuthSig response completed");
    }

    @Override
    public void getShieldTransactionHash(Transaction request,
        StreamObserver<GrpcAPI.BytesMessage> responseObserver) {
      logger.debug("WalletApi.getShieldTransactionHash request received");
      try {
        BytesMessage transactionHash = wallet.getShieldTransactionHash(request);
        responseObserver.onNext(transactionHash);
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
        logger.debug("WalletApi.getShieldTransactionHash response completed");
        return;
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.getShieldTransactionHash response completed");
    }

    @Override
    public void createShieldedContractParameters(
        PrivateShieldedTRC20Parameters request,
        StreamObserver<org.tron.api.GrpcAPI.ShieldedTRC20Parameters> responseObserver) {
      logger.debug("WalletApi.createShieldedContractParameters request received");
      try {
        ShieldedTRC20Parameters shieldedTRC20Parameters = wallet
            .createShieldedContractParameters(request);
        responseObserver.onNext(shieldedTRC20Parameters);
      } catch (ZksnarkException | ContractValidateException | ContractExeException e) {
        responseObserver.onError(getRunTimeException(e));
        logger.info("createShieldedContractParameters: {}", e.getMessage());
        logger.debug("WalletApi.createShieldedContractParameters response completed");
        return;
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
        logger.error("createShieldedContractParameters: ", e);
        logger.debug("WalletApi.createShieldedContractParameters response completed");
        return;
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.createShieldedContractParameters response completed");
    }

    @Override
    public void createShieldedContractParametersWithoutAsk(
        PrivateShieldedTRC20ParametersWithoutAsk request,
        StreamObserver<org.tron.api.GrpcAPI.ShieldedTRC20Parameters> responseObserver) {
      logger.debug("WalletApi.createShieldedContractParametersWithoutAsk request received");
      try {
        ShieldedTRC20Parameters shieldedTRC20Parameters = wallet
            .createShieldedContractParametersWithoutAsk(request);
        responseObserver.onNext(shieldedTRC20Parameters);
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
        logger
            .info("createShieldedContractParametersWithoutAsk exception caught: " + e.getMessage());
        logger.debug("WalletApi.createShieldedContractParametersWithoutAsk response completed");
        return;
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.createShieldedContractParametersWithoutAsk response completed");
    }

    @Override
    public void scanShieldedTRC20NotesByIvk(
        IvkDecryptTRC20Parameters request,
        StreamObserver<org.tron.api.GrpcAPI.DecryptNotesTRC20> responseObserver) {
      logger.debug("WalletApi.scanShieldedTRC20NotesByIvk request received");
      long startNum = request.getStartBlockIndex();
      long endNum = request.getEndBlockIndex();
      try {
        DecryptNotesTRC20 decryptNotes = wallet.scanShieldedTRC20NotesByIvk(startNum, endNum,
            request.getShieldedTRC20ContractAddress().toByteArray(),
            request.getIvk().toByteArray(),
            request.getAk().toByteArray(),
            request.getNk().toByteArray(),
            request.getEventsList());
        responseObserver.onNext(decryptNotes);
      } catch (BadItemException | ZksnarkException e) {
        responseObserver.onError(getRunTimeException(e));
        logger.info("scanShieldedTRC20NotesByIvk: {}", e.getMessage());
        logger.debug("WalletApi.scanShieldedTRC20NotesByIvk response completed");
        return;
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
        logger.error("scanShieldedTRC20NotesByIvk:", e);
        logger.debug("WalletApi.scanShieldedTRC20NotesByIvk response completed");
        return;
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.scanShieldedTRC20NotesByIvk response completed");
    }

    @Override
    public void scanShieldedTRC20NotesByOvk(
        OvkDecryptTRC20Parameters request,
        StreamObserver<org.tron.api.GrpcAPI.DecryptNotesTRC20> responseObserver) {
      logger.debug("WalletApi.scanShieldedTRC20NotesByOvk request received");
      long startNum = request.getStartBlockIndex();
      long endNum = request.getEndBlockIndex();
      try {
        DecryptNotesTRC20 decryptNotes = wallet.scanShieldedTRC20NotesByOvk(startNum, endNum,
            request.getOvk().toByteArray(),
            request.getShieldedTRC20ContractAddress().toByteArray(),
            request.getEventsList());
        responseObserver.onNext(decryptNotes);
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
        logger.info("scanShieldedTRC20NotesByOvk exception caught: " + e.getMessage());
        logger.debug("WalletApi.scanShieldedTRC20NotesByOvk response completed");
        return;
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.scanShieldedTRC20NotesByOvk response completed");
    }

    @Override
    public void isShieldedTRC20ContractNoteSpent(NfTRC20Parameters request,
        StreamObserver<GrpcAPI.NullifierResult> responseObserver) {
      logger.debug("WalletApi.isShieldedTRC20ContractNoteSpent request received");
      try {
        GrpcAPI.NullifierResult nf = wallet
            .isShieldedTRC20ContractNoteSpent(request);
        responseObserver.onNext(nf);
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
        logger.debug("WalletApi.isShieldedTRC20ContractNoteSpent response completed");
        return;
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.isShieldedTRC20ContractNoteSpent response completed");
    }

    @Override
    public void getTriggerInputForShieldedTRC20Contract(
        ShieldedTRC20TriggerContractParameters request,
        StreamObserver<org.tron.api.GrpcAPI.BytesMessage> responseObserver) {
      logger.debug("WalletApi.getTriggerInputForShieldedTRC20Contract request received");
      try {
        responseObserver.onNext(wallet.getTriggerInputForShieldedTRC20Contract(request));
      } catch (Exception e) {
        responseObserver.onError(e);
        logger.debug("WalletApi.getTriggerInputForShieldedTRC20Contract response completed");
        return;
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.getTriggerInputForShieldedTRC20Contract response completed");
    }

    @Override
    public void getRewardInfo(BytesMessage request,
        StreamObserver<NumberMessage> responseObserver) {
      getRewardInfoCommon(request, responseObserver);
    }

    @Override
    public void getBrokerageInfo(BytesMessage request,
        StreamObserver<NumberMessage> responseObserver) {
      getBrokerageInfoCommon(request, responseObserver);
    }

    @Override
    public void getBurnTrx(EmptyMessage request, StreamObserver<NumberMessage> responseObserver) {
      getBurnTrxCommon(request, responseObserver);
    }

    @Override
    public void updateBrokerage(UpdateBrokerageContract request,
        StreamObserver<TransactionExtention> responseObserver) {
      createTransactionExtention(request, ContractType.UpdateBrokerageContract,
          responseObserver);
    }

    @Override
    public void createCommonTransaction(Transaction request,
        StreamObserver<TransactionExtention> responseObserver) {
      Transaction.Contract contract = request.getRawData().getContract(0);
      createTransactionExtention(contract.getParameter(), contract.getType(),
          responseObserver);
    }

    @Override
    public void getTransactionInfoByBlockNum(NumberMessage request,
        StreamObserver<TransactionInfoList> responseObserver) {
      logger.debug("WalletApi.getTransactionInfoByBlockNum request received");
      try {
        responseObserver.onNext(wallet.getTransactionInfoByBlockNum(request.getNum()));
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
      }

      responseObserver.onCompleted();
      logger.debug("WalletApi.getTransactionInfoByBlockNum response completed");
    }

    @Override
    public void marketSellAsset(MarketSellAssetContract request,
        StreamObserver<TransactionExtention> responseObserver) {
      createTransactionExtention(request, ContractType.MarketSellAssetContract,
          responseObserver);
    }

    @Override
    public void marketCancelOrder(MarketCancelOrderContract request,
        StreamObserver<TransactionExtention> responseObserver) {
      createTransactionExtention(request, ContractType.MarketCancelOrderContract, responseObserver);
    }

    @Override
    public void getMarketOrderByAccount(BytesMessage request,
        StreamObserver<MarketOrderList> responseObserver) {
      logger.debug("WalletApi.getMarketOrderByAccount request received");
      try {
        ByteString address = request.getValue();

        MarketOrderList marketOrderList = wallet
            .getMarketOrderByAccount(address);
        responseObserver.onNext(marketOrderList);
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.getMarketOrderByAccount response completed");
    }

    @Override
    public void getMarketOrderById(BytesMessage request,
        StreamObserver<MarketOrder> responseObserver) {
      logger.debug("WalletApi.getMarketOrderById request received");
      try {
        ByteString address = request.getValue();

        MarketOrder marketOrder = wallet
            .getMarketOrderById(address);
        responseObserver.onNext(marketOrder);
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.getMarketOrderById response completed");
    }

    @Override
    public void getMarketPriceByPair(MarketOrderPair request,
        StreamObserver<MarketPriceList> responseObserver) {
      logger.debug("WalletApi.getMarketPriceByPair request received");
      try {
        MarketPriceList marketPriceList = wallet
            .getMarketPriceByPair(request.getSellTokenId().toByteArray(),
                request.getBuyTokenId().toByteArray());
        responseObserver.onNext(marketPriceList);
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.getMarketPriceByPair response completed");
    }

    @Override
    public void getMarketOrderListByPair(org.tron.protos.Protocol.MarketOrderPair request,
        StreamObserver<MarketOrderList> responseObserver) {
      logger.debug("WalletApi.getMarketOrderListByPair request received");
      try {
        MarketOrderList orderPairList = wallet
            .getMarketOrderListByPair(request.getSellTokenId().toByteArray(),
                request.getBuyTokenId().toByteArray());
        responseObserver.onNext(orderPairList);
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.getMarketOrderListByPair response completed");
    }

    @Override
    public void getMarketPairList(EmptyMessage request,
        StreamObserver<MarketOrderPairList> responseObserver) {
      logger.debug("WalletApi.getMarketPairList request received");
      try {
        MarketOrderPairList pairList = wallet.getMarketPairList();
        responseObserver.onNext(pairList);
      } catch (Exception e) {
        responseObserver.onError(getRunTimeException(e));
      }
      responseObserver.onCompleted();
      logger.debug("WalletApi.getMarketPairList response completed");
    }

    @Override
    public void getTransactionFromPending(BytesMessage request,
        StreamObserver<Transaction> responseObserver) {
      getTransactionFromPendingCommon(request, responseObserver);
    }

    @Override
    public void getTransactionListFromPending(EmptyMessage request,
        StreamObserver<TransactionIdList> responseObserver) {
      getTransactionListFromPendingCommon(request, responseObserver);
    }

    @Override
    public void getPendingSize(EmptyMessage request,
        StreamObserver<NumberMessage> responseObserver) {
      getPendingSizeCommon(request, responseObserver);
    }


    @Override
    public void getBlock(GrpcAPI.BlockReq  request,
        StreamObserver<BlockExtention> responseObserver) {
      getBlockCommon(request, responseObserver);
    }
  }

  public class MonitorApi extends MonitorGrpc.MonitorImplBase {

    @Override
    public void getStatsInfo(EmptyMessage request,
        StreamObserver<Protocol.MetricsInfo> responseObserver) {
      logger.debug("MonitorApi.getStatsInfo request received");
      responseObserver.onNext(metricsApiService.getMetricProtoInfo());
      responseObserver.onCompleted();
      logger.debug("MonitorApi.getStatsInfo response completed");
    }
  }

  public void getRewardInfoCommon(BytesMessage request,
      StreamObserver<NumberMessage> responseObserver) {
    logger.debug("getRewardInfo request received");
    try {
      long value = dbManager.getMortgageService().queryReward(request.getValue().toByteArray());
      NumberMessage.Builder builder = NumberMessage.newBuilder();
      builder.setNum(value);
      responseObserver.onNext(builder.build());
    } catch (Exception e) {
      responseObserver.onError(e);
    }
    responseObserver.onCompleted();
    logger.debug("getRewardInfo response completed");
  }

  public void getBurnTrxCommon(EmptyMessage request,
      StreamObserver<NumberMessage> responseObserver) {
    logger.debug("getBurnTrx request received");
    try {
      long value = dbManager.getDynamicPropertiesStore().getBurnTrxAmount();
      NumberMessage.Builder builder = NumberMessage.newBuilder();
      builder.setNum(value);
      responseObserver.onNext(builder.build());
    } catch (Exception e) {
      responseObserver.onError(e);
    }
    responseObserver.onCompleted();
    logger.debug("getBurnTrx response completed");
  }

  public void getBrokerageInfoCommon(BytesMessage request,
      StreamObserver<NumberMessage> responseObserver) {
    logger.debug("getBrokerageInfo request received");
    try {
      long cycle = dbManager.getDynamicPropertiesStore().getCurrentCycleNumber();
      long value = dbManager.getDelegationStore()
          .getBrokerage(cycle, request.getValue().toByteArray());
      NumberMessage.Builder builder = NumberMessage.newBuilder();
      builder.setNum(value);
      responseObserver.onNext(builder.build());
    } catch (Exception e) {
      responseObserver.onError(e);
    }
    responseObserver.onCompleted();
    logger.debug("getBrokerageInfo response completed");
  }

  public void getTransactionCountByBlockNumCommon(NumberMessage request,
      StreamObserver<NumberMessage> responseObserver) {
    logger.debug("getTransactionCountByBlockNum request received: {}", request.getNum());
    NumberMessage.Builder builder = NumberMessage.newBuilder();
    try {
      Block block = chainBaseManager.getBlockByNum(request.getNum()).getInstance();
      builder.setNum(block.getTransactionsCount());
    } catch (StoreException e) {
      logger.error(e.getMessage());
      builder.setNum(-1);
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
    logger.debug("getTransactionCountByBlockNum response completed");
  }

  public void getTransactionFromPendingCommon(BytesMessage request,
      StreamObserver<Transaction> responseObserver) {
    logger.debug("getTransactionFromPending request received");
    try {
      String txId = ByteArray.toHexString(request.getValue().toByteArray());
      TransactionCapsule transactionCapsule = dbManager.getTxFromPending(txId);
      responseObserver.onNext(transactionCapsule == null ? null : transactionCapsule.getInstance());
    } catch (Exception e) {
      responseObserver.onError(e);
    }
    responseObserver.onCompleted();
    logger.debug("getTransactionFromPending response completed");
  }

  public void getTransactionListFromPendingCommon(EmptyMessage request,
      StreamObserver<TransactionIdList> responseObserver) {
    logger.debug("getTransactionListFromPending request received");
    try {
      TransactionIdList.Builder builder = TransactionIdList.newBuilder();
      builder.addAllTxId(dbManager.getTxListFromPending());
      responseObserver.onNext(builder.build());
    } catch (Exception e) {
      responseObserver.onError(e);
    }
    responseObserver.onCompleted();
    logger.debug("getTransactionListFromPending response completed");
  }

  public void getPendingSizeCommon(EmptyMessage request,
      StreamObserver<NumberMessage> responseObserver) {
    logger.info("getPendingSize request received");
    try {
      NumberMessage.Builder builder = NumberMessage.newBuilder();
      builder.setNum(dbManager.getPendingSize());
      responseObserver.onNext(builder.build());
    } catch (Exception e) {
      responseObserver.onError(e);
    }
    responseObserver.onCompleted();
    logger.info("getPendingSize response completed");
  }

  public void getBlockCommon(GrpcAPI.BlockReq request,
      StreamObserver<BlockExtention> responseObserver) {
    logger.info("getBlock request received");
    try {
      responseObserver.onNext(block2Extention(wallet.getBlock(request)));
    } catch (Exception e) {
      if (e instanceof IllegalArgumentException) {
        responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage())
            .withCause(e).asRuntimeException());
      } else {
        responseObserver.onError(getRunTimeException(e));
      }
    }
    responseObserver.onCompleted();
    logger.info("getBlock response completed");
  }

}
