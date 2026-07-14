package org.tron.core.db2;

public interface ISession extends AutoCloseable {

  void commit();

  /** Commits the sole snapshot directly into the durable root store. */
  void commitToRoot();

  void revoke();

  void merge();

  void destroy();

  void close();

}
