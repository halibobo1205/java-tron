package org.tron.core.archive;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;

/** Optional durable scan progress, never evidence that permits clearing repair-required. */
@Slf4j(topic = "archive")
public final class ArchiveValidationCheckpoint {

  static final String FILE_NAME = "archive-validation-progress";
  private static final String TEMP_NAME = FILE_NAME + ".tmp";
  private static final int MAGIC = 0x41565301;
  private static final int MAX_KEY_BYTES = 1024;
  private static final int HEADER_BYTES = Integer.BYTES + 32 + Integer.BYTES
      + Long.BYTES * 2 + Integer.BYTES;
  private static final int MAX_FILE_BYTES = HEADER_BYTES + MAX_KEY_BYTES + 32;
  private static final String[] STAGES = {
      "index-keyspace", "ranges-and-positions", "temporal-blocks", "history-links",
      "changeset-links", "latest-links", "anchors", "latest-domains", "history-domains",
      "changeset-domains", "payload-owners"
  };
  // Hash whole defining artifacts, including codecs, helpers and generated message parsers.
  private static final String[] VALIDATORS = {
      "org.tron.core.archive.ArchiveValidationCheckpoint",
      "org.tron.core.archive.UnifiedArchiveBackend",
      "org.tron.core.archive.unified.UnifiedArchiveDb",
      "org.tron.core.archive.unified.UnifiedArchiveReadView",
      "org.tron.core.archive.txnum.UnifiedArchiveTxNumIndex",
      "org.tron.core.archive.temporal.UnifiedArchiveTemporalStore",
      "org.tron.common.utils.ByteArray",
      "org.tron.common.utils.Sha256Hash",
      "org.tron.protos.Protocol$Account",
      "com.google.protobuf.Message",
      "org.rocksdb.RocksDB"
  };

  private final Path directory;
  private final byte[] binding;
  private final long sequence;
  private final LongSupplier currentSequence;
  private int stage;
  private long rows;
  private long position = -1L;
  private byte[] key;
  private boolean writable = true;
  private long reportedNanos = System.nanoTime();

  private ArchiveValidationCheckpoint(Path directory, byte[] binding,
      long sequence, LongSupplier currentSequence) {
    this.directory = directory;
    this.binding = binding;
    this.sequence = sequence;
    this.currentSequence = currentSequence;
  }

  public static ArchiveValidationCheckpoint open(Path directory, byte[] schema,
      long sequence, LongSupplier currentSequence) {
    try {
      MessageDigest digest = newDigest();
      digest.update(schema);
      digest.update(ByteBuffer.allocate(Long.BYTES).putLong(sequence).array());
      digest.update(directory.toRealPath().toString().getBytes(StandardCharsets.UTF_8));
      digest.update(readBounded(directory.resolve("IDENTITY"), 256));
      digest.update(System.getProperty("java.runtime.version", "unknown")
          .getBytes(StandardCharsets.UTF_8));
      Set<Path> visited = new HashSet<>();
      for (String validator : VALIDATORS) {
        fingerprint(Class.forName(validator, false,
            ArchiveValidationCheckpoint.class.getClassLoader()), digest, visited);
      }
      ArchiveValidationCheckpoint checkpoint = new ArchiveValidationCheckpoint(
          directory, digest.digest(), sequence, currentSequence);
      checkpoint.load();
      checkpoint.requireUnchanged();
      return checkpoint;
    } catch (IOException | ReflectiveOperationException | URISyntaxException e) {
      logger.warn("Archive validation resume unavailable; scanning from the beginning", e);
      return null;
    }
  }

  public ArchiveStartupProgress progress(String name) {
    int ordinal = ordinal(name);
    if (ordinal > stage) {
      throw new ArchiveException("archive validation stage skipped before " + name);
    }
    return new ArchiveStartupProgress(name, this, ordinal < stage,
        ordinal == stage ? key : null, ordinal == stage ? rows : 0L,
        ordinal == stage ? position : -1L);
  }

  void save(String name, long count, long lastPosition, byte[] lastKey, boolean complete) {
    if (ordinal(name) != stage) {
      throw new ArchiveException("archive validation checkpoint stage mismatch: " + name);
    }
    requireUnchanged();
    if (complete) {
      stage++;
      rows = 0L;
      position = -1L;
      key = null;
    } else {
      if (count <= 0 || lastKey == null || lastKey.length == 0) {
        throw new ArchiveException("archive validation checkpoint cursor is invalid");
      }
      // A wide key can be valid data; the progress-file limit must not restrict the schema.
      if (lastKey.length > MAX_KEY_BYTES) {
        logger.debug("Skipping archive validation checkpoint for wide key: stage={}, bytes={}",
            name, lastKey.length);
        return;
      }
      rows = count;
      position = lastPosition;
      key = Arrays.copyOf(lastKey, lastKey.length);
    }
    if (writable) {
      try {
        persist();
        long now = System.nanoTime();
        if (complete || now - reportedNanos >= TimeUnit.SECONDS.toNanos(5L)) {
          logger.info("Archive validation checkpoint saved: stage={}, rows={}, position={}, "
              + "complete={}, sequence={}", name, count, lastPosition, complete, sequence);
          reportedNanos = now;
        }
      } catch (IOException e) {
        writable = false;
        logger.warn("Archive validation checkpoint write failed; continuing full validation", e);
      }
    }
  }

  /** Called only after every stage succeeded; no archive metadata is changed here. */
  public void finish() {
    requireUnchanged();
    if (stage != STAGES.length) {
      throw new ArchiveException("archive validation ended before all stages completed");
    }
    discard();
  }

  /** Failed validation must not leave evidence usable by a subsequent attempt. */
  public void discard() {
    discard(directory);
    writable = false;
  }

  public static void discard(Path directory) {
    try {
      Files.deleteIfExists(directory.resolve(FILE_NAME));
      forceDirectory(directory);
    } catch (IOException e) {
      logger.warn("Could not remove archive validation checkpoint", e);
    }
  }

  private void requireUnchanged() {
    if (currentSequence.getAsLong() != sequence) {
      discard();
      throw new ArchiveException("archive database changed during resumable validation");
    }
  }

  private void load() throws IOException {
    byte[] bytes;
    try {
      bytes = readBounded(directory.resolve(FILE_NAME), MAX_FILE_BYTES);
    } catch (NoSuchFileException e) {
      return;
    } catch (IOException e) {
      logger.warn("Ignoring unreadable archive validation checkpoint; restarting validation", e);
      return;
    }
    if (bytes.length < HEADER_BYTES + 32) {
      logger.warn("Ignoring truncated archive validation checkpoint");
      return;
    }
    int bodyLength = bytes.length - 32;
    MessageDigest digest = newDigest();
    digest.update(bytes, 0, bodyLength);
    ByteBuffer data = ByteBuffer.wrap(bytes, 0, bodyLength);
    byte[] persistedBinding = new byte[32];
    int magic = data.getInt();
    data.get(persistedBinding);
    int savedStage = data.getInt();
    long savedRows = data.getLong();
    long savedPosition = data.getLong();
    int keyLength = data.getInt();
    if (magic != MAGIC || !MessageDigest.isEqual(binding, persistedBinding)
        || !MessageDigest.isEqual(digest.digest(),
            Arrays.copyOfRange(bytes, bodyLength, bytes.length))
        || savedStage < 0 || savedStage > STAGES.length || savedRows < 0
        || savedPosition < -1L || keyLength < 0 || keyLength > MAX_KEY_BYTES
        || keyLength != data.remaining() || (savedRows == 0) != (keyLength == 0)
        || (savedStage == STAGES.length && keyLength != 0)) {
      logger.info("Archive validation checkpoint does not match data/rules or is corrupt; "
          + "restarting validation");
      return;
    }
    stage = savedStage;
    rows = savedRows;
    position = savedPosition;
    if (keyLength != 0) {
      key = new byte[keyLength];
      data.get(key);
    }
    logger.info("Archive validation checkpoint loaded: stage={}, rows={}, position={}, sequence={}",
        stage < STAGES.length ? STAGES[stage] : "complete", rows, position, sequence);
  }

  private void persist() throws IOException {
    int keyLength = key == null ? 0 : key.length;
    ByteBuffer body = ByteBuffer.allocate(HEADER_BYTES + keyLength);
    body.putInt(MAGIC).put(binding).putInt(stage).putLong(rows).putLong(position)
        .putInt(keyLength);
    if (key != null) {
      body.put(key);
    }
    Path temporary = directory.resolve(TEMP_NAME);
    try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
        LinkOption.NOFOLLOW_LINKS)) {
      writeFully(channel, ByteBuffer.wrap(body.array()));
      writeFully(channel, ByteBuffer.wrap(newDigest().digest(body.array())));
      channel.force(true);
    }
    Files.move(temporary, directory.resolve(FILE_NAME),
        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    forceDirectory(directory);
  }

  private static void forceDirectory(Path directory) throws IOException {
    try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
      channel.force(true);
    }
  }

  private static byte[] readBounded(Path file, int maximum) throws IOException {
    BasicFileAttributes attributes = Files.readAttributes(
        file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!attributes.isRegularFile() || attributes.size() <= 0 || attributes.size() > maximum) {
      throw new IOException("archive validation file has invalid type or size: " + file);
    }
    try (FileChannel channel = FileChannel.open(
        file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      ByteBuffer buffer = ByteBuffer.allocate((int) attributes.size());
      while (buffer.hasRemaining()) {
        if (channel.read(buffer) < 0) {
          throw new IOException("archive validation file was truncated: " + file);
        }
      }
      if (channel.read(ByteBuffer.allocate(1)) != -1) {
        throw new IOException("archive validation file grew while reading: " + file);
      }
      return buffer.array();
    }
  }

  private static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
    while (buffer.hasRemaining()) {
      channel.write(buffer);
    }
  }

  private static int ordinal(String name) {
    for (int i = 0; i < STAGES.length; i++) {
      if (STAGES[i].equals(name)) {
        return i;
      }
    }
    throw new ArchiveException("unknown resumable archive validation stage: " + name);
  }

  private static void fingerprint(Class<?> type, MessageDigest digest, Set<Path> visited)
      throws IOException, URISyntaxException {
    if (type.getProtectionDomain().getCodeSource() == null) {
      throw new IOException("missing archive validator code source: " + type.getName());
    }
    Path source = Paths.get(type.getProtectionDomain().getCodeSource().getLocation().toURI())
        .toRealPath();
    if (!visited.add(source)) {
      return;
    }
    if (Files.isDirectory(source)) {
      List<Path> classes;
      try (Stream<Path> files = Files.walk(source)) {
        classes = files.filter(file -> file.toString().endsWith(".class"))
            .sorted().collect(Collectors.toList());
      }
      for (Path file : classes) {
        digest.update(source.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
        fingerprintFile(file, digest);
      }
    } else {
      fingerprintFile(source, digest);
    }
  }

  private static void fingerprintFile(Path file, MessageDigest digest) throws IOException {
    try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) {
        digest.update(buffer, 0, count);
      }
    }
  }

  private static MessageDigest newDigest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
