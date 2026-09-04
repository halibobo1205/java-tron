package org.tron.core.archive;

import java.util.Optional;
import org.tron.core.archive.txnum.ArchiveTxPosition;

/**
 * Thread-local holder of the current {@link ArchiveTxPosition} during canonical block apply.
 * L2 only sets/clears it around each phase/tx; L4 Store hooks read {@link #current()} to learn
 * which txNum a write belongs to. Must be empty again after a phase/tx ends or aborts.
 */
public final class ArchiveExecutionContext {

  private final ThreadLocal<ArchiveTxPosition> current = new ThreadLocal<>();

  public void enter(ArchiveTxPosition position) {
    if (position == null) {
      throw new NullPointerException("position");
    }
    if (current.get() != null) {
      throw new ArchiveException("archive execution context is already active");
    }
    try {
      current.set(position);
    } catch (RuntimeException | Error failure) {
      try {
        current.remove();
      } catch (RuntimeException | Error restoreFailure) {
        if (failure != restoreFailure) {
          failure.addSuppressed(restoreFailure);
        }
      }
      throw failure;
    }
  }

  public Optional<ArchiveTxPosition> current() {
    return Optional.ofNullable(current.get());
  }

  /** Allocation-free hot-path check used by archive capture hooks. */
  public boolean hasCurrent() {
    return current.get() != null;
  }

  /** Allocation-free hot-path access; callers must treat a null result as no active transaction. */
  public ArchiveTxPosition currentOrNull() {
    return current.get();
  }

  public void clear() {
    current.remove();
  }
}
