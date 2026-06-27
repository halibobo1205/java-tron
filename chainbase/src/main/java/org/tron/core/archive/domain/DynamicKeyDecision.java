package org.tron.core.archive.domain;

/** Per-key policy decision for a dynamic-properties key. */
public final class DynamicKeyDecision {

  private final String key;
  private final DynamicKeyClass keyClass;
  private final RootPolicy rootPolicy;
  private final HistoryPolicy historyPolicy;
  private final ReaderPolicy readerPolicy;

  public DynamicKeyDecision(String key, DynamicKeyClass keyClass, RootPolicy rootPolicy,
      HistoryPolicy historyPolicy, ReaderPolicy readerPolicy) {
    this.key = key;
    this.keyClass = keyClass;
    this.rootPolicy = rootPolicy;
    this.historyPolicy = historyPolicy;
    this.readerPolicy = readerPolicy;
  }

  public String getKey() {
    return key;
  }

  public DynamicKeyClass getKeyClass() {
    return keyClass;
  }

  public RootPolicy getRootPolicy() {
    return rootPolicy;
  }

  public HistoryPolicy getHistoryPolicy() {
    return historyPolicy;
  }

  public ReaderPolicy getReaderPolicy() {
    return readerPolicy;
  }
}
