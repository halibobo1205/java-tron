package org.tron.core.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
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
  public void journalScanRejectsUnknownRows() {
    db.putJournalDurably(new byte[] {(byte) 0x7f}, new byte[] {1});
    assertThrows(ArchiveException.class, inFlight::loadBlocks);
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
    long firstTxNum = blockNum * 2L;
    byte[] blockHash = hash(blockNum + 1L);
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
}
