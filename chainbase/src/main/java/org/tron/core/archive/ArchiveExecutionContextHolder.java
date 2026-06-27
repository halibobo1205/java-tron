package org.tron.core.archive;

/**
 * Process-wide singleton {@link ArchiveExecutionContext} so Store-level hooks (L4) and the
 * Manager lifecycle (L2) share one thread-local current position without bean wiring.
 */
public final class ArchiveExecutionContextHolder {

  private static final ArchiveExecutionContext CONTEXT = new ArchiveExecutionContext();

  private ArchiveExecutionContextHolder() {
  }

  public static ArchiveExecutionContext get() {
    return CONTEXT;
  }
}
