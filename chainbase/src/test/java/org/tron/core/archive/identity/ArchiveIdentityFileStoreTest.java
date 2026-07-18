package org.tron.core.archive.identity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ArchiveIdentityFileStoreTest {

  @Rule
  public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void exclusiveOperationStartsAfterLockFileIsDurable() throws Exception {
    Path directory = temporaryFolder.newFolder("durable-lock").toPath();
    Path lockPath = directory.resolve("archive.lock");
    AtomicBoolean durable = new AtomicBoolean();
    ArchiveIdentityFileStore fileStore = new ArchiveIdentityFileStore((channel, path) -> {
      assertEquals(lockPath, path);
      assertTrue(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS));
      ArchiveIdentityFileStore.forceLockFile(channel, path);
      durable.set(true);
    });

    String result = fileStore.withExclusiveFileLock(lockPath, () -> {
      assertTrue(durable.get());
      return "complete";
    });

    assertEquals("complete", result);
  }

  @Test
  public void lockDurabilityForcesTheLockFileChannel() throws Exception {
    Path directory = temporaryFolder.newFolder("force-lock-channel").toPath();
    FileChannel channel = mock(FileChannel.class);

    ArchiveIdentityFileStore.forceLockFile(channel, directory.resolve("archive.lock"));

    verify(channel).force(true);
  }
}
