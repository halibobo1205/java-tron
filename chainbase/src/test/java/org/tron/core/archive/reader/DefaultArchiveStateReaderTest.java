package org.tron.core.archive.reader;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.primitives.Bytes;
import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import org.junit.Before;
import org.junit.Test;
import org.tron.common.utils.ByteArray;
import org.tron.core.ChainBaseManager;
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveSource;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.domain.ArchiveDomainCatalog;
import org.tron.core.archive.domain.DefaultArchiveDomainCatalog;
import org.tron.core.archive.reader.ArchiveReadResult.Status;
import org.tron.core.archive.temporal.ArchiveTemporalStore;
import org.tron.core.archive.temporal.InMemoryArchiveTemporalStore;
import org.tron.core.archive.txnum.ArchiveTxPosition;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.ContractStateCapsule;
import org.tron.core.store.DynamicPropertiesStore;
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
    return readerAt(txNum, ArchiveReadThrough.NONE);
  }

  private ArchiveStateReader readerAt(long txNum, ArchiveReadThrough readThrough) {
    return new DefaultArchiveStateReader(store, catalog,
        ArchiveStatePoint.blockEnd(1, new byte[] {1}, txNum), readThrough);
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
  public void readThroughSuppliesMidChainBaselineWhenTemporalMisses() throws Exception {
    byte[] address = addr(1);
    ArchiveReadThrough readThrough = (domain, key, point) -> {
      if (domain == ArchiveDomain.ACCOUNT && java.util.Arrays.equals(key, address)) {
        return java.util.Optional.of(DomainValue.present(account(77)));
      }
      return java.util.Optional.empty();
    };

    ArchiveReadResult<AccountCapsule> account = readerAt(5, readThrough).getAccount(address);

    assertEquals(Status.PRESENT, account.getStatus());
    assertEquals(77, account.getValue().getBalance());
  }

  @Test
  public void temporalFutureChangeWinsOverReadThrough() throws Exception {
    byte[] address = addr(1);
    put(ArchiveDomain.ACCOUNT, address, DomainValue.present(account(30)),
        DomainValue.present(account(31)), 6);
    ArchiveReadThrough readThrough = (domain, key, point) ->
        java.util.Optional.of(DomainValue.present(account(99)));

    ArchiveReadResult<AccountCapsule> account = readerAt(5, readThrough).getAccount(address);

    assertEquals(Status.PRESENT, account.getStatus());
    assertEquals(30, account.getValue().getBalance());
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
  public void chainBaseReadThroughUsesDynamicGetterDefaultWhenRawRowIsMissing() throws Exception {
    byte[] osaka = "ALLOW_TVM_OSAKA".getBytes(StandardCharsets.US_ASCII);
    ChainBaseManager chainBaseManager = mock(ChainBaseManager.class);
    DynamicPropertiesStore dynamicPropertiesStore = mock(DynamicPropertiesStore.class);
    when(chainBaseManager.getDynamicPropertiesStore()).thenReturn(dynamicPropertiesStore);
    when(dynamicPropertiesStore.getUnchecked(osaka)).thenReturn(null);
    when(dynamicPropertiesStore.getAllowTvmOsaka()).thenReturn(0L);

    ArchiveStateReader reader = readerAt(5,
        new ChainBaseArchiveReadThrough(chainBaseManager, catalog));
    ArchiveReadResult<byte[]> result = reader.getDynamicProperty(osaka);

    assertEquals(Status.PRESENT, result.getStatus());
    assertEquals(0L, ByteArray.toLong(result.getValue()));
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
}
