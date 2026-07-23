package org.tron.core.archive.unified;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.LongSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.rocksdb.BloomFilter;
import org.rocksdb.Cache;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.ReadOptions;
import org.rocksdb.Snapshot;
import org.rocksdb.Statistics;
import org.rocksdb.StatsLevel;
import org.rocksdb.TickerType;
import org.rocksdb.WriteOptions;
import org.tron.common.utils.ReflectUtils;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.ArchiveNativeResourceReleaseException;
import org.tron.core.archive.ArchivePersistentStateCorruptionException;
import org.tron.core.archive.ArchiveSnapshotReleaseException;
import org.tron.core.archive.ArchiveStorageAccessException;
import org.tron.core.archive.query.ArchiveQueryCoordinator;
import org.tron.core.archive.query.ArchiveQueryLimits;
import org.tron.core.archive.query.ArchiveQueryRequestScope;
import org.tron.core.archive.query.ArchiveSnapshotPermit;
import org.tron.core.archive.query.HistoricalQueryLimitException;
import org.tron.core.archive.query.QueryContext;
import org.tron.core.archive.query.QueryContextHolder;
import org.tron.core.archive.query.QueryLease;
import org.tron.core.archive.txnum.ArchiveBlockRangeCodec;
import org.tron.core.archive.txnum.UnifiedArchiveTxNumIndex;

public class UnifiedArchiveDbTest {

  private static final byte[] SCHEMA_CHECKSUM = repeated(0x5a);
  private static final byte[] JOURNAL_KEY = ascii("block-7");
  private static final byte[] JOURNAL_VALUE = ascii("journal-value");
  private static final byte[] TOKEN_KEY = ascii("token-7");
  private static final byte[] TOKEN_VALUE = ascii("token-value");
  private static final byte[] ACKNOWLEDGEMENT_KEY = ascii("ack-7");
  private static final byte[] ACKNOWLEDGEMENT_VALUE = TOKEN_VALUE;
  private static final byte[] CURSOR_KEY = ascii("published-cursor");
  private static final byte[] CURSOR_VALUE = ascii("cursor-8");
  private static final byte[] INDEX_KEY = ascii("index-7");
  private static final byte[] INDEX_VALUE = ascii("index-value");
  private static final byte[] LATEST_KEY = ascii("latest-key");
  private static final byte[] LATEST_VALUE = ascii("latest-value");
  private static final byte[] HISTORY_KEY = ascii("history-key");
  private static final byte[] HISTORY_VALUE = ascii("history-value");
  private static final byte[] CHANGESET_KEY = ascii("changeset-key");
  private static final byte[] CHANGESET_VALUE = ascii("changeset-value");
  private static final byte[] MARKER_KEY = ascii("marker-7");
  private static final byte[] MARKER_VALUE = ascii("marker-value");
  private static final byte[] COMMITMENT_KEY = ascii("commitment-7");
  private static final byte[] COMMITMENT_VALUE = ascii("commitment-value");
  private static final byte[] TEMPORAL_PAYLOAD_KEY = ascii("payload-key");
  private static final byte[] TEMPORAL_PAYLOAD_VALUE = ascii("payload-value");

  private Path root;
  private Path dbPath;
  private UnifiedArchiveDb db;

  @Before
  public void setUp() throws IOException {
    root = Files.createTempDirectory("unified-archive-db-test");
    dbPath = root.resolve("unified");
    db = UnifiedArchiveDb.initialize(dbPath, SCHEMA_CHECKSUM);
  }

  @After
  public void tearDown() {
    if (db != null) {
      db.close();
    }
    deleteRecursively(root.toFile());
  }

  @Test
  public void publishedRowsAndColumnFamiliesSurviveReopen() throws Exception {
    putJournalBundle();
    db.publishBlockAtomically(publish(), true);

    db.close();
    db = UnifiedArchiveDb.open(dbPath, SCHEMA_CHECKSUM);

    try (UnifiedArchiveReadView view = db.openReadView()) {
      assertPublished(view);
    }
    assertExactColumnFamilies();
  }

  @Test
  public void resourceCleanupAttemptsEveryOwnerAndPreservesPrimaryFailure() {
    List<String> closed = new ArrayList<>();
    AssertionError primary = new AssertionError("first close failed");
    IllegalStateException secondary = new IllegalStateException("second close failed");

    Throwable failure = null;
    failure = UnifiedArchiveDb.closeAndCollect(failure, () -> {
      closed.add("first");
      throw primary;
    });
    failure = UnifiedArchiveDb.closeAndCollect(failure, () -> {
      closed.add("second");
      throw secondary;
    });
    failure = UnifiedArchiveDb.closeAndCollect(failure, () -> closed.add("third"));

    assertEquals(Arrays.asList("first", "second", "third"), closed);
    assertSame(primary, failure);
    assertEquals(1, failure.getSuppressed().length);
    assertSame(secondary, failure.getSuppressed()[0]);
    Throwable collectedFailure = failure;
    assertSame(primary, assertThrows(
        AssertionError.class,
        () -> UnifiedArchiveDb.rethrowCloseFailure(collectedFailure)));
  }

  @Test
  public void failedBatchLeavesJournalAndNoPublishedRowsThenRetrySucceeds() {
    putJournalBundle();
    db.close();

    AtomicInteger attempts = new AtomicInteger();
    db = UnifiedArchiveDb.open(dbPath, SCHEMA_CHECKSUM, (rocksDb, writeOptions, batch) -> {
      assertFalse(writeOptions.disableWAL());
      assertEquals(11, batch.count());
      if (attempts.getAndIncrement() == 0) {
        assertFalse(writeOptions.sync());
        throw new RocksDBException("injected publish failure");
      }
      assertTrue(writeOptions.sync());
      rocksDb.write(writeOptions, batch);
    });

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> db.publishBlockAtomically(publish(), false));
    assertTrue(failure.getMessage().contains("atomic block publish failed"));
    assertEquals(1, attempts.get());
    try (UnifiedArchiveReadView view = db.openReadView()) {
      assertUnpublished(view);
    }

    db.publishBlockAtomically(publish(), true);
    assertEquals(2, attempts.get());
    try (UnifiedArchiveReadView view = db.openReadView()) {
      assertPublished(view);
    }
  }

  @Test
  public void exceptionAfterDurablePublishReopensAsOneCompleteCommit() {
    putJournalBundle();
    db.close();

    db = UnifiedArchiveDb.open(dbPath, SCHEMA_CHECKSUM, (rocksDb, writeOptions, batch) -> {
      assertFalse(writeOptions.disableWAL());
      assertTrue(writeOptions.sync());
      rocksDb.write(writeOptions, batch);
      throw new RocksDBException("injected failure after durable publish");
    });

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> db.publishBlockAtomically(publish(), true));
    assertTrue(failure.getMessage().contains("atomic block publish failed"));

    db.close();
    db = UnifiedArchiveDb.open(dbPath, SCHEMA_CHECKSUM);
    try (UnifiedArchiveReadView view = db.openReadView()) {
      assertPublished(view);
    }
  }

  @Test
  public void failedInitialJournalBundleBatchLeavesNoPartialRowsAndRetrySucceeds() {
    db.close();

    AtomicInteger attempts = new AtomicInteger();
    db = UnifiedArchiveDb.open(dbPath, SCHEMA_CHECKSUM, (rocksDb, writeOptions, batch) -> {
      assertFalse(writeOptions.disableWAL());
      assertTrue(writeOptions.sync());
      assertEquals(2, batch.count());
      if (attempts.getAndIncrement() == 0) {
        throw new RocksDBException("injected initial journal bundle failure");
      }
      rocksDb.write(writeOptions, batch);
    });

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> db.putJournalBlockDurably(
            JOURNAL_KEY, JOURNAL_VALUE, TOKEN_KEY, TOKEN_VALUE,
            ACKNOWLEDGEMENT_KEY, null));
    assertTrue(failure.getMessage().contains("journal bundle write failed"));
    assertEquals(1, attempts.get());
    assertNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT, JOURNAL_KEY));
    assertNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT, TOKEN_KEY));
    assertNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT, ACKNOWLEDGEMENT_KEY));

    db.putJournalBlockDurably(
        JOURNAL_KEY, JOURNAL_VALUE, TOKEN_KEY, TOKEN_VALUE,
        ACKNOWLEDGEMENT_KEY, null);
    assertEquals(2, attempts.get());
    assertArrayEquals(JOURNAL_VALUE,
        db.get(UnifiedArchiveColumnFamily.INFLIGHT, JOURNAL_KEY));
    assertArrayEquals(TOKEN_VALUE,
        db.get(UnifiedArchiveColumnFamily.INFLIGHT, TOKEN_KEY));
    assertNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT, ACKNOWLEDGEMENT_KEY));

    db.putJournalBlockDurably(
        JOURNAL_KEY, JOURNAL_VALUE, TOKEN_KEY, TOKEN_VALUE,
        ACKNOWLEDGEMENT_KEY, null);
    assertEquals(2, attempts.get());
  }

  @Test
  public void publishRequiresAcknowledgementAndLeavesJournalBundleUntouched() {
    db.putJournalBlockDurably(
        JOURNAL_KEY, JOURNAL_VALUE, TOKEN_KEY, TOKEN_VALUE, ACKNOWLEDGEMENT_KEY, null);

    assertThrows(ArchiveException.class, () -> db.publishBlockAtomically(publish(), false));

    assertArrayEquals(JOURNAL_VALUE,
        db.get(UnifiedArchiveColumnFamily.INFLIGHT, JOURNAL_KEY));
    assertArrayEquals(TOKEN_VALUE,
        db.get(UnifiedArchiveColumnFamily.INFLIGHT, TOKEN_KEY));
    assertNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT, ACKNOWLEDGEMENT_KEY));
    assertNull(db.get(UnifiedArchiveColumnFamily.META, CURSOR_KEY));
    assertNull(db.get(UnifiedArchiveColumnFamily.INDEX, INDEX_KEY));
  }

  @Test
  public void failedRollbackBatchLeavesWholeJournalBundleThenRetryDeletesIt() {
    putJournalBundle();
    db.close();

    AtomicInteger attempts = new AtomicInteger();
    db = UnifiedArchiveDb.open(dbPath, SCHEMA_CHECKSUM, (rocksDb, writeOptions, batch) -> {
      assertFalse(writeOptions.disableWAL());
      assertTrue(writeOptions.sync());
      assertEquals(3, batch.count());
      if (attempts.getAndIncrement() == 0) {
        throw new RocksDBException("injected rollback failure");
      }
      rocksDb.write(writeOptions, batch);
    });

    assertThrows(ArchiveException.class, () -> db.deleteJournalBlockDurably(
        JOURNAL_KEY, JOURNAL_VALUE, TOKEN_KEY, TOKEN_VALUE,
        ACKNOWLEDGEMENT_KEY, ACKNOWLEDGEMENT_VALUE));
    try (UnifiedArchiveReadView view = db.openReadView()) {
      assertUnpublished(view);
    }

    db.deleteJournalBlockDurably(
        JOURNAL_KEY, JOURNAL_VALUE, TOKEN_KEY, TOKEN_VALUE,
        ACKNOWLEDGEMENT_KEY, ACKNOWLEDGEMENT_VALUE);
    assertEquals(2, attempts.get());
    assertNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT, JOURNAL_KEY));
    assertNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT, TOKEN_KEY));
    assertNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT, ACKNOWLEDGEMENT_KEY));
  }

  @Test
  public void verifiedDeleteRejectsPayloadBeforeItsAdmittedLimit() {
    putJournalBundle();

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> db.deleteVerifiedJournalBlockDurably(
            JOURNAL_KEY, journalVerifier(JOURNAL_VALUE), JOURNAL_VALUE.length - 1L,
            TOKEN_KEY, TOKEN_VALUE, ACKNOWLEDGEMENT_KEY, ACKNOWLEDGEMENT_VALUE));

    assertTrue(failure.getMessage().contains("admitted payload limit"));
    assertArrayEquals(JOURNAL_VALUE,
        db.get(UnifiedArchiveColumnFamily.INFLIGHT, JOURNAL_KEY));
    assertArrayEquals(TOKEN_VALUE,
        db.get(UnifiedArchiveColumnFamily.INFLIGHT, TOKEN_KEY));
    assertArrayEquals(ACKNOWLEDGEMENT_VALUE,
        db.get(UnifiedArchiveColumnFamily.INFLIGHT, ACKNOWLEDGEMENT_KEY));
  }

  @Test
  public void verifiedDeleteFreezesAdmittedVerifierLength() {
    putJournalBundle();
    AtomicInteger lengthReads = new AtomicInteger();
    UnifiedArchiveJournalVerifier changingVerifier = changingLengthVerifier(lengthReads);

    db.deleteVerifiedJournalBlockDurably(
        JOURNAL_KEY, changingVerifier, JOURNAL_VALUE.length,
        TOKEN_KEY, TOKEN_VALUE, ACKNOWLEDGEMENT_KEY, ACKNOWLEDGEMENT_VALUE);

    assertEquals(1, lengthReads.get());
    assertNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT, JOURNAL_KEY));
    assertNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT, TOKEN_KEY));
    assertNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT, ACKNOWLEDGEMENT_KEY));
  }

  @Test
  public void acknowledgementMustMatchTokenHeaderAtEveryWriteBoundary() {
    assertThrows(ArchiveException.class, () -> db.putJournalBlockDurably(
        JOURNAL_KEY, JOURNAL_VALUE, TOKEN_KEY, TOKEN_VALUE,
        ACKNOWLEDGEMENT_KEY, ascii("different-token")));

    db.putJournalBlockDurably(
        JOURNAL_KEY, JOURNAL_VALUE, TOKEN_KEY, TOKEN_VALUE, ACKNOWLEDGEMENT_KEY, null);
    assertThrows(ArchiveException.class, () -> db.acknowledgeJournalWalOnly(
        JOURNAL_KEY, TOKEN_KEY, TOKEN_VALUE,
        ACKNOWLEDGEMENT_KEY, ascii("different-token")));
    assertNull(db.get(UnifiedArchiveColumnFamily.INFLIGHT, ACKNOWLEDGEMENT_KEY));

    assertThrows(ArchiveException.class, () -> UnifiedArchivePublish.builder()
        .journal(JOURNAL_KEY, JOURNAL_VALUE)
        .journalToken(TOKEN_KEY, TOKEN_VALUE)
        .acknowledgement(ACKNOWLEDGEMENT_KEY, ascii("different-token"))
        .cursor(CURSOR_KEY, CURSOR_VALUE)
        .blockMarker(MARKER_KEY, MARKER_VALUE)
        .put(UnifiedArchiveColumnFamily.INDEX, INDEX_KEY, INDEX_VALUE)
        .build());
  }

  @Test
  public void acknowledgementDefersPayloadValidationUntilPublication() {
    db.putJournalBlockDurably(
        JOURNAL_KEY, JOURNAL_VALUE, TOKEN_KEY, TOKEN_VALUE, ACKNOWLEDGEMENT_KEY, null);
    db.deleteJournalBlockDurably(
        JOURNAL_KEY, JOURNAL_VALUE, TOKEN_KEY, TOKEN_VALUE, ACKNOWLEDGEMENT_KEY, null);
    db.putJournalBlockDurably(
        JOURNAL_KEY, ascii("tampered-journal"), TOKEN_KEY, TOKEN_VALUE,
        ACKNOWLEDGEMENT_KEY, null);

    db.acknowledgeJournalWalOnly(
        JOURNAL_KEY, TOKEN_KEY, TOKEN_VALUE,
        ACKNOWLEDGEMENT_KEY, ACKNOWLEDGEMENT_VALUE);

    assertArrayEquals(ACKNOWLEDGEMENT_VALUE,
        db.get(UnifiedArchiveColumnFamily.INFLIGHT, ACKNOWLEDGEMENT_KEY));
    assertThrows(ArchiveException.class, () -> db.publishBlockAtomically(publish(), false));
    assertArrayEquals(ascii("tampered-journal"),
        db.get(UnifiedArchiveColumnFamily.INFLIGHT, JOURNAL_KEY));
    assertNull(db.get(UnifiedArchiveColumnFamily.INDEX, INDEX_KEY));
  }

  @Test
  public void snapshotKeepsMetaIndexTemporalMarkerAndJournalAtOneSequence() {
    putJournalBundle();

    try (UnifiedArchiveReadView beforePublish = db.openReadView()) {
      long beforeSequence = beforePublish.getSequenceNumber();
      db.publishBlockAtomically(publish(), false);

      assertUnpublished(beforePublish);
      try (UnifiedArchiveReadView afterPublish = db.openReadView()) {
        assertTrue(afterPublish.getSequenceNumber() > beforeSequence);
        assertPublished(afterPublish);
      }
    }
  }

  @Test
  public void blockedPublicationDoesNotBlockQuerySnapshotCreation() throws Exception {
    putJournalBundle();
    db.close();

    CountDownLatch writeEntered = new CountDownLatch(1);
    CountDownLatch releaseWrite = new CountDownLatch(1);
    db = UnifiedArchiveDb.open(dbPath, SCHEMA_CHECKSUM, (rocksDb, writeOptions, batch) -> {
      writeEntered.countDown();
      try {
        if (!releaseWrite.await(5L, TimeUnit.SECONDS)) {
          throw new RocksDBException("timed out waiting to release injected publication");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RocksDBException("injected publication interrupted");
      }
      rocksDb.write(writeOptions, batch);
    });

    CountDownLatch queryOpened = new CountDownLatch(1);
    AtomicReference<Throwable> publicationFailure = new AtomicReference<>();
    AtomicReference<Throwable> queryFailure = new AtomicReference<>();
    Thread publisher = new Thread(() -> {
      try {
        db.publishBlockAtomically(publish(), true);
      } catch (Throwable failure) {
        publicationFailure.set(failure);
      }
    }, "blocked-unified-publication");
    Thread query = new Thread(() -> {
      try (UnifiedArchiveReadView ignored =
               db.openQueryReadView(TimeUnit.SECONDS.toNanos(1L))) {
        queryOpened.countDown();
      } catch (Throwable failure) {
        queryFailure.set(failure);
      }
    }, "concurrent-unified-query-snapshot");

    try {
      publisher.start();
      assertTrue(writeEntered.await(5L, TimeUnit.SECONDS));
      query.start();
      assertTrue("query snapshot must not wait for durable publication",
          queryOpened.await(1L, TimeUnit.SECONDS));
    } finally {
      releaseWrite.countDown();
      publisher.join(5_000L);
      query.join(5_000L);
    }

    assertFalse(publisher.isAlive());
    assertFalse(query.isAlive());
    assertNull(publicationFailure.get());
    assertNull(queryFailure.get());
  }

  @Test
  public void blockedPublicationJavaLockDoesNotBlockDifferentJournalBundle() throws Exception {
    putJournalBundle();
    db.close();

    CountDownLatch publicationEntered = new CountDownLatch(1);
    CountDownLatch releasePublication = new CountDownLatch(1);
    AtomicInteger writes = new AtomicInteger();
    db = UnifiedArchiveDb.open(dbPath, SCHEMA_CHECKSUM, (rocksDb, writeOptions, batch) -> {
      if (writes.incrementAndGet() == 1) {
        publicationEntered.countDown();
        try {
          if (!releasePublication.await(5L, TimeUnit.SECONDS)) {
            throw new RocksDBException("timed out waiting to release injected publication");
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RocksDBException("injected publication interrupted");
        }
      }
      rocksDb.write(writeOptions, batch);
    });

    byte[] tailJournalKey = ascii("block-8");
    byte[] tailJournalValue = ascii("journal-value-8");
    byte[] tailTokenKey = ascii("token-8");
    byte[] tailTokenValue = ascii("token-value-8");
    byte[] tailAcknowledgementKey = ascii("ack-8");
    AtomicReference<Throwable> publicationFailure = new AtomicReference<>();
    Thread publisher = new Thread(() -> {
      try {
        db.publishBlockAtomically(publish(), true);
      } catch (Throwable failure) {
        publicationFailure.set(failure);
      }
    }, "blocked-unified-publication");
    FutureTask<Void> tailJournal = new FutureTask<>(() -> {
      db.putJournalBlockDurably(
          tailJournalKey, tailJournalValue, tailTokenKey, tailTokenValue,
          tailAcknowledgementKey, null);
      return null;
    });
    Thread journalWriter = new Thread(tailJournal, "concurrent-unified-tail-journal");

    try {
      publisher.start();
      assertTrue(publicationEntered.await(5L, TimeUnit.SECONDS));
      journalWriter.start();
      tailJournal.get(1L, TimeUnit.SECONDS);
      assertArrayEquals(tailJournalValue,
          db.get(UnifiedArchiveColumnFamily.INFLIGHT, tailJournalKey));
      assertArrayEquals(tailTokenValue,
          db.get(UnifiedArchiveColumnFamily.INFLIGHT, tailTokenKey));
    } finally {
      releasePublication.countDown();
      publisher.join(5_000L);
      journalWriter.join(5_000L);
    }

    assertFalse(publisher.isAlive());
    assertFalse(journalWriter.isAlive());
    assertNull(publicationFailure.get());
    assertArrayEquals(tailJournalValue,
        db.get(UnifiedArchiveColumnFamily.INFLIGHT, tailJournalKey));
  }

  @Test
  public void closeWaitsForActivePublicationBeforeClosingNativeDatabase() throws Exception {
    putJournalBundle();
    db.close();

    CountDownLatch writeEntered = new CountDownLatch(1);
    CountDownLatch releaseWrite = new CountDownLatch(1);
    db = UnifiedArchiveDb.open(dbPath, SCHEMA_CHECKSUM, (rocksDb, writeOptions, batch) -> {
      writeEntered.countDown();
      try {
        if (!releaseWrite.await(5L, TimeUnit.SECONDS)) {
          throw new RocksDBException("timed out waiting to release injected publication");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RocksDBException("injected publication interrupted");
      }
      rocksDb.write(writeOptions, batch);
    });

    CountDownLatch closeReturned = new CountDownLatch(1);
    AtomicReference<Throwable> publicationFailure = new AtomicReference<>();
    AtomicReference<Throwable> closeFailure = new AtomicReference<>();
    Thread publisher = new Thread(() -> {
      try {
        db.publishBlockAtomically(publish(), true);
      } catch (Throwable failure) {
        publicationFailure.set(failure);
      }
    }, "active-unified-publication");
    Thread closer = new Thread(() -> {
      try {
        db.close();
      } catch (Throwable failure) {
        closeFailure.set(failure);
      } finally {
        closeReturned.countDown();
      }
    }, "concurrent-unified-close");

    try {
      publisher.start();
      assertTrue(writeEntered.await(5L, TimeUnit.SECONDS));
      closer.start();
      assertFalse("close must wait for the active native write",
          closeReturned.await(200L, TimeUnit.MILLISECONDS));
    } finally {
      releaseWrite.countDown();
      publisher.join(5_000L);
      closer.join(5_000L);
    }

    assertFalse(publisher.isAlive());
    assertFalse(closer.isAlive());
    assertNull(publicationFailure.get());
    assertNull(closeFailure.get());
    db = null;
  }

  @Test
  public void pointQueryAndScanViewsUseExplicitCacheAdmissionPolicies() {
    try (UnifiedArchiveReadView point = db.openReadView();
         UnifiedArchiveReadView query = db.openQueryReadView();
         UnifiedArchiveReadView scan = db.openScanView()) {
      assertTrue(point.fillsCache());
      assertFalse(query.fillsCache());
      assertFalse(scan.fillsCache());
      assertEquals(point.getSequenceNumber(), scan.getSequenceNumber());
    }
  }

  @Test
  public void nullNativeSnapshotFailsWithoutRegisteringReadView() {
    RocksDB raw = ReflectUtils.getFieldValue(db, "db");
    RocksDB failing = spy(raw);
    doReturn(null).when(failing).getSnapshot();
    ReflectUtils.setFieldValue(db, "db", failing);
    try {
      assertThrows(ArchiveStorageAccessException.class, db::openReadView);
      assertThrows(ArchiveStorageAccessException.class, db::openQueryReadView);
      assertThrows(ArchiveStorageAccessException.class, db::openScanView);

      AtomicInteger activeReadViews = ReflectUtils.getFieldValue(db, "activeReadViews");
      assertEquals(0, activeReadViews.get());
    } finally {
      ReflectUtils.setFieldValue(db, "db", raw);
    }

    try (UnifiedArchiveReadView ignored = db.openReadView()) {
      assertTrue(ignored.getSequenceNumber() >= 0L);
    }
  }

  @Test
  public void nativeOptionConfigurationFailureClosesCreatedHandle() {
    AssertionError readFailure = new AssertionError("injected read-options failure");
    ReadOptions readOptions = mock(ReadOptions.class);
    Snapshot snapshot = mock(Snapshot.class);
    doReturn(readOptions).when(readOptions).setSnapshot(snapshot);
    doThrow(readFailure).when(readOptions).setFillCache(false);

    assertSame(readFailure, assertThrows(AssertionError.class,
        () -> UnifiedArchiveDb.configureSnapshotReadOptions(
            readOptions, snapshot, false, Long.MAX_VALUE)));
    verify(readOptions).close();

    AssertionError writeFailure = new AssertionError("injected write-options failure");
    WriteOptions writeOptions = mock(WriteOptions.class);
    doReturn(writeOptions).when(writeOptions).setDisableWAL(false);
    doThrow(writeFailure).when(writeOptions).setSync(true);

    assertSame(writeFailure, assertThrows(AssertionError.class,
        () -> UnifiedArchiveDb.configureWriteOptions(writeOptions, true)));
    verify(writeOptions).close();
  }

  @Test
  public void snapshotOptionCleanupFailureIsTypedAsReleaseUncertainty() {
    AssertionError configurationFailure =
        new AssertionError("injected read-options configuration failure");
    AssertionError closeFailure = new AssertionError("injected read-options close failure");
    ReadOptions readOptions = mock(ReadOptions.class);
    Snapshot snapshot = mock(Snapshot.class);
    doReturn(readOptions).when(readOptions).setSnapshot(snapshot);
    doThrow(configurationFailure).when(readOptions).setFillCache(false);
    doThrow(closeFailure).when(readOptions).close();

    ArchiveSnapshotReleaseException uncertain = assertThrows(
        ArchiveSnapshotReleaseException.class,
        () -> UnifiedArchiveDb.configureSnapshotReadOptions(
            readOptions, snapshot, false, Long.MAX_VALUE));

    assertSame(closeFailure, uncertain.getCause());
    assertTrue(Arrays.asList(uncertain.getSuppressed()).contains(configurationFailure));
    verify(readOptions).close();
  }

  @Test
  public void iteratorRejectsForeignOperationsBeforeNativeAccess() throws Exception {
    try (UnifiedArchiveReadView view = db.openReadView()) {
      UnifiedArchiveIterator iterator = view.newIterator(UnifiedArchiveColumnFamily.META);
      FutureTask<Throwable> foreignSeek = failureOf(iterator::seekToFirst);
      Thread seekThread = new Thread(foreignSeek, "archive-foreign-iterator-seek");
      seekThread.start();
      assertTrue(foreignSeek.get(1L, TimeUnit.SECONDS) instanceof ArchiveException);
      seekThread.join(1_000L);

      iterator.seekToFirst();
      assertTrue(iterator.isValid());

      FutureTask<Throwable> foreignClose = failureOf(iterator::close);
      Thread closeThread = new Thread(foreignClose, "archive-foreign-iterator-close");
      closeThread.start();
      assertTrue(foreignClose.get(1L, TimeUnit.SECONDS) instanceof ArchiveException);
      closeThread.join(1_000L);

      assertTrue(iterator.isValid());
    }
  }

  @Test
  public void queryViewPropagatesDeadlineToNativeReadOptions() throws Exception {
    long beforeMicros = TimeUnit.MILLISECONDS.toMicros(System.currentTimeMillis());
    long remainingNanos = TimeUnit.SECONDS.toNanos(2L);
    try (UnifiedArchiveReadView query = db.openQueryReadView(remainingNanos)) {
      if (!UnifiedArchiveDb.nativeReadDeadlineSupported()) {
        assertFalse(hasReadOptionsMethod("deadline"));
        assertFalse(hasReadOptionsMethod("ioTimeout"));
        return;
      }
      ReadOptions readOptions = ReflectUtils.getFieldValue(query, "readOptions");
      long deadlineMicros = (long) ReadOptions.class.getMethod("deadline").invoke(readOptions);
      long ioTimeoutMicros =
          (long) ReadOptions.class.getMethod("ioTimeout").invoke(readOptions);

      assertTrue(deadlineMicros > beforeMicros);
      assertTrue(ioTimeoutMicros > 0L);
      assertTrue(ioTimeoutMicros <= TimeUnit.NANOSECONDS.toMicros(remainingNanos));
    }
  }

  @Test
  public void queryViewRejectsExhaustedRemainingBudgetBeforeSnapshotOpen() {
    HistoricalQueryLimitException failure = assertThrows(
        HistoricalQueryLimitException.class, () -> db.openQueryReadView(0L));

    assertEquals(HistoricalQueryLimitException.Limit.DEADLINE, failure.getLimit());
    AtomicInteger activeReadViews = ReflectUtils.getFieldValue(db, "activeReadViews");
    assertEquals(0, activeReadViews.get());
  }

  @Test
  public void contextQueryViewRequiresCoordinatorSnapshotPermit() {
    QueryContext unadmitted = new QueryContext(ArchiveQueryLimits.unlimited());

    ArchiveException rejected = assertThrows(
        ArchiveException.class, () -> db.openQueryReadView(unadmitted));

    assertTrue(rejected.getMessage().contains("coordinator permit"));
    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator();
    QueryLease lease = coordinator.acquire();
    assertFalse(lease.getContext().hasActiveSnapshotPermit());
    try (ArchiveSnapshotPermit ignored = coordinator.acquireSnapshot(lease);
        UnifiedArchiveReadView view = db.openQueryReadView(lease.getContext())) {
      assertTrue(lease.getContext().hasActiveSnapshotPermit());
      assertTrue(view.getSequenceNumber() >= 0L);
    }
    assertFalse(lease.getContext().hasActiveSnapshotPermit());
    lease.close();
    coordinator.close();
  }

  @Test
  public void oneCoordinatorPermitCannotBackTwoConcurrentQueryViews() {
    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator();
    QueryLease lease = coordinator.acquire();
    ArchiveSnapshotPermit snapshotPermit = coordinator.acquireSnapshot(lease);

    try (UnifiedArchiveReadView first = db.openQueryReadView(lease.getContext())) {
      ArchiveException rejected = assertThrows(
          ArchiveException.class, () -> db.openQueryReadView(lease.getContext()));
      assertTrue(rejected.getMessage().contains("available coordinator permit"));
      assertTrue(snapshotPermit.isInUse());
      assertEquals(1L, coordinator.getActiveSnapshotCount());
    }

    assertFalse(snapshotPermit.isInUse());
    try (UnifiedArchiveReadView second = db.openQueryReadView(lease.getContext())) {
      assertTrue(second.getSequenceNumber() >= 0L);
    }
    snapshotPermit.close();
    lease.close();
    coordinator.close();
  }

  @Test
  public void permitCloseCannotRacePastAClaimWaitingForTheDbLock() throws Exception {
    ReentrantReadWriteLock lifecycleLock =
        ReflectUtils.getFieldValue(db, "lifecycleLock");
    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator();
    QueryLease lease = coordinator.acquire();
    ArchiveSnapshotPermit snapshotPermit = coordinator.acquireSnapshot(lease);
    AtomicReference<Throwable> openFailure = new AtomicReference<>();
    Thread opener = new Thread(() -> {
      try (UnifiedArchiveReadView ignored = db.openQueryReadView(lease.getContext())) {
        // The view must remain accounted until this owner closes it.
      } catch (Throwable failure) {
        openFailure.set(failure);
      }
    }, "unified-query-snapshot-claim-race");

    lifecycleLock.writeLock().lock();
    try {
      opener.start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1L);
      while (!snapshotPermit.isInUse() && System.nanoTime() < deadline) {
        Thread.sleep(1L);
      }
      assertTrue(snapshotPermit.isInUse());
      assertThrows(IllegalStateException.class, snapshotPermit::close);
      assertEquals(1L, coordinator.getActiveSnapshotCount());
    } finally {
      lifecycleLock.writeLock().unlock();
    }

    opener.join(1_000L);
    assertFalse(opener.isAlive());
    assertNull(openFailure.get());
    assertFalse(snapshotPermit.isInUse());
    snapshotPermit.close();
    lease.close();
    coordinator.close();
  }

  @Test
  public void queryContextIsRecheckedImmediatelyBeforeNativeSnapshotOpen() {
    RocksDB raw = ReflectUtils.getFieldValue(db, "db");
    RocksDB observed = spy(raw);
    ReflectUtils.setFieldValue(db, "db", observed);
    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator(
        ArchiveQueryLimits.builder().deadlineMs(1_000L).build());
    QueryLease lease = coordinator.acquire();
    QueryContext context = lease.getContext();
    ArchiveSnapshotPermit snapshotPermit = coordinator.acquireSnapshot(lease);
    long startedNanos = context.getStartedNanos();
    AtomicInteger samples = new AtomicInteger();
    LongSupplier clock = () -> samples.incrementAndGet() <= 2
        ? startedNanos : startedNanos + TimeUnit.SECONDS.toNanos(2L);
    ReflectUtils.setFieldValue(context, "nanoTime", clock);

    HistoricalQueryLimitException failure = assertThrows(
        HistoricalQueryLimitException.class, () -> db.openQueryReadView(context));

    assertEquals(HistoricalQueryLimitException.Limit.DEADLINE, failure.getLimit());
    verify(observed, never()).getSnapshot();
    ReflectUtils.setFieldValue(db, "db", raw);
    snapshotPermit.close();
    lease.close();
    coordinator.close();
  }

  @Test
  public void snapshotLockTimeoutPreservesBatchDeadlineClassification() throws Exception {
    ReentrantReadWriteLock lifecycleLock =
        ReflectUtils.getFieldValue(db, "lifecycleLock");
    CountDownLatch writeLocked = new CountDownLatch(1);
    CountDownLatch releaseWrite = new CountDownLatch(1);
    Thread blocker = new Thread(() -> {
      lifecycleLock.writeLock().lock();
      try {
        writeLocked.countDown();
        releaseWrite.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        lifecycleLock.writeLock().unlock();
      }
    }, "unified-snapshot-lock-blocker");
    ArchiveQueryLimits limits = ArchiveQueryLimits.builder()
        .deadlineMs(1_000L)
        .batchDeadlineMs(50L)
        .build();
    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator(limits);

    blocker.start();
    assertTrue(writeLocked.await(1L, TimeUnit.SECONDS));
    try (ArchiveQueryRequestScope ignored = ArchiveQueryRequestScope.open();
        QueryLease lease = coordinator.acquire();
        ArchiveSnapshotPermit snapshotPermit = coordinator.acquireSnapshot(lease)) {
      HistoricalQueryLimitException failure = assertThrows(
          HistoricalQueryLimitException.class,
          () -> db.openQueryReadView(lease.getContext()));

      assertEquals(HistoricalQueryLimitException.Limit.BATCH_DEADLINE, failure.getLimit());
    } finally {
      releaseWrite.countDown();
      blocker.join(1_000L);
      coordinator.close();
    }
  }

  @Test
  public void publishBuilderRejectsRetainedBytesBeforeCopyingOversizedEntry() {
    UnifiedArchivePublish.Builder builder = UnifiedArchivePublish.builder(100L, 10L);

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> builder.journal(ascii("journal"), new byte[64]));

    assertTrue(failure.getMessage().contains("retained byte limit 100"));
  }

  @Test
  public void publishBuilderRejectsMutationCardinalityBeforeAddingMutation() {
    UnifiedArchivePublish.Builder builder =
        UnifiedArchivePublish.builder(Long.MAX_VALUE, 1L);
    builder.put(UnifiedArchiveColumnFamily.INDEX, ascii("first"), ascii("value"));

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> builder.put(
            UnifiedArchiveColumnFamily.INDEX, ascii("second"), ascii("value")));

    assertTrue(failure.getMessage().contains("mutation limit 1"));
  }

  @Test
  public void publishBuilderRejectsPreparationEstimateBeforeRowConstruction() {
    UnifiedArchivePublish.Builder builder = UnifiedArchivePublish.builder(100L, 2L);

    ArchiveException byteFailure = assertThrows(ArchiveException.class,
        () -> builder.requireAdditionalCapacity(101L, 1L, "temporal preparation"));
    ArchiveException mutationFailure = assertThrows(ArchiveException.class,
        () -> builder.requireAdditionalCapacity(1L, 3L, "temporal preparation"));

    assertTrue(byteFailure.getMessage().contains("retained byte limit 100"));
    assertTrue(mutationFailure.getMessage().contains("mutation limit 2"));
  }

  @Test
  public void boundedMaintenanceBatchRejectsBeforeRetainingOversizedValue() {
    UnifiedArchiveMaintenanceBatch batch =
        UnifiedArchiveMaintenanceBatch.bounded(100L, 10L);

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> batch.put(UnifiedArchiveColumnFamily.LATEST,
            ascii("key"), new byte[64]));

    assertTrue(failure.getMessage().contains("retained byte limit 100"));
  }

  @Test
  public void maintenanceWriteAtomicallyMarksArchiveForFullStartupScrub() {
    db.writeMaintenanceAtomically(new UnifiedArchiveMaintenanceBatch()
        .put(UnifiedArchiveColumnFamily.LATEST, ascii("maintenance-key"), ascii("value")));

    assertTrue(UnifiedArchiveTxNumIndex.hasRepairRequired(db));
    assertThrows(ArchiveException.class,
        () -> db.deleteMetaDurably(ArchiveBlockRangeCodec.repairRequiredKey()));
    assertTrue(UnifiedArchiveTxNumIndex.hasRepairRequired(db));
  }

  @Test
  public void productionSealRejectsMaintenanceWrites() {
    db.sealForProduction();

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> db.writeMaintenanceAtomically(new UnifiedArchiveMaintenanceBatch()
            .put(UnifiedArchiveColumnFamily.LATEST, ascii("key"), ascii("value"))));

    assertTrue(failure.getMessage().contains("disabled in production mode"));
    assertFalse(UnifiedArchiveTxNumIndex.hasRepairRequired(db));
  }

  @Test
  public void productionSealRequiresTheDbBoundWritePermit() {
    byte[] key = ascii("owned-journal");
    byte[] value = ascii("owned-value");
    UnifiedArchiveDb.ProductionWritePermit permit = db.claimProductionWritePermit();
    assertThrows(ArchiveException.class, db::claimProductionWritePermit);
    db.sealForProduction();

    assertThrows(ArchiveException.class, () -> db.putJournalDurably(key, value));
    db.withProductionWritePermit(permit, () -> db.putJournalDurably(key, value));

    try (UnifiedArchiveReadView view = db.openReadView()) {
      assertArrayEquals(value, view.get(UnifiedArchiveColumnFamily.INFLIGHT, key));
    }

    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator();
    QueryLease lease = coordinator.acquire();
    try (ArchiveSnapshotPermit ignored = coordinator.acquireSnapshot(lease)) {
      assertThrows(ArchiveException.class,
          () -> db.openQueryReadView(lease.getContext()));
      try (UnifiedArchiveReadView view =
          db.openQueryReadView(lease.getContext(), permit)) {
        assertTrue(view.getSequenceNumber() >= 0L);
      }
    }
    lease.close();
    coordinator.close();
  }

  @Test
  public void productionWritePermitCannotCrossDatabaseOwners() {
    try (UnifiedArchiveDb foreign = UnifiedArchiveDb.initialize(
        root.resolve("foreign-owner"), SCHEMA_CHECKSUM)) {
      UnifiedArchiveDb.ProductionWritePermit foreignPermit =
          foreign.claimProductionWritePermit();

      assertThrows(ArchiveException.class,
          () -> db.withProductionWritePermit(foreignPermit,
              () -> db.putJournalDurably(ascii("key"), ascii("value"))));
    }
  }

  @Test
  public void productionPermitInstallFailureRollsBackThreadLocal() {
    AssertionError injected = new AssertionError("injected permit install failure");
    AtomicBoolean failNextInstall = new AtomicBoolean(true);
    ThreadLocal<Object> holder = new ThreadLocal<Object>() {
      @Override
      public void set(Object value) {
        super.set(value);
        if (failNextInstall.getAndSet(false)) {
          throw injected;
        }
      }
    };
    Object permit = new Object();

    assertSame(injected, assertThrows(AssertionError.class,
        () -> UnifiedArchiveDb.installThreadLocal(holder, permit)));
    assertNull(holder.get());

    UnifiedArchiveDb.installThreadLocal(holder, permit);
    assertSame(permit, holder.get());
    holder.remove();
  }

  @Test
  public void productionPermitInstallPreservesRollbackFailureAsSuppressed() {
    AssertionError installFailure = new AssertionError("injected permit install failure");
    AssertionError rollbackFailure = new AssertionError("injected permit rollback failure");
    ThreadLocal<Object> holder = new ThreadLocal<Object>() {
      @Override
      public void set(Object value) {
        super.set(value);
        throw installFailure;
      }

      @Override
      public void remove() {
        super.remove();
        throw rollbackFailure;
      }
    };

    AssertionError failure = assertThrows(AssertionError.class,
        () -> UnifiedArchiveDb.installThreadLocal(holder, new Object()));

    assertSame(installFailure, failure);
    assertEquals(1, failure.getSuppressed().length);
    assertSame(rollbackFailure, failure.getSuppressed()[0]);
    assertNull(holder.get());
  }

  @Test
  public void productionPermitIsClearedAfterMutationFailure() {
    UnifiedArchiveDb.ProductionWritePermit permit = db.claimProductionWritePermit();
    AssertionError injected = new AssertionError("injected production mutation failure");

    assertSame(injected, assertThrows(AssertionError.class,
        () -> db.withProductionWritePermit(permit, () -> {
          throw injected;
        })));

    db.withProductionWritePermit(permit,
        () -> db.putJournalDurably(ascii("permit-retry"), ascii("value")));
  }

  @Test
  public void optionConfigurationCleanupFailureBecomesNativeReleaseMarker() {
    AssertionError configurationFailure = new AssertionError("injected configuration failure");
    AssertionError closeFailure = new AssertionError("injected options close failure");
    ReadOptions readOptions = mock(ReadOptions.class);
    doThrow(configurationFailure).when(readOptions).setFillCache(false);
    doThrow(closeFailure).when(readOptions).close();

    ArchiveNativeResourceReleaseException readFailure = assertThrows(
        ArchiveNativeResourceReleaseException.class,
        () -> UnifiedArchiveDb.configureReadOptions(readOptions, false));

    assertSame(closeFailure, readFailure.getCause());
    assertEquals(1, readFailure.getSuppressed().length);
    assertSame(configurationFailure, readFailure.getSuppressed()[0]);

    WriteOptions writeOptions = mock(WriteOptions.class);
    doThrow(configurationFailure).when(writeOptions).setDisableWAL(false);
    doThrow(closeFailure).when(writeOptions).close();

    ArchiveNativeResourceReleaseException writeFailure = assertThrows(
        ArchiveNativeResourceReleaseException.class,
        () -> UnifiedArchiveDb.configureWriteOptions(writeOptions, true));

    assertSame(closeFailure, writeFailure.getCause());
    assertEquals(1, writeFailure.getSuppressed().length);
    assertSame(configurationFailure, writeFailure.getSuppressed()[0]);
  }

  @Test
  public void boundedSmallQueryValueUsesOneNativeGet() {
    byte[] key = ascii("bounded-query-key");
    byte[] value = new byte[4096];
    Arrays.fill(value, (byte) 0x5c);
    db.writeMaintenanceAtomically(new UnifiedArchiveMaintenanceBatch()
        .put(UnifiedArchiveColumnFamily.LATEST, key, value));

    db.close();
    db = null;
    try {
      db = UnifiedArchiveDb.openWithStatisticsForTesting(dbPath, SCHEMA_CHECKSUM);
      Statistics statistics = ReflectUtils.getFieldValue(db, "statistics");
      try (UnifiedArchiveReadView query = db.openQueryReadView()) {
        long before = statistics.getTickerCount(TickerType.NUMBER_KEYS_READ);
        assertArrayEquals(value, query.getBounded(
            UnifiedArchiveColumnFamily.LATEST, key, value.length, "bounded query value"));
        assertEquals(1L,
            statistics.getTickerCount(TickerType.NUMBER_KEYS_READ) - before);
      }
    } finally {
      if (db != null) {
        db.close();
        db = null;
      }
      db = UnifiedArchiveDb.open(dbPath, SCHEMA_CHECKSUM);
    }
  }

  @Test
  public void journalPublicationCompareDoesNotPopulateDataBlockCache() {
    putJournalBundle();
    db.close();
    db = null;
    try {
      db = UnifiedArchiveDb.openWithStatisticsForTesting(dbPath, SCHEMA_CHECKSUM);
      Statistics statistics = ReflectUtils.getFieldValue(db, "statistics");
      long before = statistics.getTickerCount(TickerType.BLOCK_CACHE_DATA_BYTES_INSERT);

      db.publishBlockAtomically(publish(), true);

      assertEquals(0L, statistics.getTickerCount(
          TickerType.BLOCK_CACHE_DATA_BYTES_INSERT) - before);
    } finally {
      if (db != null) {
        db.close();
        db = null;
      }
      db = UnifiedArchiveDb.open(dbPath, SCHEMA_CHECKSUM);
    }
  }

  @Test
  public void activeSnapshotPreventsSharedDatabaseClose() {
    UnifiedArchiveReadView view = db.openReadView();
    ArchiveException failure = assertThrows(ArchiveException.class, db::close);
    assertTrue(failure.getMessage().contains("active snapshot read views"));

    view.close();
    db.close();
    db.close();
    db = null;
  }

  @Test
  public void iteratorCloseFailurePinsSnapshotAndDatabase() {
    RocksDB raw = ReflectUtils.getFieldValue(db, "db");
    RocksDB failing = spy(raw);
    ReflectUtils.setFieldValue(db, "db", failing);
    UnifiedArchiveReadView view = db.openReadView();
    Snapshot snapshot = ReflectUtils.getFieldValue(view, "snapshot");
    ReadOptions ownedReadOptions = ReflectUtils.getFieldValue(view, "readOptions");
    ReadOptions trackingReadOptions = mock(ReadOptions.class);
    ReflectUtils.setFieldValue(view, "readOptions", trackingReadOptions);
    UnifiedArchiveIterator failingIterator = mock(UnifiedArchiveIterator.class);
    AssertionError injected = new AssertionError("injected iterator close failure");
    AtomicInteger attempts = new AtomicInteger();
    doAnswer(invocation -> {
      attempts.incrementAndGet();
      throw injected;
    }).when(failingIterator).close();
    List<UnifiedArchiveIterator> iterators =
        ReflectUtils.getFieldValue(view, "iterators");
    iterators.add(failingIterator);
    AtomicInteger activeReadViews = ReflectUtils.getFieldValue(db, "activeReadViews");

    try {
      ArchiveSnapshotReleaseException first = assertThrows(
          ArchiveSnapshotReleaseException.class, view::close);
      assertSame(injected, first.getCause());
      assertSame(first, assertThrows(ArchiveSnapshotReleaseException.class, view::close));
      assertEquals(1, attempts.get());
      assertEquals(1, activeReadViews.get());
      assertThrows(ArchiveException.class, db::close);
      verify(failing, never()).releaseSnapshot(any(Snapshot.class));
      verify(trackingReadOptions, never()).close();
    } finally {
      if (activeReadViews.get() != 0) {
        ownedReadOptions.close();
        raw.releaseSnapshot(snapshot);
        ReflectUtils.invokeMethod(db, "releaseReadView", new Class<?>[0]);
      }
      ReflectUtils.setFieldValue(db, "db", raw);
      db.close();
      db = null;
    }
  }

  @Test
  public void readOptionsCloseFailurePinsSnapshotAndDatabase() {
    RocksDB raw = ReflectUtils.getFieldValue(db, "db");
    RocksDB failing = spy(raw);
    ReflectUtils.setFieldValue(db, "db", failing);
    UnifiedArchiveReadView view = db.openReadView();
    Snapshot snapshot = ReflectUtils.getFieldValue(view, "snapshot");
    ReadOptions ownedReadOptions = ReflectUtils.getFieldValue(view, "readOptions");
    ReadOptions failingReadOptions = mock(ReadOptions.class);
    AssertionError injected = new AssertionError("injected read options close failure");
    AtomicInteger attempts = new AtomicInteger();
    doAnswer(invocation -> {
      attempts.incrementAndGet();
      throw injected;
    }).when(failingReadOptions).close();
    ReflectUtils.setFieldValue(view, "readOptions", failingReadOptions);
    AtomicInteger activeReadViews = ReflectUtils.getFieldValue(db, "activeReadViews");

    try {
      ArchiveSnapshotReleaseException first = assertThrows(
          ArchiveSnapshotReleaseException.class, view::close);
      assertSame(injected, first.getCause());
      assertSame(first, assertThrows(ArchiveSnapshotReleaseException.class, view::close));
      assertEquals(1, attempts.get());
      assertEquals(1, activeReadViews.get());
      assertThrows(ArchiveException.class, db::close);
      verify(failing, never()).releaseSnapshot(any(Snapshot.class));
    } finally {
      if (activeReadViews.get() != 0) {
        ownedReadOptions.close();
        raw.releaseSnapshot(snapshot);
        ReflectUtils.invokeMethod(db, "releaseReadView", new Class<?>[0]);
      }
      ReflectUtils.setFieldValue(db, "db", raw);
      db.close();
      db = null;
    }
  }

  @Test
  public void iteratorRegistrationCleanupFailurePinsSnapshotAndDatabase() {
    RocksDB raw = ReflectUtils.getFieldValue(db, "db");
    RocksDB failing = spy(raw);
    RocksIterator delegate = mock(RocksIterator.class);
    AssertionError cleanupFailure = new AssertionError("injected iterator cleanup failure");
    doThrow(cleanupFailure).when(delegate).close();
    doReturn(delegate).when(failing).newIterator(
        any(ColumnFamilyHandle.class), any(ReadOptions.class));
    ReflectUtils.setFieldValue(db, "db", failing);
    UnifiedArchiveReadView view = db.openReadView();
    Snapshot snapshot = ReflectUtils.getFieldValue(view, "snapshot");
    ReadOptions readOptions = ReflectUtils.getFieldValue(view, "readOptions");
    AssertionError registrationFailure =
        new AssertionError("injected iterator registration failure");
    List<UnifiedArchiveIterator> rejectingIterators = new ArrayList<UnifiedArchiveIterator>() {
      private static final long serialVersionUID = 1L;

      @Override
      public boolean add(UnifiedArchiveIterator ignored) {
        throw registrationFailure;
      }
    };
    ReflectUtils.setFieldValue(view, "iterators", rejectingIterators);
    AtomicInteger activeReadViews = ReflectUtils.getFieldValue(db, "activeReadViews");

    try {
      ArchiveSnapshotReleaseException first = assertThrows(
          ArchiveSnapshotReleaseException.class,
          () -> view.newIterator(UnifiedArchiveColumnFamily.META));
      assertSame(cleanupFailure, first.getCause());
      assertTrue(Arrays.asList(first.getSuppressed()).contains(registrationFailure));
      assertSame(first, assertThrows(ArchiveSnapshotReleaseException.class, view::close));
      assertEquals(1, activeReadViews.get());
      assertThrows(ArchiveException.class, db::close);
      verify(delegate).close();
      verify(failing, never()).releaseSnapshot(any(Snapshot.class));
    } finally {
      if (activeReadViews.get() != 0) {
        readOptions.close();
        raw.releaseSnapshot(snapshot);
        ReflectUtils.invokeMethod(db, "releaseReadView", new Class<?>[0]);
      }
      ReflectUtils.setFieldValue(db, "db", raw);
      db.close();
      db = null;
    }
  }

  @Test
  public void snapshotReleaseFailureKeepsAccountingAndIsNeverRetried() {
    RocksDB raw = ReflectUtils.getFieldValue(db, "db");
    RocksDB failing = spy(raw);
    AssertionError injected = new AssertionError("injected snapshot release failure");
    AtomicInteger attempts = new AtomicInteger();
    doAnswer(invocation -> {
      attempts.incrementAndGet();
      throw injected;
    }).when(failing).releaseSnapshot(any(Snapshot.class));
    ReflectUtils.setFieldValue(db, "db", failing);
    UnifiedArchiveReadView view = db.openReadView();
    Snapshot snapshot = ReflectUtils.getFieldValue(view, "snapshot");
    AtomicInteger activeReadViews = ReflectUtils.getFieldValue(db, "activeReadViews");

    ArchiveSnapshotReleaseException first = assertThrows(
        ArchiveSnapshotReleaseException.class, view::close);
    assertSame(injected, first.getCause());
    assertEquals(1, activeReadViews.get());
    assertThrows(ArchiveException.class, db::close);

    assertSame(first, assertThrows(ArchiveSnapshotReleaseException.class, view::close));
    assertEquals(1, attempts.get());
    raw.releaseSnapshot(snapshot);
    ReflectUtils.invokeMethod(db, "releaseReadView", new Class<?>[0]);
    assertEquals(0, activeReadViews.get());
    ReflectUtils.setFieldValue(db, "db", raw);
    db.close();
    db = null;
  }

  @Test
  public void postCommitSnapshotReleaseFailureIsNeverDoubleReleased() {
    RocksDB raw = ReflectUtils.getFieldValue(db, "db");
    RocksDB failing = spy(raw);
    AssertionError injected = new AssertionError("injected post-release failure");
    AtomicInteger attempts = new AtomicInteger();
    doAnswer(invocation -> {
      attempts.incrementAndGet();
      raw.releaseSnapshot(invocation.getArgument(0));
      throw injected;
    }).when(failing).releaseSnapshot(any(Snapshot.class));
    ReflectUtils.setFieldValue(db, "db", failing);
    UnifiedArchiveReadView view = db.openReadView();
    AtomicInteger activeReadViews = ReflectUtils.getFieldValue(db, "activeReadViews");

    ArchiveSnapshotReleaseException first = assertThrows(
        ArchiveSnapshotReleaseException.class, view::close);
    assertSame(injected, first.getCause());
    assertSame(first, assertThrows(ArchiveSnapshotReleaseException.class, view::close));
    assertEquals(1, attempts.get());
    assertEquals(1, activeReadViews.get());

    ReflectUtils.invokeMethod(db, "releaseReadView", new Class<?>[0]);
    ReflectUtils.setFieldValue(db, "db", raw);
    db.close();
    db = null;
  }

  @Test
  public void snapshotOpenCleanupFailureRemainsAccountedWithoutReturnedView() {
    RocksDB raw = ReflectUtils.getFieldValue(db, "db");
    RocksDB failing = spy(raw);
    AtomicBoolean snapshotOpened = new AtomicBoolean();
    AtomicReference<Snapshot> openedSnapshot = new AtomicReference<>();
    AssertionError injected = new AssertionError("injected open cleanup release failure");
    doAnswer(invocation -> {
      Snapshot snapshot = raw.getSnapshot();
      openedSnapshot.set(snapshot);
      snapshotOpened.set(true);
      return snapshot;
    }).when(failing).getSnapshot();
    doThrow(injected).when(failing).releaseSnapshot(any(Snapshot.class));
    ReflectUtils.setFieldValue(db, "db", failing);
    ArchiveQueryCoordinator coordinator = new ArchiveQueryCoordinator(
        ArchiveQueryLimits.builder().deadlineMs(1_000L).build());
    QueryLease lease = coordinator.acquire();
    QueryContext context = lease.getContext();
    ArchiveSnapshotPermit snapshotPermit = coordinator.acquireSnapshot(lease);
    long startedNanos = context.getStartedNanos();
    ReflectUtils.setFieldValue(context, "nanoTime", (LongSupplier) () -> snapshotOpened.get()
        ? startedNanos + TimeUnit.SECONDS.toNanos(2L) : startedNanos);
    AtomicInteger activeReadViews = ReflectUtils.getFieldValue(db, "activeReadViews");

    ArchiveSnapshotReleaseException failure = assertThrows(
        ArchiveSnapshotReleaseException.class, () -> db.openQueryReadView(context));

    assertSame(injected, failure.getCause());
    assertEquals(1, activeReadViews.get());
    assertThrows(ArchiveException.class, db::close);

    raw.releaseSnapshot(openedSnapshot.get());
    ReflectUtils.invokeMethod(db, "releaseReadView", new Class<?>[0]);
    ReflectUtils.setFieldValue(db, "db", raw);
    assertTrue(snapshotPermit.isInUse());
    snapshotPermit.retainAfterUncertainRelease();
    snapshotPermit.close();
    lease.close();
    coordinator.close();
    assertEquals(1L, coordinator.getActiveSnapshotCount());
    assertEquals(1L, coordinator.getActiveLeaseCount());
  }

  @Test
  public void iteratorCloseFailureIsStickyAndTerminal() {
    RocksIterator delegate = mock(RocksIterator.class);
    AssertionError injected = new AssertionError("injected iterator close failure");
    doThrow(injected).when(delegate).close();
    UnifiedArchiveIterator iterator = new UnifiedArchiveIterator(delegate);

    assertSame(injected, assertThrows(AssertionError.class, iterator::close));
    assertSame(injected, assertThrows(AssertionError.class, iterator::close));
    assertThrows(ArchiveException.class, iterator::isValid);
    verify(delegate).close();
  }

  @Test
  public void iteratorNativeIoFailureWinsWhenDeadlineExpiresDuringOperation() throws Exception {
    RocksIterator delegate = mock(RocksIterator.class);
    RocksDBException nativeFailure = new RocksDBException(
        new org.rocksdb.Status(org.rocksdb.Status.Code.IOError,
            org.rocksdb.Status.SubCode.None, "injected iterator I/O failure"));
    doAnswer(invocation -> {
      Thread.sleep(40L);
      return null;
    }).when(delegate).seekToFirst();
    doThrow(nativeFailure).when(delegate).status();
    UnifiedArchiveIterator iterator = new UnifiedArchiveIterator(delegate);
    QueryContext context = new QueryContext(
        ArchiveQueryLimits.builder().deadlineMs(20L).build());

    try (QueryContextHolder.Scope ignored = QueryContextHolder.attach(context)) {
      ArchiveException failure = assertThrows(ArchiveException.class, iterator::seekToFirst);
      assertSame(nativeFailure, failure.getCause());
    } finally {
      iterator.close();
    }
    verify(delegate).status();
  }

  @Test
  public void nativeCloseFailureRemainsObservableOnRetry() {
    AssertionError injected = new AssertionError("injected native close failure");
    ColumnFamilyOptions failingOptions = mock(ColumnFamilyOptions.class);
    doThrow(injected).when(failingOptions).close();
    List<ColumnFamilyOptions> options =
        ReflectUtils.getFieldValue(db, "columnFamilyOptions");
    List<BloomFilter> bloomFilters = ReflectUtils.getFieldValue(db, "bloomFilters");
    Cache blockCache = ReflectUtils.getFieldValue(db, "blockCache");
    options.add(failingOptions);
    try {
      ArchiveNativeResourceReleaseException first = assertThrows(
          ArchiveNativeResourceReleaseException.class, db::close);
      ArchiveNativeResourceReleaseException second = assertThrows(
          ArchiveNativeResourceReleaseException.class, db::close);

      assertSame(injected, first.getCause());
      assertSame(first, second);
    } finally {
      for (int i = bloomFilters.size() - 1; i >= 0; i--) {
        bloomFilters.get(i).close();
      }
      blockCache.close();
      db = null;
    }
  }

  @Test
  public void rocksDbCloseFailureRemainsObservableOnRetry() throws Throwable {
    RocksDB raw = ReflectUtils.getFieldValue(db, "db");
    UnifiedArchiveDb.RocksDbCloser original =
        ReflectUtils.getFieldValue(db, "rocksDbCloser");
    RocksDBException injected = new RocksDBException("injected RocksDB close failure");
    List<ColumnFamilyOptions> columnOptions =
        ReflectUtils.getFieldValue(db, "columnFamilyOptions");
    List<BloomFilter> bloomFilters = ReflectUtils.getFieldValue(db, "bloomFilters");
    Cache blockCache = ReflectUtils.getFieldValue(db, "blockCache");
    DBOptions dbOptions = ReflectUtils.getFieldValue(db, "dbOptions");
    Statistics statistics = ReflectUtils.getFieldValue(db, "statistics");
    ReflectUtils.setFieldValue(db, "rocksDbCloser",
        (UnifiedArchiveDb.RocksDbCloser) ignored -> {
          throw injected;
        });

    try {
      ArchiveNativeResourceReleaseException first = assertThrows(
          ArchiveNativeResourceReleaseException.class, db::close);
      ArchiveNativeResourceReleaseException second = assertThrows(
          ArchiveNativeResourceReleaseException.class, db::close);

      assertSame(injected, first.getCause());
      assertSame(first, second);
    } finally {
      ReflectUtils.setFieldValue(db, "rocksDbCloser", original);
      db = null;
      original.close(raw);
      for (int i = columnOptions.size() - 1; i >= 0; i--) {
        columnOptions.get(i).close();
      }
      for (int i = bloomFilters.size() - 1; i >= 0; i--) {
        bloomFilters.get(i).close();
      }
      blockCache.close();
      dbOptions.close();
      if (statistics != null) {
        statistics.close();
      }
    }
  }

  @Test
  public void columnFamilyHandleFailurePinsEveryDependentNativeOwner() {
    AssertionError injected = new AssertionError("injected handle close failure");
    ColumnFamilyHandle firstHandle = mock(ColumnFamilyHandle.class);
    ColumnFamilyHandle failingHandle = mock(ColumnFamilyHandle.class);
    doThrow(injected).when(failingHandle).close();
    AtomicBoolean dbCloseCalled = new AtomicBoolean();
    ColumnFamilyOptions columnOptions = mock(ColumnFamilyOptions.class);
    DBOptions dbOptions = mock(DBOptions.class);
    BloomFilter bloomFilter = mock(BloomFilter.class);
    Cache blockCache = mock(Cache.class);
    Statistics statistics = mock(Statistics.class);

    Throwable failure = UnifiedArchiveDb.closeResources(
        Arrays.asList(firstHandle, failingHandle), mock(RocksDB.class),
        Collections.singletonList(columnOptions), Collections.singletonList(bloomFilter),
        blockCache, dbOptions, statistics, null,
        ignored -> dbCloseCalled.set(true));

    assertTrue(failure instanceof ArchiveNativeResourceReleaseException);
    assertSame(injected, failure.getCause());
    verify(firstHandle).close();
    verify(failingHandle).close();
    assertFalse(dbCloseCalled.get());
    verify(columnOptions, never()).close();
    verify(dbOptions, never()).close();
    verify(bloomFilter, never()).close();
    verify(blockCache, never()).close();
    verify(statistics, never()).close();
  }

  @Test
  public void rocksDbFailurePinsOptionsAndTheirDependencies() {
    AssertionError injected = new AssertionError("injected DB close failure");
    ColumnFamilyHandle handle = mock(ColumnFamilyHandle.class);
    ColumnFamilyOptions columnOptions = mock(ColumnFamilyOptions.class);
    DBOptions dbOptions = mock(DBOptions.class);
    BloomFilter bloomFilter = mock(BloomFilter.class);
    Cache blockCache = mock(Cache.class);
    Statistics statistics = mock(Statistics.class);

    Throwable failure = UnifiedArchiveDb.closeResources(
        Collections.singletonList(handle), mock(RocksDB.class),
        Collections.singletonList(columnOptions), Collections.singletonList(bloomFilter),
        blockCache, dbOptions, statistics, null, ignored -> {
          throw injected;
        });

    assertTrue(failure instanceof ArchiveNativeResourceReleaseException);
    assertSame(injected, failure.getCause());
    verify(handle).close();
    verify(columnOptions, never()).close();
    verify(dbOptions, never()).close();
    verify(bloomFilter, never()).close();
    verify(blockCache, never()).close();
    verify(statistics, never()).close();
  }

  @Test
  public void optionFailuresPinOnlyTheirOwnDependencyBranches() {
    AssertionError columnFailure = new AssertionError("injected column options failure");
    AssertionError dbOptionsFailure = new AssertionError("injected DB options failure");
    ColumnFamilyOptions columnOptions = mock(ColumnFamilyOptions.class);
    doThrow(columnFailure).when(columnOptions).close();
    DBOptions dbOptions = mock(DBOptions.class);
    doThrow(dbOptionsFailure).when(dbOptions).close();
    BloomFilter bloomFilter = mock(BloomFilter.class);
    Cache blockCache = mock(Cache.class);
    Statistics statistics = mock(Statistics.class);

    Throwable failure = UnifiedArchiveDb.closeResources(
        Collections.emptyList(), null, Collections.singletonList(columnOptions),
        Collections.singletonList(bloomFilter), blockCache, dbOptions, statistics,
        null, ignored -> { });

    assertTrue(failure instanceof ArchiveNativeResourceReleaseException);
    assertSame(columnFailure, failure.getCause());
    assertEquals(1, columnFailure.getSuppressed().length);
    assertSame(dbOptionsFailure, columnFailure.getSuppressed()[0]);
    verify(bloomFilter, never()).close();
    verify(blockCache, never()).close();
    verify(statistics, never()).close();
  }

  @Test
  public void columnFamilyOptionFailureStillReleasesDbOptionsBranch() {
    AssertionError injected = new AssertionError("injected column options failure");
    ColumnFamilyOptions columnOptions = mock(ColumnFamilyOptions.class);
    doThrow(injected).when(columnOptions).close();
    DBOptions dbOptions = mock(DBOptions.class);
    BloomFilter bloomFilter = mock(BloomFilter.class);
    Cache blockCache = mock(Cache.class);
    Statistics statistics = mock(Statistics.class);

    Throwable failure = UnifiedArchiveDb.closeResources(
        Collections.emptyList(), null, Collections.singletonList(columnOptions),
        Collections.singletonList(bloomFilter), blockCache, dbOptions, statistics,
        null, ignored -> { });

    assertTrue(failure instanceof ArchiveNativeResourceReleaseException);
    assertSame(injected, failure.getCause());
    verify(bloomFilter, never()).close();
    verify(blockCache, never()).close();
    verify(dbOptions).close();
    verify(statistics).close();
  }

  @Test
  public void dbOptionsFailureStillReleasesColumnFamilyBranch() {
    AssertionError injected = new AssertionError("injected DB options failure");
    ColumnFamilyOptions columnOptions = mock(ColumnFamilyOptions.class);
    DBOptions dbOptions = mock(DBOptions.class);
    doThrow(injected).when(dbOptions).close();
    BloomFilter bloomFilter = mock(BloomFilter.class);
    Cache blockCache = mock(Cache.class);
    Statistics statistics = mock(Statistics.class);

    Throwable failure = UnifiedArchiveDb.closeResources(
        Collections.emptyList(), null, Collections.singletonList(columnOptions),
        Collections.singletonList(bloomFilter), blockCache, dbOptions, statistics,
        null, ignored -> { });

    assertTrue(failure instanceof ArchiveNativeResourceReleaseException);
    assertSame(injected, failure.getCause());
    verify(columnOptions).close();
    verify(bloomFilter).close();
    verify(blockCache).close();
    verify(dbOptions).close();
    verify(statistics, never()).close();
  }

  @Test
  public void openingFailureRemainsSuppressedWhenNativeCleanupIsUncertain() {
    RuntimeException openingFailure = new RuntimeException("injected open failure");
    AssertionError cleanupFailure = new AssertionError("injected open cleanup failure");
    ColumnFamilyHandle handle = mock(ColumnFamilyHandle.class);
    doThrow(cleanupFailure).when(handle).close();

    Throwable failure = UnifiedArchiveDb.closeResources(
        Collections.singletonList(handle), null, Collections.emptyList(),
        Collections.emptyList(), null, null, null, openingFailure, ignored -> { });

    assertTrue(failure instanceof ArchiveNativeResourceReleaseException);
    assertSame(cleanupFailure, failure.getCause());
    assertEquals(1, failure.getSuppressed().length);
    assertSame(openingFailure, failure.getSuppressed()[0]);
  }

  @Test
  public void ownsOneSharedCacheAndPayloadBloomFilter() {
    assertEquals(UnifiedArchiveDb.expectedColumnFamilyNames().size(),
        db.ownedBloomFilterCount());
    assertEquals(1, db.ownedBlockCacheCount());
    assertEquals(72L * 1024L * 1024L, db.configuredBlockCacheBytes());
    assertTrue(db.usesEvictableIndexAndFilterCache());
    assertTrue(db.temporalPayloadMetadataUsesEvictableCache());
    assertTrue(db.temporalPayloadOptimizesFiltersForHits());
    assertTrue(db.usesStableSstTableFormat());
    assertTrue(db.usesDynamicLevelCompaction());
  }

  @Test
  public void usesBoundedBackgroundIoDefaults() {
    assertEquals(2, db.maxBackgroundJobs());
    assertEquals(128L * 1024L * 1024L, db.dbWriteBufferSize());
    assertEquals(256L * 1024L * 1024L, db.maxTotalWalSize());
    assertEquals(512, db.maxOpenFiles());
    assertEquals(1024L * 1024L, db.bytesPerSync());
    assertEquals(0L, db.walBytesPerSync());
    assertFalse(db.usesDirectIo());
  }

  private static boolean hasReadOptionsMethod(String name) {
    try {
      ReadOptions.class.getMethod(name);
      return true;
    } catch (NoSuchMethodException e) {
      return false;
    }
  }

  private static FutureTask<Throwable> failureOf(Runnable action) {
    return new FutureTask<>(() -> {
      try {
        action.run();
        return null;
      } catch (Throwable failure) {
        return failure;
      }
    });
  }

  @Test
  public void exactReadsAccountLocatorAfterReadAndPayloadBeforeReadExactlyOnce() {
    byte[] locator = new byte[45];
    Arrays.fill(locator, (byte) 0x11);
    db.writeMaintenanceAtomically(new UnifiedArchiveMaintenanceBatch()
        .put(UnifiedArchiveColumnFamily.HISTORY, HISTORY_KEY, locator)
        .put(UnifiedArchiveColumnFamily.TEMPORAL_PAYLOAD,
            TEMPORAL_PAYLOAD_KEY, TEMPORAL_PAYLOAD_VALUE));
    QueryContext context = new QueryContext(ArchiveQueryLimits.builder()
        .maxBackendValueBytes(1024L)
        .maxBackendReadBytesPerRequest(1024L)
        .build());

    try (UnifiedArchiveReadView view = db.openQueryReadView();
        QueryContextHolder.Scope ignored = QueryContextHolder.attach(context)) {
      assertArrayEquals(locator, view.getExact(
          UnifiedArchiveColumnFamily.HISTORY, HISTORY_KEY, locator.length, "locator"));
      assertArrayEquals(TEMPORAL_PAYLOAD_VALUE, view.getExactBudgeted(
          UnifiedArchiveColumnFamily.TEMPORAL_PAYLOAD, TEMPORAL_PAYLOAD_KEY,
          TEMPORAL_PAYLOAD_VALUE.length, "payload"));
    }

    assertEquals(locator.length + TEMPORAL_PAYLOAD_VALUE.length,
        context.getBackendReadBytes());
  }

  @Test
  public void nonQueryExactBudgetedReadDoesNotAllocateFromUntrustedExpectedLength() {
    try (UnifiedArchiveReadView view = db.openReadView()) {
      assertNull(view.getExactBudgeted(
          UnifiedArchiveColumnFamily.TEMPORAL_PAYLOAD, ascii("missing-payload"),
          256L * 1024L * 1024L, "missing payload"));

      byte[] probe = ReflectUtils.getFieldValue(view, "boundedGetProbe");
      assertEquals(64 * 1024, probe.length);
    }
  }

  @Test
  public void queryExactBudgetedReadProbesBeforeUntrustedExpectedLengthAllocation() {
    QueryContext context = new QueryContext(ArchiveQueryLimits.unlimited());
    try (UnifiedArchiveReadView view = db.openQueryReadView();
        QueryContextHolder.Scope ignored = QueryContextHolder.attach(context)) {
      assertNull(view.getExactBudgeted(
          UnifiedArchiveColumnFamily.TEMPORAL_PAYLOAD, ascii("missing-query-payload"),
          256L * 1024L * 1024L, "missing query payload"));

      byte[] probe = ReflectUtils.getFieldValue(view, "boundedGetProbe");
      assertEquals(64 * 1024, probe.length);
      assertEquals(0L, context.getBackendReadBytes());
    }
  }

  @Test
  public void productionOpenAvoidsUnusedNativeStatistics() {
    db.close();
    db = null;
    try {
      try (UnifiedArchiveDb withoutStatistics =
               UnifiedArchiveDb.open(dbPath, SCHEMA_CHECKSUM)) {
        assertFalse(withoutStatistics.hasStatistics());
      }
      try (UnifiedArchiveDb withStatistics =
               UnifiedArchiveDb.openWithStatisticsForTesting(dbPath, SCHEMA_CHECKSUM)) {
        assertTrue(withStatistics.hasStatistics());
        assertEquals(StatsLevel.EXCEPT_DETAILED_TIMERS,
            withStatistics.statisticsLevel());
      }
    } finally {
      db = UnifiedArchiveDb.open(dbPath, SCHEMA_CHECKSUM);
    }
  }

  @Test
  public void openDoesNotCreateMissingPathOrInitializeEmptyDirectory() throws IOException {
    Path missing = root.resolve("missing");
    assertThrows(ArchiveException.class,
        () -> UnifiedArchiveDb.open(missing, SCHEMA_CHECKSUM));
    assertFalse(Files.exists(missing));

    Path empty = root.resolve("empty");
    Files.createDirectory(empty);
    assertThrows(ArchiveException.class,
        () -> UnifiedArchiveDb.open(empty, SCHEMA_CHECKSUM));
    assertThrows(ArchiveException.class,
        () -> UnifiedArchiveDb.initialize(empty, SCHEMA_CHECKSUM));
    assertEquals(0, empty.toFile().list().length);
  }

  @Test
  public void openRejectsSchemaMismatch() {
    closeForRawEdit();
    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> UnifiedArchiveDb.open(dbPath, repeated(0x6b)));
    assertTrue(failure.getMessage().contains("schema checksum mismatch"));
    assertTrue(failure.getMessage().contains("rebuild"));
  }

  @Test
  public void openRejectsOversizedManifestBeforeMaterialization() throws Exception {
    closeForRawEdit();
    editRaw((rocksDb, handles) -> {
      try (WriteOptions writeOptions = new WriteOptions().setDisableWAL(false).setSync(true)) {
        rocksDb.put(handles.get(UnifiedArchiveColumnFamily.META.ordinal() + 1),
            writeOptions, UnifiedArchiveManifest.key(), new byte[128 * 1024]);
      }
    });

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> UnifiedArchiveDb.open(dbPath, SCHEMA_CHECKSUM));

    assertTrue(failure.getMessage().contains("manifest has invalid byte length"));
  }

  @Test
  public void txNumStartupClassifiesEmptyIndexKeyAsPersistentCorruption() throws Exception {
    closeForRawEdit();
    editRaw((rocksDb, handles) -> {
      try (WriteOptions writeOptions = new WriteOptions().setDisableWAL(false).setSync(true)) {
        rocksDb.put(handles.get(UnifiedArchiveColumnFamily.INDEX.ordinal() + 1),
            writeOptions, new byte[0], new byte[] {1});
      }
    });
    db = UnifiedArchiveDb.open(dbPath, SCHEMA_CHECKSUM);

    ArchivePersistentStateCorruptionException failure = assertThrows(
        ArchivePersistentStateCorruptionException.class,
        () -> new UnifiedArchiveTxNumIndex(db, SCHEMA_CHECKSUM, false, true));

    assertTrue(failure.getMessage().contains("index column family has an empty key"));
  }

  @Test
  public void openRejectsPreTemporalPayloadLayoutSchema() throws Exception {
    closeForRawEdit();
    byte[] current = UnifiedArchiveManifest.value(SCHEMA_CHECKSUM);
    byte[] previous = new String(current, StandardCharsets.US_ASCII)
        .replace("layout-schema=6", "layout-schema=5")
        .getBytes(StandardCharsets.US_ASCII);
    editRaw((rocksDb, handles) -> {
      try (WriteOptions writeOptions = new WriteOptions().setDisableWAL(false).setSync(true)) {
        rocksDb.put(handles.get(UnifiedArchiveColumnFamily.META.ordinal() + 1),
            writeOptions, UnifiedArchiveManifest.key(), previous);
      }
    });

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> UnifiedArchiveDb.open(dbPath, SCHEMA_CHECKSUM));
    assertTrue(failure.getMessage().contains("layout schema mismatch"));
    assertTrue(failure.getMessage().contains("rebuild"));
  }

  @Test
  public void openRejectsMissingManifest() throws Exception {
    closeForRawEdit();
    editRaw((rocksDb, handles) -> {
      try (WriteOptions writeOptions = new WriteOptions().setDisableWAL(false).setSync(true)) {
        rocksDb.delete(handles.get(UnifiedArchiveColumnFamily.META.ordinal() + 1),
            writeOptions, UnifiedArchiveManifest.key());
      }
    });

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> UnifiedArchiveDb.open(dbPath, SCHEMA_CHECKSUM));
    assertTrue(failure.getMessage().contains("missing its manifest"));
  }

  @Test
  public void emptyInitializationResumesAfterManifestWriteWasInterrupted() throws Exception {
    closeForRawEdit();
    editRaw((rocksDb, handles) -> {
      try (WriteOptions writeOptions = new WriteOptions().setDisableWAL(false).setSync(true)) {
        rocksDb.delete(handles.get(UnifiedArchiveColumnFamily.META.ordinal() + 1),
            writeOptions, UnifiedArchiveManifest.key());
      }
    });

    db = UnifiedArchiveDb.initializeOrResumeEmpty(dbPath, SCHEMA_CHECKSUM);
    assertFalse(db.hasArchiveData());
    db.close();
    db = UnifiedArchiveDb.open(dbPath, SCHEMA_CHECKSUM);
  }

  @Test
  public void emptyInitializationDoesNotRepairAStoreContainingArchiveRows() throws Exception {
    closeForRawEdit();
    editRaw((rocksDb, handles) -> {
      try (WriteOptions writeOptions = new WriteOptions().setDisableWAL(false).setSync(true)) {
        rocksDb.delete(handles.get(UnifiedArchiveColumnFamily.META.ordinal() + 1),
            writeOptions, UnifiedArchiveManifest.key());
        rocksDb.put(handles.get(UnifiedArchiveColumnFamily.INDEX.ordinal() + 1),
            writeOptions, INDEX_KEY, INDEX_VALUE);
      }
    });

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> UnifiedArchiveDb.initializeOrResumeEmpty(dbPath, SCHEMA_CHECKSUM));
    assertTrue(failure.getMessage().contains("archive rows present"));
  }

  @Test
  public void openRejectsUnexpectedColumnFamily() throws Exception {
    closeForRawEdit();
    editRaw((rocksDb, handles) -> {
      try (ColumnFamilyOptions options = new ColumnFamilyOptions();
           ColumnFamilyHandle ignored = rocksDb.createColumnFamily(
               new ColumnFamilyDescriptor(ascii("unexpected"), options))) {
        assertEquals("unexpected",
            new String(ignored.getName(), StandardCharsets.US_ASCII));
      }
    });

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> UnifiedArchiveDb.open(dbPath, SCHEMA_CHECKSUM));
    assertTrue(failure.getMessage().contains("column family set mismatch"));
  }

  @Test
  public void everyWriteOptionKeepsWalEnabled() {
    try (WriteOptions asynchronous = UnifiedArchiveDb.createWriteOptions(false);
         WriteOptions synchronous = UnifiedArchiveDb.createWriteOptions(true)) {
      assertFalse(asynchronous.disableWAL());
      assertFalse(asynchronous.sync());
      assertFalse(synchronous.disableWAL());
      assertTrue(synchronous.sync());
    }
  }

  @Test
  public void publishRejectsReservedCursorAndDuplicateMutationKeys() {
    assertThrows(ArchiveException.class, () -> UnifiedArchivePublish.builder()
        .journal(JOURNAL_KEY, JOURNAL_VALUE)
        .cursor(ascii("not-the-published-cursor"), CURSOR_VALUE));

    assertThrows(ArchiveException.class, () -> UnifiedArchivePublish.builder()
        .journal(JOURNAL_KEY, JOURNAL_VALUE)
        .journalToken(TOKEN_KEY, TOKEN_VALUE)
        .acknowledgement(ACKNOWLEDGEMENT_KEY, ACKNOWLEDGEMENT_VALUE)
        .cursor(CURSOR_KEY, CURSOR_VALUE)
        .blockMarker(MARKER_KEY, MARKER_VALUE)
        .put(UnifiedArchiveColumnFamily.INDEX, INDEX_KEY, INDEX_VALUE)
        .put(UnifiedArchiveColumnFamily.LATEST, LATEST_KEY, LATEST_VALUE)
        .put(UnifiedArchiveColumnFamily.LATEST, LATEST_KEY, ascii("replacement"))
        .build());
  }

  @Test
  public void genericMetaWritesCannotReplaceManifestOrPublishedCursor() {
    assertThrows(ArchiveException.class,
        () -> db.putMetaDurably(UnifiedArchiveManifest.key(), ascii("replacement")));
    assertThrows(ArchiveException.class,
        () -> db.putMetaDurably(CURSOR_KEY, CURSOR_VALUE));
  }

  private static UnifiedArchivePublish publish() {
    return UnifiedArchivePublish.builder()
        .journal(JOURNAL_KEY, JOURNAL_VALUE)
        .journalToken(TOKEN_KEY, TOKEN_VALUE)
        .acknowledgement(ACKNOWLEDGEMENT_KEY, ACKNOWLEDGEMENT_VALUE)
        .cursor(CURSOR_KEY, CURSOR_VALUE)
        .blockMarker(MARKER_KEY, MARKER_VALUE)
        .put(UnifiedArchiveColumnFamily.INDEX, INDEX_KEY, INDEX_VALUE)
        .put(UnifiedArchiveColumnFamily.LATEST, LATEST_KEY, LATEST_VALUE)
        .put(UnifiedArchiveColumnFamily.HISTORY, HISTORY_KEY, HISTORY_VALUE)
        .put(UnifiedArchiveColumnFamily.CHANGESET, CHANGESET_KEY, CHANGESET_VALUE)
        .put(UnifiedArchiveColumnFamily.TEMPORAL_PAYLOAD,
            TEMPORAL_PAYLOAD_KEY, TEMPORAL_PAYLOAD_VALUE)
        .put(UnifiedArchiveColumnFamily.COMMITMENT, COMMITMENT_KEY, COMMITMENT_VALUE)
        .build();
  }

  private static void assertUnpublished(UnifiedArchiveReadView view) {
    assertArrayEquals(JOURNAL_VALUE,
        view.get(UnifiedArchiveColumnFamily.INFLIGHT, JOURNAL_KEY));
    assertArrayEquals(TOKEN_VALUE,
        view.get(UnifiedArchiveColumnFamily.INFLIGHT, TOKEN_KEY));
    assertArrayEquals(ACKNOWLEDGEMENT_VALUE,
        view.get(UnifiedArchiveColumnFamily.INFLIGHT, ACKNOWLEDGEMENT_KEY));
    assertNull(view.get(UnifiedArchiveColumnFamily.META, CURSOR_KEY));
    assertNull(view.get(UnifiedArchiveColumnFamily.INDEX, INDEX_KEY));
    assertNull(view.get(UnifiedArchiveColumnFamily.LATEST, LATEST_KEY));
    assertNull(view.get(UnifiedArchiveColumnFamily.HISTORY, HISTORY_KEY));
    assertNull(view.get(UnifiedArchiveColumnFamily.CHANGESET, CHANGESET_KEY));
    assertNull(view.get(
        UnifiedArchiveColumnFamily.TEMPORAL_PAYLOAD, TEMPORAL_PAYLOAD_KEY));
    assertNull(view.get(UnifiedArchiveColumnFamily.BLOCK_MARKER, MARKER_KEY));
    assertNull(view.get(UnifiedArchiveColumnFamily.COMMITMENT, COMMITMENT_KEY));
  }

  private static void assertPublished(UnifiedArchiveReadView view) {
    assertNull(view.get(UnifiedArchiveColumnFamily.INFLIGHT, JOURNAL_KEY));
    assertNull(view.get(UnifiedArchiveColumnFamily.INFLIGHT, TOKEN_KEY));
    assertNull(view.get(UnifiedArchiveColumnFamily.INFLIGHT, ACKNOWLEDGEMENT_KEY));
    assertArrayEquals(CURSOR_VALUE,
        view.get(UnifiedArchiveColumnFamily.META, CURSOR_KEY));
    assertArrayEquals(INDEX_VALUE,
        view.get(UnifiedArchiveColumnFamily.INDEX, INDEX_KEY));
    assertArrayEquals(LATEST_VALUE,
        view.get(UnifiedArchiveColumnFamily.LATEST, LATEST_KEY));
    assertArrayEquals(HISTORY_VALUE,
        view.get(UnifiedArchiveColumnFamily.HISTORY, HISTORY_KEY));
    assertArrayEquals(CHANGESET_VALUE,
        view.get(UnifiedArchiveColumnFamily.CHANGESET, CHANGESET_KEY));
    assertArrayEquals(TEMPORAL_PAYLOAD_VALUE,
        view.get(UnifiedArchiveColumnFamily.TEMPORAL_PAYLOAD, TEMPORAL_PAYLOAD_KEY));
    assertArrayEquals(MARKER_VALUE,
        view.get(UnifiedArchiveColumnFamily.BLOCK_MARKER, MARKER_KEY));
    assertArrayEquals(COMMITMENT_VALUE,
        view.get(UnifiedArchiveColumnFamily.COMMITMENT, COMMITMENT_KEY));
  }

  private void putJournalBundle() {
    db.putJournalBlockDurably(
        JOURNAL_KEY, JOURNAL_VALUE, TOKEN_KEY, TOKEN_VALUE,
        ACKNOWLEDGEMENT_KEY, ACKNOWLEDGEMENT_VALUE);
  }

  private void assertExactColumnFamilies() throws RocksDBException {
    List<byte[]> actual;
    try (Options options = new Options().setCreateIfMissing(false)) {
      actual = RocksDB.listColumnFamilies(options, dbPath.toString());
    }
    List<byte[]> expected = UnifiedArchiveDb.expectedColumnFamilyNames();
    assertEquals(expected.size(), actual.size());
    for (byte[] expectedName : expected) {
      assertTrue(contains(actual, expectedName));
    }
  }

  private static boolean contains(List<byte[]> names, byte[] expected) {
    for (byte[] name : names) {
      if (Arrays.equals(name, expected)) {
        return true;
      }
    }
    return false;
  }

  private void closeForRawEdit() {
    db.close();
    db = null;
  }

  private void editRaw(RawEditor editor) throws Exception {
    List<ColumnFamilyOptions> optionOwners = new ArrayList<>();
    List<ColumnFamilyDescriptor> descriptors = new ArrayList<>();
    for (byte[] name : UnifiedArchiveDb.expectedColumnFamilyNames()) {
      ColumnFamilyOptions options = new ColumnFamilyOptions();
      optionOwners.add(options);
      descriptors.add(new ColumnFamilyDescriptor(name, options));
    }
    List<ColumnFamilyHandle> handles = new ArrayList<>();
    DBOptions options = new DBOptions()
        .setCreateIfMissing(false)
        .setCreateMissingColumnFamilies(false);
    RocksDB rocksDb = null;
    try {
      rocksDb = RocksDB.open(options, dbPath.toString(), descriptors, handles);
      editor.edit(rocksDb, handles);
    } finally {
      for (int i = handles.size() - 1; i >= 0; i--) {
        handles.get(i).close();
      }
      if (rocksDb != null) {
        rocksDb.close();
      }
      options.close();
      for (int i = optionOwners.size() - 1; i >= 0; i--) {
        optionOwners.get(i).close();
      }
    }
  }

  private static byte[] repeated(int value) {
    byte[] bytes = new byte[32];
    Arrays.fill(bytes, (byte) value);
    return bytes;
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static UnifiedArchiveJournalVerifier journalVerifier(byte[] expected) {
    byte[] immutableExpected = Arrays.copyOf(expected, expected.length);
    return new UnifiedArchiveJournalVerifier() {
      @Override
      public long expectedPayloadBytes() {
        return immutableExpected.length;
      }

      @Override
      public void requireMatches(byte[] journalKey, byte[] payload) {
        if (!Arrays.equals(immutableExpected, payload)) {
          throw new ArchiveException("test journal payload mismatch");
        }
      }
    };
  }

  private static UnifiedArchiveJournalVerifier changingLengthVerifier(
      AtomicInteger lengthReads) {
    return new UnifiedArchiveJournalVerifier() {
      @Override
      public long expectedPayloadBytes() {
        return lengthReads.getAndIncrement() == 0
            ? JOURNAL_VALUE.length : Integer.MAX_VALUE;
      }

      @Override
      public void requireMatches(byte[] journalKey, byte[] payload) {
        if (!Arrays.equals(JOURNAL_VALUE, payload)) {
          throw new ArchiveException("test journal payload mismatch");
        }
      }
    };
  }

  private static void deleteRecursively(File file) {
    if (file == null || !file.exists()) {
      return;
    }
    File[] children = file.listFiles();
    if (children != null) {
      for (File child : children) {
        deleteRecursively(child);
      }
    }
    file.delete();
  }

  private interface RawEditor {
    void edit(RocksDB rocksDb, List<ColumnFamilyHandle> handles) throws Exception;
  }
}
