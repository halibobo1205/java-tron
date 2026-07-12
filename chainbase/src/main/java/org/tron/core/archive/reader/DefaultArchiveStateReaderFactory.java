package org.tron.core.archive.reader;

import java.util.Arrays;
import org.tron.core.archive.domain.ArchiveDomainCatalog;
import org.tron.core.archive.temporal.ArchiveTemporalReadView;
import org.tron.core.archive.temporal.ArchiveTemporalStore;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.archive.txnum.ArchiveTxNumIndex;

/**
 * Default {@link ArchiveStateReaderFactory}: each {@link #open} returns a lightweight
 * {@link DefaultArchiveStateReader} view over the shared temporal store + catalog at that point.
 */
public final class DefaultArchiveStateReaderFactory implements ArchiveStateReaderFactory {

  @FunctionalInterface
  public interface AvailabilityGuard {
    void validateAvailable() throws ArchiveReaderException;
  }

  private final ArchiveTemporalStore temporalStore;
  private final ArchiveDomainCatalog catalog;
  private final ArchiveTxNumIndex txNumIndex;
  private final AvailabilityGuard availabilityGuard;
  private final ArchiveReadThrough readThrough;

  public DefaultArchiveStateReaderFactory(ArchiveTemporalStore temporalStore,
      ArchiveDomainCatalog catalog, ArchiveTxNumIndex txNumIndex,
      AvailabilityGuard availabilityGuard) {
    this(temporalStore, catalog, txNumIndex, availabilityGuard, ArchiveReadThrough.NONE);
  }

  public DefaultArchiveStateReaderFactory(ArchiveTemporalStore temporalStore,
      ArchiveDomainCatalog catalog, ArchiveTxNumIndex txNumIndex,
      AvailabilityGuard availabilityGuard, ArchiveReadThrough readThrough) {
    this.temporalStore = temporalStore;
    this.catalog = catalog;
    this.txNumIndex = txNumIndex;
    this.availabilityGuard = availabilityGuard;
    this.readThrough = readThrough == null ? ArchiveReadThrough.NONE : readThrough;
  }

  @Override
  public ArchiveStateReader open(ArchiveStatePoint point) throws ArchiveReaderException {
    validateOpenable(point);
    return new DefaultArchiveStateReader(temporalStore, catalog, point, readThrough);
  }

  /**
   * Open a live reader whose {@code close()} additionally runs {@code onClose} (used to release the
   * archive read lock a mid-chain reader holds for its lifetime).
   */
  public ArchiveStateReader open(ArchiveStatePoint point, Runnable onClose)
      throws ArchiveReaderException {
    validateOpenable(point);
    return new DefaultArchiveStateReader(ArchiveTemporalReadView.passThrough(temporalStore),
        catalog, point, readThrough, onClose);
  }

  /**
   * Open a reader bound to a pre-captured temporal snapshot {@code view} (genesis-complete path).
   * The live read-through is gated on {@code firstArchivedBlock > 0}, so it is unused here and the
   * temporal snapshot alone is a complete, consistent view; {@code view.close()} releases it.
   */
  public ArchiveStateReader openSnapshot(ArchiveStatePoint point, ArchiveTemporalReadView view)
      throws ArchiveReaderException {
    validateOpenable(point);
    return new DefaultArchiveStateReader(view, catalog, point, ArchiveReadThrough.NONE);
  }

  private void validateOpenable(ArchiveStatePoint point) throws ArchiveReaderException {
    if (temporalStore == null) {
      throw new ArchiveReaderException(ArchiveReaderException.Reason.ARCHIVE_DISABLED,
          "archive temporal store is not available");
    }
    if (point == null) {
      throw new ArchiveReaderException(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE,
          "no resolved archive state point");
    }
    validatePoint(point);
  }

  private void validatePoint(ArchiveStatePoint point) throws ArchiveReaderException {
    availabilityGuard.validateAvailable();
    ArchiveBlockRange range = txNumIndex.getBlockRange(point.getBlockNum())
        .orElseThrow(() -> new ArchiveReaderException(
            ArchiveReaderException.Reason.HISTORY_UNAVAILABLE,
            "archive block " + point.getBlockNum() + " is not covered"));
    validatePointTxNum(point, range);
    byte[] blockHash = point.getBlockHash();
    if (blockHash == null || blockHash.length != ArchiveBlockRange.BLOCK_HASH_LENGTH
        || !Arrays.equals(blockHash, range.getBlockHash())) {
      throw new ArchiveReaderException(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE,
          "archive point block hash does not match committed range");
    }
  }

  private static void validatePointTxNum(ArchiveStatePoint point, ArchiveBlockRange range)
      throws ArchiveReaderException {
    if (point.getKind() == ArchiveStatePoint.Kind.BLOCK_END) {
      if (point.getTxNum() == range.getFinalizeTxNum()) {
        return;
      }
      throw new ArchiveReaderException(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE,
          "archive block-end point txNum does not match block finalize txNum");
    }
    if (point.getKind() == ArchiveStatePoint.Kind.TX_BEFORE) {
      if (range.getUserTxCount() > 0
          && point.getTxNum() >= range.getPrepareTxNum()
          && point.getTxNum() <= range.getFinalizeTxNum() - 2) {
        return;
      }
      throw new ArchiveReaderException(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE,
          "archive tx-before point txNum is outside committed block range");
    }
    throw new ArchiveReaderException(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE,
        "archive reader point kind is not supported: " + point.getKind());
  }
}
