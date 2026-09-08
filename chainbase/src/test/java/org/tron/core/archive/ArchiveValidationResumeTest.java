package org.tron.core.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.domain.ArchiveSchemaChecksum;
import org.tron.core.archive.domain.DefaultArchiveDomainCatalog;
import org.tron.core.archive.domain.DefaultArchiveDomainRegistry;
import org.tron.core.archive.temporal.UnifiedArchiveTemporalStore;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.archive.txnum.ArchiveTxPosition;
import org.tron.core.archive.txnum.UnifiedArchiveTxNumIndex;
import org.tron.core.archive.unified.UnifiedArchiveDb;
import org.tron.core.archive.unified.UnifiedArchiveIterator;
import org.tron.core.archive.unified.UnifiedArchiveReadView;
import org.tron.protos.Protocol.Account;

@RunWith(Parameterized.class)
public class ArchiveValidationResumeTest {

  private static final DefaultArchiveDomainCatalog CATALOG = new DefaultArchiveDomainCatalog();
  private static final byte[] SCHEMA = ArchiveSchemaChecksum.of(
      new DefaultArchiveDomainRegistry(), CATALOG);
  private static final String[] STAGES = {
      "index-keyspace", "ranges-and-positions", "temporal-blocks", "history-links",
      "changeset-links", "latest-links", "anchors", "latest-domains", "history-domains",
      "changeset-domains", "payload-owners"
  };
  private static final Map<String, Integer> FULL_COUNTS = new LinkedHashMap<>();
  private static Path fixture;
  private final String interruptedStage;

  @Rule
  public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  public ArchiveValidationResumeTest(String interruptedStage) {
    this.interruptedStage = interruptedStage;
  }

  @Parameterized.Parameters(name = "{0}")
  public static Collection<Object[]> stages() {
    return Arrays.stream(STAGES).map(stage -> new Object[] {stage}).collect(Collectors.toList());
  }

  @BeforeClass
  public static void createFixture() throws Exception {
    fixture = Files.createTempDirectory("archive-resume-fixture").resolve("unified");
    generateFixture(fixture);
    try (UnifiedArchiveDb db = UnifiedArchiveDb.open(fixture, SCHEMA);
        UnifiedArchiveReadView view = observing(db.openValidationReadView(), FULL_COUNTS, null)) {
      validate(db, view);
    }
    for (String stage : STAGES) {
      assertTrue(stage, FULL_COUNTS.get(stage) > 1024);
    }
  }

  @AfterClass
  public static void deleteFixture() throws Exception {
    if (fixture != null) {
      try (Stream<Path> files = Files.walk(fixture.getParent())) {
        for (Path file : files.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
          Files.deleteIfExists(file);
        }
      }
    }
  }

  @Test
  public void resumesInsideEveryStageWithoutRepeatingTheValidatedPrefix() throws Exception {
    Path path = temporaryFolder.getRoot().toPath().resolve("unified");
    copyFixture(fixture, path);
    Map<String, Integer> before = new LinkedHashMap<>();
    try (UnifiedArchiveDb db = UnifiedArchiveDb.open(path, SCHEMA);
        UnifiedArchiveReadView view = observing(
            db.openResumableValidationReadView(), before, interruptedStage)) {
      SimulatedProcessExit failure = assertThrows(SimulatedProcessExit.class,
          () -> validate(db, view));
      assertEquals(interruptedStage, failure.getMessage());
    }
    Path progress = path.resolve(ArchiveValidationCheckpoint.FILE_NAME);
    assertTrue(Files.isRegularFile(progress));
    Map<String, Integer> after = new LinkedHashMap<>();
    try (UnifiedArchiveDb db = UnifiedArchiveDb.open(path, SCHEMA);
        UnifiedArchiveReadView view = observing(
            db.openResumableValidationReadView(), after, null)) {
      validate(db, view);
      assertTrue(UnifiedArchiveTxNumIndex.hasRepairRequired(db));
    }
    boolean passedBoundary = false;
    for (String stage : STAGES) {
      if (stage.equals(interruptedStage)) {
        passedBoundary = true;
        assertEquals(stage, (int) FULL_COUNTS.get(stage) - 1023, (int) after.get(stage));
      } else if (passedBoundary) {
        assertEquals(stage, FULL_COUNTS.get(stage), after.get(stage));
      } else {
        assertFalse(stage, after.containsKey(stage));
      }
    }
    assertFalse(Files.exists(progress));
  }

  static void validate(UnifiedArchiveDb db, UnifiedArchiveReadView view) {
    UnifiedArchiveTxNumIndex index = new UnifiedArchiveTxNumIndex(db, SCHEMA, false, true);
    UnifiedArchiveTemporalStore temporal = new UnifiedArchiveTemporalStore(db, CATALOG);
    try (UnifiedArchiveTxNumIndex.ReadScope ignored = index.bindReadView(view)) {
      index.validateStartup(true, true);
      temporal.validateStartupTail(view, index.getLastRange());
      temporal.validateCommittedBlocks(view, index.getFirstArchivedBlock(),
          index.getLastArchivedBlock(), block -> index.getBlockRange(block).orElse(null));
      temporal.validateTxNumsCovered(view, tx -> index.getPosition(tx).isPresent());
      temporal.validateDomainRows(view);
      view.finishValidation();
    }
  }

  static UnifiedArchiveReadView observing(UnifiedArchiveReadView real,
      Map<String, Integer> counts, String stopAtStage) {
    UnifiedArchiveReadView view = spy(real);
    doAnswer(invocation -> {
      String stage = invocation.getArgument(0);
      ArchiveStartupProgress progress = spy((ArchiveStartupProgress) invocation.callRealMethod());
      doAnswer(record -> {
        Object result = record.callRealMethod();
        int count = counts.merge(stage, 1, Integer::sum);
        if (stage.equals(stopAtStage) && count == 1024) {
          throw new SimulatedProcessExit(stage);
        }
        return result;
      }).when(progress).record(anyLong(), any(UnifiedArchiveIterator.class));
      return progress;
    }).when(view).startupProgress(anyString());
    return view;
  }

  static void generateFixture(Path path) {
    try (UnifiedArchiveDb db = UnifiedArchiveDb.initialize(path, SCHEMA)) {
      UnifiedArchiveTxNumIndex index = new UnifiedArchiveTxNumIndex(db, SCHEMA, false, true);
      UnifiedArchiveTemporalStore temporal = new UnifiedArchiveTemporalStore(db, CATALOG);
      UnifiedArchiveInFlightStore journals = new UnifiedArchiveInFlightStore(db, CATALOG);
      UnifiedArchiveBackend backend = new UnifiedArchiveBackend(db, index, temporal);
      DomainValue[] previous = new DomainValue[1101];
      Arrays.fill(previous, DomainValue.tombstone());
      for (int number = 0; number < 1120; number++) {
        long first = number * 2L;
        ArchiveBlockRange range = new ArchiveBlockRange(number, first, first + 1L,
            first, first + 1L, hash(number), 0, ArchiveSource.NORMAL, SCHEMA);
        ArchiveTxPosition prepare = new ArchiveTxPosition(first, number,
            ArchivePhase.BLOCK_PREPARE, ArchiveSource.NORMAL, -1, null);
        ArchiveTxPosition finalize = new ArchiveTxPosition(first + 1L, number,
            ArchivePhase.BLOCK_FINALIZE, ArchiveSource.NORMAL, -1, null);
        int coldKey = number % 1100 + 1;
        DomainValue value = DomainValue.present(Account.newBuilder().setBalance(number + 1L)
            .build().toByteArray());
        List<ArchiveChangeRecord> records = Arrays.asList(
            new ArchiveChangeRecord(prepare, ArchiveDomain.ACCOUNT, key(0), previous[0], value),
            new ArchiveChangeRecord(finalize, ArchiveDomain.ACCOUNT,
                key(coldKey), previous[coldKey], value));
        previous[0] = value;
        previous[coldKey] = value;
        ArchiveInFlightBlock block = new ArchiveInFlightBlock(
            range, Arrays.asList(prepare, finalize), records);
        journals.putBlock(block);
        journals.acknowledgeBlock(block.getJournalToken());
        backend.publishBlock(block.withJournalState(
            ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED));
        journals.onBlockPublished(number);
      }
      index.markRepairRequired("resumable validation test fixture");
    }
  }

  static void copyFixture(Path sourceDirectory, Path target) throws Exception {
    try (Stream<Path> files = Files.walk(sourceDirectory)) {
      for (Path source : files.collect(Collectors.toList())) {
        Path destination = target.resolve(sourceDirectory.relativize(source));
        if (Files.isDirectory(source)) {
          Files.createDirectories(destination);
        } else {
          Files.copy(source, destination);
        }
      }
    }
  }

  private static byte[] key(int value) {
    byte[] key = new byte[21];
    key[0] = 0x41;
    ByteBuffer.wrap(key, key.length - Integer.BYTES, Integer.BYTES).putInt(value);
    return key;
  }

  private static byte[] hash(long number) {
    byte[] hash = new byte[32];
    ByteBuffer.wrap(hash, hash.length - Long.BYTES, Long.BYTES).putLong(number);
    return hash;
  }

  static final class SimulatedProcessExit extends RuntimeException {
    private SimulatedProcessExit(String stage) {
      super(stage);
    }
  }
}
