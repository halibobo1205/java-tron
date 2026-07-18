package org.tron.core.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class ArchiveDiskSpaceSamplerTest {

  @Test
  public void stalledProbeIsSingleFlightAndCallerTimeoutIsBounded() throws Exception {
    CountDownLatch probeEntered = new CountDownLatch(1);
    CountDownLatch releaseProbe = new CountDownLatch(1);
    AtomicInteger probes = new AtomicInteger();
    ArchiveDiskSpaceSampler sampler = new ArchiveDiskSpaceSampler("disk-sampler-test", () -> {
      probes.incrementAndGet();
      probeEntered.countDown();
      await(releaseProbe);
      return 42L;
    });
    FutureTask<ArchiveDiskSpaceSampler.Sample> first = new FutureTask<>(
        () -> sampler.sample(TimeUnit.SECONDS.toNanos(2L)));
    Thread firstCaller = new Thread(first, "first-disk-sample-caller");
    try {
      firstCaller.start();
      assertTrue(probeEntered.await(1L, TimeUnit.SECONDS));

      assertThrows(ArchiveException.class,
          () -> sampler.sample(TimeUnit.MILLISECONDS.toNanos(20L)));
      assertEquals(1, probes.get());

      releaseProbe.countDown();
      ArchiveDiskSpaceSampler.Sample sample = first.get(1L, TimeUnit.SECONDS);
      assertEquals(1L, sample.getGeneration());
      assertEquals(42L, sample.getUsableBytes());
      assertTrue(sample.getSampledAtNanos() != Long.MIN_VALUE);
    } finally {
      releaseProbe.countDown();
      sampler.close();
      firstCaller.join(1_000L);
    }
  }

  @Test
  public void completedSamplesCarryMonotonicGenerations() {
    AtomicInteger probes = new AtomicInteger();
    try (ArchiveDiskSpaceSampler sampler = new ArchiveDiskSpaceSampler(
        "disk-sampler-generations", probes::incrementAndGet)) {
      ArchiveDiskSpaceSampler.Sample first =
          sampler.sample(TimeUnit.SECONDS.toNanos(1L));
      ArchiveDiskSpaceSampler.Sample second =
          sampler.sample(TimeUnit.SECONDS.toNanos(1L));

      assertEquals(1L, first.getGeneration());
      assertEquals(2L, second.getGeneration());
      assertEquals(1L, first.getUsableBytes());
      assertEquals(2L, second.getUsableBytes());
    }
  }

  @Test
  public void closeFailsWhenProbeIgnoresInterruptUntilReleased() throws Exception {
    CountDownLatch probeEntered = new CountDownLatch(1);
    CountDownLatch releaseProbe = new CountDownLatch(1);
    ArchiveDiskSpaceSampler sampler = new ArchiveDiskSpaceSampler(
        "disk-sampler-close-timeout", () -> {
          probeEntered.countDown();
          await(releaseProbe);
          return 1L;
        });
    FutureTask<ArchiveDiskSpaceSampler.Sample> sample = new FutureTask<>(
        () -> sampler.sample(TimeUnit.SECONDS.toNanos(2L)));
    Thread caller = new Thread(sample, "disk-sampler-close-caller");
    try {
      caller.start();
      assertTrue(probeEntered.await(1L, TimeUnit.SECONDS));

      ArchiveException failure = assertThrows(ArchiveException.class,
          () -> sampler.close(TimeUnit.MILLISECONDS.toNanos(20L)));
      assertTrue(failure.getMessage().contains("did not stop"));
    } finally {
      releaseProbe.countDown();
      caller.join(1_000L);
      sampler.close(TimeUnit.SECONDS.toNanos(1L));
    }
  }

  private static void await(CountDownLatch latch) {
    boolean interrupted = false;
    while (true) {
      try {
        latch.await();
        break;
      } catch (InterruptedException e) {
        interrupted = true;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
