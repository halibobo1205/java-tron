package org.tron.core.archive;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Single-slot, non-queueing fatal signal delivered by a dedicated control thread. */
public final class ArchiveFatalController implements AutoCloseable {

  private static final long DEFAULT_FATAL_SHUTDOWN_TIMEOUT_MS = 30_000L;
  private static final int FATAL_EXIT_STATUS = 70;

  @FunctionalInterface
  interface ProcessTerminator {

    void halt(int status);
  }

  private final Object monitor = new Object();
  private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
  private final AtomicReference<Consumer<RuntimeException>> handler = new AtomicReference<>();
  private final Thread controlThread;
  private final Thread watchdogThread;
  private final long fatalShutdownTimeoutMs;
  private final ProcessTerminator processTerminator;
  private boolean closed;
  private boolean closeRequested;
  private boolean deliveryEnabled;
  private boolean deliveryStarted;
  private boolean deliveryReturned;

  public ArchiveFatalController(String threadName) {
    this(threadName, DEFAULT_FATAL_SHUTDOWN_TIMEOUT_MS,
        status -> Runtime.getRuntime().halt(status));
  }

  ArchiveFatalController(String threadName, long fatalShutdownTimeoutMs,
      ProcessTerminator processTerminator) {
    if (threadName == null || threadName.isEmpty()) {
      throw new IllegalArgumentException("threadName must not be empty");
    }
    if (fatalShutdownTimeoutMs < 0L) {
      throw new IllegalArgumentException("fatalShutdownTimeoutMs must be non-negative");
    }
    if (processTerminator == null) {
      throw new NullPointerException("processTerminator");
    }
    this.fatalShutdownTimeoutMs = fatalShutdownTimeoutMs;
    this.processTerminator = processTerminator;
    controlThread = new Thread(this::run, threadName);
    controlThread.setDaemon(true);
    controlThread.start();
    watchdogThread = new Thread(this::runWatchdog, threadName + "-watchdog");
    watchdogThread.setDaemon(true);
    watchdogThread.start();
  }

  public void setHandler(Consumer<RuntimeException> fatalHandler) {
    if (fatalHandler == null) {
      throw new NullPointerException("fatalHandler");
    }
    if (!handler.compareAndSet(null, fatalHandler)) {
      throw new ArchiveException("archive fatal handler is already installed");
    }
    synchronized (monitor) {
      monitor.notifyAll();
    }
  }

  /** Never blocks on the control callback and never allocates an executor task. */
  public void signal(RuntimeException fatalFailure) {
    arm(fatalFailure);
    deliver();
  }

  /** Arms the watchdog while keeping the application callback behind a durable-state barrier. */
  public void arm(RuntimeException fatalFailure) {
    if (fatalFailure == null) {
      throw new NullPointerException("fatalFailure");
    }
    if (failure.compareAndSet(null, fatalFailure)) {
      synchronized (monitor) {
        monitor.notifyAll();
      }
    }
  }

  /** Opens callback delivery after the caller has persisted its fail-stop evidence. */
  public void deliver() {
    if (failure.get() == null) {
      throw new IllegalStateException("archive fatal delivery requires an armed failure");
    }
    synchronized (monitor) {
      deliveryEnabled = true;
      monitor.notifyAll();
    }
  }

  public RuntimeException getFailure() {
    return failure.get();
  }

  @Override
  public void close() {
    boolean join;
    boolean interrupted = false;
    synchronized (monitor) {
      if (closed) {
        return;
      }
      if (failure.get() != null && handler.get() != null && !deliveryReturned) {
        // A registered fatal must cross the delivery boundary before normal shutdown can close the
        // controller. Do not wait for the application callback itself: if it blocks or throws, the
        // watchdog remains armed and can still force a non-zero halt.
        closeRequested = true;
        monitor.notifyAll();
        while (deliveryEnabled && !deliveryStarted) {
          try {
            monitor.wait();
          } catch (InterruptedException e) {
            interrupted = true;
          }
        }
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
        return;
      }
      closed = true;
      join = !deliveryStarted;
      monitor.notifyAll();
    }
    // Shutdown must not wait for an application callback that may itself initiate shutdown.
    if (join && Thread.currentThread() != controlThread) {
      join(controlThread);
    }
    if (Thread.currentThread() != watchdogThread) {
      join(watchdogThread);
    }
  }

  private static void join(Thread thread) {
    boolean interrupted = false;
    while (thread.isAlive()) {
      try {
        thread.join();
      } catch (InterruptedException e) {
        interrupted = true;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private void run() {
    Consumer<RuntimeException> fatalHandler;
    RuntimeException fatalFailure;
    synchronized (monitor) {
      while (!closed
          && (failure.get() == null || handler.get() == null || !deliveryEnabled)) {
        try {
          monitor.wait();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
      if (closed) {
        return;
      }
      if (deliveryStarted) {
        return;
      }
      fatalHandler = handler.get();
      fatalFailure = failure.get();
      deliveryStarted = true;
      monitor.notifyAll();
    }
    // Deliberately outside every archive lock. A production handler may request application exit.
    fatalHandler.accept(fatalFailure);
    synchronized (monitor) {
      deliveryReturned = true;
      if (closeRequested) {
        closed = true;
      }
      monitor.notifyAll();
    }
  }

  private void runWatchdog() {
    synchronized (monitor) {
      while (!closed && (failure.get() == null || handler.get() == null)) {
        try {
          monitor.wait();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
      if (closed) {
        return;
      }
      long deadline = System.nanoTime()
          + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(fatalShutdownTimeoutMs);
      while (!closed) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0L) {
          break;
        }
        try {
          java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
      if (closed) {
        return;
      }
    }
    processTerminator.halt(FATAL_EXIT_STATUS);
  }
}
