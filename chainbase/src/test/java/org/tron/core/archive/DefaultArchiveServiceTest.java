package org.tron.core.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.archive.capture.ArchiveCaptureHolder;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.reader.ArchiveReaderException;
import org.tron.core.archive.reader.ArchiveStatePoint;
import org.tron.core.archive.temporal.ArchiveTemporalStore;
import org.tron.core.archive.temporal.InMemoryArchiveTemporalStore;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.archive.txnum.ArchiveTxPosition;
import org.tron.core.archive.txnum.ArchiveTxNumIndex;
import org.tron.core.archive.txnum.InMemoryArchiveTxNumIndex;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.protos.Protocol.Account;
import org.tron.protos.Protocol.Transaction;

public class DefaultArchiveServiceTest {

  @After
  public void clearCaptureHolder() {
    // An enabled service installs a process-wide capture engine; clear it between tests.
    ArchiveCaptureHolder.clear();
  }

  private static BlockCapsule block(long num) {
    return new BlockCapsule(num, Sha256Hash.ZERO_HASH, 1L, ByteString.EMPTY);
  }

  private static BlockCapsule blockWithParentSeed(long num, byte seed) {
    byte[] parent = new byte[32];
    parent[31] = seed;
    return new BlockCapsule(num, Sha256Hash.wrap(parent), 1L, ByteString.EMPTY);
  }

  @Test
  public void disabledServiceIsNoOp() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    DefaultArchiveService service = new DefaultArchiveService(false, index, context);
    assertFalse(service.isEnabled());

    BlockCapsule b = block(5);
    service.beginBlock(b, ArchiveSource.NORMAL);
    service.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    // Disabled: nothing allocated, no context entered.
    assertFalse(context.current().isPresent());
    assertFalse(index.getBlockRange(5).isPresent());
    service.endTx();
    service.commitBlock(b);
    service.validateCanonicalHead(null);
    assertFalse(index.getBlockRange(5).isPresent());
  }

  @Test
  public void enabledEmptyBlockLifecycleCommitsRange() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    DefaultArchiveService service = new DefaultArchiveService(true, index, context);

    BlockCapsule b = block(5); // no transactions
    service.beginBlock(b, ArchiveSource.NORMAL);

    service.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    ArchiveTxPosition prepare = context.current().orElseThrow(AssertionError::new);
    assertEquals(0, prepare.getTxNum());
    assertEquals(ArchivePhase.BLOCK_PREPARE, prepare.getPhase());
    service.endTx();
    assertFalse(context.current().isPresent());

    service.beginSystemTx(b, ArchivePhase.BLOCK_FINALIZE);
    service.endTx();

    service.commitBlock(b); // userTxCount = block.getTransactions().size() = 0
    assertFalse(index.getBlockRange(5).isPresent());
    service.publishSolidifiedBlocks(5);
    ArchiveBlockRange range = index.getBlockRange(5).orElseThrow(AssertionError::new);
    assertEquals(0, range.getFirstTxNum());
    assertEquals(1, range.getLastTxNum());
    assertEquals(0, range.getUserTxCount());
    assertEquals(ArchiveSource.NORMAL, range.getSource());
  }

  @Test
  public void readerFactoryRejectsUncoveredOrMismatchedPoints() throws Exception {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    DefaultArchiveService service = new DefaultArchiveService(true, index, context);

    BlockCapsule b = blockWithParentSeed(5, (byte) 5);
    service.beginBlock(b, ArchiveSource.NORMAL);
    service.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    service.endTx();
    service.beginUserTx(b, 0, new TransactionCapsule(Transaction.getDefaultInstance()));
    service.endTx();
    service.beginSystemTx(b, ArchivePhase.BLOCK_FINALIZE);
    service.endTx();
    service.commitBlock(b, 1);
    service.publishSolidifiedBlocks(5);
    ArchiveBlockRange range = index.getBlockRange(5).orElseThrow(AssertionError::new);

    service.getReaderFactory().open(ArchiveStatePoint.blockEnd(
        5, range.getBlockHash(), range.getFinalizeTxNum())).close();
    service.getReaderFactory().open(ArchiveStatePoint.txBefore(
        5, range.getBlockHash(), range.getPrepareTxNum())).close();
    ArchiveReaderException wrongTxNum = assertThrows(ArchiveReaderException.class,
        () -> service.getReaderFactory().open(ArchiveStatePoint.blockEnd(
            5, range.getBlockHash(), range.getFinalizeTxNum() + 1)));
    assertEquals(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE, wrongTxNum.getReason());
    ArchiveReaderException txBeforeAtFinalize = assertThrows(ArchiveReaderException.class,
        () -> service.getReaderFactory().open(ArchiveStatePoint.txBefore(
            5, range.getBlockHash(), range.getFinalizeTxNum() - 1)));
    assertEquals(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE,
        txBeforeAtFinalize.getReason());
    byte[] wrongBlockHash = range.getBlockHash();
    wrongBlockHash[0] ^= 1;
    ArchiveReaderException wrongHash = assertThrows(ArchiveReaderException.class,
        () -> service.getReaderFactory().open(ArchiveStatePoint.blockEnd(
            5, wrongBlockHash, range.getFinalizeTxNum())));
    assertEquals(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE, wrongHash.getReason());
    ArchiveReaderException missingHash = assertThrows(ArchiveReaderException.class,
        () -> service.getReaderFactory().open(ArchiveStatePoint.blockEnd(
            5, null, range.getFinalizeTxNum())));
    assertEquals(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE, missingHash.getReason());
    ArchiveReaderException uncovered = assertThrows(ArchiveReaderException.class,
        () -> service.getReaderFactory().open(ArchiveStatePoint.blockEnd(
            6, null, range.getFinalizeTxNum())));
    assertEquals(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE, uncovered.getReason());
  }

  @Test
  public void commitBlockDoesNotExposeReaderStateUntilPublish() throws Exception {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    DefaultArchiveService service =
        new DefaultArchiveService(true, index, context, temporal);

    byte[] addr = new byte[21];
    addr[0] = 0x41;
    BlockCapsule b = blockWithParentSeed(5, (byte) 5);
    service.beginBlock(b, ArchiveSource.NORMAL);
    service.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    service.endTx();
    service.beginSystemTx(b, ArchivePhase.BLOCK_FINALIZE);
    long finalizeTxNum = context.current().orElseThrow(AssertionError::new).getTxNum();
    service.getCaptureEngine().capturePut("account", addr, null, account(1));
    service.endTx();
    service.commitBlock(b);

    assertFalse(index.getBlockRange(5).isPresent());
    assertEquals(0, temporal.changeCount());
    ArchiveReaderException unpublished = assertThrows(ArchiveReaderException.class,
        () -> service.getReaderFactory().open(ArchiveStatePoint.blockEnd(
            5, b.getBlockId().getBytes(), finalizeTxNum)));
    assertEquals(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE, unpublished.getReason());

    service.publishSolidifiedBlocks(5);
    assertTrue(index.getBlockRange(5).isPresent());
    assertEquals(1, temporal.changeCount());
    service.getReaderFactory().open(ArchiveStatePoint.blockEnd(
        5, b.getBlockId().getBytes(), finalizeTxNum)).close();
  }

  @Test
  public void explicitUserTxCountAllowsGenesisSyntheticTransactions() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    DefaultArchiveService service = new DefaultArchiveService(true, index, context);

    BlockCapsule b = block(0);
    b.addTransaction(new TransactionCapsule(Transaction.getDefaultInstance()));
    service.beginBlock(b, ArchiveSource.NORMAL);
    service.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    service.endTx();
    service.beginSystemTx(b, ArchivePhase.BLOCK_FINALIZE);
    service.endTx();

    service.commitBlock(b, 0);
    service.publishSolidifiedBlocks(0);

    ArchiveBlockRange range = index.getBlockRange(0).orElseThrow(AssertionError::new);
    assertEquals(0, range.getUserTxCount());
    assertEquals(0, range.getPrepareTxNum());
    assertEquals(1, range.getFinalizeTxNum());
  }

  @Test
  public void enabledUserTxEntersContextWithTxId() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    DefaultArchiveService service = new DefaultArchiveService(true, index, context);

    BlockCapsule b = block(7);
    TransactionCapsule tx = new TransactionCapsule(Transaction.getDefaultInstance());
    service.beginBlock(b, ArchiveSource.NORMAL);
    service.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    service.endTx();

    service.beginUserTx(b, 0, tx);
    ArchiveTxPosition pos = context.current().orElseThrow(AssertionError::new);
    assertEquals(ArchivePhase.USER_TX, pos.getPhase());
    assertEquals(0, pos.getTxIndex());
    assertArrayEquals(tx.getTransactionId().getBytes(), pos.getTxId());
    service.endTx();
    assertFalse(context.current().isPresent());
  }

  private static byte[] account(long balance) {
    return Account.newBuilder().setBalance(balance).build().toByteArray();
  }

  private static long balanceOf(byte[] accountBytes) throws Exception {
    return Account.parseFrom(accountBytes).getBalance();
  }

  private static void commitEmptyBlock(DefaultArchiveService service, BlockCapsule block) {
    service.beginBlock(block, ArchiveSource.NORMAL);
    service.beginSystemTx(block, ArchivePhase.BLOCK_PREPARE);
    service.endTx();
    service.beginSystemTx(block, ArchivePhase.BLOCK_FINALIZE);
    service.endTx();
    service.commitBlock(block);
  }

  @Test
  public void commitCollapsesSameKeySameTxToFirstPrevLastNew() throws Exception {
    // Two captures of the same account within one user tx (10 -> 20 -> 30): the drain must keep the
    // FIRST prev (10, the true pre-tx value) for history and the LAST value (30) for latest, so a
    // read of the block before the tx returns 10 -- not the intermediate 20.
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    DefaultArchiveService service =
        new DefaultArchiveService(true, index, context, temporal);

    byte[] addr = new byte[21];
    addr[0] = 0x41;
    BlockCapsule b = block(5); // no user transactions
    service.beginBlock(b, ArchiveSource.NORMAL);
    service.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    service.endTx();

    // Two captures of the same account within ONE (system) tx: 10 -> 20 -> 30.
    service.beginSystemTx(b, ArchivePhase.BLOCK_FINALIZE);
    long userTxNum = context.current().orElseThrow(AssertionError::new).getTxNum();
    service.getCaptureEngine().capturePut("account", addr, account(10), account(20));
    service.getCaptureEngine().capturePut("account", addr, account(20), account(30));
    service.endTx();
    service.commitBlock(b);
    service.publishSolidifiedBlocks(5);

    // exactly one history entry for the key (the two captures collapsed into one change).
    assertEquals(1, temporal.changeCount());
    // value at the end of (userTxNum - 1) = the first captured prev (10), not the intermediate 20.
    assertEquals(10, balanceOf(
        temporal.getAsOf(ArchiveDomain.ACCOUNT, addr, userTxNum - 1).get().getValue()));
    // latest = the last captured value (30).
    assertEquals(30, balanceOf(temporal.latest(ArchiveDomain.ACCOUNT, addr).get().getValue()));
    // value at the end of the tx itself falls through to latest (30).
    assertEquals(30, balanceOf(
        temporal.getAsOf(ArchiveDomain.ACCOUNT, addr, userTxNum).get().getValue()));
  }

  @Test
  public void sameValueWritesSeedTemporalLatestForMidChainCoverage() throws Exception {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    DefaultArchiveService service =
        new DefaultArchiveService(true, index, context, temporal);

    byte[] addr = new byte[21];
    addr[0] = 0x41;
    BlockCapsule b = block(5);
    service.beginBlock(b, ArchiveSource.NORMAL);
    service.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    service.getCaptureEngine().capturePut("account", addr, account(10), account(10));
    service.endTx();
    service.beginSystemTx(b, ArchivePhase.BLOCK_FINALIZE);
    service.endTx();

    service.commitBlock(b);
    assertFalse(index.getBlockRange(5).isPresent());
    assertEquals(0, temporal.changeCount());
    service.publishSolidifiedBlocks(5);

    assertTrue(index.getBlockRange(5).isPresent());
    assertEquals(1, temporal.changeCount());
    assertEquals(10, balanceOf(temporal.latest(ArchiveDomain.ACCOUNT, addr).get().getValue()));
    assertEquals(10, balanceOf(
        temporal.getAsOf(ArchiveDomain.ACCOUNT, addr, Long.MAX_VALUE).get().getValue()));

    BlockCapsule b2 = block(6);
    service.beginBlock(b2, ArchiveSource.NORMAL);
    service.beginSystemTx(b2, ArchivePhase.BLOCK_PREPARE);
    service.getCaptureEngine().capturePut("account", addr, account(10), account(10));
    service.endTx();
    service.beginSystemTx(b2, ArchivePhase.BLOCK_FINALIZE);
    service.endTx();
    service.commitBlock(b2);
    service.publishSolidifiedBlocks(6);

    assertTrue(index.getBlockRange(6).isPresent());
    assertEquals(1, temporal.changeCount());
  }

  @Test
  public void publishSolidifiedBlocksPublishesOnlyThroughSolidNum() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    DefaultArchiveService service = new DefaultArchiveService(true, index, context);

    BlockCapsule b5 = blockWithParentSeed(5, (byte) 5);
    commitEmptyBlock(service, b5);
    BlockCapsule b6 = blockWithParentSeed(6, (byte) 6);
    commitEmptyBlock(service, b6);
    BlockCapsule b7 = blockWithParentSeed(7, (byte) 7);
    commitEmptyBlock(service, b7);

    service.publishSolidifiedBlocks(6);

    assertTrue(index.getBlockRange(5).isPresent());
    assertTrue(index.getBlockRange(6).isPresent());
    assertFalse(index.getBlockRange(7).isPresent());
  }

  @Test
  public void deleteMissingKeyDoesNotSeedTemporalTombstone() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    DefaultArchiveService service =
        new DefaultArchiveService(true, index, context, temporal);

    byte[] addr = new byte[21];
    addr[0] = 0x41;
    BlockCapsule b = block(5);
    service.beginBlock(b, ArchiveSource.NORMAL);
    service.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    service.getCaptureEngine().captureDelete("account", addr, null);
    service.endTx();
    service.beginSystemTx(b, ArchivePhase.BLOCK_FINALIZE);
    service.endTx();

    service.commitBlock(b);
    service.publishSolidifiedBlocks(5);

    assertTrue(index.getBlockRange(5).isPresent());
    assertEquals(0, temporal.changeCount());
    assertFalse(temporal.latest(ArchiveDomain.ACCOUNT, addr).isPresent());
  }

  @Test
  public void unwindUnpublishedHeadDropsOnlyInFlightState() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    DefaultArchiveService service =
        new DefaultArchiveService(true, index, context, temporal);

    byte[] addr = new byte[21];
    addr[0] = 0x41;
    BlockCapsule b = blockWithParentSeed(5, (byte) 5);
    service.beginBlock(b, ArchiveSource.NORMAL);
    service.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    service.getCaptureEngine().capturePut("account", addr, null, account(1));
    service.endTx();
    service.beginSystemTx(b, ArchivePhase.BLOCK_FINALIZE);
    service.endTx();
    service.commitBlock(b);

    service.unwindBlock(b);
    service.publishSolidifiedBlocks(5);

    assertFalse(index.getBlockRange(5).isPresent());
    assertEquals(0, temporal.changeCount());
  }

  @Test
  public void abortClearsContextAndDiscardsPending() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    DefaultArchiveService service = new DefaultArchiveService(true, index, context);

    BlockCapsule b = block(5);
    service.beginBlock(b, ArchiveSource.NORMAL);
    service.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    assertTrue(context.current().isPresent());

    service.abortBlock(b);
    assertFalse(context.current().isPresent());
    assertFalse(index.getBlockRange(5).isPresent());
  }

  @Test
  public void commitFailsClosedWhenCaptureFailureWasRecorded() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    DefaultArchiveService service =
        new DefaultArchiveService(true, index, context, temporal);

    BlockCapsule b = block(5);
    service.beginBlock(b, ArchiveSource.NORMAL);
    service.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    service.endTx();
    service.beginSystemTx(b, ArchivePhase.BLOCK_FINALIZE);
    ArchiveCaptureHolder.capturePut("account", new byte[21], null, new byte[] {(byte) 0xff});
    service.endTx();

    assertThrows(ArchiveException.class, () -> service.commitBlock(b));
    assertThrows(ArchiveException.class, service::validateAvailable);
    assertThrows(ArchiveException.class, () -> service.beginBlock(block(6), ArchiveSource.NORMAL));
    assertFalse(index.getBlockRange(5).isPresent());
    assertEquals(0, temporal.changeCount());
  }

  @Test
  public void temporalBatchFailureDuringPublishUnwindsCommittedIndex() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    DefaultArchiveService service =
        new DefaultArchiveService(true, index, context, new FailingTemporalStore());

    byte[] addr = new byte[21];
    addr[0] = 0x41;
    BlockCapsule b = block(5);
    service.beginBlock(b, ArchiveSource.NORMAL);
    service.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    service.getCaptureEngine().capturePut("account", addr, account(1), account(2));
    service.endTx();
    service.beginSystemTx(b, ArchivePhase.BLOCK_FINALIZE);
    service.endTx();

    service.commitBlock(b);
    assertFalse(index.getBlockRange(5).isPresent());

    assertThrows(ArchiveException.class, () -> service.publishSolidifiedBlocks(5));
    assertThrows(ArchiveException.class, service::validateAvailable);
    assertThrows(ArchiveException.class, () -> service.beginBlock(block(6), ArchiveSource.NORMAL));
    assertFalse(index.getBlockRange(5).isPresent());
  }

  @Test
  public void publishedUnwindMarksArchiveUnavailable() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    DefaultArchiveService service =
        new DefaultArchiveService(true, index, context, new InMemoryArchiveTemporalStore());

    BlockCapsule b = block(5);
    service.beginBlock(b, ArchiveSource.NORMAL);
    service.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    service.endTx();
    service.beginSystemTx(b, ArchivePhase.BLOCK_FINALIZE);
    service.endTx();
    service.commitBlock(b);
    service.publishSolidifiedBlocks(5);

    assertThrows(ArchiveException.class, () -> service.unwindBlock(b));
    assertThrows(ArchiveException.class, service::validateAvailable);
    assertThrows(ArchiveException.class, () -> service.beginBlock(block(6), ArchiveSource.NORMAL));
  }

  @Test
  public void unwindRejectsSameHeightDifferentHashAndFailsClosed() throws Exception {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    DefaultArchiveService service =
        new DefaultArchiveService(true, index, context, temporal);

    byte[] addr = new byte[21];
    addr[0] = 0x41;
    BlockCapsule b = block(5);
    service.beginBlock(b, ArchiveSource.NORMAL);
    service.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    service.getCaptureEngine().capturePut("account", addr, null, account(1));
    service.endTx();
    service.beginSystemTx(b, ArchivePhase.BLOCK_FINALIZE);
    service.endTx();
    service.commitBlock(b);

    assertThrows(ArchiveException.class,
        () -> service.unwindBlock(blockWithParentSeed(5, (byte) 1)));

    assertFalse(index.getBlockRange(5).isPresent());
    assertEquals(0, temporal.changeCount());
    assertThrows(ArchiveException.class, service::validateAvailable);
  }

  @Test
  public void readGuardBlocksSolidifiedPublication() throws Exception {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    DefaultArchiveService service = new DefaultArchiveService(true, index, context);

    BlockCapsule b = block(5);
    service.beginBlock(b, ArchiveSource.NORMAL);
    service.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    service.endTx();
    service.beginSystemTx(b, ArchivePhase.BLOCK_FINALIZE);
    service.endTx();

    service.commitBlock(b);
    ArchiveService.ReadGuard guard = service.acquireReadGuard();
    CountDownLatch started = new CountDownLatch(1);
    FutureTask<Void> publish = new FutureTask<>(() -> {
      started.countDown();
      service.publishSolidifiedBlocks(5);
      return null;
    });
    Thread t = new Thread(publish);
    t.start();

    assertTrue(started.await(1, TimeUnit.SECONDS));
    Thread.sleep(100);
    assertFalse(index.getBlockRange(5).isPresent());
    guard.close();
    publish.get(1, TimeUnit.SECONDS);
    assertTrue(index.getBlockRange(5).isPresent());
  }

  @Test
  public void preCoverageUnwindIsNoop() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    DefaultArchiveService service = new DefaultArchiveService(true, index, context);

    service.unwindBlock(block(100));
    assertFalse(index.getBlockRange(100).isPresent());
    assertFalse(context.current().isPresent());
  }

  @Test
  public void enabledServiceValidatesCanonicalHead() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    DefaultArchiveService service = new DefaultArchiveService(true, index, context);

    BlockCapsule b = block(5);
    service.beginBlock(b, ArchiveSource.NORMAL);
    service.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    service.endTx();
    service.beginSystemTx(b, ArchivePhase.BLOCK_FINALIZE);
    service.endTx();
    service.commitBlock(b);
    service.publishSolidifiedBlocks(5);

    service.validateCanonicalHead(b);
    assertThrows(ArchiveException.class, () -> service.validateCanonicalHead(block(6)));
  }

  @Test
  public void enabledServiceAllowsEmptyArchiveCanonicalHead() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    DefaultArchiveService service = new DefaultArchiveService(true, index, context);

    service.validateCanonicalHead(block(5));
  }

  @Test
  public void txNumCommitFailureClearsPendingContextAndCaptureBuffer() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    DefaultArchiveService service =
        new DefaultArchiveService(true, index, context, temporal);

    byte[] addr = new byte[21];
    addr[0] = 0x41;
    BlockCapsule b = block(5);
    service.beginBlock(b, ArchiveSource.NORMAL);
    service.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    service.getCaptureEngine().capturePut("account", addr, null, account(1));

    ArchiveException ex = assertThrows(ArchiveException.class, () -> service.commitBlock(b));
    assertTrue(ex.getMessage().contains("requires both prepare and finalize"));
    assertFalse(context.current().isPresent());
    assertTrue(service.getCaptureEngine().records().isEmpty());
    assertFalse(index.getBlockRange(5).isPresent());
    assertEquals(0, temporal.changeCount());

    assertThrows(ArchiveException.class, service::validateAvailable);
    assertThrows(ArchiveException.class, () -> service.beginBlock(block(6), ArchiveSource.NORMAL));
  }

  @Test
  public void ownerDriftDuringCommitMarksRepairAndAbortsPendingBlock() {
    TrackingArchiveTxNumIndex index = new TrackingArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    DefaultArchiveService older =
        new DefaultArchiveService(true, index, context, new InMemoryArchiveTemporalStore());

    byte[] addr = new byte[21];
    addr[0] = 0x41;
    BlockCapsule b = block(5);
    older.beginBlock(b, ArchiveSource.NORMAL);
    older.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    older.getCaptureEngine().capturePut("account", addr, null, account(1));
    older.endTx();
    older.beginSystemTx(b, ArchivePhase.BLOCK_FINALIZE);
    older.endTx();
    DefaultArchiveService newer = new DefaultArchiveService(true);
    try {
      ArchiveException ex = assertThrows(ArchiveException.class, () -> older.commitBlock(b));

      assertTrue(ex.getMessage().contains("capture engine is not active"));
      assertTrue(index.repairReason.contains("capture engine is not active"));
      assertFalse(index.getBlockRange(5).isPresent());
      assertFalse(context.current().isPresent());
      assertTrue(older.getCaptureEngine().records().isEmpty());
      assertThrows(ArchiveException.class, older::validateAvailable);
    } finally {
      newer.close();
    }
  }

  @Test
  public void ownerDriftDuringAbortMarksRepairAndClearsPendingBlock() {
    TrackingArchiveTxNumIndex index = new TrackingArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    DefaultArchiveService older =
        new DefaultArchiveService(true, index, context, new InMemoryArchiveTemporalStore());

    byte[] addr = new byte[21];
    addr[0] = 0x41;
    BlockCapsule b = block(5);
    older.beginBlock(b, ArchiveSource.NORMAL);
    older.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    older.getCaptureEngine().capturePut("account", addr, null, account(1));
    DefaultArchiveService newer = new DefaultArchiveService(true);
    try {
      ArchiveException ex = assertThrows(ArchiveException.class, () -> older.abortBlock(b));

      assertTrue(ex.getMessage().contains("capture engine is not active"));
      assertTrue(index.repairReason.contains("capture engine is not active"));
      assertFalse(index.getBlockRange(5).isPresent());
      assertFalse(context.current().isPresent());
      assertTrue(older.getCaptureEngine().records().isEmpty());
      index.beginBlock(6L, ArchiveSource.NORMAL);
      index.abortBlock(6L);
      assertThrows(ArchiveException.class, older::validateAvailable);
    } finally {
      newer.close();
    }
  }

  @Test
  public void ownerDriftDuringAbortDoesNotTouchPublishedIndex() {
    FailingAbortArchiveTxNumIndex index = new FailingAbortArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    DefaultArchiveService older =
        new DefaultArchiveService(true, index, context, new InMemoryArchiveTemporalStore());

    byte[] addr = new byte[21];
    addr[0] = 0x41;
    BlockCapsule b = block(5);
    older.beginBlock(b, ArchiveSource.NORMAL);
    older.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    older.getCaptureEngine().capturePut("account", addr, null, account(1));
    DefaultArchiveService newer = new DefaultArchiveService(true);
    try {
      ArchiveException ex = assertThrows(ArchiveException.class, () -> older.abortBlock(b));

      assertTrue(ex.getMessage().contains("capture engine is not active"));
      assertEquals(0, ex.getSuppressed().length);
      assertTrue(index.repairReason.contains("capture engine is not active"));
      assertFalse(context.current().isPresent());
      assertTrue(older.getCaptureEngine().records().isEmpty());
    } finally {
      newer.close();
    }
  }

  @Test
  public void ownerDriftDuringUnwindMarksRepairWithoutDroppingArchiveRange() {
    TrackingArchiveTxNumIndex index = new TrackingArchiveTxNumIndex();
    DefaultArchiveService older =
        new DefaultArchiveService(true, index, new ArchiveExecutionContext(),
            new InMemoryArchiveTemporalStore());

    BlockCapsule b = block(5);
    older.beginBlock(b, ArchiveSource.NORMAL);
    older.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    older.endTx();
    older.beginSystemTx(b, ArchivePhase.BLOCK_FINALIZE);
    older.endTx();
    older.commitBlock(b);
    older.publishSolidifiedBlocks(5);
    DefaultArchiveService newer = new DefaultArchiveService(true);
    try {
      ArchiveException ex = assertThrows(ArchiveException.class, () -> older.unwindBlock(b));

      assertTrue(ex.getMessage().contains("capture engine is not active"));
      assertTrue(index.repairReason.contains("capture engine is not active"));
      assertTrue(index.getBlockRange(5).isPresent());
      assertThrows(ArchiveException.class, older::validateAvailable);
    } finally {
      newer.close();
    }
  }

  private static final class FailingAbortArchiveTxNumIndex extends TrackingArchiveTxNumIndex {

    @Override
    public void abortBlock(long blockNum) {
      delegate.abortBlock(blockNum);
      throw new ArchiveException("abort cleanup failed");
    }
  }

  private static class TrackingArchiveTxNumIndex implements ArchiveTxNumIndex {
    protected final InMemoryArchiveTxNumIndex delegate = new InMemoryArchiveTxNumIndex();
    protected String repairReason = "";

    @Override
    public void beginBlock(long blockNum, ArchiveSource source) {
      delegate.beginBlock(blockNum, source);
    }

    @Override
    public ArchiveTxPosition allocateSystemTx(long blockNum, ArchivePhase phase) {
      return delegate.allocateSystemTx(blockNum, phase);
    }

    @Override
    public ArchiveTxPosition allocateUserTx(long blockNum, int txIndex, byte[] txId) {
      return delegate.allocateUserTx(blockNum, txIndex, txId);
    }

    @Override
    public ArchiveBlockRange commitBlock(long blockNum, int userTxCount) {
      return delegate.commitBlock(blockNum, userTxCount);
    }

    @Override
    public ArchiveBlockRange commitBlock(long blockNum, byte[] blockHash, int userTxCount,
        byte[] schemaChecksum) {
      return delegate.commitBlock(blockNum, blockHash, userTxCount, schemaChecksum);
    }

    @Override
    public void abortBlock(long blockNum) {
      delegate.abortBlock(blockNum);
    }

    @Override
    public void unwindBlock(long blockNum) {
      delegate.unwindBlock(blockNum);
    }

    @Override
    public void validateCanonicalHead(long headNum, byte[] headHash) {
      delegate.validateCanonicalHead(headNum, headHash);
    }

    @Override
    public ArchiveBlockRange getHeadBlockRange(long blockNum) {
      return delegate.getHeadBlockRange(blockNum);
    }

    @Override
    public Optional<ArchiveBlockRange> getBlockRange(long blockNum) {
      return delegate.getBlockRange(blockNum);
    }

    @Override
    public Optional<ArchiveTxPosition> getPosition(long txNum) {
      return delegate.getPosition(txNum);
    }

    @Override
    public OptionalLong findTxNumByBlockAndIndex(long blockNum, int txIndex) {
      return delegate.findTxNumByBlockAndIndex(blockNum, txIndex);
    }

    @Override
    public OptionalLong findTxNumByTxId(byte[] txId) {
      return delegate.findTxNumByTxId(txId);
    }

    @Override
    public long getNextTxNum() {
      return delegate.getNextTxNum();
    }

    @Override
    public long getLastArchivedBlock() {
      return delegate.getLastArchivedBlock();
    }

    @Override
    public void markRepairRequired(String reason) {
      repairReason = reason;
    }

    @Override
    public long getFirstArchivedBlock() {
      return delegate.getFirstArchivedBlock();
    }
  }

  private static final class FailingTemporalStore implements ArchiveTemporalStore {

    @Override
    public void putChange(ArchiveChangeRecord record) {
      throw new ArchiveException("boom");
    }

    @Override
    public void putChanges(List<ArchiveChangeRecord> records) {
      throw new ArchiveException("boom");
    }

    @Override
    public Optional<DomainValue> getAsOf(ArchiveDomain domain, byte[] canonicalKey, long txNum) {
      return Optional.empty();
    }

    @Override
    public Optional<DomainValue> latest(ArchiveDomain domain, byte[] canonicalKey) {
      return Optional.empty();
    }

    @Override
    public void unwind(long fromTxNum) {
    }
  }

}
