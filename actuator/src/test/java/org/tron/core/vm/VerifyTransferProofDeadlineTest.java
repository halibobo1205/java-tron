package org.tron.core.vm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.Test;
import org.tron.core.archive.query.ArchiveQueryLimits;
import org.tron.core.archive.query.HistoricalQueryLimitException;
import org.tron.core.archive.query.QueryContext;
import org.tron.core.archive.query.QueryContextHolder;
import org.tron.core.vm.PrecompiledContracts.VerifyTransferProof;
import org.tron.core.vm.program.Program.OutOfTimeException;

public class VerifyTransferProofDeadlineTest {

  @Test
  public void historicalProofWaitFailsAtDeadlineWithoutUnboundedFutureGet() throws Exception {
    TestVerifyTransferProof proof = new TestVerifyTransferProof();
    Future<Boolean> future = future();

    assertThrows(OutOfTimeException.class,
        () -> proof.awaitHistoricalProofTasks(
            new CountDownLatch(1), Collections.singletonList(future)));

    verify(future, never()).get();
    verify(future, never()).get(anyLong(), eq(TimeUnit.NANOSECONDS));
  }

  @Test
  public void historicalProofCollectionUsesOnlyTimedFutureGet() throws Exception {
    TestVerifyTransferProof proof = new TestVerifyTransferProof();
    Future<Boolean> future = future();
    when(future.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenReturn(true);

    assertTrue(proof.awaitHistoricalProofTasks(
        new CountDownLatch(0), Collections.singletonList(future)));

    verify(future, never()).get();
    verify(future).get(anyLong(), eq(TimeUnit.NANOSECONDS));
  }

  @Test
  public void archiveDeadlineBoundsHistoricalProofWait() {
    TestVerifyTransferProof proof = new TestVerifyTransferProof();
    QueryContext context = new QueryContext(
        ArchiveQueryLimits.builder().deadlineMs(0L).build());

    try (QueryContextHolder.Scope ignored = QueryContextHolder.attach(context)) {
      assertThrows(HistoricalQueryLimitException.class,
          () -> proof.awaitHistoricalProofTasks(
              new CountDownLatch(1), Collections.singletonList(future())));
    }
  }

  @Test
  public void historicalCleanupCancelsOnlyUnfinishedTasks() {
    Future<Boolean> unfinished = future();
    Future<Boolean> completed = future();
    when(unfinished.isDone()).thenReturn(false);
    when(completed.isDone()).thenReturn(true);
    List<Future<Boolean>> futures = java.util.Arrays.asList(unfinished, completed);

    VerifyTransferProof.cancelUnfinished(futures);

    verify(unfinished).cancel(true);
    verify(completed, never()).cancel(true);
    assertFalse(Thread.currentThread().isInterrupted());
  }

  @Test
  public void historicalQueueSaturationIsAQueryResourceFailure() {
    HistoricalQueryLimitException failure = assertThrows(
        HistoricalQueryLimitException.class,
        () -> VerifyTransferProof.rethrowHistoricalHardFailure(
            true, new RejectedExecutionException("full")));

    assertTrue(failure.getMessage().contains("proof worker capacity"));
    assertTrue(failure.getCause() instanceof RejectedExecutionException);
  }

  @Test
  public void timedFutureRaceRechecksArchiveDeadline() {
    QueryContext context = new QueryContext(
        ArchiveQueryLimits.builder().deadlineMs(0L).build());

    try (QueryContextHolder.Scope ignored = QueryContextHolder.attach(context)) {
      HistoricalQueryLimitException failure = assertThrows(
          HistoricalQueryLimitException.class,
          () -> VerifyTransferProof.historicalProofTimeout(new TimeoutException("late"), true));

      assertEquals(HistoricalQueryLimitException.Limit.DEADLINE, failure.getLimit());
      assertSame(failure, context.getTerminalException());
    }
  }

  @Test
  public void earlierVmDeadlineIsNotReclassifiedAfterArchiveDeadlineAlsoExpires() {
    QueryContext context = new QueryContext(
        ArchiveQueryLimits.builder().deadlineMs(0L).build());
    TimeoutException nativeTimeout = new TimeoutException("VM deadline won");

    try (QueryContextHolder.Scope ignored = QueryContextHolder.attach(context)) {
      OutOfTimeException failure = VerifyTransferProof.historicalProofTimeout(
          nativeTimeout, false);

      assertSame(nativeTimeout, failure.getSuppressed()[0]);
      assertNull(context.getRecordedTerminalException());
    }
  }

  @Test
  public void interruptedHistoricalWaitIsAQueryFailure() {
    InterruptedException interrupted = new InterruptedException("stop");

    HistoricalQueryLimitException failure = assertThrows(
        HistoricalQueryLimitException.class,
        () -> VerifyTransferProof.rethrowHistoricalInterrupt(true, interrupted));

    assertEquals(HistoricalQueryLimitException.Limit.INTERRUPTED, failure.getLimit());
    assertSame(interrupted, failure.getCause());
  }

  @SuppressWarnings("unchecked")
  private static Future<Boolean> future() {
    return mock(Future.class);
  }

  private static final class TestVerifyTransferProof extends VerifyTransferProof {

    @Override
    protected long getCPUTimeLeftInNanoSecond() {
      return 1L;
    }

    @Override
    protected long getCPUTimeRemainingInNanoSecond(long sampledNanos) {
      return 1L;
    }
  }
}
