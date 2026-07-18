package org.tron.core.archive.temporal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.tron.core.archive.unified.UnifiedArchiveTestMaintenance.write;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveSource;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.domain.ArchiveDomainCatalog;
import org.tron.core.archive.domain.ArchiveSchemaChecksum;
import org.tron.core.archive.domain.DefaultArchiveDomainCatalog;
import org.tron.core.archive.domain.DefaultArchiveDomainRegistry;
import org.tron.core.archive.query.ArchiveQueryLimits;
import org.tron.core.archive.query.HistoricalQueryLimitException;
import org.tron.core.archive.query.QueryContext;
import org.tron.core.archive.query.QueryContextHolder;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.archive.txnum.ArchiveTxPosition;
import org.tron.core.archive.unified.UnifiedArchiveDb;
import org.tron.core.archive.unified.UnifiedArchiveColumnFamily;
import org.tron.core.archive.unified.UnifiedArchiveMaintenanceBatch;
import org.tron.core.archive.unified.UnifiedArchiveReadView;

/**
 * Independent model oracle for the UNIFIED_V1 temporal adapter. The in-memory store has no RocksDB
 * codecs or column-family wiring, so driving the same changes through both catches Unified key
 * routing, prev-value, tombstone and snapshot errors without an alternate disk layout.
 */
public class UnifiedArchiveTemporalStoreOracleTest {

  private static final ArchiveDomain DOMAIN = ArchiveDomain.DYNAMIC_PROPERTIES;
  // K1 is a strict byte-prefix of K2 (the variable-length-key trap).
  private static final byte[] K1 = "ENERGY_FEE".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] K2 = "ENERGY_FEE_HISTORY".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] K3 = "BANDWIDTH".getBytes(StandardCharsets.US_ASCII);

  private InMemoryArchiveTemporalStore mem;
  private UnifiedArchiveDb db;
  private UnifiedArchiveTemporalStore unified;
  private Path dir;
  private byte[] schemaChecksum;

  @Before
  public void setUp() throws IOException {
    mem = new InMemoryArchiveTemporalStore();
    dir = Files.createTempDirectory("unified-archive-temporal-oracle");
    ArchiveDomainCatalog catalog = new DefaultArchiveDomainCatalog();
    schemaChecksum = ArchiveSchemaChecksum.of(
        new DefaultArchiveDomainRegistry(), catalog);
    db = UnifiedArchiveDb.initialize(dir.resolve("unified"), schemaChecksum);
    unified = new UnifiedArchiveTemporalStore(db, catalog);
  }

  @After
  public void tearDown() {
    db.close();
    deleteRecursively(dir.toFile());
  }

  private void put(long txNum, byte[] key, DomainValue prev, DomainValue value) {
    ArchiveChangeRecord r = new ArchiveChangeRecord(
        new ArchiveTxPosition(txNum, 1, ArchivePhase.USER_TX, ArchiveSource.NORMAL, 0, null),
        DOMAIN, key, prev, value);
    mem.putChange(r);
    putUnifiedChange(unified, r);
  }

  private void putBlock(ArchiveBlockRange range, List<ArchiveChangeRecord> records) {
    mem.putBlockChanges(range, records);
    write(db, unified.prepareMaintenanceBatch(range, records));
  }

  private void putUnifiedChange(UnifiedArchiveTemporalStore store, ArchiveChangeRecord record) {
    write(db, store.prepareMaintenanceBatch(null, Collections.singletonList(record)));
  }

  private void overwriteIntegrityRow(UnifiedArchiveColumnFamily columnFamily,
      byte[] key, byte[] payload, long linkedTxNum) {
    write(db, new UnifiedArchiveMaintenanceBatch()
        .put(columnFamily, key,
            ArchiveTemporalIntegrityCodec.encode(
                columnFamily, key, payload, linkedTxNum))
        .put(UnifiedArchiveColumnFamily.TEMPORAL_PAYLOAD,
            ArchiveTemporalIntegrityCodec.payloadKey(columnFamily, key), payload));
  }

  private long persistedPayloadBytes(UnifiedArchiveColumnFamily columnFamily, byte[] key) {
    byte[] locatorBytes = db.get(columnFamily, key);
    assertTrue(locatorBytes != null);
    return ArchiveTemporalIntegrityCodec.decodeLocator(
        columnFamily, key, locatorBytes, "test temporal locator").payloadBytes();
  }

  private static ArchiveChangeRecord rec(long txNum, long blockNum, byte[] key, DomainValue prev,
      DomainValue value) {
    return new ArchiveChangeRecord(
        new ArchiveTxPosition(txNum, blockNum, ArchivePhase.USER_TX, ArchiveSource.NORMAL, 0, null),
        DOMAIN, key, prev, value);
  }

  private static DomainValue val(int b) {
    return DomainValue.present(new byte[] {(byte) b});
  }

  @Test
  public void temporalRowsUseFixedLocatorsAndOutOfLinePayloads() {
    DomainValue previous = DomainValue.tombstone();
    DomainValue current = val(0x0A);
    put(5L, K1, previous, current);

    assertOutOfLineRow(UnifiedArchiveColumnFamily.HISTORY,
        ArchiveTemporalCodec.historyKey(DOMAIN, K1, 5L),
        ArchiveTemporalCodec.encodeValue(previous));
    assertOutOfLineRow(UnifiedArchiveColumnFamily.CHANGESET,
        ArchiveTemporalCodec.changesetKey(5L, DOMAIN, K1),
        ArchiveTemporalCodec.encodeValue(current));
    assertOutOfLineRow(UnifiedArchiveColumnFamily.LATEST,
        ArchiveTemporalCodec.latestKey(DOMAIN, K1),
        ArchiveTemporalCodec.encodeValue(current));
    assertOutOfLineRow(UnifiedArchiveColumnFamily.COMMITMENT,
        ArchiveTemporalCodec.anchorKey(DOMAIN, K1),
        ArchiveTemporalCodec.encodeValue(previous));
  }

  @Test
  public void publicationPreflightAccountsLargestNativePayloadRead() {
    byte[] largeBytes = new byte[128 * 1024];
    Arrays.fill(largeBytes, (byte) 0x5a);
    DomainValue previous = DomainValue.tombstone();
    DomainValue current = DomainValue.present(largeBytes);
    put(5L, K1, previous, current);

    long anchorBytes = persistedPayloadBytes(
        UnifiedArchiveColumnFamily.COMMITMENT,
        ArchiveTemporalCodec.anchorKey(DOMAIN, K1));
    long latestBytes = persistedPayloadBytes(
        UnifiedArchiveColumnFamily.LATEST,
        ArchiveTemporalCodec.latestKey(DOMAIN, K1));
    long historyBytes = persistedPayloadBytes(
        UnifiedArchiveColumnFamily.HISTORY,
        ArchiveTemporalCodec.historyKey(DOMAIN, K1, 5L));
    long changesetBytes = persistedPayloadBytes(
        UnifiedArchiveColumnFamily.CHANGESET,
        ArchiveTemporalCodec.changesetKey(5L, DOMAIN, K1));
    long largestNativeRead = Math.max(
        Math.max(anchorBytes, latestBytes), Math.max(historyBytes, changesetBytes));
    long expectedBytes = anchorBytes + latestBytes + historyBytes + changesetBytes
        + latestBytes + largestNativeRead;
    ArchiveChangeRecord next = rec(6L, 2L, K1, current, val(0x0B));

    try (UnifiedArchiveTemporalStore.PublicationPreflight preflight =
        unified.preflightPublication(Collections.singletonList(next))) {
      assertEquals(expectedBytes, preflight.getPersistedPreparationBytes());
    }
  }

  @Test
  public void pointReadRejectsMissingOutOfLinePayload() {
    put(5L, K1, DomainValue.tombstone(), val(0x0A));
    byte[] historyKey = ArchiveTemporalCodec.historyKey(DOMAIN, K1, 5L);
    write(db, new UnifiedArchiveMaintenanceBatch().delete(
        UnifiedArchiveColumnFamily.TEMPORAL_PAYLOAD,
        ArchiveTemporalIntegrityCodec.payloadKey(
            UnifiedArchiveColumnFamily.HISTORY, historyKey)));

    ArchiveException failure = assertThrows(
        ArchiveException.class, () -> unified.getAsOf(DOMAIN, K1, 4L));

    assertTrue(failure.getMessage().contains("temporal payload is missing"));
    assertThrows(ArchiveException.class, unified::validateDomainRows);
  }

  @Test
  public void pointReadRejectsModifiedOutOfLinePayload() {
    put(5L, K1, DomainValue.tombstone(), val(0x0A));
    byte[] historyKey = ArchiveTemporalCodec.historyKey(DOMAIN, K1, 5L);
    byte[] payloadKey = ArchiveTemporalIntegrityCodec.payloadKey(
        UnifiedArchiveColumnFamily.HISTORY, historyKey);
    byte[] payload = db.get(UnifiedArchiveColumnFamily.TEMPORAL_PAYLOAD, payloadKey);
    payload[payload.length - 1] ^= 0x01;
    write(db, new UnifiedArchiveMaintenanceBatch().put(
        UnifiedArchiveColumnFamily.TEMPORAL_PAYLOAD, payloadKey, payload));

    ArchiveException failure = assertThrows(
        ArchiveException.class, () -> unified.getAsOf(DOMAIN, K1, 4L));

    assertTrue(failure.getMessage().contains("temporal payload digest mismatch"));
  }

  @Test
  public void pointReadRejectsOutOfLinePayloadLengthMismatch() {
    put(5L, K1, DomainValue.tombstone(), val(0x0A));
    byte[] historyKey = ArchiveTemporalCodec.historyKey(DOMAIN, K1, 5L);
    byte[] payloadKey = ArchiveTemporalIntegrityCodec.payloadKey(
        UnifiedArchiveColumnFamily.HISTORY, historyKey);
    byte[] payload = db.get(UnifiedArchiveColumnFamily.TEMPORAL_PAYLOAD, payloadKey);
    write(db, new UnifiedArchiveMaintenanceBatch().put(
        UnifiedArchiveColumnFamily.TEMPORAL_PAYLOAD, payloadKey,
        Arrays.copyOf(payload, payload.length + 1)));

    ArchiveException failure = assertThrows(
        ArchiveException.class, () -> unified.getAsOf(DOMAIN, K1, 4L));

    assertTrue(failure.getMessage().contains("payload length mismatch"));
  }

  @Test
  public void fullScrubRejectsOrphanAndUnknownPayloadKeys() {
    byte[] orphanLogicalKey = ArchiveTemporalCodec.historyKey(DOMAIN, K1, 5L);
    write(db, new UnifiedArchiveMaintenanceBatch().put(
        UnifiedArchiveColumnFamily.TEMPORAL_PAYLOAD,
        ArchiveTemporalIntegrityCodec.payloadKey(
            UnifiedArchiveColumnFamily.HISTORY, orphanLogicalKey),
        ArchiveTemporalCodec.encodeValue(val(0x0A))));

    ArchiveException orphan = assertThrows(
        ArchiveException.class, unified::validateDomainRows);
    assertTrue(orphan.getMessage().contains("no logical owner"));

    write(db, new UnifiedArchiveMaintenanceBatch()
        .delete(UnifiedArchiveColumnFamily.TEMPORAL_PAYLOAD,
            ArchiveTemporalIntegrityCodec.payloadKey(
                UnifiedArchiveColumnFamily.HISTORY, orphanLogicalKey))
        .put(UnifiedArchiveColumnFamily.TEMPORAL_PAYLOAD,
            new byte[] {(byte) 0x7f, 0x01}, new byte[] {0x01}));
    ArchiveException unknown = assertThrows(
        ArchiveException.class, unified::validateDomainRows);
    assertTrue(unknown.getMessage().contains("table tag is invalid"));
  }

  @Test
  public void committedGenesisUnwindIsRejectedWithoutTouchingLocatorPayloadPairs() {
    ArchiveBlockRange range = new ArchiveBlockRange(
        0L, 0L, 1L, 0L, 1L, new byte[32], 0, ArchiveSource.NORMAL);
    byte[] historyKey = ArchiveTemporalCodec.historyKey(DOMAIN, K1, 0L);
    byte[] changesetKey = ArchiveTemporalCodec.changesetKey(0L, DOMAIN, K1);
    byte[] latestKey = ArchiveTemporalCodec.latestKey(DOMAIN, K1);
    byte[] anchorKey = ArchiveTemporalCodec.anchorKey(DOMAIN, K1);
    putBlock(range,
        List.of(rec(0L, 0L, K1, DomainValue.tombstone(), val(0x0A))));

    ArchiveException failure = assertThrows(
        ArchiveException.class, () -> unified.unwindBlock(range));
    ArchiveException directFailure = assertThrows(
        ArchiveException.class, () -> unified.unwind(0L));

    assertTrue(failure.getMessage().contains("atomic backend transaction"));
    assertTrue(directFailure.getMessage().contains("atomic backend transaction"));
    assertRowAndPayloadPresent(UnifiedArchiveColumnFamily.HISTORY, historyKey);
    assertRowAndPayloadPresent(UnifiedArchiveColumnFamily.CHANGESET, changesetKey);
    assertRowAndPayloadPresent(UnifiedArchiveColumnFamily.LATEST, latestKey);
    assertRowAndPayloadPresent(UnifiedArchiveColumnFamily.COMMITMENT, anchorKey);
    unified.validateCommittedBlock(range);
  }

  @Test
  public void committedNonGenesisUnwindIsRejectedWithoutPartialDeletion() {
    ArchiveBlockRange first = new ArchiveBlockRange(
        0L, 0L, 1L, 0L, 1L, new byte[32], 0, ArchiveSource.NORMAL);
    ArchiveBlockRange second = new ArchiveBlockRange(
        1L, 2L, 3L, 2L, 3L, new byte[32], 0, ArchiveSource.NORMAL);
    putBlock(first,
        List.of(rec(0L, 0L, K1, DomainValue.tombstone(), val(0x0A))));
    putBlock(second, List.of(
        rec(2L, 1L, K1, val(0x0A), val(0x0B)),
        rec(3L, 1L, K2, DomainValue.tombstone(), val(0x20))));

    byte[] k1History = ArchiveTemporalCodec.historyKey(DOMAIN, K1, 2L);
    byte[] k1Changeset = ArchiveTemporalCodec.changesetKey(2L, DOMAIN, K1);
    byte[] k2History = ArchiveTemporalCodec.historyKey(DOMAIN, K2, 3L);
    byte[] k2Changeset = ArchiveTemporalCodec.changesetKey(3L, DOMAIN, K2);

    ArchiveException failure = assertThrows(
        ArchiveException.class, () -> unified.unwindBlock(second));

    assertTrue(failure.getMessage().contains("atomic backend transaction"));
    assertRowAndPayloadPresent(UnifiedArchiveColumnFamily.HISTORY, k1History);
    assertRowAndPayloadPresent(UnifiedArchiveColumnFamily.CHANGESET, k1Changeset);
    assertRowAndPayloadPresent(UnifiedArchiveColumnFamily.HISTORY, k2History);
    assertRowAndPayloadPresent(UnifiedArchiveColumnFamily.CHANGESET, k2Changeset);
    assertOutOfLineRow(UnifiedArchiveColumnFamily.LATEST,
        ArchiveTemporalCodec.latestKey(DOMAIN, K1),
        ArchiveTemporalCodec.encodeValue(val(0x0B)));
    assertOutOfLineRow(UnifiedArchiveColumnFamily.LATEST,
        ArchiveTemporalCodec.latestKey(DOMAIN, K2),
        ArchiveTemporalCodec.encodeValue(val(0x20)));
    unified.validateCommittedBlock(first);
    unified.validateCommittedBlock(second);
    unified.validateDomainRows();
  }

  private void assertOutOfLineRow(UnifiedArchiveColumnFamily columnFamily,
      byte[] key, byte[] expectedPayload) {
    assertEquals(ArchiveTemporalIntegrityCodec.LOCATOR_BYTES,
        db.get(columnFamily, key).length);
    assertTrue(Arrays.equals(expectedPayload,
        db.get(UnifiedArchiveColumnFamily.TEMPORAL_PAYLOAD,
            ArchiveTemporalIntegrityCodec.payloadKey(columnFamily, key))));
  }

  private void assertRowAndPayloadMissing(UnifiedArchiveColumnFamily columnFamily,
      byte[] key) {
    assertTrue(db.get(columnFamily, key) == null);
    assertTrue(db.get(UnifiedArchiveColumnFamily.TEMPORAL_PAYLOAD,
        ArchiveTemporalIntegrityCodec.payloadKey(columnFamily, key)) == null);
  }

  private void assertRowAndPayloadPresent(UnifiedArchiveColumnFamily columnFamily,
      byte[] key) {
    assertTrue(db.get(columnFamily, key) != null);
    assertTrue(db.get(UnifiedArchiveColumnFamily.TEMPORAL_PAYLOAD,
        ArchiveTemporalIntegrityCodec.payloadKey(columnFamily, key)) != null);
  }

  @Test
  public void openReadViewClosesUnifiedSnapshotWhenIteratorConstructionFails() {
    UnifiedArchiveDb failingDb = mock(UnifiedArchiveDb.class);
    UnifiedArchiveReadView view = mock(UnifiedArchiveReadView.class);
    AssertionError openFailure = new AssertionError("iterator allocation failed");
    AssertionError closeFailure = new AssertionError("snapshot close failed");
    when(failingDb.openScanView()).thenReturn(view);
    when(view.newIterator(UnifiedArchiveColumnFamily.HISTORY)).thenThrow(openFailure);
    doThrow(closeFailure).when(view).close();
    UnifiedArchiveTemporalStore store = new UnifiedArchiveTemporalStore(failingDb,
        new DefaultArchiveDomainCatalog());

    AssertionError thrown = assertThrows(AssertionError.class, store::openReadView);

    org.junit.Assert.assertSame(openFailure, thrown);
    assertEquals(1, thrown.getSuppressed().length);
    org.junit.Assert.assertSame(closeFailure, thrown.getSuppressed()[0]);
    verify(view).close();
  }

  @Test
  public void pointReadRejectsOrphanHistoryRow() {
    put(5L, K1, DomainValue.tombstone(), val(0x0A));
    write(db, new UnifiedArchiveMaintenanceBatch().put(
        UnifiedArchiveColumnFamily.HISTORY,
        ArchiveTemporalCodec.historyKey(DOMAIN, K1, 2L),
        ArchiveTemporalCodec.encodeValue(val(0x7F))));

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> unified.getAsOf(DOMAIN, K1, 1L));

    assertTrue(failure.getMessage().contains("changeset"));
  }

  @Test
  public void pointReadRejectsMalformedHistoryKeySharingCanonicalPrefix() {
    put(5L, K1, DomainValue.tombstone(), val(0x0A));
    byte[] valid = ArchiveTemporalCodec.historyKey(DOMAIN, K1, 2L);
    byte[] malformed = Arrays.copyOf(valid, valid.length + 1);
    write(db, new UnifiedArchiveMaintenanceBatch().put(
        UnifiedArchiveColumnFamily.HISTORY, malformed,
        ArchiveTemporalCodec.encodeValue(val(0x7F))));

    assertThrows(ArchiveException.class, () -> unified.getAsOf(DOMAIN, K1, 1L));
  }

  @Test
  public void pointReadRejectsLatestValueThatDisagreesWithLastChangeset() {
    put(5L, K1, DomainValue.tombstone(), val(0x0A));
    write(db, new UnifiedArchiveMaintenanceBatch().put(
        UnifiedArchiveColumnFamily.LATEST,
        ArchiveTemporalCodec.latestKey(DOMAIN, K1),
        ArchiveTemporalCodec.encodeValue(val(0x7F))));

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> unified.getAsOf(DOMAIN, K1, Long.MAX_VALUE));

    assertTrue(failure.getMessage().contains("latest"));
  }

  @Test
  public void pointReadRejectsHistoryEnvelopeModifiedAfterPublication() {
    put(5L, K1, DomainValue.tombstone(), val(0x0A));
    put(9L, K1, val(0x0A), val(0x0B));
    byte[] historyKey = ArchiveTemporalCodec.historyKey(DOMAIN, K1, 9L);
    byte[] modified = db.get(UnifiedArchiveColumnFamily.HISTORY, historyKey);
    modified[modified.length - 1] ^= 0x01;
    write(db, new UnifiedArchiveMaintenanceBatch().put(
        UnifiedArchiveColumnFamily.HISTORY,
        historyKey, modified));

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> unified.getAsOf(DOMAIN, K1, 7L));

    assertTrue(failure.getMessage().contains("payload digest mismatch"));
  }

  @Test
  public void pointReadRejectsDeletedHistoryGapInsteadOfReturningLaterPrevValue() {
    put(2L, K1, DomainValue.tombstone(), val(0x0A));
    put(5L, K1, val(0x0A), val(0x0B));
    put(9L, K1, val(0x0B), val(0x0C));
    byte[] deletedHistoryKey = ArchiveTemporalCodec.historyKey(DOMAIN, K1, 5L);
    write(db, new UnifiedArchiveMaintenanceBatch()
        .delete(UnifiedArchiveColumnFamily.HISTORY, deletedHistoryKey));

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> unified.getAsOf(DOMAIN, K1, 3L));

    assertTrue(failure.getMessage().contains("history chain"));
    assertThrows(ArchiveException.class, unified::validateDomainRows);
  }

  @Test
  public void pointReadRejectsValidEnvelopeWithRebasedHistoryEdge() {
    put(2L, K1, val(0x0A), val(0x0B));
    put(5L, K1, val(0x0B), val(0x0C));
    put(9L, K1, val(0x0C), val(0x0D));
    byte[] historyKey = ArchiveTemporalCodec.historyKey(DOMAIN, K1, 5L);
    overwriteIntegrityRow(
        UnifiedArchiveColumnFamily.HISTORY, historyKey,
        ArchiveTemporalCodec.encodeValue(val(0x0A)),
        ArchiveTemporalIntegrityCodec.NO_HISTORY_TX_NUM);

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> unified.getAsOf(DOMAIN, K1, 3L));

    assertTrue(failure.getMessage().contains("physical predecessor"));
  }

  @Test
  public void appendRejectsTailLinkSkippingPhysicalPredecessor() {
    put(2L, K1, val(0x0A), val(0x0B));
    put(5L, K1, val(0x0B), val(0x0C));
    put(9L, K1, val(0x0C), val(0x0D));
    byte[] historyKey = ArchiveTemporalCodec.historyKey(DOMAIN, K1, 9L);
    overwriteIntegrityRow(UnifiedArchiveColumnFamily.HISTORY, historyKey,
        ArchiveTemporalCodec.encodeValue(val(0x0C)), 2L);

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> putUnifiedChange(
            unified, rec(12L, 1L, K1, val(0x0D), val(0x0E))));

    assertTrue(failure.getMessage().contains("physical predecessor"));
    assertTrue(db.get(UnifiedArchiveColumnFamily.HISTORY,
        ArchiveTemporalCodec.historyKey(DOMAIN, K1, 12L)) == null);
    assertTrue(db.get(UnifiedArchiveColumnFamily.CHANGESET,
        ArchiveTemporalCodec.changesetKey(12L, DOMAIN, K1)) == null);
    assertTrue(Arrays.equals(new byte[] {0x0D}, unified.latest(DOMAIN, K1).get().getValue()));
  }

  @Test
  public void appendRejectsDeletedLatestInsteadOfResettingExistingHistoryChain() {
    put(2L, K1, DomainValue.tombstone(), val(0x0A));
    put(5L, K1, val(0x0A), val(0x0B));
    write(db, new UnifiedArchiveMaintenanceBatch().delete(
        UnifiedArchiveColumnFamily.LATEST,
        ArchiveTemporalCodec.latestKey(DOMAIN, K1)));
    ArchiveChangeRecord forged = rec(9L, 1L, K1, DomainValue.tombstone(), val(0x7F));

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> putUnifiedChange(unified, forged));

    assertTrue(failure.getMessage().contains("latest row is missing"));
  }

  @Test
  public void pointReadAndScrubRejectValidEnvelopeChangesetRewrite() {
    put(5L, K1, DomainValue.tombstone(), val(0x0A));
    put(9L, K1, val(0x0A), val(0x0B));
    byte[] changesetKey = ArchiveTemporalCodec.changesetKey(9L, DOMAIN, K1);
    overwriteIntegrityRow(
        UnifiedArchiveColumnFamily.CHANGESET, changesetKey,
        ArchiveTemporalCodec.encodeValue(val(0x7F)), 9L);

    ArchiveException pointFailure = assertThrows(ArchiveException.class,
        () -> unified.getAsOf(DOMAIN, K1, Long.MAX_VALUE));

    assertTrue(pointFailure.getMessage().contains("latest value"));
    assertThrows(ArchiveException.class, () -> unified.validateTxNumsCovered(txNum -> true));
  }

  @Test
  public void pointReadAndScrubRejectRawLatestBaselinePairWithoutEnvelopes() {
    byte[] value = ArchiveTemporalCodec.encodeValue(val(0x7F));
    write(db, new UnifiedArchiveMaintenanceBatch()
        .put(UnifiedArchiveColumnFamily.LATEST,
            ArchiveTemporalCodec.latestKey(DOMAIN, K3), value)
        .put(UnifiedArchiveColumnFamily.LATEST,
            ArchiveTemporalCodec.latestBaselineKey(DOMAIN, K3), value));

    ArchiveException pointFailure = assertThrows(ArchiveException.class,
        () -> unified.getAsOf(DOMAIN, K3, Long.MAX_VALUE));

    assertTrue(pointFailure.getMessage().contains("locator length mismatch"));
    assertThrows(ArchiveException.class, unified::validateDomainRows);
  }

  @Test
  public void pointReadRejectsDeletedLatestEnvelope() {
    put(5L, K1, DomainValue.tombstone(), val(0x0A));
    byte[] latestKey = ArchiveTemporalCodec.latestKey(DOMAIN, K1);
    write(db, new UnifiedArchiveMaintenanceBatch().delete(
        UnifiedArchiveColumnFamily.LATEST, latestKey));

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> unified.getAsOf(DOMAIN, K1, Long.MAX_VALUE));

    assertTrue(failure.getMessage().contains("latest value"));
  }

  @Test
  public void pointReadRejectsDeletingEveryMutableRowForKnownKey() {
    put(5L, K1, DomainValue.tombstone(), val(0x0A));
    write(db, new UnifiedArchiveMaintenanceBatch()
        .delete(UnifiedArchiveColumnFamily.HISTORY,
            ArchiveTemporalCodec.historyKey(DOMAIN, K1, 5L))
        .delete(UnifiedArchiveColumnFamily.CHANGESET,
            ArchiveTemporalCodec.changesetKey(5L, DOMAIN, K1))
        .delete(UnifiedArchiveColumnFamily.LATEST,
            ArchiveTemporalCodec.latestKey(DOMAIN, K1)));

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> unified.getAsOf(DOMAIN, K1, Long.MAX_VALUE));

    assertTrue(failure.getMessage().contains("anchor"));
    assertThrows(ArchiveException.class, unified::validateDomainRows);
  }

  @Test
  public void pointReadRejectsDeletingLatestAndBaselineForAnchoredKey() {
    put(5L, K1, DomainValue.tombstone(), val(0x0A));
    write(db, new UnifiedArchiveMaintenanceBatch()
        .delete(UnifiedArchiveColumnFamily.HISTORY,
            ArchiveTemporalCodec.historyKey(DOMAIN, K1, 5L))
        .delete(UnifiedArchiveColumnFamily.CHANGESET,
            ArchiveTemporalCodec.changesetKey(5L, DOMAIN, K1))
        .delete(UnifiedArchiveColumnFamily.LATEST,
            ArchiveTemporalCodec.latestKey(DOMAIN, K1))
        .delete(UnifiedArchiveColumnFamily.LATEST,
            ArchiveTemporalCodec.latestBaselineKey(DOMAIN, K1)));

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> unified.getAsOf(DOMAIN, K1, Long.MAX_VALUE));

    assertTrue(failure.getMessage().contains("anchor"));
    assertThrows(ArchiveException.class, unified::validateDomainRows);
  }

  @Test
  public void pointReadRejectsEitherHalfOfLatestBaselinePairMissing() {
    put(5L, K1, DomainValue.tombstone(), val(0x0A));
    byte[] latestKey = ArchiveTemporalCodec.latestKey(DOMAIN, K1);
    byte[] baselineKey = ArchiveTemporalCodec.latestBaselineKey(DOMAIN, K1);
    byte[] restoredPayload = ArchiveTemporalCodec.encodeValue(DomainValue.tombstone());
    write(db, new UnifiedArchiveMaintenanceBatch()
        .delete(UnifiedArchiveColumnFamily.HISTORY,
            ArchiveTemporalCodec.historyKey(DOMAIN, K1, 5L))
        .delete(UnifiedArchiveColumnFamily.CHANGESET,
            ArchiveTemporalCodec.changesetKey(5L, DOMAIN, K1)));
    overwriteIntegrityRow(UnifiedArchiveColumnFamily.LATEST, latestKey,
        restoredPayload, ArchiveTemporalIntegrityCodec.NO_HISTORY_TX_NUM);
    overwriteIntegrityRow(UnifiedArchiveColumnFamily.LATEST, baselineKey,
        restoredPayload, ArchiveTemporalIntegrityCodec.NO_HISTORY_TX_NUM);
    byte[] baselineValue = db.get(UnifiedArchiveColumnFamily.LATEST, baselineKey);

    write(db, new UnifiedArchiveMaintenanceBatch().delete(
        UnifiedArchiveColumnFamily.LATEST, baselineKey));
    assertThrows(ArchiveException.class,
        () -> unified.getAsOf(DOMAIN, K1, Long.MAX_VALUE));

    write(db, new UnifiedArchiveMaintenanceBatch()
        .put(UnifiedArchiveColumnFamily.LATEST, baselineKey, baselineValue)
        .delete(UnifiedArchiveColumnFamily.LATEST, latestKey));
    assertThrows(ArchiveException.class,
        () -> unified.getAsOf(DOMAIN, K1, Long.MAX_VALUE));
  }

  @Test
  public void pointReadAndScrubRejectDeletedAnchor() {
    put(5L, K1, DomainValue.tombstone(), val(0x0A));
    write(db, new UnifiedArchiveMaintenanceBatch().delete(
        UnifiedArchiveColumnFamily.COMMITMENT,
        ArchiveTemporalCodec.anchorKey(DOMAIN, K1)));

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> unified.getAsOf(DOMAIN, K1, Long.MAX_VALUE));

    assertTrue(failure.getMessage().contains("anchor"));
    assertThrows(ArchiveException.class, unified::validateDomainRows);
  }

  @Test
  public void latestRejectsMissingTailChangeset() {
    put(5L, K1, DomainValue.tombstone(), val(0x0A));
    write(db, new UnifiedArchiveMaintenanceBatch().delete(
        UnifiedArchiveColumnFamily.CHANGESET,
        ArchiveTemporalCodec.changesetKey(5L, DOMAIN, K1)));

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> unified.latest(DOMAIN, K1));

    assertTrue(failure.getMessage().contains("changeset"));
  }

  @Test
  public void blockMarkerRejectsValidEnvelopeWithForgedHistoryLink() {
    ArchiveBlockRange range = new ArchiveBlockRange(
        3L, 5L, 6L, 5L, 6L, new byte[32], 0, ArchiveSource.NORMAL);
    putBlock(range,
        List.of(rec(5L, 3L, K1, DomainValue.tombstone(), val(0x0A))));
    byte[] historyKey = ArchiveTemporalCodec.historyKey(DOMAIN, K1, 5L);
    overwriteIntegrityRow(
        UnifiedArchiveColumnFamily.HISTORY, historyKey,
        ArchiveTemporalCodec.encodeValue(DomainValue.tombstone()), 4L);

    assertThrows(ArchiveException.class, () -> unified.validateCommittedBlock(range));
  }

  @Test
  public void replacementBlockCannotBypassAtomicBackendUnwindRequirement() {
    ArchiveBlockRange range = new ArchiveBlockRange(
        3L, 5L, 6L, 5L, 6L, new byte[32], 0, ArchiveSource.NORMAL);
    putBlock(range,
        List.of(rec(5L, 3L, K1, DomainValue.tombstone(), val(0x0A))));

    ArchiveException failure = assertThrows(
        ArchiveException.class, () -> unified.unwindBlock(range));

    assertTrue(failure.getMessage().contains("atomic backend transaction"));
    unified.validateCommittedBlock(range);
    unified.validateDomainRows();
    assertTrue(Arrays.equals(new byte[] {0x0A}, unified.latest(DOMAIN, K1).get().getValue()));
  }

  @Test
  public void directUnwindRejectsCommittedRangeWithoutLeavingMarkerStateAmbiguous() {
    ArchiveBlockRange range = new ArchiveBlockRange(
        3L, 5L, 6L, 5L, 6L, new byte[32], 0, ArchiveSource.NORMAL);
    putBlock(range,
        List.of(rec(5L, 3L, K1, DomainValue.tombstone(), val(0x0A))));

    ArchiveException unifiedFailure = assertThrows(ArchiveException.class,
        () -> unified.unwind(5L));
    ArchiveException memoryFailure = assertThrows(ArchiveException.class,
        () -> mem.unwind(5L));

    assertTrue(unifiedFailure.getMessage().contains("atomic backend transaction"));
    assertTrue(memoryFailure.getMessage().contains("unwindBlock"));
    assertTrue(unified.hasCommitMarker(3L));
    unified.validateCommittedBlock(range);
    assertTrue(Arrays.equals(
        new byte[] {0x0A}, unified.latest(DOMAIN, K1).get().getValue()));
  }

  @Test
  public void directUnwindRejectsUncommittedRowsBeforeMaintenanceBudgeting() {
    put(5L, K1, DomainValue.tombstone(), val(0x0A));
    put(5L, K2, DomainValue.tombstone(), val(0x0B));
    UnifiedArchiveTemporalStore bounded = new UnifiedArchiveTemporalStore(
        db, new DefaultArchiveDomainCatalog(), 1_200L, 100L);

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> bounded.unwind(5L));

    assertTrue(failure.getMessage().contains("atomic backend transaction"));
    assertTrue(Arrays.equals(
        new byte[] {0x0A}, unified.latest(DOMAIN, K1).get().getValue()));
    assertTrue(Arrays.equals(
        new byte[] {0x0B}, unified.latest(DOMAIN, K2).get().getValue()));
    unified.validateDomainRows();
  }

  @Test
  public void preparationBudgetRejectsBeforeTemporalRowsAreAllocatedOrWritten() {
    UnifiedArchiveTemporalStore byteBounded = new UnifiedArchiveTemporalStore(
        db, new DefaultArchiveDomainCatalog(), 1_000L, 100L);
    ArchiveChangeRecord record = rec(
        5L, 1L, K1, DomainValue.tombstone(), DomainValue.present(new byte[128]));

    ArchiveException byteFailure = assertThrows(
        ArchiveException.class, () -> putUnifiedChange(byteBounded, record));

    assertTrue(byteFailure.getMessage().contains("preparation exceeds retained byte limit"));
    assertTrue(!unified.latest(DOMAIN, K1).isPresent());

    UnifiedArchiveTemporalStore mutationBounded = new UnifiedArchiveTemporalStore(
        db, new DefaultArchiveDomainCatalog(), 1_000_000L, 9L);
    ArchiveException mutationFailure = assertThrows(
        ArchiveException.class, () -> putUnifiedChange(mutationBounded, record));

    assertTrue(mutationFailure.getMessage().contains("preparation exceeds mutation limit"));
    assertTrue(!unified.latest(DOMAIN, K1).isPresent());
  }

  @Test
  public void directUnwindZeroRejectsAndRestartPreservesRows() {
    put(5L, K1, DomainValue.tombstone(), val(0x0A));

    ArchiveException failure = assertThrows(ArchiveException.class, () -> unified.unwind(0L));
    assertTrue(failure.getMessage().contains("atomic backend transaction"));
    db.close();
    db = UnifiedArchiveDb.open(dir.resolve("unified"), schemaChecksum);
    unified = new UnifiedArchiveTemporalStore(db, new DefaultArchiveDomainCatalog());

    assertTrue(Arrays.equals(
        new byte[] {0x0A}, unified.latest(DOMAIN, K1).get().getValue()));
    unified.validateDomainRows();
  }

  @Test
  public void pointReadRejectsOversizedBackendValueBeforeMaterialization() {
    DomainValue eightBytes = DomainValue.present(new byte[Long.BYTES]);
    put(5L, K1, DomainValue.tombstone(), eightBytes);
    QueryContext context = new QueryContext(ArchiveQueryLimits.builder()
        .maxBackendValueBytes(4L)
        .build());

    HistoricalQueryLimitException failure;
    try (QueryContextHolder.Scope ignored = QueryContextHolder.attach(context)) {
      failure = assertThrows(HistoricalQueryLimitException.class,
          () -> unified.getAsOf(DOMAIN, K1, Long.MAX_VALUE));
    }

    assertEquals(HistoricalQueryLimitException.Limit.BACKEND_VALUE_BYTES,
        failure.getLimit());
    assertEquals(0L, context.getBackendReadBytes());
  }

  @Test
  public void backendReadCostDelegatesToPhysicalOperationAccounting() {
    assertEquals(0L, unified.getAsOfBackendReadCost());
    try (ArchiveTemporalReadView view = unified.openReadView()) {
      assertEquals(0L, view.getAsOfBackendReadCost());
    }

    QueryContext context = new QueryContext(ArchiveQueryLimits.unlimited());
    try (QueryContextHolder.Scope ignored = QueryContextHolder.attach(context)) {
      assertEquals(Optional.empty(), unified.latest(DOMAIN, K1));
    }
    // Anchor, latest, history seek and baseline are four actual native operations.
    assertEquals(4L, context.getBackendReads());
  }

  /** Assert the two stores return identical getAsOf for a key over a txNum window, plus latest. */
  private void assertParity(byte[] key, long maxTxNum) {
    for (long t = 0; t <= maxTxNum; t++) {
      assertSame("getAsOf(" + new String(key, StandardCharsets.US_ASCII) + ", " + t + ")",
          mem.getAsOf(DOMAIN, key, t), unified.getAsOf(DOMAIN, key, t));
    }
    assertSame("latest(" + new String(key, StandardCharsets.US_ASCII) + ")",
        mem.latest(DOMAIN, key), unified.latest(DOMAIN, key));
  }

  private static void assertSame(String what, Optional<DomainValue> a, Optional<DomainValue> b) {
    assertEquals(what + " presence", a.isPresent(), b.isPresent());
    if (a.isPresent()) {
      assertEquals(what + " deleted", a.get().isDeleted(), b.get().isDeleted());
      assertTrue(what + " value", Arrays.equals(a.get().getValue(), b.get().getValue()));
    }
  }

  @Test
  public void inMemoryAndUnifiedAgreeBeforeInMemoryOnlyUnwind() {
    // K1: created at tx2 (0x0A), 0x0A -> 0x0B at tx5, deleted at tx9.
    put(2, K1, DomainValue.tombstone(), val(0x0A));
    put(5, K1, val(0x0A), val(0x0B));
    put(9, K1, val(0x0B), DomainValue.tombstone());
    // K2 (prefix-colliding): created at tx3 (0x21), 0x21 -> 0x22 at tx7.
    put(3, K2, DomainValue.tombstone(), val(0x21));
    put(7, K2, val(0x21), val(0x22));
    // K3: mid-chain -- existed as 0x30 before coverage; first captured change at tx6 (0x30->0x31).
    put(6, K3, val(0x30), val(0x31));

    for (byte[] k : List.of(K1, K2, K3)) {
      assertParity(k, 12);
    }

    ArchiveException failure = assertThrows(ArchiveException.class, () -> unified.unwind(6));
    assertTrue(failure.getMessage().contains("atomic backend transaction"));

    // The in-memory test oracle retains local unwind for algorithm tests; the production Unified
    // adapter must stay byte-stable until a cross-CF backend transaction exists.
    mem.unwind(6);
    assertTrue(Arrays.equals(new byte[] {0x0B}, mem.latest(DOMAIN, K1).get().getValue()));
    assertTrue(Arrays.equals(new byte[] {0x21}, mem.latest(DOMAIN, K2).get().getValue()));
    assertTrue(Arrays.equals(new byte[] {0x30}, mem.latest(DOMAIN, K3).get().getValue()));
    assertTrue(unified.latest(DOMAIN, K1).get().isDeleted());
    assertTrue(Arrays.equals(new byte[] {0x22}, unified.latest(DOMAIN, K2).get().getValue()));
    assertTrue(Arrays.equals(new byte[] {0x31}, unified.latest(DOMAIN, K3).get().getValue()));
  }

  @Test
  public void inMemoryAndUnifiedAgreeOnDeleteThenRecreate() {
    // Created, deleted, then re-created. A read inside the deleted window [9,11] must return a
    // TOMBSTONE sourced from the NEXT change's (tx12) pre-value via a HISTORY seek -- NOT fall-to-
    // latest (latest is the recreated 0x0B) and NOT empty. No existing test has a change after a
    // delete, so this history-seek-returns-tombstone branch was previously undriven.
    put(5, K1, DomainValue.tombstone(), val(0x0A));
    put(9, K1, val(0x0A), DomainValue.tombstone());
    put(12, K1, DomainValue.tombstone(), val(0x0B));

    for (long t : new long[] {9, 10, 11}) {
      Optional<DomainValue> v = unified.getAsOf(DOMAIN, K1, t);
      assertTrue("as-of " + t + " present-but-deleted", v.isPresent() && v.get().isDeleted());
    }
    // value at end of tx8 is 0x0A; at/after tx12 it is the recreated 0x0B.
    assertTrue(Arrays.equals(
        new byte[] {0x0A}, unified.getAsOf(DOMAIN, K1, 8).get().getValue()));
    assertTrue(Arrays.equals(
        new byte[] {0x0B}, unified.getAsOf(DOMAIN, K1, 12).get().getValue()));
    // both stores agree at every txNum across the whole create/delete/recreate lifecycle.
    assertParity(K1, 15);
  }

  @Test
  public void unifiedCommittedUnwindAlwaysRequiresAtomicBackendTransaction() {
    ArchiveBlockRange b3 = new ArchiveBlockRange(
        3, 10, 11, 10, 11, new byte[32], 0, ArchiveSource.NORMAL);
    ArchiveBlockRange b4 = new ArchiveBlockRange(
        4, 12, 13, 12, 13, new byte[32], 0, ArchiveSource.NORMAL);
    putBlock(b3, List.of(rec(10, 3, K1, DomainValue.tombstone(), val(0x0A))));
    putBlock(b4, List.of(rec(12, 4, K1, val(0x0A), val(0x0B))));

    assertTrue(assertThrows(ArchiveException.class, () -> mem.unwindBlock(b3))
        .getMessage().contains("not temporal head"));
    assertTrue(assertThrows(ArchiveException.class, () -> unified.unwindBlock(b3))
        .getMessage().contains("atomic backend transaction"));
    assertParity(K1, 15);

    mem.unwindBlock(b4);
    assertTrue(assertThrows(ArchiveException.class, () -> unified.unwindBlock(b4))
        .getMessage().contains("atomic backend transaction"));
    assertTrue(Arrays.equals(new byte[] {0x0A}, mem.latest(DOMAIN, K1).get().getValue()));
    assertTrue(Arrays.equals(new byte[] {0x0B}, unified.latest(DOMAIN, K1).get().getValue()));
    unified.validateCommittedBlock(b3);
    unified.validateCommittedBlock(b4);
  }

  @Test
  public void inMemoryAndUnifiedAgreeRejectingNonHeadUnwindWhenHeadBlockIsEmpty() {
    // An empty head block (no state change, no history row) must still make a lower block non-head
    // in BOTH stores -- Unified via its block-marker CF, InMemory via its committed-block set.
    ArchiveBlockRange b3 = new ArchiveBlockRange(
        3, 10, 11, 10, 11, new byte[32], 0, ArchiveSource.NORMAL);
    ArchiveBlockRange b4empty = new ArchiveBlockRange(
        4, 12, 13, 12, 13, new byte[32], 0, ArchiveSource.NORMAL);
    putBlock(b3, List.of(rec(10, 3, K1, DomainValue.tombstone(), val(0x0A))));
    putBlock(b4empty, List.of());

    assertTrue(assertThrows(ArchiveException.class, () -> mem.unwindBlock(b3))
        .getMessage().contains("not temporal head"));
    assertTrue(assertThrows(ArchiveException.class, () -> unified.unwindBlock(b3))
        .getMessage().contains("atomic backend transaction"));
    assertParity(K1, 15);
  }

  @Test
  public void readViewIsIsolatedFromWritesAfterItOpensInBothStores() {
    // K1 created at tx2 (0x0A). Open a read view on each store, THEN move K1 to 0x0B at tx5. Live
    // reads must see 0x0B; the views must keep returning the snapshot-time 0x0A -- Unified via a
    // real snapshot, InMemory via a deep copy. This is what lets a VM run after the lock releases.
    put(2, K1, DomainValue.tombstone(), val(0x0A));

    try (ArchiveTemporalReadView memView = mem.openReadView();
        ArchiveTemporalReadView unifiedView = unified.openReadView()) {
      put(5, K1, val(0x0A), val(0x0B));

      assertTrue(Arrays.equals(new byte[] {0x0B}, mem.latest(DOMAIN, K1).get().getValue()));
      assertTrue(Arrays.equals(new byte[] {0x0B}, unified.latest(DOMAIN, K1).get().getValue()));
      assertTrue(Arrays.equals(new byte[] {0x0A}, memView.latest(DOMAIN, K1).get().getValue()));
      assertTrue(Arrays.equals(
          new byte[] {0x0A}, unifiedView.latest(DOMAIN, K1).get().getValue()));
      assertTrue(Arrays.equals(new byte[] {0x0A},
          memView.getAsOf(DOMAIN, K1, 100).get().getValue()));
      assertTrue(Arrays.equals(new byte[] {0x0A},
          unifiedView.getAsOf(DOMAIN, K1, 100).get().getValue()));
    }
  }

  private static void deleteRecursively(File f) {
    File[] children = f.listFiles();
    if (children != null) {
      for (File c : children) {
        deleteRecursively(c);
      }
    }
    f.delete();
  }
}
