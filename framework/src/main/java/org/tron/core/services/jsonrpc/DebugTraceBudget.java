package org.tron.core.services.jsonrpc;

import org.tron.core.archive.query.HistoricalQueryLimitException;

/** Request-local pre-serialization allocation budget for debug trace materialization. */
final class DebugTraceBudget {

  private final long maxBytes;
  private long usedBytes;

  DebugTraceBudget(long maxBytes) {
    if (maxBytes <= 0L) {
      throw new IllegalArgumentException("debug trace byte limit must be positive");
    }
    this.maxBytes = maxBytes;
  }

  void reserve(long bytes) {
    if (bytes < 0L) {
      throw new IllegalArgumentException("debug trace reservation must be non-negative");
    }
    long observed = usedBytes > Long.MAX_VALUE - bytes
        ? Long.MAX_VALUE : usedBytes + bytes;
    if (observed > maxBytes) {
      throw new HistoricalQueryLimitException(
          HistoricalQueryLimitException.Reason.RESOURCE_EXHAUSTED,
          HistoricalQueryLimitException.Limit.RESPONSE_BYTES,
          maxBytes,
          observed,
          "historical debug trace materialization budget exceeded: limit="
              + maxBytes + ", observed=" + observed);
    }
    usedBytes = observed;
  }
}
