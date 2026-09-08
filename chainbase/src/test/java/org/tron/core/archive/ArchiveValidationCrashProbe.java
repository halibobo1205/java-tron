package org.tron.core.archive;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.concurrent.CountDownLatch;
import org.tron.core.archive.domain.ArchiveSchemaChecksum;
import org.tron.core.archive.domain.DefaultArchiveDomainCatalog;
import org.tron.core.archive.domain.DefaultArchiveDomainRegistry;
import org.tron.core.archive.unified.UnifiedArchiveDb;
import org.tron.core.archive.unified.UnifiedArchiveReadView;

/** Child process killed by ArchiveValidationRestartTest after a durable scan checkpoint. */
public final class ArchiveValidationCrashProbe {

  private ArchiveValidationCrashProbe() {
  }

  public static void main(String[] args) throws Exception {
    Path path = Paths.get(args[0]);
    String stage = args[1];
    Path ready = Paths.get(args[2]);
    byte[] schema = ArchiveSchemaChecksum.of(
        new DefaultArchiveDomainRegistry(), new DefaultArchiveDomainCatalog());
    try (UnifiedArchiveDb db = UnifiedArchiveDb.open(path, schema);
        UnifiedArchiveReadView view = ArchiveValidationResumeTest.observing(
            db.openResumableValidationReadView(), new LinkedHashMap<>(), stage)) {
      try {
        ArchiveValidationResumeTest.validate(db, view);
        throw new AssertionError("scan did not reach interruption point: " + stage);
      } catch (ArchiveValidationResumeTest.SimulatedProcessExit expected) {
        Files.write(ready, new byte[] {1});
        new CountDownLatch(1).await();
      }
    }
  }
}
