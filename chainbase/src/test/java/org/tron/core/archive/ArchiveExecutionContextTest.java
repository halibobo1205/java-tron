package org.tron.core.archive;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.tron.core.archive.txnum.ArchiveTxPosition;

public class ArchiveExecutionContextTest {

  @Test
  public void nestedEnterFailsWithoutReplacingCurrentPosition() {
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    ArchiveTxPosition first = position(0L, ArchivePhase.BLOCK_PREPARE);
    ArchiveTxPosition second = position(1L, ArchivePhase.BLOCK_FINALIZE);

    context.enter(first);
    assertTrue(context.hasCurrent());
    assertSame(first, context.currentOrNull());
    assertThrows(ArchiveException.class, () -> context.enter(second));
    assertSame(first, context.current().orElseThrow(AssertionError::new));

    context.clear();
    assertFalse(context.hasCurrent());
    assertNull(context.currentOrNull());
    context.enter(second);
    assertSame(second, context.current().orElseThrow(AssertionError::new));
    context.clear();
    assertFalse(context.current().isPresent());
  }

  @Test
  public void nullPositionCannotCreateAnInvisibleActiveContext() {
    ArchiveExecutionContext context = new ArchiveExecutionContext();

    assertThrows(NullPointerException.class, () -> context.enter(null));
    assertFalse(context.current().isPresent());
  }

  private static ArchiveTxPosition position(long txNum, ArchivePhase phase) {
    return new ArchiveTxPosition(txNum, 0L, phase, ArchiveSource.NORMAL, -1, null);
  }
}
