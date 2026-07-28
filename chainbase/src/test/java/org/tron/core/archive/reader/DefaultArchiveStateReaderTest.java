package org.tron.core.archive.reader;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.primitives.Bytes;
import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;
import org.rocksdb.RocksDBException;
import org.tron.common.utils.ByteArray;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.ArchivePersistentStateCorruptionException;
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveSource;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.domain.ArchiveDomainCatalog;
import org.tron.core.archive.domain.DefaultArchiveDomainCatalog;
import org.tron.core.archive.query.ArchiveQueryLimits;
import org.tron.core.archive.query.HistoricalQueryLimitException;
import org.tron.core.archive.query.QueryContext;
import org.tron.core.archive.reader.ArchiveReadResult.Status;
import org.tron.core.archive.temporal.ArchiveTemporalStore;
import org.tron.core.archive.temporal.ArchiveTemporalReadView;
import org.tron.core.archive.temporal.InMemoryArchiveTemporalStore;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.archive.txnum.ArchiveTxPosition;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.ContractStateCapsule;
import org.tron.protos.Protocol.Account;
import org.tron.protos.contract.SmartContractOuterClass.ContractState;
import org.tron.protos.contract.SmartContractOuterClass.SmartContract;

public class DefaultArchiveStateReaderTest {

  private InMemoryArchiveTemporalStore store;
  private ArchiveDomainCatalog catalog;

  @Before
  public void setUp() {
    store = new InMemoryArchiveTemporalStore();
    catalog = new DefaultArchiveDomainCatalog();
  }

  private ArchiveStateReader readerAt(long txNum) {
    return new DefaultArchiveStateReader(store, catalog,
        ArchiveStatePoint.blockEnd(1, new byte[] {1}, txNum));
  }

  // Models "key created at txNum with `value`, absent before" (prev = tombstone). getAsOf at/after
  // txNum falls through to latest = value, exactly as the floor model returned that value.
  private void put(ArchiveDomain domain, byte[] key, DomainValue value, long txNum) {
    put(domain, key, DomainValue.tombstone(), value, txNum);
  }

  private void put(ArchiveDomain domain, byte[] key, DomainValue prev, DomainValue value,
      long txNum) {
    store.putChange(new ArchiveChangeRecord(
        new ArchiveTxPosition(txNum, 1, ArchivePhase.BLOCK_FINALIZE,
            ArchiveSource.NORMAL, -1, null),
        domain, key, prev, value));
  }

  @Test
  public void dynamicPropertyPresentDecodesAndAbsentIsMissing() throws Exception {
    byte[] london = "ALLOW_TVM_LONDON".getBytes(StandardCharsets.US_ASCII);
    put(ArchiveDomain.DYNAMIC_PROPERTIES, london, DomainValue.present(ByteArray.fromLong(1L)), 5);
    ArchiveStateReader reader = readerAt(10);

    ArchiveReadResult<byte[]> present = reader.getDynamicProperty(london);
    assertEquals(Status.PRESENT, present.getStatus());
    assertEquals(1L, ByteArray.toLong(present.getValue()));

    // A key never written by a proposal is MISSING (the historical view maps this to the default).
    assertEquals(Status.MISSING, reader.getDynamicProperty(
        "ALLOW_TVM_CANCUN".getBytes(StandardCharsets.US_ASCII)).getStatus());
  }

  @Test
  public void blockHashUsesBoundSnapshotLookupWithoutGuessingBackendCost() throws Exception {
    byte[] hash = new byte[ArchiveBlockRange.BLOCK_HASH_LENGTH];
    hash[31] = 0x2a;
    ArchiveBlockRange range = new ArchiveBlockRange(
        0L, 0L, 1L, 0L, 1L, hash, 0, ArchiveSource.NORMAL, new byte[32]);
    QueryContext context = new QueryContext(ArchiveQueryLimits.unlimited());
    AtomicInteger lookups = new AtomicInteger();
    ArchiveTemporalReadView view = mock(ArchiveTemporalReadView.class);
    DefaultArchiveStateReader reader = new DefaultArchiveStateReader(
        view, catalog, ArchiveStatePoint.blockEnd(1L, new byte[32], 3L),
        () -> { }, true, 16, 4_096L, context, blockNum -> {
          lookups.incrementAndGet();
          return Optional.of(range);
        });

    byte[] actual = reader.getBlockHash(0L);
    actual[31] = 0;

    assertEquals(0x2a, reader.getBlockHash(0L)[31]);
    assertEquals(2, lookups.get());
    assertEquals(2L, context.getLogicalReads());
    assertEquals(0L, context.getBackendReads());
    reader.close();
  }

  @Test
  public void missingBlockHashRangeFailsAsCorruptForCompleteHistory() {
    QueryContext context = new QueryContext(ArchiveQueryLimits.unlimited());
    DefaultArchiveStateReader reader = new DefaultArchiveStateReader(
        mock(ArchiveTemporalReadView.class), catalog,
        ArchiveStatePoint.blockEnd(1L, new byte[32], 3L),
        () -> { }, true, 16, 4_096L, context, blockNum -> Optional.empty());

    ArchiveReaderException failure = assertThrows(
        ArchiveReaderException.class, () -> reader.getBlockHash(0L));

    assertEquals(ArchiveReaderException.Reason.CORRUPT_INDEX, failure.getReason());
    assertEquals(0L, context.getBackendReads());
    reader.close();
  }

  @Test
  public void internalOnlyDynamicPropertiesAreRejectedByReaderPolicy() {
    byte[] header = "latest_block_header_number".getBytes(StandardCharsets.US_ASCII);
    put(ArchiveDomain.DYNAMIC_PROPERTIES, header, DomainValue.present(ByteArray.fromLong(1L)), 5);
    ArchiveStateReader reader = readerAt(5);

    ArchiveReaderException headerEx = assertThrows(ArchiveReaderException.class,
        () -> reader.getDynamicProperty(header));
    assertEquals(ArchiveReaderException.Reason.DOMAIN_UNSUPPORTED, headerEx.getReason());

    byte[] unknown = "SOME_FUTURE_KEY".getBytes(StandardCharsets.US_ASCII);
    ArchiveReaderException unknownEx = assertThrows(ArchiveReaderException.class,
        () -> reader.getDynamicProperty(unknown));
    assertEquals(ArchiveReaderException.Reason.DOMAIN_UNSUPPORTED, unknownEx.getReason());

    byte[] internalRoot = "BLOCK_HASH_HISTORY_INSTALLED".getBytes(StandardCharsets.US_ASCII);
    ArchiveReaderException internalRootEx = assertThrows(ArchiveReaderException.class,
        () -> reader.getDynamicProperty(internalRoot));
    assertEquals(ArchiveReaderException.Reason.DOMAIN_UNSUPPORTED, internalRootEx.getReason());
  }

  @Test
  public void historicalVmDynamicPropertiesRemainReadableByKeyPolicy() throws Exception {
    byte[] timestamp = "latest_block_header_timestamp".getBytes(StandardCharsets.US_ASCII);
    put(ArchiveDomain.DYNAMIC_PROPERTIES, timestamp,
        DomainValue.present(ByteArray.fromLong(123L)), 5);

    ArchiveReadResult<byte[]> result = readerAt(5).getDynamicProperty(timestamp);

    assertEquals(Status.PRESENT, result.getStatus());
    assertEquals(123L, ByteArray.toLong(result.getValue()));
  }

  private static byte[] addr(int last) {
    byte[] a = new byte[21];
    a[0] = 0x41;
    a[20] = (byte) last;
    return a;
  }

  private static byte[] account(long balance) {
    return Account.newBuilder().setBalance(balance).build().toByteArray();
  }

  private static byte[] contract(int version) {
    return SmartContract.newBuilder().setVersion(version).build().toByteArray();
  }

  private static byte[] contract(int version, byte[] trxHash) {
    return SmartContract.newBuilder()
        .setVersion(version)
        .setTrxHash(ByteString.copyFrom(trxHash))
        .build()
        .toByteArray();
  }

  private static byte[] storageKey(byte[] address, byte[] slot, int version) {
    return ArchiveStorageKeyCodec.contractStorageKey(address, slot, version);
  }

  private static byte[] storageKey(byte[] address, byte[] slot, byte[] trxHash, int version) {
    return ArchiveStorageKeyCodec.contractStorageKey(address, slot, trxHash, version);
  }

  @Test
  public void getAccountResolvesThreeStates() throws Exception {
    put(ArchiveDomain.ACCOUNT, addr(1), DomainValue.present(account(100)), 5);
    put(ArchiveDomain.ACCOUNT, addr(2), DomainValue.present(account(1)),
        DomainValue.tombstone(), 5);
    ArchiveStateReader reader = readerAt(5);
    ArchiveReadResult<AccountCapsule> present = reader.getAccount(addr(1));
    assertEquals(Status.PRESENT, present.getStatus());
    assertEquals(100, present.getValue().getBalance());
    assertEquals(Status.TOMBSTONE, reader.getAccount(addr(2)).getStatus());
    assertEquals(Status.MISSING, reader.getAccount(addr(3)).getStatus());
  }

  @Test
  public void votesAndDelegationReadHistoricalSnapshotWithoutLiveFallback() throws Exception {
    byte[] address = addr(1);
    byte[] delegationKey = "12-witness-vi".getBytes(StandardCharsets.US_ASCII);
    org.tron.protos.Protocol.Votes votes = org.tron.protos.Protocol.Votes.newBuilder()
        .setAddress(ByteString.copyFrom(address))
        .addNewVotes(org.tron.protos.Protocol.Vote.newBuilder()
            .setVoteAddress(ByteString.copyFrom(addr(2)))
            .setVoteCount(7L))
        .build();
    put(ArchiveDomain.VOTES, address, DomainValue.present(votes.toByteArray()), 5L);
    put(ArchiveDomain.DELEGATION, delegationKey,
        DomainValue.present(ByteArray.fromLong(123L)), 5L);

    ArchiveStateReader reader = readerAt(5L);
    assertEquals(votes, reader.getVotes(address).getValue().getInstance());
    assertEquals(123L, ByteArray.toLong(
        reader.getDelegation(delegationKey).getValue()));
    assertEquals(Status.MISSING, reader.getVotes(addr(3)).getStatus());
    assertEquals(Status.MISSING, reader.getDelegation(
        "missing".getBytes(StandardCharsets.US_ASCII)).getStatus());
  }

  @Test
  public void getAccountAssetReadsTrc10Balance() throws Exception {
    byte[] address = addr(1);
    byte[] assetId = "1000001".getBytes(StandardCharsets.US_ASCII);
    put(ArchiveDomain.ACCOUNT_ASSET, Bytes.concat(address, assetId),
        DomainValue.present(ByteArray.fromLong(88L)), 5);

    ArchiveReadResult<byte[]> balance = readerAt(5).getAccountAsset(address, assetId);

    assertEquals(Status.PRESENT, balance.getStatus());
    assertEquals(88L, ByteArray.toLong(balance.getValue()));
    assertEquals(Status.MISSING, readerAt(5).getAccountAsset(addr(2), assetId).getStatus());
  }

  @Test
  public void getAccountAssetsEnumeratesMembershipAtHistoricalPoint() throws Exception {
    byte[] address = addr(1);
    byte[] firstId = "1000001".getBytes(StandardCharsets.US_ASCII);
    byte[] secondId = "1000010".getBytes(StandardCharsets.US_ASCII);
    byte[] otherAddressId = "1000100".getBytes(StandardCharsets.US_ASCII);
    put(ArchiveDomain.ACCOUNT_ASSET, Bytes.concat(address, firstId),
        DomainValue.tombstone(), DomainValue.present(ByteArray.fromLong(88L)), 2L);
    put(ArchiveDomain.ACCOUNT_ASSET, Bytes.concat(address, secondId),
        DomainValue.tombstone(), DomainValue.present(ByteArray.fromLong(99L)), 6L);
    put(ArchiveDomain.ACCOUNT_ASSET, Bytes.concat(addr(2), otherAddressId),
        DomainValue.tombstone(), DomainValue.present(ByteArray.fromLong(777L)), 3L);
    put(ArchiveDomain.ACCOUNT_ASSET, Bytes.concat(address, firstId),
        DomainValue.present(ByteArray.fromLong(88L)), DomainValue.tombstone(), 8L);

    Map<String, Long> atFive = readerAt(5L).getAccountAssets(address);
    Map<String, Long> atSeven = readerAt(7L).getAccountAssets(address);
    Map<String, Long> atNine = readerAt(9L).getAccountAssets(address);

    assertEquals(Collections.singletonMap("1000001", 88L), atFive);
    assertEquals(2, atSeven.size());
    assertEquals(Long.valueOf(88L), atSeven.get("1000001"));
    assertEquals(Long.valueOf(99L), atSeven.get("1000010"));
    assertEquals(Collections.singletonMap("1000010", 99L), atNine);
    assertThrows(UnsupportedOperationException.class,
        () -> atSeven.put("1000100", 1L));
  }

  @Test
  public void getAccountAssetsFailsClosedForMidChainCoverage() {
    ArchiveStateReader reader = new DefaultArchiveStateReader(
        store.openReadView(), catalog,
        ArchiveStatePoint.blockEnd(1L, new byte[] {1}, 5L),
        () -> { }, false);

    ArchiveReaderException failure = assertThrows(
        ArchiveReaderException.class, () -> reader.getAccountAssets(addr(1)));

    assertEquals(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE, failure.getReason());
    reader.close();
  }

  @Test
  public void getAccountAssetsStopsAtBackendReadBudgetDuringMembershipScan() {
    byte[] address = addr(1);
    byte[] assetId = "1000001".getBytes(StandardCharsets.US_ASCII);
    put(ArchiveDomain.ACCOUNT_ASSET, Bytes.concat(address, assetId),
        DomainValue.tombstone(), DomainValue.present(ByteArray.fromLong(88L)), 2L);
    QueryContext context = new QueryContext(ArchiveQueryLimits.builder()
        .maxBackendReadsPerRequest(7L)
        .build());
    ArchiveStateReader reader = new DefaultArchiveStateReader(
        store.openReadView(), catalog,
        ArchiveStatePoint.blockEnd(1L, new byte[] {1}, 5L),
        () -> { }, true, 16, 4_096L, context);

    HistoricalQueryLimitException failure = assertThrows(
        HistoricalQueryLimitException.class, () -> reader.getAccountAssets(address));

    assertEquals(HistoricalQueryLimitException.Limit.BACKEND_READS, failure.getLimit());
    assertEquals(8L, context.getBackendReads());
    assertSame(failure, context.getTerminalException());
    reader.close();
  }

  @Test
  public void getAccountAssetsChargesMaterializedEntriesToVmOverlayBudget() {
    byte[] address = addr(1);
    byte[] assetId = "1000001".getBytes(StandardCharsets.US_ASCII);
    put(ArchiveDomain.ACCOUNT_ASSET, Bytes.concat(address, assetId),
        DomainValue.tombstone(), DomainValue.present(ByteArray.fromLong(88L)), 2L);
    QueryContext context = new QueryContext(ArchiveQueryLimits.builder()
        .maxVmOverlayBytes(200L)
        .build());
    ArchiveStateReader reader = new DefaultArchiveStateReader(
        store.openReadView(), catalog,
        ArchiveStatePoint.blockEnd(1L, new byte[] {1}, 5L),
        () -> { }, true, 16, 4_096L, context);

    HistoricalQueryLimitException failure = assertThrows(
        HistoricalQueryLimitException.class, () -> reader.getAccountAssets(address));

    assertEquals(HistoricalQueryLimitException.Limit.VM_OVERLAY_BYTES, failure.getLimit());
    assertSame(failure, context.getTerminalException());
    reader.close();
  }

  @Test
  public void corruptAccountAssetLengthThrows() {
    byte[] address = addr(1);
    byte[] assetId = "1000001".getBytes(StandardCharsets.US_ASCII);
    put(ArchiveDomain.ACCOUNT_ASSET, Bytes.concat(address, assetId),
        DomainValue.present(new byte[] {1}), 5);

    ArchiveReaderException e = assertThrows(ArchiveReaderException.class,
        () -> readerAt(5).getAccountAsset(address, assetId));
    assertEquals(ArchiveReaderException.Reason.CORRUPT_VALUE, e.getReason());
  }

  @Test
  public void noFallbackToLatestAndInclusiveAfter() throws Exception {
    put(ArchiveDomain.ACCOUNT, addr(1), DomainValue.present(account(100)), 5);
    // At txNum 4 the account did not yet exist: the prev-value model reports it as absent
    // (TOMBSTONE), NOT the live/latest value -- the reader never leaks current state into the gap.
    // (L6/L8 render TOMBSTONE and MISSING identically, so the observable RPC result is unchanged.)
    assertEquals(Status.TOMBSTONE, readerAt(4).getAccount(addr(1)).getStatus());
    assertEquals(Status.PRESENT, readerAt(5).getAccount(addr(1)).getStatus());
  }

  @Test
  public void rawMemoUsesContentKeysAndDefensiveValueCopies() throws Exception {
    AtomicInteger backendReads = new AtomicInteger();
    AtomicInteger closes = new AtomicInteger();
    byte[] code = new byte[] {1, 2, 3};
    ArchiveTemporalReadView view = new ArchiveTemporalReadView() {
      @Override
      public Optional<DomainValue> getAsOf(ArchiveDomain domain, byte[] canonicalKey, long txNum) {
        backendReads.incrementAndGet();
        return Optional.of(DomainValue.present(code));
      }

      @Override
      public Optional<DomainValue> latest(ArchiveDomain domain, byte[] canonicalKey) {
        return Optional.empty();
      }

      @Override
      public void close() {
        closes.incrementAndGet();
      }
    };
    DefaultArchiveStateReader reader = new DefaultArchiveStateReader(
        view, catalog, ArchiveStatePoint.blockEnd(1, new byte[] {1}, 5));

    byte[] first = reader.getCode(addr(1)).getValue();
    first[0] = 9;
    byte[] sameContentAddress = addr(1);
    assertArrayEquals(code, reader.getCode(sameContentAddress).getValue());
    assertEquals(1, backendReads.get());

    assertNonOwnerRejected(() -> reader.getCode(addr(1)));
    assertEquals(1, backendReads.get());

    assertNonOwnerRejected(reader::close);
    assertEquals(0, closes.get());

    reader.close();
    reader.close();
    assertEquals(1, closes.get());
  }

  @Test
  public void typedProtoReadsReparseRawMemoAndIsolateMutableCapsules()
      throws Exception {
    byte[] address = addr(1);
    put(ArchiveDomain.ACCOUNT, address, DomainValue.present(account(100L)), 5L);
    put(ArchiveDomain.CONTRACT, address, DomainValue.present(contract(2)), 5L);
    byte[] contractState = ContractState.newBuilder()
        .setEnergyUsage(7L)
        .build()
        .toByteArray();
    put(ArchiveDomain.CONTRACT_STATE, address, DomainValue.present(contractState), 5L);
    ArchiveStateReader reader = readerAt(5L);

    AccountCapsule firstAccount = reader.getAccount(address).getValue();
    Account originalAccount = firstAccount.getInstance();
    firstAccount.setBalance(999L);
    AccountCapsule secondAccount = reader.getAccount(address).getValue();
    assertNotSame(firstAccount, secondAccount);
    assertNotSame(originalAccount, secondAccount.getInstance());
    assertEquals(100L, secondAccount.getBalance());

    org.tron.core.capsule.ContractCapsule firstContract =
        reader.getContract(address).getValue();
    org.tron.core.capsule.ContractCapsule secondContract =
        reader.getContract(address).getValue();
    assertNotSame(firstContract, secondContract);
    assertNotSame(firstContract.getInstance(), secondContract.getInstance());

    ContractStateCapsule firstState = reader.getContractState(address).getValue();
    ContractStateCapsule secondState = reader.getContractState(address).getValue();
    assertNotSame(firstState, secondState);
    assertNotSame(firstState.getInstance(), secondState.getInstance());
    reader.close();
  }

  @Test
  public void mapHeavyAccountKeepsOnlyByteBoundedRawMemo() throws Exception {
    byte[] address = addr(1);
    Account.Builder account = Account.newBuilder().setBalance(100L);
    for (int i = 0; i < 1_000; i++) {
      account.putFreeAssetNetUsageV2("asset-" + i, i);
    }
    byte[] encoded = account.build().toByteArray();
    AtomicInteger backendReads = new AtomicInteger();
    ArchiveTemporalReadView view = new ArchiveTemporalReadView() {
      @Override
      public Optional<DomainValue> getAsOf(ArchiveDomain domain, byte[] key, long txNum) {
        backendReads.incrementAndGet();
        return Optional.of(DomainValue.present(encoded));
      }

      @Override
      public Optional<DomainValue> latest(ArchiveDomain domain, byte[] key) {
        return Optional.empty();
      }

      @Override
      public void close() {
      }
    };
    long memoLimit = encoded.length + 512L;
    DefaultArchiveStateReader reader = new DefaultArchiveStateReader(
        view, catalog, ArchiveStatePoint.blockEnd(1, new byte[] {1}, 5L),
        () -> { }, true, 4, memoLimit);

    Account first = reader.getAccount(address).getValue().getInstance();
    Account second = reader.getAccount(address).getValue().getInstance();

    assertEquals(1, backendReads.get());
    assertNotSame(first, second);
    assertEquals(1_000, second.getFreeAssetNetUsageV2Count());
    reader.close();
  }

  @Test
  public void closeRunsReleaseCallbackAfterViewErrorAndPreservesPrimaryFailure() {
    AssertionError viewFailure = new AssertionError("view close failed");
    AssertionError releaseFailure = new AssertionError("release failed");
    AtomicInteger releases = new AtomicInteger();
    ArchiveTemporalReadView view = new ArchiveTemporalReadView() {
      @Override
      public Optional<DomainValue> getAsOf(ArchiveDomain domain, byte[] canonicalKey, long txNum) {
        return Optional.empty();
      }

      @Override
      public Optional<DomainValue> latest(ArchiveDomain domain, byte[] canonicalKey) {
        return Optional.empty();
      }

      @Override
      public void close() {
        throw viewFailure;
      }
    };
    DefaultArchiveStateReader reader = new DefaultArchiveStateReader(
        view, catalog, ArchiveStatePoint.blockEnd(1, new byte[] {1}, 5),
        () -> {
          releases.incrementAndGet();
          throw releaseFailure;
        });

    AssertionError thrown = assertThrows(AssertionError.class, reader::close);

    assertSame(viewFailure, thrown);
    assertEquals(1, releases.get());
    assertEquals(1, thrown.getSuppressed().length);
    assertSame(releaseFailure, thrown.getSuppressed()[0]);
  }

  @Test
  public void rawMemoEvictsLeastRecentlyUsedAtEntryAndByteBudgetsWithoutChangingResults()
      throws Exception {
    AtomicInteger entryLimitedReads = new AtomicInteger();
    ArchiveTemporalReadView entryLimitedView = countingPresentView(entryLimitedReads);
    DefaultArchiveStateReader entryLimited = new DefaultArchiveStateReader(
        entryLimitedView, catalog, ArchiveStatePoint.blockEnd(1, new byte[] {1}, 5),
        () -> { }, true, 1, 1_024,
        new QueryContext(ArchiveQueryLimits.unlimited()));

    assertArrayEquals(new byte[] {7}, entryLimited.getCode(addr(1)).getValue());
    assertArrayEquals(new byte[] {7}, entryLimited.getCode(addr(1)).getValue());
    assertArrayEquals(new byte[] {7}, entryLimited.getCode(addr(2)).getValue());
    assertArrayEquals(new byte[] {7}, entryLimited.getCode(addr(2)).getValue());
    assertEquals(2, entryLimitedReads.get());
    entryLimited.close();

    AtomicInteger byteLimitedReads = new AtomicInteger();
    DefaultArchiveStateReader byteLimited = new DefaultArchiveStateReader(
        countingPresentView(byteLimitedReads), catalog,
        ArchiveStatePoint.blockEnd(1, new byte[] {1}, 5), () -> { }, true, 10, 0,
        new QueryContext(ArchiveQueryLimits.unlimited()));

    assertArrayEquals(new byte[] {7}, byteLimited.getCode(addr(3)).getValue());
    assertArrayEquals(new byte[] {7}, byteLimited.getCode(addr(3)).getValue());
    assertEquals(2, byteLimitedReads.get());
    byteLimited.close();
  }

  private static ArchiveTemporalReadView countingPresentView(AtomicInteger reads) {
    return new ArchiveTemporalReadView() {
      @Override
      public Optional<DomainValue> getAsOf(ArchiveDomain domain, byte[] canonicalKey, long txNum) {
        reads.incrementAndGet();
        return Optional.of(DomainValue.present(new byte[] {7}));
      }

      @Override
      public Optional<DomainValue> latest(ArchiveDomain domain, byte[] canonicalKey) {
        return Optional.empty();
      }

      @Override
      public void close() {
      }
    };
  }

  @Test
  public void everyPublicReadChecksOwnerBeforeValidationOrShortCircuit() throws Exception {
    DefaultArchiveStateReader reader = new DefaultArchiveStateReader(
        store, catalog, ArchiveStatePoint.blockEnd(1, new byte[] {1}, 5));

    assertNonOwnerRejected(reader::getPoint);
    assertNonOwnerRejected(() -> reader.getAccount(new byte[0]));
    assertNonOwnerRejected(() -> reader.getAccountAsset(addr(1), null));
    assertNonOwnerRejected(() -> reader.getContract(new byte[0]));
    assertNonOwnerRejected(() -> reader.getContractState(new byte[0]));
    assertNonOwnerRejected(() -> reader.getCode(new byte[0]));
    assertNonOwnerRejected(() -> reader.getStorage(new byte[0], new byte[0]));
    assertNonOwnerRejected(() -> reader.getDynamicProperty(new byte[0]));

    reader.close();
  }

  @Test
  public void midChainReaderUsesCapturedPrevButLeavesUncapturedKeysMissing() throws Exception {
    byte[] existing = "TOTAL_NET_LIMIT".getBytes(StandardCharsets.US_ASCII);
    byte[] gap = "TOTAL_ENERGY_LIMIT".getBytes(StandardCharsets.US_ASCII);
    put(ArchiveDomain.DYNAMIC_PROPERTIES, existing,
        DomainValue.present(new byte[] {0x30}), DomainValue.present(new byte[] {0x31}), 6);

    ArchiveReadResult<byte[]> beforeFirstCapture = readerAt(5).getDynamicProperty(existing);
    assertEquals(Status.PRESENT, beforeFirstCapture.getStatus());
    assertArrayEquals(new byte[] {0x30}, beforeFirstCapture.getValue());
    assertArrayEquals(new byte[] {0x31}, readerAt(6).getDynamicProperty(existing).getValue());
    assertArrayEquals(new byte[] {0x31}, readerAt(100).getDynamicProperty(existing).getValue());
    assertEquals(Status.MISSING, readerAt(5).getDynamicProperty(gap).getStatus());
  }

  @Test
  public void getCodeAndStorage() throws Exception {
    put(ArchiveDomain.CODE, addr(1), DomainValue.present(new byte[] {0x60, (byte) 0x80}), 5);
    put(ArchiveDomain.CONTRACT, addr(1), DomainValue.present(contract(0)), 5);
    byte[] slot = new byte[32];
    slot[31] = 7;
    byte[] word = new byte[32];
    word[31] = 9;
    put(ArchiveDomain.CONTRACT_STORAGE, storageKey(addr(1), slot, 0),
        DomainValue.present(word), 5);
    ArchiveStateReader reader = readerAt(5);
    assertArrayEquals(new byte[] {0x60, (byte) 0x80}, reader.getCode(addr(1)).getValue());
    assertArrayEquals(word, reader.getStorage(addr(1), slot).getValue());
    assertEquals(Status.MISSING, reader.getStorage(addr(2), slot).getStatus());
  }

  @Test
  public void getStorageUsesHistoricalContractVersion() throws Exception {
    byte[] address = addr(1);
    byte[] slot = new byte[32];
    slot[31] = 7;
    byte[] v0Word = new byte[32];
    v0Word[31] = 9;
    byte[] v1Word = new byte[32];
    v1Word[31] = 10;
    put(ArchiveDomain.CONTRACT, address, DomainValue.present(contract(1)), 5);
    put(ArchiveDomain.CONTRACT_STORAGE, storageKey(address, slot, 0),
        DomainValue.present(v0Word), 5);
    put(ArchiveDomain.CONTRACT_STORAGE, storageKey(address, slot, 1),
        DomainValue.present(v1Word), 5);

    assertArrayEquals(v1Word, readerAt(5).getStorage(address, slot).getValue());
  }

  @Test
  public void nonVersionOneLogicalSlotAliasesResolveTheSamePhysicalRow() throws Exception {
    byte[] address = addr(1);
    byte[] first = new byte[32];
    byte[] alias = new byte[32];
    first[0] = 1;
    alias[0] = 2;
    first[31] = alias[31] = 7;
    byte[] word = new byte[32];
    word[31] = 9;
    put(ArchiveDomain.CONTRACT, address, DomainValue.present(contract(2)), 5);
    put(ArchiveDomain.CONTRACT_STORAGE, storageKey(address, first, 2),
        DomainValue.present(word), 5);

    assertArrayEquals(storageKey(address, first, 2), storageKey(address, alias, 2));
    assertArrayEquals(word, readerAt(5).getStorage(address, alias).getValue());
  }

  @Test
  public void getStorageDoesNotFallbackToAlternateVersionWhenPrimaryIsMissing() throws Exception {
    byte[] address = addr(1);
    byte[] slot = new byte[32];
    slot[31] = 7;
    byte[] v0Word = new byte[32];
    v0Word[31] = 9;
    put(ArchiveDomain.CONTRACT, address, DomainValue.present(contract(1)), 5);
    put(ArchiveDomain.CONTRACT_STORAGE, storageKey(address, slot, 0),
        DomainValue.present(v0Word), 5);

    assertEquals(Status.MISSING, readerAt(5).getStorage(address, slot).getStatus());
  }

  @Test
  public void getStoragePrimaryTombstoneWinsOverAlternatePresent() throws Exception {
    byte[] address = addr(1);
    byte[] slot = new byte[32];
    slot[31] = 7;
    byte[] v0Word = new byte[32];
    v0Word[31] = 9;
    put(ArchiveDomain.CONTRACT, address, DomainValue.present(contract(1)), 5);
    put(ArchiveDomain.CONTRACT_STORAGE, storageKey(address, slot, 1),
        DomainValue.present(new byte[] {1}), DomainValue.tombstone(), 5);
    put(ArchiveDomain.CONTRACT_STORAGE, storageKey(address, slot, 0),
        DomainValue.present(v0Word), 5);

    assertEquals(Status.TOMBSTONE, readerAt(5).getStorage(address, slot).getStatus());
  }

  @Test
  public void getStorageDoesNotReadStorageForDeletedContract() throws Exception {
    byte[] address = addr(1);
    byte[] slot = new byte[32];
    byte[] word = new byte[32];
    word[31] = 9;
    put(ArchiveDomain.CONTRACT, address, DomainValue.present(contract(0)),
        DomainValue.tombstone(), 5);
    put(ArchiveDomain.CONTRACT_STORAGE, storageKey(address, slot, 0),
        DomainValue.present(word), 5);

    assertEquals(Status.TOMBSTONE, readerAt(5).getStorage(address, slot).getStatus());
  }

  @Test
  public void getStorageUsesHistoricalDeploymentHash() throws Exception {
    byte[] address = addr(1);
    byte[] slot = new byte[32];
    slot[31] = 7;
    byte[] oldNamespaceWord = new byte[32];
    oldNamespaceWord[31] = 1;
    byte[] create2Word = new byte[32];
    create2Word[31] = 2;
    byte[] trxHash = new byte[32];
    trxHash[31] = 9;
    put(ArchiveDomain.CONTRACT, address, DomainValue.present(contract(0, trxHash)), 5);
    put(ArchiveDomain.CONTRACT_STORAGE, storageKey(address, slot, 0),
        DomainValue.present(oldNamespaceWord), 5);
    put(ArchiveDomain.CONTRACT_STORAGE, storageKey(address, slot, trxHash, 0),
        DomainValue.present(create2Word), 5);

    assertArrayEquals(create2Word, readerAt(5).getStorage(address, slot).getValue());
  }

  @Test
  public void getContractParsesArchivedContract() throws Exception {
    byte[] contract = SmartContract.newBuilder().setBytecode(ByteString.copyFromUtf8("X"))
        .build().toByteArray();
    put(ArchiveDomain.CONTRACT, addr(1), DomainValue.present(contract), 5);
    assertEquals(Status.PRESENT, readerAt(5).getContract(addr(1)).getStatus());
  }

  @Test
  public void getContractStateParsesArchivedState() throws Exception {
    byte[] state = ContractState.newBuilder()
        .setEnergyFactor(123L)
        .setUpdateCycle(7L)
        .build()
        .toByteArray();
    put(ArchiveDomain.CONTRACT_STATE, addr(1), DomainValue.present(state), 5);

    ArchiveReadResult<ContractStateCapsule> result = readerAt(5).getContractState(addr(1));

    assertEquals(Status.PRESENT, result.getStatus());
    assertEquals(123L, result.getValue().getEnergyFactor());
    assertEquals(7L, result.getValue().getUpdateCycle());
    assertEquals(Status.MISSING, readerAt(5).getContractState(addr(2)).getStatus());
  }

  @Test
  public void badInputIsIllegalArgumentNotArchiveError() {
    assertThrows(IllegalArgumentException.class, () -> readerAt(5).getAccount(new byte[5]));
    assertThrows(IllegalArgumentException.class,
        () -> readerAt(5).getStorage(addr(1), new byte[8]));
  }

  @Test
  public void corruptValueThrowsCodecErrorNotMissing() {
    put(ArchiveDomain.ACCOUNT, addr(1), DomainValue.present(new byte[] {(byte) 0xff, 0x01}), 5);
    ArchiveReaderException e = assertThrows(ArchiveReaderException.class,
        () -> readerAt(5).getAccount(addr(1)));
    assertEquals(ArchiveReaderException.Reason.CODEC_ERROR, e.getReason());
  }

  @Test
  public void getAccountAssetMissingWhenAssetIdNullOrEmpty() throws Exception {
    // A null or zero-length assetId short-circuits to MISSING before any store lookup, so the
    // reader never builds a bare-address ACCOUNT_ASSET key.
    assertEquals(Status.MISSING,
        readerAt(5).getAccountAsset(addr(1), new byte[0]).getStatus());
    assertEquals(Status.MISSING,
        readerAt(5).getAccountAsset(addr(1), null).getStatus());
  }

  @Test
  public void getStorageValueExceedingThirtyTwoBytesThrowsCorruptValue() {
    // The post-read guard maps an over-length (>32-byte) storage word to CORRUPT_VALUE. The
    // contract row must be present so getContract resolves before the storage read.
    byte[] address = addr(1);
    byte[] slot = new byte[32];
    slot[31] = 7;
    put(ArchiveDomain.CONTRACT, address, DomainValue.present(contract(0)), 5);
    put(ArchiveDomain.CONTRACT_STORAGE, storageKey(address, slot, 0),
        DomainValue.present(new byte[33]), 5);

    ArchiveReaderException e = assertThrows(ArchiveReaderException.class,
        () -> readerAt(5).getStorage(address, slot));
    assertEquals(ArchiveReaderException.Reason.CORRUPT_VALUE, e.getReason());
  }

  @Test
  public void getRawRejectsDomainAbsentFromCatalog() {
    // getRaw's descriptor guard (distinct from the getDynamicProperty key-policy guard): a catalog
    // with no descriptor for the queried domain fails closed as DOMAIN_UNSUPPORTED, never reaching
    // the temporal store.
    ArchiveDomainCatalog emptyCatalog = mock(ArchiveDomainCatalog.class);
    ArchiveStateReader reader = new DefaultArchiveStateReader(store, emptyCatalog,
        ArchiveStatePoint.blockEnd(1, new byte[] {1}, 5));

    ArchiveReaderException e = assertThrows(ArchiveReaderException.class,
        () -> reader.getAccount(addr(1)));
    assertEquals(ArchiveReaderException.Reason.DOMAIN_UNSUPPORTED, e.getReason());
  }

  @Test
  public void temporalStoreFailureBecomesInternalIo() {
    // A RuntimeException from the underlying temporal store (e.g. a RocksDB read failure) is
    // wrapped as INTERNAL_IO, never leaked as a raw runtime error or a MISSING result.
    ArchiveTemporalStore throwingStore = mock(ArchiveTemporalStore.class);
    when(throwingStore.getAsOf(any(), any(), anyLong()))
        .thenThrow(new RuntimeException("temporal read failed"));
    ArchiveStateReader reader = new DefaultArchiveStateReader(throwingStore, catalog,
        ArchiveStatePoint.blockEnd(1, new byte[] {1}, 5));

    ArchiveReaderException e = assertThrows(ArchiveReaderException.class,
        () -> reader.getAccount(addr(1)));
    assertEquals(ArchiveReaderException.Reason.INTERNAL_IO, e.getReason());
  }

  @Test
  public void nativeRocksCorruptionBecomesCorruptValue() {
    ArchiveTemporalStore throwingStore = mock(ArchiveTemporalStore.class);
    RocksDBException corruption = new RocksDBException(
        new org.rocksdb.Status(
            org.rocksdb.Status.Code.Corruption,
            org.rocksdb.Status.SubCode.None,
            "checksum mismatch"));
    when(throwingStore.getAsOf(any(), any(), anyLong()))
        .thenThrow(new ArchiveException("archive read failed", corruption));
    ArchiveStateReader reader = new DefaultArchiveStateReader(throwingStore, catalog,
        ArchiveStatePoint.blockEnd(1, new byte[] {1}, 5));

    ArchiveReaderException e = assertThrows(ArchiveReaderException.class,
        () -> reader.getAccount(addr(1)));
    assertEquals(ArchiveReaderException.Reason.CORRUPT_VALUE, e.getReason());
  }

  @Test
  public void explicitPersistentCorruptionWinsOverNestedIoCause() {
    ArchiveTemporalStore throwingStore = mock(ArchiveTemporalStore.class);
    RocksDBException ioFailure = new RocksDBException("diagnostic I/O cause");
    when(throwingStore.getAsOf(any(), any(), anyLong())).thenThrow(
        new ArchivePersistentStateCorruptionException("persistent corruption", ioFailure));
    ArchiveStateReader reader = new DefaultArchiveStateReader(throwingStore, catalog,
        ArchiveStatePoint.blockEnd(1, new byte[] {1}, 5));

    ArchiveReaderException e = assertThrows(ArchiveReaderException.class,
        () -> reader.getAccount(addr(1)));
    assertEquals(ArchiveReaderException.Reason.CORRUPT_VALUE, e.getReason());
  }

  private static void assertNonOwnerRejected(ThrowingRunnable action) throws InterruptedException {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread thread = new Thread(() -> {
      try {
        action.run();
      } catch (Throwable t) {
        failure.set(t);
      }
    }, "archive-reader-non-owner-test");
    thread.start();
    thread.join();

    assertNotNull(failure.get());
    assertEquals(ArchiveException.class, failure.get().getClass());
    assertEquals("archive state reader used from a non-owner thread",
        failure.get().getMessage());
  }
}
