package org.tron.core.archive;

import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/** Coarse progress for startup-only scans, without a clock read on every row. */
@Slf4j(topic = "archive")
public final class ArchiveStartupProgress {

  private final String stage;
  private final long startedNanos = System.nanoTime();
  private long reportedNanos = startedNanos;
  private long rows;
  private long position = -1L;

  public ArchiveStartupProgress(String stage) {
    this.stage = stage;
    logger.info("Archive startup validation started: stage={}", stage);
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
    report("complete", System.nanoTime());
  }

  private void report(String state, long now) {
    logger.info("Archive startup validation {}: stage={}, rows={}, position={}, elapsedMs={}",
        state, stage, rows, position, TimeUnit.NANOSECONDS.toMillis(now - startedNanos));
  }
}
