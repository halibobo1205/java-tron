package org.tron.core.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
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
  public void conditionalSampleReusesANewerCompletionWithoutStartingAnotherProbe() {
    AtomicInteger probes = new AtomicInteger();
    try (ArchiveDiskSpaceSampler sampler = new ArchiveDiskSpaceSampler(
        "disk-sampler-conditional", probes::incrementAndGet)) {
      ArchiveDiskSpaceSampler.Sample first =
          sampler.sample(TimeUnit.SECONDS.toNanos(1L));
      ArchiveDiskSpaceSampler.Sample second =
          sampler.sample(TimeUnit.SECONDS.toNanos(1L));

      ArchiveDiskSpaceSampler.Sample reused =
          sampler.sampleAfter(first.getGeneration(), TimeUnit.SECONDS.toNanos(1L));

      assertEquals(second.getGeneration(), reused.getGeneration());
      assertEquals(second.getUsableBytes(), reused.getUsableBytes());
      assertEquals(2, probes.get());
    }
  }

  @Test
  public void conditionalRequestReturnsNewerLowSampleWithoutStartingAnotherProbe() {
    AtomicInteger probes = new AtomicInteger();
    try (ArchiveDiskSpaceSampler sampler = new ArchiveDiskSpaceSampler(
        "disk-sampler-conditional-request", () -> {
          int attempt = probes.incrementAndGet();
          return attempt == 1 ? Long.MAX_VALUE : 7L;
        })) {
      ArchiveDiskSpaceSampler.Sample first =
          sampler.sample(TimeUnit.SECONDS.toNanos(1L));
      long lowGeneration = sampler.requestSample();
      ArchiveDiskSpaceSampler.Sample low =
          sampler.awaitSample(lowGeneration, TimeUnit.SECONDS.toNanos(1L));

      ArchiveDiskSpaceSampler.Sample reused = sampler.requestSampleAfter(
          first.getGeneration(), TimeUnit.SECONDS.toNanos(1L));

      assertEquals(low.getGeneration(), reused.getGeneration());
      assertEquals(7L, reused.getUsableBytes());
      assertEquals(2, probes.get());
    }
  }

  @Test
  public void conditionalRequestDoesNotSkipNewerFailedCompletion() {
    AtomicInteger probes = new AtomicInteger();
    try (ArchiveDiskSpaceSampler sampler = new ArchiveDiskSpaceSampler(
        "disk-sampler-conditional-failure", () -> {
          int attempt = probes.incrementAndGet();
          if (attempt == 2) {
            throw new ArchiveException("injected conditional probe failure");
          }
          return attempt;
        })) {
      ArchiveDiskSpaceSampler.Sample first =
          sampler.sample(TimeUnit.SECONDS.toNanos(1L));
      long failedGeneration = sampler.requestSample();
      assertThrows(ArchiveException.class, () -> sampler.awaitSample(
          failedGeneration, TimeUnit.SECONDS.toNanos(1L)));

      ArchiveException failure = assertThrows(ArchiveException.class,
          () -> sampler.requestSampleAfter(
              first.getGeneration(), TimeUnit.SECONDS.toNanos(1L)));

      assertTrue(failure.getMessage().contains("injected conditional probe failure"));
      assertEquals(2, probes.get());
    }
  }

  @Test
  public void asynchronousRequestsAreSingleFlightAndExposeTheCompletedSample() throws Exception {
    CountDownLatch probeEntered = new CountDownLatch(1);
    CountDownLatch releaseProbe = new CountDownLatch(1);
    AtomicInteger probes = new AtomicInteger();
    try (ArchiveDiskSpaceSampler sampler = new ArchiveDiskSpaceSampler(
        "disk-sampler-async", () -> {
          probes.incrementAndGet();
          probeEntered.countDown();
          await(releaseProbe);
          return 84L;
        })) {
      long firstGeneration = sampler.requestSample();
      long coalescedGeneration = sampler.requestSample();

      assertEquals(firstGeneration, coalescedGeneration);
      assertTrue(probeEntered.await(1L, TimeUnit.SECONDS));
      assertEquals(1, probes.get());
      assertNull(sampler.latestCompletedSample());

      releaseProbe.countDown();
      ArchiveDiskSpaceSampler.Sample completed = sampler.awaitSample(
          firstGeneration, TimeUnit.SECONDS.toNanos(1L));
      assertEquals(firstGeneration, completed.getGeneration());
      assertEquals(84L, completed.getUsableBytes());
      assertEquals(firstGeneration,
          sampler.latestCompletedSample().getGeneration());
    } finally {
      releaseProbe.countDown();
    }
  }

  @Test
  public void stalledAsynchronousRequestIsRejectedAfterItsPendingTimeout() throws Exception {
    CountDownLatch probeEntered = new CountDownLatch(1);
    CountDownLatch releaseProbe = new CountDownLatch(1);
    try (ArchiveDiskSpaceSampler sampler = new ArchiveDiskSpaceSampler(
        "disk-sampler-async-timeout", () -> {
          probeEntered.countDown();
          await(releaseProbe);
          return 84L;
        })) {
      long generation = sampler.requestSample(0L);
      assertTrue(probeEntered.await(1L, TimeUnit.SECONDS));

      ArchiveException failure = assertThrows(
          ArchiveException.class, () -> sampler.requestSample(0L));

      assertTrue(failure.getMessage().contains("probe timed out"));
      releaseProbe.countDown();
      assertEquals(generation, sampler.awaitSample(
          generation, TimeUnit.SECONDS.toNanos(1L)).getGeneration());
    } finally {
      releaseProbe.countDown();
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
