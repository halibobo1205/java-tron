package org.tron.core.archive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/**
 * Resolves archive directories without creating them and rejects overlap with other database
 * paths.
 */
public final class ArchivePathResolver {

  private ArchivePathResolver() {
  }

  /**
   * Resolves a configured archive path to an absolute canonical path.
   *
   * <p>Absolute paths are used directly. Relative paths are resolved against {@code relativeBase}
   * and must remain below that base after both lexical normalization and symlink resolution.
   * Existing paths use {@link Path#toRealPath(LinkOption...) toRealPath}; paths that do not yet
   * exist use the real path of their nearest existing ancestor plus the unresolved suffix.
   *
   * @param configuredPath configured absolute or relative archive path
   * @param relativeBase base used only when {@code configuredPath} is relative
   * @return an absolute, normalized path with every existing prefix canonicalized
   * @throws IOException if an existing path component cannot be inspected or canonicalized
   * @throws IllegalArgumentException if a relative path escapes {@code relativeBase}
   */
  public static Path resolve(Path configuredPath, Path relativeBase) throws IOException {
    Objects.requireNonNull(configuredPath, "configuredPath");
    if (configuredPath.isAbsolute()) {
      return canonicalize(configuredPath);
    }

    Objects.requireNonNull(relativeBase, "relativeBase");
    Path normalizedBase = relativeBase.toAbsolutePath().normalize();
    Path resolved = normalizedBase.resolve(configuredPath).normalize();
    if (!resolved.startsWith(normalizedBase)) {
      throw new IllegalArgumentException(
          "relative archive path escapes archive base: " + configuredPath);
    }

    Path canonicalBase = canonicalize(normalizedBase);
    Path canonicalResolved = canonicalize(resolved);
    if (!canonicalResolved.startsWith(canonicalBase)) {
      throw new IllegalArgumentException(
          "relative archive path resolves outside archive base: " + configuredPath);
    }
    return canonicalResolved;
  }

  /**
   * Resolves an archive path and validates it against canonical, legacy, and migration paths.
   */
  public static Path resolveAndValidate(Path configuredPath, Path relativeBase,
      Iterable<? extends Path> protectedPaths) throws IOException {
    Path archivePath = resolve(configuredPath, relativeBase);
    validateNoConflicts(archivePath, protectedPaths);
    return archivePath;
  }

  /**
   * Rejects archive paths that are the same as, an ancestor of, or a descendant of a protected
   * path. All paths are canonicalized before comparison, including paths that do not yet exist.
   *
   * @param archivePath resolved or unresolved archive path
   * @param protectedPaths canonical database, legacy database, and migration source/target paths
   * @throws IOException if an existing path component cannot be inspected or canonicalized
   * @throws IllegalArgumentException if any path overlaps the archive path
   */
  public static void validateNoConflicts(Path archivePath,
      Iterable<? extends Path> protectedPaths) throws IOException {
    Objects.requireNonNull(archivePath, "archivePath");
    Objects.requireNonNull(protectedPaths, "protectedPaths");

    Path canonicalArchive = canonicalize(archivePath);
    for (Path protectedPath : protectedPaths) {
      Objects.requireNonNull(protectedPath, "protectedPath");
      Path canonicalProtected = canonicalize(protectedPath);
      if (overlaps(canonicalArchive, canonicalProtected)) {
        throw new IllegalArgumentException("archive path conflicts with protected path: "
            + canonicalArchive + " <-> " + canonicalProtected);
      }
    }
  }

  private static boolean overlaps(Path left, Path right) {
    return left.equals(right) || left.startsWith(right) || right.startsWith(left);
  }

  private static Path canonicalize(Path path) throws IOException {
    Path absolutePath = path.toAbsolutePath();
    Path existingPath = absolutePath;
    while (existingPath != null) {
      try {
        Files.readAttributes(existingPath, BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS);
      } catch (NoSuchFileException e) {
        existingPath = existingPath.getParent();
        continue;
      }
      Path unresolvedSuffix = existingPath.relativize(absolutePath);
      return existingPath.toRealPath().resolve(unresolvedSuffix).normalize();
    }
    throw new IOException("archive path has no existing ancestor: " + absolutePath);
  }
}
