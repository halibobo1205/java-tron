package org.tron.core.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.tron.common.utils.ReflectUtils;
import org.tron.core.archive.unified.UnifiedArchiveIterator;

public class ArchiveValidationCheckpointTest {

  @Rule
  public final TemporaryFolder temporaryFolder = new TemporaryFolder();
  private final byte[] schema = new byte[32];
  private final AtomicLong sequence = new AtomicLong(7L);
  private Path directory;

  @Before
  public void setUp() throws Exception {
    directory = temporaryFolder.getRoot().toPath();
    Files.write(directory.resolve("IDENTITY"), "test-database".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  public void durableCursorAndCompletedStagesSurviveReopen() {
    ArchiveValidationCheckpoint checkpoint = open();
    checkpoint.progress("index-keyspace").complete();
    checkpoint.save("ranges-and-positions", 1024L, 1023L, new byte[] {1, 2, 3}, false);
    ArchiveValidationCheckpoint reopened = open();
    assertTrue(reopened.progress("index-keyspace").isComplete());
    assertArrayEquals(new byte[] {1, 2, 3},
        reopened.progress("ranges-and-positions").resumeKey());
    assertTrue(Files.exists(directory.resolve(ArchiveValidationCheckpoint.FILE_NAME)));
  }

  @Test
  public void mutationOrSchemaOrIdentityChangeInvalidatesProgress() throws Exception {
    open().save("index-keyspace", 1024L, -1L, new byte[] {1}, false);
    sequence.incrementAndGet();
    assertFresh(open());
    sequence.decrementAndGet();
    schema[0] = 1;
    assertFresh(open());
    schema[0] = 0;
    Files.write(directory.resolve("IDENTITY"), "another-db".getBytes(StandardCharsets.UTF_8));
    assertFresh(open());
  }

  @Test
  public void damagedOrTruncatedOrOversizedCheckpointFallsBackToFullScan() throws Exception {
    Path progress = directory.resolve(ArchiveValidationCheckpoint.FILE_NAME);
    open().save("index-keyspace", 1024L, -1L, new byte[] {1}, false);
    byte[] valid = Files.readAllBytes(progress);
    for (int offset : new int[] {0, 10, 36, 40, valid.length - 1}) {
      byte[] damaged = valid.clone();
      damaged[offset] ^= 1;
      Files.write(progress, damaged);
      assertFresh(open());
    }
    Files.write(progress, new byte[3]);
    assertFresh(open());
    Files.write(progress, new byte[65536]);
    assertFresh(open());
  }

  @Test
  public void fastBatchesThrottleCheckpointIoButStageCompletionStillPersists() {
    ArchiveStartupProgress progress = open().progress("index-keyspace");
    UnifiedArchiveIterator iterator = mock(UnifiedArchiveIterator.class);
    when(iterator.key()).thenReturn(new byte[] {1});
    recordBatch(progress, iterator);
    assertArrayEquals(new byte[] {1}, open().progress("index-keyspace").resumeKey());

    ReflectUtils.setFieldValue(progress, "checkpointNanos",
        System.nanoTime() + TimeUnit.HOURS.toNanos(1L));
    when(iterator.key()).thenReturn(new byte[] {2});
    recordBatch(progress, iterator);
    assertArrayEquals(new byte[] {1}, open().progress("index-keyspace").resumeKey());

    ReflectUtils.setFieldValue(progress, "checkpointNanos",
        System.nanoTime() - TimeUnit.HOURS.toNanos(1L));
    when(iterator.key()).thenReturn(new byte[] {3});
    recordBatch(progress, iterator);
    assertArrayEquals(new byte[] {3}, open().progress("index-keyspace").resumeKey());
    progress.complete();
    assertTrue(open().progress("index-keyspace").isComplete());
  }

  private static void recordBatch(
      ArchiveStartupProgress progress, UnifiedArchiveIterator iterator) {
    for (int i = 0; i < 1024; i++) {
      progress.record(-1L, iterator);
    }
  }

  @Test
  public void wideValidKeysDoNotFailValidationOrAdvanceAnUnrepresentableCursor() {
    ArchiveValidationCheckpoint checkpoint = open();
    checkpoint.save("index-keyspace", 1024L, -1L, new byte[] {1}, false);
    checkpoint.save("index-keyspace", 2048L, -1L, new byte[2048], false);
    assertArrayEquals(new byte[] {1}, open().progress("index-keyspace").resumeKey());
    checkpoint.progress("index-keyspace").complete();
    assertTrue(open().progress("index-keyspace").isComplete());
  }

  @Test
  public void copiedDatabasePathDoesNotReuseProgress() throws Exception {
    open().save("index-keyspace", 1024L, -1L, new byte[] {1}, false);
    Path copy = directory.resolve("copy");
    Files.createDirectory(copy);
    Files.copy(directory.resolve("IDENTITY"), copy.resolve("IDENTITY"));
    Files.copy(directory.resolve(ArchiveValidationCheckpoint.FILE_NAME),
        copy.resolve(ArchiveValidationCheckpoint.FILE_NAME));
    ArchiveValidationCheckpoint copied = ArchiveValidationCheckpoint.open(
        copy, schema, sequence.get(), sequence::get);
    assertNotNull(copied);
    assertFresh(copied);
  }

  @Test
  public void missingResumeBoundaryFailsClosedAndDiscardsProgress() {
    open().save("index-keyspace", 1024L, -1L, new byte[] {1}, false);
    ArchiveStartupProgress progress = open().progress("index-keyspace");
    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> progress.seek(mock(UnifiedArchiveIterator.class), null));
    assertTrue(failure.getMessage().contains("cursor is missing"));
    assertFalse(Files.exists(directory.resolve(ArchiveValidationCheckpoint.FILE_NAME)));
  }

  @Test
  public void completedScanCanFinishAfterInterruptionBeforeProgressRemoval() {
    ArchiveValidationCheckpoint checkpoint = open();
    String[] stages = {
        "index-keyspace", "ranges-and-positions", "temporal-blocks", "history-links",
        "changeset-links", "latest-links", "anchors", "latest-domains", "history-domains",
        "changeset-domains", "payload-owners"
    };
    for (String stage : stages) {
      checkpoint.progress(stage).complete();
    }
    ArchiveValidationCheckpoint reopened = open();
    for (String stage : stages) {
      assertTrue(stage, reopened.progress(stage).isComplete());
    }
    reopened.finish();
    assertFalse(Files.exists(directory.resolve(ArchiveValidationCheckpoint.FILE_NAME)));
  }

  @Test
  public void temporaryFileNeverCountsAsCommittedProgress() throws Exception {
    Path progress = directory.resolve(ArchiveValidationCheckpoint.FILE_NAME);
    open().save("index-keyspace", 1024L, -1L, new byte[] {1}, false);
    Files.move(progress, directory.resolve(ArchiveValidationCheckpoint.FILE_NAME + ".tmp"));
    assertFresh(open());
  }

  @Test
  public void changedSequenceCannotAdvanceOrFinishCheckpoint() {
    ArchiveValidationCheckpoint checkpoint = open();
    checkpoint.save("index-keyspace", 1024L, -1L, new byte[] {1}, false);
    sequence.incrementAndGet();
    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> checkpoint.save("index-keyspace", 2048L, -1L, new byte[] {2}, false));
    assertTrue(failure.getMessage().contains("database changed"));
    assertFalse(Files.exists(directory.resolve(ArchiveValidationCheckpoint.FILE_NAME)));
  }

  @Test
  public void cannotSkipValidationStagesOrFinishEarly() {
    ArchiveValidationCheckpoint checkpoint = open();
    ArchiveException skipped = assertThrows(ArchiveException.class,
        () -> checkpoint.progress("temporal-blocks"));
    assertTrue(skipped.getMessage().contains("stage skipped"));
    ArchiveException early = assertThrows(ArchiveException.class, checkpoint::finish);
    assertTrue(early.getMessage().contains("before all stages completed"));
  }

  @Test
  public void symlinkCheckpointIsNotFollowedAndCannotAuthorizeSkipping() throws Exception {
    Path external = directory.resolve("external");
    byte[] sentinel = new byte[] {9, 8, 7};
    Files.write(external, sentinel);
    Files.createSymbolicLink(directory.resolve(ArchiveValidationCheckpoint.FILE_NAME), external);
    ArchiveValidationCheckpoint checkpoint = open();
    assertFresh(checkpoint);
    checkpoint.save("index-keyspace", 1024L, -1L, new byte[] {1}, false);
    assertArrayEquals(sentinel, Files.readAllBytes(external));
    assertArrayEquals(new byte[] {1}, open().progress("index-keyspace").resumeKey());
  }

  @Test
  public void progressWriteFailureDoesNotOverwriteSymlinkTarget() throws Exception {
    Path external = directory.resolve("external");
    byte[] sentinel = new byte[] {9, 8, 7};
    Files.write(external, sentinel);
    Files.createSymbolicLink(directory.resolve(ArchiveValidationCheckpoint.FILE_NAME + ".tmp"),
        external);
    open().save("index-keyspace", 1024L, -1L, new byte[] {1}, false);
    assertArrayEquals(sentinel, Files.readAllBytes(external));
    assertFresh(open());
  }

  private ArchiveValidationCheckpoint open() {
    ArchiveValidationCheckpoint result = ArchiveValidationCheckpoint.open(
        directory, schema, sequence.get(), sequence::get);
    assertNotNull(result);
    return result;
  }

  private static void assertFresh(ArchiveValidationCheckpoint checkpoint) {
    ArchiveStartupProgress progress = checkpoint.progress("index-keyspace");
    assertFalse(progress.isComplete());
    assertNull(progress.resumeKey());
  }
}
