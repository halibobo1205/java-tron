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
    try (UnifiedArchiveReadView view = db.openScanView()) {
      validateKnownKeys(view);
      scanAlignedBlocks(view, null, true);
      scanAlignedBlocks(view, consumer, false);
    }
  }

  private void validateKnownKeys(UnifiedArchiveReadView view) {
    byte[] blockPrefix = ArchiveInFlightCodec.blockPrefix();
    byte[] tokenPrefix = ArchiveInFlightCodec.tokenPrefix();
    byte[] acknowledgementPrefix = ArchiveInFlightCodec.acknowledgementPrefix();
    RocksIterator iterator = view.newIterator(UnifiedArchiveColumnFamily.INFLIGHT);
    iterator.seekToFirst();
    while (iterator.isValid()) {
      byte[] key = iterator.key();
      if (ArchiveInFlightCodec.startsWith(key, blockPrefix)) {
        ArchiveInFlightCodec.blockNumOfKey(key);
      } else if (ArchiveInFlightCodec.startsWith(key, tokenPrefix)) {
        ArchiveInFlightCodec.blockNumOfTokenKey(key);
      } else if (ArchiveInFlightCodec.startsWith(key, acknowledgementPrefix)) {
        ArchiveInFlightCodec.blockNumOfAcknowledgementKey(key);
      } else {
        throw new ArchiveException("UNIFIED_V1 in-flight store has an unknown key");
      }
      iterator.next();
    }
    ArchiveRocksIterators.requireOk(iterator, "loadBlocks: classify UNIFIED_V1 journal keys");
  }

  private void scanAlignedBlocks(UnifiedArchiveReadView view,
      Consumer<ArchiveInFlightBlock> consumer, boolean validatePayload) {
    byte[] blockPrefix = ArchiveInFlightCodec.blockPrefix();
    byte[] tokenPrefix = ArchiveInFlightCodec.tokenPrefix();
    byte[] acknowledgementPrefix = ArchiveInFlightCodec.acknowledgementPrefix();
    RocksIterator blocks = view.newIterator(UnifiedArchiveColumnFamily.INFLIGHT);
    RocksIterator tokens = view.newIterator(UnifiedArchiveColumnFamily.INFLIGHT);
    RocksIterator acknowledgements = view.newIterator(UnifiedArchiveColumnFamily.INFLIGHT);
    blocks.seek(blockPrefix);
    tokens.seek(tokenPrefix);
    acknowledgements.seek(acknowledgementPrefix);
    while (hasPrefix(blocks, blockPrefix)) {
      long blockNum = ArchiveInFlightCodec.blockNumOfKey(blocks.key());
      ArchiveInFlightBlock block = ArchiveInFlightCodec.decodeBlock(blocks.value());
      if (validatePayload) {
        validateJournalPayload(blockNum, block);
      }
      ArchiveJournalToken token = requireLifecycleToken(tokens, tokenPrefix, blockNum, "token");
      if (!token.equals(block.getJournalToken())) {
        throw new ArchiveException("UNIFIED_V1 journal token mismatch for block " + blockNum);
      }
      ArchiveJournalToken acknowledgement = optionalAcknowledgement(
          acknowledgements, acknowledgementPrefix, blockNum);
      if (acknowledgement != null && !acknowledgement.equals(token)) {
        throw new ArchiveException(
            "UNIFIED_V1 acknowledgement token mismatch for block " + blockNum);
      }
      if (consumer != null) {
        if (acknowledgement != null) {
          block = block.withJournalState(
              ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED);
        }
        consumer.accept(block);
      }
      blocks.next();
      tokens.next();
      if (acknowledgement != null) {
        acknowledgements.next();
      }
    }
    ArchiveRocksIterators.requireOk(blocks, "loadBlocks: scan UNIFIED_V1 payloads");
    ArchiveRocksIterators.requireOk(tokens, "loadBlocks: scan UNIFIED_V1 tokens");
    ArchiveRocksIterators.requireOk(
        acknowledgements, "loadBlocks: scan UNIFIED_V1 acknowledgements");
    if (hasPrefix(tokens, tokenPrefix) || hasPrefix(acknowledgements, acknowledgementPrefix)) {
      throw new ArchiveException("UNIFIED_V1 in-flight store has an orphan lifecycle row");
    }
  }

  private void validateJournalPayload(long blockNum, ArchiveInFlightBlock block) {
    ArchiveInFlightValidator.validate(block, catalog, dynamicKeyPolicy);
    if (block.getRange().getBlockNum() != blockNum) {
      throw new ArchiveException("archive in-flight block key/value mismatch for block "
          + blockNum);
    }
    if (block.getJournalState() != ArchiveInFlightBlock.JournalState.JOURNALED) {
      throw new ArchiveException("UNIFIED_V1 journal payload is not immutable JOURNALED "
          + "state for block " + blockNum);
    }
  }

  private static ArchiveJournalToken requireLifecycleToken(RocksIterator iterator,
      byte[] prefix, long blockNum, String kind) {
    if (!hasPrefix(iterator, prefix)) {
      throw new ArchiveException("UNIFIED_V1 journal " + kind
          + " is missing for block " + blockNum);
    }
    long lifecycleBlockNum = ArchiveInFlightCodec.blockNumOfTokenKey(iterator.key());
    if (lifecycleBlockNum != blockNum) {
      throw new ArchiveException("UNIFIED_V1 journal " + kind
          + " key/value mismatch for block " + blockNum);
    }
    ArchiveJournalToken token = ArchiveInFlightCodec.decodeAcknowledgement(iterator.value());
    if (token.getBlockNum() != blockNum) {
      throw new ArchiveException("UNIFIED_V1 journal " + kind
          + " key/value mismatch for block " + blockNum);
    }
    return token;
  }

  private static ArchiveJournalToken optionalAcknowledgement(RocksIterator iterator,
      byte[] prefix, long blockNum) {
    if (!hasPrefix(iterator, prefix)) {
      return null;
    }
    long acknowledgementBlockNum =
        ArchiveInFlightCodec.blockNumOfAcknowledgementKey(iterator.key());
    if (acknowledgementBlockNum < blockNum) {
      throw new ArchiveException("UNIFIED_V1 in-flight store has an orphan lifecycle row");
    }
    if (acknowledgementBlockNum > blockNum) {
      return null;
    }
    ArchiveJournalToken acknowledgement =
        ArchiveInFlightCodec.decodeAcknowledgement(iterator.value());
    if (acknowledgement.getBlockNum() != blockNum) {
      throw new ArchiveException(
          "UNIFIED_V1 journal acknowledgement key/value mismatch for block " + blockNum);
    }
    return acknowledgement;
  }

  private static boolean hasPrefix(RocksIterator iterator, byte[] prefix) {
    return iterator.isValid() && ArchiveInFlightCodec.startsWith(iterator.key(), prefix);
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
