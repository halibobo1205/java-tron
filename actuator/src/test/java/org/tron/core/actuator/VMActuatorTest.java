package org.tron.core.actuator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import org.junit.Test;
import org.tron.common.runtime.ProgramResult;
import org.tron.core.db.TransactionContext;
import org.tron.core.vm.archive.UnsupportedHistoricalStateException;
import org.tron.core.vm.repository.Repository;

public class VMActuatorTest {

  @Test
  public void testConstantCallUsesConfiguredTimeoutVerbatim() {
    assertEquals(123_000L, VMActuator.calculateCpuLimitInUs(true, 80L, 5.0, 123L));
  }

  @Test
  public void testConstantCallWithoutConfiguredTimeoutUsesNetworkDeadline() {
    assertEquals(400_000L, VMActuator.calculateCpuLimitInUs(true, 80L, 5.0, 0L));
  }

  @Test
  public void testNonConstantCallIgnoresConfiguredTimeout() {
    assertEquals(400_000L, VMActuator.calculateCpuLimitInUs(false, 80L, 5.0, 123L));
  }

  @Test
  public void archiveUnsupportedStateEscapesRuntimeCatch() throws Exception {
    VMActuator actuator = new VMActuator(true);
    Repository archiveRepository = mock(Repository.class);
    UnsupportedHistoricalStateException failure =
        new UnsupportedHistoricalStateException("archive unsupported");
    doThrow(failure).when(archiveRepository).commit();
    setField(actuator, "injectedRootRepository", archiveRepository);
    setField(actuator, "rootRepository", archiveRepository);
    TransactionContext context = mock(TransactionContext.class);
    when(context.getProgramResult()).thenReturn(new ProgramResult());

    UnsupportedHistoricalStateException thrown = assertThrows(
        UnsupportedHistoricalStateException.class, () -> actuator.execute(context));

    assertSame(failure, thrown);
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }
}
