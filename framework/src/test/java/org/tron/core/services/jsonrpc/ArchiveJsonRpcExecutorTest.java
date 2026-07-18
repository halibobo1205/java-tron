package org.tron.core.services.jsonrpc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.tron.core.archive.query.HistoricalQueryLimitException;

public class ArchiveJsonRpcExecutorTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  public void classifiesOnlyArchiveBackedHistoricalRequests() throws Exception {
    assertFalse(ArchiveJsonRpcExecutor.containsHistoricalRequest(request(
        "eth_getBalance", "[\"TAddress\",\"latest\"]")));
    assertTrue(ArchiveJsonRpcExecutor.containsHistoricalRequest(request(
        "eth_getBalance", "[\"TAddress\",\"0x10\"]")));
    assertFalse(ArchiveJsonRpcExecutor.containsHistoricalRequest(request(
        "eth_getStorageAt", "[\"TAddress\",\"0x0\",\"latest\"]")));
    assertTrue(ArchiveJsonRpcExecutor.containsHistoricalRequest(request(
        "eth_getStorageAt", "[\"TAddress\",\"0x0\",\"finalized\"]")));
    assertTrue(ArchiveJsonRpcExecutor.containsHistoricalRequest(request(
        "eth_call", "[{}, {\"blockHash\":\"0x01\"}]")));
    assertFalse(ArchiveJsonRpcExecutor.containsHistoricalRequest(request(
        "eth_blockNumber", "[]")));
    assertTrue(ArchiveJsonRpcExecutor.containsHistoricalRequest(MAPPER.readTree("["
        + "{\"method\":\"eth_blockNumber\",\"params\":[]},"
        + "{\"method\":\"eth_getCode\",\"params\":[\"TAddress\",\"0x2\"]}]")));
  }

  @Test
  public void historicalExecutionUsesLowPriorityWorkerButLatestRunsInline() throws Exception {
    ArchiveJsonRpcExecutor executor = new ArchiveJsonRpcExecutor(1, 1_000L);
    try {
      Thread caller = Thread.currentThread();
      AtomicReference<Thread> historicalThread = new AtomicReference<>();
      executor.executeIfHistorical(
          request("eth_getBalance", "[\"TAddress\",\"0x10\"]"),
          () -> historicalThread.set(Thread.currentThread()));
      assertTrue(historicalThread.get().getName().startsWith("archive-jsonrpc-"));
      assertEquals(Thread.NORM_PRIORITY - 1, historicalThread.get().getPriority());

      AtomicReference<Thread> latestThread = new AtomicReference<>();
      executor.executeIfHistorical(
          request("eth_getBalance", "[\"TAddress\",\"latest\"]"),
          () -> latestThread.set(Thread.currentThread()));
      assertEquals(caller, latestThread.get());
    } finally {
      executor.close();
    }
  }

  @Test
  public void saturatedWorkerFailsFastWithoutRunningOnCaller() throws Exception {
    ArchiveJsonRpcExecutor executor = new ArchiveJsonRpcExecutor(1, 1_000L);
    ExecutorService caller = Executors.newSingleThreadExecutor();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    JsonNode historical = request("eth_call", "[{},\"0x10\"]");
    try {
      Future<?> first = caller.submit(() -> {
        try {
          executor.executeIfHistorical(historical, () -> {
            entered.countDown();
            try {
              release.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          });
        } catch (Exception e) {
          throw new IllegalStateException(e);
        }
      });
      assertTrue(entered.await(5L, TimeUnit.SECONDS));

      HistoricalQueryLimitException failure = assertThrows(
          HistoricalQueryLimitException.class,
          () -> executor.executeIfHistorical(historical, () -> { }));
      assertEquals(HistoricalQueryLimitException.Limit.CONCURRENT_QUERIES,
          failure.getLimit());

      release.countDown();
      first.get(5L, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      caller.shutdownNow();
      executor.close();
    }
  }

  @Test
  public void interruptedCallerWaitsForAdmittedResponseSettlement() throws Exception {
    ArchiveJsonRpcExecutor executor = new ArchiveJsonRpcExecutor(1, 1_000L);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch returned = new CountDownLatch(1);
    AtomicBoolean workerInterrupted = new AtomicBoolean();
    AtomicBoolean callerInterruptRestored = new AtomicBoolean();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    JsonNode historical = request("eth_getBalance", "[\"TAddress\",\"0x10\"]");
    Thread caller = new Thread(() -> {
      try {
        executor.executeIfHistorical(historical, () -> {
          entered.countDown();
          try {
            release.await();
          } catch (InterruptedException e) {
            workerInterrupted.set(true);
            Thread.currentThread().interrupt();
          }
        });
      } catch (Throwable t) {
        failure.set(t);
      } finally {
        callerInterruptRestored.set(Thread.currentThread().isInterrupted());
        returned.countDown();
      }
    }, "archive-jsonrpc-test-caller");
    try {
      caller.start();
      assertTrue(entered.await(5L, TimeUnit.SECONDS));

      caller.interrupt();
      assertFalse(returned.await(250L, TimeUnit.MILLISECONDS));
      assertFalse(workerInterrupted.get());

      release.countDown();
      assertTrue(returned.await(5L, TimeUnit.SECONDS));
      caller.join(5_000L);
      assertFalse(caller.isAlive());
      assertTrue(callerInterruptRestored.get());
      assertEquals(null, failure.get());
    } finally {
      release.countDown();
      caller.interrupt();
      caller.join(5_000L);
      executor.close();
    }
  }

  private static JsonNode request(String method, String params) throws Exception {
    return MAPPER.readTree("{\"method\":\"" + method + "\",\"params\":" + params + "}");
  }
}
