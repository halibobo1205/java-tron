package org.tron.core.archive;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.tron.core.archive.unified.UnifiedArchiveIterator;

/** Coarse progress for startup-only scans, without a clock read on every row. */
@Slf4j(topic = "archive")
public final class ArchiveStartupProgress {

  private final String stage;
  private final ArchiveValidationCheckpoint checkpoint;
  private final boolean complete;
  private final byte[] resumeKey;
  private final long startedNanos = System.nanoTime();
  private long reportedNanos = startedNanos;
  private long checkpointNanos = startedNanos;
  private boolean cursorSaved;
  private long rows;
  private long position = -1L;

  public ArchiveStartupProgress(String stage) {
    this(stage, null, false, null, 0L, -1L);
  }

  ArchiveStartupProgress(String stage, ArchiveValidationCheckpoint checkpoint,
      boolean complete, byte[] resumeKey, long rows, long position) {
    this.stage = stage;
    this.checkpoint = checkpoint;
    this.complete = complete;
    this.resumeKey = resumeKey == null ? null : Arrays.copyOf(resumeKey, resumeKey.length);
    this.cursorSaved = resumeKey != null;
    this.rows = resumeKey == null ? 0L : rows - 1L;
    this.position = position;
    logger.info("Archive startup validation {}: stage={}, rows={}, position={}",
        complete ? "reused" : resumeKey == null ? "started" : "resumed", stage, rows, position);
  }

  public boolean isComplete() {
    return complete;
  }

  public byte[] resumeKey() {
    return resumeKey == null ? null : Arrays.copyOf(resumeKey, resumeKey.length);
  }

  /** Resume inclusively so the checkpoint boundary itself is checked again. */
  public void seek(UnifiedArchiveIterator iterator, byte[] firstKey) {
    if (resumeKey != null) {
      iterator.seek(resumeKey);
      if (!iterator.isValid() || !Arrays.equals(iterator.key(), resumeKey)) {
        checkpoint.discard();
        throw new ArchiveException("archive validation checkpoint cursor is missing: " + stage);
      }
    } else if (firstKey == null) {
      iterator.seekToFirst();
    } else {
      iterator.seek(firstKey);
    }
  }

  public void record(long position, UnifiedArchiveIterator iterator) {
    record(position);
    if (checkpoint != null && (rows & 1023L) == 0L) {
      long now = System.nanoTime();
      if (!cursorSaved || now - checkpointNanos >= TimeUnit.SECONDS.toNanos(5L)) {
        ArchiveRocksIterators.requireOk(iterator, "archive validation checkpoint " + stage);
        checkpoint.save(stage, rows, position, iterator.key(), false);
        cursorSaved = true;
        checkpointNanos = System.nanoTime();
      }
    }
  }

  public void record(long position) {
    this.position = position;
    rows++;
    if ((rows & 1023L) == 0L) {
      long now = System.nanoTime();
      if (now - reportedNanos >= TimeUnit.SECONDS.toNanos(5L)) {
        report("running", now);
        reportedNanos = now;
      }
    }
  }

  public void complete() {
    if (checkpoint != null && !complete) {
      checkpoint.save(stage, rows, position, null, true);
    }
    report("complete", System.nanoTime());
  }

  private void report(String state, long now) {
    logger.info("Archive startup validation {}: stage={}, rows={}, position={}, elapsedMs={}",
        state, stage, rows, position, TimeUnit.NANOSECONDS.toMillis(now - startedNanos));
  }
}
