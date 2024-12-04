package org.tron.common.exit;

import java.util.Arrays;
import java.util.concurrent.ThreadFactory;

import com.google.common.base.Strings;
import lombok.extern.slf4j.Slf4j;

@Slf4j(topic = "Exit")
public class ExitManager {

  private static final ExitManager INSTANCE = new ExitManager();
  private static final String[] CI_ENVIRONMENT_VARIABLES = {
      "CI",
      "JENKINS_URL",
      "TRAVIS",
      "CIRCLECI",
      "GITHUB_ACTIONS",
      "GITLAB_CI"
  };

  private final ThreadFactory exitThreadFactory = r -> {
    Thread thread = new Thread(r, "System-Exit-Thread");
    thread.setDaemon(true);
    return thread;
  };

  private ExitManager() {
  }

  public static ExitManager getInstance() {
    return INSTANCE;
  }

  public void exit(ExitReason reason) {
    exit(reason, reason.getDescription());
  }

  public void exit(ExitReason reason, String message) {
    exit(reason, message, null);
  }

  public void exit(ExitReason reason, Throwable cause) {
    exit(reason, cause.getMessage(), cause);
  }

  public void exit(ExitReason reason, String message, Throwable cause) {
    TronExitException exitException = new TronExitException(reason, message, cause);
    if (isRunningInCI()) {
      if (exitException.getExitReason() != ExitReason.NORMAL_SHUTDOWN) {
        throw exitException;
      }
    } else {
      logAndExit(exitException);
    }
  }

  private boolean isRunningInCI() {
    return Arrays.stream(CI_ENVIRONMENT_VARIABLES).anyMatch(System.getenv()::containsKey);
  }

  private void logAndExit(TronExitException exception) {
    ExitReason reason = exception.getExitReason();
    String message = exception.getMessage();
    if (reason == ExitReason.NORMAL_SHUTDOWN) {
      if (!Strings.isNullOrEmpty(message)) {
        logger.info("Exiting with code: {}, {}, {}.",
            reason.getCode(), reason.getDescription(), message);
      }
    } else {
      logger.error("Exiting with code: {}, {}, {}.", reason.getCode(), reason.getDescription(),
          message, exception);
    }
    Thread exitThread = exitThreadFactory.newThread(() -> System.exit(reason.getCode()));
    exitThread.start();
  }
}