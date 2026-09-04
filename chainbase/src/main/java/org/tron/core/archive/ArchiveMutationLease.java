package org.tron.core.archive;

/** A fair chain-mutation barrier lease shared by block writers and the archive publisher. */
public interface ArchiveMutationLease extends AutoCloseable {

  /** Canonical epoch protected by this lease. */
  long getEpoch();

  /** True for fork/recovery leases that exclude ordinary writers and publishers. */
  boolean isExclusive();

  @Override
  void close();
}
