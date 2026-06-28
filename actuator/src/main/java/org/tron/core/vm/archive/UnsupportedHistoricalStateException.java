package org.tron.core.vm.archive;

/**
 * Thrown when a historical archive call reaches state the archive cannot answer: a domain outside
 * P0 coverage (delegation, votes, witnesses, asset-issue, resource/energy accounting), the live
 * dynamic-properties store, or an underlying archive read error. Failing fast here is deliberate:
 * the historical path must never silently fall back to latest state and return a wrong answer.
 */
public class UnsupportedHistoricalStateException extends RuntimeException {

  public UnsupportedHistoricalStateException(String message) {
    super(message);
  }

  public UnsupportedHistoricalStateException(String message, Throwable cause) {
    super(message, cause);
  }
}
