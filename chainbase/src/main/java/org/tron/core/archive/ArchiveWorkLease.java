package org.tron.core.archive;

/** Reservation for one lifecycle-controlled archive operation. */
public interface ArchiveWorkLease extends AutoCloseable {

  /** Final admission check after the caller has acquired its mutation/archive locks. */
  void start();

  @Override
  void close();
}
