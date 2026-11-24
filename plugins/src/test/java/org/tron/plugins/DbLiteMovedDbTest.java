package org.tron.plugins;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

public class DbLiteMovedDbTest {

  @Rule
  public final TemporaryFolder folder = new TemporaryFolder();

  @Test
  public void testDanglingMovedDbPropagates() throws IOException {
    Path database = folder.newFolder("database").toPath();
    Path link = database.resolve("block");
    Files.createSymbolicLink(link, database.resolveSibling("missing"));

    UncheckedIOException error = assertThrows(UncheckedIOException.class,
        () -> DbLite.getMovedArchiveDbs(database.toString()));
    assertTrue(error.getCause() instanceof NoSuchFileException);
    assertEquals(link.toString(), ((NoSuchFileException) error.getCause()).getFile());
  }

  @Test
  public void testClearFailurePropagates() throws IOException {
    Path moved = folder.newFolder("trans").toPath();
    Path current = Files.write(moved.resolve("CURRENT"), new byte[] {1});
    IOException failure = new IOException("Read-only file system");

    try (MockedStatic<Files> files = Mockito.mockStatic(Files.class, Mockito.CALLS_REAL_METHODS)) {
      files.when(() -> Files.delete(ArgumentMatchers.eq(current))).thenThrow(failure);
      UncheckedIOException error = assertThrows(UncheckedIOException.class,
          () -> DbLite.clearDir(moved));
      assertSame(failure, error.getCause());
    }
    assertTrue(Files.exists(current));
  }

  @Test
  public void testCopyFailurePropagates() throws IOException {
    Path history = folder.newFolder("history-trans").toPath();
    Files.createDirectories(history.resolve("nested"));
    Path moved = folder.newFolder("trans").toPath();
    IOException failure = new IOException("No space left on device");

    try (MockedStatic<Files> files = Mockito.mockStatic(Files.class, Mockito.CALLS_REAL_METHODS)) {
      files.when(() -> Files.createDirectories(ArgumentMatchers.eq(moved.resolve("nested"))))
          .thenThrow(failure);
      UncheckedIOException error = assertThrows(UncheckedIOException.class,
          () -> DbLite.copyInto(history, moved));
      assertSame(failure, error.getCause());
    }
  }
}
