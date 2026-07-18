package org.tron.core.archive.temporal;

import java.util.Optional;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.ArchiveRocksIterators;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.unified.UnifiedArchiveIterator;
import org.tron.core.archive.domain.ArchiveDomain;

/** Shared inclusive-after lookup semantics for temporal stores and snapshots. */
final class ArchiveTemporalReadSupport {

  private ArchiveTemporalReadSupport() {
  }

  static Optional<DomainValue> getAsOf(ArchiveDomain domain, byte[] canonicalKey, long txNum,
      UnifiedArchiveIterator history, IntegrityRowLookup historyLookup,
      IntegrityRowLookup latestLookup, IntegrityRowLookup changesetLookup,
      IntegrityRowLookup baselineLookup, IntegrityRowLookup anchorLookup, String operation) {
    if (txNum < 0) {
      throw new ArchiveException("archive temporal txNum must be non-negative");
    }
    ArchiveTemporalIntegrityCodec.DecodedRow anchor = anchorLookup.get(
        ArchiveTemporalCodec.anchorKey(domain, canonicalKey));
    validateAnchor(anchor, operation);
    if (txNum != Long.MAX_VALUE) {
      byte[] prefix = ArchiveTemporalCodec.historyPrefix(domain, canonicalKey);
      history.seek(ArchiveTemporalCodec.historyKey(domain, canonicalKey, txNum + 1L));
      ArchiveRocksIterators.requireOk(history, operation + ": history seek");
      if (history.isValid() && ArchiveTemporalCodec.startsWith(history.key(), prefix)) {
        byte[] historyKey = history.key();
        requireMatchingChangeset(historyKey, changesetLookup, operation);
        ArchiveTemporalIntegrityCodec.DecodedRow historyValue = historyLookup.get(historyKey);
        if (historyValue == null) {
          throw new ArchiveException(operation + ": selected history row disappeared");
        }
        long historyTxNum = ArchiveTemporalCodec.txNumOfHistory(historyKey);
        if (historyValue.linkedTxNum() >= historyTxNum
            || historyValue.linkedTxNum() > txNum) {
          throw new ArchiveException(operation + ": history chain has a missing version");
        }
        requireAnchor(anchor, operation);
        requirePhysicalPredecessor(
            history, prefix, historyValue, anchor, changesetLookup, operation);
        return Optional.of(historyValue.domainValue().decode());
      }
    }
    byte[] latestKey = ArchiveTemporalCodec.latestKey(domain, canonicalKey);
    ArchiveTemporalIntegrityCodec.DecodedRow latest = latestLookup.get(latestKey);
    history.seekForPrev(ArchiveTemporalCodec.historyKey(domain, canonicalKey, Long.MAX_VALUE));
    ArchiveRocksIterators.requireOk(history, operation + ": latest history seek");
    byte[] prefix = ArchiveTemporalCodec.historyPrefix(domain, canonicalKey);
    if (history.isValid() && ArchiveTemporalCodec.startsWith(history.key(), prefix)) {
      requireAnchor(anchor, operation);
      long latestHistoryTxNum = ArchiveTemporalCodec.txNumOfHistory(history.key());
      ArchiveTemporalIntegrityCodec.DecodedRow changeset =
          requireMatchingChangeset(history.key(), changesetLookup, operation);
      if (latest == null || latest.linkedTxNum() != latestHistoryTxNum
          || !latest.payloadEquals(changeset)) {
        throw new ArchiveException(operation + ": latest value does not match last changeset");
      }
    } else {
      ArchiveTemporalIntegrityCodec.DecodedRow baseline = baselineLookup.get(
          ArchiveTemporalCodec.latestBaselineKey(domain, canonicalKey));
      if (anchor == null) {
        if (latest == null && baseline == null) {
          return Optional.empty();
        }
        throw new ArchiveException(operation + ": temporal rows have no anchor");
      }
      if (latest == null && baseline == null) {
        throw new ArchiveException(operation + ": anchor has no matching temporal rows");
      }
      if (latest != null && latest.linkedTxNum()
          != ArchiveTemporalIntegrityCodec.NO_HISTORY_TX_NUM) {
        throw new ArchiveException(operation + ": latest history chain is missing");
      }
      if (baseline != null && baseline.linkedTxNum()
          != ArchiveTemporalIntegrityCodec.NO_HISTORY_TX_NUM) {
        throw new ArchiveException(operation + ": latest baseline link is invalid");
      }
      if (latest == null ? baseline != null : !latest.payloadEquals(baseline)) {
        throw new ArchiveException(operation + ": latest value has no matching baseline");
      }
      if (baseline != null && !baseline.payloadEquals(anchor)) {
        throw new ArchiveException(operation + ": latest baseline does not match anchor");
      }
    }
    return latest == null ? Optional.empty()
        : Optional.of(latest.domainValue().decode());
  }

  private static void validateAnchor(ArchiveTemporalIntegrityCodec.DecodedRow anchor,
      String operation) {
    if (anchor == null) {
      return;
    }
    if (anchor.linkedTxNum() < 0L) {
      throw new ArchiveException(operation + ": anchor origin txNum is invalid");
    }
    anchor.domainValue().decode();
  }

  private static void requireAnchor(ArchiveTemporalIntegrityCodec.DecodedRow anchor,
      String operation) {
    if (anchor == null) {
      throw new ArchiveException(operation + ": temporal history has no anchor");
    }
  }

  private static void requirePhysicalPredecessor(UnifiedArchiveIterator history,
      byte[] historyPrefix,
      ArchiveTemporalIntegrityCodec.DecodedRow current,
      ArchiveTemporalIntegrityCodec.DecodedRow anchor,
      IntegrityRowLookup changesetLookup, String operation) {
    history.prev();
    ArchiveRocksIterators.requireOk(history, operation + ": physical predecessor seek");
    if (!history.isValid()
        || !ArchiveTemporalCodec.startsWith(history.key(), historyPrefix)) {
      if (current.linkedTxNum() != ArchiveTemporalIntegrityCodec.NO_HISTORY_TX_NUM
          || !current.payloadEquals(anchor)) {
        throw new ArchiveException(
            operation + ": first history row does not match anchor/physical predecessor");
      }
      return;
    }
    byte[] predecessorKey = history.key();
    long predecessorTxNum = ArchiveTemporalCodec.txNumOfHistory(predecessorKey);
    ArchiveTemporalIntegrityCodec.DecodedRow predecessorChangeset =
        requireMatchingChangeset(predecessorKey, changesetLookup, operation);
    if (current.linkedTxNum() != predecessorTxNum
        || !current.payloadEquals(predecessorChangeset)) {
      throw new ArchiveException(
          operation + ": history row does not match its physical predecessor");
    }
  }

  private static ArchiveTemporalIntegrityCodec.DecodedRow requireMatchingChangeset(
      byte[] historyKey, IntegrityRowLookup changesetLookup, String operation) {
    long historyTxNum = ArchiveTemporalCodec.txNumOfHistory(historyKey);
    ArchiveTemporalIntegrityCodec.DecodedRow changeset = changesetLookup.get(
        ArchiveTemporalCodec.changesetKeyOfHistory(historyKey));
    if (changeset == null) {
      throw new ArchiveException(operation + ": history row has no matching changeset");
    }
    if (changeset.linkedTxNum() != historyTxNum) {
      throw new ArchiveException(operation + ": changeset txNum link is invalid");
    }
    changeset.domainValue().decode();
    return changeset;
  }

  @FunctionalInterface
  interface IntegrityRowLookup {

    ArchiveTemporalIntegrityCodec.DecodedRow get(byte[] key);
  }
}
