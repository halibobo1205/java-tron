package org.tron.core.archive.capture;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import org.junit.Before;
import org.junit.Test;
import org.tron.core.archive.ArchiveExecutionContext;
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveSource;
import org.tron.core.archive.domain.ArchiveDomain;
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
    engine.capturePut("account", addr, account(100));
    assertEquals(1, engine.records().size());
    ArchiveChangeRecord r = engine.records().get(0);
    assertEquals(ArchiveDomain.ACCOUNT, r.getDomain());
    assertEquals(42, r.getTxNum());
    assertArrayEquals(addr, r.getCanonicalKey());
    assertFalse(r.getValue().isDeleted());
  }

  @Test
  public void noCaptureOutsideContext() {
    engine.capturePut("account", new byte[21], account(1));
    assertTrue(engine.records().isEmpty());
  }

  @Test
  public void noCaptureForUnknownSemanticExcluded() {
    enterTx(1);
    engine.capturePut("no-such-store", new byte[21], account(1)); // unknown
    engine.capturePut("account-asset", new byte[8], new byte[8]); // semantic IGNORE_RAW
    engine.capturePut("storage-row", new byte[8], new byte[32]);  // semantic-only
    engine.capturePut("block", new byte[4], new byte[4]);         // excluded
    assertTrue(engine.records().isEmpty());
  }

  @Test
  public void capturesStoreSpecificContractDomain() {
    // STORE_SPECIFIC stores (contract) bypass the base put and capture from their own hook.
    enterTx(3);
    byte[] addr = new byte[21];
    byte[] contract = SmartContract.newBuilder()
        .setBytecode(ByteString.copyFromUtf8("6080")).build().toByteArray();
    engine.capturePut("contract", addr, contract);
    assertEquals(1, engine.records().size());
    assertEquals(ArchiveDomain.CONTRACT, engine.records().get(0).getDomain());
  }

  @Test
  public void dynamicPropertiesAllowlistFiltersByKeyPolicy() {
    enterTx(5);
    engine.capturePut("properties", ascii("ENERGY_FEE"), new byte[] {1});       // root -> capture
    engine.capturePut("properties", ascii("ABI_MOVE_DONE"), new byte[] {1}); // NO_ARCHIVE -> skip
    engine.capturePut("properties", ascii("SOME_FUTURE_KEY"), new byte[] {1}); // unknown kept
    assertEquals(2, engine.records().size());
  }

  @Test
  public void captureDeleteRecordsTombstone() {
    enterTx(9);
    engine.captureDelete("account", new byte[21]);
    assertEquals(1, engine.records().size());
    assertTrue(engine.records().get(0).getValue().isDeleted());
  }

  @Test
  public void clearEmptiesBuffer() {
    enterTx(1);
    engine.capturePut("account", new byte[21], account(1));
    engine.clear();
    assertTrue(engine.records().isEmpty());
  }
}
