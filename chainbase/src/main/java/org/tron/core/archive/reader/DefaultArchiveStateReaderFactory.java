package org.tron.core.archive.reader;

import java.util.Arrays;
import java.util.Optional;
import java.util.function.LongFunction;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.domain.ArchiveDomainCatalog;
import org.tron.core.archive.query.ArchiveQueryLimits;
import org.tron.core.archive.query.QueryContext;
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
  private final int maxMemoEntries;
  private final long maxMemoBytes;

  public DefaultArchiveStateReaderFactory(ArchiveTemporalStore temporalStore,
      ArchiveDomainCatalog catalog, ArchiveTxNumIndex txNumIndex,
      AvailabilityGuard availabilityGuard) {
    this(temporalStore, catalog, txNumIndex, availabilityGuard, ArchiveQueryLimits.unlimited());
  }

  public DefaultArchiveStateReaderFactory(ArchiveTemporalStore temporalStore,
      ArchiveDomainCatalog catalog, ArchiveTxNumIndex txNumIndex,
      AvailabilityGuard availabilityGuard,
      ArchiveQueryLimits queryLimits) {
    this.temporalStore = temporalStore;
    this.catalog = catalog;
    this.txNumIndex = txNumIndex;
    this.availabilityGuard = availabilityGuard;
    this.maxMemoEntries = queryLimits.getMaxCachedEntries();
    this.maxMemoBytes = queryLimits.getMaxCachedBytes();
  }

  @Override
  public ArchiveStateReader open(ArchiveStatePoint point) throws ArchiveReaderException {
    validateOpenable(point);
    return openView(point, () -> { });
  }

  /**
   * Open a live reader whose {@code close()} additionally runs {@code onClose} (used to release the
   * archive read lock a mid-chain reader holds for its lifetime).
   */
  public ArchiveStateReader open(ArchiveStatePoint point, Runnable onClose)
      throws ArchiveReaderException {
    return open(point, onClose, new QueryContext(ArchiveQueryLimits.unlimited()));
  }

  public ArchiveStateReader open(ArchiveStatePoint point, Runnable onClose,
      QueryContext queryContext) throws ArchiveReaderException {
    validateOpenable(point);
    return openView(point, onClose, queryContext);
  }

  /**
   * Open a reader bound to a pre-captured temporal snapshot {@code view}. The temporal snapshot is
   * the complete state source; {@code view.close()} releases it.
   */
  public ArchiveStateReader openSnapshot(ArchiveStatePoint point, ArchiveTemporalReadView view)
      throws ArchiveReaderException {
    return openSnapshot(point, view, new QueryContext(ArchiveQueryLimits.unlimited()));
  }

  public ArchiveStateReader openSnapshot(ArchiveStatePoint point, ArchiveTemporalReadView view,
      QueryContext queryContext) throws ArchiveReaderException {
    return openSnapshot(point, view, () -> { }, true, queryContext);
  }

  /** Opens an independently validated point with a block-range lookup bound to the same snapshot. */
  public ArchiveStateReader openSnapshot(ArchiveStatePoint point, ArchiveTemporalReadView view,
      QueryContext queryContext, LongFunction<Optional<ArchiveBlockRange>> blockRangeLookup)
      throws ArchiveReaderException {
    validateOpenable(point);
    return new DefaultArchiveStateReader(view, catalog, point,
        () -> { }, true, maxMemoEntries, maxMemoBytes, queryContext, blockRangeLookup);
  }

  /**
   * Opens a from-genesis snapshot using the committed range that resolved {@code point}. The range
   * is revalidated in memory, avoiding a second index lookup from the same protected snapshot.
   */
  public ArchiveStateReader openSnapshot(ArchiveStatePoint point, ArchiveBlockRange committedRange,
      ArchiveTemporalReadView view, QueryContext queryContext) throws ArchiveReaderException {
    return openSnapshot(point, committedRange, view, queryContext, txNumIndex::getBlockRange);
  }

  /** Opens a validated snapshot with a block-range lookup bound to that same snapshot. */
  public ArchiveStateReader openSnapshot(ArchiveStatePoint point, ArchiveBlockRange committedRange,
      ArchiveTemporalReadView view, QueryContext queryContext,
      LongFunction<Optional<ArchiveBlockRange>> blockRangeLookup)
      throws ArchiveReaderException {
    validateOpenable(point, committedRange);
    return new DefaultArchiveStateReader(view, catalog, point,
        () -> { }, true, maxMemoEntries, maxMemoBytes, queryContext, blockRangeLookup);
  }

  /**
   * Opens a reader over a caller-supplied snapshot and coverage classification.
   */
  public ArchiveStateReader openSnapshot(ArchiveStatePoint point, ArchiveTemporalReadView view,
      Runnable onClose, boolean completeHistory, QueryContext queryContext)
      throws ArchiveReaderException {
    validateOpenable(point);
    return new DefaultArchiveStateReader(view, catalog, point,
        onClose, completeHistory, maxMemoEntries, maxMemoBytes, queryContext,
        txNumIndex::getBlockRange);
  }

  private ArchiveStateReader openView(ArchiveStatePoint point, Runnable onClose)
      throws ArchiveReaderException {
    return openView(point, onClose, new QueryContext(ArchiveQueryLimits.unlimited()));
  }

  private ArchiveStateReader openView(ArchiveStatePoint point, Runnable onClose,
      QueryContext queryContext) throws ArchiveReaderException {
    ArchiveTemporalReadView view = temporalStore.openReadView();
    try {
      boolean completeHistory = isCompleteHistory();
      return new DefaultArchiveStateReader(
          view, catalog, point, onClose, completeHistory,
          maxMemoEntries, maxMemoBytes, queryContext, txNumIndex::getBlockRange);
    } catch (RuntimeException | Error | ArchiveReaderException failure) {
      try {
        view.close();
      } catch (Throwable closeFailure) {
        addSuppressedSafely(failure, closeFailure);
      }
      throw failure;
    }
  }

  private static void addSuppressedSafely(Throwable primary, Throwable candidate) {
    if (primary == candidate) {
      return;
    }
    try {
      primary.addSuppressed(candidate);
    } catch (Throwable ignored) {
      // Preserve the construction failure when suppression cannot allocate.
    }
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

  private void validateOpenable(ArchiveStatePoint point, ArchiveBlockRange committedRange)
      throws ArchiveReaderException {
    if (temporalStore == null) {
      throw new ArchiveReaderException(ArchiveReaderException.Reason.ARCHIVE_DISABLED,
          "archive temporal store is not available");
    }
    if (point == null || committedRange == null) {
      throw new ArchiveReaderException(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE,
          "no resolved archive state point/range");
    }
    availabilityGuard.validateAvailable();
    validatePoint(point, committedRange);
  }

  private void validatePoint(ArchiveStatePoint point) throws ArchiveReaderException {
    availabilityGuard.validateAvailable();
    ArchiveBlockRange range;
    try {
      range = txNumIndex.getBlockRange(point.getBlockNum())
          .orElseThrow(() -> new ArchiveReaderException(
              ArchiveReaderException.Reason.HISTORY_UNAVAILABLE,
              "archive block " + point.getBlockNum() + " is not covered"));
    } catch (ArchiveReaderException e) {
      throw e;
    } catch (ArchiveException e) {
      throw new ArchiveReaderException(ArchiveReaderException.reasonForStorageFailure(
          e, ArchiveReaderException.Reason.CORRUPT_INDEX),
          "archive block-range index is corrupt", e);
    }
    validatePoint(point, range);
  }

  private static void validatePoint(ArchiveStatePoint point, ArchiveBlockRange range)
      throws ArchiveReaderException {
    if (range.getBlockNum() != point.getBlockNum()) {
      throw new ArchiveReaderException(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE,
          "archive point block number does not match committed range");
    }
    validatePointTxNum(point, range);
    byte[] blockHash = point.getBlockHash();
    if (blockHash == null || blockHash.length != ArchiveBlockRange.BLOCK_HASH_LENGTH
        || !Arrays.equals(blockHash, range.getBlockHash())) {
      throw new ArchiveReaderException(ArchiveReaderException.Reason.HISTORY_UNAVAILABLE,
          "archive point block hash does not match committed range");
    }
  }

  private boolean isCompleteHistory() throws ArchiveReaderException {
    try {
      return txNumIndex.getFirstArchivedBlock() == 0L;
    } catch (ArchiveException e) {
      throw new ArchiveReaderException(ArchiveReaderException.reasonForStorageFailure(
          e, ArchiveReaderException.Reason.CORRUPT_INDEX),
          "archive coverage-floor index is corrupt", e);
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
