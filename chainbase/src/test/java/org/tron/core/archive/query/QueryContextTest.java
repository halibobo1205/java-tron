package org.tron.core.archive.query;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import org.tron.core.archive.ArchiveException;

public class QueryContextTest {

  @Test
  public void eachBudgetAllowsExactLimitAndTerminatesOnNextUnit() {
    assertBudgetExceeded(
        ArchiveQueryLimits.builder().maxLogicalReads(2).build(),
        HistoricalQueryLimitException.Limit.LOGICAL_READS,
        context -> context.recordLogicalReads(2),
        QueryContext::recordLogicalRead);
    assertBudgetExceeded(
        ArchiveQueryLimits.builder().maxBackendReads(2).build(),
        HistoricalQueryLimitException.Limit.BACKEND_READS,
        context -> context.recordBackendReads(2),
        QueryContext::recordBackendRead);
    assertBudgetExceeded(
        ArchiveQueryLimits.builder().maxBackendReadBytesPerRequest(2).build(),
        HistoricalQueryLimitException.Limit.BACKEND_READ_BYTES,
        context -> context.recordBackendValueBytes(2),
        context -> context.recordBackendValueBytes(1));
    assertBudgetExceeded(
        ArchiveQueryLimits.builder().maxVmSteps(2).build(),
        HistoricalQueryLimitException.Limit.VM_STEPS,
        context -> context.recordVmSteps(2),
        QueryContext::recordVmStep);
    assertBudgetExceeded(
        ArchiveQueryLimits.builder().maxVmOverlayBytes(2).build(),
        HistoricalQueryLimitException.Limit.VM_OVERLAY_BYTES,
        context -> context.recordVmOverlayBytes(2),
        context -> context.recordVmOverlayBytes(1));
    assertBudgetExceeded(
        ArchiveQueryLimits.builder().maxResponseBytes(2).build(),
        HistoricalQueryLimitException.Limit.RESPONSE_BYTES,
        context -> context.recordResponseBytes(2),
        context -> context.recordResponseBytes(1));
  }

  @Test
  public void singleBackendValueLimitRejectsBeforeAggregateAccounting() {
    QueryContext context = new QueryContext(ArchiveQueryLimits.builder()
        .maxBackendValueBytes(2)
        .maxBackendReadBytesPerRequest(10)
        .build());

    HistoricalQueryLimitException failure = assertThrows(
        HistoricalQueryLimitException.class, () -> context.recordBackendValueBytes(3));

    assertEquals(HistoricalQueryLimitException.Limit.BACKEND_VALUE_BYTES,
        failure.getLimit());
    assertEquals(0L, context.getBackendReadBytes());
  }

  @Test
  public void serializedResponseReconcilesEstimatesWithoutDoubleCounting() {
    QueryContext context = new QueryContext(ArchiveQueryLimits.builder()
        .maxResponseBytes(10L)
        .build());
    context.recordResponseBytes(4L);

    assertEquals(9L, context.recordSerializedResponseBytes(9L));
    assertEquals(9L, context.recordSerializedResponseBytes(3L));
    assertEquals(9L, context.getResponseBytes());
  }

  @Test
  public void serializedResponseEnforcesActualWireSize() {
    QueryContext context = new QueryContext(ArchiveQueryLimits.builder()
        .maxResponseBytes(8L)
        .build());
    context.recordResponseBytes(2L);

    HistoricalQueryLimitException failure = assertThrows(
        HistoricalQueryLimitException.class,
        () -> context.recordSerializedResponseBytes(9L));

    assertEquals(HistoricalQueryLimitException.Limit.RESPONSE_BYTES, failure.getLimit());
    assertEquals(9L, failure.getObserved());
    assertEquals(9L, context.getResponseBytes());
  }

  @Test
  public void unlimitedBudgetsStillAccountAndSaturateWithoutWrapping() {
    QueryContext context = new QueryContext(ArchiveQueryLimits.unlimited());

    context.recordLogicalReads(Long.MAX_VALUE);
    context.recordLogicalRead();
    context.recordBackendReads(Long.MAX_VALUE);
    context.recordBackendRead();
    context.recordBackendValueBytes(Long.MAX_VALUE);
    context.recordBackendValueBytes(1);
    context.recordCacheHit();
    context.recordCacheHit();
    context.recordVmSteps(Long.MAX_VALUE);
    context.recordVmStep();
    context.recordVmOverlayBytes(Long.MAX_VALUE);
    context.recordVmOverlayBytes(1);
    context.recordResponseBytes(Long.MAX_VALUE);
    context.recordResponseBytes(1);

    assertEquals(Long.MAX_VALUE, context.getLogicalReads());
    assertEquals(Long.MAX_VALUE, context.getBackendReads());
    assertEquals(Long.MAX_VALUE, context.getBackendReadBytes());
    assertEquals(2, context.getCacheHits());
    assertEquals(Long.MAX_VALUE, context.getVmSteps());
    assertEquals(Long.MAX_VALUE, context.getVmOverlayBytes());
    assertEquals(Long.MAX_VALUE, context.getResponseBytes());
    assertFalse(context.isTerminated());
  }

  @Test
  public void boundedBudgetCannotBeBypassedByLongOverflow() {
    QueryContext context = new QueryContext(ArchiveQueryLimits.builder()
        .maxResponseBytes(Long.MAX_VALUE - 1)
        .build());
    context.recordResponseBytes(Long.MAX_VALUE - 2);

    HistoricalQueryLimitException failure = assertThrows(
        HistoricalQueryLimitException.class, () -> context.recordResponseBytes(10));

    assertEquals(HistoricalQueryLimitException.Reason.RESOURCE_EXHAUSTED,
        failure.getReason());
    assertEquals(HistoricalQueryLimitException.Limit.RESPONSE_BYTES, failure.getLimit());
    assertEquals(Long.MAX_VALUE - 1, failure.getConfiguredLimit());
    assertEquals(Long.MAX_VALUE, failure.getObserved());
    assertEquals(Long.MAX_VALUE, context.getResponseBytes());
  }

  @Test
  public void exactLongMaxBudgetTerminatesOnTheNextUnit() {
    QueryContext context = new QueryContext(ArchiveQueryLimits.builder()
        .maxBackendReads(Long.MAX_VALUE)
        .build());
    context.recordBackendReads(Long.MAX_VALUE);

    HistoricalQueryLimitException failure = assertThrows(
        HistoricalQueryLimitException.class, context::recordBackendRead);

    assertEquals(HistoricalQueryLimitException.Limit.BACKEND_READS, failure.getLimit());
    assertEquals(Long.MAX_VALUE, failure.getConfiguredLimit());
    assertEquals(Long.MAX_VALUE, failure.getObserved());
  }

  @Test
  public void exactLongMaxAggregateBytePreflightTerminatesOnTheNextUnit() {
    QueryContext context = new QueryContext(ArchiveQueryLimits.builder()
        .maxBackendValueBytes(Long.MAX_VALUE)
        .maxBackendReadBytesPerRequest(Long.MAX_VALUE)
        .build());
    context.recordBackendValueBytes(Long.MAX_VALUE);

    HistoricalQueryLimitException failure = assertThrows(
        HistoricalQueryLimitException.class,
        () -> context.validateBackendValueBytes(1L));

    assertEquals(HistoricalQueryLimitException.Limit.BACKEND_READ_BYTES,
        failure.getLimit());
  }

  @Test
  public void saturatedFiniteDeadlineDoesNotBecomeUnlimited() {
    AtomicLong now = new AtomicLong();
    QueryContext context = new QueryContext(
        ArchiveQueryLimits.builder().deadlineMs(Long.MAX_VALUE).build(), now::get);
    now.set(Long.MAX_VALUE);

    HistoricalQueryLimitException failure = assertThrows(
        HistoricalQueryLimitException.class, context::checkDeadline);

    assertEquals(HistoricalQueryLimitException.Limit.DEADLINE, failure.getLimit());
  }

  @Test
  public void deadlineUsesMonotonicElapsedTimeAcrossNanoTimeWraparound() {
    AtomicLong now = new AtomicLong(Long.MAX_VALUE - 500_000L);
    QueryContext context = new QueryContext(
        ArchiveQueryLimits.builder().deadlineMs(1).build(), now::get);
    long started = now.get();

    now.set(started + 999_999L);
    context.checkDeadline();
    assertEquals(1, context.getRemainingNanos());

    now.set(started + 1_000_000L);
    HistoricalQueryLimitException failure = assertThrows(
        HistoricalQueryLimitException.class, context::checkDeadline);

    assertEquals(HistoricalQueryLimitException.Reason.DEADLINE, failure.getReason());
    assertEquals(HistoricalQueryLimitException.Limit.DEADLINE, failure.getLimit());
    assertEquals(1_000_000L, failure.getConfiguredLimit());
    assertEquals(1_000_000L, failure.getObserved());
    assertEquals(0, context.getRemainingNanos());
  }

  @Test
  public void firstTerminalFailureIsRethrownEvenAfterDeadlinePasses() {
    AtomicLong now = new AtomicLong(100);
    QueryContext context = new QueryContext(ArchiveQueryLimits.builder()
        .deadlineMs(1)
        .maxLogicalReads(0)
        .build(), now::get);

    HistoricalQueryLimitException first = assertThrows(
        HistoricalQueryLimitException.class, context::recordLogicalRead);
    now.addAndGet(1_000_000L);
    HistoricalQueryLimitException afterDeadline = assertThrows(
        HistoricalQueryLimitException.class, context::throwIfTerminated);

    assertSame(first, afterDeadline);
    assertSame(first, context.getTerminalException());
    assertEquals(HistoricalQueryLimitException.Reason.RESOURCE_EXHAUSTED,
        context.getTerminalReason());
    assertEquals(HistoricalQueryLimitException.Reason.RESOURCE_EXHAUSTED,
        context.terminalReason().orElse(null));
  }

  @Test
  public void recordedTerminalPeekDoesNotSampleDeadline() {
    AtomicLong now = new AtomicLong(10L);
    QueryContext context = new QueryContext(
        ArchiveQueryLimits.builder().deadlineMs(0).build(), now::get);

    assertNull(context.getRecordedTerminalException());
    HistoricalQueryLimitException deadline = assertThrows(
        HistoricalQueryLimitException.class, context::checkDeadline);
    assertSame(deadline, context.getRecordedTerminalException());
  }

  @Test
  public void concurrentTerminalRacesConvergeOnOneException() throws Exception {
    QueryContext context = new QueryContext(ArchiveQueryLimits.builder()
        .maxLogicalReads(0)
        .maxBackendReads(0)
        .build());
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    FutureTask<HistoricalQueryLimitException> logical = terminalTask(
        ready, start, context::recordLogicalRead);
    FutureTask<HistoricalQueryLimitException> backend = terminalTask(
        ready, start, context::recordBackendRead);
    Thread logicalThread = new Thread(logical, "query-logical-budget-test");
    Thread backendThread = new Thread(backend, "query-backend-budget-test");
    logicalThread.start();
    backendThread.start();
    assertTrue(ready.await(2, TimeUnit.SECONDS));
    start.countDown();

    HistoricalQueryLimitException logicalFailure = logical.get(2, TimeUnit.SECONDS);
    HistoricalQueryLimitException backendFailure = backend.get(2, TimeUnit.SECONDS);

    assertSame(logicalFailure, backendFailure);
    assertSame(logicalFailure, context.getTerminalException());
  }

  @Test
  public void negativeConsumptionIsRejectedWithoutTerminatingRequest() {
    QueryContext context = new QueryContext(ArchiveQueryLimits.unlimited());

    assertThrows(IllegalArgumentException.class, () -> context.recordLogicalReads(-1));
    assertThrows(IllegalArgumentException.class, () -> context.recordBackendReads(-1));
    assertThrows(IllegalArgumentException.class, () -> context.recordVmSteps(-1));
    assertThrows(IllegalArgumentException.class, () -> context.recordResponseBytes(-1));
    assertThrows(IllegalArgumentException.class,
        () -> context.recordSerializedResponseBytes(-1));

    assertFalse(context.isTerminated());
    assertEquals(0, context.getLogicalReads());
    assertEquals(0, context.getBackendReads());
    assertEquals(0, context.getCacheHits());
    assertEquals(0, context.getVmSteps());
    assertEquals(0, context.getResponseBytes());
  }

  @Test
  public void firstPostAdmissionFailureIsRecordedForLeaseSettlement() {
    QueryContext context = new QueryContext(ArchiveQueryLimits.unlimited());
    ArchiveException first = new ArchiveException("reader failed");

    context.recordFailure(first);
    context.recordFailure(new ArchiveException("close failed"));

    assertSame(first, context.getRecordedFailure());
    assertFalse(context.isTerminated());
  }

  @Test
  public void terminalLimitParticipatesInFirstFailureOrdering() {
    QueryContext limitFirst = new QueryContext(
        ArchiveQueryLimits.builder().maxLogicalReads(0).build());
    HistoricalQueryLimitException limit = assertThrows(
        HistoricalQueryLimitException.class, limitFirst::recordLogicalRead);
    limitFirst.recordFailure(new ArchiveException("cleanup failed"));
    assertSame(limit, limitFirst.getRecordedFailure());

    QueryContext ioFirst = new QueryContext(
        ArchiveQueryLimits.builder().maxLogicalReads(0).build());
    ArchiveException ioFailure = new ArchiveException("reader failed");
    ioFirst.recordFailure(ioFailure);
    assertThrows(HistoricalQueryLimitException.class, ioFirst::recordLogicalRead);
    assertSame(ioFailure, ioFirst.getRecordedFailure());
  }

  @Test
  public void vmTerminalFailureRetainsExactFirstException() {
    QueryContext context = new QueryContext(ArchiveQueryLimits.unlimited());
    RuntimeException first = new RuntimeException("nested archive read unsupported");

    context.recordVmTerminalFailure(first);
    context.recordVmTerminalFailure(new RuntimeException("later nested failure"));

    assertSame(first, context.getRecordedVmTerminalFailure());
    assertSame(first, context.getRecordedFailure());
    assertFalse(context.isTerminated());
  }

  @Test
  public void vmTerminalFailureDoesNotReplaceEarlierMetricFailure() {
    QueryContext context = new QueryContext(ArchiveQueryLimits.unlimited());
    ArchiveException readerFailure = new ArchiveException("reader failed first");
    RuntimeException vmFailure = new RuntimeException("nested archive read unsupported");

    context.recordFailure(readerFailure);
    context.recordVmTerminalFailure(vmFailure);

    assertSame(vmFailure, context.getRecordedVmTerminalFailure());
    assertSame(readerFailure, context.getRecordedFailure());
  }

  @Test
  public void firstExecutionTerminalWinsAcrossBudgetAndVmFailures() {
    QueryContext budgetFirst = new QueryContext(
        ArchiveQueryLimits.builder().maxVmSteps(0L).build());
    HistoricalQueryLimitException budget = assertThrows(
        HistoricalQueryLimitException.class, budgetFirst::recordVmStep);
    RuntimeException laterVm = new RuntimeException("later unsupported state");
    budgetFirst.recordVmTerminalFailure(laterVm);
    assertSame(budget, budgetFirst.getRecordedExecutionTerminalFailure());
    assertSame(budget, budgetFirst.getRecordedFailure());

    QueryContext vmFirst = new QueryContext(
        ArchiveQueryLimits.builder().maxVmSteps(0L).build());
    RuntimeException vm = new RuntimeException("unsupported state first");
    vmFirst.recordVmTerminalFailure(vm);
    HistoricalQueryLimitException laterBudget = assertThrows(
        HistoricalQueryLimitException.class, vmFirst::recordVmStep);
    assertSame(vm, vmFirst.getRecordedExecutionTerminalFailure());
    assertSame(vm, vmFirst.getRecordedFailure());
    assertSame(laterBudget, vmFirst.getRecordedTerminalException());
  }

  @Test
  public void deadlineSnapshotUsesOneTickWithoutRetroactivelyTerminating() {
    AtomicLong now = new AtomicLong(7L);
    QueryContext context = new QueryContext(
        ArchiveQueryLimits.builder().deadlineMs(1L).build(), now::get);
    now.set(1_000_007L);

    QueryContext.DeadlineSnapshot snapshot = context.sampleDeadline();

    assertEquals(1_000_007L, snapshot.getSampledNanos());
    assertEquals(0L, snapshot.getRemainingNanos());
    assertNull(context.getRecordedTerminalException());
    assertThrows(HistoricalQueryLimitException.class, context::checkDeadline);
  }

  @Test
  public void zeroDeadlineTerminatesAtFirstBoundary() {
    AtomicLong now = new AtomicLong(7);
    QueryContext context = new QueryContext(
        ArchiveQueryLimits.builder().deadlineMs(0).build(), now::get);

    HistoricalQueryLimitException failure = assertThrows(
        HistoricalQueryLimitException.class, context::checkDeadline);

    assertEquals(HistoricalQueryLimitException.Reason.DEADLINE, failure.getReason());
    assertTrue(context.isTerminated());
  }

  private static void assertBudgetExceeded(
      ArchiveQueryLimits limits,
      HistoricalQueryLimitException.Limit expectedLimit,
      ContextAction consumeToLimit,
      ContextAction exceedLimit) {
    QueryContext context = new QueryContext(limits);
    consumeToLimit.run(context);

    HistoricalQueryLimitException failure = assertThrows(
        HistoricalQueryLimitException.class, () -> exceedLimit.run(context));

    assertEquals(HistoricalQueryLimitException.Reason.RESOURCE_EXHAUSTED,
        failure.getReason());
    assertEquals(expectedLimit, failure.getLimit());
    assertEquals(2, failure.getConfiguredLimit());
    assertEquals(3, failure.getObserved());
    assertSame(failure, context.getTerminalException());
    assertSame(failure, assertThrows(
        HistoricalQueryLimitException.class, context::throwIfTerminated));
  }

  private static FutureTask<HistoricalQueryLimitException> terminalTask(
      CountDownLatch ready, CountDownLatch start, QueryAction action) {
    return new FutureTask<>(() -> {
      ready.countDown();
      start.await();
      try {
        action.run();
        throw new AssertionError("expected terminal query failure");
      } catch (HistoricalQueryLimitException e) {
        return e;
      }
    });
  }

  private interface ContextAction {

    void run(QueryContext context);
  }

  private interface QueryAction {

    void run();
  }
}
