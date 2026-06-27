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
    current.set(position);
  }

  public Optional<ArchiveTxPosition> current() {
    return Optional.ofNullable(current.get());
  }

  public void clear() {
    current.remove();
  }
}
