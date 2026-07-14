package org.tron.core.archive;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Single-worker publisher with a constant-size coalescing target. Durable journals are the queue;
 * this object never copies block records into heap backlog.
 */
public final class BoundedArchivePublisher implements AutoCloseable {

  private static final long CLOSE_TIMEOUT_SECONDS = 30L;

  public enum State {
    PAUSED,
    RUNNING,
    DRAINING,
    FAILED,
    CLOSED
  }

  @FunctionalInterface
  public interface TargetExecutor {

    /** Publishes at most one block and returns true when the target is fully satisfied or stale. */
    boolean publishNext(ArchivePublishTarget target);
  }

  private final Object monitor = new Object();
  private final TargetExecutor executor;
  private final Consumer<RuntimeException> failureHandler;
  private final Thread worker;

  private State state = State.PAUSED;
  private ArchivePublishTarget target;
  private boolean processing;

  public BoundedArchivePublisher(String threadName, TargetExecutor executor,
      Consumer<RuntimeException> failureHandler) {
    if (threadName == null || threadName.isEmpty()) {
      throw new IllegalArgumentException("threadName must not be empty");
    }
    if (executor == null) {
      throw new NullPointerException("executor");
    }
    if (failureHandler == null) {
      throw new NullPointerException("failureHandler");
    }
    this.executor = executor;
    this.failureHandler = failureHandler;
    this.worker = new Thread(this::run, threadName);
    this.worker.setDaemon(true);
    this.worker.start();
  }

  /** Opens the worker gate. The thread is created paused and performs no archive access before it. */
  public void activate() {
    synchronized (monitor) {
      if (state != State.PAUSED) {
        throw new ArchiveException("archive publisher cannot activate from state " + state);
      }
      state = State.RUNNING;
      monitor.notifyAll();
    }
  }

  /**
   * Coalesces targets by epoch and height. A newer epoch replaces all older work; within one epoch
   * only the highest requested solidified block is retained.
   */
  public void request(ArchivePublishTarget requested) {
    if (requested == null) {
      throw new NullPointerException("requested");
    }
    synchronized (monitor) {
      if (state == State.DRAINING || state == State.CLOSED) {
        // A writer admitted before drain may finish after publisher admission closes. Its durable
        // journal is intentionally recovered synchronously on the next startup.
        return;
      }
      if (state != State.RUNNING) {
        throw new ArchiveException("archive publisher admission is closed in state " + state);
      }
      if (target != null
          && requested.getCanonicalEpoch() == target.getCanonicalEpoch()
          && requested.getBlockNum() == target.getBlockNum()
          && !requested.equals(target)) {
        throw new ArchiveException("conflicting archive publish target for block "
            + requested.getBlockNum() + " in canonical epoch "
            + requested.getCanonicalEpoch());
      }
      if (target == null
          || requested.getCanonicalEpoch() > target.getCanonicalEpoch()
          || requested.getCanonicalEpoch() == target.getCanonicalEpoch()
              && requested.getBlockNum() > target.getBlockNum()) {
        target = requested;
      }
      monitor.notifyAll();
    }
  }

  /** Stops admission and drops only the in-memory target; durable journals remain recoverable. */
  public void beginDrain() {
    synchronized (monitor) {
      if (state == State.CLOSED || state == State.FAILED) {
        return;
      }
      state = State.DRAINING;
      target = null;
      monitor.notifyAll();
    }
  }

  public State getState() {
    synchronized (monitor) {
      return state;
    }
  }

  public boolean awaitIdle(long timeout, TimeUnit unit) throws InterruptedException {
    if (unit == null) {
      throw new NullPointerException("unit");
    }
    if (timeout < 0) {
      throw new IllegalArgumentException("timeout must be non-negative");
    }
    long remaining = unit.toNanos(timeout);
    synchronized (monitor) {
      while (processing || target != null) {
        if (remaining == 0) {
          return false;
        }
        long started = System.nanoTime();
        TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
        long elapsed = System.nanoTime() - started;
        remaining = elapsed >= remaining ? 0 : remaining - elapsed;
      }
      return true;
    }
  }

  @Override
  public void close() {
    beginDrain();
    if (Thread.currentThread() == worker) {
      return;
    }
    boolean interrupted = false;
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CLOSE_TIMEOUT_SECONDS);
    while (worker.isAlive()) {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0L) {
        throw new ArchiveException("archive publisher did not stop within "
            + CLOSE_TIMEOUT_SECONDS + " seconds");
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
    RuntimeException failure = null;
    try {
      while (true) {
        ArchivePublishTarget next;
        synchronized (monitor) {
          while (state == State.PAUSED || (state == State.RUNNING && target == null)) {
            try {
              monitor.wait();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new ArchiveException("archive publisher interrupted", e);
            }
          }
          if (state != State.RUNNING) {
            return;
          }
          next = target;
          processing = true;
        }

        boolean complete = executor.publishNext(next);
        synchronized (monitor) {
          processing = false;
          if (complete && next.equals(target)) {
            target = null;
          }
          monitor.notifyAll();
        }
      }
    } catch (Throwable throwable) {
      failure = throwable instanceof RuntimeException
          ? (RuntimeException) throwable
          : new ArchiveException("archive publisher terminated with a fatal error", throwable);
      synchronized (monitor) {
        processing = false;
        target = null;
        state = State.FAILED;
        monitor.notifyAll();
      }
    } finally {
      if (failure != null) {
        try {
          failureHandler.accept(failure);
        } catch (RuntimeException handlerFailure) {
          failure.addSuppressed(handlerFailure);
        }
      }
      synchronized (monitor) {
        if (state != State.FAILED) {
          state = State.CLOSED;
        }
        monitor.notifyAll();
      }
    }
  }
}
