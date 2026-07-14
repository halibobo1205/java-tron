package org.tron.core.store;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.tron.core.db.TronStoreWithRevoking;
import org.tron.core.db.accountstate.AccountStateCallBackUtils;
import org.tron.core.db2.common.IRevokingDB;
import org.tron.protos.Protocol.Account;

public class AccountStoreArchiveCaptureTest {

  private boolean historyBalanceLookup;

  @Before
  public void disableHistoryBalanceLookup() {
    historyBalanceLookup = CommonParameter.getInstance().isHistoryBalanceLookup();
    CommonParameter.getInstance().setHistoryBalanceLookup(false);
  }

  @After
  public void cleanUp() {
    ArchiveCaptureHolder.clear();
    CommonParameter.getInstance().setHistoryBalanceLookup(historyBalanceLookup);
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

  private static AccountStore accountStore(AccountAssetStore assetStore, IRevokingDB revokingDb)
      throws Exception {
    AccountStore store = plannerStore(assetStore);
    setField(TronStoreWithRevoking.class, store, "revokingDB", revokingDb);
    setField(AccountStore.class, store, "accountStateCallBackUtils",
        mock(AccountStateCallBackUtils.class));
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

  private static void capturePlannedTransitions(AccountStore store, byte[] address,
      byte[] oldAccount, byte[] newAccount) throws Exception {
    Method capture = AccountStore.class.getDeclaredMethod("captureAccountAssetTransitions",
        byte[].class, byte[].class, byte[].class);
    capture.setAccessible(true);
    capture.invoke(store, address, oldAccount, newAccount);
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
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    ArchiveCaptureEngine engine = new ArchiveCaptureEngine(
        new DefaultArchiveDomainRegistry(), new DefaultArchiveDomainCatalog(),
        new DynamicKeyPolicy(), context, maxRawRecords, Long.MAX_VALUE);
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
