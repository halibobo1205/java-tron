package org.tron.core.archive.temporal;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongPredicate;
import org.rocksdb.RocksIterator;
import org.tron.common.math.StrictMathWrapper;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.ArchiveRocksIterators;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.domain.ArchiveDomainCatalog;
import org.tron.core.archive.domain.DynamicKeyPolicy;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.archive.unified.UnifiedArchiveColumnFamily;
import org.tron.core.archive.unified.UnifiedArchiveDb;
import org.tron.core.archive.unified.UnifiedArchiveMaintenanceBatch;
import org.tron.core.archive.unified.UnifiedArchivePublish;
import org.tron.core.archive.unified.UnifiedArchiveReadView;
import org.tron.core.db2.common.WrappedByteArray;

/** Temporal-store adapter over UNIFIED_V1 latest/history/changeset/marker column families. */
public final class UnifiedArchiveTemporalStore implements ArchiveTemporalStore {

  private static final Comparator<ArchiveChangeRecord> RECORD_ORDER =
      UnifiedArchiveTemporalStore::compareRecords;

  private final UnifiedArchiveDb db;
  private final ArchiveDomainCatalog catalog;
  private final DynamicKeyPolicy dynamicKeyPolicy = new DynamicKeyPolicy();

  public UnifiedArchiveTemporalStore(UnifiedArchiveDb db, ArchiveDomainCatalog catalog) {
    if (db == null) {
      throw new NullPointerException("db");
    }
    if (catalog == null) {
      throw new NullPointerException("catalog");
    }
    this.db = db;
    this.catalog = catalog;
  }

  /** Adds temporal mutations and the block marker to the caller's atomic publish. */
  public void stagePublication(UnifiedArchivePublish.Builder publish, ArchiveBlockRange range,
      List<ArchiveChangeRecord> records) {
    if (publish == null) {
      throw new NullPointerException("publish");
    }
    PreparedChanges prepared = prepare(range, records);
    for (Row row : prepared.historyRows) {
      publish.put(UnifiedArchiveColumnFamily.HISTORY, row.key, row.value);
    }
    for (Row row : prepared.changesetRows) {
      publish.put(UnifiedArchiveColumnFamily.CHANGESET, row.key, row.value);
    }
    for (LatestRows latest : prepared.latestRows.values()) {
      publish.put(UnifiedArchiveColumnFamily.LATEST, latest.latestKey, latest.value);
      publish.delete(UnifiedArchiveColumnFamily.LATEST, latest.baselineKey);
    }
    publish.blockMarker(ArchiveTemporalCodec.blockCommitKey(range.getBlockNum()),
        ArchiveTemporalCodec.encodeBlockCommit(
            range, prepared.rowCount, prepared.digest));
  }

  @Override
  public void putChange(ArchiveChangeRecord record) {
    putChanges(Collections.singletonList(record));
  }

  @Override
  public void putChanges(List<ArchiveChangeRecord> records) {
    PreparedChanges prepared = prepare(null, records);
    db.writeMaintenanceAtomically(toMaintenanceBatch(prepared, null));
  }

  @Override
  public void putBlockChanges(ArchiveBlockRange range, List<ArchiveChangeRecord> records) {
    PreparedChanges prepared = prepare(range, records);
    db.writeMaintenanceAtomically(toMaintenanceBatch(prepared, range));
  }

  private UnifiedArchiveMaintenanceBatch toMaintenanceBatch(PreparedChanges prepared,
      ArchiveBlockRange range) {
    UnifiedArchiveMaintenanceBatch batch = new UnifiedArchiveMaintenanceBatch();
    for (Row row : prepared.historyRows) {
      batch.put(UnifiedArchiveColumnFamily.HISTORY, row.key, row.value);
    }
    for (Row row : prepared.changesetRows) {
      batch.put(UnifiedArchiveColumnFamily.CHANGESET, row.key, row.value);
    }
    for (LatestRows latest : prepared.latestRows.values()) {
      batch.put(UnifiedArchiveColumnFamily.LATEST, latest.latestKey, latest.value);
      batch.delete(UnifiedArchiveColumnFamily.LATEST, latest.baselineKey);
    }
    if (range != null) {
      batch.put(UnifiedArchiveColumnFamily.BLOCK_MARKER,
          ArchiveTemporalCodec.blockCommitKey(range.getBlockNum()),
          ArchiveTemporalCodec.encodeBlockCommit(range, prepared.rowCount, prepared.digest));
    }
    return batch;
  }

  private PreparedChanges prepare(ArchiveBlockRange range,
      List<ArchiveChangeRecord> records) {
    if (records == null) {
      throw new ArchiveException("archive temporal block changes are missing");
    }
    List<ArchiveChangeRecord> ordered = new ArrayList<>(records);
    ordered.sort(RECORD_ORDER);
    validatePrevValueChain(ordered);
    List<Row> historyRows = new ArrayList<>();
    List<Row> changesetRows = new ArrayList<>();
    Map<WrappedByteArray, LatestRows> latestRows = new LinkedHashMap<>();
    List<CommitDigestRow> commitRows = new ArrayList<>();
    Set<WrappedByteArray> changesetKeys = new HashSet<>();
    int rowCount = 0;
    for (ArchiveChangeRecord record : ordered) {
      if (range != null) {
        validateRecordInRange(range, record);
      }
      ArchiveDomain domain = record.getDomain();
      byte[] canonicalKey = record.getCanonicalKey();
      byte[] historyKey = ArchiveTemporalCodec.historyKey(
          domain, canonicalKey, record.getTxNum());
      byte[] historyValue = ArchiveTemporalCodec.encodeValue(record.getPrevValue());
      byte[] changesetKey = ArchiveTemporalCodec.changesetKey(
          record.getTxNum(), domain, canonicalKey);
      byte[] changesetValue = ArchiveTemporalCodec.encodeValue(record.getValue());
      if (!changesetKeys.add(WrappedByteArray.copyOf(changesetKey))) {
        throw new ArchiveException("archive temporal duplicate changeset row");
      }
      historyRows.add(new Row(historyKey, historyValue));
      changesetRows.add(new Row(changesetKey, changesetValue));
      byte[] latestKey = ArchiveTemporalCodec.latestKey(domain, canonicalKey);
      latestRows.put(WrappedByteArray.copyOf(latestKey),
          new LatestRows(latestKey,
              ArchiveTemporalCodec.latestBaselineKey(domain, canonicalKey), changesetValue));
      commitRows.add(new CommitDigestRow(changesetKey, changesetValue, historyKey, historyValue));
      rowCount++;
    }
    // Fold the block-commit digest in changeset-key order to match the physical CHANGESET
    // iteration in readBlockRows(), mirroring the Rocks BY_CHANGESET_KEY sort. RECORD_ORDER
    // (canonicalKey lex-then-length) diverges from the stored order (keyLen-then-canonicalKey)
    // for multi-key (txNum, domain) groups, which would fail-stop commit-marker validation.
    commitRows.sort(COMMIT_DIGEST_ROW_BY_CHANGESET_KEY);
    MessageDigest digest = newDigest();
    for (CommitDigestRow row : commitRows) {
      updateDigest(digest, row.changesetKey);
      updateDigest(digest, row.changesetValue);
      updateDigest(digest, row.historyKey);
      updateDigest(digest, row.historyValue);
    }
    return new PreparedChanges(historyRows, changesetRows, latestRows, rowCount, digest.digest());
  }

  private void validatePrevValueChain(List<ArchiveChangeRecord> ordered) {
    Map<WrappedByteArray, DomainValue> stagedLatest = new HashMap<>();
    for (ArchiveChangeRecord record : ordered) {
      byte[] latestKey = ArchiveTemporalCodec.latestKey(
          record.getDomain(), record.getCanonicalKey());
      WrappedByteArray wrapped = WrappedByteArray.copyOf(latestKey);
      DomainValue expected = stagedLatest.get(wrapped);
      if (expected == null) {
        byte[] current = db.get(UnifiedArchiveColumnFamily.LATEST, latestKey);
        expected = current == null ? null : ArchiveTemporalCodec.decodeValue(current);
      }
      if (expected != null && !expected.contentEquals(record.getPrevValue())) {
        throw new ArchiveException("archive temporal prev-value chain mismatch for txNum "
            + record.getTxNum());
      }
      stagedLatest.put(wrapped, record.getValue());
    }
  }

  @Override
  public Optional<DomainValue> getAsOf(ArchiveDomain domain, byte[] canonicalKey, long txNum) {
    try (UnifiedArchiveReadView view = db.openReadView()) {
      RocksIterator history = view.newIterator(UnifiedArchiveColumnFamily.HISTORY);
      return getAsOf(view, history, domain, canonicalKey, txNum);
    }
  }

  @Override
  public long getAsOfBackendReadCost() {
    return 2L;
  }

  @Override
  public Optional<DomainValue> latest(ArchiveDomain domain, byte[] canonicalKey) {
    byte[] value = db.get(UnifiedArchiveColumnFamily.LATEST,
        ArchiveTemporalCodec.latestKey(domain, canonicalKey));
    return value == null ? Optional.empty()
        : Optional.of(ArchiveTemporalCodec.decodeValue(value));
  }

  @Override
  public ArchiveTemporalReadView openReadView() {
    return wrapReadView(db.openReadView());
  }

  /** Wraps an already-open cross-CF snapshot; closing the adapter closes the shared view. */
  public ArchiveTemporalReadView wrapReadView(UnifiedArchiveReadView view) {
    return new SnapshotView(view);
  }

  @Override
  public void validateCommittedBlock(ArchiveBlockRange range) {
    try (UnifiedArchiveReadView view = db.openReadView()) {
      validateCommittedBlock(view, range);
    }
  }

  private void validateCommittedBlock(UnifiedArchiveReadView view, ArchiveBlockRange range) {
    byte[] marker = view.get(UnifiedArchiveColumnFamily.BLOCK_MARKER,
        ArchiveTemporalCodec.blockCommitKey(range.getBlockNum()));
    BlockRows rows = readBlockRows(view, range);
    if (!ArchiveTemporalCodec.blockCommitMatches(
        marker, range, rows.count, rows.digest)) {
      throw new ArchiveException("archive temporal commit marker missing for block "
          + range.getBlockNum());
    }
  }

  @Override
  public void reconcilePublishedBlock(ArchiveBlockRange range,
      List<ArchiveChangeRecord> records) {
    // A UNIFIED publication cannot expose the index without its temporal marker and rows.
    validateCommittedBlock(range);
  }

  public void validateStartupTail(Optional<ArchiveBlockRange> lastRange) {
    try (UnifiedArchiveReadView view = db.openReadView()) {
      RocksIterator iterator = view.newIterator(UnifiedArchiveColumnFamily.BLOCK_MARKER);
      iterator.seekForPrev(ArchiveTemporalCodec.blockCommitKey(Long.MAX_VALUE));
      ArchiveRocksIterators.requireOk(iterator, "UNIFIED_V1 locate last temporal marker");
      byte[] prefix = ArchiveTemporalCodec.blockCommitPrefix();
      boolean hasMarker = iterator.isValid()
          && ArchiveTemporalCodec.startsWith(iterator.key(), prefix);
      if (!lastRange.isPresent()) {
        if (hasMarker) {
          throw new ArchiveException(
              "archive temporal commit marker exists without an index range");
        }
        return;
      }
      if (!hasMarker) {
        throw new ArchiveException("archive index tail has no temporal commit marker for block "
            + lastRange.get().getBlockNum());
      }
      long markerBlock = ArchiveTemporalCodec.blockNumOfBlockCommitKey(iterator.key());
      if (markerBlock != lastRange.get().getBlockNum()) {
        throw new ArchiveException("archive temporal/index tail mismatch: marker=" + markerBlock
            + ", index=" + lastRange.get().getBlockNum());
      }
      validateCommittedBlock(view, lastRange.get());
    }
  }

  public void validateCommitMarkersCovered(LongPredicate hasIndexRange) {
    try (UnifiedArchiveReadView view = db.openReadView()) {
      RocksIterator iterator = view.newIterator(UnifiedArchiveColumnFamily.BLOCK_MARKER);
      byte[] prefix = ArchiveTemporalCodec.blockCommitPrefix();
      iterator.seekToFirst();
      while (iterator.isValid()) {
        if (!ArchiveTemporalCodec.startsWith(iterator.key(), prefix)) {
          throw new ArchiveException(
              "UNIFIED_V1 block-marker column family has an unknown key");
        }
        long blockNum = ArchiveTemporalCodec.blockNumOfBlockCommitKey(iterator.key());
        if (!hasIndexRange.test(blockNum)) {
          throw new ArchiveException(
              "archive temporal commit marker has no index range for block " + blockNum);
        }
        iterator.next();
      }
      ArchiveRocksIterators.requireOk(iterator, "UNIFIED_V1 validate temporal markers");
    }
  }

  public void validateTxNumsCovered(LongPredicate hasCommittedTxNum) {
    try (UnifiedArchiveReadView view = db.openReadView()) {
      RocksIterator history = view.newIterator(UnifiedArchiveColumnFamily.HISTORY);
      history.seek(new byte[] {ArchiveTemporalCodec.HISTORY_PREFIX});
      while (history.isValid()
          && history.key()[0] == ArchiveTemporalCodec.HISTORY_PREFIX) {
        byte[] historyKey = history.key();
        long txNum = ArchiveTemporalCodec.txNumOfHistory(historyKey);
        if (!hasCommittedTxNum.test(txNum)) {
          throw new ArchiveException(
              "archive temporal history txNum has no index position: " + txNum);
        }
        if (view.get(UnifiedArchiveColumnFamily.CHANGESET,
            ArchiveTemporalCodec.changesetKeyOfHistory(historyKey)) == null) {
          throw new ArchiveException(
              "archive temporal changeset missing for history txNum " + txNum);
        }
        if (view.get(UnifiedArchiveColumnFamily.LATEST,
            ArchiveTemporalCodec.latestKeyOfHistory(historyKey)) == null) {
          throw new ArchiveException(
              "archive temporal latest missing for history txNum " + txNum);
        }
        history.next();
      }
      ArchiveRocksIterators.requireOk(history, "UNIFIED_V1 validate temporal history");

      RocksIterator changeset = view.newIterator(UnifiedArchiveColumnFamily.CHANGESET);
      changeset.seek(new byte[] {ArchiveTemporalCodec.CHANGESET_PREFIX});
      while (changeset.isValid()
          && changeset.key()[0] == ArchiveTemporalCodec.CHANGESET_PREFIX) {
        long txNum = ArchiveTemporalCodec.txNumOfChangeset(changeset.key());
        if (!hasCommittedTxNum.test(txNum)
            || view.get(UnifiedArchiveColumnFamily.HISTORY,
                ArchiveTemporalCodec.historyKeyOfChangeset(changeset.key())) == null) {
          throw new ArchiveException(
              "archive temporal changeset has no index/history for txNum " + txNum);
        }
        ArchiveTemporalCodec.decodeValue(changeset.value());
        changeset.next();
      }
      ArchiveRocksIterators.requireOk(changeset, "UNIFIED_V1 validate temporal changesets");
      validateLatestRows(view);
    }
  }

  public void validateDomainRows() {
    try (UnifiedArchiveReadView view = db.openReadView()) {
      validateLatestDomainRows(view);
      validateDomainRows(view, UnifiedArchiveColumnFamily.HISTORY,
          ArchiveTemporalCodec.HISTORY_PREFIX);
      validateDomainRows(view, UnifiedArchiveColumnFamily.CHANGESET,
          ArchiveTemporalCodec.CHANGESET_PREFIX);
      validateCommitmentEmpty(view);
    }
  }

  private void validateLatestDomainRows(UnifiedArchiveReadView view) {
    RocksIterator iterator = view.newIterator(UnifiedArchiveColumnFamily.LATEST);
    byte[] baselinePrefix = ArchiveTemporalCodec.latestBaselinePrefix();
    iterator.seekToFirst();
    while (iterator.isValid()) {
      byte[] key = iterator.key();
      if (key.length > 0 && key[0] == ArchiveTemporalCodec.LATEST_PREFIX) {
        RocksDbArchiveTemporalStore.validateDomainRow(
            catalog, key, iterator.value(), true, dynamicKeyPolicy);
      } else if (ArchiveTemporalCodec.startsWith(key, baselinePrefix)) {
        RocksDbArchiveTemporalStore.validateDomainRow(catalog,
            ArchiveTemporalCodec.latestKeyOfBaseline(key), iterator.value(), true,
            dynamicKeyPolicy);
      } else {
        throw new ArchiveException("UNIFIED_V1 latest column family has an unknown key");
      }
      iterator.next();
    }
    ArchiveRocksIterators.requireOk(iterator, "UNIFIED_V1 validate latest domain rows");
  }

  private void validateDomainRows(UnifiedArchiveReadView view,
      UnifiedArchiveColumnFamily columnFamily, byte prefix) {
    RocksIterator iterator = view.newIterator(columnFamily);
    iterator.seekToFirst();
    while (iterator.isValid()) {
      if (iterator.key().length == 0 || iterator.key()[0] != prefix) {
        throw new ArchiveException("UNIFIED_V1 " + columnFamily.getName()
            + " column family has an unknown key");
      }
      RocksDbArchiveTemporalStore.validateDomainRow(
          catalog, iterator.key(), iterator.value(), true, dynamicKeyPolicy);
      iterator.next();
    }
    ArchiveRocksIterators.requireOk(iterator,
        "UNIFIED_V1 validate domain rows in " + columnFamily.getName());
  }

  private static void validateCommitmentEmpty(UnifiedArchiveReadView view) {
    RocksIterator iterator = view.newIterator(UnifiedArchiveColumnFamily.COMMITMENT);
    iterator.seekToFirst();
    ArchiveRocksIterators.requireOk(iterator, "UNIFIED_V1 validate commitment keyspace");
    if (iterator.isValid()) {
      throw new ArchiveException(
          "UNIFIED_V1 commitment column family must be empty in TVM_STATE_ONLY P0");
    }
  }

  public boolean hasCommitMarker(long blockNum) {
    return db.get(UnifiedArchiveColumnFamily.BLOCK_MARKER,
        ArchiveTemporalCodec.blockCommitKey(blockNum)) != null;
  }

  @Override
  public void unwind(long fromTxNum) {
    unwind(fromTxNum, Long.MAX_VALUE, -1L, false);
  }

  @Override
  public void unwindBlock(ArchiveBlockRange range) {
    validateCommittedBlock(range);
    validateHeadBlock(range.getBlockNum());
    unwind(range.getFirstTxNum(), range.getLastTxNum(), range.getBlockNum(), true);
  }

  private void unwind(long fromTxNum, long toTxNum, long blockNum,
      boolean deleteBlockMarker) {
    if (fromTxNum < 0 || toTxNum < 0) {
      throw new ArchiveException("archive temporal txNum must be non-negative");
    }
    try (UnifiedArchiveReadView view = db.openReadView()) {
      UnifiedArchiveMaintenanceBatch batch = new UnifiedArchiveMaintenanceBatch();
      Map<WrappedByteArray, RestoreRows> restore = new LinkedHashMap<>();
      RocksIterator changeset = view.newIterator(UnifiedArchiveColumnFamily.CHANGESET);
      changeset.seek(ArchiveTemporalCodec.changesetSeekFrom(fromTxNum));
      while (changeset.isValid()
          && changeset.key()[0] == ArchiveTemporalCodec.CHANGESET_PREFIX) {
        byte[] changesetKey = changeset.key().clone();
        long txNum = ArchiveTemporalCodec.txNumOfChangeset(changesetKey);
        if (txNum > toTxNum) {
          break;
        }
        byte[] historyKey = ArchiveTemporalCodec.historyKeyOfChangeset(changesetKey);
        byte[] historyValue = view.get(UnifiedArchiveColumnFamily.HISTORY, historyKey);
        if (historyValue == null) {
          throw new ArchiveException("archive temporal history missing for unwind txNum " + txNum);
        }
        byte[] historyPrefix = ArchiveTemporalCodec.historyPrefixOfChangeset(changesetKey);
        restore.putIfAbsent(WrappedByteArray.copyOf(historyPrefix),
            new RestoreRows(historyPrefix, historyValue));
        batch.delete(UnifiedArchiveColumnFamily.HISTORY, historyKey);
        batch.delete(UnifiedArchiveColumnFamily.CHANGESET, changesetKey);
        changeset.next();
      }
      ArchiveRocksIterators.requireOk(changeset, "UNIFIED_V1 unwind changesets");

      if (fromTxNum == 0L) {
        deleteAll(view, batch, UnifiedArchiveColumnFamily.LATEST,
            new byte[] {ArchiveTemporalCodec.LATEST_PREFIX});
        deleteAll(view, batch, UnifiedArchiveColumnFamily.LATEST,
            ArchiveTemporalCodec.latestBaselinePrefix());
        deleteAll(view, batch, UnifiedArchiveColumnFamily.BLOCK_MARKER,
            ArchiveTemporalCodec.blockCommitPrefix());
      } else {
        for (RestoreRows rows : restore.values()) {
          byte[] latestKey = rows.historyPrefix.clone();
          latestKey[0] = ArchiveTemporalCodec.LATEST_PREFIX;
          byte[] baselineKey = ArchiveTemporalCodec.latestBaselineKeyOfLatest(latestKey);
          batch.put(UnifiedArchiveColumnFamily.LATEST, latestKey, rows.value);
          if (hasHistoryBefore(view, rows.historyPrefix, fromTxNum)) {
            batch.delete(UnifiedArchiveColumnFamily.LATEST, baselineKey);
          } else {
            batch.put(UnifiedArchiveColumnFamily.LATEST, baselineKey, rows.value);
          }
        }
      }
      if (deleteBlockMarker && fromTxNum != 0L) {
        batch.delete(UnifiedArchiveColumnFamily.BLOCK_MARKER,
            ArchiveTemporalCodec.blockCommitKey(blockNum));
      }
      db.writeMaintenanceAtomically(batch);
    }
  }

  private void validateHeadBlock(long blockNum) {
    try (UnifiedArchiveReadView view = db.openReadView()) {
      RocksIterator iterator = view.newIterator(UnifiedArchiveColumnFamily.BLOCK_MARKER);
      iterator.seekForPrev(ArchiveTemporalCodec.blockCommitKey(Long.MAX_VALUE));
      ArchiveRocksIterators.requireOk(iterator, "UNIFIED_V1 validate temporal head");
      if (!iterator.isValid()
          || ArchiveTemporalCodec.blockNumOfBlockCommitKey(iterator.key()) != blockNum) {
        throw new ArchiveException("cannot unwind archive temporal block " + blockNum
            + ": not temporal head");
      }
    }
  }

  private static void deleteAll(UnifiedArchiveReadView view,
      UnifiedArchiveMaintenanceBatch batch, UnifiedArchiveColumnFamily columnFamily,
      byte[] prefix) {
    RocksIterator iterator = view.newIterator(columnFamily);
    iterator.seek(prefix);
    while (iterator.isValid() && ArchiveTemporalCodec.startsWith(iterator.key(), prefix)) {
      batch.delete(columnFamily, iterator.key().clone());
      iterator.next();
    }
    ArchiveRocksIterators.requireOk(iterator,
        "UNIFIED_V1 delete temporal prefix from " + columnFamily.getName());
  }

  private static boolean hasHistoryBefore(UnifiedArchiveReadView view, byte[] prefix,
      long txNum) {
    RocksIterator iterator = view.newIterator(UnifiedArchiveColumnFamily.HISTORY);
    iterator.seek(prefix);
    ArchiveRocksIterators.requireOk(iterator, "UNIFIED_V1 history prefix probe");
    return iterator.isValid() && ArchiveTemporalCodec.startsWith(iterator.key(), prefix)
        && ArchiveTemporalCodec.txNumOfHistory(iterator.key()) < txNum;
  }

  private void validateLatestRows(UnifiedArchiveReadView view) {
    RocksIterator latest = view.newIterator(UnifiedArchiveColumnFamily.LATEST);
    RocksIterator history = view.newIterator(UnifiedArchiveColumnFamily.HISTORY);
    latest.seek(new byte[] {ArchiveTemporalCodec.LATEST_PREFIX});
    while (latest.isValid() && latest.key()[0] == ArchiveTemporalCodec.LATEST_PREFIX) {
      byte[] latestKey = latest.key();
      byte[] historyPrefix = ArchiveTemporalCodec.historyPrefixOfLatest(latestKey);
      history.seekForPrev(ArchiveTemporalCodec.historyKey(
          ArchiveTemporalCodec.domainOfLatestKey(latestKey),
          ArchiveTemporalCodec.canonicalKeyOfLatestKey(latestKey), Long.MAX_VALUE));
      ArchiveRocksIterators.requireOk(history, "UNIFIED_V1 validate latest history");
      if (history.isValid() && ArchiveTemporalCodec.startsWith(history.key(), historyPrefix)) {
        byte[] expected = view.get(UnifiedArchiveColumnFamily.CHANGESET,
            ArchiveTemporalCodec.changesetKeyOfHistory(history.key()));
        if (expected == null || !Arrays.equals(expected, latest.value())) {
          throw new ArchiveException("archive temporal latest value mismatch");
        }
        if (view.get(UnifiedArchiveColumnFamily.LATEST,
            ArchiveTemporalCodec.latestBaselineKeyOfLatest(latestKey)) != null) {
          throw new ArchiveException(
              "archive temporal latest baseline marker has a history row");
        }
      } else {
        byte[] baseline = view.get(UnifiedArchiveColumnFamily.LATEST,
            ArchiveTemporalCodec.latestBaselineKeyOfLatest(latestKey));
        if (baseline == null || !Arrays.equals(baseline, latest.value())) {
          throw new ArchiveException("archive temporal latest has no history row or baseline");
        }
      }
      latest.next();
    }
    ArchiveRocksIterators.requireOk(latest, "UNIFIED_V1 validate latest rows");
    validateLatestBaselineRows(view, history);
  }

  private static void validateLatestBaselineRows(UnifiedArchiveReadView view,
      RocksIterator history) {
    byte[] prefix = ArchiveTemporalCodec.latestBaselinePrefix();
    RocksIterator baseline = view.newIterator(UnifiedArchiveColumnFamily.LATEST);
    baseline.seek(prefix);
    while (baseline.isValid() && ArchiveTemporalCodec.startsWith(baseline.key(), prefix)) {
      byte[] latestKey = ArchiveTemporalCodec.latestKeyOfBaseline(baseline.key());
      byte[] latestValue = view.get(UnifiedArchiveColumnFamily.LATEST, latestKey);
      if (latestValue == null || !Arrays.equals(latestValue, baseline.value())) {
        throw new ArchiveException(
            "archive temporal latest baseline has no matching latest row");
      }
      byte[] historyPrefix = ArchiveTemporalCodec.historyPrefixOfLatest(latestKey);
      history.seek(historyPrefix);
      ArchiveRocksIterators.requireOk(history,
          "UNIFIED_V1 validate latest baseline history");
      if (history.isValid() && ArchiveTemporalCodec.startsWith(history.key(), historyPrefix)) {
        throw new ArchiveException("archive temporal latest baseline has a history row");
      }
      baseline.next();
    }
    ArchiveRocksIterators.requireOk(baseline,
        "UNIFIED_V1 validate latest baseline rows");
  }

  private BlockRows readBlockRows(UnifiedArchiveReadView view, ArchiveBlockRange range) {
    MessageDigest digest = newDigest();
    Set<WrappedByteArray> keys = new HashSet<>();
    int count = 0;
    RocksIterator changeset = view.newIterator(UnifiedArchiveColumnFamily.CHANGESET);
    changeset.seek(ArchiveTemporalCodec.changesetSeekFrom(range.getFirstTxNum()));
    while (changeset.isValid()
        && changeset.key()[0] == ArchiveTemporalCodec.CHANGESET_PREFIX) {
      byte[] changesetKey = changeset.key().clone();
      long txNum = ArchiveTemporalCodec.txNumOfChangeset(changesetKey);
      if (txNum > range.getLastTxNum()) {
        break;
      }
      if (!keys.add(WrappedByteArray.copyOf(changesetKey))) {
        throw new ArchiveException("archive temporal duplicate changeset row");
      }
      byte[] historyKey = ArchiveTemporalCodec.historyKeyOfChangeset(changesetKey);
      byte[] historyValue = view.get(UnifiedArchiveColumnFamily.HISTORY, historyKey);
      if (historyValue == null) {
        throw new ArchiveException(
            "archive temporal history missing for commit marker txNum " + txNum);
      }
      updateDigest(digest, changesetKey);
      updateDigest(digest, changeset.value());
      updateDigest(digest, historyKey);
      updateDigest(digest, historyValue);
      count++;
      changeset.next();
    }
    ArchiveRocksIterators.requireOk(changeset, "UNIFIED_V1 read block commit rows");
    return new BlockRows(count, digest.digest());
  }

  private Optional<DomainValue> getAsOf(UnifiedArchiveReadView view, RocksIterator history,
      ArchiveDomain domain, byte[] canonicalKey, long txNum) {
    return ArchiveTemporalReadSupport.getAsOf(domain, canonicalKey, txNum, history,
        key -> view.get(UnifiedArchiveColumnFamily.LATEST, key), "UNIFIED_V1 getAsOf");
  }

  private static void validateRecordInRange(ArchiveBlockRange range,
      ArchiveChangeRecord record) {
    if (record.getTxNum() < range.getFirstTxNum()
        || record.getTxNum() > range.getLastTxNum()
        || record.getPosition().getBlockNum() != range.getBlockNum()
        || record.getPosition().getSource() != range.getSource()) {
      throw new ArchiveException("archive temporal change position does not match block range "
          + range.getBlockNum());
    }
  }

  private static int compareRecords(ArchiveChangeRecord left, ArchiveChangeRecord right) {
    int result = Long.compare(left.getTxNum(), right.getTxNum());
    if (result != 0) {
      return result;
    }
    result = Integer.compare(left.getDomain().getId(), right.getDomain().getId());
    return result != 0 ? result : compareBytes(left.getCanonicalKey(), right.getCanonicalKey());
  }

  private static int compareBytes(byte[] left, byte[] right) {
    int length = StrictMathWrapper.min(left.length, right.length);
    for (int i = 0; i < length; i++) {
      int diff = (left[i] & 0xff) - (right[i] & 0xff);
      if (diff != 0) {
        return diff;
      }
    }
    return left.length - right.length;
  }

  private static MessageDigest newDigest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new ArchiveException("archive temporal block digest is unavailable", e);
    }
  }

  private static void updateDigest(MessageDigest digest, byte[] bytes) {
    int length = bytes.length;
    digest.update((byte) (length >>> 24));
    digest.update((byte) (length >>> 16));
    digest.update((byte) (length >>> 8));
    digest.update((byte) length);
    digest.update(bytes);
  }

  public void close() {
    // UnifiedArchiveTxNumIndex owns the shared DB and closes it last.
  }

  private final class SnapshotView implements ArchiveTemporalReadView {

    private final UnifiedArchiveReadView view;
    private final RocksIterator history;
    private boolean closed;

    private SnapshotView(UnifiedArchiveReadView view) {
      this.view = view;
      this.history = view.newIterator(UnifiedArchiveColumnFamily.HISTORY);
    }

    @Override
    public Optional<DomainValue> getAsOf(ArchiveDomain domain, byte[] canonicalKey, long txNum) {
      requireOpen();
      return UnifiedArchiveTemporalStore.this.getAsOf(
          view, history, domain, canonicalKey, txNum);
    }

    @Override
    public long getAsOfBackendReadCost() {
      return 2L;
    }

    @Override
    public Optional<DomainValue> latest(ArchiveDomain domain, byte[] canonicalKey) {
      requireOpen();
      byte[] value = view.get(UnifiedArchiveColumnFamily.LATEST,
          ArchiveTemporalCodec.latestKey(domain, canonicalKey));
      return value == null ? Optional.empty()
          : Optional.of(ArchiveTemporalCodec.decodeValue(value));
    }

    @Override
    public void close() {
      if (!closed) {
        closed = true;
        view.close();
      }
    }

    private void requireOpen() {
      if (closed) {
        throw new ArchiveException("UNIFIED_V1 temporal read view is closed");
      }
    }
  }

  private static final class PreparedChanges {

    private final List<Row> historyRows;
    private final List<Row> changesetRows;
    private final Map<WrappedByteArray, LatestRows> latestRows;
    private final int rowCount;
    private final byte[] digest;

    private PreparedChanges(List<Row> historyRows, List<Row> changesetRows,
        Map<WrappedByteArray, LatestRows> latestRows, int rowCount, byte[] digest) {
      this.historyRows = historyRows;
      this.changesetRows = changesetRows;
      this.latestRows = latestRows;
      this.rowCount = rowCount;
      this.digest = digest;
    }
  }

  private static final Comparator<CommitDigestRow> COMMIT_DIGEST_ROW_BY_CHANGESET_KEY =
      (left, right) -> compareBytes(left.changesetKey, right.changesetKey);

  private static final class CommitDigestRow {

    private final byte[] changesetKey;
    private final byte[] changesetValue;
    private final byte[] historyKey;
    private final byte[] historyValue;

    private CommitDigestRow(byte[] changesetKey, byte[] changesetValue, byte[] historyKey,
        byte[] historyValue) {
      this.changesetKey = changesetKey;
      this.changesetValue = changesetValue;
      this.historyKey = historyKey;
      this.historyValue = historyValue;
    }
  }

  private static final class Row {

    private final byte[] key;
    private final byte[] value;

    private Row(byte[] key, byte[] value) {
      this.key = key;
      this.value = value;
    }
  }

  private static final class LatestRows {

    private final byte[] latestKey;
    private final byte[] baselineKey;
    private final byte[] value;

    private LatestRows(byte[] latestKey, byte[] baselineKey, byte[] value) {
      this.latestKey = latestKey;
      this.baselineKey = baselineKey;
      this.value = value;
    }
  }

  private static final class RestoreRows {

    private final byte[] historyPrefix;
    private final byte[] value;

    private RestoreRows(byte[] historyPrefix, byte[] value) {
      this.historyPrefix = historyPrefix;
      this.value = value;
    }
  }

  private static final class BlockRows {

    private final int count;
    private final byte[] digest;

    private BlockRows(int count, byte[] digest) {
      this.count = count;
      this.digest = digest;
    }
  }
}
