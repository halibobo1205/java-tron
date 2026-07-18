package org.tron.core.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class ArchiveFatalControllerTest {

  @Test
  public void firstFailureIsDeliveredOnceEvenWhenHandlerIsInstalledLater() throws Exception {
    ArchiveFatalController controller = new ArchiveFatalController("archive-fatal-test");
    ArchiveException first = new ArchiveException("first");
    controller.signal(first);
    controller.signal(new ArchiveException("second"));

    AtomicInteger calls = new AtomicInteger();
    CountDownLatch delivered = new CountDownLatch(1);
    controller.setHandler(failure -> {
      assertSame(first, failure);
      calls.incrementAndGet();
      delivered.countDown();
    });

    assertTrue(delivered.await(1, TimeUnit.SECONDS));
    assertEquals(1, calls.get());
    assertSame(first, controller.getFailure());
    controller.close();
  }

  @Test
  public void armedFailureWaitsForDurableBarrierBeforeCallbackDelivery() throws Exception {
    ArchiveFatalController controller = new ArchiveFatalController("archive-fatal-barrier-test");
    CountDownLatch delivered = new CountDownLatch(1);
    controller.setHandler(failure -> delivered.countDown());

    controller.arm(new ArchiveException("armed"));

    assertTrue("callback escaped the durable barrier",
        !delivered.await(30, TimeUnit.MILLISECONDS));
    controller.deliver();
    assertTrue(delivered.await(1, TimeUnit.SECONDS));
    controller.close();
  }

  @Test
  public void closeDoesNotWaitForRunningApplicationCallback() throws Exception {
    ArchiveFatalController controller = new ArchiveFatalController("archive-fatal-close-test");
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    controller.setHandler(failure -> {
      entered.countDown();
      try {
        release.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    controller.signal(new ArchiveException("fatal"));
    assertTrue(entered.await(1, TimeUnit.SECONDS));

    long started = System.nanoTime();
    controller.close();
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    release.countDown();
    assertTrue("close waited for callback", elapsedMs < 500);
  }

  @Test
  public void closeCannotCancelFatalBeforeDeliveryStarts() throws Exception {
    ArchiveFatalController controller = new ArchiveFatalController(
        "archive-fatal-pending-close-test");
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch returned = new CountDownLatch(1);
    controller.setHandler(failure -> {
      entered.countDown();
      try {
        release.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        returned.countDown();
      }
    });

    controller.signal(new ArchiveException("fatal"));
    controller.close();

    Field deliveryStarted = ArchiveFatalController.class.getDeclaredField("deliveryStarted");
    deliveryStarted.setAccessible(true);
    assertTrue("close returned before control thread claimed fatal delivery",
        deliveryStarted.getBoolean(controller));
    release.countDown();
    assertTrue(entered.await(1, TimeUnit.SECONDS));
    assertTrue(returned.await(1, TimeUnit.SECONDS));
    controller.close();
  }

  @Test
  public void watchdogTerminatesWhenFatalHandlerDoesNotCompleteShutdown() throws Exception {
    CountDownLatch terminated = new CountDownLatch(1);
    AtomicInteger status = new AtomicInteger();
    ArchiveFatalController controller = new ArchiveFatalController(
        "archive-fatal-watchdog-test", 20L, value -> {
          status.set(value);
          terminated.countDown();
        });
    controller.setHandler(failure -> {
      // Returning without closing the controller simulates a stuck or ineffective shutdown path.
    });

    controller.signal(new ArchiveException("fatal"));

    assertTrue(terminated.await(1, TimeUnit.SECONDS));
    assertEquals(70, status.get());
    controller.close();
  }

  @Test
  public void watchdogTerminatesWhenFatalMessageCannotBeRendered() throws Exception {
    CountDownLatch terminated = new CountDownLatch(1);
    AtomicInteger status = new AtomicInteger();
    ArchiveFatalController controller = new ArchiveFatalController(
        "archive-fatal-hostile-message-test", 20L, value -> {
          status.set(value);
          terminated.countDown();
        });
    controller.setHandler(failure -> {
      // Returning without closing keeps the watchdog armed.
    });

    controller.signal(new HostileMessageException());

    assertTrue(terminated.await(1, TimeUnit.SECONDS));
    assertEquals(70, status.get());
    controller.close();
  }

  private static final class HostileMessageException extends ArchiveException {

    private HostileMessageException() {
      super("unrenderable");
    }

    @Override
    public String getMessage() {
      throw new AssertionError("message rendering failed");
    }
  }
}
