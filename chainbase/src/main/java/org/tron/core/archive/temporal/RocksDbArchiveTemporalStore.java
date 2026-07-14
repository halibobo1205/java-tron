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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongPredicate;
import org.rocksdb.Options;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.Snapshot;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.tron.common.math.StrictMathWrapper;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.ArchiveRocksDbWriteOptions;
import org.tron.core.archive.ArchiveRocksIterators;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.domain.ArchiveDomainCatalog;
import org.tron.core.archive.domain.ArchiveDomainDescriptor;
import org.tron.core.archive.domain.DynamicKeyPolicy;
import org.tron.core.archive.domain.HistoryPolicy;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.db2.common.WrappedByteArray;

/**
 * RocksDB-backed {@link ArchiveTemporalStore} for the Erigon-v3 prev-value model. A single column
 * family holds the latest record, the txNum-versioned history (storing each change's PRE-value) and
 * the txNum-ordered changeset (storing each change's AFTER-value), distinguished by the
 * {@link ArchiveTemporalCodec} family prefix.
 * {@code getAsOf} forward-seeks the first history entry after the queried txNum and returns its
 * pre-value (falling back to latest when none exists); {@code latest} is a direct get. One column
 * family + plain KV keeps it compatible with the RocksDB shipped for both architectures
 * (5.15.10 / 9.7.4); decision-5 column-family / BlobDB tuning is a follow-on.
 */
public final class RocksDbArchiveTemporalStore implements ArchiveTemporalStore, AutoCloseable {

  private static final Comparator<ArchiveChangeRecord> RECORD_ORDER =
      RocksDbArchiveTemporalStore::compareRecords;

  static {
    RocksDB.loadLibrary();
  }

  private final Options options;
  private final RocksDB db;
  private final DynamicKeyPolicy dynamicKeyPolicy = new DynamicKeyPolicy();

  public RocksDbArchiveTemporalStore(String path) {
    this(path, true);
  }

  /** Opens the store, optionally deferring its full row scrub to an explicit maintenance run. */
  public RocksDbArchiveTemporalStore(String path, boolean validateFullKeyspace) {
    this(path, validateFullKeyspace, true, false);
  }

  /**
   * Opens with an explicit initialization policy. Strict existing-store opens never create a
   * missing RocksDB or install a missing manifest; adoption additionally uses read-only mode.
   */
  public RocksDbArchiveTemporalStore(String path, boolean validateFullKeyspace,
      boolean allowInitialize, boolean readOnly) {
    if (readOnly && allowInitialize) {
      throw new IllegalArgumentException("read-only temporal store cannot initialize");
    }
    this.options = new Options().setCreateIfMissing(allowInitialize);
    RocksDB opened = null;
    try {
      opened = readOnly ? RocksDB.openReadOnly(options, path) : RocksDB.open(options, path);
      validateOrInstallManifest(opened, validateFullKeyspace, allowInitialize);
      this.db = opened;
    } catch (RocksDBException e) {
      closeQuietly(opened);
      options.close();
      throw new ArchiveException("failed to open archive temporal store at " + path, e);
    } catch (RuntimeException e) {
      closeQuietly(opened);
      options.close();
      throw e;
    }
  }

  private static void validateOrInstallManifest(RocksDB db, boolean validateFullKeyspace,
      boolean allowInitialize) throws RocksDBException {
    byte[] manifest = db.get(ArchiveTemporalCodec.manifestKey());
    if (manifest != null) {
      if (!ArchiveTemporalCodec.manifestMatches(manifest)) {
        throw new ArchiveException("archive temporal manifest mismatch");
      }
      if (validateFullKeyspace) {
        validateCurrentKeyspace(db);
      }
      return;
    }
    if (!isEmpty(db)) {
      throw new ArchiveException("archive temporal store is non-empty but missing manifest");
    }
    if (!allowInitialize) {
      throw new ArchiveException("archive temporal store is empty or missing manifest");
    }
    try (WriteOptions writeOptions = ArchiveRocksDbWriteOptions.createForcedSync()) {
      db.put(writeOptions, ArchiveTemporalCodec.manifestKey(),
          ArchiveTemporalCodec.manifestValue());
    }
  }

  private static boolean isEmpty(RocksDB db) {
    try (RocksIterator it = db.newIterator()) {
      it.seekToFirst();
      ArchiveRocksIterators.requireOk(it, "isEmpty: scan for any row");
      return !it.isValid();
    }
  }

  private static void validateCurrentKeyspace(RocksDB db) {
    byte[] manifestKey = ArchiveTemporalCodec.manifestKey();
    byte[] blockCommitPrefix = ArchiveTemporalCodec.blockCommitPrefix();
    byte[] latestBaselinePrefix = ArchiveTemporalCodec.latestBaselinePrefix();
    try (RocksIterator it = db.newIterator()) {
      it.seekToFirst();
      while (it.isValid()) {
        byte[] key = it.key();
        byte[] value = it.value();
        if (Arrays.equals(key, manifestKey)) {
          it.next();
          continue;
        }
        if (ArchiveTemporalCodec.startsWith(key, blockCommitPrefix)) {
          long blockNum = ArchiveTemporalCodec.blockNumOfBlockCommitKey(key);
          ArchiveTemporalCodec.validateBlockCommitValue(value, blockNum);
          it.next();
          continue;
        }
        if (ArchiveTemporalCodec.startsWith(key, latestBaselinePrefix)) {
          ArchiveTemporalCodec.latestKeyOfBaseline(key);
          ArchiveTemporalCodec.decodeValue(value);
          it.next();
          continue;
        }
        if (key == null || key.length == 0) {
          throw new ArchiveException("archive temporal key is empty");
        }
        switch (key[0]) {
          case ArchiveTemporalCodec.LATEST_PREFIX:
            ArchiveTemporalCodec.historyPrefixOfLatest(key);
            ArchiveTemporalCodec.decodeValue(value);
            break;
          case ArchiveTemporalCodec.HISTORY_PREFIX:
            ArchiveTemporalCodec.txNumOfHistory(key);
            ArchiveTemporalCodec.decodeValue(value);
            break;
          case ArchiveTemporalCodec.CHANGESET_PREFIX:
            ArchiveTemporalCodec.txNumOfChangeset(key);
            ArchiveTemporalCodec.decodeValue(value);
            break;
          default:
            throw new ArchiveException("archive temporal store has unknown key prefix "
                + key[0]);
        }
        it.next();
      }
      ArchiveRocksIterators.requireOk(it, "validateCurrentKeyspace: keyspace scan");
    }
  }

  private static void closeQuietly(RocksDB db) {
    if (db != null) {
      db.close();
    }
  }

  private static void closeQuietly(AutoCloseable resource) {
    if (resource == null) {
      return;
    }
    try {
      resource.close();
    } catch (Exception ignored) {
      // Preserve the original construction failure.
    }
  }

  private static Throwable closeAndCollect(Throwable failure, Runnable closeAction) {
    try {
      closeAction.run();
    } catch (Throwable closeFailure) {
      if (failure == null) {
        return closeFailure;
      }
      if (failure != closeFailure) {
        failure.addSuppressed(closeFailure);
      }
    }
    return failure;
  }

  private static void rethrowCloseFailure(Throwable failure) {
    if (failure instanceof RuntimeException) {
      throw (RuntimeException) failure;
    }
    if (failure instanceof Error) {
      throw (Error) failure;
    }
    if (failure != null) {
      throw new ArchiveException("archive temporal read view close failed", failure);
    }
  }

  @Override
  public void putChange(ArchiveChangeRecord record) {
    try (WriteBatch batch = new WriteBatch();
         WriteOptions writeOptions = ArchiveRocksDbWriteOptions.createForcedSync()) {
      putChange(batch, record);
      db.write(writeOptions, batch);
    } catch (RocksDBException e) {
      throw new ArchiveException("archive temporal putChange failed", e);
    }
  }

  @Override
  public void putChanges(List<ArchiveChangeRecord> records) {
    List<ArchiveChangeRecord> ordered = orderedRecords(records);
    try (WriteBatch batch = new WriteBatch();
         WriteOptions writeOptions = ArchiveRocksDbWriteOptions.createForcedSync()) {
      validatePrevValueChain(ordered);
      for (ArchiveChangeRecord record : ordered) {
        putChange(batch, record);
      }
      db.write(writeOptions, batch);
    } catch (RocksDBException e) {
      throw new ArchiveException("archive temporal putChanges failed", e);
    }
  }

  @Override
  public void putBlockChanges(ArchiveBlockRange range, List<ArchiveChangeRecord> records) {
    if (records == null) {
      throw new ArchiveException("archive temporal block changes are missing");
    }
    List<ArchiveChangeRecord> ordered = orderedRecords(records);
    BlockCommitRowAccumulator rowAccumulator = new BlockCommitRowAccumulator();
    List<BlockCommitRow> commitRows = new ArrayList<>();
    try (WriteBatch batch = new WriteBatch();
         WriteOptions writeOptions = ArchiveRocksDbWriteOptions.createForcedSync()) {
      validatePrevValueChain(ordered);
      for (ArchiveChangeRecord record : ordered) {
        validateRecordInRange(range, record);
        commitRows.add(BlockCommitRow.of(record));
        putChange(batch, record);
      }
      Collections.sort(commitRows, BlockCommitRow.BY_CHANGESET_KEY);
      for (BlockCommitRow row : commitRows) {
        rowAccumulator.addRow(row.changesetKey, row.historyValue, row.changesetValue);
      }
      BlockCommitRows rows = rowAccumulator.finish();
      batch.put(ArchiveTemporalCodec.blockCommitKey(range.getBlockNum()),
          ArchiveTemporalCodec.encodeBlockCommit(range, rows.count, rows.digest));
      db.write(writeOptions, batch);
    } catch (RocksDBException e) {
      throw new ArchiveException("archive temporal putBlockChanges failed", e);
    }
  }

  private void validatePrevValueChain(List<ArchiveChangeRecord> ordered)
      throws RocksDBException {
    Map<WrappedByteArray, DomainValue> stagedLatest = new HashMap<>();
    for (ArchiveChangeRecord record : ordered) {
      WrappedByteArray key = latestKeyOf(record);
      DomainValue expected = stagedLatest.get(key);
      if (expected == null) {
        byte[] latest = db.get(ArchiveTemporalCodec.latestKey(
            record.getDomain(), record.getCanonicalKey()));
        expected = latest == null ? null : ArchiveTemporalCodec.decodeValue(latest);
      }
      if (expected != null && !sameDomainValue(expected, record.getPrevValue())) {
        throw new ArchiveException("archive temporal prev-value chain mismatch for txNum "
            + record.getTxNum());
      }
      stagedLatest.put(key, record.getValue());
    }
  }

  @Override
  public void validateCommittedBlock(ArchiveBlockRange range) {
    try {
      byte[] marker = db.get(ArchiveTemporalCodec.blockCommitKey(range.getBlockNum()));
      BlockCommitRows rows = readBlockCommitRows(range);
      if (!ArchiveTemporalCodec.blockCommitMatches(marker, range, rows.count, rows.digest)) {
        throw new ArchiveException("archive temporal commit marker missing for block "
            + range.getBlockNum());
      }
    } catch (RocksDBException e) {
      throw new ArchiveException("archive temporal commit marker read failed", e);
    }
  }

  @Override
  public void reconcilePublishedBlock(ArchiveBlockRange range,
      List<ArchiveChangeRecord> records) {
    try {
      validateCommittedBlock(range);
      return;
    } catch (RuntimeException missingTemporalCommit) {
      if (hasCommitMarker(range.getBlockNum()) || hasRowsInRange(range)) {
        throw missingTemporalCommit;
      }
      validateLatestRowsAnchored();
      putBlockChanges(range, records);
      validateCommittedBlock(range);
    }
  }

  public boolean hasCommitMarker(long blockNum) {
    try {
      return db.get(ArchiveTemporalCodec.blockCommitKey(blockNum)) != null;
    } catch (RocksDBException e) {
      throw new ArchiveException("archive temporal commit marker read failed", e);
    }
  }

  /**
   * Bounded normal-startup validation: the highest temporal marker must match the highest index
   * range, and that block's marker/digest must validate. Full historical row validation remains
   * available through the explicit scrub methods below.
   */
  public void validateStartupTail(Optional<ArchiveBlockRange> lastRange) {
    try (RocksIterator it = db.newIterator()) {
      byte[] prefix = ArchiveTemporalCodec.blockCommitPrefix();
      it.seekForPrev(ArchiveTemporalCodec.blockCommitKey(Long.MAX_VALUE));
      ArchiveRocksIterators.requireOk(it, "validateStartupTail: last commit marker seek");
      boolean hasMarker = it.isValid() && ArchiveTemporalCodec.startsWith(it.key(), prefix);
      if (!lastRange.isPresent()) {
        if (hasMarker) {
          throw new ArchiveException(
              "archive temporal commit marker exists without an index range");
        }
        return;
      }
      ArchiveBlockRange range = lastRange.get();
      if (!hasMarker) {
        throw new ArchiveException("archive index tail has no temporal commit marker for block "
            + range.getBlockNum());
      }
      long markerBlock = ArchiveTemporalCodec.blockNumOfBlockCommitKey(it.key());
      if (markerBlock != range.getBlockNum()) {
        throw new ArchiveException("archive temporal/index tail mismatch: marker=" + markerBlock
            + ", index=" + range.getBlockNum());
      }
      validateCommittedBlock(range);
    }
  }

  public boolean hasRowsInRange(ArchiveBlockRange range) {
    try (RocksIterator it = db.newIterator()) {
      it.seek(ArchiveTemporalCodec.changesetSeekFrom(range.getFirstTxNum()));
      if (it.isValid()
          && it.key()[0] == ArchiveTemporalCodec.CHANGESET_PREFIX
          && ArchiveTemporalCodec.txNumOfChangeset(it.key()) <= range.getLastTxNum()) {
        return true;
      }
      ArchiveRocksIterators.requireOk(it, "hasRowsInRange: changeset range scan");
    }
    try (RocksIterator it = db.newIterator()) {
      it.seek(new byte[] {ArchiveTemporalCodec.HISTORY_PREFIX});
      while (it.isValid() && it.key()[0] == ArchiveTemporalCodec.HISTORY_PREFIX) {
        long txNum = ArchiveTemporalCodec.txNumOfHistory(it.key());
        if (txNum >= range.getFirstTxNum() && txNum <= range.getLastTxNum()) {
          return true;
        }
        it.next();
      }
      ArchiveRocksIterators.requireOk(it, "hasRowsInRange: history range scan");
      return false;
    }
  }

  /** True when this temporal store contains any row other than its schema manifest. */
  public boolean hasDataBeyondManifest() {
    try (RocksIterator it = db.newIterator()) {
      it.seekToFirst();
      while (it.isValid()) {
        if (!Arrays.equals(it.key(), ArchiveTemporalCodec.manifestKey())) {
          return true;
        }
        it.next();
      }
      ArchiveRocksIterators.requireOk(it, "hasDataBeyondManifest: scan beyond manifest");
      return false;
    }
  }

  /** Fail closed if temporal commit markers refer to blocks absent from the txNum index. */
  public void validateCommitMarkersCovered(LongPredicate hasIndexRange) {
    byte[] prefix = ArchiveTemporalCodec.blockCommitPrefix();
    try (RocksIterator it = db.newIterator()) {
      it.seek(prefix);
      while (it.isValid() && ArchiveTemporalCodec.startsWith(it.key(), prefix)) {
        byte[] key = it.key();
        long blockNum = ArchiveTemporalCodec.blockNumOfBlockCommitKey(key);
        if (!hasIndexRange.test(blockNum)) {
          throw new ArchiveException(
              "archive temporal commit marker has no index range for block " + blockNum);
        }
        it.next();
      }
      ArchiveRocksIterators.requireOk(it, "validateCommitMarkersCovered: commit marker scan");
    }
  }

  /** Fail closed if history/changeset txNums are not covered by the committed txNum index. */
  public void validateTxNumsCovered(LongPredicate hasCommittedTxNum) {
    try (RocksIterator it = db.newIterator()) {
      it.seek(new byte[] {ArchiveTemporalCodec.HISTORY_PREFIX});
      while (it.isValid() && it.key()[0] == ArchiveTemporalCodec.HISTORY_PREFIX) {
        byte[] historyKey = it.key();
        long txNum = ArchiveTemporalCodec.txNumOfHistory(historyKey);
        if (!hasCommittedTxNum.test(txNum)) {
          throw new ArchiveException(
              "archive temporal history txNum has no index position: " + txNum);
        }
        if (db.get(ArchiveTemporalCodec.changesetKeyOfHistory(historyKey)) == null) {
          throw new ArchiveException(
              "archive temporal changeset missing for history txNum " + txNum);
        }
        if (db.get(ArchiveTemporalCodec.latestKeyOfHistory(historyKey)) == null) {
          throw new ArchiveException(
              "archive temporal latest missing for history txNum " + txNum);
        }
        it.next();
      }
      ArchiveRocksIterators.requireOk(it, "validateTxNumsCovered: history scan");
    } catch (RocksDBException e) {
      throw new ArchiveException("archive temporal history validation failed", e);
    }
    try (RocksIterator it = db.newIterator()) {
      it.seek(new byte[] {ArchiveTemporalCodec.CHANGESET_PREFIX});
      while (it.isValid() && it.key()[0] == ArchiveTemporalCodec.CHANGESET_PREFIX) {
        byte[] changesetKey = it.key();
        long txNum = ArchiveTemporalCodec.txNumOfChangeset(changesetKey);
        if (!hasCommittedTxNum.test(txNum)) {
          throw new ArchiveException(
              "archive temporal changeset txNum has no index position: " + txNum);
        }
        if (db.get(ArchiveTemporalCodec.historyKeyOfChangeset(changesetKey)) == null) {
          throw new ArchiveException(
              "archive temporal history missing for changeset txNum " + txNum);
        }
        byte[] changesetValue = it.value().clone();
        ArchiveTemporalCodec.decodeValue(changesetValue);
        it.next();
      }
      ArchiveRocksIterators.requireOk(it, "validateTxNumsCovered: changeset scan");
    } catch (RocksDBException e) {
      throw new ArchiveException("archive temporal changeset validation failed", e);
    }
    try {
      validateLatestRowsAnchoredStreaming();
    } catch (RocksDBException e) {
      throw new ArchiveException("archive temporal latest validation failed", e);
    }
  }

  /** Fail closed if any latest row is not anchored by history/changeset or an unwind baseline. */
  public void validateLatestRowsAnchored() {
    try (RocksIterator it = db.newIterator()) {
      it.seek(new byte[] {ArchiveTemporalCodec.CHANGESET_PREFIX});
      while (it.isValid() && it.key()[0] == ArchiveTemporalCodec.CHANGESET_PREFIX) {
        ArchiveTemporalCodec.decodeValue(it.value());
        it.next();
      }
      ArchiveRocksIterators.requireOk(it, "validateLatestRowsAnchored: changeset scan");
      validateLatestRowsAnchoredStreaming();
    } catch (RocksDBException e) {
      throw new ArchiveException("archive temporal latest validation failed", e);
    }
  }

  private void validateLatestRowsAnchoredStreaming()
      throws RocksDBException {
    try (RocksIterator it = db.newIterator(); RocksIterator history = db.newIterator()) {
      it.seek(new byte[] {ArchiveTemporalCodec.LATEST_PREFIX});
      while (it.isValid() && it.key()[0] == ArchiveTemporalCodec.LATEST_PREFIX) {
        byte[] latestKey = it.key();
        ArchiveDomain domain = ArchiveTemporalCodec.domainOfLatestKey(latestKey);
        byte[] canonicalKey = ArchiveTemporalCodec.canonicalKeyOfLatestKey(latestKey);
        byte[] historyPrefix = ArchiveTemporalCodec.historyPrefix(domain, canonicalKey);
        history.seekForPrev(ArchiveTemporalCodec.historyKey(domain, canonicalKey, Long.MAX_VALUE));
        ArchiveRocksIterators.requireOk(history,
            "validateLatestRowsAnchored: latest history seek");
        boolean hasHistory = history.isValid()
            && ArchiveTemporalCodec.startsWith(history.key(), historyPrefix);
        byte[] baselineValue = db.get(ArchiveTemporalCodec.latestBaselineKeyOfLatest(latestKey));
        if (!hasHistory) {
          if (baselineValue == null) {
            throw new ArchiveException(
                "archive temporal latest has no history row or baseline marker");
          }
          if (!Arrays.equals(it.value(), baselineValue)) {
            throw new ArchiveException("archive temporal latest baseline value mismatch");
          }
          it.next();
          continue;
        }
        if (baselineValue != null) {
          throw new ArchiveException("archive temporal latest baseline marker has history row");
        }
        long latestTxNum = ArchiveTemporalCodec.txNumOfHistory(history.key());
        byte[] expectedValue = db.get(
            ArchiveTemporalCodec.changesetKey(latestTxNum, domain, canonicalKey));
        if (expectedValue == null) {
          throw new ArchiveException("archive temporal latest has no changeset row");
        }
        if (!Arrays.equals(it.value(), expectedValue)) {
          throw new ArchiveException("archive temporal latest value mismatch");
        }
        it.next();
      }
      ArchiveRocksIterators.requireOk(it, "validateLatestRowsAnchored: latest scan");
      validateLatestBaselineMarkers();
    }
  }

  private boolean hasHistoryRow(byte[] latestKey) {
    byte[] historyPrefix = ArchiveTemporalCodec.historyPrefixOfLatest(latestKey);
    try (RocksIterator history = db.newIterator()) {
      history.seek(historyPrefix);
      ArchiveRocksIterators.requireOk(history, "hasHistoryRow: history prefix probe");
      return history.isValid() && ArchiveTemporalCodec.startsWith(history.key(), historyPrefix);
    }
  }

  private boolean hasHistoryBefore(byte[] historyPrefix, long txNum) {
    try (RocksIterator history = db.newIterator()) {
      history.seek(historyPrefix);
      ArchiveRocksIterators.requireOk(history, "hasHistoryBefore: history prefix probe");
      return history.isValid()
          && ArchiveTemporalCodec.startsWith(history.key(), historyPrefix)
          && ArchiveTemporalCodec.txNumOfHistory(history.key()) < txNum;
    }
  }

  private void validateLatestBaselineMarkers() throws RocksDBException {
    byte[] prefix = ArchiveTemporalCodec.latestBaselinePrefix();
    try (RocksIterator it = db.newIterator()) {
      it.seek(prefix);
      while (it.isValid() && ArchiveTemporalCodec.startsWith(it.key(), prefix)) {
        byte[] latestKey = ArchiveTemporalCodec.latestKeyOfBaseline(it.key());
        byte[] latestValue = db.get(latestKey);
        if (latestValue == null) {
          throw new ArchiveException("archive temporal latest baseline has no latest row");
        }
        if (!Arrays.equals(latestValue, it.value())) {
          throw new ArchiveException("archive temporal latest baseline value mismatch");
        }
        if (hasHistoryRow(latestKey)) {
          throw new ArchiveException("archive temporal latest baseline has history row");
        }
        it.next();
      }
      ArchiveRocksIterators.requireOk(it, "validateLatestBaselineMarkers: baseline scan");
    }
  }

  /**
   * Fail closed if persisted temporal keys or values are not canonical for their archive domain.
   * This is catalog-aware, so the service factory calls it after constructing the frozen catalog.
   */
  public void validateDomainRows(ArchiveDomainCatalog catalog) {
    try (RocksIterator it = db.newIterator()) {
      it.seek(new byte[] {ArchiveTemporalCodec.LATEST_PREFIX});
      while (it.isValid() && it.key()[0] == ArchiveTemporalCodec.LATEST_PREFIX) {
        validateDomainRow(catalog, it.key(), it.value(), true, dynamicKeyPolicy);
        it.next();
      }
      ArchiveRocksIterators.requireOk(it, "validateDomainRows: latest scan");
    }
    try (RocksIterator it = db.newIterator()) {
      it.seek(new byte[] {ArchiveTemporalCodec.HISTORY_PREFIX});
      while (it.isValid() && it.key()[0] == ArchiveTemporalCodec.HISTORY_PREFIX) {
        validateDomainRow(catalog, it.key(), it.value(), true, dynamicKeyPolicy);
        it.next();
      }
      ArchiveRocksIterators.requireOk(it, "validateDomainRows: history scan");
    }
    try (RocksIterator it = db.newIterator()) {
      it.seek(new byte[] {ArchiveTemporalCodec.CHANGESET_PREFIX});
      while (it.isValid() && it.key()[0] == ArchiveTemporalCodec.CHANGESET_PREFIX) {
        validateDomainRow(catalog, it.key(), it.value(), true, dynamicKeyPolicy);
        it.next();
      }
      ArchiveRocksIterators.requireOk(it, "validateDomainRows: changeset scan");
    }
    byte[] latestBaselinePrefix = ArchiveTemporalCodec.latestBaselinePrefix();
    try (RocksIterator it = db.newIterator()) {
      it.seek(latestBaselinePrefix);
      while (it.isValid() && ArchiveTemporalCodec.startsWith(it.key(), latestBaselinePrefix)) {
        validateDomainRow(catalog, ArchiveTemporalCodec.latestKeyOfBaseline(it.key()), it.value(),
            true, dynamicKeyPolicy);
        it.next();
      }
      ArchiveRocksIterators.requireOk(it, "validateDomainRows: baseline scan");
    }
  }

  static void validateDomainRow(ArchiveDomainCatalog catalog, byte[] key, byte[] value,
      boolean validateValue, DynamicKeyPolicy dynamicKeyPolicy) {
    ArchiveDomain domain;
    byte[] canonicalKey;
    switch (key[0]) {
      case ArchiveTemporalCodec.LATEST_PREFIX:
        domain = ArchiveTemporalCodec.domainOfLatestKey(key);
        canonicalKey = ArchiveTemporalCodec.canonicalKeyOfLatestKey(key);
        break;
      case ArchiveTemporalCodec.HISTORY_PREFIX:
        domain = ArchiveTemporalCodec.domainOfHistoryKey(key);
        canonicalKey = ArchiveTemporalCodec.canonicalKeyOfHistoryKey(key);
        break;
      case ArchiveTemporalCodec.CHANGESET_PREFIX:
        domain = ArchiveTemporalCodec.domainOfChangesetKey(key);
        canonicalKey = ArchiveTemporalCodec.canonicalKeyOfChangesetKey(key);
        break;
      default:
        throw new ArchiveException("archive temporal store has unknown key prefix " + key[0]);
    }
    ArchiveDomainDescriptor descriptor = catalog.descriptorFor(domain);
    if (descriptor == null) {
      throw new ArchiveException("archive temporal catalog missing descriptor for " + domain);
    }
    descriptor.getKeyCodec().validate(canonicalKey);
    validateKeyPolicy(domain, canonicalKey, dynamicKeyPolicy);
    if (validateValue) {
      descriptor.getValueCodec().validate(ArchiveTemporalCodec.decodeValue(value));
    }
  }

  private static void validateKeyPolicy(ArchiveDomain domain, byte[] canonicalKey,
      DynamicKeyPolicy dynamicKeyPolicy) {
    if (domain == ArchiveDomain.DYNAMIC_PROPERTIES
        && dynamicKeyPolicy.decision(canonicalKey).getHistoryPolicy() == HistoryPolicy.NO_ARCHIVE) {
      throw new ArchiveException("archive temporal dynamic property is not archived");
    }
  }

  private static void putChange(WriteBatch batch, ArchiveChangeRecord record)
      throws RocksDBException {
    ArchiveDomain domain = record.getDomain();
    byte[] key = record.getCanonicalKey();
    byte[] prevValue = ArchiveTemporalCodec.encodeValue(record.getPrevValue());
    byte[] newValue = ArchiveTemporalCodec.encodeValue(record.getValue());
    // history stores the value BEFORE the change (Erigon prev-value); latest the value AFTER.
    batch.put(ArchiveTemporalCodec.latestKey(domain, key), newValue);
    batch.put(ArchiveTemporalCodec.historyKey(domain, key, record.getTxNum()), prevValue);
    // changeset row (txNum-ordered) stores the AFTER value so validation can bind latest.
    batch.put(ArchiveTemporalCodec.changesetKey(record.getTxNum(), domain, key), newValue);
    batch.delete(ArchiveTemporalCodec.latestBaselineKey(domain, key));
  }

  @Override
  public Optional<DomainValue> getAsOf(ArchiveDomain domain, byte[] canonicalKey, long txNum) {
    return getAsOf(domain, canonicalKey, txNum, null, null);
  }

  @Override
  public long getAsOfBackendReadCost() {
    // A history seek may miss and fall through to a latest-key point lookup.
    return 2L;
  }

  // readOptions == null -> a live read; a snapshot ReadOptions -> an isolated point-in-time read.
  private Optional<DomainValue> getAsOf(ArchiveDomain domain, byte[] canonicalKey, long txNum,
      ReadOptions readOptions, RocksIterator reusableHistoryIterator) {
    if (reusableHistoryIterator != null) {
      return ArchiveTemporalReadSupport.getAsOf(domain, canonicalKey, txNum,
          reusableHistoryIterator, key -> get(key, readOptions), "getAsOf");
    }
    try (RocksIterator iterator = readOptions == null
        ? db.newIterator() : db.newIterator(readOptions)) {
      return ArchiveTemporalReadSupport.getAsOf(domain, canonicalKey, txNum,
          iterator, key -> get(key, readOptions), "getAsOf");
    }
  }

  @Override
  public Optional<DomainValue> latest(ArchiveDomain domain, byte[] canonicalKey) {
    return latest(domain, canonicalKey, null);
  }

  private Optional<DomainValue> latest(ArchiveDomain domain, byte[] canonicalKey,
      ReadOptions readOptions) {
    byte[] value = get(ArchiveTemporalCodec.latestKey(domain, canonicalKey), readOptions);
    return value == null ? Optional.empty()
        : Optional.of(ArchiveTemporalCodec.decodeValue(value));
  }

  private byte[] get(byte[] key, ReadOptions readOptions) {
    try {
      return readOptions == null ? db.get(key) : db.get(readOptions, key);
    } catch (RocksDBException e) {
      throw new ArchiveException("archive temporal latest failed", e);
    }
  }

  @Override
  public ArchiveTemporalReadView openReadView() {
    Snapshot snapshot = db.getSnapshot();
    ReadOptions readOptions = null;
    RocksIterator historyIterator = null;
    try {
      readOptions = new ReadOptions().setSnapshot(snapshot);
      historyIterator = db.newIterator(readOptions);
      return new SnapshotReadView(
          readOptions, historyIterator, () -> db.releaseSnapshot(snapshot));
    } catch (RuntimeException e) {
      closeQuietly(historyIterator);
      closeQuietly(readOptions);
      db.releaseSnapshot(snapshot);
      throw e;
    }
  }

  final class SnapshotReadView implements ArchiveTemporalReadView {

    private final ReadOptions readOptions;
    private final RocksIterator historyIterator;
    private final Runnable releaseSnapshot;
    private final Thread ownerThread;
    private final AtomicBoolean closed = new AtomicBoolean();

    SnapshotReadView(ReadOptions readOptions, RocksIterator historyIterator,
        Runnable releaseSnapshot) {
      this.readOptions = readOptions;
      this.historyIterator = historyIterator;
      this.releaseSnapshot = releaseSnapshot;
      this.ownerThread = Thread.currentThread();
    }

    @Override
    public Optional<DomainValue> getAsOf(ArchiveDomain domain, byte[] canonicalKey, long txNum) {
      requireOwnerAndOpen();
      return RocksDbArchiveTemporalStore.this.getAsOf(
          domain, canonicalKey, txNum, readOptions, historyIterator);
    }

    @Override
    public long getAsOfBackendReadCost() {
      return RocksDbArchiveTemporalStore.this.getAsOfBackendReadCost();
    }

    @Override
    public Optional<DomainValue> latest(ArchiveDomain domain, byte[] canonicalKey) {
      requireOwnerAndOpen();
      return RocksDbArchiveTemporalStore.this.latest(domain, canonicalKey, readOptions);
    }

    @Override
    public void close() {
      requireOwner();
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      Throwable failure = null;
      failure = closeAndCollect(failure, historyIterator::close);
      failure = closeAndCollect(failure, readOptions::close);
      failure = closeAndCollect(failure, releaseSnapshot);
      rethrowCloseFailure(failure);
    }

    private void requireOwnerAndOpen() {
      requireOwner();
      if (closed.get()) {
        throw new ArchiveException("archive temporal read view is closed");
      }
    }

    private void requireOwner() {
      if (Thread.currentThread() != ownerThread) {
        throw new ArchiveException("archive temporal read view used from a non-owner thread");
      }
    }
  }

  @Override
  public void unwind(long fromTxNum) {
    unwind(fromTxNum, Long.MAX_VALUE, 0L, false);
  }

  @Override
  public void unwindBlock(ArchiveBlockRange range) {
    validateCommittedBlock(range);
    validateHeadBlock(range);
    unwind(range.getFirstTxNum(), range.getLastTxNum(), range.getBlockNum(), true);
  }

  private void validateHeadBlock(ArchiveBlockRange range) {
    byte[] prefix = ArchiveTemporalCodec.blockCommitPrefix();
    try (RocksIterator it = db.newIterator()) {
      it.seek(prefix);
      while (it.isValid() && ArchiveTemporalCodec.startsWith(it.key(), prefix)) {
        long blockNum = ArchiveTemporalCodec.blockNumOfBlockCommitKey(it.key());
        if (blockNum > range.getBlockNum()) {
          throw new ArchiveException("cannot unwind archive temporal block "
              + range.getBlockNum() + ": not temporal head");
        }
        it.next();
      }
      ArchiveRocksIterators.requireOk(it, "validateHeadBlock: commit marker scan");
    }
  }

  private void unwind(long fromTxNum, long toTxNum, long blockNum, boolean deleteBlockMarker) {
    if (fromTxNum < 0 || toTxNum < 0) {
      throw new ArchiveException("archive temporal txNum must be non-negative");
    }
    // One atomic batch: delete each reverted change's history + changeset entry, and reset each
    // affected key's latest to the prevValue of its SMALLEST dropped change. If that value is a
    // tombstone, latest records "known absent"; it is not the same as unknown archive coverage.
    Map<WrappedByteArray, byte[]> affectedPrefix = new LinkedHashMap<>();
    Map<WrappedByteArray, byte[]> restore = new HashMap<>();
    try (WriteBatch batch = new WriteBatch();
         WriteOptions writeOptions = ArchiveRocksDbWriteOptions.createForcedSync()) {
      try (RocksIterator it = db.newIterator()) {
        it.seek(ArchiveTemporalCodec.changesetSeekFrom(fromTxNum));
        while (it.isValid() && it.key()[0] == ArchiveTemporalCodec.CHANGESET_PREFIX) {
          byte[] changesetKey = it.key();
          long txNum = ArchiveTemporalCodec.txNumOfChangeset(changesetKey);
          if (txNum > toTxNum) {
            break;
          }
          byte[] historyKey = ArchiveTemporalCodec.historyKeyOfChangeset(changesetKey);
          byte[] historyPrefix = ArchiveTemporalCodec.historyPrefixOfChangeset(changesetKey);
          WrappedByteArray prefix = WrappedByteArray.of(historyPrefix);
          if (!affectedPrefix.containsKey(prefix)) {
            // ascending changeset scan -> first hit is the smallest dropped txNum for this key.
            affectedPrefix.put(prefix, historyPrefix);
            byte[] prevValue = db.get(historyKey);
            if (prevValue == null) {
              throw new ArchiveException("archive temporal history missing for unwind txNum "
                  + ArchiveTemporalCodec.txNumOfChangeset(changesetKey));
            }
            restore.put(prefix, prevValue); // its prevValue, read before the delete lands
          }
          batch.delete(historyKey);
          batch.delete(changesetKey);
          it.next();
        }
        ArchiveRocksIterators.requireOk(it, "unwind: changeset scan");
      }
      for (Map.Entry<WrappedByteArray, byte[]> e : affectedPrefix.entrySet()) {
        byte[] latestKey = e.getValue().clone();
        latestKey[0] = ArchiveTemporalCodec.LATEST_PREFIX;
        byte[] restoreValue = restore.get(e.getKey());
        batch.put(latestKey, restoreValue);
        byte[] baselineKey = ArchiveTemporalCodec.latestBaselineKeyOfLatest(latestKey);
        if (fromTxNum == 0 || hasHistoryBefore(e.getValue(), fromTxNum)) {
          batch.delete(baselineKey);
        } else {
          batch.put(baselineKey, restoreValue);
        }
      }
      if (fromTxNum == 0) {
        deleteAllLatest(batch);
        deleteAllLatestBaselines(batch);
        deleteAllBlockCommitMarkers(batch);
      }
      if (deleteBlockMarker) {
        batch.delete(ArchiveTemporalCodec.blockCommitKey(blockNum));
      }
      db.write(writeOptions, batch);
    } catch (RocksDBException e) {
      throw new ArchiveException("archive temporal unwind failed", e);
    }
  }

  private void deleteAllLatest(WriteBatch batch) throws RocksDBException {
    try (RocksIterator it = db.newIterator()) {
      it.seek(new byte[] {ArchiveTemporalCodec.LATEST_PREFIX});
      while (it.isValid() && it.key()[0] == ArchiveTemporalCodec.LATEST_PREFIX) {
        batch.delete(it.key().clone());
        it.next();
      }
      ArchiveRocksIterators.requireOk(it, "deleteAllLatest: latest scan");
    }
  }

  private void deleteAllLatestBaselines(WriteBatch batch) throws RocksDBException {
    byte[] prefix = ArchiveTemporalCodec.latestBaselinePrefix();
    try (RocksIterator it = db.newIterator()) {
      it.seek(prefix);
      while (it.isValid() && ArchiveTemporalCodec.startsWith(it.key(), prefix)) {
        batch.delete(it.key().clone());
        it.next();
      }
      ArchiveRocksIterators.requireOk(it, "deleteAllLatestBaselines: baseline scan");
    }
  }

  private void deleteAllBlockCommitMarkers(WriteBatch batch) throws RocksDBException {
    byte[] prefix = ArchiveTemporalCodec.blockCommitPrefix();
    try (RocksIterator it = db.newIterator()) {
      it.seek(prefix);
      while (it.isValid() && ArchiveTemporalCodec.startsWith(it.key(), prefix)) {
        batch.delete(it.key().clone());
        it.next();
      }
      ArchiveRocksIterators.requireOk(it, "deleteAllBlockCommitMarkers: marker scan");
    }
  }

  private static void validateRecordInRange(ArchiveBlockRange range, ArchiveChangeRecord record) {
    long txNum = record.getTxNum();
    if (txNum < range.getFirstTxNum() || txNum > range.getLastTxNum()) {
      throw new ArchiveException("archive temporal change txNum " + txNum
          + " is outside committed block range " + range.getBlockNum());
    }
    if (record.getPosition().getBlockNum() != range.getBlockNum()
        || record.getPosition().getSource() != range.getSource()) {
      throw new ArchiveException("archive temporal change position does not match block range "
          + range.getBlockNum());
    }
  }

  private BlockCommitRows readBlockCommitRows(ArchiveBlockRange range) throws RocksDBException {
    BlockCommitRowAccumulator rowAccumulator = new BlockCommitRowAccumulator();
    try (RocksIterator it = db.newIterator()) {
      it.seek(ArchiveTemporalCodec.changesetSeekFrom(range.getFirstTxNum()));
      while (it.isValid() && it.key()[0] == ArchiveTemporalCodec.CHANGESET_PREFIX) {
        byte[] changesetKey = it.key().clone();
        long txNum = ArchiveTemporalCodec.txNumOfChangeset(changesetKey);
        if (txNum > range.getLastTxNum()) {
          break;
        }
        byte[] historyKey = ArchiveTemporalCodec.historyKeyOfChangeset(changesetKey);
        byte[] historyValue = db.get(historyKey);
        byte[] changesetValue = it.value().clone();
        if (historyValue == null) {
          throw new ArchiveException(
              "archive temporal history missing for commit marker txNum " + txNum);
        }
        rowAccumulator.addPersisted(changesetKey, historyValue, changesetValue);
        it.next();
      }
      ArchiveRocksIterators.requireOk(it, "readBlockCommitRows: changeset scan");
    }
    return rowAccumulator.finish();
  }

  private static List<ArchiveChangeRecord> orderedRecords(List<ArchiveChangeRecord> records) {
    List<ArchiveChangeRecord> ordered = new ArrayList<>(records);
    Collections.sort(ordered, RECORD_ORDER);
    return ordered;
  }

  private static WrappedByteArray latestKeyOf(ArchiveChangeRecord record) {
    return WrappedByteArray.copyOf(
        ArchiveTemporalCodec.latestKey(record.getDomain(), record.getCanonicalKey()));
  }

  private static boolean sameDomainValue(DomainValue left, DomainValue right) {
    return left.contentEquals(right);
  }

  private static int compareRecords(ArchiveChangeRecord left, ArchiveChangeRecord right) {
    int result = Long.compare(left.getTxNum(), right.getTxNum());
    if (result != 0) {
      return result;
    }
    result = Integer.compare(left.getDomain().getId(), right.getDomain().getId());
    if (result != 0) {
      return result;
    }
    return compareBytes(left.getCanonicalKey(), right.getCanonicalKey());
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

  private static final class BlockCommitRow {

    private static final Comparator<BlockCommitRow> BY_CHANGESET_KEY =
        (left, right) -> compareBytes(left.changesetKey, right.changesetKey);

    private final byte[] changesetKey;
    private final byte[] historyValue;
    private final byte[] changesetValue;

    private BlockCommitRow(byte[] changesetKey, byte[] historyValue, byte[] changesetValue) {
      this.changesetKey = changesetKey;
      this.historyValue = historyValue;
      this.changesetValue = changesetValue;
    }

    private static BlockCommitRow of(ArchiveChangeRecord record) {
      byte[] changesetKey = ArchiveTemporalCodec.changesetKey(
          record.getTxNum(), record.getDomain(), record.getCanonicalKey());
      return new BlockCommitRow(changesetKey,
          ArchiveTemporalCodec.encodeValue(record.getPrevValue()),
          ArchiveTemporalCodec.encodeValue(record.getValue()));
    }
  }

  private static final class BlockCommitRowAccumulator {

    private final MessageDigest digest = newBlockCommitDigest();
    private final Set<WrappedByteArray> changesetKeys = new HashSet<>();
    private int count;

    void addPersisted(byte[] changesetKey, byte[] historyValue, byte[] changesetValue) {
      addRow(changesetKey, historyValue, changesetValue);
    }

    private void addRow(byte[] changesetKey, byte[] historyValue, byte[] changesetValue) {
      if (!changesetKeys.add(WrappedByteArray.copyOf(changesetKey))) {
        throw new ArchiveException("archive temporal duplicate changeset row");
      }
      updateDigest(digest, changesetKey);
      updateDigest(digest, changesetValue);
      updateDigest(digest, ArchiveTemporalCodec.historyKeyOfChangeset(changesetKey));
      updateDigest(digest, historyValue);
      count++;
    }

    BlockCommitRows finish() {
      return new BlockCommitRows(count, digest.digest());
    }
  }

  private static final class BlockCommitRows {

    private final int count;
    private final byte[] digest;

    private BlockCommitRows(int count, byte[] digest) {
      this.count = count;
      ArchiveTemporalCodec.requireBlockCommitDigest(digest);
      this.digest = digest;
    }
  }

  private static MessageDigest newBlockCommitDigest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new ArchiveException("archive temporal block digest is unavailable", e);
    }
  }

  private static void updateDigest(MessageDigest digest, byte[] bytes) {
    updateDigestLength(digest, bytes.length);
    digest.update(bytes);
  }

  private static void updateDigestLength(MessageDigest digest, int length) {
    digest.update((byte) (length >>> 24));
    digest.update((byte) (length >>> 16));
    digest.update((byte) (length >>> 8));
    digest.update((byte) length);
  }

  @Override
  public void close() {
    db.close();
    options.close();
  }
}
