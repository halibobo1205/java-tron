package org.tron.common.log.layout;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Context;
import ch.qos.logback.core.Layout;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MultiLayoutPattern extends PatternLayoutEncoder {
  private List<Rule> rules = new ArrayList<>();
  private final Map<String, Layout<ILoggingEvent>> layoutMap = new ConcurrentHashMap<>();

  public void addRule(Rule rule) {
    this.rules.add(rule);
    rule.start(context);
  }

  public byte[] encode(ILoggingEvent event) {
    Layout<ILoggingEvent> layout = getLayout(event.getLoggerName());
    String txt = layout.doLayout(event);
    return convertToBytes(txt);
  }

  private byte[] convertToBytes(String s) {
    Charset charset = getCharset();
    if (charset == null) {
      return s.getBytes();
    } else {
      return s.getBytes(charset);
    }
  }

  private Layout<ILoggingEvent> getLayout(final String name) {
    if (name == null) {
      throw new IllegalArgumentException("name cannot be null");
    }
    if (Logger.ROOT_LOGGER_NAME.equalsIgnoreCase(name)) {
      return this.getLayout();
    }
    Layout<ILoggingEvent> layout = layoutMap.get(name);
    if (layout != null) {
      return layout;
    }
    return layoutMap.computeIfAbsent(name, this::findLayout);
  }

  private Layout<ILoggingEvent> findLayout(String name) {
    for (Rule rule : rules) {
      if (rule.logger.equals(name)) {
        return rule.getPatternLayoutEncoder().getLayout();
      }
    }
    return getLayout();
  }

  @Override
  public void start() {
    super.start();
  }

  public static class Rule {
    private String logger;
    private String pattern;
    private PatternLayoutEncoder patternLayoutEncoder;
    private boolean outputPatternAsHeader = false;

    public String getLogger() {
      return logger;
    }

    public void setLogger(String logger) {
      this.logger = logger;
    }

    public String getPattern() {
      return pattern;
    }

    public void setPattern(String pattern) {
      this.pattern = pattern;
    }

    public boolean isOutputPatternAsHeader() {
      return outputPatternAsHeader;
    }

    public void setOutputPatternAsHeader(boolean outputPatternAsHeader) {
      this.outputPatternAsHeader = outputPatternAsHeader;
    }

    public PatternLayoutEncoder getPatternLayoutEncoder() {
      return patternLayoutEncoder;
    }

    public void start(Context context) {
      patternLayoutEncoder = new PatternLayoutEncoder();
      patternLayoutEncoder.setPattern(pattern);
      patternLayoutEncoder.setContext(context);
      patternLayoutEncoder.setOutputPatternAsHeader(outputPatternAsHeader);
      patternLayoutEncoder.start();
    }
  }
}