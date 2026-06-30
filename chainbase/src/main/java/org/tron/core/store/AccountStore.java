package org.tron.core.store;

import com.google.protobuf.ByteString;
import com.typesafe.config.ConfigObject;
import org.apache.commons.lang3.ArrayUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.utils.Commons;
import org.tron.core.archive.capture.ArchiveCaptureHolder;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.db.TronStoreWithRevoking;
import org.tron.core.db.accountstate.AccountStateCallBackUtils;
import org.tron.core.exception.TronError;
import org.tron.protos.contract.BalanceContract.TransactionBalanceTrace;
import org.tron.protos.contract.BalanceContract.TransactionBalanceTrace.Operation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

@Component
public class AccountStore extends TronStoreWithRevoking<AccountCapsule> {

  private static String ACCOUNT_BLACKHOLE = "Blackhole";

  private static Map<String, byte[]> assertsAddress = new HashMap<>(); // key = name , value = address

  @Autowired
  private AccountStateCallBackUtils accountStateCallBackUtils;

  @Autowired
  private BalanceTraceStore balanceTraceStore;

  @Autowired
  private AccountTraceStore accountTraceStore;

  @Autowired
  private DynamicPropertiesStore dynamicPropertiesStore;

  @Autowired
  private AccountAssetStore accountAssetStore;

  @Autowired
  private AccountStore(@Value("account") String dbName) {
    super(dbName);
  }

  public static void setAccount(com.typesafe.config.Config config) {
    List list = config.getObjectList("genesis.block.assets");
    for (int i = 0; i < list.size(); i++) {
      ConfigObject obj = (ConfigObject) list.get(i);
      String accountName = obj.get("accountName").unwrapped().toString();
      byte[] address = Commons.decodeFromBase58Check(obj.get("address").unwrapped().toString());
      assertsAddress.put(accountName, address);
    }
    if (assertsAddress.get(ACCOUNT_BLACKHOLE) == null) {
      throw new TronError("Account[Blackhole] is not configured.", TronError.ErrCode.GENESIS_BLOCK_INIT);
    }
  }

  @Override
  public AccountCapsule get(byte[] key) {
    byte[] value = revokingDB.getUnchecked(key);
    return ArrayUtils.isEmpty(value) ? null : new AccountCapsule(value);
  }

  @Override
  public void put(byte[] key, AccountCapsule item) {
    if (CommonParameter.getInstance().isHistoryBalanceLookup()) {
      AccountCapsule old = super.getUnchecked(key);
      if (old == null) {
        if (item.getBalance() != 0) {
          recordBalance(item, item.getBalance());
          BlockCapsule.BlockId blockId = balanceTraceStore.getCurrentBlockId();
          if (blockId != null) {
            accountTraceStore.recordBalanceWithBlock(key, blockId.getNum(), item.getBalance());
          }
        }
      } else if (old.getBalance() != item.getBalance()) {
        recordBalance(item, item.getBalance() - old.getBalance());
        BlockCapsule.BlockId blockId = balanceTraceStore.getCurrentBlockId();
        if (blockId != null) {
          accountTraceStore.recordBalanceWithBlock(key, blockId.getNum(), item.getBalance());
        }
      }
    }
    // L4c: read the pre-put account so ACCOUNT_ASSET can value-diff assetV2 (gated to avoid the
    // extra read + serialize when archive is off).
    boolean archiveActive = ArchiveCaptureHolder.isActive();
    byte[] oldArchiveValue = archiveActive
        ? accountAssetDiffValue(revokingDB.getUnchecked(key)) : null;
    byte[] newArchiveValue = archiveActive ? accountAssetDiffValue(item.getData()) : null;
    super.put(key, item);
    accountStateCallBackUtils.accountCallBack(key, item);
    if (archiveActive) {
      ArchiveCaptureHolder.captureAccountAsset(key, oldArchiveValue, newArchiveValue);
    }
  }

  private byte[] accountAssetDiffValue(byte[] accountBytes) {
    if (accountBytes == null || accountBytes.length == 0) {
      return accountBytes;
    }
    try {
      if (!dynamicPropertiesStore.supportAllowAccountAssetOptimization()) {
        return accountBytes;
      }
      AccountCapsule account = new AccountCapsule(accountBytes);
      if (!account.getAssetOptimized()) {
        return accountBytes;
      }
      return account.getInstance().toBuilder()
          .clearAssetV2()
          .putAllAssetV2(accountAssetStore.getAllAssets(account.getInstance()))
          .build()
          .toByteArray();
    } catch (Exception e) {
      ArchiveCaptureHolder.recordFailure("account-asset importAllAsset", e);
      return accountBytes;
    }
  }

  @Override
  public void delete(byte[] key) {
    if (CommonParameter.getInstance().isHistoryBalanceLookup()) {
      AccountCapsule old = super.getUnchecked(key);
      if (old != null) {
        recordBalance(old, -old.getBalance());
      }

      BlockCapsule.BlockId blockId = balanceTraceStore.getCurrentBlockId();
      if (blockId != null) {
        accountTraceStore.recordBalanceWithBlock(key, blockId.getNum(), 0);
      }
    }
    boolean archiveActive = ArchiveCaptureHolder.isActive();
    byte[] oldArchiveValue = archiveActive
        ? accountAssetDiffValue(revokingDB.getUnchecked(key)) : null;
    super.delete(key);
    if (archiveActive) {
      ArchiveCaptureHolder.captureAccountAsset(key, oldArchiveValue, null);
    }
  }

  /**
   * Max TRX account.
   */
  public AccountCapsule getSun() {
    return getUnchecked(assertsAddress.get("Sun"));
  }

  /**
   * Min TRX account.
   */
  public AccountCapsule getBlackhole() {
    return getUnchecked(assertsAddress.get(ACCOUNT_BLACKHOLE));
  }


  public byte[] getBlackholeAddress() {
    return assertsAddress.get(ACCOUNT_BLACKHOLE);
  }

  /**
   * Get foundation account info.
   */
  public AccountCapsule getZion() {
    return getUnchecked(assertsAddress.get("Zion"));
  }


  // do somethings
  // check old balance and new balance, if equals, do nothing, then get balance trace from balancetraceStore
  private void recordBalance(AccountCapsule accountCapsule, long diff) {
    TransactionBalanceTrace transactionBalanceTrace = balanceTraceStore.getCurrentTransactionBalanceTrace();

    if (transactionBalanceTrace == null) {
      return;
    }

    long operationIdentifier;
    OptionalLong max = transactionBalanceTrace.getOperationList().stream()
        .mapToLong(Operation::getOperationIdentifier)
        .max();
    if (max.isPresent()) {
      operationIdentifier = max.getAsLong() + 1;
    } else {
      operationIdentifier = 0;
    }

    ByteString address = accountCapsule.getAddress();
    Operation operation = Operation.newBuilder()
        .setAddress(address)
        .setAmount(diff)
        .setOperationIdentifier(operationIdentifier)
        .build();
    transactionBalanceTrace = transactionBalanceTrace.toBuilder()
        .addOperation(operation)
        .build();
    balanceTraceStore.setCurrentTransactionBalanceTrace(transactionBalanceTrace);
  }

  @Override
  public void close() {
    super.close();
  }
}
