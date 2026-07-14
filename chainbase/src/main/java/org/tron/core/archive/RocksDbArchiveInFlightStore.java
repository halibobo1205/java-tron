package org.tron.core.archive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.rocksdb.Options;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.Snapshot;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.domain.ArchiveDomainCatalog;
import org.tron.core.archive.domain.ArchiveDomainDescriptor;
import org.tron.core.archive.domain.DefaultArchiveDomainCatalog;
import org.tron.core.archive.domain.DynamicKeyPolicy;
import org.tron.core.archive.domain.HistoryPolicy;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.archive.txnum.ArchiveTxPosition;

/** RocksDB-backed durable journal for not-yet-solidified archive blocks. */
public final class RocksDbArchiveInFlightStore implements ArchiveInFlightStore {

  static {
    RocksDB.loadLibrary();
  }

  private final Options options;
  private final RocksDB db;
  private final Path dbPath;
  private final ArchiveDomainCatalog catalog;
  private final DynamicKeyPolicy dynamicKeyPolicy = new DynamicKeyPolicy();

  public RocksDbArchiveInFlightStore(String path) {
    this(path, new DefaultArchiveDomainCatalog(), true);
  }

  public RocksDbArchiveInFlightStore(String path, ArchiveDomainCatalog catalog) {
    this(path, catalog, true);
  }

  /** Opens the journal, optionally deferring its duplicate full scan to streamed service loading. */
  public RocksDbArchiveInFlightStore(String path, ArchiveDomainCatalog catalog,
      boolean validateFullKeyspace) {
    this(path, catalog, validateFullKeyspace, true, false);
  }

  /** Strict existing-store and read-only adoption entry point. */
  public RocksDbArchiveInFlightStore(String path, ArchiveDomainCatalog catalog,
      boolean validateFullKeyspace, boolean allowInitialize, boolean readOnly) {
    if (catalog == null) {
      throw new ArchiveException("archive in-flight store requires a domain catalog");
    }
    if (readOnly && allowInitialize) {
      throw new IllegalArgumentException("read-only in-flight store cannot initialize");
    }
    this.catalog = catalog;
    this.dbPath = Paths.get(path).toAbsolutePath().normalize();
    this.options = new Options().setCreateIfMissing(allowInitialize);
    RocksDB opened = null;
    try {
      opened = readOnly ? RocksDB.openReadOnly(options, path) : RocksDB.open(options, path);
      validateOrInstallManifest(opened, catalog, validateFullKeyspace, allowInitialize);
      this.db = opened;
    } catch (RocksDBException e) {
      closeQuietly(opened);
      options.close();
      throw new ArchiveException("failed to open archive in-flight store at " + path, e);
    } catch (RuntimeException e) {
      closeQuietly(opened);
      options.close();
      throw e;
    }
  }

  private static void closeQuietly(RocksDB db) {
    if (db != null) {
      db.close();
    }
  }

  private static void validateOrInstallManifest(RocksDB db, ArchiveDomainCatalog catalog,
      boolean validateFullKeyspace, boolean allowInitialize) throws RocksDBException {
    byte[] manifest = db.get(ArchiveInFlightCodec.manifestKey());
    if (manifest != null) {
      if (!ArchiveInFlightCodec.manifestMatches(manifest)) {
        throw new ArchiveException("archive in-flight manifest mismatch");
      }
      if (validateFullKeyspace) {
        validateCurrentKeyspace(db, catalog);
      }
      return;
    }
    if (!isEmpty(db)) {
      throw new ArchiveException("archive in-flight store is non-empty but missing manifest");
    }
    if (!allowInitialize) {
      throw new ArchiveException("archive in-flight store is empty or missing manifest");
    }
    try (WriteOptions writeOptions = ArchiveRocksDbWriteOptions.createForcedSync()) {
      db.put(writeOptions, ArchiveInFlightCodec.manifestKey(),
          ArchiveInFlightCodec.manifestValue());
    }
  }

  private static boolean isEmpty(RocksDB db) {
    try (RocksIterator it = db.newIterator()) {
      it.seekToFirst();
      ArchiveRocksIterators.requireOk(it, "isEmpty: scan in-flight store for any row");
      return !it.isValid();
    }
  }

  private static void validateCurrentKeyspace(RocksDB db, ArchiveDomainCatalog catalog) {
    byte[] blockPrefix = ArchiveInFlightCodec.blockPrefix();
    byte[] acknowledgementPrefix = ArchiveInFlightCodec.acknowledgementPrefix();
    byte[] tokenPrefix = ArchiveInFlightCodec.tokenPrefix();
    try (RocksIterator it = db.newIterator()) {
      it.seekToFirst();
      while (it.isValid()) {
        byte[] key = it.key();
        byte[] value = it.value();
        if (Arrays.equals(key, ArchiveInFlightCodec.manifestKey())) {
          if (!ArchiveInFlightCodec.manifestMatches(value)) {
            throw new ArchiveException("archive in-flight manifest mismatch");
          }
          it.next();
          continue;
        }
        if (ArchiveInFlightCodec.startsWith(key, acknowledgementPrefix)) {
          validateAcknowledgementRow(db, key, value);
          it.next();
          continue;
        }
        if (ArchiveInFlightCodec.startsWith(key, tokenPrefix)) {
          validateTokenRow(db, key, value);
          it.next();
          continue;
        }
        if (!ArchiveInFlightCodec.startsWith(key, blockPrefix)) {
          throw new ArchiveException("archive in-flight store has unknown key prefix");
        }
        long blockNum = ArchiveInFlightCodec.blockNumOfKey(key);
        ArchiveInFlightBlock block = ArchiveInFlightCodec.decodeBlock(value);
        validateBlock(block, catalog, new DynamicKeyPolicy());
        if (block.getRange().getBlockNum() != blockNum) {
          throw new ArchiveException("archive in-flight block key/value mismatch for block "
              + blockNum);
        }
        it.next();
      }
      ArchiveRocksIterators.requireOk(it, "validateCurrentKeyspace: scan in-flight keyspace");
    }
  }

  private static void validateTokenRow(RocksDB db, byte[] key, byte[] value) {
    long blockNum = ArchiveInFlightCodec.blockNumOfTokenKey(key);
    ArchiveJournalToken token = ArchiveInFlightCodec.decodeAcknowledgement(value);
    if (token.getBlockNum() != blockNum) {
      throw new ArchiveException("archive journal token key/value mismatch for block "
          + blockNum);
    }
    try {
      byte[] blockValue = db.get(ArchiveInFlightCodec.blockKey(blockNum));
      if (blockValue == null) {
        throw new ArchiveException("archive journal token has no journal block " + blockNum);
      }
      ArchiveInFlightBlock block = ArchiveInFlightCodec.decodeBlock(blockValue);
      if (!token.equals(block.getJournalToken())) {
        throw new ArchiveException("archive journal token mismatch for block " + blockNum);
      }
    } catch (RocksDBException e) {
      throw new ArchiveException("archive journal token validation failed for block "
          + blockNum, e);
    }
  }

  private static void validateAcknowledgementRow(RocksDB db, byte[] key, byte[] value) {
    long blockNum = ArchiveInFlightCodec.blockNumOfAcknowledgementKey(key);
    ArchiveJournalToken token = ArchiveInFlightCodec.decodeAcknowledgement(value);
    if (token.getBlockNum() != blockNum) {
      throw new ArchiveException("archive acknowledgement key/value mismatch for block "
          + blockNum);
    }
    try {
      byte[] blockValue = db.get(ArchiveInFlightCodec.blockKey(blockNum));
      if (blockValue == null) {
        throw new ArchiveException("archive acknowledgement has no journal block " + blockNum);
      }
      ArchiveInFlightBlock block = ArchiveInFlightCodec.decodeBlock(blockValue);
      if (!token.equals(block.getJournalToken())) {
        throw new ArchiveException("archive acknowledgement token mismatch for block " + blockNum);
      }
    } catch (RocksDBException e) {
      throw new ArchiveException("archive acknowledgement validation failed for block "
          + blockNum, e);
    }
  }

  @Override
  public List<ArchiveInFlightBlock> loadBlocks() {
    List<ArchiveInFlightBlock> blocks = new ArrayList<>();
    forEachBlock(blocks::add);
    return blocks;
  }

  @Override
  public void forEachBlock(Consumer<ArchiveInFlightBlock> consumer) {
    if (consumer == null) {
      throw new NullPointerException("consumer");
    }
    byte[] prefix = ArchiveInFlightCodec.blockPrefix();
    Snapshot snapshot = db.getSnapshot();
    try (ReadOptions readOptions = new ReadOptions().setSnapshot(snapshot);
         RocksIterator it = db.newIterator(readOptions)) {
      it.seek(prefix);
      while (it.isValid() && ArchiveInFlightCodec.startsWith(it.key(), prefix)) {
        long blockNum = ArchiveInFlightCodec.blockNumOfKey(it.key());
        ArchiveInFlightBlock block = applyAcknowledgement(
            ArchiveInFlightCodec.decodeBlock(it.value()));
        validateBlock(block, catalog, dynamicKeyPolicy);
        if (block.getRange().getBlockNum() != blockNum) {
          throw new ArchiveException("archive in-flight block key/value mismatch for block "
              + blockNum);
        }
        consumer.accept(block);
        it.next();
      }
      ArchiveRocksIterators.requireOk(it, "loadBlocks: scan in-flight blocks");
    } finally {
      db.releaseSnapshot(snapshot);
    }
  }

  private ArchiveInFlightBlock applyAcknowledgement(ArchiveInFlightBlock block) {
    long blockNum = block.getRange().getBlockNum();
    try {
      byte[] value = db.get(ArchiveInFlightCodec.acknowledgementKey(blockNum));
      if (value == null) {
        return block;
      }
      ArchiveJournalToken token = ArchiveInFlightCodec.decodeAcknowledgement(value);
      if (!token.equals(block.getJournalToken())) {
        throw new ArchiveException("archive acknowledgement token mismatch for block " + blockNum);
      }
      return block.getJournalState() == ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED
          ? block
          : block.withJournalState(ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED);
    } catch (RocksDBException e) {
      throw new ArchiveException("archive acknowledgement read failed for block " + blockNum, e);
    }
  }

  @Override
  public void putBlock(ArchiveInFlightBlock block) {
    validateBlock(block, catalog, dynamicKeyPolicy);
    long blockNum = block.getRange().getBlockNum();
    byte[] encodedBlock = ArchiveInFlightCodec.encodeBlock(block);
    long startedNanos = ArchiveMetrics.startTimer();
    try (WriteBatch batch = new WriteBatch();
         WriteOptions writeOptions = ArchiveRocksDbWriteOptions.createForcedSync()) {
      batch.put(ArchiveInFlightCodec.blockKey(blockNum), encodedBlock);
      batch.put(ArchiveInFlightCodec.tokenKey(blockNum),
          ArchiveInFlightCodec.encodeAcknowledgement(block.getJournalToken()));
      if (block.getJournalState() == ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED) {
        batch.put(ArchiveInFlightCodec.acknowledgementKey(blockNum),
            ArchiveInFlightCodec.encodeAcknowledgement(block.getJournalToken()));
      } else {
        batch.delete(ArchiveInFlightCodec.acknowledgementKey(blockNum));
      }
      db.write(writeOptions, batch);
      ArchiveMetrics.journalWritten(encodedBlock.length, startedNanos);
    } catch (RocksDBException e) {
      throw new ArchiveException("archive in-flight put failed", e);
    }
  }

  @Override
  public void acknowledgeBlock(ArchiveInFlightBlock block) {
    if (block.getJournalState() != ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED) {
      throw new ArchiveException("archive acknowledgement requires canonical-committed state");
    }
    acknowledgeBlock(block.getJournalToken());
  }

  @Override
  public void acknowledgeBlock(ArchiveJournalToken token) {
    long blockNum = token.getBlockNum();
    try {
      byte[] persistedValue = db.get(ArchiveInFlightCodec.tokenKey(blockNum));
      ArchiveJournalToken persistedToken;
      if (persistedValue == null) {
        // Compatibility fallback for journals written before compact token headers existed.
        byte[] blockValue = db.get(ArchiveInFlightCodec.blockKey(blockNum));
        if (blockValue == null) {
          throw new ArchiveException("archive acknowledgement has no journal block " + blockNum);
        }
        persistedToken = ArchiveInFlightCodec.decodeBlock(blockValue).getJournalToken();
      } else {
        persistedToken = ArchiveInFlightCodec.decodeAcknowledgement(persistedValue);
      }
      if (!persistedToken.equals(token)) {
        throw new ArchiveException("archive acknowledgement token mismatch for block " + blockNum);
      }
      byte[] acknowledgement = ArchiveInFlightCodec.encodeAcknowledgement(token);
      long startedNanos = ArchiveMetrics.startTimer();
      try (WriteOptions writeOptions = ArchiveRocksDbWriteOptions.createWalOnly()) {
        db.put(writeOptions, ArchiveInFlightCodec.acknowledgementKey(blockNum),
            acknowledgement);
      }
      ArchiveMetrics.journalAcknowledged(acknowledgement.length, startedNanos);
    } catch (RocksDBException e) {
      throw new ArchiveException("archive acknowledgement write failed for block " + blockNum, e);
    }
  }

  static void validateBlock(ArchiveInFlightBlock block, ArchiveDomainCatalog catalog,
      DynamicKeyPolicy dynamicKeyPolicy) {
    if (block == null || block.getRange() == null) {
      throw new ArchiveException("archive in-flight block is invalid");
    }
    ArchiveBlockRange range = block.getRange();
    validateRange(range);
    Map<Long, ArchiveTxPosition> positions = validatePositions(range, block.getPositions());
    validateRecords(range, positions, block.getRecords(), catalog, dynamicKeyPolicy);
  }

  private static void validateRange(ArchiveBlockRange range) {
    if (range.getBlockNum() < 0 || range.getFirstTxNum() < 0 || range.getLastTxNum() < 0
        || range.getPrepareTxNum() < 0 || range.getFinalizeTxNum() < 0) {
      throw new ArchiveException("archive in-flight range coordinates must be non-negative");
    }
    if (range.getFirstTxNum() > range.getLastTxNum()) {
      throw new ArchiveException("archive in-flight range has invalid txNum order");
    }
    if (range.getPrepareTxNum() < range.getFirstTxNum()
        || range.getPrepareTxNum() > range.getLastTxNum()
        || range.getFinalizeTxNum() < range.getFirstTxNum()
        || range.getFinalizeTxNum() > range.getLastTxNum()) {
      throw new ArchiveException("archive in-flight system txNum is outside block range");
    }
    if (range.getPrepareTxNum() != range.getFirstTxNum()) {
      throw new ArchiveException("archive in-flight prepare txNum must be first for block "
          + range.getBlockNum());
    }
    if (range.getFinalizeTxNum() != range.getLastTxNum()) {
      throw new ArchiveException("archive in-flight finalize txNum must be last for block "
          + range.getBlockNum());
    }
    if (range.getUserTxCount() < 0) {
      throw new ArchiveException("archive in-flight user tx count must be non-negative");
    }
    long expectedSpan = (long) range.getUserTxCount() + 2L;
    long actualSpan = range.getLastTxNum() - range.getFirstTxNum() + 1L;
    if (actualSpan != expectedSpan) {
      throw new ArchiveException(
          "archive in-flight txNum span does not match user tx count for block "
              + range.getBlockNum());
    }
    requireLength(range.getBlockHash(), ArchiveBlockRange.BLOCK_HASH_LENGTH,
        "archive in-flight block hash");
    requireLength(range.getSchemaChecksum(), ArchiveBlockRange.SCHEMA_CHECKSUM_LENGTH,
        "archive in-flight schema checksum");
  }

  private static Map<Long, ArchiveTxPosition> validatePositions(ArchiveBlockRange range,
      List<ArchiveTxPosition> positions) {
    if (positions == null) {
      throw new ArchiveException("archive in-flight positions are missing");
    }
    long expectedCount = range.getLastTxNum() - range.getFirstTxNum() + 1;
    if (expectedCount > Integer.MAX_VALUE || positions.size() != (int) expectedCount) {
      throw new ArchiveException("archive in-flight position count does not match block range");
    }
    Map<Long, ArchiveTxPosition> byTxNum = new HashMap<>();
    Set<Integer> userIndexes = new HashSet<>();
    Set<ByteArrayKey> userTxIds = new HashSet<>();
    boolean sawPrepare = false;
    boolean sawFinalize = false;
    int userCount = 0;
    for (ArchiveTxPosition position : positions) {
      validatePosition(range, position);
      if (byTxNum.put(position.getTxNum(), position) != null) {
        throw new ArchiveException("archive in-flight duplicate position txNum "
            + position.getTxNum());
      }
      switch (position.getPhase()) {
        case BLOCK_PREPARE:
          if (position.getTxNum() != range.getPrepareTxNum()) {
            throw new ArchiveException("archive in-flight prepare position txNum mismatch");
          }
          sawPrepare = true;
          break;
        case BLOCK_FINALIZE:
          if (position.getTxNum() != range.getFinalizeTxNum()) {
            throw new ArchiveException("archive in-flight finalize position txNum mismatch");
          }
          sawFinalize = true;
          break;
        case USER_TX:
          userCount++;
          if (!userIndexes.add(position.getTxIndex())) {
            throw new ArchiveException("archive in-flight duplicate user tx index "
                + position.getTxIndex());
          }
          if (!userTxIds.add(new ByteArrayKey(position.getTxId()))) {
            throw new ArchiveException("archive in-flight duplicate user txId");
          }
          break;
        default:
          throw new ArchiveException("archive in-flight position phase is not publishable");
      }
    }
    if (!sawPrepare || !sawFinalize || userCount != range.getUserTxCount()) {
      throw new ArchiveException("archive in-flight positions do not match block range");
    }
    return byTxNum;
  }

  private static void validatePosition(ArchiveBlockRange range, ArchiveTxPosition position) {
    if (position == null) {
      throw new ArchiveException("archive in-flight position is missing");
    }
    if (position.getTxNum() < range.getFirstTxNum()
        || position.getTxNum() > range.getLastTxNum()
        || position.getBlockNum() != range.getBlockNum()
        || position.getSource() != range.getSource()) {
      throw new ArchiveException("archive in-flight position does not match block range");
    }
    byte[] positionBlockHash = position.getBlockHash();
    if (positionBlockHash.length != 0
        && !Arrays.equals(positionBlockHash, range.getBlockHash())) {
      throw new ArchiveException("archive in-flight position block hash mismatch");
    }
    if (position.getPhase() == ArchivePhase.USER_TX) {
      if (position.getTxIndex() < 0 || position.getTxIndex() >= range.getUserTxCount()) {
        throw new ArchiveException("archive in-flight user tx index is outside block range");
      }
      requireLength(position.getTxId(), ArchiveBlockRange.BLOCK_HASH_LENGTH,
          "archive in-flight user txId");
      long expectedTxNum = range.getFirstTxNum() + 1L + position.getTxIndex();
      if (position.getTxNum() != expectedTxNum) {
        throw new ArchiveException("archive in-flight user tx-position order mismatch");
      }
      return;
    }
    if (position.getTxIndex() != -1 || position.getTxId().length != 0) {
      throw new ArchiveException("archive in-flight system position is invalid");
    }
  }

  private static void validateRecords(ArchiveBlockRange range,
      Map<Long, ArchiveTxPosition> positions, List<ArchiveChangeRecord> records,
      ArchiveDomainCatalog catalog, DynamicKeyPolicy dynamicKeyPolicy) {
    if (records == null) {
      throw new ArchiveException("archive in-flight records are missing");
    }
    Set<ChangeKey> changes = new HashSet<>();
    for (ArchiveChangeRecord record : records) {
      if (record == null || record.getPosition() == null || record.getDomain() == null) {
        throw new ArchiveException("archive in-flight record is invalid");
      }
      validatePosition(range, record.getPosition());
      ArchiveTxPosition persisted = positions.get(record.getTxNum());
      if (!samePosition(persisted, record.getPosition())) {
        throw new ArchiveException(
            "archive in-flight record position does not match persisted position");
      }
      if (!changes.add(new ChangeKey(record))) {
        throw new ArchiveException("archive in-flight duplicate changeset row");
      }
      ArchiveDomainDescriptor descriptor = catalog.descriptorFor(record.getDomain());
      if (descriptor == null) {
        throw new ArchiveException("archive in-flight record has unknown domain "
            + record.getDomain());
      }
      descriptor.getKeyCodec().validate(record.getCanonicalKey());
      validateKeyPolicy(record.getDomain(), record.getCanonicalKey(), dynamicKeyPolicy);
      descriptor.getValueCodec().validate(record.getPrevValue());
      descriptor.getValueCodec().validate(record.getValue());
    }
  }

  private static void validateKeyPolicy(ArchiveDomain domain, byte[] canonicalKey,
      DynamicKeyPolicy dynamicKeyPolicy) {
    if (domain == ArchiveDomain.DYNAMIC_PROPERTIES
        && dynamicKeyPolicy.decision(canonicalKey).getHistoryPolicy() == HistoryPolicy.NO_ARCHIVE) {
      throw new ArchiveException("archive in-flight dynamic property is not archived");
    }
  }

  private static boolean samePosition(ArchiveTxPosition left, ArchiveTxPosition right) {
    return left != null
        && right != null
        && left.getTxNum() == right.getTxNum()
        && left.getBlockNum() == right.getBlockNum()
        && left.getPhase() == right.getPhase()
        && left.getSource() == right.getSource()
        && left.getTxIndex() == right.getTxIndex()
        && Arrays.equals(left.getTxId(), right.getTxId())
        && Arrays.equals(left.getBlockHash(), right.getBlockHash());
  }

  private static void requireLength(byte[] value, int length, String what) {
    if (value == null || value.length != length) {
      throw new ArchiveException(what + " must be " + length + " bytes");
    }
  }

  private static final class ByteArrayKey {

    private final byte[] bytes;

    private ByteArrayKey(byte[] bytes) {
      this.bytes = Arrays.copyOf(bytes, bytes.length);
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (!(obj instanceof ByteArrayKey)) {
        return false;
      }
      return Arrays.equals(bytes, ((ByteArrayKey) obj).bytes);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(bytes);
    }
  }

  private static final class ChangeKey {

    private final long txNum;
    private final ArchiveDomain domain;
    private final ByteArrayKey canonicalKey;

    private ChangeKey(ArchiveChangeRecord record) {
      this.txNum = record.getTxNum();
      this.domain = record.getDomain();
      this.canonicalKey = new ByteArrayKey(record.getCanonicalKey());
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (!(obj instanceof ChangeKey)) {
        return false;
      }
      ChangeKey other = (ChangeKey) obj;
      return txNum == other.txNum
          && domain == other.domain
          && canonicalKey.equals(other.canonicalKey);
    }

    @Override
    public int hashCode() {
      int result = Long.hashCode(txNum);
      result = 31 * result + domain.hashCode();
      result = 31 * result + canonicalKey.hashCode();
      return result;
    }
  }

  @Override
  public void deleteBlock(long blockNum) {
    try (WriteBatch batch = new WriteBatch();
         WriteOptions writeOptions = ArchiveRocksDbWriteOptions.createForcedSync()) {
      batch.delete(ArchiveInFlightCodec.blockKey(blockNum));
      batch.delete(ArchiveInFlightCodec.acknowledgementKey(blockNum));
      batch.delete(ArchiveInFlightCodec.tokenKey(blockNum));
      db.write(writeOptions, batch);
    } catch (RocksDBException e) {
      throw new ArchiveException("archive in-flight delete failed", e);
    }
  }

  @Override
  public long usableSpaceBytes() {
    try {
      return Files.getFileStore(dbPath).getUsableSpace();
    } catch (IOException e) {
      throw new ArchiveException("archive in-flight filesystem capacity read failed", e);
    }
  }

  @Override
  public void close() {
    db.close();
    options.close();
  }
}
