package org.tron.core.archive.temporal;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongFunction;
import java.util.function.LongPredicate;
import org.tron.common.math.StrictMathWrapper;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.ArchiveRocksIterators;
import org.tron.core.archive.ArchiveResourceEstimator;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.domain.ArchiveDomainCatalog;
import org.tron.core.archive.domain.DynamicKeyPolicy;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.archive.unified.UnifiedArchiveColumnFamily;
import org.tron.core.archive.unified.UnifiedArchiveDb;
import org.tron.core.archive.unified.UnifiedArchiveIterator;
import org.tron.core.archive.unified.UnifiedArchiveMaintenanceBatch;
import org.tron.core.archive.unified.UnifiedArchivePublish;
import org.tron.core.archive.unified.UnifiedArchiveReadView;
import org.tron.core.db2.common.WrappedByteArray;

/** Temporal-store adapter over UNIFIED_V1 latest/history/changeset/marker column families. */
public final class UnifiedArchiveTemporalStore implements ArchiveTemporalStore {


  /** Maximum single value admitted by the immutable UNIFIED_V1 temporal payload format. */
  public static long maxPayloadBytes() {
    return ArchiveTemporalIntegrityCodec.MAX_PAYLOAD_BYTES;
  }

  /** Maximum single payload reachable through the bounded production writer. */
  public static long maxStoredPayloadBytes() {
    return ArchiveTemporalIntegrityCodec.MAX_STORED_PAYLOAD_BYTES;
  }

  private static final Comparator<ArchiveChangeRecord> RECORD_ORDER =
      UnifiedArchiveTemporalStore::compareRecords;

  private final UnifiedArchiveDb db;
  private final ArchiveDomainCatalog catalog;
  private final long maxMaintenanceRetainedBytes;
  private final long maxMaintenanceMutations;
  private final DynamicKeyPolicy dynamicKeyPolicy = new DynamicKeyPolicy();

  public UnifiedArchiveTemporalStore(UnifiedArchiveDb db, ArchiveDomainCatalog catalog) {
    this(db, catalog, UnifiedArchiveMaintenanceBatch.DEFAULT_MAX_RETAINED_BYTES,
        UnifiedArchiveMaintenanceBatch.DEFAULT_MAX_MUTATIONS);
  }

  UnifiedArchiveTemporalStore(UnifiedArchiveDb db, ArchiveDomainCatalog catalog,
      long maxMaintenanceRetainedBytes, long maxMaintenanceMutations) {
    if (db == null) {
      throw new NullPointerException("db");
    }
    if (catalog == null) {
      throw new NullPointerException("catalog");
    }
    if (maxMaintenanceRetainedBytes <= 0L || maxMaintenanceMutations <= 0L) {
      throw new IllegalArgumentException("maintenance limits must be positive");
    }
    this.db = db;
    this.catalog = catalog;
    this.maxMaintenanceRetainedBytes = maxMaintenanceRetainedBytes;
    this.maxMaintenanceMutations = maxMaintenanceMutations;
  }

  public boolean isOwnedBy(UnifiedArchiveDb candidate) {
    return db == candidate;
  }

  /** Adds temporal mutations and the block marker to the caller's atomic publish. */
  public void stagePublication(UnifiedArchivePublish.Builder publish, ArchiveBlockRange range,
      List<ArchiveChangeRecord> records) {
    if (publish == null) {
      throw new NullPointerException("publish");
    }
    try (PublicationPreflight preflight = preflightPublication(records)) {
      stagePublication(publish, range, preflight);
    }
  }

  /** Adds temporal mutations from a locator-only, snapshot-bound publication plan. */
  public void stagePublication(UnifiedArchivePublish.Builder publish, ArchiveBlockRange range,
      PublicationPreflight preflight) {
    if (publish == null) {
      throw new NullPointerException("publish");
    }
    preflight.requireOwnedAndOpen(this);
    List<ArchiveChangeRecord> records = preflight.orderedRecords;
    PublicationEstimate estimate = estimatePublicationPreparation(records);
    publish.requireAdditionalCapacity(
        addSaturated(estimate.retainedBytes, preflight.persistedPreparationBytes),
        estimate.mutations, "UNIFIED_V1 temporal preparation");
    PreparedChanges prepared = prepare(range, preflight);
    for (Row row : prepared.anchorRows) {
      stageIntegrityRow(publish, UnifiedArchiveColumnFamily.COMMITMENT,
          row.key, row.value, row.linkedTxNum);
    }
    for (Row row : prepared.historyRows) {
      stageIntegrityRow(publish, UnifiedArchiveColumnFamily.HISTORY,
          row.key, row.value, row.linkedTxNum);
    }
    for (Row row : prepared.changesetRows) {
      stageIntegrityRow(publish, UnifiedArchiveColumnFamily.CHANGESET,
          row.key, row.value, row.linkedTxNum);
    }
    for (LatestRows latest : prepared.latestRows.values()) {
      stageIntegrityRow(publish, UnifiedArchiveColumnFamily.LATEST,
          latest.latestKey, latest.value, latest.lastTxNum);
      deleteIntegrityRow(publish, UnifiedArchiveColumnFamily.LATEST, latest.baselineKey);
    }
    publish.blockMarker(ArchiveTemporalCodec.blockCommitKey(range.getBlockNum()),
        ArchiveTemporalCodec.encodeBlockCommit(
            range, prepared.rowCount, prepared.digest));
  }

  @Override
  public void putChange(ArchiveChangeRecord record) {
    throw unsupportedDirectMaintenance();
  }

  @Override
  public void putChanges(List<ArchiveChangeRecord> records) {
    throw unsupportedDirectMaintenance();
  }

  @Override
  public void putBlockChanges(ArchiveBlockRange range, List<ArchiveChangeRecord> records) {
    throw unsupportedDirectMaintenance();
  }

  /** Prepares bounded maintenance mutations for package-owned fault-injection tests. */
  public UnifiedArchiveMaintenanceBatch prepareMaintenanceBatch(
      ArchiveBlockRange range, List<ArchiveChangeRecord> records) {
    try (PublicationPreflight preflight = preflightPublication(records)) {
      validateMaintenancePreparation(
          preflight.orderedRecords, range != null, preflight.persistedPreparationBytes);
      return toMaintenanceBatch(prepare(range, preflight), range);
    }
  }

  private static ArchiveException unsupportedDirectMaintenance() {
    return new ArchiveException(
        "direct UNIFIED_V1 temporal maintenance is unsupported; rebuild the archive instead");
  }

  private UnifiedArchiveMaintenanceBatch toMaintenanceBatch(PreparedChanges prepared,
      ArchiveBlockRange range) {
    UnifiedArchiveMaintenanceBatch batch = UnifiedArchiveMaintenanceBatch.bounded(
        maxMaintenanceRetainedBytes, maxMaintenanceMutations);
    for (Row row : prepared.anchorRows) {
      putIntegrityRow(batch, UnifiedArchiveColumnFamily.COMMITMENT,
          row.key, row.value, row.linkedTxNum);
    }
    for (Row row : prepared.historyRows) {
      putIntegrityRow(batch, UnifiedArchiveColumnFamily.HISTORY,
          row.key, row.value, row.linkedTxNum);
    }
    for (Row row : prepared.changesetRows) {
      putIntegrityRow(batch, UnifiedArchiveColumnFamily.CHANGESET,
          row.key, row.value, row.linkedTxNum);
    }
    for (LatestRows latest : prepared.latestRows.values()) {
      putIntegrityRow(batch, UnifiedArchiveColumnFamily.LATEST,
          latest.latestKey, latest.value, latest.lastTxNum);
      deleteIntegrityRow(batch, UnifiedArchiveColumnFamily.LATEST, latest.baselineKey);
    }
    if (range != null) {
      batch.put(UnifiedArchiveColumnFamily.BLOCK_MARKER,
          ArchiveTemporalCodec.blockCommitKey(range.getBlockNum()),
          ArchiveTemporalCodec.encodeBlockCommit(range, prepared.rowCount, prepared.digest));
    }
    return batch;
  }

  private void validateMaintenancePreparation(List<ArchiveChangeRecord> records,
      boolean includeMarker, long persistedPreparationBytes) {
    PreparationEstimate estimate = estimatePreparation(records, includeMarker);
    long retainedBytes = addSaturated(estimate.retainedBytes, persistedPreparationBytes);
    if (retainedBytes > maxMaintenanceRetainedBytes) {
      throw new ArchiveException("UNIFIED_V1 temporal preparation exceeds retained byte limit "
          + maxMaintenanceRetainedBytes + ": estimatedBytes=" + retainedBytes);
    }
    if (estimate.mutations > maxMaintenanceMutations) {
      throw new ArchiveException("UNIFIED_V1 temporal preparation exceeds mutation limit "
          + maxMaintenanceMutations + ": estimatedMutations=" + estimate.mutations);
    }
  }

  /** Shared conservative budget used before journal durability and before temporal preparation. */
  public PublicationEstimate estimatePublicationPreparation(
      List<ArchiveChangeRecord> records) {
    PreparationEstimate estimate = estimatePreparation(records, true);
    return new PublicationEstimate(estimate.retainedBytes, estimate.mutations);
  }

  /**
   * Reads only fixed-size temporal locators to account for persisted payloads that preparation will
   * materialize. The returned single-use plan binds those locators, records, and the snapshot used
   * by preparation; callers must close it when publication admission fails.
   */
  public PublicationPreflight preflightPublication(List<ArchiveChangeRecord> records) {
    if (records == null) {
      throw new ArchiveException("archive temporal block changes are missing");
    }
    List<ArchiveChangeRecord> ordered = new ArrayList<>(records.size());
    for (ArchiveChangeRecord record : records) {
      if (record == null) {
        throw new ArchiveException("archive temporal change record is missing");
      }
      ordered.add(record);
    }
    ordered.sort(RECORD_ORDER);
    if (ordered.isEmpty()) {
      return new PublicationPreflight(this, ordered, new HashMap<>(), null, 0L);
    }
    PersistedPreparationEstimate estimate = new PersistedPreparationEstimate();
    Map<WrappedByteArray, PersistedLatestStatePlan> plans = new HashMap<>();
    UnifiedArchiveReadView view = db.openScanView();
    try {
      UnifiedArchiveIterator history = view.newIterator(UnifiedArchiveColumnFamily.HISTORY);
      for (ArchiveChangeRecord record : ordered) {
        ArchiveDomain domain = record.getDomain();
        byte[] canonicalKey = record.getCanonicalKey();
        byte[] latestKey = ArchiveTemporalCodec.latestKey(domain, canonicalKey);
        WrappedByteArray wrappedLatestKey = WrappedByteArray.copyOf(latestKey);
        if (!plans.containsKey(wrappedLatestKey)) {
          plans.put(wrappedLatestKey, preflightPersistedLatestState(
              estimate, view, history, domain, canonicalKey, latestKey));
        }
      }
      long persistedBytes = addSaturated(
          estimate.payloadAndCopyBytes, estimate.maxNativeReadBytes);
      return new PublicationPreflight(this, ordered, plans, view, persistedBytes);
    } catch (RuntimeException | Error failure) {
      closeAfterFailure(view, failure);
      throw failure;
    }
  }

  private static PersistedLatestStatePlan preflightPersistedLatestState(
      PersistedPreparationEstimate estimate,
      UnifiedArchiveReadView view, UnifiedArchiveIterator history, ArchiveDomain domain,
      byte[] canonicalKey, byte[] latestKey) {
    String operation = "UNIFIED_V1 estimate temporal preparation";
    byte[] anchorKey = ArchiveTemporalCodec.anchorKey(domain, canonicalKey);
    ArchiveTemporalIntegrityCodec.Locator anchor = readIntegrityLocator(
        view, UnifiedArchiveColumnFamily.COMMITMENT, anchorKey, operation);
    estimate.addPayload(anchor);
    ArchiveTemporalIntegrityCodec.Locator current = readIntegrityLocator(
        view, UnifiedArchiveColumnFamily.LATEST, latestKey, operation);
    estimate.addPayload(current);

    byte[] historyPrefix = ArchiveTemporalCodec.historyPrefix(domain, canonicalKey);
    history.seekForPrev(ArchiveTemporalCodec.historyKey(domain, canonicalKey, Long.MAX_VALUE));
    ArchiveRocksIterators.requireOk(history, operation + ": history tail seek");
    boolean hasHistory = history.isValid()
        && ArchiveTemporalCodec.startsWith(history.key(), historyPrefix);
    byte[] historyTailKey = hasHistory ? history.key().clone() : null;
    ArchiveTemporalIntegrityCodec.Locator historyTail = null;
    byte[] predecessorHistoryKey = null;
    byte[] predecessorChangesetKey = null;
    ArchiveTemporalIntegrityCodec.Locator predecessorChangeset = null;
    if (hasHistory) {
      historyTail = readIntegrityLocator(
          view, UnifiedArchiveColumnFamily.HISTORY, historyTailKey, operation);
      estimate.addPayload(historyTail);
      history.prev();
      ArchiveRocksIterators.requireOk(history, operation + ": physical predecessor seek");
      if (history.isValid()
          && ArchiveTemporalCodec.startsWith(history.key(), historyPrefix)) {
        predecessorHistoryKey = history.key().clone();
        predecessorChangesetKey =
            ArchiveTemporalCodec.changesetKeyOfHistory(predecessorHistoryKey);
        predecessorChangeset = readIntegrityLocator(
            view, UnifiedArchiveColumnFamily.CHANGESET, predecessorChangesetKey, operation);
        estimate.addPayload(predecessorChangeset);
      }
    }

    byte[] baselineKey = ArchiveTemporalCodec.latestBaselineKey(domain, canonicalKey);
    ArchiveTemporalIntegrityCodec.Locator baseline = readIntegrityLocator(
        view, UnifiedArchiveColumnFamily.LATEST, baselineKey, operation);
    estimate.addPayload(baseline);
    byte[] tailChangesetKey = null;
    ArchiveTemporalIntegrityCodec.Locator tailChangeset = null;
    if (current != null) {
      estimate.addCopy(current.payloadBytes());
      if (hasHistory) {
        tailChangesetKey = ArchiveTemporalCodec.changesetKeyOfHistory(historyTailKey);
        tailChangeset = readIntegrityLocator(
            view, UnifiedArchiveColumnFamily.CHANGESET, tailChangesetKey, operation);
        estimate.addPayload(tailChangeset);
      } else if (anchor != null) {
        estimate.addCopy(anchor.payloadBytes());
      }
    }
    return new PersistedLatestStatePlan(
        anchorKey, anchor, latestKey, current, historyTailKey, historyTail,
        predecessorHistoryKey, predecessorChangesetKey, predecessorChangeset,
        baselineKey, baseline, tailChangesetKey, tailChangeset);
  }

  private static PreparationEstimate estimatePreparation(List<ArchiveChangeRecord> records,
      boolean includeMarker) {
    if (records == null) {
      throw new ArchiveException("archive temporal block changes are missing");
    }
    long retainedBytes = includeMarker ? 512L : 0L;
    long mutations = includeMarker ? 1L : 0L;
    for (ArchiveChangeRecord record : records) {
      if (record == null) {
        throw new ArchiveException("archive temporal change record is missing");
      }
      retainedBytes = addSaturated(retainedBytes,
          ArchiveResourceEstimator.estimatedTemporalPreparationBytes(
              record.canonicalKeySize(), record.getPrevValue().size(), record.getValue().size()));
      mutations = addSaturated(mutations, 10L);
    }
    return new PreparationEstimate(retainedBytes, mutations);
  }

  private static long addSaturated(long left, long right) {
    return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
  }

  private PreparedChanges prepare(ArchiveBlockRange range, PublicationPreflight preflight) {
    preflight.claim(this);
    List<ArchiveChangeRecord> ordered = preflight.orderedRecords;
    List<Row> anchorRows = new ArrayList<>();
    List<Row> historyRows = new ArrayList<>();
    List<Row> changesetRows = new ArrayList<>();
    Map<WrappedByteArray, LatestRows> latestRows = new LinkedHashMap<>();
    List<CommitDigestRow> commitRows = new ArrayList<>();
    Set<WrappedByteArray> changesetKeys = new HashSet<>();
    int rowCount = 0;
    Map<WrappedByteArray, LatestState> latestStates = new HashMap<>();
    UnifiedArchiveReadView view = preflight.view;
    for (ArchiveChangeRecord record : ordered) {
      if (range != null) {
        validateRecordInRange(range, record);
      }
      ArchiveDomain domain = record.getDomain();
      byte[] canonicalKey = record.getCanonicalKey();
      byte[] latestKey = ArchiveTemporalCodec.latestKey(domain, canonicalKey);
      WrappedByteArray wrappedLatestKey = WrappedByteArray.copyOf(latestKey);
      LatestState state = latestStates.get(wrappedLatestKey);
      if (state == null) {
        PersistedLatestStatePlan plan = preflight.plans.get(wrappedLatestKey);
        if (plan == null) {
          throw new ArchiveException("archive temporal preflight plan is incomplete");
        }
        state = loadLatestState(view, plan);
        latestStates.put(wrappedLatestKey, state);
      }
      if (!state.anchored) {
        state.anchorKey = ArchiveTemporalCodec.anchorKey(domain, canonicalKey);
        state.anchorValue = ArchiveTemporalCodec.encodeValue(record.getPrevValue());
        state.anchorOriginTxNum = record.getTxNum();
        state.anchored = true;
        anchorRows.add(new Row(
            state.anchorKey, state.anchorValue, state.anchorOriginTxNum));
      }
      if (state.value != null && !state.value.contentEquals(record.getPrevValue())) {
        throw new ArchiveException("archive temporal prev-value chain mismatch for txNum "
            + record.getTxNum());
      }
      if (state.lastTxNum >= record.getTxNum()) {
        throw new ArchiveException("archive temporal history txNum is not append-only");
      }
      byte[] historyKey = ArchiveTemporalCodec.historyKey(
          domain, canonicalKey, record.getTxNum());
      byte[] historyValue = ArchiveTemporalCodec.encodeValue(record.getPrevValue());
      byte[] changesetKey = ArchiveTemporalCodec.changesetKey(
          record.getTxNum(), domain, canonicalKey);
      byte[] changesetValue = ArchiveTemporalCodec.encodeValue(record.getValue());
      if (!changesetKeys.add(WrappedByteArray.copyOf(changesetKey))) {
        throw new ArchiveException("archive temporal duplicate changeset row");
      }
      long predecessorTxNum = state.lastTxNum;
      historyRows.add(new Row(historyKey, historyValue, predecessorTxNum));
      changesetRows.add(new Row(changesetKey, changesetValue, record.getTxNum()));
      state.value = record.getValue();
      state.lastTxNum = record.getTxNum();
      latestRows.put(wrappedLatestKey,
          new LatestRows(latestKey,
              ArchiveTemporalCodec.latestBaselineKey(domain, canonicalKey),
              changesetValue, state.lastTxNum));
      Row anchorForDigest = predecessorTxNum == ArchiveTemporalIntegrityCodec.NO_HISTORY_TX_NUM
          ? new Row(state.anchorKey, state.anchorValue, state.anchorOriginTxNum) : null;
      commitRows.add(new CommitDigestRow(
          changesetKey, changesetValue, record.getTxNum(),
          historyKey, historyValue, predecessorTxNum, anchorForDigest));
      rowCount++;
    }
    // Fold the block-commit digest in changeset-key order to match the physical CHANGESET
    // iteration in readBlockRows(), mirroring the Rocks BY_CHANGESET_KEY sort. RECORD_ORDER
    // (canonicalKey lex-then-length) diverges from the stored order (keyLen-then-canonicalKey)
    // for multi-key (txNum, domain) groups, which would fail-stop commit-marker validation.
    commitRows.sort(COMMIT_DIGEST_ROW_BY_CHANGESET_KEY);
    MessageDigest digest = newDigest();
    for (CommitDigestRow row : commitRows) {
      updateDigest(digest, row.changesetKey, row.changesetLinkedTxNum, row.changesetValue);
      updateDigest(digest, row.historyKey, row.historyLinkedTxNum, row.historyValue);
      if (row.anchor != null) {
        updateDigest(digest, row.anchor.key, row.anchor.linkedTxNum, row.anchor.value);
      }
    }
    return new PreparedChanges(
        anchorRows, historyRows, changesetRows, latestRows, rowCount, digest.digest());
  }

  private static LatestState loadLatestState(
      UnifiedArchiveReadView view, PersistedLatestStatePlan plan) {
    String operation = "UNIFIED_V1 validate temporal prev-value chain";
    ArchiveTemporalIntegrityCodec.DecodedRow anchor = readPlannedIntegrityRow(
        view, UnifiedArchiveColumnFamily.COMMITMENT,
        plan.anchorKey, plan.anchor, operation);
    if (anchor != null) {
      if (anchor.linkedTxNum() < 0L) {
        throw new ArchiveException(operation + ": anchor origin txNum is invalid");
      }
      ArchiveTemporalCodec.validateValueEncoding(anchor.payloadView());
    }
    ArchiveTemporalIntegrityCodec.DecodedRow current = readPlannedIntegrityRow(
        view, UnifiedArchiveColumnFamily.LATEST,
        plan.latestKey, plan.current, operation);
    boolean hasHistory = plan.historyTailKey != null;
    long actualLastTxNum = hasHistory
        ? ArchiveTemporalCodec.txNumOfHistory(plan.historyTailKey)
        : ArchiveTemporalIntegrityCodec.NO_HISTORY_TX_NUM;
    if (hasHistory) {
      ArchiveTemporalIntegrityCodec.DecodedRow historyTail = readRequiredPlannedIntegrityRow(
          view, UnifiedArchiveColumnFamily.HISTORY,
          plan.historyTailKey, plan.historyTail, operation);
      requireHistoryPredecessor(view, plan, historyTail, actualLastTxNum, anchor, operation);
    }
    ArchiveTemporalIntegrityCodec.DecodedRow baseline = readPlannedIntegrityRow(
        view, UnifiedArchiveColumnFamily.LATEST, plan.baselineKey, plan.baseline, operation);
    if (current == null) {
      if (hasHistory || baseline != null || anchor != null) {
        throw new ArchiveException(operation + ": latest row is missing from an existing chain");
      }
      return new LatestState(null, ArchiveTemporalIntegrityCodec.NO_HISTORY_TX_NUM,
          false, null, null, ArchiveTemporalIntegrityCodec.NO_HISTORY_TX_NUM);
    }
    if (anchor == null) {
      throw new ArchiveException(operation + ": temporal chain has no anchor");
    }
    if (current.linkedTxNum() != actualLastTxNum) {
      throw new ArchiveException(operation + ": latest history link mismatch");
    }
    if (hasHistory) {
      ArchiveTemporalIntegrityCodec.DecodedRow changeset = readPlannedIntegrityRow(
          view, UnifiedArchiveColumnFamily.CHANGESET,
          plan.tailChangesetKey, plan.tailChangeset, operation);
      if (changeset == null || changeset.linkedTxNum() != actualLastTxNum
          || !current.payloadEquals(changeset)) {
        throw new ArchiveException(operation + ": latest value mismatch");
      }
      if (baseline != null) {
        throw new ArchiveException(operation + ": baseline exists for a history chain");
      }
    } else {
      if (baseline == null
          || baseline.linkedTxNum() != ArchiveTemporalIntegrityCodec.NO_HISTORY_TX_NUM
          || !current.payloadEquals(baseline)
          || !baseline.payloadEquals(anchor)) {
        throw new ArchiveException(operation + ": latest baseline mismatch");
      }
    }
    return new LatestState(current.domainValue().decode(), current.linkedTxNum(),
        true, plan.anchorKey, hasHistory ? null : anchor.payload(), anchor.linkedTxNum());
  }

  private static void requireHistoryPredecessor(UnifiedArchiveReadView view,
      PersistedLatestStatePlan plan,
      ArchiveTemporalIntegrityCodec.DecodedRow row, long txNum,
      ArchiveTemporalIntegrityCodec.DecodedRow anchor, String operation) {
    long linkedTxNum = row.linkedTxNum();
    if (linkedTxNum >= txNum) {
      throw new ArchiveException(operation + ": history predecessor link is invalid");
    }
    if (plan.predecessorHistoryKey == null) {
      if (linkedTxNum != ArchiveTemporalIntegrityCodec.NO_HISTORY_TX_NUM
          || anchor == null || !row.payloadEquals(anchor)) {
        throw new ArchiveException(
            operation + ": first history row does not match anchor/physical predecessor");
      }
      return;
    }
    long physicalPredecessorTxNum =
        ArchiveTemporalCodec.txNumOfHistory(plan.predecessorHistoryKey);
    ArchiveTemporalIntegrityCodec.DecodedRow predecessorChangeset = readPlannedIntegrityRow(
        view, UnifiedArchiveColumnFamily.CHANGESET,
        plan.predecessorChangesetKey, plan.predecessorChangeset, operation);
    if (linkedTxNum != physicalPredecessorTxNum || predecessorChangeset == null
        || predecessorChangeset.linkedTxNum() != physicalPredecessorTxNum
        || !row.payloadEquals(predecessorChangeset)) {
      throw new ArchiveException(
          operation + ": history row does not match its physical predecessor");
    }
  }

  private static void stageIntegrityRow(UnifiedArchivePublish.Builder publish,
      UnifiedArchiveColumnFamily columnFamily, byte[] key, byte[] value, long linkedTxNum) {
    publish.put(columnFamily, key,
        ArchiveTemporalIntegrityCodec.encode(columnFamily, key, value, linkedTxNum));
    publish.put(UnifiedArchiveColumnFamily.TEMPORAL_PAYLOAD,
        ArchiveTemporalIntegrityCodec.payloadKey(columnFamily, key), value);
  }

  private static void putIntegrityRow(UnifiedArchiveMaintenanceBatch batch,
      UnifiedArchiveColumnFamily columnFamily, byte[] key, byte[] value, long linkedTxNum) {
    batch.put(columnFamily, key,
        ArchiveTemporalIntegrityCodec.encode(columnFamily, key, value, linkedTxNum));
    batch.put(UnifiedArchiveColumnFamily.TEMPORAL_PAYLOAD,
        ArchiveTemporalIntegrityCodec.payloadKey(columnFamily, key), value);
  }

  private static void deleteIntegrityRow(UnifiedArchivePublish.Builder publish,
      UnifiedArchiveColumnFamily columnFamily, byte[] key) {
    publish.delete(columnFamily, key);
    publish.delete(UnifiedArchiveColumnFamily.TEMPORAL_PAYLOAD,
        ArchiveTemporalIntegrityCodec.payloadKey(columnFamily, key));
  }

  private static void deleteIntegrityRow(UnifiedArchiveMaintenanceBatch batch,
      UnifiedArchiveColumnFamily columnFamily, byte[] key) {
    batch.delete(columnFamily, key);
    batch.delete(UnifiedArchiveColumnFamily.TEMPORAL_PAYLOAD,
        ArchiveTemporalIntegrityCodec.payloadKey(columnFamily, key));
  }

  @Override
  public Optional<DomainValue> getAsOf(ArchiveDomain domain, byte[] canonicalKey, long txNum) {
    try (UnifiedArchiveReadView view = db.openScanView()) {
      UnifiedArchiveIterator history = view.newIterator(UnifiedArchiveColumnFamily.HISTORY);
      return getAsOf(view, history, domain, canonicalKey, txNum);
    }
  }

  @Override
  public long getAsOfBackendReadCost() {
    // Unified point reads and iterator movements account their actual native operations.
    return 0L;
  }

  @Override
  public Optional<DomainValue> latest(ArchiveDomain domain, byte[] canonicalKey) {
    try (UnifiedArchiveReadView view = db.openScanView()) {
      UnifiedArchiveIterator history = view.newIterator(UnifiedArchiveColumnFamily.HISTORY);
      return getAsOf(view, history, domain, canonicalKey, Long.MAX_VALUE);
    }
  }

  @Override
  public ArchiveTemporalReadView openReadView() {
    UnifiedArchiveReadView view = db.openScanView();
    try {
      return wrapReadView(view);
    } catch (RuntimeException | Error failure) {
      try {
        view.close();
      } catch (RuntimeException | Error closeFailure) {
        if (failure != closeFailure) {
          failure.addSuppressed(closeFailure);
        }
      }
      throw failure;
    }
  }

  /** Wraps an already-open cross-CF snapshot; closing the adapter closes the shared view. */
  public ArchiveTemporalReadView wrapReadView(UnifiedArchiveReadView view) {
    db.requireOwnedReadView(view);
    return new SnapshotView(view);
  }

  /** Cross-checks one index range against its independently stored marker in the same snapshot. */
  public void validateBlockRangeMarker(UnifiedArchiveReadView view, ArchiveBlockRange range) {
    if (view == null || range == null) {
      throw new NullPointerException("view/range");
    }
    db.requireOwnedReadView(view);
    byte[] marker = readBlockMarker(view,
        ArchiveTemporalCodec.blockCommitKey(range.getBlockNum()),
        "UNIFIED_V1 validate block-range marker");
    if (!ArchiveTemporalCodec.blockCommitRangeMatches(marker, range)) {
      throw new ArchiveException("archive temporal marker does not match index range for block "
          + range.getBlockNum());
    }
  }

  @Override
  public void validateCommittedBlock(ArchiveBlockRange range) {
    try (UnifiedArchiveReadView view = db.openScanView()) {
      validateCommittedBlock(view, range);
    }
  }

  private void validateCommittedBlock(UnifiedArchiveReadView view, ArchiveBlockRange range) {
    byte[] marker = readBlockMarker(view,
        ArchiveTemporalCodec.blockCommitKey(range.getBlockNum()),
        "UNIFIED_V1 validate temporal commit marker");
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
    try (UnifiedArchiveReadView view = db.openScanView()) {
      validateStartupTail(view, lastRange);
    }
  }

  /** Validates the temporal tail against an already-bound cross-column-family snapshot. */
  public void validateStartupTail(UnifiedArchiveReadView view,
      Optional<ArchiveBlockRange> lastRange) {
    db.requireOwnedReadView(view);
    UnifiedArchiveIterator iterator =
        view.newIterator(UnifiedArchiveColumnFamily.BLOCK_MARKER);
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

  /**
   * Streams every committed marker and changeset range through one snapshot. Iterator creation is
   * constant in the number of archived blocks, while each marker digest retains the same checks as
   * {@link #validateCommittedBlock(ArchiveBlockRange)}.
   */
  public void validateCommittedBlocks(UnifiedArchiveReadView view, long firstBlock,
      long lastBlock, LongFunction<ArchiveBlockRange> rangeLookup) {
    if (view == null || rangeLookup == null) {
      throw new NullPointerException("view/rangeLookup");
    }
    db.requireOwnedReadView(view);
    UnifiedArchiveIterator markers =
        view.newIterator(UnifiedArchiveColumnFamily.BLOCK_MARKER);
    markers.seekToFirst();
    if (firstBlock < 0L) {
      if (lastBlock >= 0L) {
        throw new ArchiveException("archive block coverage bounds are inconsistent");
      }
      if (markers.isValid()) {
        throw new ArchiveException(
            "archive temporal commit marker exists without an index range");
      }
      ArchiveRocksIterators.requireOk(markers, "UNIFIED_V1 validate temporal markers");
      return;
    }
    if (lastBlock < firstBlock) {
      throw new ArchiveException("archive block coverage bounds are inconsistent");
    }

    UnifiedArchiveIterator changeset = view.newIterator(UnifiedArchiveColumnFamily.CHANGESET);
    boolean firstRange = true;
    for (long blockNum = firstBlock; ; blockNum++) {
      ArchiveBlockRange range = rangeLookup.apply(blockNum);
      if (range == null || range.getBlockNum() != blockNum) {
        throw new ArchiveException("archive block range lookup mismatch for block " + blockNum);
      }
      if (firstRange) {
        changeset.seek(ArchiveTemporalCodec.changesetSeekFrom(range.getFirstTxNum()));
        firstRange = false;
      }
      requireCommitMarker(markers, blockNum);
      BlockRows rows = readBlockRows(view, changeset, range);
      byte[] marker = readBlockMarker(view, markers.key(),
          "UNIFIED_V1 validate temporal commit marker");
      if (!ArchiveTemporalCodec.blockCommitMatches(
          marker, range, rows.count, rows.digest)) {
        throw new ArchiveException("archive temporal commit marker missing for block "
            + blockNum);
      }
      markers.next();
      if (blockNum == lastBlock) {
        break;
      }
    }
    ArchiveRocksIterators.requireOk(changeset, "UNIFIED_V1 read committed block rows");
    ArchiveRocksIterators.requireOk(markers, "UNIFIED_V1 validate temporal markers");
    if (markers.isValid()) {
      if (!ArchiveTemporalCodec.startsWith(
          markers.key(), ArchiveTemporalCodec.blockCommitPrefix())) {
        throw new ArchiveException(
            "UNIFIED_V1 block-marker column family has an unknown key");
      }
      throw new ArchiveException("archive temporal commit marker has no index range for block "
          + ArchiveTemporalCodec.blockNumOfBlockCommitKey(markers.key()));
    }
  }

  public void validateCommitMarkersCovered(LongPredicate hasIndexRange) {
    try (UnifiedArchiveReadView view = db.openScanView()) {
      UnifiedArchiveIterator iterator =
          view.newIterator(UnifiedArchiveColumnFamily.BLOCK_MARKER);
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
    try (UnifiedArchiveReadView view = db.openScanView()) {
      validateTxNumsCovered(view, hasCommittedTxNum);
    }
  }

  /** Validates txNum coverage against an already-bound cross-column-family snapshot. */
  public void validateTxNumsCovered(UnifiedArchiveReadView view,
      LongPredicate hasCommittedTxNum) {
      db.requireOwnedReadView(view);
      UnifiedArchiveIterator history = view.newIterator(UnifiedArchiveColumnFamily.HISTORY);
      byte[] previousLatestKey = null;
      byte[] previousHistoryPrefix = null;
      long previousHistoryTxNum = ArchiveTemporalIntegrityCodec.NO_HISTORY_TX_NUM;
      ArchiveTemporalIntegrityCodec.DecodedRow previousChangeset = null;
      history.seek(new byte[] {ArchiveTemporalCodec.HISTORY_PREFIX});
      while (history.isValid()
          && history.key()[0] == ArchiveTemporalCodec.HISTORY_PREFIX) {
        byte[] historyKey = history.key();
        ArchiveTemporalIntegrityCodec.DecodedRow historyRow = readRequiredIntegrityRow(
            view, UnifiedArchiveColumnFamily.HISTORY, historyKey,
            "UNIFIED_V1 validate temporal history");
        long txNum = ArchiveTemporalCodec.txNumOfHistory(historyKey);
        if (!hasCommittedTxNum.test(txNum)) {
          throw new ArchiveException(
              "archive temporal history txNum has no index position: " + txNum);
        }
        ArchiveTemporalIntegrityCodec.DecodedRow changesetRow = readIntegrityRow(
            view, UnifiedArchiveColumnFamily.CHANGESET,
            ArchiveTemporalCodec.changesetKeyOfHistory(historyKey),
            "UNIFIED_V1 validate temporal changeset");
        if (changesetRow == null) {
          throw new ArchiveException(
              "archive temporal changeset missing for history txNum " + txNum);
        }
        if (changesetRow.linkedTxNum() != txNum) {
          throw new ArchiveException(
              "archive temporal changeset txNum link mismatch for " + txNum);
        }
        byte[] historyPrefix = Arrays.copyOf(historyKey, historyKey.length - Long.BYTES);
        if (Arrays.equals(previousHistoryPrefix, historyPrefix)) {
          if (historyRow.linkedTxNum() != previousHistoryTxNum
              || !historyRow.payloadEquals(previousChangeset)) {
            throw new ArchiveException(
                "archive temporal history value/physical predecessor mismatch for " + txNum);
          }
        } else {
          ArchiveTemporalIntegrityCodec.DecodedRow anchor = readIntegrityRow(
              view, UnifiedArchiveColumnFamily.COMMITMENT,
              ArchiveTemporalCodec.anchorKeyOfHistory(historyKey),
              "UNIFIED_V1 validate temporal history anchor");
          if (historyRow.linkedTxNum()
              != ArchiveTemporalIntegrityCodec.NO_HISTORY_TX_NUM
              || anchor == null || !historyRow.payloadEquals(anchor)) {
            throw new ArchiveException(
                "archive temporal first history row/anchor mismatch for " + txNum);
          }
        }
        previousHistoryPrefix = historyPrefix;
        previousHistoryTxNum = txNum;
        previousChangeset = changesetRow;
        historyRow.domainValue().decode();
        byte[] latestKey = ArchiveTemporalCodec.latestKeyOfHistory(historyKey);
        if (!Arrays.equals(previousLatestKey, latestKey)) {
          if (readIntegrityRow(view, UnifiedArchiveColumnFamily.LATEST, latestKey,
              "UNIFIED_V1 validate temporal latest") == null) {
            throw new ArchiveException(
                "archive temporal latest missing for history txNum " + txNum);
          }
          previousLatestKey = latestKey;
        }
        history.next();
      }
      ArchiveRocksIterators.requireOk(history, "UNIFIED_V1 validate temporal history");

      UnifiedArchiveIterator changeset =
          view.newIterator(UnifiedArchiveColumnFamily.CHANGESET);
      changeset.seek(new byte[] {ArchiveTemporalCodec.CHANGESET_PREFIX});
      while (changeset.isValid()
          && changeset.key()[0] == ArchiveTemporalCodec.CHANGESET_PREFIX) {
        byte[] changesetKey = changeset.key();
        long txNum = ArchiveTemporalCodec.txNumOfChangeset(changeset.key());
        if (!hasCommittedTxNum.test(txNum)
            || !hasIntegrityRow(view, UnifiedArchiveColumnFamily.HISTORY,
                ArchiveTemporalCodec.historyKeyOfChangeset(changesetKey),
                "UNIFIED_V1 validate temporal history owner")) {
          throw new ArchiveException(
              "archive temporal changeset has no index/history for txNum " + txNum);
        }
        ArchiveTemporalIntegrityCodec.DecodedRow changesetRow = readRequiredIntegrityRow(
            view, UnifiedArchiveColumnFamily.CHANGESET, changesetKey,
            "UNIFIED_V1 validate temporal changeset");
        if (changesetRow.linkedTxNum() != txNum) {
          throw new ArchiveException("archive temporal changeset txNum link mismatch");
        }
        changesetRow.domainValue().decode();
        changeset.next();
      }
      ArchiveRocksIterators.requireOk(changeset, "UNIFIED_V1 validate temporal changesets");
    validateLatestRows(view);
  }

  public void validateDomainRows() {
    try (UnifiedArchiveReadView view = db.openScanView()) {
      validateDomainRows(view);
    }
  }

  /** Validates every temporal domain row in an already-bound snapshot. */
  public void validateDomainRows(UnifiedArchiveReadView view) {
    db.requireOwnedReadView(view);
    validateAnchorRows(view);
    validateLatestDomainRows(view);
    validateDomainRows(view, UnifiedArchiveColumnFamily.HISTORY,
        ArchiveTemporalCodec.HISTORY_PREFIX);
    validateDomainRows(view, UnifiedArchiveColumnFamily.CHANGESET,
        ArchiveTemporalCodec.CHANGESET_PREFIX);
    validatePayloadRows(view);
  }

  private static void validatePayloadRows(UnifiedArchiveReadView view) {
    UnifiedArchiveIterator payloads =
        view.newIterator(UnifiedArchiveColumnFamily.TEMPORAL_PAYLOAD);
    payloads.seekToFirst();
    while (payloads.isValid()) {
      byte[] payloadKey = payloads.key();
      UnifiedArchiveColumnFamily columnFamily =
          ArchiveTemporalIntegrityCodec.columnFamilyOfPayloadKey(
              payloadKey, "UNIFIED_V1 validate temporal payload key");
      byte[] logicalKey =
          ArchiveTemporalIntegrityCodec.logicalKeyOfPayloadKey(
              payloadKey, "UNIFIED_V1 validate temporal payload key");
      if (view.getExact(columnFamily, logicalKey,
          ArchiveTemporalIntegrityCodec.LOCATOR_BYTES,
          "UNIFIED_V1 validate temporal payload owner") == null) {
        throw new ArchiveException("UNIFIED_V1 temporal payload has no logical owner");
      }
      payloads.next();
    }
    ArchiveRocksIterators.requireOk(payloads, "UNIFIED_V1 validate temporal payload rows");
  }

  private void validateAnchorRows(UnifiedArchiveReadView view) {
    UnifiedArchiveIterator anchors = view.newIterator(UnifiedArchiveColumnFamily.COMMITMENT);
    UnifiedArchiveIterator history = view.newIterator(UnifiedArchiveColumnFamily.HISTORY);
    anchors.seekToFirst();
    while (anchors.isValid()) {
      byte[] anchorKey = anchors.key();
      if (anchorKey.length == 0 || anchorKey[0] != ArchiveTemporalCodec.ANCHOR_PREFIX) {
        throw new ArchiveException("UNIFIED_V1 commitment column family has an unknown key");
      }
      byte[] latestKey = ArchiveTemporalCodec.latestKeyOfAnchor(anchorKey);
      ArchiveTemporalIntegrityCodec.DecodedRow anchor = readRequiredIntegrityRow(
          view, UnifiedArchiveColumnFamily.COMMITMENT, anchorKey,
          "UNIFIED_V1 validate temporal anchor");
      if (anchor.linkedTxNum() < 0L) {
        throw new ArchiveException("UNIFIED_V1 temporal anchor origin txNum is invalid");
      }
      ArchiveTemporalRowValidator.validate(
          catalog, latestKey, anchor.payloadView(), true, dynamicKeyPolicy);
      ArchiveTemporalIntegrityCodec.DecodedRow latest = readIntegrityRow(
          view, UnifiedArchiveColumnFamily.LATEST, latestKey,
          "UNIFIED_V1 validate latest for anchor");
      if (latest == null) {
        throw new ArchiveException("UNIFIED_V1 temporal anchor has no latest row");
      }
      byte[] historyPrefix = ArchiveTemporalCodec.historyPrefixOfLatest(latestKey);
      history.seek(historyPrefix);
      ArchiveRocksIterators.requireOk(history, "UNIFIED_V1 validate history for anchor");
      if (history.isValid() && ArchiveTemporalCodec.startsWith(history.key(), historyPrefix)) {
        ArchiveTemporalIntegrityCodec.DecodedRow firstHistory = readRequiredIntegrityRow(
            view, UnifiedArchiveColumnFamily.HISTORY, history.key(),
            "UNIFIED_V1 validate first history for anchor");
        if (firstHistory.linkedTxNum()
            != ArchiveTemporalIntegrityCodec.NO_HISTORY_TX_NUM
            || !firstHistory.payloadEquals(anchor)) {
          throw new ArchiveException("UNIFIED_V1 temporal first history/anchor mismatch");
        }
      } else {
        ArchiveTemporalIntegrityCodec.DecodedRow baseline = readIntegrityRow(
            view, UnifiedArchiveColumnFamily.LATEST,
            ArchiveTemporalCodec.latestBaselineKeyOfLatest(latestKey),
            "UNIFIED_V1 validate baseline for anchor");
        if (baseline == null || !baseline.payloadEquals(anchor)
            || !latest.payloadEquals(baseline)) {
          throw new ArchiveException("UNIFIED_V1 temporal anchor has no matching baseline");
        }
      }
      anchors.next();
    }
    ArchiveRocksIterators.requireOk(anchors, "UNIFIED_V1 validate temporal anchors");
  }

  private void validateLatestDomainRows(UnifiedArchiveReadView view) {
    UnifiedArchiveIterator iterator = view.newIterator(UnifiedArchiveColumnFamily.LATEST);
    byte[] baselinePrefix = ArchiveTemporalCodec.latestBaselinePrefix();
    iterator.seekToFirst();
    while (iterator.isValid()) {
      byte[] key = iterator.key();
      byte[] validationKey;
      if (key.length > 0 && key[0] == ArchiveTemporalCodec.LATEST_PREFIX) {
        validationKey = key;
      } else if (ArchiveTemporalCodec.startsWith(key, baselinePrefix)) {
        validationKey = ArchiveTemporalCodec.latestKeyOfBaseline(key);
      } else {
        throw new ArchiveException("UNIFIED_V1 latest column family has an unknown key");
      }
      ArchiveTemporalIntegrityCodec.DecodedRow row = readRequiredIntegrityRow(
          view, UnifiedArchiveColumnFamily.LATEST, key,
          "UNIFIED_V1 validate latest domain row");
      ArchiveTemporalRowValidator.validate(
          catalog, validationKey, row.payloadView(), true, dynamicKeyPolicy);
      iterator.next();
    }
    ArchiveRocksIterators.requireOk(iterator, "UNIFIED_V1 validate latest domain rows");
  }

  private void validateDomainRows(UnifiedArchiveReadView view,
      UnifiedArchiveColumnFamily columnFamily, byte prefix) {
    UnifiedArchiveIterator iterator = view.newIterator(columnFamily);
    byte[] previousHistoryPrefix = null;
    long previousHistoryTxNum = ArchiveTemporalIntegrityCodec.NO_HISTORY_TX_NUM;
    iterator.seekToFirst();
    while (iterator.isValid()) {
      if (iterator.key().length == 0 || iterator.key()[0] != prefix) {
        throw new ArchiveException("UNIFIED_V1 " + columnFamily.getName()
            + " column family has an unknown key");
      }
      byte[] key = iterator.key();
      ArchiveTemporalIntegrityCodec.DecodedRow row = readRequiredIntegrityRow(
          view, columnFamily, key,
          "UNIFIED_V1 validate " + columnFamily.getName() + " domain row");
      if (columnFamily == UnifiedArchiveColumnFamily.HISTORY) {
        byte[] historyPrefix = Arrays.copyOf(key, key.length - Long.BYTES);
        long expectedLink = Arrays.equals(previousHistoryPrefix, historyPrefix)
            ? previousHistoryTxNum : ArchiveTemporalIntegrityCodec.NO_HISTORY_TX_NUM;
        if (row.linkedTxNum() != expectedLink) {
          throw new ArchiveException("UNIFIED_V1 history chain link mismatch");
        }
        if (expectedLink == ArchiveTemporalIntegrityCodec.NO_HISTORY_TX_NUM) {
          ArchiveTemporalIntegrityCodec.DecodedRow anchor = readIntegrityRow(
              view, UnifiedArchiveColumnFamily.COMMITMENT,
              ArchiveTemporalCodec.anchorKeyOfHistory(key),
              "UNIFIED_V1 validate history anchor");
          if (anchor == null || anchor.linkedTxNum() < 0L
              || !row.payloadEquals(anchor)) {
            throw new ArchiveException("UNIFIED_V1 first history row has no matching anchor");
          }
        }
        previousHistoryPrefix = historyPrefix;
        previousHistoryTxNum = ArchiveTemporalCodec.txNumOfHistory(key);
      } else if (row.linkedTxNum() != ArchiveTemporalCodec.txNumOfChangeset(key)) {
        throw new ArchiveException("UNIFIED_V1 changeset txNum link mismatch");
      }
      ArchiveTemporalRowValidator.validate(
          catalog, key, row.payloadView(), true, dynamicKeyPolicy);
      iterator.next();
    }
    ArchiveRocksIterators.requireOk(iterator,
        "UNIFIED_V1 validate domain rows in " + columnFamily.getName());
  }

  public boolean hasCommitMarker(long blockNum) {
    try (UnifiedArchiveReadView view = db.openScanView()) {
      return readBlockMarker(view, ArchiveTemporalCodec.blockCommitKey(blockNum),
          "UNIFIED_V1 read temporal commit marker") != null;
    }
  }

  @Override
  public void unwind(long fromTxNum) {
    throw new ArchiveException(
        "UNIFIED_V1 temporal unwind requires one atomic backend transaction");
  }

  @Override
  public void unwindBlock(ArchiveBlockRange range) {
    throw new ArchiveException(
        "UNIFIED_V1 committed unwind requires one atomic backend transaction");
  }

  private void validateLatestRows(UnifiedArchiveReadView view) {
    UnifiedArchiveIterator latest = view.newIterator(UnifiedArchiveColumnFamily.LATEST);
    UnifiedArchiveIterator history = view.newIterator(UnifiedArchiveColumnFamily.HISTORY);
    latest.seek(new byte[] {ArchiveTemporalCodec.LATEST_PREFIX});
    while (latest.isValid() && latest.key()[0] == ArchiveTemporalCodec.LATEST_PREFIX) {
      byte[] latestKey = latest.key();
      ArchiveTemporalIntegrityCodec.DecodedRow latestRow = readRequiredIntegrityRow(
          view, UnifiedArchiveColumnFamily.LATEST, latestKey,
          "UNIFIED_V1 validate latest row");
      ArchiveTemporalIntegrityCodec.DecodedRow anchor = readIntegrityRow(
          view, UnifiedArchiveColumnFamily.COMMITMENT,
          ArchiveTemporalCodec.anchorKeyOfLatest(latestKey),
          "UNIFIED_V1 validate latest anchor");
      if (anchor == null || anchor.linkedTxNum() < 0L) {
        throw new ArchiveException("archive temporal latest has no anchor");
      }
      byte[] historyPrefix = ArchiveTemporalCodec.historyPrefixOfLatest(latestKey);
      history.seekForPrev(ArchiveTemporalCodec.historyKey(
          ArchiveTemporalCodec.domainOfLatestKey(latestKey),
          ArchiveTemporalCodec.canonicalKeyOfLatestKey(latestKey), Long.MAX_VALUE));
      ArchiveRocksIterators.requireOk(history, "UNIFIED_V1 validate latest history");
      if (history.isValid() && ArchiveTemporalCodec.startsWith(history.key(), historyPrefix)) {
        readRequiredIntegrityRow(view, UnifiedArchiveColumnFamily.HISTORY,
            history.key(), "UNIFIED_V1 validate latest history");
        long latestHistoryTxNum = ArchiveTemporalCodec.txNumOfHistory(history.key());
        ArchiveTemporalIntegrityCodec.DecodedRow expected = readIntegrityRow(
            view, UnifiedArchiveColumnFamily.CHANGESET,
            ArchiveTemporalCodec.changesetKeyOfHistory(history.key()),
            "UNIFIED_V1 validate latest changeset");
        if (latestRow.linkedTxNum() != latestHistoryTxNum
            || expected == null || expected.linkedTxNum() != latestHistoryTxNum
            || !latestRow.payloadEquals(expected)) {
          throw new ArchiveException("archive temporal latest value mismatch");
        }
        if (hasIntegrityRow(view, UnifiedArchiveColumnFamily.LATEST,
            ArchiveTemporalCodec.latestBaselineKeyOfLatest(latestKey),
            "UNIFIED_V1 validate latest baseline absence")) {
          throw new ArchiveException(
              "archive temporal latest baseline marker has a history row");
        }
      } else {
        ArchiveTemporalIntegrityCodec.DecodedRow baseline = readIntegrityRow(
            view, UnifiedArchiveColumnFamily.LATEST,
            ArchiveTemporalCodec.latestBaselineKeyOfLatest(latestKey),
            "UNIFIED_V1 validate latest baseline");
        if (latestRow.linkedTxNum() != ArchiveTemporalIntegrityCodec.NO_HISTORY_TX_NUM
            || baseline == null
            || baseline.linkedTxNum() != ArchiveTemporalIntegrityCodec.NO_HISTORY_TX_NUM
            || !latestRow.payloadEquals(baseline)
            || !baseline.payloadEquals(anchor)) {
          throw new ArchiveException("archive temporal latest has no history row or baseline");
        }
      }
      latest.next();
    }
    ArchiveRocksIterators.requireOk(latest, "UNIFIED_V1 validate latest rows");
    validateLatestBaselineRows(view, history);
  }

  private static void validateLatestBaselineRows(UnifiedArchiveReadView view,
      UnifiedArchiveIterator history) {
    byte[] prefix = ArchiveTemporalCodec.latestBaselinePrefix();
    UnifiedArchiveIterator baseline = view.newIterator(UnifiedArchiveColumnFamily.LATEST);
    baseline.seek(prefix);
    while (baseline.isValid() && ArchiveTemporalCodec.startsWith(baseline.key(), prefix)) {
      byte[] latestKey = ArchiveTemporalCodec.latestKeyOfBaseline(baseline.key());
      ArchiveTemporalIntegrityCodec.DecodedRow baselineValue = readRequiredIntegrityRow(
          view, UnifiedArchiveColumnFamily.LATEST, baseline.key(),
          "UNIFIED_V1 validate latest baseline row");
      ArchiveTemporalIntegrityCodec.DecodedRow latestValue = readIntegrityRow(
          view, UnifiedArchiveColumnFamily.LATEST, latestKey,
          "UNIFIED_V1 validate latest row for baseline");
      ArchiveTemporalIntegrityCodec.DecodedRow anchorValue = readIntegrityRow(
          view, UnifiedArchiveColumnFamily.COMMITMENT,
          ArchiveTemporalCodec.anchorKeyOfLatest(latestKey),
          "UNIFIED_V1 validate anchor for baseline");
      if (baselineValue.linkedTxNum() != ArchiveTemporalIntegrityCodec.NO_HISTORY_TX_NUM
          || latestValue == null
          || latestValue.linkedTxNum() != ArchiveTemporalIntegrityCodec.NO_HISTORY_TX_NUM
          || !latestValue.payloadEquals(baselineValue)
          || anchorValue == null || anchorValue.linkedTxNum() < 0L
          || !baselineValue.payloadEquals(anchorValue)) {
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
    UnifiedArchiveIterator changeset = view.newIterator(UnifiedArchiveColumnFamily.CHANGESET);
    changeset.seek(ArchiveTemporalCodec.changesetSeekFrom(range.getFirstTxNum()));
    return readBlockRows(view, changeset, range);
  }

  private BlockRows readBlockRows(UnifiedArchiveReadView view,
      UnifiedArchiveIterator changeset,
      ArchiveBlockRange range) {
    MessageDigest digest = newDigest();
    int count = 0;
    while (changeset.isValid()
        && changeset.key()[0] == ArchiveTemporalCodec.CHANGESET_PREFIX) {
      byte[] changesetKey = changeset.key().clone();
      long txNum = ArchiveTemporalCodec.txNumOfChangeset(changesetKey);
      if (txNum < range.getFirstTxNum()) {
        throw new ArchiveException("archive temporal changeset precedes block range for block "
            + range.getBlockNum());
      }
      if (txNum > range.getLastTxNum()) {
        break;
      }
      ArchiveTemporalIntegrityCodec.DecodedRow changesetValue = readRequiredIntegrityRow(
          view, UnifiedArchiveColumnFamily.CHANGESET, changesetKey,
          "UNIFIED_V1 read block commit changeset");
      if (changesetValue.linkedTxNum() != txNum) {
        throw new ArchiveException(
            "archive temporal changeset link mismatch for commit marker txNum " + txNum);
      }
      byte[] historyKey = ArchiveTemporalCodec.historyKeyOfChangeset(changesetKey);
      ArchiveTemporalIntegrityCodec.DecodedRow historyValue = readIntegrityRow(
          view, UnifiedArchiveColumnFamily.HISTORY, historyKey,
          "UNIFIED_V1 read block commit history");
      if (historyValue == null) {
        throw new ArchiveException(
            "archive temporal history missing for commit marker txNum " + txNum);
      }
      if (historyValue.linkedTxNum() >= txNum) {
        throw new ArchiveException(
            "archive temporal history link is invalid for commit marker txNum " + txNum);
      }
      ArchiveTemporalIntegrityCodec.DecodedRow anchor = null;
      if (historyValue.linkedTxNum()
          == ArchiveTemporalIntegrityCodec.NO_HISTORY_TX_NUM) {
        byte[] anchorKey = ArchiveTemporalCodec.anchorKeyOfHistory(historyKey);
        anchor = readIntegrityRow(view, UnifiedArchiveColumnFamily.COMMITMENT, anchorKey,
            "UNIFIED_V1 read block commit anchor");
        if (anchor == null || anchor.linkedTxNum() < 0L
            || !historyValue.payloadEquals(anchor)) {
          throw new ArchiveException(
              "archive temporal first history row has no matching anchor");
        }
      }
      updateDigest(digest, changesetKey, changesetValue.linkedTxNum(), changesetValue);
      updateDigest(digest, historyKey, historyValue.linkedTxNum(), historyValue);
      if (anchor != null) {
        updateDigest(digest, ArchiveTemporalCodec.anchorKeyOfHistory(historyKey),
            anchor.linkedTxNum(), anchor);
      }
      if (count == Integer.MAX_VALUE) {
        throw new ArchiveException("archive temporal block row count overflow for block "
            + range.getBlockNum());
      }
      count++;
      changeset.next();
    }
    ArchiveRocksIterators.requireOk(changeset, "UNIFIED_V1 read block commit rows");
    return new BlockRows(count, digest.digest());
  }

  private static void requireCommitMarker(UnifiedArchiveIterator markers, long blockNum) {
    ArchiveRocksIterators.requireOk(markers, "UNIFIED_V1 validate temporal marker");
    if (!markers.isValid()) {
      throw new ArchiveException("archive temporal commit marker missing for block " + blockNum);
    }
    if (!ArchiveTemporalCodec.startsWith(
        markers.key(), ArchiveTemporalCodec.blockCommitPrefix())) {
      throw new ArchiveException("UNIFIED_V1 block-marker column family has an unknown key");
    }
    long markerBlock = ArchiveTemporalCodec.blockNumOfBlockCommitKey(markers.key());
    if (markerBlock != blockNum) {
      throw new ArchiveException("archive temporal/index marker mismatch: marker=" + markerBlock
          + ", index=" + blockNum);
    }
  }

  private Optional<DomainValue> getAsOf(UnifiedArchiveReadView view,
      UnifiedArchiveIterator history,
      ArchiveDomain domain, byte[] canonicalKey, long txNum) {
    return ArchiveTemporalReadSupport.getAsOf(domain, canonicalKey, txNum, history,
        key -> readIntegrityRow(view, UnifiedArchiveColumnFamily.HISTORY, key,
            "UNIFIED_V1 history row"),
        key -> readIntegrityRow(view, UnifiedArchiveColumnFamily.LATEST, key,
            "UNIFIED_V1 latest row"),
        key -> readIntegrityRow(view, UnifiedArchiveColumnFamily.CHANGESET, key,
            "UNIFIED_V1 changeset row"),
        key -> readIntegrityRow(view, UnifiedArchiveColumnFamily.LATEST, key,
            "UNIFIED_V1 latest baseline row"),
        key -> readIntegrityRow(view, UnifiedArchiveColumnFamily.COMMITMENT, key,
            "UNIFIED_V1 anchor row"),
        "UNIFIED_V1 getAsOf");
  }

  private static ArchiveTemporalIntegrityCodec.DecodedRow readIntegrityRow(
      UnifiedArchiveReadView view,
      UnifiedArchiveColumnFamily columnFamily, byte[] key, String what) {
    byte[] locator = view.getExact(
        columnFamily, key, ArchiveTemporalIntegrityCodec.LOCATOR_BYTES, what + " locator");
    return locator == null ? null
        : decodeIntegrityRow(view, columnFamily, key, locator, what);
  }

  private static ArchiveTemporalIntegrityCodec.Locator readIntegrityLocator(
      UnifiedArchiveReadView view,
      UnifiedArchiveColumnFamily columnFamily, byte[] key, String what) {
    byte[] locator = view.getExact(
        columnFamily, key, ArchiveTemporalIntegrityCodec.LOCATOR_BYTES, what + " locator");
    return locator == null ? null : ArchiveTemporalIntegrityCodec.decodeLocator(
        columnFamily, key, locator, what);
  }

  private static ArchiveTemporalIntegrityCodec.DecodedRow readPlannedIntegrityRow(
      UnifiedArchiveReadView view, UnifiedArchiveColumnFamily columnFamily,
      byte[] key, ArchiveTemporalIntegrityCodec.Locator locator, String what) {
    if (locator == null) {
      return null;
    }
    byte[] payloadKey = ArchiveTemporalIntegrityCodec.payloadKey(columnFamily, key);
    byte[] payload = view.getPreaccountedExact(
        UnifiedArchiveColumnFamily.TEMPORAL_PAYLOAD, payloadKey,
        locator.payloadBytes(), what + " payload");
    if (payload == null) {
      throw new ArchiveException(what + ": temporal payload is missing");
    }
    return ArchiveTemporalIntegrityCodec.decode(
        columnFamily, key, locator, payload, what);
  }

  private static ArchiveTemporalIntegrityCodec.DecodedRow readRequiredPlannedIntegrityRow(
      UnifiedArchiveReadView view, UnifiedArchiveColumnFamily columnFamily,
      byte[] key, ArchiveTemporalIntegrityCodec.Locator locator, String what) {
    ArchiveTemporalIntegrityCodec.DecodedRow row =
        readPlannedIntegrityRow(view, columnFamily, key, locator, what);
    if (row == null) {
      throw new ArchiveException(what + ": temporal locator is missing");
    }
    return row;
  }

  private static ArchiveTemporalIntegrityCodec.DecodedRow readRequiredIntegrityRow(
      UnifiedArchiveReadView view, UnifiedArchiveColumnFamily columnFamily,
      byte[] key, String what) {
    ArchiveTemporalIntegrityCodec.DecodedRow row = readIntegrityRow(
        view, columnFamily, key, what);
    if (row == null) {
      throw new ArchiveException(what + ": temporal locator is missing");
    }
    return row;
  }

  private static boolean hasIntegrityRow(UnifiedArchiveReadView view,
      UnifiedArchiveColumnFamily columnFamily, byte[] key, String what) {
    return view.getExact(columnFamily, key, ArchiveTemporalIntegrityCodec.LOCATOR_BYTES,
        what + " locator") != null;
  }

  private static byte[] readBlockMarker(
      UnifiedArchiveReadView view, byte[] key, String what) {
    return view.getExact(UnifiedArchiveColumnFamily.BLOCK_MARKER, key,
        ArchiveTemporalCodec.blockCommitValueLength(), what);
  }

  private static ArchiveTemporalIntegrityCodec.DecodedRow decodeIntegrityRow(
      UnifiedArchiveReadView view, UnifiedArchiveColumnFamily columnFamily,
      byte[] key, byte[] locatorBytes, String what) {
    ArchiveTemporalIntegrityCodec.Locator locator =
        ArchiveTemporalIntegrityCodec.decodeLocator(
            columnFamily, key, locatorBytes, what);
    return decodeIntegrityRow(view, columnFamily, key, locator, what);
  }

  private static ArchiveTemporalIntegrityCodec.DecodedRow decodeIntegrityRow(
      UnifiedArchiveReadView view, UnifiedArchiveColumnFamily columnFamily,
      byte[] key, ArchiveTemporalIntegrityCodec.Locator locator, String what) {
    byte[] payloadKey = ArchiveTemporalIntegrityCodec.payloadKey(columnFamily, key);
    byte[] payload = view.getExactBudgeted(
        UnifiedArchiveColumnFamily.TEMPORAL_PAYLOAD, payloadKey,
        locator.payloadBytes(), what + " payload");
    if (payload == null) {
      throw new ArchiveException(what + ": temporal payload is missing");
    }
    return ArchiveTemporalIntegrityCodec.decode(
        columnFamily, key, locator, payload, what);
  }

  private static void closeAfterFailure(UnifiedArchiveReadView view, Throwable failure) {
    try {
      view.close();
    } catch (Throwable closeFailure) {
      if (failure != closeFailure) {
        try {
          failure.addSuppressed(closeFailure);
        } catch (Throwable ignored) {
          // Preserve the preflight failure when a hostile Throwable rejects suppression.
        }
      }
    }
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
    return result != 0 ? result : left.compareCanonicalKeyTo(right);
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

  private static void updateDigest(MessageDigest digest,
      ArchiveTemporalIntegrityCodec.DecodedRow row) {
    int length = row.payloadBytes();
    digest.update((byte) (length >>> 24));
    digest.update((byte) (length >>> 16));
    digest.update((byte) (length >>> 8));
    digest.update((byte) length);
    row.updatePayloadDigest(digest);
  }

  private static void updateDigest(MessageDigest digest, byte[] key, long linkedTxNum,
      byte[] payload) {
    updateDigest(digest, key);
    updateLong(digest, linkedTxNum);
    updateDigest(digest, payload);
  }

  private static void updateDigest(MessageDigest digest, byte[] key, long linkedTxNum,
      ArchiveTemporalIntegrityCodec.DecodedRow row) {
    updateDigest(digest, key);
    updateLong(digest, linkedTxNum);
    updateDigest(digest, row);
  }

  private static void updateLong(MessageDigest digest, long value) {
    for (int shift = 56; shift >= 0; shift -= 8) {
      digest.update((byte) (value >>> shift));
    }
  }

  public void close() {
    // UnifiedArchiveTxNumIndex owns the shared DB and closes it last.
  }

  private final class SnapshotView implements ArchiveTemporalReadView {

    private final UnifiedArchiveReadView view;
    private final UnifiedArchiveIterator history;
    private final Thread owner = Thread.currentThread();
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
      return 0L;
    }

    @Override
    public Optional<DomainValue> latest(ArchiveDomain domain, byte[] canonicalKey) {
      requireOpen();
      return UnifiedArchiveTemporalStore.this.getAsOf(
          view, history, domain, canonicalKey, Long.MAX_VALUE);
    }

    @Override
    public void close() {
      requireOwner();
      if (!closed) {
        view.close();
        closed = true;
      }
    }

    private void requireOpen() {
      requireOwner();
      if (closed) {
        throw new ArchiveException("UNIFIED_V1 temporal read view is closed");
      }
    }

    private void requireOwner() {
      if (Thread.currentThread() != owner) {
        throw new ArchiveException(
            "UNIFIED_V1 temporal read view used from a non-owner thread");
      }
    }
  }

  private static final class PreparationEstimate {

    private final long retainedBytes;
    private final long mutations;

    private PreparationEstimate(long retainedBytes, long mutations) {
      this.retainedBytes = retainedBytes;
      this.mutations = mutations;
    }
  }

  private static final class PersistedPreparationEstimate {

    private long payloadAndCopyBytes;
    private long maxNativeReadBytes;

    private void addPayload(ArchiveTemporalIntegrityCodec.Locator locator) {
      if (locator != null) {
        addPayloadBytes(locator.payloadBytes());
      }
    }

    private void addPayloadBytes(long bytes) {
      payloadAndCopyBytes = addSaturated(payloadAndCopyBytes, bytes);
      // RocksDB's preallocated-buffer JNI Get still materializes one native PinnableSlice.
      maxNativeReadBytes = Math.max(maxNativeReadBytes, bytes);
    }

    private void addCopy(long bytes) {
      payloadAndCopyBytes = addSaturated(payloadAndCopyBytes, bytes);
    }
  }

  private static final class PersistedLatestStatePlan {

    private final byte[] anchorKey;
    private final ArchiveTemporalIntegrityCodec.Locator anchor;
    private final byte[] latestKey;
    private final ArchiveTemporalIntegrityCodec.Locator current;
    private final byte[] historyTailKey;
    private final ArchiveTemporalIntegrityCodec.Locator historyTail;
    private final byte[] predecessorHistoryKey;
    private final byte[] predecessorChangesetKey;
    private final ArchiveTemporalIntegrityCodec.Locator predecessorChangeset;
    private final byte[] baselineKey;
    private final ArchiveTemporalIntegrityCodec.Locator baseline;
    private final byte[] tailChangesetKey;
    private final ArchiveTemporalIntegrityCodec.Locator tailChangeset;

    private PersistedLatestStatePlan(byte[] anchorKey,
        ArchiveTemporalIntegrityCodec.Locator anchor, byte[] latestKey,
        ArchiveTemporalIntegrityCodec.Locator current, byte[] historyTailKey,
        ArchiveTemporalIntegrityCodec.Locator historyTail, byte[] predecessorHistoryKey,
        byte[] predecessorChangesetKey,
        ArchiveTemporalIntegrityCodec.Locator predecessorChangeset, byte[] baselineKey,
        ArchiveTemporalIntegrityCodec.Locator baseline, byte[] tailChangesetKey,
        ArchiveTemporalIntegrityCodec.Locator tailChangeset) {
      this.anchorKey = anchorKey;
      this.anchor = anchor;
      this.latestKey = latestKey;
      this.current = current;
      this.historyTailKey = historyTailKey;
      this.historyTail = historyTail;
      this.predecessorHistoryKey = predecessorHistoryKey;
      this.predecessorChangesetKey = predecessorChangesetKey;
      this.predecessorChangeset = predecessorChangeset;
      this.baselineKey = baselineKey;
      this.baseline = baseline;
      this.tailChangesetKey = tailChangesetKey;
      this.tailChangeset = tailChangeset;
    }
  }

  /** Single-use locator plan whose snapshot remains open until publication preparation completes. */
  public static final class PublicationPreflight implements AutoCloseable {

    private final UnifiedArchiveTemporalStore owner;
    private final List<ArchiveChangeRecord> orderedRecords;
    private final Map<WrappedByteArray, PersistedLatestStatePlan> plans;
    private final UnifiedArchiveReadView view;
    private final long persistedPreparationBytes;
    private final Thread ownerThread = Thread.currentThread();
    private boolean consumed;
    private boolean closed;

    private PublicationPreflight(UnifiedArchiveTemporalStore owner,
        List<ArchiveChangeRecord> orderedRecords,
        Map<WrappedByteArray, PersistedLatestStatePlan> plans,
        UnifiedArchiveReadView view, long persistedPreparationBytes) {
      this.owner = owner;
      this.orderedRecords = orderedRecords;
      this.plans = plans;
      this.view = view;
      this.persistedPreparationBytes = persistedPreparationBytes;
    }

    public long getPersistedPreparationBytes() {
      requireOpen();
      return persistedPreparationBytes;
    }

    @Override
    public void close() {
      requireOwner();
      if (!closed) {
        closed = true;
        if (view != null) {
          view.close();
        }
      }
    }

    private void claim(UnifiedArchiveTemporalStore candidateOwner) {
      requireOwnedAndOpen(candidateOwner);
      if (consumed) {
        throw new ArchiveException("archive temporal publication preflight is already consumed");
      }
      consumed = true;
    }

    private void requireOwnedAndOpen(UnifiedArchiveTemporalStore candidateOwner) {
      requireOpen();
      if (owner != candidateOwner) {
        throw new ArchiveException(
            "archive temporal publication preflight belongs to another store");
      }
    }

    private void requireOpen() {
      requireOwner();
      if (closed) {
        throw new ArchiveException("archive temporal publication preflight is closed");
      }
    }

    private void requireOwner() {
      if (Thread.currentThread() != ownerThread) {
        throw new ArchiveException(
            "archive temporal publication preflight used from a non-owner thread");
      }
    }
  }

  public static final class PublicationEstimate {

    private final long retainedBytes;
    private final long mutations;

    private PublicationEstimate(long retainedBytes, long mutations) {
      this.retainedBytes = retainedBytes;
      this.mutations = mutations;
    }

    public long getRetainedBytes() {
      return retainedBytes;
    }

    public long getMutations() {
      return mutations;
    }
  }

  private static final class PreparedChanges {

    private final List<Row> anchorRows;
    private final List<Row> historyRows;
    private final List<Row> changesetRows;
    private final Map<WrappedByteArray, LatestRows> latestRows;
    private final int rowCount;
    private final byte[] digest;

    private PreparedChanges(List<Row> anchorRows, List<Row> historyRows, List<Row> changesetRows,
        Map<WrappedByteArray, LatestRows> latestRows, int rowCount, byte[] digest) {
      this.anchorRows = anchorRows;
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
    private final long changesetLinkedTxNum;
    private final byte[] historyKey;
    private final byte[] historyValue;
    private final long historyLinkedTxNum;
    private final Row anchor;

    private CommitDigestRow(byte[] changesetKey, byte[] changesetValue,
        long changesetLinkedTxNum, byte[] historyKey, byte[] historyValue,
        long historyLinkedTxNum, Row anchor) {
      this.changesetKey = changesetKey;
      this.changesetValue = changesetValue;
      this.changesetLinkedTxNum = changesetLinkedTxNum;
      this.historyKey = historyKey;
      this.historyValue = historyValue;
      this.historyLinkedTxNum = historyLinkedTxNum;
      this.anchor = anchor;
    }
  }

  private static final class Row {

    private final byte[] key;
    private final byte[] value;
    private final long linkedTxNum;

    private Row(byte[] key, byte[] value) {
      this(key, value, ArchiveTemporalIntegrityCodec.NO_HISTORY_TX_NUM);
    }

    private Row(byte[] key, byte[] value, long linkedTxNum) {
      this.key = key;
      this.value = value;
      this.linkedTxNum = linkedTxNum;
    }
  }

  private static final class LatestRows {

    private final byte[] latestKey;
    private final byte[] baselineKey;
    private final byte[] value;
    private final long lastTxNum;

    private LatestRows(byte[] latestKey, byte[] baselineKey, byte[] value, long lastTxNum) {
      this.latestKey = latestKey;
      this.baselineKey = baselineKey;
      this.value = value;
      this.lastTxNum = lastTxNum;
    }
  }

  private static final class LatestState {

    private DomainValue value;
    private long lastTxNum;
    private boolean anchored;
    private byte[] anchorKey;
    private byte[] anchorValue;
    private long anchorOriginTxNum;

    private LatestState(DomainValue value, long lastTxNum, boolean anchored, byte[] anchorKey,
        byte[] anchorValue, long anchorOriginTxNum) {
      this.value = value;
      this.lastTxNum = lastTxNum;
      this.anchored = anchored;
      this.anchorKey = anchorKey;
      this.anchorValue = anchorValue;
      this.anchorOriginTxNum = anchorOriginTxNum;
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
