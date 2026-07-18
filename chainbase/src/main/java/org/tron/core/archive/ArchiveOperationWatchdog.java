package org.tron.core.archive;

import java.util.concurrent.TimeUnit;

/** One-operation daemon watchdog used where a native call cannot be interrupted safely. */
final class ArchiveOperationWatchdog implements AutoCloseable {

  @FunctionalInterface
  interface TimeoutHandler {

    /** Arms fail-stop synchronously and returns any slower durability/delivery continuation. */
    Runnable arm(RuntimeException failure);
  }

  private final Object monitor = new Object();
  private final TimeoutHandler timeoutHandler;
  private final PreparedTimeoutException preparedTimeoutFailure =
      new PreparedTimeoutException();
  private final Thread worker;
  private long generation;
  private long activeGeneration;
  private long deadlineNanos;
  private long timedOutGeneration;
  private long armedTimeoutGeneration;
  private RuntimeException timeoutFailure;
  private boolean closed;

  ArchiveOperationWatchdog(String threadName, TimeoutHandler timeoutHandler) {
    if (threadName == null || threadName.isEmpty()) {
      throw new IllegalArgumentException("threadName must not be empty");
    }
    if (timeoutHandler == null) {
      throw new NullPointerException("timeoutHandler");
    }
    this.timeoutHandler = timeoutHandler;
    worker = new Thread(this::run, threadName);
    worker.setDaemon(true);
    worker.start();
  }

  Scope arm(String operationName, long timeoutNanos) {
    if (operationName == null || operationName.isEmpty()) {
      throw new IllegalArgumentException("operationName must not be empty");
    }
    if (timeoutNanos <= 0L) {
      throw new IllegalArgumentException("operation timeout must be positive");
    }
    synchronized (monitor) {
      requireOpen();
      if (timedOutGeneration != 0L) {
        throw new ArchiveException("archive operation watchdog has already timed out");
      }
      if (activeGeneration != 0L) {
        throw new ArchiveException("archive operation watchdog is already armed");
      }
      if (generation == Long.MAX_VALUE) {
        throw new ArchiveException("archive operation watchdog generation overflow");
      }
      long nextGeneration = generation + 1L;
      Scope scope = new Scope(this, nextGeneration);
      preparedTimeoutFailure.prepare(operationName + " exceeded its fail-stop timeout");
      long preparedDeadline = System.nanoTime() + timeoutNanos;
      generation = nextGeneration;
      activeGeneration = nextGeneration;
      deadlineNanos = preparedDeadline;
      timeoutFailure = preparedTimeoutFailure;
      monitor.notifyAll();
      return scope;
    }
  }

  @Override
  public void close() {
    if (Thread.currentThread() == worker) {
      throw new ArchiveException("archive operation watchdog cannot close from its worker thread");
    }
    boolean interrupted = false;
    boolean interruptWorker;
    synchronized (monitor) {
      if (!closed) {
        if (activeGeneration != 0L && deadlineNanos - System.nanoTime() <= 0L) {
          claimTimeoutLocked(activeGeneration);
        }
        closed = true;
        if (timedOutGeneration == 0L) {
          activeGeneration = 0L;
          timeoutFailure = null;
        }
        monitor.notifyAll();
        while (timedOutGeneration != 0L
            && armedTimeoutGeneration < timedOutGeneration) {
          try {
            monitor.wait();
          } catch (InterruptedException e) {
            interrupted = true;
          }
        }
      }
      interruptWorker = timedOutGeneration == 0L;
    }
    if (interruptWorker) {
      worker.interrupt();
    }
    while (worker.isAlive()) {
      try {
        worker.join();
      } catch (InterruptedException e) {
        interrupted = true;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private void disarmOrThrow(long expectedGeneration) {
    boolean interrupted = false;
    RuntimeException failure = null;
    synchronized (monitor) {
      if (activeGeneration == expectedGeneration) {
        if (deadlineNanos - System.nanoTime() > 0L) {
          activeGeneration = 0L;
          timeoutFailure = null;
          monitor.notifyAll();
          return;
        }
        claimTimeoutLocked(expectedGeneration);
      }
      while (timedOutGeneration == expectedGeneration
          && armedTimeoutGeneration < expectedGeneration) {
        try {
          monitor.wait();
        } catch (InterruptedException e) {
          interrupted = true;
        }
      }
      if (timedOutGeneration == expectedGeneration) {
        failure = timeoutFailure;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
    if (failure != null) {
      throw failure;
    }
  }

  private void run() {
    while (true) {
      long watchedGeneration;
      RuntimeException watchedFailure;
      synchronized (monitor) {
        while (timedOutGeneration == 0L && !closed && activeGeneration == 0L) {
          try {
            monitor.wait();
          } catch (InterruptedException e) {
            if (closed) {
              return;
            }
          }
        }
        if (timedOutGeneration != 0L
            && armedTimeoutGeneration < timedOutGeneration) {
          watchedGeneration = timedOutGeneration;
          watchedFailure = timeoutFailure;
        } else if (closed) {
          return;
        } else {
          watchedGeneration = activeGeneration;
          while (!closed && activeGeneration == watchedGeneration) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0L) {
              break;
            }
            try {
              TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
            } catch (InterruptedException e) {
              if (closed) {
                return;
              }
            }
          }
          if (activeGeneration != watchedGeneration) {
            continue;
          }
          claimTimeoutLocked(watchedGeneration);
          watchedFailure = timeoutFailure;
        }
      }

      Runnable completion = null;
      try {
        completion = timeoutHandler.arm(watchedFailure);
      } catch (Throwable armingFailure) {
        addSuppressedSafely(watchedFailure, armingFailure);
      } finally {
        synchronized (monitor) {
          armedTimeoutGeneration = watchedGeneration;
          monitor.notifyAll();
        }
      }
      if (completion != null) {
        try {
          completion.run();
        } catch (Throwable completionFailure) {
          addSuppressedSafely(watchedFailure, completionFailure);
        }
      }
      return;
    }
  }

  private void claimTimeoutLocked(long expectedGeneration) {
    if (activeGeneration != expectedGeneration || timedOutGeneration != 0L) {
      return;
    }
    timedOutGeneration = expectedGeneration;
    activeGeneration = 0L;
    monitor.notifyAll();
  }

  private static void addSuppressedSafely(Throwable primary, Throwable candidate) {
    if (primary == null || candidate == null || primary == candidate) {
      return;
    }
    try {
      primary.addSuppressed(candidate);
    } catch (Throwable ignored) {
      // Preserve the timeout failure even when a hostile Throwable rejects suppression.
    }
  }

  private void requireOpen() {
    if (closed) {
      throw new ArchiveException("archive operation watchdog is closed");
    }
  }

  private static final class PreparedTimeoutException extends ArchiveException {

    private static final long serialVersionUID = 1L;
    private volatile String operationName = "archive operation timeout";

    private PreparedTimeoutException() {
      super("archive operation timeout");
    }

    private void prepare(String operation) {
      operationName = operation;
    }

    @Override
    public String getMessage() {
      return operationName;
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
      return this;
    }
  }

  static final class Scope implements AutoCloseable {

    private final ArchiveOperationWatchdog owner;
    private final long generation;
    private final Thread thread = Thread.currentThread();
    private boolean closed;

    private Scope(ArchiveOperationWatchdog owner, long generation) {
      this.owner = owner;
      this.generation = generation;
    }

    @Override
    public void close() {
      if (Thread.currentThread() != thread) {
        throw new ArchiveException("archive operation watchdog scope changed threads");
      }
      if (!closed) {
        closed = true;
        owner.disarmOrThrow(generation);
      }
    }
  }
}
