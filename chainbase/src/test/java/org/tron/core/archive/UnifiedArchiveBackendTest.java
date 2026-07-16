package org.tron.core.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.rocksdb.RocksIterator;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.archive.capture.ArchiveCaptureHolder;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.domain.ArchiveDomainCatalog;
import org.tron.core.archive.domain.ArchiveDomainRegistry;
import org.tron.core.archive.domain.ArchiveSchemaChecksum;
import org.tron.core.archive.domain.DefaultArchiveDomainCatalog;
import org.tron.core.archive.domain.DefaultArchiveDomainRegistry;
import org.tron.core.archive.query.ArchiveQueryLimits;
import org.tron.core.archive.reader.ArchiveReadResult;
import org.tron.core.archive.reader.ArchiveReadThrough;
import org.tron.core.archive.reader.ArchiveStateReader;
import org.tron.core.archive.temporal.UnifiedArchiveTemporalStore;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.archive.txnum.ArchiveTxPosition;
import org.tron.core.archive.txnum.UnifiedArchiveTxNumIndex;
import org.tron.core.archive.unified.UnifiedArchiveColumnFamily;
import org.tron.core.archive.unified.UnifiedArchiveDb;
import org.tron.core.archive.unified.UnifiedArchiveMaintenanceBatch;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.protos.Protocol.Account;

public class UnifiedArchiveBackendTest {

  @Rule
  public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private Path dbPath;
  private ArchiveDomainCatalog catalog;
  private byte[] schemaChecksum;
  private UnifiedArchiveDb db;
  private UnifiedArchiveTxNumIndex index;
  private UnifiedArchiveTemporalStore temporal;
  private UnifiedArchiveInFlightStore inFlight;
  private UnifiedArchiveBackend backend;
  private DefaultArchiveService service;

  @Before
  public void setUp() {
    dbPath = temporaryFolder.getRoot().toPath().resolve("unified");
    ArchiveDomainRegistry registry = new DefaultArchiveDomainRegistry();
    catalog = new DefaultArchiveDomainCatalog();
    schemaChecksum = ArchiveSchemaChecksum.of(registry, catalog);
    db = UnifiedArchiveDb.initialize(dbPath, schemaChecksum);
    wire(false);
  }

  @After
  public void tearDown() {
    if (service != null) {
      service.close();
      service = null;
      index = null;
    } else if (index != null) {
      index.close();
    }
    ArchiveCaptureHolder.clear();
  }

  @Test
  public void atomicPublishAndTypedRowsSurviveRestart() {
    publish(block(0L, DomainValue.tombstone(), value(1)));

    assertEquals(0L, index.getFirstArchivedBlock());
    assertEquals(0L, index.getLastArchivedBlock());
    assertEquals(2L, index.getNextTxNum());
    assertTrue(inFlight.loadBlocks().isEmpty());
    assertValue(temporal.getAsOf(ArchiveDomain.ACCOUNT, accountKey(), 0L), 1);
    backend.validateStartup(true, false);

    index.close();
    index = null;
    db = UnifiedArchiveDb.open(dbPath, schemaChecksum);
    wire(true);

    assertEquals(0L, index.getFirstArchivedBlock());
    assertEquals(0L, index.getLastArchivedBlock());
    assertEquals(2L, index.getNextTxNum());
    assertTrue(inFlight.loadBlocks().isEmpty());
    assertValue(temporal.getAsOf(ArchiveDomain.ACCOUNT, accountKey(), 0L), 1);
    backend.validateStartup(true, false);
  }

  @Test
  public void publishedSequenceMatchesIndependentTxNumAndStateOracle() {
    publish(block(0L, DomainValue.tombstone(), value(1)));
    publish(block(1L, value(1), value(2)));
    publish(block(2L, value(2), DomainValue.tombstone()));
    publish(block(3L, DomainValue.tombstone(), value(3)));

    for (long blockNum = 0L; blockNum <= 3L; blockNum++) {
      long firstTxNum = blockNum * 2L;
      ArchiveBlockRange range = index.getBlockRange(blockNum).orElseThrow(AssertionError::new);
      assertEquals(firstTxNum, range.getFirstTxNum());
      assertEquals(firstTxNum + 1L, range.getLastTxNum());
      assertEquals(firstTxNum, range.getPrepareTxNum());
      assertEquals(firstTxNum + 1L, range.getFinalizeTxNum());
      assertEquals(ArchivePhase.BLOCK_PREPARE,
          index.getPosition(firstTxNum).orElseThrow(AssertionError::new).getPhase());
      assertEquals(ArchivePhase.BLOCK_FINALIZE,
          index.getPosition(firstTxNum + 1L).orElseThrow(AssertionError::new).getPhase());
    }

    assertEquals(8L, index.getNextTxNum());
    assertValue(temporal.getAsOf(ArchiveDomain.ACCOUNT, accountKey(), 0L), 1);
    assertValue(temporal.getAsOf(ArchiveDomain.ACCOUNT, accountKey(), 1L), 1);
    assertValue(temporal.getAsOf(ArchiveDomain.ACCOUNT, accountKey(), 2L), 2);
    assertValue(temporal.getAsOf(ArchiveDomain.ACCOUNT, accountKey(), 3L), 2);
    assertTrue(temporal.getAsOf(ArchiveDomain.ACCOUNT, accountKey(), 4L)
        .orElseThrow(AssertionError::new).isDeleted());
    assertTrue(temporal.getAsOf(ArchiveDomain.ACCOUNT, accountKey(), 5L)
        .orElseThrow(AssertionError::new).isDeleted());
    assertValue(temporal.getAsOf(ArchiveDomain.ACCOUNT, accountKey(), 6L), 3);
    assertValue(temporal.latest(ArchiveDomain.ACCOUNT, accountKey()), 3);
    backend.validateStartup(true, false);
  }

  @Test
  public void acknowledgementKeepsJournalPayloadImmutableAndFoldsAtLoad() {
    ArchiveInFlightBlock block = block(0L, DomainValue.tombstone(), value(1));
    long blockNum = block.getRange().getBlockNum();
    byte[] journalKey = ArchiveInFlightCodec.blockKey(blockNum);
    byte[] acknowledgementKey = ArchiveInFlightCodec.acknowledgementKey(blockNum);
    inFlight.putBlock(block);
    byte[] before = db.get(UnifiedArchiveColumnFamily.INFLIGHT, journalKey);

    inFlight.acknowledgeBlock(block.getJournalToken());

    byte[] after = db.get(UnifiedArchiveColumnFamily.INFLIGHT, journalKey);
    byte[] acknowledgement = db.get(
        UnifiedArchiveColumnFamily.INFLIGHT, acknowledgementKey);
    assertArrayEquals(before, after);
    assertTrue(acknowledgement.length < after.length);
    assertEquals(ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED,
        inFlight.loadBlocks().get(0).getJournalState());

    inFlight.deleteBlock(blockNum);
    assertNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT, journalKey));
    assertNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT,
        ArchiveInFlightCodec.tokenKey(blockNum)));
    assertNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT, acknowledgementKey));
  }

  @Test
  public void restartReconcileReconstructsAckAndReloadsNonSolidifiedCanonicalBlock() {
    BlockCapsule canonical = canonicalBlock(0L);
    ArchiveInFlightBlock journaled = block(
        canonical, DomainValue.tombstone(), value(1));
    long blockNum = journaled.getRange().getBlockNum();
    byte[] journalKey = ArchiveInFlightCodec.blockKey(blockNum);
    byte[] tokenKey = ArchiveInFlightCodec.tokenKey(blockNum);
    byte[] acknowledgementKey = ArchiveInFlightCodec.acknowledgementKey(blockNum);
    byte[] encodedToken =
        ArchiveInFlightCodec.encodeAcknowledgement(journaled.getJournalToken());
    inFlight.putBlock(journaled);
    byte[] durableJournal =
        db.get(UnifiedArchiveColumnFamily.INFLIGHT, journalKey);

    assertArrayEquals(encodedToken,
        db.get(UnifiedArchiveColumnFamily.INFLIGHT, tokenKey));
    assertNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT, acknowledgementKey));

    index.close();
    index = null;
    db = UnifiedArchiveDb.open(dbPath, schemaChecksum);
    wire(true);
    service = unifiedService();

    assertEquals(ArchiveInFlightBlock.JournalState.JOURNALED,
        inFlight.loadBlocks().get(0).getJournalState());
    service.reconcileInFlightOnStartup(-1L, canonical.getNum(), ignored -> canonical);

    assertFalse(index.getBlockRange(blockNum).isPresent());
    assertArrayEquals(durableJournal,
        db.get(UnifiedArchiveColumnFamily.INFLIGHT, journalKey));
    assertArrayEquals(encodedToken,
        db.get(UnifiedArchiveColumnFamily.INFLIGHT, tokenKey));
    assertArrayEquals(encodedToken,
        db.get(UnifiedArchiveColumnFamily.INFLIGHT, acknowledgementKey));

    service.close();
    service = null;
    index = null;
    db = UnifiedArchiveDb.open(dbPath, schemaChecksum);
    wire(true);

    ArchiveInFlightBlock reloaded = inFlight.loadBlocks().get(0);
    assertEquals(journaled.getJournalToken(), reloaded.getJournalToken());
    assertEquals(ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED,
        reloaded.getJournalState());
  }

  @Test
  public void restartReconcilePublishesSolidifiedBlockAndDeletesJournalBundle() {
    BlockCapsule canonical = canonicalBlock(0L);
    ArchiveInFlightBlock journaled = block(
        canonical, DomainValue.tombstone(), value(1));
    long blockNum = journaled.getRange().getBlockNum();
    byte[] journalKey = ArchiveInFlightCodec.blockKey(blockNum);
    byte[] tokenKey = ArchiveInFlightCodec.tokenKey(blockNum);
    byte[] acknowledgementKey = ArchiveInFlightCodec.acknowledgementKey(blockNum);
    inFlight.putBlock(journaled);

    assertNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT, acknowledgementKey));

    index.close();
    index = null;
    db = UnifiedArchiveDb.open(dbPath, schemaChecksum);
    wire(true);
    service = unifiedService();
    service.reconcileInFlightOnStartup(
        blockNum, canonical.getNum(), ignored -> canonical);

    assertTrue(index.getBlockRange(blockNum).isPresent());
    assertValue(temporal.getAsOf(ArchiveDomain.ACCOUNT, accountKey(), 0L), 1);
    assertTrue(inFlight.loadBlocks().isEmpty());
    assertNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT, journalKey));
    assertNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT, tokenKey));
    assertNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT, acknowledgementKey));
  }

  @Test
  public void sharedSnapshotDoesNotCrossAConcurrentPublication() throws Exception {
    publish(block(0L, DomainValue.tombstone(), value(1)));
    ArchiveInFlightBlock next = prepareCanonicalJournal(
        block(1L, value(1), value(2)));

    try (UnifiedArchiveBackend.ReadSession oldSession = backend.openReadSession();
         UnifiedArchiveTxNumIndex.ReadScope ignored = oldSession.bindIndex()) {
      assertFalse(index.getBlockRange(1L).isPresent());
      assertValue(oldSession.getTemporalView().getAsOf(
          ArchiveDomain.ACCOUNT, accountKey(), 0L), 1);

      AtomicReference<Throwable> failure = new AtomicReference<>();
      Thread publisher = new Thread(() -> {
        try {
          backend.publishBlock(next);
        } catch (Throwable t) {
          failure.set(t);
        }
      }, "unified-archive-test-publisher");
      publisher.start();
      publisher.join();
      if (failure.get() != null) {
        throw new AssertionError("concurrent unified publication failed", failure.get());
      }

      assertFalse(index.getBlockRange(1L).isPresent());
      assertValue(oldSession.getTemporalView().getAsOf(
          ArchiveDomain.ACCOUNT, accountKey(), 0L), 1);
    }

    try (UnifiedArchiveBackend.ReadSession currentSession = backend.openReadSession();
         UnifiedArchiveTxNumIndex.ReadScope ignored = currentSession.bindIndex()) {
      assertTrue(index.getBlockRange(1L).isPresent());
      assertValue(currentSession.getTemporalView().getAsOf(
          ArchiveDomain.ACCOUNT, accountKey(), 2L), 2);
    }
  }

  @Test
  public void defaultServicePublishesAndReadsThroughUnifiedBackend() throws Exception {
    ArchiveDomainRegistry registry = new DefaultArchiveDomainRegistry();
    ArchivePublisherConfig publisherConfig = new ArchivePublisherConfig(
        false, false, 32, 64, 1024L * 1024L, 2L * 1024L * 1024L,
        1_000L, 2_000L, 0L, 0L, 1_000L);
    service = new DefaultArchiveService(true, index, new ArchiveExecutionContext(),
        temporal, inFlight, registry, catalog, ArchiveReadThrough.NONE,
        ArchiveLifecycle.Phase.RUNNING, ArchiveQueryLimits.unlimited(), publisherConfig,
        () -> backend.validateStartup(false, true), backend);
    BlockCapsule block = new BlockCapsule(
        0L, Sha256Hash.ZERO_HASH, 1L, ByteString.EMPTY);
    byte[] account = Account.newBuilder().setBalance(99L).build().toByteArray();

    service.beginBlock(block, ArchiveSource.NORMAL);
    service.beginSystemTx(block, ArchivePhase.BLOCK_PREPARE);
    service.endTx();
    service.beginSystemTx(block, ArchivePhase.BLOCK_FINALIZE);
    service.getCaptureEngine().capturePut("account", accountKey(), null, account);
    service.endTx();
    ArchiveJournalToken token = service.commitBlockJournaled(block, 0);
    service.acknowledgeCanonicalCommit(token);
    service.publishSolidifiedBlocks(0L);

    assertTrue(inFlight.loadBlocks().isEmpty());
    try (ArchiveStateReader reader = service.openBlockEndReader(
        0L, block.getBlockId().getBytes())) {
      ArchiveReadResult<AccountCapsule> result = reader.getAccount(accountKey());
      assertTrue(result.isPresent());
      assertEquals(99L, result.getValue().getBalance());
      assertTrue(reader.isGenesisComplete());
    }
  }

  @Test
  public void fullScrubRejectsUnknownIndexAndP0CommitmentRows() {
    db.writeMaintenanceAtomically(new UnifiedArchiveMaintenanceBatch()
        .put(UnifiedArchiveColumnFamily.INDEX, new byte[] {(byte) 0x7f}, new byte[] {1}));
    assertThrows(ArchiveException.class, () -> backend.validateStartup(true, false));

    index.close();
    index = null;
    db = UnifiedArchiveDb.open(dbPath, schemaChecksum);
    db.writeMaintenanceAtomically(new UnifiedArchiveMaintenanceBatch()
        .delete(UnifiedArchiveColumnFamily.INDEX, new byte[] {(byte) 0x7f})
        .put(UnifiedArchiveColumnFamily.COMMITMENT, new byte[] {1}, new byte[] {1}));
    wire(false);
    assertThrows(ArchiveException.class, () -> backend.validateStartup(true, false));
  }

  @Test
  public void fullScrubRejectsPublishedRowCorruptionMatrix() throws Exception {
    assertPublishedCorruptionRejected("missing-index-row",
        (caseDb, rowKey) -> deleteFirstRow(caseDb, UnifiedArchiveColumnFamily.INDEX));
    assertPublishedCorruptionRejected("missing-latest-row",
        (caseDb, rowKey) -> deleteFirstRow(caseDb, UnifiedArchiveColumnFamily.LATEST));
    assertPublishedCorruptionRejected("missing-history-row",
        (caseDb, rowKey) -> deleteFirstRow(caseDb, UnifiedArchiveColumnFamily.HISTORY));
    assertPublishedCorruptionRejected("missing-changeset-row",
        (caseDb, rowKey) -> deleteFirstRow(caseDb, UnifiedArchiveColumnFamily.CHANGESET));
    assertPublishedCorruptionRejected("missing-block-marker",
        (caseDb, rowKey) -> deleteFirstRow(caseDb, UnifiedArchiveColumnFamily.BLOCK_MARKER));
    assertPublishedCorruptionRejected("malformed-latest-value",
        (caseDb, rowKey) -> caseDb.writeMaintenanceAtomically(
            new UnifiedArchiveMaintenanceBatch().put(
                UnifiedArchiveColumnFamily.LATEST, rowKey, new byte[] {(byte) 0x7f})));
    assertPublishedCorruptionRejected("malformed-history-value",
        (caseDb, rowKey) -> replaceFirstValue(
            caseDb, UnifiedArchiveColumnFamily.HISTORY, new byte[] {(byte) 0x7f}));
    assertPublishedCorruptionRejected("malformed-changeset-value",
        (caseDb, rowKey) -> replaceFirstValue(
            caseDb, UnifiedArchiveColumnFamily.CHANGESET, new byte[] {(byte) 0x7f}));
    assertPublishedCorruptionRejected("latest-value-mismatch",
        (caseDb, rowKey) -> caseDb.writeMaintenanceAtomically(
            new UnifiedArchiveMaintenanceBatch().put(
                UnifiedArchiveColumnFamily.LATEST, rowKey,
                firstValue(caseDb, UnifiedArchiveColumnFamily.HISTORY))));
    assertPublishedCorruptionRejected("unknown-latest-key",
        (caseDb, rowKey) -> putUnknownRow(caseDb, UnifiedArchiveColumnFamily.LATEST));
    assertPublishedCorruptionRejected("unknown-history-key",
        (caseDb, rowKey) -> putUnknownRow(caseDb, UnifiedArchiveColumnFamily.HISTORY));
    assertPublishedCorruptionRejected("unknown-changeset-key",
        (caseDb, rowKey) -> putUnknownRow(caseDb, UnifiedArchiveColumnFamily.CHANGESET));
    assertPublishedCorruptionRejected("unknown-block-marker-key",
        (caseDb, rowKey) -> putUnknownRow(caseDb, UnifiedArchiveColumnFamily.BLOCK_MARKER));
  }

  @Test
  public void journalScanRejectsUnknownRows() {
    db.putJournalDurably(new byte[] {(byte) 0x7f}, new byte[] {1});
    assertThrows(ArchiveException.class, inFlight::loadBlocks);
  }

  @Test
  public void journalScanRejectsOrphanLifecycleRows() {
    ArchiveInFlightBlock block = block(0L, DomainValue.tombstone(), value(1));
    db.putJournalDurably(ArchiveInFlightCodec.acknowledgementKey(0L),
        ArchiveInFlightCodec.encodeAcknowledgement(block.getJournalToken()));

    assertThrows(ArchiveException.class, inFlight::loadBlocks);
  }

  @Test
  public void journalScanDoesNotExposeValidatedPrefixBeforeRejectingOrphanRows() {
    inFlight.putBlock(block(0L, DomainValue.tombstone(), value(1)));
    ArchiveInFlightBlock orphan = block(1L, value(1), value(2));
    db.putJournalDurably(ArchiveInFlightCodec.acknowledgementKey(1L),
        ArchiveInFlightCodec.encodeAcknowledgement(orphan.getJournalToken()));
    AtomicInteger consumed = new AtomicInteger();

    assertThrows(ArchiveException.class,
        () -> inFlight.forEachBlock(ignored -> consumed.incrementAndGet()));

    assertEquals(0, consumed.get());
  }

  @Test
  public void journalCorruptionMatrixIsFullyValidatedBeforeConsumerCallbacks() throws Exception {
    assertJournalCorruptionRejectedBeforeConsumer("unknown-row",
        (caseDb, caseStore, corrupt) ->
            caseDb.putJournalDurably(new byte[] {(byte) 0x7f}, new byte[] {1}));
    assertJournalCorruptionRejectedBeforeConsumer("missing-token",
        (caseDb, caseStore, corrupt) -> caseDb.putJournalDurably(
            ArchiveInFlightCodec.blockKey(1L), ArchiveInFlightCodec.encodeBlock(corrupt)));
    assertJournalCorruptionRejectedBeforeConsumer("orphan-token",
        (caseDb, caseStore, corrupt) -> caseDb.putJournalDurably(
            ArchiveInFlightCodec.tokenKey(1L),
            ArchiveInFlightCodec.encodeAcknowledgement(corrupt.getJournalToken())));
    assertJournalCorruptionRejectedBeforeConsumer("mismatched-token",
        (caseDb, caseStore, corrupt) -> {
          caseDb.putJournalDurably(
              ArchiveInFlightCodec.blockKey(1L), ArchiveInFlightCodec.encodeBlock(corrupt));
          caseDb.putJournalDurably(
              ArchiveInFlightCodec.tokenKey(1L),
              ArchiveInFlightCodec.encodeAcknowledgement(differentGeneration(
                  corrupt.getJournalToken())));
        });
    assertJournalCorruptionRejectedBeforeConsumer("mismatched-acknowledgement",
        (caseDb, caseStore, corrupt) -> {
          caseStore.putBlock(corrupt);
          caseDb.putJournalDurably(
              ArchiveInFlightCodec.acknowledgementKey(1L),
              ArchiveInFlightCodec.encodeAcknowledgement(differentGeneration(
                  corrupt.getJournalToken())));
        });
    assertJournalCorruptionRejectedBeforeConsumer("mutable-payload-state",
        (caseDb, caseStore, corrupt) -> {
          caseDb.putJournalDurably(
              ArchiveInFlightCodec.blockKey(1L),
              ArchiveInFlightCodec.encodeBlock(corrupt.withJournalState(
                  ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED)));
          caseDb.putJournalDurably(
              ArchiveInFlightCodec.tokenKey(1L),
              ArchiveInFlightCodec.encodeAcknowledgement(corrupt.getJournalToken()));
        });
  }

  @Test
  public void commitMarkerMatchesForMultipleVariableLengthKeysInOneTx() {
    // Two DYNAMIC_PROPERTIES changes share one (txNum, domain): "AA" (2 bytes, lexicographically
    // smaller) and "B" (1 byte, lexicographically greater). prepare() must fold the block digest in
    // stored changeset-key order (keyLen-first -> [B, AA]), NOT RECORD_ORDER (canonicalKey
    // lex-then-length -> [AA, B]); otherwise validateCommittedBlock fail-stops a valid block.
    ArchiveBlockRange range = new ArchiveBlockRange(
        0L, 0L, 1L, 0L, 1L, hash(1L), 0, ArchiveSource.NORMAL, schemaChecksum);
    ArchiveTxPosition pos = new ArchiveTxPosition(
        0L, 0L, ArchivePhase.BLOCK_PREPARE, ArchiveSource.NORMAL, -1, null);
    byte[] longerLexSmaller = "AA".getBytes(StandardCharsets.US_ASCII);
    byte[] shorterLexGreater = "B".getBytes(StandardCharsets.US_ASCII);
    ArchiveChangeRecord first = new ArchiveChangeRecord(
        pos, ArchiveDomain.DYNAMIC_PROPERTIES, longerLexSmaller,
        DomainValue.tombstone(), DomainValue.present(new byte[] {0x0A}));
    ArchiveChangeRecord second = new ArchiveChangeRecord(
        pos, ArchiveDomain.DYNAMIC_PROPERTIES, shorterLexGreater,
        DomainValue.tombstone(), DomainValue.present(new byte[] {0x0B}));
    temporal.putBlockChanges(range, Arrays.asList(first, second));
    // Recompute must equal the stored marker digest; pre-fix this threw "commit marker missing".
    temporal.validateCommittedBlock(range);
  }

  private void wire(boolean fullStartupValidation) {
    index = new UnifiedArchiveTxNumIndex(
        db, schemaChecksum, fullStartupValidation, false);
    temporal = new UnifiedArchiveTemporalStore(db, catalog);
    inFlight = new UnifiedArchiveInFlightStore(db, catalog);
    backend = new UnifiedArchiveBackend(db, index, temporal);
  }

  private void publish(ArchiveInFlightBlock block) {
    backend.publishBlock(prepareCanonicalJournal(block));
  }

  private ArchiveInFlightBlock prepareCanonicalJournal(ArchiveInFlightBlock block) {
    inFlight.putBlock(block);
    inFlight.acknowledgeBlock(block.getJournalToken());
    return block.withJournalState(ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED);
  }

  private ArchiveInFlightBlock block(long blockNum, DomainValue previous, DomainValue current) {
    return block(blockNum, hash(blockNum + 1L), previous, current);
  }

  private ArchiveInFlightBlock block(
      BlockCapsule canonical, DomainValue previous, DomainValue current) {
    return block(canonical.getNum(), canonical.getBlockId().getBytes(), previous, current);
  }

  private ArchiveInFlightBlock block(
      long blockNum, byte[] blockHash, DomainValue previous, DomainValue current) {
    long firstTxNum = blockNum * 2L;
    ArchiveBlockRange range = new ArchiveBlockRange(
        blockNum, firstTxNum, firstTxNum + 1L, firstTxNum, firstTxNum + 1L,
        blockHash, 0, ArchiveSource.NORMAL, schemaChecksum);
    ArchiveTxPosition prepare = new ArchiveTxPosition(
        firstTxNum, blockNum, ArchivePhase.BLOCK_PREPARE, ArchiveSource.NORMAL, -1, null);
    ArchiveTxPosition finalize = new ArchiveTxPosition(
        firstTxNum + 1L, blockNum, ArchivePhase.BLOCK_FINALIZE,
        ArchiveSource.NORMAL, -1, null);
    ArchiveChangeRecord record = new ArchiveChangeRecord(
        prepare, ArchiveDomain.ACCOUNT, accountKey(), previous, current);
    return new ArchiveInFlightBlock(
        range, Arrays.asList(prepare, finalize), Collections.singletonList(record));
  }

  private DefaultArchiveService unifiedService() {
    ArchiveDomainRegistry registry = new DefaultArchiveDomainRegistry();
    ArchivePublisherConfig publisherConfig = new ArchivePublisherConfig(
        false, false, 32, 64, 1024L * 1024L, 2L * 1024L * 1024L,
        1_000L, 2_000L, 0L, 0L, 1_000L);
    return new DefaultArchiveService(true, index, new ArchiveExecutionContext(),
        temporal, inFlight, registry, catalog, ArchiveReadThrough.NONE,
        ArchiveLifecycle.Phase.RUNNING, ArchiveQueryLimits.unlimited(), publisherConfig,
        () -> backend.validateStartup(false, true), backend);
  }

  private void assertJournalCorruptionRejectedBeforeConsumer(
      String caseName, JournalCorruptor corruptor) throws Exception {
    Path casePath = temporaryFolder.newFolder(caseName).toPath().resolve("unified");
    UnifiedArchiveDb caseDb = UnifiedArchiveDb.initialize(casePath, schemaChecksum);
    try {
      UnifiedArchiveInFlightStore caseStore =
          new UnifiedArchiveInFlightStore(caseDb, catalog);
      caseStore.putBlock(block(0L, DomainValue.tombstone(), value(1)));
      ArchiveInFlightBlock corrupt = block(1L, value(1), value(2));
      corruptor.corrupt(caseDb, caseStore, corrupt);
      AtomicInteger consumed = new AtomicInteger();

      assertThrows(ArchiveException.class,
          () -> caseStore.forEachBlock(ignored -> consumed.incrementAndGet()));

      assertEquals(caseName, 0, consumed.get());
    } finally {
      caseDb.close();
    }
  }

  private void assertPublishedCorruptionRejected(
      String caseName, PublishedCorruptor corruptor) throws Exception {
    Path casePath = temporaryFolder.newFolder(caseName).toPath().resolve("unified");
    UnifiedArchiveDb caseDb = UnifiedArchiveDb.initialize(casePath, schemaChecksum);
    UnifiedArchiveTxNumIndex caseIndex = null;
    try {
      caseIndex = new UnifiedArchiveTxNumIndex(caseDb, schemaChecksum, false, false);
      UnifiedArchiveTemporalStore caseTemporal =
          new UnifiedArchiveTemporalStore(caseDb, catalog);
      UnifiedArchiveInFlightStore caseInFlight =
          new UnifiedArchiveInFlightStore(caseDb, catalog);
      UnifiedArchiveBackend caseBackend =
          new UnifiedArchiveBackend(caseDb, caseIndex, caseTemporal);
      ArchiveInFlightBlock published =
          block(0L, DomainValue.tombstone(), value(1));
      caseInFlight.putBlock(published);
      caseInFlight.acknowledgeBlock(published.getJournalToken());
      caseBackend.publishBlock(
          published.withJournalState(ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED));

      byte[] latestKey = firstKey(caseDb, UnifiedArchiveColumnFamily.LATEST);
      corruptor.corrupt(caseDb, latestKey);

      assertThrows(caseName, ArchiveException.class,
          () -> caseBackend.validateStartup(true, false));
    } finally {
      if (caseIndex == null) {
        caseDb.close();
      } else {
        caseIndex.close();
      }
    }
  }

  private static void deleteFirstRow(UnifiedArchiveDb db,
      UnifiedArchiveColumnFamily columnFamily) {
    db.writeMaintenanceAtomically(new UnifiedArchiveMaintenanceBatch()
        .delete(columnFamily, firstKey(db, columnFamily)));
  }

  private static void replaceFirstValue(UnifiedArchiveDb db,
      UnifiedArchiveColumnFamily columnFamily, byte[] value) {
    db.writeMaintenanceAtomically(new UnifiedArchiveMaintenanceBatch()
        .put(columnFamily, firstKey(db, columnFamily), value));
  }

  private static void putUnknownRow(UnifiedArchiveDb db,
      UnifiedArchiveColumnFamily columnFamily) {
    db.writeMaintenanceAtomically(new UnifiedArchiveMaintenanceBatch()
        .put(columnFamily, new byte[] {(byte) 0x7f}, new byte[] {1}));
  }

  private static byte[] firstKey(UnifiedArchiveDb db,
      UnifiedArchiveColumnFamily columnFamily) {
    try (org.tron.core.archive.unified.UnifiedArchiveReadView view = db.openReadView()) {
      RocksIterator iterator = view.newIterator(columnFamily);
      iterator.seekToFirst();
      assertTrue(columnFamily.getName() + " must contain a test row", iterator.isValid());
      return iterator.key().clone();
    }
  }

  private static byte[] firstValue(UnifiedArchiveDb db,
      UnifiedArchiveColumnFamily columnFamily) {
    try (org.tron.core.archive.unified.UnifiedArchiveReadView view = db.openReadView()) {
      RocksIterator iterator = view.newIterator(columnFamily);
      iterator.seekToFirst();
      assertTrue(columnFamily.getName() + " must contain a test row", iterator.isValid());
      return iterator.value().clone();
    }
  }

  private static ArchiveJournalToken differentGeneration(ArchiveJournalToken token) {
    byte[] nonce = token.getGenerationNonce();
    nonce[0] ^= 1;
    return new ArchiveJournalToken(
        token.getBlockNum(), token.getBlockHash(), nonce, token.getSchemaChecksum());
  }

  private static BlockCapsule canonicalBlock(long blockNum) {
    return new BlockCapsule(
        blockNum, Sha256Hash.ZERO_HASH, 1L, ByteString.EMPTY);
  }

  private static DomainValue value(int seed) {
    return DomainValue.present(Account.newBuilder().setBalance(seed).build().toByteArray());
  }

  private static byte[] accountKey() {
    byte[] key = new byte[21];
    key[0] = 0x41;
    return key;
  }

  private static byte[] hash(long seed) {
    byte[] hash = new byte[ArchiveBlockRange.BLOCK_HASH_LENGTH];
    hash[hash.length - 1] = (byte) seed;
    return hash;
  }

  private static void assertValue(Optional<DomainValue> actual, int expected) {
    assertTrue(actual.isPresent());
    assertFalse(actual.get().isDeleted());
    assertArrayEquals(Account.newBuilder().setBalance(expected).build().toByteArray(),
        actual.get().getValue());
  }

  @FunctionalInterface
  private interface JournalCorruptor {

    void corrupt(UnifiedArchiveDb db, UnifiedArchiveInFlightStore store,
        ArchiveInFlightBlock block);
  }

  @FunctionalInterface
  private interface PublishedCorruptor {

    void corrupt(UnifiedArchiveDb db, byte[] latestKey);
  }
}
