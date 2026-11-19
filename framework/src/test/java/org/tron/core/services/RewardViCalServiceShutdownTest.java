package org.tron.core.services;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.Uninterruptibles;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Collections;
import java.util.Map.Entry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.tron.common.TestConstants;
import org.tron.common.error.TronDBException;
import org.tron.common.es.ExecutorServiceManager;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.ReflectUtils;
import org.tron.core.config.args.Args;
import org.tron.core.db.common.iterator.DBIterator;
import org.tron.core.db2.common.DB;
import org.tron.core.exception.TronError;
import org.tron.core.service.RewardViCalService;
import org.tron.core.store.DelegationStore;
import org.tron.core.store.DynamicPropertiesStore;
import org.tron.core.store.RewardViStore;
import org.tron.core.store.WitnessStore;

public class RewardViCalServiceShutdownTest {

  private static final int ENTRY_COUNT = 3;

  @Rule
  public Timeout timeout = Timeout.seconds(60);

  @BeforeClass
  public static void setUp() {
    Args.setParam(new String[0], TestConstants.TEST_CONF);
  }

  @AfterClass
  public static void tearDown() {
    Args.clearParam();
  }

  @Test
  public void destroyWaitsUntilMerkleRootPassReleasesStore() throws Exception {
    BlockingIterator iterator = new BlockingIterator();
    RewardViCalService service = newService(merkleRootStore(iterator), emptyDb());

    assertDestroyWaitsForIterator(service, iterator, false);
  }

  @Test
  public void destroyKeepsWaitingWhenCallerIsInterrupted() throws Exception {
    BlockingIterator iterator = new BlockingIterator();
    RewardViCalService service = newService(merkleRootStore(iterator), emptyDb());

    assertDestroyWaitsForIterator(service, iterator, true);
  }

  @Test
  public void destroyStopsRewardCalculationWithoutMarkingDone() throws Exception {
    BlockingIterator iterator = new BlockingIterator();
    DB<byte[], byte[]> witnessDb = emptyDb();
    when(witnessDb.iterator()).thenReturn(iterator);
    RewardViStore rewardViStore = mock(RewardViStore.class);

    RewardViCalService service = newService(rewardViStore, witnessDb);

    assertDestroyWaitsForIterator(service, iterator, false);
    verify(rewardViStore, never()).get(any());
    verify(rewardViStore, never()).put(any(), any());
    Assert.assertThrows(TronDBException.class, () -> queryReward(service));
  }

  @Test
  public void stoppedCalculationFailsRewardQueriesThatWereWaiting() throws Exception {
    BlockingIterator iterator = new BlockingIterator();
    DB<byte[], byte[]> witnessDb = emptyDb();
    when(witnessDb.iterator()).thenReturn(iterator);
    RewardViCalService service = newService(mock(RewardViStore.class), witnessDb);
    ExecutorService pool = ExecutorServiceManager.newSingleThreadExecutor("reward-vi-waiter");
    try {
      Future<Long> waiter = pool.submit(() -> queryReward(service));
      Assert.assertThrows(TimeoutException.class, () -> waiter.get(300, TimeUnit.MILLISECONDS));

      assertDestroyWaitsForIterator(service, iterator, false);

      assertFailsWith(TronDBException.class, waiter);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  public void failedCalculationFailsRewardQueriesThatWereWaiting() throws Exception {
    RewardViStore rewardViStore = mock(RewardViStore.class);
    doThrow(new TronDBException("reset failed")).when(rewardViStore).reset();
    RewardViCalService service = newService(rewardViStore, emptyDb());
    ReflectUtils.setFieldValue(service, "lastBlockNumber", Long.MAX_VALUE);
    ExecutorService pool = ExecutorServiceManager.newSingleThreadExecutor("reward-vi-waiter");
    try {
      Future<Long> waiter = pool.submit(() -> queryReward(service));
      Assert.assertThrows(TimeoutException.class, () -> waiter.get(300, TimeUnit.MILLISECONDS));

      TronError error = Assert.assertThrows(TronError.class,
          () -> ReflectUtils.invokeMethod(service, "maybeRun"));

      Assert.assertEquals(TronError.ErrCode.REWARD_VI_CALCULATOR, error.getErrCode());
      assertFailsWith(TronDBException.class, waiter);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  public void interruptAfterReadsCancelsMerkleRootComputation() {
    InterruptOnCloseIterator iterator = new InterruptOnCloseIterator();
    RewardViCalService service = newService(merkleRootStore(iterator), emptyDb());

    boolean interrupted;
    try {
      ReflectUtils.invokeMethod(service, "maybeRun");
    } finally {
      interrupted = Thread.interrupted();
    }

    Assert.assertTrue(interrupted);
    Assert.assertEquals(ENTRY_COUNT, iterator.nextCalls.get());
    ScheduledExecutorService es = ReflectUtils.getFieldValue(service, "es");
    Assert.assertFalse("calcMerkleRoot finished after the interrupt", es.isShutdown());
  }

  private static void assertDestroyWaitsForIterator(RewardViCalService service,
      BlockingIterator iterator, boolean interruptCaller) throws Exception {
    ExecutorService stopper = ExecutorServiceManager.newSingleThreadExecutor("reward-vi-stopper");
    try {
      service.init();
      Assert.assertTrue("rewardViCalService did not start reading",
          iterator.reading.await(10, TimeUnit.SECONDS));

      Future<Boolean> stopped = stopper.submit(() -> {
        ReflectUtils.invokeMethod(service, "destroy");
        return Thread.currentThread().isInterrupted();
      });
      Assert.assertTrue("worker was not interrupted",
          iterator.interrupted.await(10, TimeUnit.SECONDS));
      if (interruptCaller) {
        stopper.shutdownNow();
      }
      Assert.assertThrows("destroy returned while the store was still being read",
          TimeoutException.class, () -> stopped.get(500, TimeUnit.MILLISECONDS));

      iterator.release.countDown();
      Assert.assertEquals("caller interrupt status was not preserved",
          interruptCaller, stopped.get(10, TimeUnit.SECONDS));

      ScheduledExecutorService es = ReflectUtils.getFieldValue(service, "es");
      Assert.assertTrue(es.isTerminated());
      Assert.assertTrue("iterator was not closed", iterator.closed.get());
      Assert.assertEquals("iteration continued after interrupt", 1, iterator.nextCalls.get());
    } finally {
      iterator.release.countDown();
      stopper.shutdownNow();
    }
  }

  private static long queryReward(RewardViCalService service) {
    return service.getNewRewardAlgorithmReward(1, 2, Collections.emptyList());
  }

  private static void assertFailsWith(Class<? extends Throwable> expected, Future<?> future) {
    ExecutionException failure = Assert.assertThrows(ExecutionException.class,
        () -> future.get(10, TimeUnit.SECONDS));
    Assert.assertTrue(String.valueOf(failure.getCause()), expected.isInstance(failure.getCause()));
  }

  private static RewardViStore merkleRootStore(DBIterator iterator) {
    RewardViStore rewardViStore = mock(RewardViStore.class);
    when(rewardViStore.has(any())).thenReturn(true);
    when(rewardViStore.iterator()).thenReturn(iterator);
    return rewardViStore;
  }

  @SuppressWarnings("unchecked")
  private static DB<byte[], byte[]> emptyDb() {
    return mock(DB.class);
  }

  private static RewardViCalService newService(RewardViStore rewardViStore,
      DB<byte[], byte[]> witnessDb) {
    DB<byte[], byte[]> propertiesDb = emptyDb();
    when(propertiesDb.get(any())).thenReturn(ByteArray.fromLong(5));
    DynamicPropertiesStore propertiesStore = mock(DynamicPropertiesStore.class);
    when(propertiesStore.getDb()).thenReturn(propertiesDb);
    DelegationStore delegationStore = mock(DelegationStore.class);
    when(delegationStore.getDb()).thenReturn(emptyDb());
    WitnessStore witnessStore = mock(WitnessStore.class);
    when(witnessStore.getDb()).thenReturn(witnessDb);

    RewardViCalService service =
        new RewardViCalService(propertiesStore, delegationStore, witnessStore);
    ReflectUtils.setFieldValue(service, "rewardViStore", rewardViStore);
    return service;
  }

  private static final class BlockingIterator extends StubIterator {

    private final CountDownLatch reading = new CountDownLatch(1);
    private final CountDownLatch interrupted = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    @Override
    protected void onNext(int index) {
      if (index != 1) {
        return;
      }
      reading.countDown();
      try {
        release.await();
      } catch (InterruptedException e) {
        interrupted.countDown();
        Uninterruptibles.awaitUninterruptibly(release);
        Thread.currentThread().interrupt();
      }
    }
  }

  private static final class InterruptOnCloseIterator extends StubIterator {

    @Override
    public void close() {
      super.close();
      Thread.currentThread().interrupt();
    }
  }

  private abstract static class StubIterator implements DBIterator {

    protected final AtomicInteger nextCalls = new AtomicInteger();
    protected final AtomicBoolean closed = new AtomicBoolean();

    protected void onNext(int index) {
    }

    @Override
    public boolean hasNext() {
      return !closed.get() && nextCalls.get() < ENTRY_COUNT;
    }

    @Override
    public Entry<byte[], byte[]> next() {
      int index = nextCalls.incrementAndGet();
      onNext(index);
      return new SimpleImmutableEntry<>(ByteArray.fromInt(index), ByteArray.fromInt(index));
    }

    @Override
    public void close() {
      closed.set(true);
    }

    @Override
    public void seek(byte[] key) {
    }

    @Override
    public void seekToFirst() {
    }

    @Override
    public void seekToLast() {
    }

    @Override
    public boolean valid() {
      return hasNext();
    }

    @Override
    public byte[] getKey() {
      throw new UnsupportedOperationException();
    }

    @Override
    public byte[] getValue() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void checkState() {
    }
  }
}
