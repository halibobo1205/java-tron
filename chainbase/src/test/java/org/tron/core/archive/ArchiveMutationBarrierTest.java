package org.tron.core.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class ArchiveMutationBarrierTest {

  @Test
  public void exclusiveLeaseAdvancesEpochAndWaitsForSharedLease() throws Exception {
    ArchiveMutationBarrier barrier = new ArchiveMutationBarrier();
    ArchiveMutationLease shared = barrier.acquireShared();
    assertEquals(0, shared.getEpoch());

    CountDownLatch attempted = new CountDownLatch(1);
    FutureTask<Long> exclusiveEpoch = new FutureTask<>(() -> {
      attempted.countDown();
      try (ArchiveMutationLease exclusive = barrier.acquireExclusive()) {
        assertTrue(exclusive.isExclusive());
        return exclusive.getEpoch();
      }
    });
    Thread thread = new Thread(exclusiveEpoch);
    thread.start();
    assertTrue(attempted.await(1, TimeUnit.SECONDS));
    assertFalse(exclusiveEpoch.isDone());

    shared.close();
    assertEquals(Long.valueOf(1), exclusiveEpoch.get(1, TimeUnit.SECONDS));
    assertEquals(1, barrier.getEpoch());
  }

  @Test
  public void currentThreadMustHoldLeaseAndForeignCloseDoesNotUnlock() throws Exception {
    ArchiveMutationBarrier barrier = new ArchiveMutationBarrier();
    assertThrows(ArchiveException.class, barrier::requireHeldByCurrentThread);
    ArchiveMutationLease shared = barrier.acquireShared();
    barrier.requireHeldByCurrentThread();

    FutureTask<Throwable> foreignClose = new FutureTask<>(
        () -> assertThrows(ArchiveException.class, shared::close));
    Thread thread = new Thread(foreignClose);
    thread.start();
    assertTrue(foreignClose.get(1, TimeUnit.SECONDS) instanceof ArchiveException);
    barrier.requireHeldByCurrentThread();
    shared.close();
    shared.close();
  }
}
