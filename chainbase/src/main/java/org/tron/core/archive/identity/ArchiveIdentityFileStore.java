package org.tron.core.archive.identity;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Atomic and durable file operations used by the identity protocol. */
final class ArchiveIdentityFileStore {

  private static final int MAX_IDENTITY_BYTES = 64 * 1024;
  private static final ConcurrentMap<Path, ReentrantReadWriteLock> JVM_LOCKS =
      new ConcurrentHashMap<>();

  private final ArchiveIdentityCodec codec = new ArchiveIdentityCodec();
  private final LockDurability lockDurability;

  ArchiveIdentityFileStore() {
    this(ArchiveIdentityFileStore::forceLockFile);
  }

  ArchiveIdentityFileStore(LockDurability lockDurability) {
    if (lockDurability == null) {
      throw new NullPointerException("lockDurability");
    }
    this.lockDurability = lockDurability;
  }

  <T> T withExclusiveFileLock(Path lockPath, LockedOperation<T> operation)
      throws IOException {
    requireDirectory(requireParent(lockPath));
    ReentrantReadWriteLock processLock = processLock(lockPath);
    Lock localLock = processLock.writeLock();
    localLock.lock();
    try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
         FileLock ignored = channel.lock()) {
      requireRegularLockFile(lockPath);
      lockDurability.force(channel, lockPath);
      return operation.run();
    } finally {
      localLock.unlock();
    }
  }

  <T> T withSharedFileLock(Path lockPath, LockedOperation<T> operation)
      throws IOException {
    requireDirectory(requireParent(lockPath));
    requireRegularLockFile(lockPath);
    ReentrantReadWriteLock processLock = processLock(lockPath);
    // The JVM rejects overlapping FileLock regions even when both locks are shared, so local
    // readers serialize while separate processes may still share the OS lock.
    Lock localLock = processLock.writeLock();
    localLock.lock();
    try (FileChannel channel = FileChannel.open(
        lockPath, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
         FileLock ignored = channel.lock(0L, Long.MAX_VALUE, true)) {
      requireRegularLockFile(lockPath);
      return operation.run();
    } finally {
      localLock.unlock();
    }
  }

  Optional<ArchiveIdentity> read(Path target) throws IOException {
    final BasicFileAttributes attributes;
    try {
      attributes = Files.readAttributes(
          target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    } catch (NoSuchFileException e) {
      return Optional.empty();
    }
    if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
      throw new ArchiveIdentityException("archive identity is not a regular file: " + target);
    }
    if (attributes.size() <= 0 || attributes.size() > MAX_IDENTITY_BYTES) {
      throw new ArchiveIdentityException("archive identity has an invalid size: " + target);
    }
    byte[] bytes = readBounded(target);
    return Optional.of(codec.decode(bytes, target));
  }

  ArchiveIdentity readRequired(Path target, String role) throws IOException {
    Optional<ArchiveIdentity> identity = read(target);
    if (!identity.isPresent()) {
      throw new ArchiveIdentityException("missing " + role + " archive identity: " + target);
    }
    return identity.get();
  }

  void write(Path target, ArchiveIdentity identity) throws IOException {
    Path parent = requireParent(target);
    requireDirectory(parent);
    cleanupTemporaryFiles(target, identity.getUuid());

    byte[] bytes = codec.encode(identity);
    Path temporary = Files.createTempFile(parent, temporaryPrefix(target, identity.getUuid()), "");
    boolean moved = false;
    IOException failure = null;
    try {
      try (FileChannel channel = FileChannel.open(
          temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
        channel.force(true);
      }
      try {
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        throw new IOException("atomic archive identity replacement is not supported for " + target,
            e);
      }
      moved = true;
      forceDirectory(parent);
    } catch (IOException e) {
      failure = e;
      throw e;
    } finally {
      if (!moved) {
        try {
          if (Files.deleteIfExists(temporary)) {
            forceDirectory(parent);
          }
        } catch (IOException cleanupFailure) {
          if (failure != null) {
            failure.addSuppressed(cleanupFailure);
          } else {
            throw cleanupFailure;
          }
        }
      }
    }
  }

  void delete(Path target, UUID owner) throws IOException {
    Path parent = requireParent(target);
    try {
      requireDirectory(parent);
    } catch (NoSuchFileException e) {
      return;
    }
    boolean changed = deleteTemporaryFiles(target, owner);
    changed |= Files.deleteIfExists(target);
    if (changed) {
      forceDirectory(parent);
    }
  }

  void cleanupTemporaryFiles(Path target, UUID owner) throws IOException {
    Path parent = requireParent(target);
    if (deleteTemporaryFiles(target, owner)) {
      forceDirectory(parent);
    }
  }

  boolean isOwnedTemporaryFile(Path target, UUID owner, Path candidate) {
    Path name = candidate.getFileName();
    return name != null && name.toString().startsWith(temporaryPrefix(target, owner));
  }

  boolean ensureDirectory(Path directory) throws IOException {
    try {
      requireDirectory(directory);
      return false;
    } catch (NoSuchFileException e) {
      Path parent = requireParent(directory);
      requireDirectory(parent);
      try {
        Files.createDirectory(directory);
      } catch (FileAlreadyExistsException raced) {
        requireDirectory(directory);
        return false;
      }
      forceDirectory(directory);
      forceDirectory(parent);
      return true;
    }
  }

  void requireDirectory(Path directory) throws IOException {
    BasicFileAttributes attributes = Files.readAttributes(
        directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
      throw new ArchiveIdentityException(
          "archive identity path is not a real directory: " + directory);
    }
  }

  private static ReentrantReadWriteLock processLock(Path lockPath) {
    Path parent = lockPath.toAbsolutePath().normalize().getParent();
    Path normalized = lockPath.toAbsolutePath().normalize();
    if (parent != null) {
      try {
        normalized = parent.toRealPath().resolve(lockPath.getFileName());
      } catch (IOException ignored) {
        // The subsequent directory/lock-file validation produces the actionable error.
      }
    }
    return JVM_LOCKS.computeIfAbsent(normalized,
        ignored -> new ReentrantReadWriteLock(true));
  }

  private static void requireRegularLockFile(Path lockPath) throws IOException {
    BasicFileAttributes attributes = Files.readAttributes(
        lockPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
      throw new ArchiveIdentityException(
          "archive identity lock is not a regular file: " + lockPath);
    }
  }

  private boolean deleteTemporaryFiles(Path target, UUID owner) throws IOException {
    Path parent = requireParent(target);
    try {
      requireDirectory(parent);
    } catch (NoSuchFileException e) {
      return false;
    }
    boolean changed = false;
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent)) {
      for (Path candidate : stream) {
        if (isOwnedTemporaryFile(target, owner, candidate)) {
          changed |= Files.deleteIfExists(candidate);
        }
      }
    }
    return changed;
  }

  private static String temporaryPrefix(Path target, UUID owner) {
    return "." + target.getFileName() + "." + owner + ".tmp-";
  }

  private static Path requireParent(Path path) throws ArchiveIdentityException {
    Path parent = path.getParent();
    if (parent == null) {
      throw new ArchiveIdentityException("archive identity path has no parent: " + path);
    }
    return parent;
  }

  private static byte[] readBounded(Path target) throws IOException {
    try (FileChannel channel = FileChannel.open(
        target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      long size = channel.size();
      if (size <= 0 || size > MAX_IDENTITY_BYTES) {
        throw new ArchiveIdentityException(
            "archive identity changed to an invalid size: " + target);
      }
      byte[] bytes = new byte[(int) size];
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) {
        if (channel.read(buffer) < 0) {
          throw new ArchiveIdentityException(
              "archive identity was truncated while reading: " + target);
        }
      }
      if (channel.read(ByteBuffer.allocate(1)) >= 0) {
        throw new ArchiveIdentityException(
            "archive identity grew while reading: " + target);
      }
      return bytes;
    }
  }

  static void forceDirectory(Path directory) throws IOException {
    try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
      channel.force(true);
    } catch (UnsupportedOperationException e) {
      throw new IOException("directory fsync is unsupported for archive identity path " + directory,
          e);
    } catch (IOException e) {
      throw new IOException("could not fsync archive identity directory " + directory, e);
    }
  }

  static void forceLockFile(FileChannel channel, Path lockPath) throws IOException {
    channel.force(true);
    forceDirectory(requireParent(lockPath));
  }

  @FunctionalInterface
  interface LockDurability {

    void force(FileChannel channel, Path lockPath) throws IOException;
  }

  @FunctionalInterface
  interface LockedOperation<T> {
    T run() throws IOException;
  }
}
