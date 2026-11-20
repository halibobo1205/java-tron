package org.tron.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import org.tron.common.es.ExecutorServiceManager;
import org.tron.common.log.layout.MultiLayoutPattern;

public class MultiLayoutPatternTest {

  private static final String POOL_NAME = "multi-layout-encode";

  private MultiLayoutPattern multiLayoutPattern;
  private LoggerContext context;

  @Before
  public void setUp() {
    context = new LoggerContext();
    multiLayoutPattern = new MultiLayoutPattern();
    multiLayoutPattern.setContext(context);

    MultiLayoutPattern.Rule rule1 = new MultiLayoutPattern.Rule();
    rule1.setLogger("com.example.app1");
    assertNotNull(rule1.getLogger());
    rule1.setPattern("%date [%thread] %-5level %logger{36} - %msg%n");
    assertNotNull(rule1.getPattern());
    rule1.setOutputPatternAsHeader(true);
    assertTrue(rule1.isOutputPatternAsHeader());
    multiLayoutPattern.addRule(rule1);

    MultiLayoutPattern.Rule rule2 = new MultiLayoutPattern.Rule();
    rule2.setLogger("com.example.app2");
    rule2.setPattern("%msg%n");
    multiLayoutPattern.addRule(rule2);

    multiLayoutPattern.start();
  }

  @Test
  public void testEncodeForSpecificLogger() {
    ILoggingEvent event1 = createLoggingEvent("com.example.app1", "Test message 1");
    byte[] encoded1 = multiLayoutPattern.encode(event1);
    String result1 = new String(encoded1);
    assertTrue(result1.contains("Test message 1"));

    ILoggingEvent event2 = createLoggingEvent("com.example.app2", "Test message 2");
    byte[] encoded2 = multiLayoutPattern.encode(event2);
    String result2 = new String(encoded2);
    assertEquals("Test message 2\n", result2);
  }

  @Test
  public void testEncodeForRootLogger() {
    ILoggingEvent event = createLoggingEvent(Logger.ROOT_LOGGER_NAME, "Root logger message");
    byte[] encoded = multiLayoutPattern.encode(event);
    String result = new String(encoded);
    assertFalse(result.contains("Root logger message"));
  }

  @Test
  public void testConcurrentEncode() throws Exception {
    List<String> names = sameHashNames(7);
    int threads = 16;
    ExecutorService pool = ExecutorServiceManager.newFixedThreadPool(POOL_NAME, threads, true);
    try {
      for (int round = 0; round < 50; round++) {
        encodeConcurrently(pool, threads, names, round);
      }
    } finally {
      ExecutorServiceManager.shutdownAndAwaitTermination(pool, POOL_NAME);
    }
  }

  private void encodeConcurrently(ExecutorService pool, int threads, List<String> names,
      int round) throws Exception {
    String ruleLogger = names.get(0);
    MultiLayoutPattern encoder = new MultiLayoutPattern();
    encoder.setContext(context);
    encoder.setPattern("default %logger %msg");
    MultiLayoutPattern.Rule rule = new MultiLayoutPattern.Rule();
    rule.setLogger(ruleLogger);
    rule.setPattern("rule %logger %msg");
    encoder.addRule(rule);
    encoder.start();

    CountDownLatch start = new CountDownLatch(1);
    List<Future<?>> futures = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      List<String> order = new ArrayList<>(names);
      Collections.shuffle(order, new Random(round * threads + t));
      futures.add(pool.submit(() -> {
        start.await();
        for (String name : order) {
          String prefix = name.equals(ruleLogger) ? "rule " : "default ";
          String actual = new String(encoder.encode(createLoggingEvent(name, "m")));
          assertEquals(prefix + name + " m", actual);
        }
        return null;
      }));
    }
    start.countDown();
    for (Future<?> future : futures) {
      future.get(10, TimeUnit.SECONDS);
    }
  }

  private static List<String> sameHashNames(int blocks) {
    List<String> names = new ArrayList<>();
    for (int mask = 0; mask < (1 << blocks); mask++) {
      StringBuilder name = new StringBuilder("org.tron.");
      for (int i = 0; i < blocks; i++) {
        name.append((mask & (1 << i)) == 0 ? "Aa" : "BB");
      }
      names.add(name.toString());
    }
    return names;
  }

  private ILoggingEvent createLoggingEvent(String loggerName, String message) {
    Logger logger = (Logger) LoggerFactory.getLogger(loggerName);
    return new LoggingEvent(loggerName, logger, Level.INFO, message, null, null);
  }

}
