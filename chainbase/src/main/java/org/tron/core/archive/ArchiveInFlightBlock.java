package org.tron.core.archive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.archive.txnum.ArchiveTxPosition;

/** A canonical block that has committed locally but is not published to archive readers yet. */
public final class ArchiveInFlightBlock {

  private final ArchiveBlockRange range;
  private final List<ArchiveTxPosition> positions;
  private final List<ArchiveChangeRecord> records;

  public ArchiveInFlightBlock(ArchiveBlockRange range, List<ArchiveTxPosition> positions,
      List<ArchiveChangeRecord> records) {
    this.range = range;
    this.positions = Collections.unmodifiableList(new ArrayList<>(positions));
    this.records = Collections.unmodifiableList(new ArrayList<>(records));
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
}
