package org.tron.core.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ArchivePathResolverTest {

  @Rule
  public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void absolutePathIgnoresBaseAndUsesRealPath() throws Exception {
    Path root = temporaryFolder.getRoot().toPath();
    Path base = Files.createDirectory(root.resolve("base"));
    Path absolute = Files.createDirectory(root.resolve("absolute"));

    Path resolved = ArchivePathResolver.resolve(absolute, base.resolve("unused"));

    assertEquals(absolute.toRealPath(), resolved);
  }

  @Test
  public void absolutePathDoesNotRequireBase() throws Exception {
    Path absolute = Files.createDirectory(
        temporaryFolder.getRoot().toPath().resolve("absolute-no-base"));

    assertEquals(absolute.toRealPath(), ArchivePathResolver.resolve(absolute, null));
  }

  @Test
  public void relativePathResolvesAndNormalizesUnderBase() throws Exception {
    Path base = Files.createDirectory(temporaryFolder.getRoot().toPath().resolve("base"));
    Path requested = Paths.get("intermediate", "..", "archive");

    Path resolved = ArchivePathResolver.resolve(requested, base);

    assertEquals(base.toRealPath().resolve("archive"), resolved);
    assertFalse(Files.exists(base.resolve("archive")));
  }

  @Test
  public void pendingPathUsesNearestExistingAncestorWithoutCreatingDirectories()
      throws Exception {
    Path anchor = Files.createDirectory(temporaryFolder.getRoot().toPath().resolve("anchor"));
    Path requested = anchor.resolve("new").resolve("nested").resolve("archive");

    Path resolved = ArchivePathResolver.resolve(requested, null);

    assertEquals(anchor.toRealPath().resolve("new/nested/archive"), resolved);
    assertFalse(Files.exists(anchor.resolve("new")));
  }

  @Test
  public void relativeDotSegmentsCannotEscapeBase() throws Exception {
    Path root = temporaryFolder.getRoot().toPath();
    Path base = Files.createDirectory(root.resolve("base"));
    Path escaped = root.resolve("escaped");

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> ArchivePathResolver.resolve(Paths.get("..", "escaped"), base));

    assertTrue(error.getMessage().contains("escapes archive base"));
    assertFalse(Files.exists(escaped));
  }

  @Test
  public void sameParentAndChildProtectedPathsAreRejected() throws Exception {
    Path root = temporaryFolder.getRoot().toPath();
    Path archive = root.resolve("archive");

    assertConflict(archive, archive);
    assertConflict(archive, archive.resolve("legacy"));
    assertConflict(archive, root);
    assertFalse(Files.exists(archive));
  }

  @Test
  public void conflictCheckExaminesCanonicalLegacyAndMigrationPathList() throws Exception {
    Path root = temporaryFolder.getRoot().toPath();
    Path archive = root.resolve("archive");
    Path canonical = root.resolve("canonical");
    Path legacy = root.resolve("legacy");
    Path migrationTarget = archive.resolve("migration-target");

    assertThrows(IllegalArgumentException.class,
        () -> ArchivePathResolver.validateNoConflicts(archive,
            Arrays.asList(canonical, legacy, migrationTarget)));
    assertFalse(Files.exists(archive));
    assertFalse(Files.exists(migrationTarget));
  }

  @Test
  public void distinctSiblingPathsAreAcceptedWithoutCreatingDirectories() throws Exception {
    Path root = temporaryFolder.getRoot().toPath();
    Path archive = root.resolve("archive");
    Path canonical = root.resolve("canonical");
    Path legacy = root.resolve("legacy");
    Path migration = root.resolve("migration");

    Path resolved = ArchivePathResolver.resolveAndValidate(archive, null,
        Arrays.asList(canonical, legacy, migration));

    assertEquals(root.toRealPath().resolve("archive"), resolved);
    assertFalse(Files.exists(archive));
    assertFalse(Files.exists(canonical));
    assertFalse(Files.exists(legacy));
    assertFalse(Files.exists(migration));
  }

  @Test
  public void existingSymlinkAliasIsRejectedWhenSymlinksAreSupported() throws Exception {
    Path root = temporaryFolder.getRoot().toPath();
    Path canonical = Files.createDirectory(root.resolve("canonical"));
    Path alias = createSymbolicLinkOrSkip(root.resolve("alias"), canonical);

    assertConflict(alias, canonical);
  }

  @Test
  public void pendingPathBelowSymlinkAliasIsRejectedWhenSymlinksAreSupported()
      throws Exception {
    Path root = temporaryFolder.getRoot().toPath();
    Path canonical = Files.createDirectory(root.resolve("canonical"));
    Path alias = createSymbolicLinkOrSkip(root.resolve("alias"), canonical);
    Path archiveViaAlias = alias.resolve("new").resolve("archive");
    Path archiveViaCanonical = canonical.resolve("new").resolve("archive");

    assertConflict(archiveViaAlias, archiveViaCanonical);
    assertFalse(Files.exists(canonical.resolve("new")));
  }

  @Test
  public void relativeSymlinkCannotEscapeBaseWhenSymlinksAreSupported() throws Exception {
    Path root = temporaryFolder.getRoot().toPath();
    Path base = Files.createDirectory(root.resolve("base"));
    Path outside = Files.createDirectory(root.resolve("outside"));
    createSymbolicLinkOrSkip(base.resolve("escape"), outside);

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> ArchivePathResolver.resolve(Paths.get("escape", "archive"), base));

    assertTrue(error.getMessage().contains("resolves outside archive base"));
    assertFalse(Files.exists(outside.resolve("archive")));
  }

  @Test
  public void danglingSymlinkFailsCanonicalizationWhenSymlinksAreSupported() throws Exception {
    Path root = temporaryFolder.getRoot().toPath();
    Path missingTarget = root.resolve("missing-target");
    Path dangling = createSymbolicLinkOrSkip(root.resolve("dangling"), missingTarget);

    assertThrows(IOException.class, () -> ArchivePathResolver.resolve(dangling, null));
  }

  private void assertConflict(Path archive, Path protectedPath) throws IOException {
    assertThrows(IllegalArgumentException.class,
        () -> ArchivePathResolver.validateNoConflicts(archive,
            Collections.singletonList(protectedPath)));
  }

  private Path createSymbolicLinkOrSkip(Path link, Path target) {
    try {
      return Files.createSymbolicLink(link, target);
    } catch (IOException | UnsupportedOperationException | SecurityException e) {
      Assume.assumeNoException("symbolic links are not supported", e);
      return link;
    }
  }
}
