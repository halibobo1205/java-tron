package org.tron.core.db;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.tron.common.logsfilter.EventPluginLoader;
import org.tron.common.utils.ReflectUtils;
import org.tron.common.utils.SessionOptional;
import org.tron.core.ChainBaseManager;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.ArchiveService;

public class ManagerArchiveShutdownTest {

  @Test
  public void archiveDrainFailurePreservesEveryDependentStore() {
    Manager manager = spy(new Manager());
    ArchiveService archive = mock(ArchiveService.class);
    ChainBaseManager chainBase = mock(ChainBaseManager.class);
    RevokingDatabase revoking = mock(RevokingDatabase.class);
    SessionOptional session = mock(SessionOptional.class);
    ArchiveException failure = new ArchiveException("active reader did not drain");
    doThrow(failure).when(archive).close();
    doNothing().when(manager).stopRePushThread();
    doNothing().when(manager).stopRePushTriggerThread();
    doNothing().when(manager).stopFilterProcessThread();
    doNothing().when(manager).stopValidateSignThread();
    ReflectUtils.setFieldValue(manager, "archiveService", archive);
    ReflectUtils.setFieldValue(manager, "chainBaseManager", chainBase);
    ReflectUtils.setFieldValue(manager, "revokingStore", revoking);
    ReflectUtils.setFieldValue(manager, "session", session);

    EventPluginLoader plugin = mock(EventPluginLoader.class);
    try (MockedStatic<EventPluginLoader> loader = mockStatic(EventPluginLoader.class)) {
      when(EventPluginLoader.getInstance()).thenReturn(plugin);

      assertSame(failure, assertThrows(ArchiveException.class, manager::close));
    }

    verify(chainBase, never()).shutdown();
    verify(revoking, never()).shutdown();
    verify(session, never()).reset();
  }
}
