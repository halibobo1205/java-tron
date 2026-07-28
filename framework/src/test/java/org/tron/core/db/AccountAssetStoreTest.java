package org.tron.core.db;

import com.google.common.primitives.Bytes;
import com.google.common.primitives.Longs;
import com.google.protobuf.ByteString;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.Resource;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.tron.common.BaseTest;
import org.tron.common.TestConstants;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.Wallet;
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveSource;
import org.tron.core.archive.DefaultArchiveService;
import org.tron.core.archive.capture.ArchiveCaptureHolder;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.temporal.InMemoryArchiveTemporalStore;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.AssetIssueCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.config.args.Args;
import org.tron.core.store.AccountAssetStore;
import org.tron.core.store.AccountStore;
import org.tron.protos.Protocol;
import org.tron.protos.contract.AssetIssueContractOuterClass;

public class AccountAssetStoreTest extends BaseTest {

  private static final byte[] ASSET_KEY = "20000".getBytes();
  private static AccountCapsule ownerCapsule;

  private static String OWNER_ADDRESS = Wallet.getAddressPreFixString()
          + "abd4b9367799eaa3197fecb144eb71de1e049abc";
  private static final long TOTAL_SUPPLY = 1000_000_000L;
  private static final int TRX_NUM = 10;
  private static final int NUM = 1;
  private static final long START_TIME = 1;
  private static final long END_TIME = 2;
  private static final int VOTE_SCORE = 2;
  private static final String DESCRIPTION = "TRX";
  private static final String URL = "https://tron.network";

  @Resource
  private AccountAssetStore accountAssetStore;

  @Resource
  private AccountStore accountStore;

  static {
    Args.setParam(
            new String[]{
                "--output-directory", dbPath(),
            },
            TestConstants.TEST_CONF
    );
  }

  @Before
  public void init() {
    accountAssetStore.put(ASSET_KEY, Longs.toByteArray(200L));

    ownerCapsule =
            new AccountCapsule(
                    ByteString.copyFrom(ByteArray.fromHexString(OWNER_ADDRESS)),
                    ByteString.copyFromUtf8("owner"),
                    Protocol.AccountType.AssetIssue);
  }


  private long createAsset(String tokenName) {
    long id = chainBaseManager.getDynamicPropertiesStore().getTokenIdNum() + 1;
    chainBaseManager.getDynamicPropertiesStore().saveTokenIdNum(id);
    AssetIssueContractOuterClass.AssetIssueContract assetIssueContract =
            AssetIssueContractOuterClass.AssetIssueContract.newBuilder()
                    .setOwnerAddress(ByteString.copyFrom(ByteArray.fromHexString(OWNER_ADDRESS)))
                    .setName(ByteString.copyFrom(ByteArray.fromString(tokenName)))
                    .setId(Long.toString(id))
                    .setTotalSupply(TOTAL_SUPPLY)
                    .setTrxNum(TRX_NUM)
                    .setNum(NUM)
                    .setStartTime(START_TIME)
                    .setEndTime(END_TIME)
                    .setVoteScore(VOTE_SCORE)
                    .setDescription(ByteString.copyFrom(ByteArray.fromString(DESCRIPTION)))
                    .setUrl(ByteString.copyFrom(ByteArray.fromString(URL)))
                    .build();
    AssetIssueCapsule assetIssueCapsule = new AssetIssueCapsule(assetIssueContract);
    chainBaseManager.getAssetIssueV2Store()
            .put(assetIssueCapsule.createDbV2Key(), assetIssueCapsule);
    ownerCapsule.addAssetV2(ByteArray.fromString(String.valueOf(id)), TOTAL_SUPPLY);
    accountStore.put(ownerCapsule.getAddress().toByteArray(), ownerCapsule);
    return id;
  }

  @Test
  public void testPut() {
    byte[] key = "10000".getBytes();
    accountAssetStore.put(key, Longs.toByteArray(100L));
    byte[] bytes = accountAssetStore.get(key);
    Assert.assertEquals(100L, Longs.fromByteArray(bytes));
  }

  @Test
  public void testGet() {
    byte[] bytes = accountAssetStore.get(ASSET_KEY);
    Assert.assertEquals(200L, Longs.fromByteArray(bytes));
  }

  @Test
  public void testGetAccountAssets() {
    long assetKey = createAsset("testToken1");
    AccountCapsule accountCapsule = accountStore.get(ownerCapsule.getAddress().toByteArray());
    long assetValue = accountCapsule.getAssetV2(String.valueOf(assetKey));
    Assert.assertEquals(assetValue, TOTAL_SUPPLY);
  }

  @Test
  public void testGetAllAssets() {
    long assetKey1 = createAsset("testToken1");
    long assetKey2 = createAsset("testToken2");
    AccountCapsule accountCapsule = accountStore.get(ownerCapsule.getAddress().toByteArray());

    Map<String, Long> allAssets = accountAssetStore.getAllAssets(accountCapsule.getInstance());
    Long assetValue1 = allAssets.get(String.valueOf(assetKey1));
    Assert.assertNotNull(assetValue1);

    Long assetV1 = accountCapsule.getAssetV2(String.valueOf(assetKey1));
    Assert.assertEquals(assetValue1, assetV1);

    Long assetValue2 = allAssets.get(String.valueOf(assetKey2));
    Assert.assertNotNull(assetValue2);

    Long assetV2 = accountCapsule.getAssetV2(String.valueOf(assetKey2));
    Assert.assertEquals(assetValue1, assetV2);
  }

  @Test
  public void scanPhysicalAssetsStreamsOnlyTheRequestedPrefixInKeyOrder() {
    byte[] address = ByteArray.fromHexString(OWNER_ADDRESS);
    address[20] = 0x66;
    byte[] otherAddress = address.clone();
    otherAddress[20] = 0x67;
    String[] assetIds = {"10003", "10001", "10002"};
    for (int i = 0; i < assetIds.length; i++) {
      accountAssetStore.put(Bytes.concat(address,
          assetIds[i].getBytes(StandardCharsets.US_ASCII)), Longs.toByteArray(i + 1L));
    }
    accountAssetStore.put(Bytes.concat(otherAddress, "00000".getBytes(StandardCharsets.US_ASCII)),
        Longs.toByteArray(99L));
    List<String> scanned = new ArrayList<>();

    long rows = accountAssetStore.scanPhysicalAssets(address,
        (assetId, balance) -> scanned.add(
            new String(assetId, StandardCharsets.US_ASCII) + "=" + balance));

    Assert.assertEquals(3L, rows);
    Assert.assertEquals(Arrays.asList("10001=2", "10002=3", "10003=1"), scanned);
  }

  @Test
  public void archiveAccountAssetDoesNotEmitForImportedUnchangedOptimizedAsset() {
    chainBaseManager.getDynamicPropertiesStore().setAllowAssetOptimization(1);
    byte[] address = ByteArray.fromHexString(OWNER_ADDRESS);
    Protocol.Account accountWithAsset = Protocol.Account.newBuilder()
        .setAddress(ByteString.copyFrom(address))
        .setAssetOptimized(true)
        .putAssetV2("30001", 5L)
        .build();
    accountAssetStore.putAccount(accountWithAsset);
    AccountCapsule stripped = new AccountCapsule(Protocol.Account.newBuilder()
        .setAddress(ByteString.copyFrom(address))
        .setAssetOptimized(true)
        .build());
    accountStore.put(address, stripped);
    DefaultArchiveService archiveService =
        new DefaultArchiveService(true, new InMemoryArchiveTemporalStore());
    try {
      BlockCapsule block = new BlockCapsule(1, Sha256Hash.ZERO_HASH, 1L, ByteString.EMPTY);
      archiveService.beginBlock(block, ArchiveSource.NORMAL);
      archiveService.beginSystemTx(block, ArchivePhase.BLOCK_FINALIZE);
      AccountCapsule imported = new AccountCapsule(accountWithAsset);
      imported.setAccountName("name-only-update".getBytes());
      accountStore.put(address, imported);
      archiveService.endTx();

      long accountAssetChanges = archiveService.getCaptureEngine().records().stream()
          .filter(r -> r.getDomain() == ArchiveDomain.ACCOUNT_ASSET)
          .count();
      Assert.assertEquals(0L, accountAssetChanges);
    } finally {
      archiveService.abortBlock(new BlockCapsule(1, Sha256Hash.ZERO_HASH, 1L, ByteString.EMPTY));
      archiveService.close();
      ArchiveCaptureHolder.clear();
    }
  }

  @Test
  public void archiveCapturesLazyImportedPhysicalAssetMutationThroughAccountFlush() {
    chainBaseManager.getDynamicPropertiesStore().setAllowAssetOptimization(1);
    byte[] address = ByteArray.fromHexString(OWNER_ADDRESS);
    address[20] = 0x54;
    String assetId = "30003";
    byte[] assetKey = Bytes.concat(address, assetId.getBytes(StandardCharsets.US_ASCII));
    AccountCapsule stripped = new AccountCapsule(Protocol.Account.newBuilder()
        .setAddress(ByteString.copyFrom(address))
        .setAssetOptimized(true)
        .build());
    accountStore.put(address, stripped);
    accountAssetStore.put(assetKey, Longs.toByteArray(7L));
    DefaultArchiveService archiveService =
        new DefaultArchiveService(true, new InMemoryArchiveTemporalStore());
    BlockCapsule block = new BlockCapsule(1, Sha256Hash.ZERO_HASH, 1L, ByteString.EMPTY);
    try {
      archiveService.beginBlock(block, ArchiveSource.NORMAL);
      archiveService.beginSystemTx(block, ArchivePhase.BLOCK_FINALIZE);
      AccountCapsule updated = accountStore.get(address);
      Assert.assertTrue(updated.getInstance().getAssetV2Map().isEmpty());
      Assert.assertEquals(7L, updated.getAssetV2(assetId));
      updated.addAssetMapV2(Collections.singletonMap(assetId, 12L));

      accountStore.put(address, updated);
      archiveService.endTx();

      ArchiveChangeRecord assetChange = archiveService.getCaptureEngine().records().stream()
          .filter(r -> r.getDomain() == ArchiveDomain.ACCOUNT_ASSET)
          .findFirst()
          .orElseThrow(AssertionError::new);
      Assert.assertEquals(7L, Longs.fromByteArray(assetChange.getPrevValue().getValue()));
      Assert.assertEquals(12L, Longs.fromByteArray(assetChange.getValue().getValue()));
      Assert.assertEquals(12L, accountAssetStore.getBalance(
          address, assetId.getBytes(StandardCharsets.US_ASCII)));
    } finally {
      archiveService.abortBlock(block);
      archiveService.close();
      ArchiveCaptureHolder.clear();
    }
  }

  @Test
  public void archiveAccountDeleteEmitsAssetTombstoneForOptimizedAsset() {
    chainBaseManager.getDynamicPropertiesStore().setAllowAssetOptimization(1);
    chainBaseManager.getDynamicPropertiesStore().setAllowAccountAssetOptimization(0);
    byte[] address = ByteArray.fromHexString(OWNER_ADDRESS);
    address[20] = 0x55; // keep this test isolated from OWNER_ADDRESS assets created elsewhere
    Protocol.Account accountWithAsset = Protocol.Account.newBuilder()
        .setAddress(ByteString.copyFrom(address))
        .setAssetOptimized(true)
        .putAssetV2("30002", 7L)
        .build();
    accountAssetStore.putAccount(accountWithAsset);
    AccountCapsule stripped = new AccountCapsule(Protocol.Account.newBuilder()
        .setAddress(ByteString.copyFrom(address))
        .setAssetOptimized(true)
        .build());
    accountStore.put(address, stripped);
    Assert.assertEquals(7L, accountAssetStore.getBalance(
        address, "30002".getBytes(StandardCharsets.US_ASCII)));
    Assert.assertEquals(1L, accountAssetStore.scanPhysicalAssets(address,
        (assetId, balance) -> {
        }));

    DefaultArchiveService archiveService =
        new DefaultArchiveService(true, new InMemoryArchiveTemporalStore());
    try {
      BlockCapsule block = new BlockCapsule(1, Sha256Hash.ZERO_HASH, 1L, ByteString.EMPTY);
      archiveService.beginBlock(block, ArchiveSource.NORMAL);
      archiveService.beginSystemTx(block, ArchivePhase.BLOCK_FINALIZE);
      accountStore.delete(address);
      archiveService.endTx();

      Assert.assertFalse(archiveService.getCaptureEngine().failure()
          .map(Throwable::getMessage).orElse("archive capture failed"),
          archiveService.getCaptureEngine().failure().isPresent());
      Assert.assertEquals(1L, archiveService.getCaptureEngine().accountAssetPrefixRows());
      List<ArchiveChangeRecord> records = archiveService.getCaptureEngine().records();
      Assert.assertEquals(1L, records.stream()
          .filter(r -> r.getDomain() == ArchiveDomain.ACCOUNT_ASSET)
          .count());
      ArchiveChangeRecord record = records.stream()
          .filter(r -> r.getDomain() == ArchiveDomain.ACCOUNT_ASSET)
          .findFirst()
          .orElseThrow(AssertionError::new);
      Assert.assertEquals(7L, Longs.fromByteArray(record.getPrevValue().getValue()));
      Assert.assertTrue(record.getValue().isDeleted());
    } finally {
      archiveService.abortBlock(new BlockCapsule(1, Sha256Hash.ZERO_HASH, 1L, ByteString.EMPTY));
      archiveService.close();
      ArchiveCaptureHolder.clear();
    }
  }

}
