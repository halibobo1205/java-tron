package org.tron.core.services.jsonrpc;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
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
  private static final int DEBUG_REQUEST = 1;
  private static final int HISTORICAL_DEBUG_REQUEST = 1 << 1;
  private static final int ORDINARY_HISTORICAL_REQUEST = 1 << 2;
  private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

  private final boolean enabled;
  private final long shutdownWaitMs;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final ThreadPoolExecutor executor;
  private final ThreadPoolExecutor debugTraceExecutor;

  public static ArchiveJsonRpcExecutor disabled() {
    return new ArchiveJsonRpcExecutor();
  }

  public ArchiveJsonRpcExecutor(int workerThreads, long queryDeadlineMs) {
    this(workerThreads, queryDeadlineMs, 0, 0);
  }

  public ArchiveJsonRpcExecutor(int workerThreads, long queryDeadlineMs,
      int maxConcurrentTraces, int maxPendingTraces) {
    if (workerThreads <= 0) {
      throw new IllegalArgumentException("archive JSON-RPC worker count must be positive");
    }
    if (queryDeadlineMs <= 0L && queryDeadlineMs != ArchiveQueryLimits.UNLIMITED) {
      throw new IllegalArgumentException(
          "archive JSON-RPC query deadline must be positive or unlimited");
    }
    if (maxConcurrentTraces < 0) {
      throw new IllegalArgumentException(
          "archive debug trace worker count must be non-negative");
    }
    if (maxPendingTraces < 0) {
      throw new IllegalArgumentException(
          "archive debug trace pending count must be non-negative");
    }
    this.enabled = true;
    this.shutdownWaitMs = queryDeadlineMs == ArchiveQueryLimits.UNLIMITED
        ? MAX_SHUTDOWN_WAIT_MS
        : StrictMathWrapper.min(MAX_SHUTDOWN_WAIT_MS, StrictMathWrapper.max(1L, queryDeadlineMs));
    this.executor = new ThreadPoolExecutor(
        workerThreads, workerThreads, 0L, TimeUnit.MILLISECONDS,
        new SynchronousQueue<>(), lowPriorityThreadFactory("archive-jsonrpc-"),
        new ThreadPoolExecutor.AbortPolicy());
    this.debugTraceExecutor = maxConcurrentTraces == 0
        ? null
        : new ThreadPoolExecutor(
            maxConcurrentTraces, maxConcurrentTraces, 0L, TimeUnit.MILLISECONDS,
            traceQueue(maxPendingTraces), lowPriorityThreadFactory("archive-debug-trace-"),
            new ThreadPoolExecutor.AbortPolicy());
  }

  private ArchiveJsonRpcExecutor() {
    this.enabled = false;
    this.shutdownWaitMs = 0L;
    this.executor = null;
    this.debugTraceExecutor = null;
  }

  /** Executes inline unless the request contains an archive-backed historical method. */
  public void executeIfHistorical(JsonNode request, RequestTask task) throws IOException {
    if (task == null) {
      throw new NullPointerException("task");
    }
    int classification = classifyRequests(request);
    boolean debugTraceRequest = (classification & DEBUG_REQUEST) != 0;
    boolean historicalRequest =
        (classification & (HISTORICAL_DEBUG_REQUEST | ORDINARY_HISTORICAL_REQUEST)) != 0;
    boolean ordinaryHistoricalRequest =
        (classification & ORDINARY_HISTORICAL_REQUEST) != 0;
    if (!enabled || !historicalRequest
        || debugTraceRequest && debugTraceExecutor == null && !ordinaryHistoricalRequest) {
      task.run();
      return;
    }
    if (closed.get()) {
      throw rejection(admissionClosed(null));
    }
    Future<Void> future;
    boolean useDebugExecutor = debugTraceRequest && debugTraceExecutor != null;
    ThreadPoolExecutor selectedExecutor = useDebugExecutor ? debugTraceExecutor : executor;
    try {
      future = selectedExecutor.submit(() -> {
        task.run();
        return null;
      });
    } catch (RejectedExecutionException e) {
      throw rejection(closed.get() ? admissionClosed(e)
          : executionCapacityExceeded(useDebugExecutor, selectedExecutor));
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
    int classification = classifyRequests(request);
    return (classification
        & (HISTORICAL_DEBUG_REQUEST | ORDINARY_HISTORICAL_REQUEST)) != 0;
  }

  static boolean containsDebugTraceRequest(JsonNode request) {
    return (classifyRequests(request) & DEBUG_REQUEST) != 0;
  }

  private static int classifyRequests(JsonNode request) {
    if (request == null) {
      return 0;
    }
    if (request.isArray()) {
      int classification = 0;
      for (JsonNode element : request) {
        classification |= classifyRequests(element);
      }
      return classification;
    }
    if (!request.isObject()) {
      return 0;
    }
    JsonNode methodNode = request.get("method");
    if (methodNode == null || !methodNode.isTextual()) {
      return 0;
    }
    String method = methodNode.asText();
    boolean debugTrace = "debug_traceCall".equals(method)
        || "debug_traceTransaction".equals(method);
    int classification = debugTrace ? DEBUG_REQUEST : 0;
    if ("debug_traceTransaction".equals(method)) {
      return classification | HISTORICAL_DEBUG_REQUEST;
    }
    int selectorIndex = historicalSelectorIndex(method);
    if (selectorIndex < 0) {
      return classification;
    }
    JsonNode params = request.get("params");
    boolean historical = params == null || !params.isArray()
        || params.size() <= selectorIndex;
    if (!historical) {
      JsonNode selector = params.get(selectorIndex);
      historical = selector == null || !selector.isTextual()
          || !JsonRpcApiUtil.LATEST_STR.equalsIgnoreCase(selector.asText());
    }
    if (!historical) {
      return classification;
    }
    return classification | (debugTrace
        ? HISTORICAL_DEBUG_REQUEST : ORDINARY_HISTORICAL_REQUEST);
  }

  @Override
  public void close() {
    if (!enabled || !closed.compareAndSet(false, true)) {
      return;
    }
    executor.shutdown();
    if (debugTraceExecutor != null) {
      debugTraceExecutor.shutdown();
    }
    try {
      boolean queryStopped = executor.awaitTermination(shutdownWaitMs, TimeUnit.MILLISECONDS);
      boolean traceStopped = debugTraceExecutor == null
          || debugTraceExecutor.awaitTermination(shutdownWaitMs, TimeUnit.MILLISECONDS);
      if (queryStopped && traceStopped) {
        return;
      }
      cancelQueued(executor.shutdownNow());
      if (debugTraceExecutor != null) {
        cancelQueued(debugTraceExecutor.shutdownNow());
      }
      queryStopped = executor.awaitTermination(1L, TimeUnit.SECONDS);
      traceStopped = debugTraceExecutor == null
          || debugTraceExecutor.awaitTermination(1L, TimeUnit.SECONDS);
      if (!queryStopped || !traceStopped) {
        logger.warn("archive JSON-RPC workers did not terminate after interruption");
      }
    } catch (InterruptedException e) {
      cancelQueued(executor.shutdownNow());
      if (debugTraceExecutor != null) {
        cancelQueued(debugTraceExecutor.shutdownNow());
      }
      Thread.currentThread().interrupt();
    }
  }

  private HistoricalQueryLimitException executionCapacityExceeded(boolean debugTrace,
      ThreadPoolExecutor selectedExecutor) {
    int active = selectedExecutor.getActiveCount();
    int queued = selectedExecutor.getQueue().size();
    int capacity = selectedExecutor.getMaximumPoolSize()
        + queued + selectedExecutor.getQueue().remainingCapacity();
    String work = debugTrace ? "archive debug trace" : "historical JSON-RPC";
    return new HistoricalQueryLimitException(
        HistoricalQueryLimitException.Reason.RESOURCE_EXHAUSTED,
        queued > 0
            ? HistoricalQueryLimitException.Limit.PENDING_QUERIES
            : HistoricalQueryLimitException.Limit.CONCURRENT_QUERIES,
        capacity,
        active + queued + 1L,
        work + " execution capacity reached: active=" + active + ", queued=" + queued);
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
      case "debug_traceCall":
        return 1;
      case "eth_getStorageAt":
        return 2;
      default:
        return -1;
    }
  }

  private static BlockingQueue<Runnable> traceQueue(int maxPendingTraces) {
    return maxPendingTraces == 0
        ? new SynchronousQueue<>()
        : new ArrayBlockingQueue<>(maxPendingTraces);
  }

  private static void cancelQueued(List<Runnable> queuedTasks) {
    for (Runnable task : queuedTasks) {
      if (task instanceof Future<?>) {
        ((Future<?>) task).cancel(false);
      }
    }
  }

  private static ThreadFactory lowPriorityThreadFactory(String prefix) {
    return task -> {
      Thread thread = new Thread(task,
          prefix + THREAD_SEQUENCE.getAndIncrement());
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
