package org.tron.common.exit;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import lombok.extern.slf4j.Slf4j;
import org.tron.core.exception.TronExitException;

@Slf4j(topic = "Exit")
public class ExitManager {

  private static final String[] CI_ENVIRONMENT_VARIABLES = {
      "CI",
      "JENKINS_URL",
      "TRAVIS",
      "CIRCLECI",
      "GITHUB_ACTIONS",
      "GITLAB_CI"
  };

  private static final int EXIT_CODE_NORMAL = 0;

  private static final ThreadFactory exitThreadFactory = r -> {
    Thread thread = new Thread(r, "System-Exit-Thread");
    thread.setDaemon(true);
    return thread;
  };

  private ExitManager() {
  }

  public static void exit() {
    exit((String) null);
  }

  public static void exit(String msg) {
    exit(msg, null);
  }

  public static void exit(TronExitException cause) {
    exit(cause.getMessage(), cause);
  }

  public static void exit(String msg, TronExitException cause) {
    TronExit exit = new TronExit(msg, cause);
    if (isRunningInCI()) {
      if (Objects.nonNull(cause)) {
        throw cause;
      } else if (Objects.nonNull(msg)) {
        logger.info("{}", msg);
      }
    } else {
      logAndExit(exit);
    }
  }

  private static boolean isRunningInCI() {
    return Arrays.stream(CI_ENVIRONMENT_VARIABLES).anyMatch(System.getenv()::containsKey);
  }

  private static void logAndExit(TronExit exit) {
    String msg = exit.getMsg();
    TronExitException cause = exit.getException();
    final int code = Objects.isNull(cause) ? EXIT_CODE_NORMAL : cause.getExitCode();
    if (code == EXIT_CODE_NORMAL) {
      if (Objects.nonNull(msg)) {
        logger.info("Exiting, {}.", msg);
      }
    } else {
      if (Objects.isNull(msg)) {
        logger.error("Exiting with code: {}.", code, cause);
      } else {
        logger.error("Exiting with code: {}, {}.", code, msg, cause);
      }
    }
    Thread exitThread = exitThreadFactory.newThread(() -> System.exit(code));
    exitThread.start();
  }
}