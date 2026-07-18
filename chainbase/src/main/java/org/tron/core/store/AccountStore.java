package org.tron.core.store;

import com.google.protobuf.ByteString;
import com.typesafe.config.ConfigObject;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;
import org.apache.commons.lang3.ArrayUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.utils.Commons;
import org.tron.core.archive.ArchiveMetrics;
import org.tron.core.archive.capture.ArchiveCaptureHolder;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.db.TronStoreWithRevoking;
import org.tron.core.db.accountstate.AccountStateCallBackUtils;
import org.tron.core.exception.TronError;
import org.tron.protos.Protocol.Account;
import org.tron.protos.contract.BalanceContract.TransactionBalanceTrace;
import org.tron.protos.contract.BalanceContract.TransactionBalanceTrace.Operation;

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
    if (ArrayUtils.isEmpty(value)) {
      return null;
    }
    AccountCapsule account = new AccountCapsule(value);
    if (ArchiveCaptureHolder.isCapturingCurrentTx()) {
      try {
        account.enableAssetV2ChangeTracking(key, value);
      } catch (RuntimeException e) {
        account.invalidateAssetV2ChangeTracking();
        ArchiveCaptureHolder.recordFailure("account asset tracking baseline", e);
      }
    }
    return account;
  }

  @Override
  public AccountCapsule getUnchecked(byte[] key) {
    return get(key);
  }

  @Override
  public void put(byte[] key, AccountCapsule item) {
    boolean historyBalanceLookup = CommonParameter.getInstance().isHistoryBalanceLookup();
    boolean archiveActive = ArchiveCaptureHolder.isCapturingCurrentTx();
    ArchivePreviousValue previous = null;
    AccountCapsule old = null;
    if (historyBalanceLookup && archiveActive) {
      long startedNanos = ArchiveMetrics.startTimer();
      byte[] previousBytes = revokingDB.getUnchecked(key);
      ArchiveCaptureHolder.recordPreviousValueRead(startedNanos, true);
      previous = archivePreviousValue(previousBytes);
      old = previousBytes == null ? null : new AccountCapsule(previousBytes);
    } else if (historyBalanceLookup) {
      old = super.getUnchecked(key);
    }
    if (historyBalanceLookup) {
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
    if (item == null) {
      return;
    }
    // L4c: read the pre-put account so ACCOUNT_ASSET can value-diff assetV2 (gated to avoid the
    // extra read + serialize when archive is off).
    byte[] archiveValue = archiveActive ? item.getData() : null;
    if (archiveActive && previous == null) {
      previous = readArchivePreviousValue(getDbName(), key);
    }
    boolean archivePrepared = archiveActive && previous.isAvailable();
    if (archivePrepared) {
      // Preserve the established ACCOUNT -> ACCOUNT_ASSET capture order while still reading the
      // physical asset prefix before SnapshotRoot mutates it. These records remain block-local, so
      // a later canonical write failure is discarded by the normal archive abort path.
      ArchiveCaptureHolder.capturePut(
          getDbName(), key, previous.getValue(), archiveValue);
      // SnapshotRoot may migrate/delete account-asset physical rows as part of the account write.
      // Capture the effective transition while the previous physical prefix is still visible.
      captureAccountAssetTransitions(
          key, previous.getValue(), archiveValue, item.getInstance(), item);
    }
    if (archiveActive) {
      revokingDB.put(key, archiveValue);
    } else {
      super.put(key, item);
    }
    accountStateCallBackUtils.accountCallBack(key, item);
    if (archiveActive && !ArchiveCaptureHolder.hasFailure()) {
      try {
        item.enableAssetV2ChangeTracking(key, archiveValue);
      } catch (RuntimeException e) {
        item.invalidateAssetV2ChangeTracking();
        ArchiveCaptureHolder.recordFailure("account asset tracking rebase", e);
      }
    } else {
      item.invalidateAssetV2ChangeTracking();
    }
  }

  private void captureAccountAssetTransitions(byte[] address, byte[] oldAccountBytes,
      byte[] newAccountBytes, Account newAccount) {
    captureAccountAssetTransitions(
        address, oldAccountBytes, newAccountBytes, newAccount, null);
  }

  private void captureAccountAssetTransitions(byte[] address, byte[] oldAccountBytes,
      byte[] newAccountBytes, Account newAccount, AccountCapsule trackedAccount) {
    long startedNanos = ArchiveMetrics.startTimer();
    long[] physicalRowsRead = {0L};
    boolean prefixScan = false;
    try (ArchiveCaptureHolder.AccountAssetPlanningScope scope =
        ArchiveCaptureHolder.openAccountAssetPlanning(
            address, oldAccountBytes, newAccountBytes)) {
      if (!scope.isActive()) {
        return;
      }
      boolean changesKnown = trackedAccount != null
          && trackedAccount.hasCompleteAssetV2ChangeTrackingFor(address, oldAccountBytes);
      boolean optimizationChanged = changesKnown && trackedAccount.isAssetOptimizedChanged();
      if (changesKnown && !optimizationChanged && !trackedAccount.hasModifiedAssetV2()) {
        return;
      }
      Account oldAccount = parseAccount(oldAccountBytes);
      Account plannedNewAccount = newAccount != null ? newAccount : parseAccount(newAccountBytes);
      if (!changesKnown && oldAccount != null && plannedNewAccount != null
          && oldAccount.getAssetOptimized() == plannedNewAccount.getAssetOptimized()
          && oldAccount.getAssetV2Map().equals(plannedNewAccount.getAssetV2Map())) {
        return;
      }
      boolean oldOptimized = oldAccount != null && oldAccount.getAssetOptimized();
      boolean newOptimized = plannedNewAccount != null && plannedNewAccount.getAssetOptimized();
      prefixScan = plannedNewAccount == null
          || oldOptimized != newOptimized
          || oldAccount == null && newOptimized;
      NavigableSet<String> assetIds = new TreeSet<>();
      if (prefixScan) {
        if (oldAccount != null) {
          assetIds.addAll(oldAccount.getAssetV2Map().keySet());
        }
        if (plannedNewAccount != null) {
          assetIds.addAll(plannedNewAccount.getAssetV2Map().keySet());
        }
      } else if (changesKnown) {
        trackedAccount.copyModifiedAssetV2Into(assetIds);
      } else {
        addChangedAssetIds(assetIds, oldAccount, plannedNewAccount);
      }
      if (prefixScan) {
        accountAssetStore.scanPhysicalAssets(address,
            (assetIdBytes, physicalBalance) -> {
              physicalRowsRead[0]++;
              String physicalAssetId = new String(assetIdBytes, StandardCharsets.US_ASCII);
              while (!assetIds.isEmpty()
                  && assetIds.first().compareTo(physicalAssetId) < 0) {
                captureAccountAssetTransition(
                    address, assetIds.pollFirst(), oldAccount, plannedNewAccount, 0L);
                stopIfArchiveCaptureFailed();
              }
              if (!assetIds.isEmpty() && assetIds.first().equals(physicalAssetId)) {
                assetIds.pollFirst();
              }
              captureAccountAssetTransition(
                  address, physicalAssetId, oldAccount, plannedNewAccount, physicalBalance);
              stopIfArchiveCaptureFailed();
            });
        while (!assetIds.isEmpty()) {
          captureAccountAssetTransition(
              address, assetIds.pollFirst(), oldAccount, plannedNewAccount, 0L);
          stopIfArchiveCaptureFailed();
        }
      } else {
        for (String assetId : assetIds) {
          long physicalBalance = 0L;
          if (needsPhysical(oldAccount, assetId) || needsPhysical(plannedNewAccount, assetId)) {
            physicalBalance = accountAssetStore.getBalance(
                address, assetId.getBytes(StandardCharsets.US_ASCII));
            physicalRowsRead[0]++;
          }
          captureAccountAssetTransition(
              address, assetId, oldAccount, plannedNewAccount, physicalBalance);
          if (ArchiveCaptureHolder.hasFailure()) {
            break;
          }
        }
      }
    } catch (StopAssetScanException ignored) {
      // The first capture failure is already recorded; stop the potentially huge prefix promptly.
    } catch (Exception e) {
      ArchiveCaptureHolder.recordFailure("account-asset effectiveDiff", e);
    } finally {
      ArchiveCaptureHolder.recordAccountAssetLookup(
          prefixScan, physicalRowsRead[0], startedNanos);
    }
  }

  private static void captureAccountAssetTransition(byte[] address, String assetId,
      Account oldAccount, Account newAccount, long physicalBalance) {
    long oldBalance = effectiveBalance(oldAccount, assetId, physicalBalance);
    long newBalance = effectiveBalance(newAccount, assetId, physicalBalance);
    ArchiveCaptureHolder.captureAccountAsset(address,
        assetId.getBytes(StandardCharsets.US_ASCII), oldBalance, newBalance);
  }

  private static void stopIfArchiveCaptureFailed() {
    if (ArchiveCaptureHolder.hasFailure()) {
      throw StopAssetScanException.INSTANCE;
    }
  }

  private static Account parseAccount(byte[] accountBytes) {
    return accountBytes == null || accountBytes.length == 0
        ? null : new AccountCapsule(accountBytes).getInstance();
  }

  private static boolean needsPhysical(Account account, String assetId) {
    return account != null
        && account.getAssetOptimized()
        && !account.getAssetV2Map().containsKey(assetId);
  }

  private static void addChangedAssetIds(Set<String> assetIds,
      Account oldAccount, Account newAccount) {
    Map<String, Long> oldAssets = oldAccount == null
        ? java.util.Collections.emptyMap() : oldAccount.getAssetV2Map();
    Map<String, Long> newAssets = newAccount == null
        ? java.util.Collections.emptyMap() : newAccount.getAssetV2Map();
    for (Map.Entry<String, Long> entry : oldAssets.entrySet()) {
      if (!newAssets.containsKey(entry.getKey())
          || !entry.getValue().equals(newAssets.get(entry.getKey()))) {
        assetIds.add(entry.getKey());
      }
    }
    for (String assetId : newAssets.keySet()) {
      if (!oldAssets.containsKey(assetId)) {
        assetIds.add(assetId);
      }
    }
  }

  private static long effectiveBalance(Account account, String assetId, long physicalBalance) {
    if (account == null) {
      return 0L;
    }
    if (account.getAssetV2Map().containsKey(assetId)) {
      return account.getAssetV2Map().get(assetId);
    }
    return account.getAssetOptimized() ? physicalBalance : 0L;
  }

  private static final class StopAssetScanException extends RuntimeException {

    private static final StopAssetScanException INSTANCE = new StopAssetScanException();

    private StopAssetScanException() {
      super(null, null, false, false);
    }
  }

  @Override
  public void delete(byte[] key) {
    boolean historyBalanceLookup = CommonParameter.getInstance().isHistoryBalanceLookup();
    boolean archiveActive = ArchiveCaptureHolder.isCapturingCurrentTx();
    ArchivePreviousValue previous = null;
    AccountCapsule old = null;
    if (historyBalanceLookup && archiveActive) {
      long startedNanos = ArchiveMetrics.startTimer();
      byte[] previousBytes = revokingDB.getUnchecked(key);
      ArchiveCaptureHolder.recordPreviousValueRead(startedNanos, true);
      previous = archivePreviousValue(previousBytes);
      old = previousBytes == null ? null : new AccountCapsule(previousBytes);
    } else if (historyBalanceLookup) {
      old = super.getUnchecked(key);
    }
    if (historyBalanceLookup) {
      if (old != null) {
        recordBalance(old, -old.getBalance());
      }

      BlockCapsule.BlockId blockId = balanceTraceStore.getCurrentBlockId();
      if (blockId != null) {
        accountTraceStore.recordBalanceWithBlock(key, blockId.getNum(), 0);
      }
    }
    if (archiveActive && previous == null) {
      previous = readArchivePreviousValue(getDbName(), key);
    }
    boolean archivePrepared = archiveActive && previous.isAvailable();
    if (archivePrepared) {
      ArchiveCaptureHolder.captureDelete(getDbName(), key, previous.getValue());
      // SnapshotRoot.remove deletes optimized physical assets, so diff before canonical mutation.
      captureAccountAssetTransitions(key, previous.getValue(), null, null);
    }
    if (archiveActive) {
      revokingDB.delete(key);
    } else {
      super.delete(key);
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
