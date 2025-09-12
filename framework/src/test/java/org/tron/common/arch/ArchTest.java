package org.tron.common.arch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tron.core.exception.TronError;

@RunWith(MockitoJUnitRunner.class)
public class ArchTest {

  private void runArchTest(boolean isX86, boolean isJava8, boolean expectThrow) {
    try (MockedStatic<Arch> mocked = mockStatic(Arch.class);
         MockedStatic<LoggerFactory> logger = mockStatic(LoggerFactory.class)) {
      Logger mockLogger = mock(Logger.class);
      logger.when(() -> LoggerFactory.getLogger(Arch.class)).thenReturn(mockLogger);

      mocked.when(Arch::isX86).thenReturn(isX86);
      mocked.when(Arch::isJava8).thenReturn(isJava8);
      mocked.when(Arch::getOsArch).thenReturn("x86_64");
      mocked.when(Arch::javaSpecificationVersion).thenReturn("17");
      mocked.when(Arch::withAll).thenReturn("");
      mocked.when(Arch::throwIfUnsupportedJavaVersion).thenCallRealMethod();

      if (expectThrow) {
        assertEquals(TronError.ErrCode.JDK_VERSION,
            assertThrows(TronError.class, Arch::throwIfUnsupportedJavaVersion).getErrCode());
      } else {
        Arch.throwIfUnsupportedJavaVersion();
      }
    }
  }

  @Test
  public void testThrowIfUnsupportedJavaVersion() {
    runArchTest(true, false, true);
    runArchTest(true, true, false);
    runArchTest(false, false, false);
  }

  @Test
  public void TestThrowIfUnsupportedArm64Exception() {
    try (MockedStatic<Arch> mocked = mockStatic(Arch.class, CALLS_REAL_METHODS);
         MockedStatic<LoggerFactory> logger = mockStatic(LoggerFactory.class)) {
      Logger mockLogger = mock(Logger.class);
      logger.when(() -> LoggerFactory.getLogger(Arch.class)).thenReturn(mockLogger);

      mocked.when(Arch::isArm64).thenReturn(true);
      mocked.when(Arch::getOsArch).thenReturn("aarch64");

      assertThrows(UnsupportedOperationException.class,
          () -> Arch.throwIfUnsupportedArm64Exception("test") );
    }
  }

  @Test
  public void testWithAll() {
    Arch.withAll();
  }
}
