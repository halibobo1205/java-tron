package org.tron.core.archive;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    List<ArchiveInFlightBlock> blocks = new ArrayList<>();
    Map<Long, ArchiveJournalToken> tokens = new HashMap<>();
    Map<Long, ArchiveJournalToken> acknowledgements = new HashMap<>();
    byte[] blockPrefix = ArchiveInFlightCodec.blockPrefix();
    byte[] tokenPrefix = ArchiveInFlightCodec.tokenPrefix();
    byte[] acknowledgementPrefix = ArchiveInFlightCodec.acknowledgementPrefix();
    try (UnifiedArchiveReadView view = db.openReadView()) {
      RocksIterator iterator = view.newIterator(UnifiedArchiveColumnFamily.INFLIGHT);
      iterator.seekToFirst();
      while (iterator.isValid()) {
        byte[] key = iterator.key();
        if (ArchiveInFlightCodec.startsWith(key, blockPrefix)) {
          long blockNum = ArchiveInFlightCodec.blockNumOfKey(key);
          ArchiveInFlightBlock block = ArchiveInFlightCodec.decodeBlock(iterator.value());
          ArchiveInFlightValidator.validate(block, catalog, dynamicKeyPolicy);
          if (block.getRange().getBlockNum() != blockNum) {
            throw new ArchiveException("archive in-flight block key/value mismatch for block "
                + blockNum);
          }
          if (block.getJournalState() != ArchiveInFlightBlock.JournalState.JOURNALED) {
            throw new ArchiveException("UNIFIED_V1 journal payload is not immutable JOURNALED "
                + "state for block " + blockNum);
          }
          blocks.add(block);
        } else if (ArchiveInFlightCodec.startsWith(key, tokenPrefix)) {
          putToken(tokens, ArchiveInFlightCodec.blockNumOfTokenKey(key),
              ArchiveInFlightCodec.decodeAcknowledgement(iterator.value()), "token");
        } else if (ArchiveInFlightCodec.startsWith(key, acknowledgementPrefix)) {
          putToken(acknowledgements,
              ArchiveInFlightCodec.blockNumOfAcknowledgementKey(key),
              ArchiveInFlightCodec.decodeAcknowledgement(iterator.value()), "acknowledgement");
        } else {
          throw new ArchiveException("UNIFIED_V1 in-flight store has an unknown key");
        }
        iterator.next();
      }
      ArchiveRocksIterators.requireOk(iterator, "loadBlocks: scan UNIFIED_V1 journal");
    }
    List<ArchiveInFlightBlock> validatedBlocks = new ArrayList<>(blocks.size());
    for (ArchiveInFlightBlock block : blocks) {
      long blockNum = block.getRange().getBlockNum();
      ArchiveJournalToken token = tokens.remove(blockNum);
      if (token == null || !token.equals(block.getJournalToken())) {
        throw new ArchiveException("UNIFIED_V1 journal token mismatch for block " + blockNum);
      }
      ArchiveJournalToken acknowledgement = acknowledgements.remove(blockNum);
      if (acknowledgement != null) {
        if (!acknowledgement.equals(token)) {
          throw new ArchiveException(
              "UNIFIED_V1 acknowledgement token mismatch for block " + blockNum);
        }
        block = block.withJournalState(
            ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED);
      }
      validatedBlocks.add(block);
    }
    if (!tokens.isEmpty() || !acknowledgements.isEmpty()) {
      throw new ArchiveException("UNIFIED_V1 in-flight store has an orphan lifecycle row");
    }
    validatedBlocks.forEach(consumer);
  }

  private static void putToken(Map<Long, ArchiveJournalToken> destination, long blockNum,
      ArchiveJournalToken token, String kind) {
    if (token.getBlockNum() != blockNum || destination.put(blockNum, token) != null) {
      throw new ArchiveException("UNIFIED_V1 journal " + kind
          + " key/value mismatch for block " + blockNum);
    }
  }

  @Override
  public void putBlock(ArchiveInFlightBlock block) {
    ArchiveInFlightValidator.validate(block, catalog, dynamicKeyPolicy);
    long blockNum = block.getRange().getBlockNum();
    ArchiveInFlightBlock journaled = block.getJournalState()
        == ArchiveInFlightBlock.JournalState.JOURNALED
        ? block : block.withJournalState(ArchiveInFlightBlock.JournalState.JOURNALED);
    byte[] value = ArchiveInFlightCodec.encodeBlock(journaled);
    byte[] token = ArchiveInFlightCodec.encodeAcknowledgement(block.getJournalToken());
    byte[] acknowledgement = block.getJournalState()
        == ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED ? token : null;
    long startedNanos = ArchiveMetrics.startTimer();
    db.putJournalBlockDurably(
        ArchiveInFlightCodec.blockKey(blockNum), value,
        ArchiveInFlightCodec.tokenKey(blockNum), token,
        ArchiveInFlightCodec.acknowledgementKey(blockNum), acknowledgement);
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
    byte[] acknowledgement = ArchiveInFlightCodec.encodeAcknowledgement(token);
    long startedNanos = ArchiveMetrics.startTimer();
    db.acknowledgeJournalWalOnly(
        ArchiveInFlightCodec.blockKey(blockNum),
        ArchiveInFlightCodec.tokenKey(blockNum), acknowledgement,
        ArchiveInFlightCodec.acknowledgementKey(blockNum), acknowledgement);
    ArchiveMetrics.journalAcknowledged(acknowledgement.length, startedNanos);
  }

  @Override
  public void deleteBlock(long blockNum) {
    byte[] journalKey = ArchiveInFlightCodec.blockKey(blockNum);
    byte[] tokenKey = ArchiveInFlightCodec.tokenKey(blockNum);
    byte[] acknowledgementKey = ArchiveInFlightCodec.acknowledgementKey(blockNum);
    byte[] journal = db.get(UnifiedArchiveColumnFamily.INFLIGHT, journalKey);
    byte[] token = db.get(UnifiedArchiveColumnFamily.INFLIGHT, tokenKey);
    byte[] acknowledgement = db.get(UnifiedArchiveColumnFamily.INFLIGHT, acknowledgementKey);
    if (journal == null) {
      if (token != null || acknowledgement != null) {
        throw new ArchiveException("UNIFIED_V1 journal lifecycle row is orphaned for block "
            + blockNum);
      }
      return;
    }
    ArchiveInFlightBlock decoded = ArchiveInFlightCodec.decodeBlock(journal);
    ArchiveInFlightValidator.validate(decoded, catalog, dynamicKeyPolicy);
    if (token == null) {
      throw new ArchiveException("UNIFIED_V1 journal token is missing for block " + blockNum);
    }
    ArchiveJournalToken decodedToken = ArchiveInFlightCodec.decodeAcknowledgement(token);
    if (decoded.getRange().getBlockNum() != blockNum
        || !decodedToken.equals(decoded.getJournalToken())) {
      throw new ArchiveException("UNIFIED_V1 journal bundle mismatch for block " + blockNum);
    }
    if (acknowledgement != null
        && !ArchiveInFlightCodec.decodeAcknowledgement(acknowledgement).equals(decodedToken)) {
      throw new ArchiveException("UNIFIED_V1 acknowledgement mismatch for block " + blockNum);
    }
    db.deleteJournalBlockDurably(
        journalKey, journal, tokenKey, token, acknowledgementKey, acknowledgement);
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
