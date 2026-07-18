package org.tron.core.store;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.primitives.Bytes;
import com.google.common.primitives.Longs;
import com.google.protobuf.ByteString;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.tron.common.parameter.CommonParameter;
import org.tron.core.archive.ArchiveExecutionContext;
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveSource;
import org.tron.core.archive.capture.ArchiveCaptureEngine;
import org.tron.core.archive.capture.ArchiveCaptureHolder;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.domain.DefaultArchiveDomainCatalog;
import org.tron.core.archive.domain.DefaultArchiveDomainRegistry;
import org.tron.core.archive.domain.DynamicKeyPolicy;
import org.tron.core.archive.txnum.ArchiveTxPosition;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.utils.AssetUtil;
import org.tron.core.db.TronStoreWithRevoking;
import org.tron.core.db.accountstate.AccountStateCallBackUtils;
import org.tron.core.db2.common.IRevokingDB;
import org.tron.protos.Protocol.Account;

public class AccountStoreArchiveCaptureTest {

  private boolean historyBalanceLookup;
  private AccountAssetStore previousAssetUtilStore;
  private DynamicPropertiesStore previousAssetUtilDynamicStore;

  @Before
  public void disableHistoryBalanceLookup() throws Exception {
    historyBalanceLookup = CommonParameter.getInstance().isHistoryBalanceLookup();
    CommonParameter.getInstance().setHistoryBalanceLookup(false);
    previousAssetUtilStore = (AccountAssetStore) getStaticField(
        AssetUtil.class, "accountAssetStore");
    previousAssetUtilDynamicStore = (DynamicPropertiesStore) getStaticField(
        AssetUtil.class, "dynamicPropertiesStore");
  }

  @After
  public void cleanUp() {
    ArchiveCaptureHolder.clear();
    CommonParameter.getInstance().setHistoryBalanceLookup(historyBalanceLookup);
    AssetUtil.setAccountAssetStore(previousAssetUtilStore);
    AssetUtil.setDynamicPropertiesStore(previousAssetUtilDynamicStore);
  }

  @Test
  public void ordinaryPutReadsPreviousOnceAndCapturesSortedEffectiveBalances() throws Exception {
    byte[] address = address();
    AccountCapsule oldAccount = account(address, true,
        "a", 5L, "both", 1L);
    AccountCapsule newAccount = account(address, true,
        "b", 8L, "both", 2L, "zero", 0L);
    Map<String, Long> physical = balances(
        "a", 7L, "b", 6L, "zero", 9L);
    AccountAssetStore assetStore = mock(AccountAssetStore.class);
    when(assetStore.getBalance(any(byte[].class), any(byte[].class)))
        .thenAnswer(invocation -> physical.get(ascii((byte[]) invocation.getArgument(1))));
    IRevokingDB revokingDb = mock(IRevokingDB.class);
    when(revokingDb.getUnchecked(same(address))).thenReturn(oldAccount.getData());
    AccountStore store = accountStore(assetStore, revokingDb);
    ArchiveCaptureEngine engine = startCapture();

    store.put(address, newAccount);

    verify(revokingDb, times(1)).getUnchecked(same(address));
    verify(revokingDb).put(same(address), aryEq(newAccount.getData()));
    verify(assetStore, never()).scanPhysicalAssets(
        any(byte[].class), any(AccountAssetStore.PhysicalAssetConsumer.class));
    ArgumentCaptor<byte[]> assetIds = ArgumentCaptor.forClass(byte[].class);
    verify(assetStore, times(3)).getBalance(same(address), assetIds.capture());
    assertEquals(Arrays.asList("a", "b", "zero"), ascii(assetIds.getAllValues()));

    List<ArchiveChangeRecord> records = engine.records();
    assertEquals(5, records.size());
    assertAccountPut(records.get(0), address);
    assertAsset(records.get(1), address, "a", 5L, 7L);
    assertAsset(records.get(2), address, "b", 6L, 8L);
    assertAsset(records.get(3), address, "both", 1L, 2L);
    assertAsset(records.get(4), address, "zero", 9L, 0L);
    assertFalse(engine.failure().isPresent());
  }

  @Test
  public void nullItemIsNoOpForArchiveCaptureWhenHistoryLookupIsDisabled() throws Exception {
    byte[] address = address();
    AccountAssetStore assetStore = mock(AccountAssetStore.class);
    IRevokingDB revokingDb = mock(IRevokingDB.class);
    AccountStore store = accountStore(assetStore, revokingDb);
    ArchiveCaptureEngine engine = startCapture();

    store.put(address, null);

    verify(revokingDb, never()).getUnchecked(any(byte[].class));
    verify(revokingDb, never()).put(any(byte[].class), any(byte[].class));
    verify(assetStore, never()).scanPhysicalAssets(
        any(byte[].class), any(AccountAssetStore.PhysicalAssetConsumer.class));
    assertTrue(engine.records().isEmpty());
    assertFalse(engine.failure().isPresent());
  }

  @Test
  public void nullItemPreservesHistoryLookupNullPointerOrdering() throws Exception {
    CommonParameter.getInstance().setHistoryBalanceLookup(true);
    byte[] address = address();
    AccountCapsule oldAccount = account(address, false);
    AccountAssetStore assetStore = mock(AccountAssetStore.class);
    IRevokingDB revokingDb = mock(IRevokingDB.class);
    when(revokingDb.getUnchecked(same(address))).thenReturn(oldAccount.getData());
    AccountStore store = accountStore(assetStore, revokingDb);
    ArchiveCaptureEngine engine = startCapture();

    assertThrows(NullPointerException.class, () -> store.put(address, null));

    verify(revokingDb, times(1)).getUnchecked(same(address));
    verify(revokingDb, never()).put(any(byte[].class), any(byte[].class));
    assertTrue(engine.records().isEmpty());
    assertFalse(engine.failure().isPresent());
  }

  @Test
  public void historyLookupAndArchivePutShareOnePreviousAccountRead() throws Exception {
    CommonParameter.getInstance().setHistoryBalanceLookup(true);
    byte[] address = address();
    AccountCapsule oldAccount = account(address, false);
    AccountCapsule newAccount = account(address, false);
    AccountAssetStore assetStore = mock(AccountAssetStore.class);
    IRevokingDB revokingDb = mock(IRevokingDB.class);
    when(revokingDb.getUnchecked(same(address))).thenReturn(oldAccount.getData());
    AccountStore store = accountStore(assetStore, revokingDb);
    ArchiveCaptureEngine engine = startCapture();

    store.put(address, newAccount);

    verify(revokingDb, times(1)).getUnchecked(same(address));
    verify(revokingDb).put(same(address), aryEq(newAccount.getData()));
    assertEquals(1, engine.records().size());
    assertFalse(engine.failure().isPresent());
  }

  @Test
  public void historyLookupAndArchiveDeleteShareOnePreviousAccountRead() throws Exception {
    CommonParameter.getInstance().setHistoryBalanceLookup(true);
    byte[] address = address();
    AccountCapsule oldAccount = account(address, false);
    AccountAssetStore assetStore = mock(AccountAssetStore.class);
    IRevokingDB revokingDb = mock(IRevokingDB.class);
    when(revokingDb.getUnchecked(same(address))).thenReturn(oldAccount.getData());
    AccountStore store = accountStore(assetStore, revokingDb);
    ArchiveCaptureEngine engine = startCapture();

    store.delete(address);

    verify(revokingDb, times(1)).getUnchecked(same(address));
    verify(revokingDb).delete(same(address));
    assertEquals(1, engine.records().size());
    assertFalse(engine.failure().isPresent());
  }

  @Test
  public void unchangedAssetStateSkipsAssetPlanningAndPhysicalReads() throws Exception {
    byte[] address = address();
    AccountCapsule oldAccount = account(address, true, "asset", 7L);
    AccountCapsule newAccount = new AccountCapsule(oldAccount.getInstance().toBuilder()
        .setBalance(99L)
        .build());
    AccountAssetStore assetStore = mock(AccountAssetStore.class);
    IRevokingDB revokingDb = mock(IRevokingDB.class);
    when(revokingDb.getUnchecked(same(address))).thenReturn(oldAccount.getData());
    AccountStore store = accountStore(assetStore, revokingDb);
    ArchiveCaptureEngine engine = startCapture();

    store.put(address, newAccount);

    verify(assetStore, never()).scanPhysicalAssets(
        any(byte[].class), any(AccountAssetStore.PhysicalAssetConsumer.class));
    verify(assetStore, never()).getBalance(any(byte[].class), any(byte[].class));
    assertEquals(1, engine.records().size());
    assertEquals(ArchiveDomain.ACCOUNT, engine.records().get(0).getDomain());
    assertFalse(engine.failure().isPresent());
  }

  @Test
  public void storeLoadedAccountCapturesOnlyTouchedAssetAndRebasesHint() throws Exception {
    byte[] address = address();
    AccountCapsule oldAccount = account(address, false,
        "asset-a", 1L, "asset-b", 2L, "asset-c", 3L);
    AccountAssetStore assetStore = mock(AccountAssetStore.class);
    IRevokingDB revokingDb = mock(IRevokingDB.class);
    when(revokingDb.getUnchecked(same(address))).thenReturn(oldAccount.getData());
    AccountStore store = accountStore(assetStore, revokingDb);
    ArchiveCaptureEngine engine = startCapture();

    AccountCapsule updated = store.get(address);
    updated.addAssetMapV2(Collections.singletonMap("asset-b", 9L));
    assertTrue(updated.hasCompleteAssetV2ChangeTracking());
    assertEquals(Collections.singleton("asset-b"), updated.snapshotModifiedAssetV2());

    store.put(address, updated);

    assertTrue(updated.hasCompleteAssetV2ChangeTracking());
    assertTrue(updated.snapshotModifiedAssetV2().isEmpty());
    verify(assetStore, never()).scanPhysicalAssets(
        any(byte[].class), any(AccountAssetStore.PhysicalAssetConsumer.class));
    verify(assetStore, never()).getBalance(any(byte[].class), any(byte[].class));
    assertEquals(2, engine.records().size());
    assertAccountPut(engine.records().get(0), address);
    assertAsset(engine.records().get(1), address, "asset-b", 2L, 9L);
    assertFalse(engine.failure().isPresent());
  }

  @Test
  public void staleStoreLoadedHintFallsBackToActualPreviousVersion() throws Exception {
    byte[] address = address();
    AccountCapsule oldAccount = account(address, false, "asset", 1L);
    AtomicReference<byte[]> canonical = new AtomicReference<>(oldAccount.getData());
    AccountAssetStore assetStore = mock(AccountAssetStore.class);
    IRevokingDB revokingDb = mock(IRevokingDB.class);
    when(revokingDb.getUnchecked(same(address))).thenAnswer(
        invocation -> canonical.get());
    doAnswer(invocation -> {
      byte[] value = invocation.getArgument(1);
      canonical.set(Arrays.copyOf(value, value.length));
      return null;
    }).when(revokingDb).put(same(address), any(byte[].class));
    AccountStore store = accountStore(assetStore, revokingDb);
    ArchiveCaptureEngine engine = startCapture();

    AccountCapsule stale = store.get(address);
    AccountCapsule assetWriter = store.get(address);
    assetWriter.addAssetMapV2(Collections.singletonMap("asset", 9L));
    store.put(address, assetWriter);

    stale.setBalance(99L);
    store.put(address, stale);

    List<ArchiveChangeRecord> assetRecords = new ArrayList<>();
    for (ArchiveChangeRecord record : engine.records()) {
      if (record.getDomain() == ArchiveDomain.ACCOUNT_ASSET) {
        assetRecords.add(record);
      }
    }
    assertEquals(1, assetRecords.size());
    assertAsset(assetRecords.get(0), address, "asset", 1L, 1L);
    assertFalse(engine.failure().isPresent());
  }

  @Test
  public void lazyImportedAssetTracksPhysicalVersionWhenAccountRowIsUnchanged() throws Exception {
    byte[] address = address();
    byte[] assetId = bytes("asset");
    AccountCapsule canonicalAccount = account(address, true);
    AccountAssetStore assetStore = mock(AccountAssetStore.class);
    when(assetStore.getBalance(any(Account.class), any(byte[].class))).thenReturn(1L);
    when(assetStore.getBalance(any(byte[].class), any(byte[].class))).thenReturn(9L);
    DynamicPropertiesStore dynamicStore = mock(DynamicPropertiesStore.class);
    when(dynamicStore.supportAllowAssetOptimization()).thenReturn(true);
    AssetUtil.setAccountAssetStore(assetStore);
    AssetUtil.setDynamicPropertiesStore(dynamicStore);
    IRevokingDB revokingDb = mock(IRevokingDB.class);
    when(revokingDb.getUnchecked(same(address))).thenReturn(canonicalAccount.getData());
    AccountStore store = accountStore(assetStore, revokingDb);
    ArchiveCaptureEngine engine = startCapture();

    AccountCapsule stale = store.get(address);
    assertEquals(1L, stale.getAssetV2("asset"));
    assertEquals(Collections.singleton("asset"), stale.snapshotModifiedAssetV2());
    stale.setBalance(99L);
    store.put(address, stale);

    verify(assetStore).getBalance(any(Account.class), aryEq(assetId));
    verify(assetStore).getBalance(same(address), aryEq(assetId));
    verify(assetStore, never()).scanPhysicalAssets(
        any(byte[].class), any(AccountAssetStore.PhysicalAssetConsumer.class));
    assertEquals(2, engine.records().size());
    assertEquals(ArchiveDomain.ACCOUNT, engine.records().get(0).getDomain());
    assertArrayEquals(address, engine.records().get(0).getCanonicalKey());
    assertAsset(engine.records().get(1), address, "asset", 9L, 1L);
    assertFalse(engine.failure().isPresent());
  }

  @Test
  public void wholeAccountReplacementInvalidatesHintAndFallsBackToValueDiff() throws Exception {
    byte[] address = address();
    AccountCapsule oldAccount = account(address, false, "asset-a", 1L, "asset-b", 2L);
    AccountAssetStore assetStore = mock(AccountAssetStore.class);
    IRevokingDB revokingDb = mock(IRevokingDB.class);
    when(revokingDb.getUnchecked(same(address))).thenReturn(oldAccount.getData());
    AccountStore store = accountStore(assetStore, revokingDb);
    ArchiveCaptureEngine engine = startCapture();

    AccountCapsule updated = store.get(address);
    updated.setInstance(updated.getInstance().toBuilder().putAssetV2("asset-a", 7L).build());
    assertFalse(updated.hasCompleteAssetV2ChangeTracking());

    store.put(address, updated);

    assertEquals(2, engine.records().size());
    assertAsset(engine.records().get(1), address, "asset-a", 1L, 7L);
    assertFalse(engine.failure().isPresent());
  }

  @Test
  public void storeReadOutsideArchiveCaptureDoesNotEnableAssetTracking() throws Exception {
    byte[] address = address();
    AccountCapsule oldAccount = account(address, false, "asset", 1L);
    AccountAssetStore assetStore = mock(AccountAssetStore.class);
    IRevokingDB revokingDb = mock(IRevokingDB.class);
    when(revokingDb.getUnchecked(same(address))).thenReturn(oldAccount.getData());
    AccountStore store = accountStore(assetStore, revokingDb);

    AccountCapsule loaded = store.get(address);

    assertFalse(loaded.hasCompleteAssetV2ChangeTracking());
    assertTrue(loaded.snapshotModifiedAssetV2().isEmpty());
  }

  @Test
  public void deleteScansPhysicalAssetsAndCapturesOverlayAndOrphan() throws Exception {
    byte[] address = address();
    AccountCapsule oldAccount = account(address, true,
        "overlay", 8L, "zero", 0L);
    AccountAssetStore assetStore = mock(AccountAssetStore.class);
    stubPhysicalScan(assetStore, address, balances(
        "orphan", 7L, "overlay", 5L, "zero", 4L));
    IRevokingDB revokingDb = mock(IRevokingDB.class);
    when(revokingDb.getUnchecked(same(address))).thenReturn(oldAccount.getData());
    AccountStore store = accountStore(assetStore, revokingDb);
    ArchiveCaptureEngine engine = startCapture();

    store.delete(address);

    verify(revokingDb, times(1)).getUnchecked(same(address));
    verify(revokingDb).delete(same(address));
    verify(assetStore, times(1)).scanPhysicalAssets(
        same(address), any(AccountAssetStore.PhysicalAssetConsumer.class));
    verify(assetStore, never()).getBalance(any(byte[].class), any(byte[].class));

    List<ArchiveChangeRecord> records = engine.records();
    assertEquals(3, records.size());
    assertAccountDelete(records.get(0), address);
    assertAsset(records.get(1), address, "orphan", 7L, 0L);
    assertAsset(records.get(2), address, "overlay", 8L, 0L);
    assertFalse(engine.failure().isPresent());
  }

  @Test
  public void optimizedToUnoptimizedScansPrefixAndExplicitZeroWins() throws Exception {
    byte[] address = address();
    AccountAssetStore assetStore = mock(AccountAssetStore.class);
    stubPhysicalScan(assetStore, address, balances("orphan", 7L, "zero", 9L));
    AccountStore store = plannerStore(assetStore);
    ArchiveCaptureEngine engine = startCapture();

    capturePlannedTransitions(store, address,
        account(address, true, "zero", 0L).getData(),
        account(address, false).getData());

    verify(assetStore, times(1)).scanPhysicalAssets(
        same(address), any(AccountAssetStore.PhysicalAssetConsumer.class));
    verify(assetStore, never()).getBalance(any(byte[].class), any(byte[].class));
    assertEquals(1, engine.records().size());
    assertAsset(engine.records().get(0), address, "orphan", 7L, 0L);
    assertFalse(engine.failure().isPresent());
  }

  @Test
  public void unoptimizedToOptimizedScansPrefixAndUsesPhysicalFallback() throws Exception {
    byte[] address = address();
    AccountAssetStore assetStore = mock(AccountAssetStore.class);
    stubPhysicalScan(assetStore, address, balances("orphan", 7L, "zero", 9L));
    AccountStore store = plannerStore(assetStore);
    ArchiveCaptureEngine engine = startCapture();

    capturePlannedTransitions(store, address,
        account(address, false).getData(),
        account(address, true, "zero", 0L).getData());

    verify(assetStore, times(1)).scanPhysicalAssets(
        same(address), any(AccountAssetStore.PhysicalAssetConsumer.class));
    verify(assetStore, never()).getBalance(any(byte[].class), any(byte[].class));
    assertEquals(1, engine.records().size());
    assertAsset(engine.records().get(0), address, "orphan", 0L, 7L);
    assertFalse(engine.failure().isPresent());
  }

  @Test
  public void newOptimizedAccountScansOrphansAndMapValuesOverridePhysical() throws Exception {
    byte[] address = address();
    AccountAssetStore assetStore = mock(AccountAssetStore.class);
    stubPhysicalScan(assetStore, address, balances(
        "mapped", 2L, "orphan", 13L, "zero", 9L));
    AccountStore store = plannerStore(assetStore);
    ArchiveCaptureEngine engine = startCapture();

    capturePlannedTransitions(store, address, null,
        account(address, true, "mapped", 4L, "zero", 0L).getData());

    verify(assetStore, times(1)).scanPhysicalAssets(
        same(address), any(AccountAssetStore.PhysicalAssetConsumer.class));
    verify(assetStore, never()).getBalance(any(byte[].class), any(byte[].class));
    assertEquals(2, engine.records().size());
    assertAsset(engine.records().get(0), address, "mapped", 0L, 4L);
    assertAsset(engine.records().get(1), address, "orphan", 0L, 13L);
    assertFalse(engine.failure().isPresent());
  }

  @Test
  public void captureBudgetFailureStopsLargePhysicalPrefixPromptly() throws Exception {
    byte[] address = address();
    AtomicInteger visited = new AtomicInteger();
    AccountAssetStore assetStore = mock(AccountAssetStore.class);
    when(assetStore.scanPhysicalAssets(
        same(address), any(AccountAssetStore.PhysicalAssetConsumer.class)))
        .thenAnswer(invocation -> {
          AccountAssetStore.PhysicalAssetConsumer consumer = invocation.getArgument(1);
          for (int i = 0; i < 100; i++) {
            visited.incrementAndGet();
            consumer.accept(bytes(String.format("%03d", i)), i + 1L);
          }
          return 100L;
        });
    AccountStore store = plannerStore(assetStore);
    ArchiveCaptureEngine engine = startCapture(1L);

    capturePlannedTransitions(store, address,
        account(address, true).getData(), null);

    assertEquals(2, visited.get());
    assertEquals(1, engine.records().size());
    assertTrue(engine.failure().isPresent());
  }

  @Test
  public void plannerInputBudgetRejectsBeforeProtoParsingOrPhysicalReads() throws Exception {
    byte[] address = address();
    AccountAssetStore assetStore = mock(AccountAssetStore.class);
    AccountStore store = plannerStore(assetStore);
    ArchiveCaptureEngine engine = startCapture(Long.MAX_VALUE, 2_000L);
    byte[] oversizedInvalidAccount = new byte[256];
    Arrays.fill(oversizedInvalidAccount, (byte) 0xff);

    capturePlannedTransitions(store, address, null, oversizedInvalidAccount);

    assertTrue(engine.failure().isPresent());
    assertTrue(engine.failure().get().getCause().getMessage()
        .contains("transient resource watermark"));
    verify(assetStore, never()).scanPhysicalAssets(
        any(byte[].class), any(AccountAssetStore.PhysicalAssetConsumer.class));
    verify(assetStore, never()).getBalance(any(byte[].class), any(byte[].class));
  }

  @Test
  public void plannerScopeClosesAfterUnchangedAssetEarlyReturn() throws Exception {
    byte[] address = address();
    AccountAssetStore assetStore = mock(AccountAssetStore.class);
    AccountStore store = plannerStore(assetStore);
    ArchiveCaptureEngine engine = startCapture();
    byte[] oldAccount = account(address, true, "asset", 7L).getData();
    byte[] newAccount = account(address, true, "asset", 7L).getData();

    capturePlannedTransitions(store, address, oldAccount, newAccount);
    capturePlannedTransitions(store, address, oldAccount, newAccount);

    assertTrue(engine.records().isEmpty());
    assertFalse(engine.failure().isPresent());
    verify(assetStore, never()).scanPhysicalAssets(
        any(byte[].class), any(AccountAssetStore.PhysicalAssetConsumer.class));
    verify(assetStore, never()).getBalance(any(byte[].class), any(byte[].class));
  }

  private static AccountStore accountStore(AccountAssetStore assetStore, IRevokingDB revokingDb)
      throws Exception {
    AccountStore store = plannerStore(assetStore);
    setField(TronStoreWithRevoking.class, store, "revokingDB", revokingDb);
    setField(AccountStore.class, store, "accountStateCallBackUtils",
        mock(AccountStateCallBackUtils.class));
    setField(AccountStore.class, store, "balanceTraceStore", mock(BalanceTraceStore.class));
    setField(AccountStore.class, store, "accountTraceStore", mock(AccountTraceStore.class));
    doReturn("account").when(store).getDbName();
    return store;
  }

  private static AccountStore plannerStore(AccountAssetStore assetStore) throws Exception {
    AccountStore store = mock(AccountStore.class, CALLS_REAL_METHODS);
    setField(AccountStore.class, store, "accountAssetStore", assetStore);
    return store;
  }

  private static void setField(Class<?> owner, Object target, String name, Object value)
      throws Exception {
    Field field = owner.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static Object getStaticField(Class<?> owner, String name) throws Exception {
    Field field = owner.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(null);
  }

  private static void capturePlannedTransitions(AccountStore store, byte[] address,
      byte[] oldAccount, byte[] newAccount) throws Exception {
    Method capture = AccountStore.class.getDeclaredMethod("captureAccountAssetTransitions",
        byte[].class, byte[].class, byte[].class, Account.class);
    capture.setAccessible(true);
    capture.invoke(store, address, oldAccount, newAccount, null);
  }

  private static void stubPhysicalScan(AccountAssetStore store, byte[] address,
      Map<String, Long> physical) {
    when(store.scanPhysicalAssets(
        same(address), any(AccountAssetStore.PhysicalAssetConsumer.class)))
        .thenAnswer(invocation -> {
          AccountAssetStore.PhysicalAssetConsumer consumer = invocation.getArgument(1);
          physical.entrySet().stream()
              .sorted(Map.Entry.comparingByKey())
              .forEach(entry -> consumer.accept(bytes(entry.getKey()), entry.getValue()));
          return (long) physical.size();
        });
  }

  private static ArchiveCaptureEngine startCapture() {
    return startCapture(Long.MAX_VALUE);
  }

  private static ArchiveCaptureEngine startCapture(long maxRawRecords) {
    return startCapture(maxRawRecords, Long.MAX_VALUE);
  }

  private static ArchiveCaptureEngine startCapture(long maxRawRecords, long maxRawBytes) {
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    ArchiveCaptureEngine engine = new ArchiveCaptureEngine(
        new DefaultArchiveDomainRegistry(), new DefaultArchiveDomainCatalog(),
        new DynamicKeyPolicy(), context, maxRawRecords, maxRawBytes);
    context.enter(new ArchiveTxPosition(
        41L, 7L, ArchivePhase.USER_TX, ArchiveSource.NORMAL, 0, new byte[] {1}));
    ArchiveCaptureHolder.set(engine);
    return engine;
  }

  private static AccountCapsule account(byte[] address, boolean optimized, Object... assets) {
    Account.Builder builder = Account.newBuilder()
        .setAddress(ByteString.copyFrom(address))
        .setAssetOptimized(optimized);
    for (int i = 0; i < assets.length; i += 2) {
      builder.putAssetV2((String) assets[i], (Long) assets[i + 1]);
    }
    return new AccountCapsule(builder.build());
  }

  private static byte[] address() {
    byte[] address = new byte[21];
    address[0] = 0x41;
    address[20] = 0x2a;
    return address;
  }

  private static Map<String, Long> balances(Object... assets) {
    Map<String, Long> balances = new HashMap<>();
    for (int i = 0; i < assets.length; i += 2) {
      balances.put((String) assets[i], (Long) assets[i + 1]);
    }
    return balances;
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static String ascii(byte[] value) {
    return new String(value, StandardCharsets.US_ASCII);
  }

  private static List<String> ascii(List<byte[]> values) {
    List<String> result = new ArrayList<>();
    for (byte[] value : values) {
      result.add(ascii(value));
    }
    return result;
  }

  private static void assertAccountPut(ArchiveChangeRecord record, byte[] address) {
    assertEquals(ArchiveDomain.ACCOUNT, record.getDomain());
    assertArrayEquals(address, record.getCanonicalKey());
    assertFalse(record.getPrevValue().isDeleted());
    assertFalse(record.getValue().isDeleted());
    assertArrayEquals(record.getPrevValue().getValue(), record.getValue().getValue());
  }

  private static void assertAccountDelete(ArchiveChangeRecord record, byte[] address) {
    assertEquals(ArchiveDomain.ACCOUNT, record.getDomain());
    assertArrayEquals(address, record.getCanonicalKey());
    assertFalse(record.getPrevValue().isDeleted());
    assertTrue(record.getValue().isDeleted());
  }

  private static void assertAsset(ArchiveChangeRecord record, byte[] address, String assetId,
      long previous, long value) {
    assertEquals(ArchiveDomain.ACCOUNT_ASSET, record.getDomain());
    assertArrayEquals(Bytes.concat(address, bytes(assetId)), record.getCanonicalKey());
    assertBalance(record.getPrevValue(), previous);
    assertBalance(record.getValue(), value);
  }

  private static void assertBalance(DomainValue value, long expected) {
    if (expected == 0L) {
      assertTrue(value.isDeleted());
    } else {
      assertFalse(value.isDeleted());
      assertArrayEquals(Longs.toByteArray(expected), value.getValue());
    }
  }
}
