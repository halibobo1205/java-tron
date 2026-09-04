package org.tron.core.archive.capture;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.common.primitives.Bytes;
import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.tron.common.math.StrictMathWrapper;
import org.tron.core.archive.ArchiveExecutionContext;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveResourceEstimator;
import org.tron.core.archive.ArchiveSource;
import org.tron.core.archive.codec.ContractStorageKeyCodec;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.domain.ArchiveDomainCatalog;
import org.tron.core.archive.domain.ArchiveDomainDescriptor;
import org.tron.core.archive.domain.DefaultArchiveDomainCatalog;
import org.tron.core.archive.domain.DefaultArchiveDomainRegistry;
import org.tron.core.archive.domain.DynamicKeyPolicy;
import org.tron.core.archive.txnum.ArchiveTxPosition;
import org.tron.protos.Protocol.Account;
import org.tron.protos.contract.SmartContractOuterClass.SmartContract;

public class ArchiveCaptureEngineTest {

  private ArchiveExecutionContext context;
  private ArchiveCaptureEngine engine;

  @Before
  public void setUp() {
    context = new ArchiveExecutionContext();
    engine = new ArchiveCaptureEngine(new DefaultArchiveDomainRegistry(),
        new DefaultArchiveDomainCatalog(), new DynamicKeyPolicy(), context);
  }

  private void enterTx(long txNum) {
    context.enter(
        new ArchiveTxPosition(txNum, 7, ArchivePhase.USER_TX, ArchiveSource.NORMAL, 0, null));
  }

  private static byte[] account(long balance) {
    return Account.newBuilder().setBalance(balance).build().toByteArray();
  }

  private static byte[] ascii(String s) {
    return s.getBytes(StandardCharsets.US_ASCII);
  }

  @Test
  public void capturesGenericDomainWithTxNumAndCanonicalEncoding() {
    enterTx(42);
    byte[] addr = new byte[21];
    addr[0] = 0x41;
    engine.capturePut("account", addr, account(50), account(100)); // prev=50 -> new=100
    assertEquals(1, engine.records().size());
    ArchiveChangeRecord r = engine.records().get(0);
    assertEquals(ArchiveDomain.ACCOUNT, r.getDomain());
    assertEquals(42, r.getTxNum());
    assertArrayEquals(addr, r.getCanonicalKey());
    assertFalse(r.getPrevValue().isDeleted()); // prev present (existing account)
    assertFalse(r.getValue().isDeleted());
  }

  @Test
  public void capturesPrevTombstoneForCreatedKey() {
    enterTx(42);
    byte[] addr = new byte[21];
    addr[0] = 0x41;
    engine.capturePut("account", addr, null, account(100)); // null prev = created from absent
    ArchiveChangeRecord r = engine.records().get(0);
    assertTrue(r.getPrevValue().isDeleted()); // prev is a tombstone (did not exist before)
    assertFalse(r.getValue().isDeleted());
  }

  @Test
  public void noCaptureOutsideContext() {
    engine.capturePut("account", new byte[21], null, account(1));
    assertTrue(engine.records().isEmpty());
  }

  @Test
  public void noCaptureForSemanticExcluded() {
    enterTx(1);
    engine.capturePut("account-asset", new byte[8], null, new byte[8]); // semantic IGNORE_RAW
    engine.capturePut("storage-row", new byte[8], null, new byte[32]);  // semantic-only
    engine.capturePut("block", new byte[4], null, new byte[4]);         // excluded
    engine.capturePut("accountTrie", new byte[4], null, new byte[4]);   // derived excluded
    engine.capturePut("checkpoint/account", new byte[4], null, new byte[4]); // prefix excluded
    assertTrue(engine.records().isEmpty());
  }

  @Test
  public void unknownStoreWriteFailsClosedInsideCaptureContext() {
    enterTx(1);

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> engine.capturePut("no-such-store", new byte[21], null, account(1)));

    assertEquals("archive store dbName is not classified: no-such-store", ex.getMessage());
    assertTrue(engine.records().isEmpty());
  }

  @Test
  public void unknownStoreLookupFailsClosedInsideCaptureContext() {
    enterTx(1);

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> engine.capturesStore("no-such-store"));

    assertEquals("archive store dbName is not classified: no-such-store", ex.getMessage());
  }

  @Test
  public void capturesStoreSpecificContractDomain() {
    // STORE_SPECIFIC stores (contract) bypass the base put and capture from their own hook.
    enterTx(3);
    byte[] addr = new byte[21];
    byte[] contract = SmartContract.newBuilder()
        .setBytecode(ByteString.copyFromUtf8("6080")).build().toByteArray();
    engine.capturePut("contract", addr, null, contract);
    assertEquals(1, engine.records().size());
    assertEquals(ArchiveDomain.CONTRACT, engine.records().get(0).getDomain());
  }

  @Test
  public void dynamicPropertiesAllowlistFiltersByKeyPolicy() {
    enterTx(5);
    engine.capturePut("properties", ascii("ENERGY_FEE"), null, new byte[] {1}); // root -> capture
    engine.capturePut("properties", ascii("ABI_MOVE_DONE"), null, new byte[] {1}); // NO_ARCHIVE
    engine.capturePut(
        "properties", ascii("TOKEN_UPDATE_DONE"), null, new byte[] {1}); // NO_ARCHIVE
    engine.capturePut(
        "properties", ascii("BLOCK_FILLED_SLOTS"), null, new byte[] {1}); // NO_ARCHIVE
    engine.capturePut(
        "properties", ascii("BURN_TRX_AMOUNT"), null, new byte[] {1}); // NO_ARCHIVE
    // Unknown keys are not root-eligible, but keep diagnostic history.
    engine.capturePut("properties", ascii("SOME_FUTURE_KEY"), null, new byte[] {1});

    assertEquals(2, engine.records().size());
    assertEquals(ArchiveDomain.DYNAMIC_PROPERTIES, engine.records().get(0).getDomain());
    assertArrayEquals(ascii("ENERGY_FEE"), engine.records().get(0).getCanonicalKey());
    assertEquals(ArchiveDomain.DYNAMIC_PROPERTIES, engine.records().get(1).getDomain());
    assertArrayEquals(ascii("SOME_FUTURE_KEY"), engine.records().get(1).getCanonicalKey());
  }

  @Test
  public void capturesStoreReflectsBinding() {
    assertTrue(engine.capturesStore("account"));    // GENERIC_TRON_STORE
    assertTrue(engine.capturesStore("contract"));   // STORE_SPECIFIC
    assertTrue(engine.capturesStore("properties")); // GENERIC_TRON_STORE_ALLOWLIST
    assertTrue(engine.capturesStore("properties", ascii("ENERGY_FEE")));
    assertTrue(engine.capturesStore("properties", ascii("SOME_FUTURE_KEY")));
    assertFalse(engine.capturesStore("properties", ascii("ABI_MOVE_DONE")));
    assertFalse(engine.capturesStore("properties", ascii("TOKEN_UPDATE_DONE")));
    assertFalse(engine.capturesStore("properties", ascii("BLOCK_FILLED_SLOTS")));
    assertFalse(engine.capturesStore("properties", ascii("BURN_TRX_AMOUNT")));
    assertFalse(engine.capturesStore("storage-row")); // SEMANTIC_ONLY (captured by semantic hook)
    assertFalse(engine.capturesStore("block"));       // excluded
    assertFalse(engine.capturesStore("accountTrie")); // derived excluded
    assertFalse(engine.capturesStore("checkpoint/account")); // prefix excluded
  }

  @Test
  public void captureDeleteRecordsTombstone() {
    enterTx(9);
    engine.captureDelete("account", new byte[21], account(5)); // prev present, deleting it
    assertEquals(1, engine.records().size());
    ArchiveChangeRecord r = engine.records().get(0);
    assertFalse(r.getPrevValue().isDeleted()); // prev was a real value
    assertTrue(r.getValue().isDeleted());      // new value is a tombstone (deleted)
  }

  @Test
  public void clearEmptiesBuffer() {
    enterTx(1);
    engine.capturePut("account", new byte[21], null, account(1));
    engine.clear();
    assertTrue(engine.records().isEmpty());
    assertEquals(0L, engine.capturedPayloadBytes());
  }

  @Test
  public void captureByteWatermarkUsesPipelineEstimateBeforeAllocation() {
    ArchiveCaptureEngine bounded = new ArchiveCaptureEngine(
        new DefaultArchiveDomainRegistry(), new DefaultArchiveDomainCatalog(),
        new DynamicKeyPolicy(), context, 10L, 600L);
    enterTx(1);

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> bounded.capturePut("account", new byte[21], null, account(1)));

    assertTrue(failure.getMessage().contains("pipeline resource watermark"));
    assertEquals(0, bounded.rawRecordCount());
    assertTrue(bounded.records().isEmpty());
  }

  @Test
  public void captureBudgetIncludesResourcesRetainedByOlderBlocks() {
    byte[] account = account(1);
    long oneRecord = org.tron.core.archive.ArchiveResourceEstimator
        .estimatedRawRecordPipelineBytes(21, 0, account.length);
    ArchiveCaptureEngine bounded = new ArchiveCaptureEngine(
        new DefaultArchiveDomainRegistry(), new DefaultArchiveDomainCatalog(),
        new DynamicKeyPolicy(), context, 10L, oneRecord + 99L);
    bounded.beginBlockCapture(100L);
    enterTx(1);

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> bounded.capturePut("account", new byte[21], null, account));

    assertTrue(failure.getMessage().contains("pipeline resource watermark"));
    assertEquals(0, bounded.rawRecordCount());
  }

  @Test
  public void repeatedSameKeyUsesCoalescedResourcePeak() {
    byte[] first = account(1L);
    byte[] second = account(2L);
    long firstPipeline = ArchiveResourceEstimator.estimatedRawRecordPipelineBytes(
        21, 0, first.length);
    long retainedFirst = ArchiveResourceEstimator.estimatedRecordRetainedBytes(
        21, 0, first.length);
    long secondPipeline = ArchiveResourceEstimator.estimatedRawRecordPipelineBytes(
        21, first.length, second.length);
    long hardBytes = StrictMathWrapper.max(firstPipeline, retainedFirst + secondPipeline);
    assertTrue(firstPipeline + secondPipeline > hardBytes);
    ArchiveCaptureEngine bounded = new ArchiveCaptureEngine(
        new DefaultArchiveDomainRegistry(), new DefaultArchiveDomainCatalog(),
        new DynamicKeyPolicy(), context, 10L, hardBytes);
    enterTx(1L);
    byte[] key = new byte[21];
    key[0] = 0x41;

    bounded.capturePut("account", key, null, first);
    bounded.capturePut("account", key, first, second);

    assertEquals(2, bounded.rawRecordCount());
    assertEquals(1, bounded.records().size());
    assertArrayEquals(second, bounded.records().get(0).getValue().getValue());
    assertTrue(bounded.rawRecordBytes() <= hardBytes);
    assertEquals(21L + second.length, bounded.capturedPayloadBytes());
  }

  @Test
  public void pipelineWatermarkRejectsInvalidProtoBeforeCodecParsing() {
    ArchiveCaptureEngine bounded = new ArchiveCaptureEngine(
        new DefaultArchiveDomainRegistry(), new DefaultArchiveDomainCatalog(),
        new DynamicKeyPolicy(), context, 10L, 1_000L);
    enterTx(1);
    byte[] invalidAccount = new byte[128];
    java.util.Arrays.fill(invalidAccount, (byte) 0xff);

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> bounded.capturePut("account", new byte[21], null, invalidAccount));

    assertTrue(failure.getMessage().contains("pipeline resource watermark"));
    assertFalse(failure.getMessage().contains("valid Account proto"));
    assertEquals(0, bounded.rawRecordCount());
  }

  @Test
  public void previousValueReadMetricsAreAggregatedAndClearedPerBlock() {
    engine.recordPreviousValueRead(Long.MIN_VALUE, true);
    engine.recordPreviousValueRead(Long.MIN_VALUE, true);
    engine.recordPreviousValueRead(Long.MIN_VALUE, false);

    assertEquals(2L, engine.previousValueReads());
    assertEquals(1L, engine.previousValueReadFailures());
    assertEquals(0L, engine.previousValueReadNanos());

    engine.clear();

    assertEquals(0L, engine.previousValueReads());
    assertEquals(0L, engine.previousValueReadFailures());
    assertEquals(0L, engine.previousValueReadNanos());
  }

  private static byte[] acct(Object... assetIdThenBalance) {
    Account.Builder b = Account.newBuilder();
    for (int i = 0; i < assetIdThenBalance.length; i += 2) {
      b.putAssetV2((String) assetIdThenBalance[i], (Long) assetIdThenBalance[i + 1]);
    }
    return b.build().toByteArray();
  }

  @Test
  public void accountAssetCapturesOnlyValueChangedAssets() {
    enterTx(11);
    byte[] addr = new byte[21];
    byte[] old = acct("1000001", 100L, "1000002", 5L);
    byte[] now = acct("1000001", 150L, "1000002", 5L); // only 1000001 changed
    engine.captureAccountAsset(addr, old, now);
    assertEquals(1, engine.records().size());
    ArchiveChangeRecord r = engine.records().get(0);
    assertEquals(ArchiveDomain.ACCOUNT_ASSET, r.getDomain());
    assertEquals(11, r.getTxNum());
    assertFalse(r.getPrevValue().isDeleted()); // prev balance 100 (present)
    assertFalse(r.getValue().isDeleted());     // new balance 150 (present)
    assertArrayEquals(Bytes.concat(addr, ascii("1000001")), r.getCanonicalKey());
  }

  @Test
  public void accountAssetInputIsBudgetedBeforeProtoParsing() {
    ArchiveCaptureEngine bounded = new ArchiveCaptureEngine(
        new DefaultArchiveDomainRegistry(), new DefaultArchiveDomainCatalog(),
        new DynamicKeyPolicy(), context, 10L, 2_000L);
    enterTx(1);
    byte[] invalidAccount = new byte[256];
    java.util.Arrays.fill(invalidAccount, (byte) 0xff);

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> bounded.captureAccountAsset(new byte[21], null, invalidAccount));

    assertTrue(failure.getMessage().contains("transient resource watermark"));
    assertFalse(failure.getMessage().contains("valid Account proto"));
    assertEquals(0, bounded.rawRecordCount());
  }

  @Test
  public void accountAssetTombstoneWhenBalanceDropsToZero() {
    enterTx(1);
    engine.captureAccountAsset(new byte[21], acct("1", 9L), acct());
    assertEquals(1, engine.records().size());
    ArchiveChangeRecord r = engine.records().get(0);
    assertFalse(r.getPrevValue().isDeleted()); // prev balance 9 (present)
    assertTrue(r.getValue().isDeleted());      // dropped to 0 -> tombstone
  }

  @Test
  public void accountAssetEmitsForNewAccountAndSkipsUnchanged() {
    enterTx(1);
    engine.captureAccountAsset(new byte[21], null, acct("1", 50L)); // new account
    assertEquals(1, engine.records().size());
    assertTrue(engine.records().get(0).getPrevValue().isDeleted()); // asset absent before -> prev 0
    engine.clear();
    engine.captureAccountAsset(new byte[21], acct("1", 5L), acct("1", 5L)); // unchanged
    assertTrue(engine.records().isEmpty());
  }

  @Test
  public void accountAssetNoCaptureOutsideContext() {
    engine.captureAccountAsset(new byte[21], null, acct("1", 5L));
    assertTrue(engine.records().isEmpty());
  }

  @Test
  public void capturesSemanticContractStorage() {
    enterTx(21);
    byte[] key = new byte[ContractStorageKeyCodec.LENGTH];
    byte[] value = new byte[32];
    value[31] = 7;
    engine.captureSemanticPut(ArchiveDomain.CONTRACT_STORAGE, key, null, value); // null prev = new
    assertEquals(1, engine.records().size());
    ArchiveChangeRecord r = engine.records().get(0);
    assertEquals(ArchiveDomain.CONTRACT_STORAGE, r.getDomain());
    assertEquals(21, r.getTxNum());
    assertArrayEquals(key, r.getCanonicalKey());
    assertTrue(r.getPrevValue().isDeleted()); // slot absent before -> prev tombstone
    assertFalse(r.getValue().isDeleted());
  }

  @Test
  public void capturesSemanticStoragePrevValueOnOverwrite() {
    enterTx(21);
    byte[] key = new byte[ContractStorageKeyCodec.LENGTH];
    byte[] prev = new byte[32];
    prev[31] = 3;
    byte[] value = new byte[32];
    value[31] = 7;
    engine.captureSemanticPut(ArchiveDomain.CONTRACT_STORAGE, key, prev, value);
    ArchiveChangeRecord r = engine.records().get(0);
    assertFalse(r.getPrevValue().isDeleted()); // overwriting an existing slot
    assertFalse(r.getValue().isDeleted());
  }

  @Test
  public void capturesSemanticStorageDeleteAsTombstone() {
    enterTx(1);
    byte[] prev = new byte[32];
    prev[31] = 5;
    engine.captureSemanticDelete(
        ArchiveDomain.CONTRACT_STORAGE, new byte[ContractStorageKeyCodec.LENGTH], prev);
    assertEquals(1, engine.records().size());
    ArchiveChangeRecord r = engine.records().get(0);
    assertFalse(r.getPrevValue().isDeleted()); // slot had a value before the delete
    assertTrue(r.getValue().isDeleted());
  }

  @Test
  public void semanticCaptureNoOpOutsideContext() {
    engine.captureSemanticPut(ArchiveDomain.CONTRACT_STORAGE,
        new byte[ContractStorageKeyCodec.LENGTH], null, new byte[32]);
    assertTrue(engine.records().isEmpty());
  }

  @Test
  public void rawCapturedDomainMissingDescriptorFailsClosed() {
    ArchiveCaptureEngine missingCatalogEngine = new ArchiveCaptureEngine(
        new DefaultArchiveDomainRegistry(), new MissingCatalog(), new DynamicKeyPolicy(), context);
    enterTx(1);

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> missingCatalogEngine.capturePut("account", new byte[21], null, account(1)));

    assertTrue(ex.getMessage().contains("missing descriptor"));
  }

  @Test
  public void semanticCapturedDomainMissingDescriptorFailsClosed() {
    ArchiveCaptureEngine missingCatalogEngine = new ArchiveCaptureEngine(
        new DefaultArchiveDomainRegistry(), new MissingCatalog(), new DynamicKeyPolicy(), context);
    enterTx(1);

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> missingCatalogEngine.captureSemanticPut(
            ArchiveDomain.CONTRACT_STORAGE,
            new byte[ContractStorageKeyCodec.LENGTH], null, new byte[32]));

    assertTrue(ex.getMessage().contains("missing descriptor"));
  }

  @Test
  public void accountAssetMissingDescriptorFailsClosed() {
    ArchiveCaptureEngine missingCatalogEngine = new ArchiveCaptureEngine(
        new DefaultArchiveDomainRegistry(), new MissingCatalog(), new DynamicKeyPolicy(), context);
    enterTx(1);

    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> missingCatalogEngine.captureAccountAsset(new byte[21], null, acct("1", 50L)));

    assertTrue(ex.getMessage().contains("missing descriptor"));
  }

  private static final class MissingCatalog implements ArchiveDomainCatalog {
    public ArchiveDomainDescriptor descriptorFor(ArchiveDomain domain) {
      return null;
    }

    public List<ArchiveDomainDescriptor> allDescriptors() {
      return Collections.emptyList();
    }

    public List<ArchiveDomainDescriptor> rootDescriptors() {
      return Collections.emptyList();
    }

    public byte[] checksum() {
      return new byte[32];
    }
  }
}
