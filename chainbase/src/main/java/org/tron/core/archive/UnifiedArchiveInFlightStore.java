package org.tron.core.archive;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.rocksdb.RocksIterator;
import org.tron.core.archive.domain.ArchiveDomainCatalog;
import org.tron.core.archive.domain.DynamicKeyPolicy;
import org.tron.core.archive.unified.UnifiedArchiveColumnFamily;
import org.tron.core.archive.unified.UnifiedArchiveDb;
import org.tron.core.archive.unified.UnifiedArchiveReadView;

/** Durable in-flight journal adapter over the UNIFIED_V1 INFLIGHT column family. */
public final class UnifiedArchiveInFlightStore implements ArchiveInFlightStore {

  private final UnifiedArchiveDb db;
  private final ArchiveDomainCatalog catalog;
  private final DynamicKeyPolicy dynamicKeyPolicy = new DynamicKeyPolicy();

  public UnifiedArchiveInFlightStore(UnifiedArchiveDb db, ArchiveDomainCatalog catalog) {
    if (db == null) {
      throw new NullPointerException("db");
    }
    if (catalog == null) {
      throw new NullPointerException("catalog");
    }
    this.db = db;
    this.catalog = catalog;
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
    try (UnifiedArchiveReadView view = db.openReadView()) {
      RocksIterator iterator = view.newIterator(UnifiedArchiveColumnFamily.INFLIGHT);
      iterator.seekToFirst();
      while (iterator.isValid()) {
        if (!ArchiveInFlightCodec.startsWith(iterator.key(), prefix)) {
          throw new ArchiveException("UNIFIED_V1 in-flight store has an unknown key");
        }
        long blockNum = ArchiveInFlightCodec.blockNumOfKey(iterator.key());
        ArchiveInFlightBlock block = ArchiveInFlightCodec.decodeBlock(iterator.value());
        RocksDbArchiveInFlightStore.validateBlock(block, catalog, dynamicKeyPolicy);
        if (block.getRange().getBlockNum() != blockNum) {
          throw new ArchiveException("archive in-flight block key/value mismatch for block "
              + blockNum);
        }
        consumer.accept(block);
        iterator.next();
      }
      ArchiveRocksIterators.requireOk(iterator, "loadBlocks: scan UNIFIED_V1 journal");
    }
  }

  @Override
  public void putBlock(ArchiveInFlightBlock block) {
    RocksDbArchiveInFlightStore.validateBlock(block, catalog, dynamicKeyPolicy);
    long blockNum = block.getRange().getBlockNum();
    byte[] value = ArchiveInFlightCodec.encodeBlock(block);
    long startedNanos = ArchiveMetrics.startTimer();
    db.putJournalDurably(ArchiveInFlightCodec.blockKey(blockNum), value);
    ArchiveMetrics.journalWritten(value.length, startedNanos);
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
    byte[] key = ArchiveInFlightCodec.blockKey(blockNum);
    byte[] current = db.get(UnifiedArchiveColumnFamily.INFLIGHT, key);
    if (current == null) {
      throw new ArchiveException("archive acknowledgement has no journal block " + blockNum);
    }
    ArchiveInFlightBlock block = ArchiveInFlightCodec.decodeBlock(current);
    RocksDbArchiveInFlightStore.validateBlock(block, catalog, dynamicKeyPolicy);
    if (!token.equals(block.getJournalToken())) {
      throw new ArchiveException("archive acknowledgement token mismatch for block " + blockNum);
    }
    if (block.getJournalState() == ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED) {
      return;
    }
    byte[] acknowledged = ArchiveInFlightCodec.encodeBlock(block.withJournalState(
        ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED));
    long startedNanos = ArchiveMetrics.startTimer();
    db.replaceJournalDurably(key, current, acknowledged);
    ArchiveMetrics.journalAcknowledged(acknowledged.length, startedNanos);
  }

  @Override
  public void deleteBlock(long blockNum) {
    byte[] key = ArchiveInFlightCodec.blockKey(blockNum);
    byte[] current = db.get(UnifiedArchiveColumnFamily.INFLIGHT, key);
    if (current != null) {
      db.deleteJournalDurably(key, current);
    }
  }

  @Override
  public long usableSpaceBytes() {
    try {
      return Files.getFileStore(db.getPath()).getUsableSpace();
    } catch (IOException e) {
      throw new ArchiveException("archive in-flight filesystem capacity read failed", e);
    }
  }

  @Override
  public void close() {
    // UnifiedArchiveTxNumIndex owns the shared DB and closes it last.
  }
}
