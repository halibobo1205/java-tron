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

  public ArchiveInFlightBlock(ArchiveBlockRange range, List<ArchiveTxPosition> positions,
      List<ArchiveChangeRecord> records) {
    this(range, positions, records, ArchiveJournalToken.create(range), JournalState.JOURNALED);
  }

  public ArchiveInFlightBlock(ArchiveBlockRange range, List<ArchiveTxPosition> positions,
      List<ArchiveChangeRecord> records, ArchiveJournalToken journalToken,
      JournalState journalState) {
    this(range, positions, records, journalToken, journalState, true);
  }

  private ArchiveInFlightBlock(ArchiveBlockRange range, List<ArchiveTxPosition> positions,
      List<ArchiveChangeRecord> records, ArchiveJournalToken journalToken,
      JournalState journalState, boolean copyLists) {
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
    return new ArchiveInFlightBlock(range, positions, records, journalToken, state, false);
  }

  /** Conservative retained-heap/journal estimate used for bounded backlog admission. */
  public long estimatedRetainedBytes() {
    // Includes list/object overhead plus execution-index maps. Canonical keys are retained by both
    // the record and the in-flight latest/version lookup maps, so count their payload twice.
    long bytes = addSaturated(512L, multiplySaturated(positions.size(), 640L));
    for (ArchiveChangeRecord record : records) {
      bytes = addSaturated(bytes, 640L);
      bytes = addSaturated(bytes, multiplySaturated(record.canonicalKeySize(), 2L));
      bytes = addSaturated(bytes, record.getPrevValue().size());
      bytes = addSaturated(bytes, record.getValue().size());
    }
    return bytes;
  }

  private static long addSaturated(long left, long right) {
    return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
  }

  private static long multiplySaturated(long left, long right) {
    return left != 0L && right > Long.MAX_VALUE / left ? Long.MAX_VALUE : left * right;
  }
}
