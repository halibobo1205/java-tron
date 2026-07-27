package org.tron.core.services.jsonrpc;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.tron.common.math.StrictMathWrapper;
import org.tron.core.archive.ArchiveMetrics;
import org.tron.core.archive.query.ArchiveQueryLimits;
import org.tron.core.archive.query.HistoricalQueryLimitException;

/** Runs historical JSON-RPC execution and bounded serialization on low-priority workers. */
@Slf4j(topic = "API")
public final class ArchiveJsonRpcExecutor implements AutoCloseable {

  private static final long MAX_SHUTDOWN_WAIT_MS = 30_000L;
  private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

  private final boolean enabled;
  private final int workerThreads;
  private final long shutdownWaitMs;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final ThreadPoolExecutor executor;

  public static ArchiveJsonRpcExecutor disabled() {
    return new ArchiveJsonRpcExecutor();
  }

  public ArchiveJsonRpcExecutor(int workerThreads, long queryDeadlineMs) {
    if (workerThreads <= 0) {
      throw new IllegalArgumentException("archive JSON-RPC worker count must be positive");
    }
    if (queryDeadlineMs <= 0L && queryDeadlineMs != ArchiveQueryLimits.UNLIMITED) {
      throw new IllegalArgumentException(
          "archive JSON-RPC query deadline must be positive or unlimited");
    }
    this.enabled = true;
    this.workerThreads = workerThreads;
    this.shutdownWaitMs = queryDeadlineMs == ArchiveQueryLimits.UNLIMITED
        ? MAX_SHUTDOWN_WAIT_MS
        : StrictMathWrapper.min(MAX_SHUTDOWN_WAIT_MS, StrictMathWrapper.max(1L, queryDeadlineMs));
    this.executor = new ThreadPoolExecutor(
        workerThreads, workerThreads, 0L, TimeUnit.MILLISECONDS,
        new SynchronousQueue<>(), lowPriorityThreadFactory(),
        new ThreadPoolExecutor.AbortPolicy());
  }

  private ArchiveJsonRpcExecutor() {
    this.enabled = false;
    this.workerThreads = 0;
    this.shutdownWaitMs = 0L;
    this.executor = null;
  }

  /** Executes inline unless the request contains an archive-backed historical method. */
  public void executeIfHistorical(JsonNode request, RequestTask task) throws IOException {
    if (task == null) {
      throw new NullPointerException("task");
    }
    if (!enabled || !containsHistoricalRequest(request)) {
      task.run();
      return;
    }
    if (closed.get()) {
      throw rejection(admissionClosed(null));
    }
    Future<Void> future;
    try {
      future = executor.submit(() -> {
        task.run();
        return null;
      });
    } catch (RejectedExecutionException e) {
      throw rejection(closed.get() ? admissionClosed(e) : concurrencyExceeded());
    }
    boolean interrupted = false;
    try {
      while (true) {
        try {
          future.get();
          return;
        } catch (InterruptedException e) {
          // The worker owns servlet response settlement once admitted. Letting the caller return
          // here could recycle the response while the worker is still writing it.
          interrupted = true;
        }
      }
    } catch (CancellationException e) {
      throw rejection(admissionClosed(e));
    } catch (ExecutionException e) {
      rethrowTaskFailure(e.getCause());
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  static boolean containsHistoricalRequest(JsonNode request) {
    if (request == null) {
      return false;
    }
    if (request.isArray()) {
      for (JsonNode element : request) {
        if (containsHistoricalRequest(element)) {
          return true;
        }
      }
      return false;
    }
    if (!request.isObject()) {
      return false;
    }
    JsonNode methodNode = request.get("method");
    if (methodNode == null || !methodNode.isTextual()) {
      return false;
    }
    int selectorIndex = historicalSelectorIndex(methodNode.asText());
    if (selectorIndex < 0) {
      return false;
    }
    JsonNode params = request.get("params");
    if (params == null || !params.isArray() || params.size() <= selectorIndex) {
      return true;
    }
    JsonNode selector = params.get(selectorIndex);
    return selector == null || !selector.isTextual()
        || !JsonRpcApiUtil.LATEST_STR.equalsIgnoreCase(selector.asText());
  }

  @Override
  public void close() {
    if (!enabled || !closed.compareAndSet(false, true)) {
      return;
    }
    executor.shutdown();
    try {
      if (executor.awaitTermination(shutdownWaitMs, TimeUnit.MILLISECONDS)) {
        return;
      }
      executor.shutdownNow();
      if (!executor.awaitTermination(1L, TimeUnit.SECONDS)) {
        logger.warn("archive JSON-RPC workers did not terminate after interruption");
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  private HistoricalQueryLimitException concurrencyExceeded() {
    return new HistoricalQueryLimitException(
        HistoricalQueryLimitException.Reason.RESOURCE_EXHAUSTED,
        HistoricalQueryLimitException.Limit.CONCURRENT_QUERIES,
        workerThreads, workerThreads,
        "historical JSON-RPC worker limit reached: limit=" + workerThreads);
  }

  private static HistoricalQueryLimitException admissionClosed(Throwable cause) {
    return new HistoricalQueryLimitException(
        HistoricalQueryLimitException.Reason.RESOURCE_EXHAUSTED,
        HistoricalQueryLimitException.Limit.QUERY_ADMISSION,
        ArchiveQueryLimits.UNLIMITED, ArchiveQueryLimits.UNLIMITED,
        "historical JSON-RPC execution is closed", cause);
  }

  private static HistoricalQueryLimitException rejection(
      HistoricalQueryLimitException failure) {
    ArchiveMetrics.queryRejected(failure);
    return failure;
  }

  private static void rethrowTaskFailure(Throwable failure) throws IOException {
    if (failure instanceof IOException) {
      throw (IOException) failure;
    }
    if (failure instanceof RuntimeException) {
      throw (RuntimeException) failure;
    }
    if (failure instanceof Error) {
      throw (Error) failure;
    }
    throw new IOException("historical JSON-RPC execution failed", failure);
  }

  private static int historicalSelectorIndex(String method) {
    switch (method) {
      case "eth_getBalance":
      case "eth_getCode":
      case "eth_call":
        return 1;
      case "eth_getStorageAt":
        return 2;
      default:
        return -1;
    }
  }

  private static ThreadFactory lowPriorityThreadFactory() {
    return task -> {
      Thread thread = new Thread(task,
          "archive-jsonrpc-" + THREAD_SEQUENCE.getAndIncrement());
      thread.setDaemon(true);
      thread.setPriority(StrictMathWrapper.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
      return thread;
    };
  }

  @FunctionalInterface
  public interface RequestTask {

    void run() throws IOException;
  }
}
