package org.tron.core.archive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.archive.txnum.ArchiveTxPosition;

/** A canonical block that has committed locally but is not published to archive readers yet. */
public final class ArchiveInFlightBlock {

  public enum JournalState {
    JOURNALED,
    CANONICAL_COMMITTED
  }

  private final ArchiveBlockRange range;
  private final List<ArchiveTxPosition> positions;
  private final List<ArchiveChangeRecord> records;
  private final ArchiveJournalToken journalToken;
  private final JournalState journalState;
  private final long estimatedRetainedBytes;
  private final long encodedBlockBytes;
  private final long temporalPublicationRetainedBytes;
  private final long temporalPublicationMutations;

  public ArchiveInFlightBlock(ArchiveBlockRange range, List<ArchiveTxPosition> positions,
      List<ArchiveChangeRecord> records) {
    this(range, positions, records, ArchiveJournalToken.create(range), JournalState.JOURNALED);
  }

  public ArchiveInFlightBlock(ArchiveBlockRange range, List<ArchiveTxPosition> positions,
      List<ArchiveChangeRecord> records, ArchiveJournalToken journalToken,
      JournalState journalState) {
    this(range, positions, records, journalToken, journalState, true);
  }

  /** Internal ownership transfer; callers must not retain mutable aliases to either list. */
  static ArchiveInFlightBlock fromOwnedLists(ArchiveBlockRange range,
      List<ArchiveTxPosition> positions, List<ArchiveChangeRecord> records) {
    return fromOwnedLists(
        range, positions, records, ArchiveJournalToken.create(range), JournalState.JOURNALED);
  }

  static ArchiveInFlightBlock fromOwnedLists(ArchiveBlockRange range,
      List<ArchiveTxPosition> positions, List<ArchiveChangeRecord> records,
      ArchiveJournalToken journalToken, JournalState journalState) {
    return new ArchiveInFlightBlock(range,
        Collections.unmodifiableList(positions), Collections.unmodifiableList(records),
        journalToken, journalState, false);
  }

  private ArchiveInFlightBlock(ArchiveBlockRange range, List<ArchiveTxPosition> positions,
      List<ArchiveChangeRecord> records, ArchiveJournalToken journalToken,
      JournalState journalState, boolean copyLists) {
    this(range, positions, records, journalToken, journalState, copyLists,
        -1L, -1L, -1L, -1L);
  }

  private ArchiveInFlightBlock(ArchiveBlockRange range, List<ArchiveTxPosition> positions,
      List<ArchiveChangeRecord> records, ArchiveJournalToken journalToken,
      JournalState journalState, boolean copyLists, long retainedBytes,
      long encodedBytes, long temporalRetainedBytes, long temporalMutations) {
    if (journalToken == null || !journalToken.matches(range)) {
      throw new ArchiveException("archive in-flight journal token does not match block range");
    }
    if (journalState == null) {
      throw new ArchiveException("archive in-flight journal state is missing");
    }
    this.range = range;
    this.positions = copyLists
        ? Collections.unmodifiableList(new ArrayList<>(positions)) : positions;
    this.records = copyLists
        ? Collections.unmodifiableList(new ArrayList<>(records)) : records;
    this.journalToken = journalToken;
    this.journalState = journalState;
    ResourceEstimates estimates = retainedBytes < 0L || temporalRetainedBytes < 0L
        || temporalMutations < 0L
        ? estimateResources(this.positions, this.records) : null;
    this.estimatedRetainedBytes = retainedBytes >= 0L
        ? retainedBytes : estimates.retainedBytes;
    this.temporalPublicationRetainedBytes = temporalRetainedBytes >= 0L
        ? temporalRetainedBytes : estimates.temporalRetainedBytes;
    this.temporalPublicationMutations = temporalMutations >= 0L
        ? temporalMutations : estimates.temporalMutations;
    this.encodedBlockBytes = encodedBytes >= 0L
        ? encodedBytes : ArchiveInFlightCodec.calculateEncodedBlockSize(this);
  }

  public ArchiveBlockRange getRange() {
    return range;
  }

  public List<ArchiveTxPosition> getPositions() {
    return positions;
  }

  public List<ArchiveChangeRecord> getRecords() {
    return records;
  }

  public ArchiveJournalToken getJournalToken() {
    return journalToken;
  }

  public JournalState getJournalState() {
    return journalState;
  }

  public ArchiveInFlightBlock withJournalState(JournalState state) {
    return new ArchiveInFlightBlock(
        range, positions, records, journalToken, state, false, estimatedRetainedBytes,
        encodedBlockBytes, temporalPublicationRetainedBytes, temporalPublicationMutations);
  }

  /** Conservative retained-heap/journal estimate used for bounded backlog admission. */
  public long estimatedRetainedBytes() {
    return estimatedRetainedBytes;
  }

  long encodedBlockBytes() {
    return encodedBlockBytes;
  }

  long temporalPublicationRetainedBytes() {
    return temporalPublicationRetainedBytes;
  }

  long temporalPublicationMutations() {
    return temporalPublicationMutations;
  }

  private static ResourceEstimates estimateResources(List<ArchiveTxPosition> positions,
      List<ArchiveChangeRecord> records) {
    // Includes list/object overhead plus execution-index maps. Canonical keys are retained by both
    // the record and the in-flight latest/version lookup maps, so count their payload twice.
    long retainedBytes = ArchiveResourceEstimator.estimatedPositionSetRetainedBytes(
        positions.size());
    long temporalRetainedBytes = 512L;
    long temporalMutations = 1L;
    for (ArchiveChangeRecord record : records) {
      retainedBytes = addSaturated(retainedBytes, estimatedRecordRetainedBytes(
          record.canonicalKeySize(), record.getPrevValue().size(), record.getValue().size()));
      temporalRetainedBytes = addSaturated(temporalRetainedBytes,
          ArchiveResourceEstimator.estimatedTemporalPreparationBytes(
              record.canonicalKeySize(), record.getPrevValue().size(),
              record.getValue().size()));
      temporalMutations = addSaturated(temporalMutations, 10L);
    }
    return new ResourceEstimates(
        retainedBytes, temporalRetainedBytes, temporalMutations);
  }

  /** Shared pre-allocation estimate for one captured record and its in-flight lookup entries. */
  public static long estimatedRecordRetainedBytes(
      int keyBytes, int prevValueBytes, int valueBytes) {
    return ArchiveResourceEstimator.estimatedRecordRetainedBytes(
        keyBytes, prevValueBytes, valueBytes);
  }

  private static long addSaturated(long left, long right) {
    return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
  }

  private static final class ResourceEstimates {

    private final long retainedBytes;
    private final long temporalRetainedBytes;
    private final long temporalMutations;

    private ResourceEstimates(long retainedBytes, long temporalRetainedBytes,
        long temporalMutations) {
      this.retainedBytes = retainedBytes;
      this.temporalRetainedBytes = temporalRetainedBytes;
      this.temporalMutations = temporalMutations;
    }
  }

}
