package org.tron.core.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedConstruction;
import org.mockito.junit.MockitoJUnitRunner;
import org.reflections.Reflections;
import org.tron.core.actuator.AbstractActuator;
import org.tron.core.actuator.TransferActuator;
import org.tron.core.config.args.Args;
import org.tron.core.exception.TronError;

@RunWith(MockitoJUnitRunner.class)
public class TransactionRegisterTest {

  @Before
  public void init() throws Exception {
    Args.getInstance().setActuatorSet(new HashSet<>());
    resetRegisteredField();
  }

  @After
  public void destroy() {
    Args.clearParam();
  }

  private void resetRegisteredField() throws Exception {
    Field registeredField = TransactionRegister.class.getDeclaredField("REGISTERED");
    registeredField.setAccessible(true);
    AtomicBoolean registered = (AtomicBoolean) registeredField.get(null);
    registered.set(false);
  }

  @Test
  public void testAlreadyRegisteredSkipRegistration() throws Exception {

    TransactionRegister.registerActuator();

    Field registeredField = TransactionRegister.class.getDeclaredField("REGISTERED");
    registeredField.setAccessible(true);
    AtomicBoolean registered = (AtomicBoolean) registeredField.get(null);
    assertTrue("First registration should be completed", registered.get());

    TransactionRegister.registerActuator();
    assertTrue("Registration should still be true", registered.get());
  }

  @Test
  public void testConcurrentAccessThreadSafe() throws Exception {
    final int threadCount = 5;
    Thread[] threads = new Thread[threadCount];
    final AtomicBoolean testPassed = new AtomicBoolean(true);

    for (int i = 0; i < threadCount; i++) {
      threads[i] = new Thread(() -> {
        try {
          TransactionRegister.registerActuator();
        } catch (Exception e) {
          testPassed.set(false);
        }
      });
    }

    for (Thread thread : threads) {
      thread.start();
    }

    for (Thread thread : threads) {
      thread.join();
    }

    assertTrue("All threads should complete successfully", testPassed.get());

    Field registeredField = TransactionRegister.class.getDeclaredField("REGISTERED");
    registeredField.setAccessible(true);
    AtomicBoolean registered = (AtomicBoolean) registeredField.get(null);
    assertTrue("Registration should be completed", registered.get());
  }

  @Test
  public void testDoubleCheckLockingAtomicBoolean() throws Exception {
    Field registeredField = TransactionRegister.class.getDeclaredField("REGISTERED");
    registeredField.setAccessible(true);
    AtomicBoolean registered = (AtomicBoolean) registeredField.get(null);

    assertFalse("Initial registration state should be false", registered.get());

    TransactionRegister.registerActuator();
    assertTrue("After first call, should be registered", registered.get());

    TransactionRegister.registerActuator();
    assertTrue("After second call, should still be registered", registered.get());
  }

  @Test
  public void testSynchronizationBlock() throws Exception {
    final AtomicBoolean completedRegistration = new AtomicBoolean(false);

    Thread registrationThread = new Thread(() -> {
      TransactionRegister.registerActuator();
      completedRegistration.set(true);

    });

    registrationThread.start();
    registrationThread.join();

    assertTrue("Registration should have completed", completedRegistration.get());

    Field registeredField = TransactionRegister.class.getDeclaredField("REGISTERED");
    registeredField.setAccessible(true);
    AtomicBoolean registered = (AtomicBoolean) registeredField.get(null);
    assertTrue("Should be registered after completion", registered.get());
  }

  @Test
  public void testMultipleCallsConsistency() throws Exception {
    Field registeredField = TransactionRegister.class.getDeclaredField("REGISTERED");
    registeredField.setAccessible(true);
    AtomicBoolean registered = (AtomicBoolean) registeredField.get(null);

    assertFalse("Should start unregistered", registered.get());

    TransactionRegister.registerActuator();

    assertTrue("Should be registered after first call", registered.get());

    for (int i = 0; i < 5; i++) {
      TransactionRegister.registerActuator();
      assertTrue("Should remain registered after call " + (i + 2), registered.get());
    }
  }

  @Test
  public void testThrowsTronError() {
    try (MockedConstruction<Reflections> ignored = mockConstruction(Reflections.class,
        (mock, context) -> when(mock.getSubTypesOf(AbstractActuator.class))
            .thenReturn(Collections.singleton(TransferActuator.class)));
         MockedConstruction<TransferActuator> ignored1 = mockConstruction(TransferActuator.class,
             (mock, context) -> {
               throw new RuntimeException("boom");
             })) {
      TronError error = assertThrows(TronError.class, TransactionRegister::registerActuator);
      assertEquals(TronError.ErrCode.ACTUATOR_REGISTER, error.getErrCode());
      assertTrue(error.getMessage().contains("TransferActuator"));
    }
  }
}
