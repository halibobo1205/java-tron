package org.tron.core.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.tron.core.archive.domain.ArchiveSchemaChecksum;
import org.tron.core.archive.domain.DefaultArchiveDomainCatalog;
import org.tron.core.archive.domain.DefaultArchiveDomainRegistry;
import org.tron.core.archive.temporal.UnifiedArchiveTemporalStore;
import org.tron.core.archive.txnum.UnifiedArchiveTxNumIndex;
import org.tron.core.archive.unified.UnifiedArchiveColumnFamily;
import org.tron.core.archive.unified.UnifiedArchiveDb;
import org.tron.core.archive.unified.UnifiedArchiveIterator;
import org.tron.core.archive.unified.UnifiedArchiveMaintenanceBatch;
import org.tron.core.archive.unified.UnifiedArchiveReadView;
import org.tron.core.archive.unified.UnifiedArchiveTestMaintenance;

public class ArchiveValidationRestartTest {

  private static final DefaultArchiveDomainCatalog CATALOG = new DefaultArchiveDomainCatalog();
  private static final byte[] SCHEMA = ArchiveSchemaChecksum.of(
      new DefaultArchiveDomainRegistry(), CATALOG);

  @Rule
  public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void forceKilledProcessesResumeWithoutCleanDatabaseClose() throws Exception {
    Path fixture = temporaryFolder.getRoot().toPath().resolve("fixture");
    ArchiveValidationResumeTest.generateFixture(fixture);
    for (String stage : Arrays.asList("temporal-blocks", "history-links", "payload-owners")) {
      Path path = temporaryFolder.getRoot().toPath().resolve(stage);
      ArchiveValidationResumeTest.copyFixture(fixture, path);
      Path ready = path.resolveSibling(stage + ".ready");
      File output = path.resolveSibling(stage + ".log").toFile();
      Process child = new ProcessBuilder(
          Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
          "-Xmx512m", "-cp", childClasspath(), ArchiveValidationCrashProbe.class.getName(),
          path.toString(), stage, ready.toString())
          .redirectErrorStream(true).redirectOutput(output).start();
      try {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (!Files.exists(ready) && child.isAlive() && System.nanoTime() < deadline) {
          Thread.sleep(25L);
        }
        assertTrue("child did not reach checkpoint: " + output, Files.exists(ready));
        child.destroyForcibly();
        assertTrue("child did not exit", child.waitFor(30, TimeUnit.SECONDS));
        assertTrue(Files.isRegularFile(path.resolve(ArchiveValidationCheckpoint.FILE_NAME)));
        Map<String, Integer> counts = new LinkedHashMap<>();
        try (UnifiedArchiveDb db = UnifiedArchiveDb.open(path, SCHEMA);
            UnifiedArchiveReadView view = ArchiveValidationResumeTest.observing(
                db.openResumableValidationReadView(), counts, null)) {
          assertTrue(view.startupProgress("index-keyspace").isComplete());
          ArchiveValidationResumeTest.validate(db, view);
          assertTrue(UnifiedArchiveTxNumIndex.hasRepairRequired(db));
        }
        assertFalse(counts.containsKey("index-keyspace"));
        if (stage.equals("temporal-blocks")) {
          assertEquals(97, (int) counts.get(stage));
        }
        assertFalse(Files.exists(path.resolve(ArchiveValidationCheckpoint.FILE_NAME)));
      } finally {
        if (child.isAlive()) {
          child.destroyForcibly();
          assertTrue("child cleanup failed", child.waitFor(30, TimeUnit.SECONDS));
        }
      }
    }
  }

  @Test
  public void mutationInValidatedPrefixInvalidatesProgressAndFailsClosed() throws Exception {
    Path path = temporaryFolder.getRoot().toPath().resolve("unified");
    ArchiveValidationResumeTest.generateFixture(path);
    try (UnifiedArchiveDb db = UnifiedArchiveDb.open(path, SCHEMA);
        UnifiedArchiveReadView view = ArchiveValidationResumeTest.observing(
            db.openResumableValidationReadView(), new LinkedHashMap<>(), "history-links")) {
      ArchiveValidationResumeTest.SimulatedProcessExit failure = assertThrows(
          ArchiveValidationResumeTest.SimulatedProcessExit.class,
          () -> ArchiveValidationResumeTest.validate(db, view));
      assertEquals("history-links", failure.getMessage());
    }
    try (UnifiedArchiveDb db = UnifiedArchiveDb.open(path, SCHEMA)) {
      byte[] firstKey;
      try (UnifiedArchiveReadView view = db.openReadView()) {
        UnifiedArchiveIterator changes = view.newIterator(UnifiedArchiveColumnFamily.CHANGESET);
        changes.seekToFirst();
        firstKey = changes.key();
      }
      UnifiedArchiveTestMaintenance.write(db, new UnifiedArchiveMaintenanceBatch()
          .delete(UnifiedArchiveColumnFamily.CHANGESET, firstKey));
    }
    try (UnifiedArchiveDb db = UnifiedArchiveDb.open(path, SCHEMA)) {
      try (UnifiedArchiveReadView view = db.openResumableValidationReadView()) {
        assertFalse(view.startupProgress("index-keyspace").isComplete());
        assertNull(view.startupProgress("index-keyspace").resumeKey());
      }
      UnifiedArchiveTxNumIndex index = new UnifiedArchiveTxNumIndex(db, SCHEMA, false, true);
      UnifiedArchiveBackend backend = new UnifiedArchiveBackend(
          db, index, new UnifiedArchiveTemporalStore(db, CATALOG));
      ArchiveException failure = assertThrows(ArchiveException.class,
          () -> backend.validatePostReconcileStartup(true, true));
      assertTrue(failure.getMessage(), failure.getMessage().contains("block 0"));
      assertTrue(UnifiedArchiveTxNumIndex.hasRepairRequired(db));
      assertFalse(Files.exists(path.resolve(ArchiveValidationCheckpoint.FILE_NAME)));
    }
  }

  @Test
  public void independentFreshValidationFailureAlsoDiscardsOldProgress() {
    Path path = temporaryFolder.getRoot().toPath().resolve("fresh-failure");
    try (UnifiedArchiveDb db = UnifiedArchiveDb.initialize(path, SCHEMA)) {
      UnifiedArchiveTxNumIndex index = new UnifiedArchiveTxNumIndex(db, SCHEMA, false, true);
      index.markRepairRequired("fresh scan failure test");
      try (UnifiedArchiveReadView view = db.openResumableValidationReadView()) {
        view.startupProgress("index-keyspace").complete();
      }
      assertTrue(Files.exists(path.resolve(ArchiveValidationCheckpoint.FILE_NAME)));
      UnifiedArchiveTemporalStore temporal = spy(new UnifiedArchiveTemporalStore(db, CATALOG));
      doThrow(new ArchiveException("injected fresh scan failure")).when(temporal)
          .validateCommittedBlocks(any(), anyLong(), anyLong(), any());
      UnifiedArchiveBackend backend = new UnifiedArchiveBackend(db, index, temporal);
      ArchiveException failure = assertThrows(ArchiveException.class,
          () -> backend.validateStartup(true, true));
      assertEquals("injected fresh scan failure", failure.getMessage());
      assertFalse(Files.exists(path.resolve(ArchiveValidationCheckpoint.FILE_NAME)));
      assertTrue(UnifiedArchiveTxNumIndex.hasRepairRequired(db));
    }
  }

  @Test
  public void emptyArchiveCompletesAllStagesWithoutRetainingProgress() {
    Path path = temporaryFolder.getRoot().toPath().resolve("empty");
    try (UnifiedArchiveDb db = UnifiedArchiveDb.initialize(path, SCHEMA)) {
      UnifiedArchiveTxNumIndex index = new UnifiedArchiveTxNumIndex(db, SCHEMA, false, true);
      UnifiedArchiveBackend backend = new UnifiedArchiveBackend(
          db, index, new UnifiedArchiveTemporalStore(db, CATALOG));
      backend.validatePostReconcileStartup(true, true);
      assertFalse(Files.exists(path.resolve(ArchiveValidationCheckpoint.FILE_NAME)));
    }
  }

  private static String childClasspath() throws Exception {
    Set<String> entries = new LinkedHashSet<>(Arrays.asList(
        System.getProperty("java.class.path").split(File.pathSeparator)));
    for (ClassLoader loader = ArchiveValidationRestartTest.class.getClassLoader();
        loader != null; loader = loader.getParent()) {
      if (loader instanceof URLClassLoader) {
        for (URL url : ((URLClassLoader) loader).getURLs()) {
          entries.add(Paths.get(url.toURI()).toString());
        }
      }
    }
    return String.join(File.pathSeparator, entries);
  }
}
