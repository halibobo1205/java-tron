package org.tron.core.archive;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Single-flight daemon sampler that bounds callers when a filesystem capacity probe stalls. */
final class ArchiveDiskSpaceSampler implements AutoCloseable {

  private static final long DEFAULT_CLOSE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5L);

  private final Object monitor = new Object();
  private final LongSupplier probe;
  private final Thread worker;
  private long requestedGeneration;
  private long completedGeneration;
  private long sampledBytes = Long.MAX_VALUE;
  private long sampledAtNanos = Long.MIN_VALUE;
  private Throwable sampleFailure;
  private boolean closed;

  ArchiveDiskSpaceSampler(String threadName, LongSupplier probe) {
    if (threadName == null || threadName.isEmpty()) {
      throw new IllegalArgumentException("threadName must not be empty");
    }
    if (probe == null) {
      throw new NullPointerException("probe");
    }
    this.probe = probe;
    worker = new Thread(this::run, threadName);
    worker.setDaemon(true);
    worker.start();
  }

  Sample sample(long timeoutNanos) {
    if (timeoutNanos < 0L) {
      throw new IllegalArgumentException("disk sample timeout must be non-negative");
    }
    boolean interrupted = false;
    synchronized (monitor) {
      requireOpen();
      long targetGeneration;
      if (requestedGeneration > completedGeneration) {
        targetGeneration = requestedGeneration;
      } else {
        if (requestedGeneration == Long.MAX_VALUE) {
          throw new ArchiveException("archive filesystem capacity sample generation overflow");
        }
        targetGeneration = ++requestedGeneration;
      }
      monitor.notifyAll();
      long deadline = System.nanoTime() + timeoutNanos;
      while (!closed && completedGeneration < targetGeneration) {
        long remaining = deadline - System.nanoTime();
        if (timeoutNanos == 0L || remaining <= 0L) {
          throw new ArchiveException("archive filesystem capacity probe timed out");
        }
        try {
          TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
        } catch (InterruptedException e) {
          interrupted = true;
          break;
        }
      }
      if (interrupted) {
        Thread.currentThread().interrupt();
        throw new ArchiveException("archive filesystem capacity probe interrupted");
      }
      requireOpen();
      if (sampleFailure != null) {
        throw sampleFailure(sampleFailure);
      }
      return new Sample(completedGeneration, sampledBytes, sampledAtNanos);
    }
  }

  @Override
  public void close() {
    close(DEFAULT_CLOSE_TIMEOUT_NANOS);
  }

  void close(long timeoutNanos) {
    if (timeoutNanos < 0L) {
      throw new IllegalArgumentException("disk sampler close timeout must be non-negative");
    }
    synchronized (monitor) {
      if (!closed) {
        closed = true;
        monitor.notifyAll();
      }
    }
    worker.interrupt();
    if (Thread.currentThread() == worker) {
      return;
    }
    long deadline = System.nanoTime() + timeoutNanos;
    boolean interrupted = false;
    while (worker.isAlive()) {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0L) {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
        throw new ArchiveException("archive filesystem capacity sampler did not stop");
      }
      try {
        TimeUnit.NANOSECONDS.timedJoin(worker, remaining);
      } catch (InterruptedException e) {
        interrupted = true;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private void run() {
    while (true) {
      long generation;
      synchronized (monitor) {
        while (!closed && requestedGeneration <= completedGeneration) {
          try {
            monitor.wait();
          } catch (InterruptedException e) {
            if (closed) {
              return;
            }
          }
        }
        if (closed) {
          return;
        }
        generation = requestedGeneration;
      }

      long value = Long.MAX_VALUE;
      Throwable failure = null;
      try {
        value = probe.getAsLong();
      } catch (Throwable probeFailure) {
        failure = probeFailure;
      }

      synchronized (monitor) {
        if (closed) {
          return;
        }
        sampledBytes = value;
        sampledAtNanos = System.nanoTime();
        sampleFailure = failure;
        completedGeneration = generation;
        monitor.notifyAll();
      }
    }
  }

  private void requireOpen() {
    if (closed) {
      throw new ArchiveException("archive filesystem capacity sampler is closed");
    }
  }

  private static ArchiveException sampleFailure(Throwable failure) {
    if (failure instanceof ArchiveException) {
      return (ArchiveException) failure;
    }
    return new ArchiveException("archive filesystem capacity probe failed", failure);
  }

  static final class Sample {

    private final long generation;
    private final long usableBytes;
    private final long sampledAtNanos;

    Sample(long generation, long usableBytes, long sampledAtNanos) {
      this.generation = generation;
      this.usableBytes = usableBytes;
      this.sampledAtNanos = sampledAtNanos;
    }

    long getGeneration() {
      return generation;
    }

    long getUsableBytes() {
      return usableBytes;
    }

    long getSampledAtNanos() {
      return sampledAtNanos;
    }
  }
}
