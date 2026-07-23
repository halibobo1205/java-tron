package org.tron.core.archive;

import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.tron.core.archive.domain.ArchiveDomainCatalog;
import org.tron.core.archive.domain.DynamicKeyPolicy;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.archive.unified.UnifiedArchiveColumnFamily;
import org.tron.core.archive.unified.UnifiedArchiveDb;
import org.tron.core.archive.unified.UnifiedArchiveIterator;
import org.tron.core.archive.unified.UnifiedArchiveReadView;

/** Durable in-flight journal adapter over the UNIFIED_V1 INFLIGHT column family. */
public final class UnifiedArchiveInFlightStore implements ArchiveInFlightStore {

  private final UnifiedArchiveDb db;
  private final ArchiveDomainCatalog catalog;
  private final long maxEncodedBlockBytes;
  private final long maxRecordsPerBlock;
  private final int maxBlocks;
  private final byte[] schemaChecksum;
  private final UnifiedArchiveDb.ProductionWritePermit writePermit;
  private final DynamicKeyPolicy dynamicKeyPolicy = new DynamicKeyPolicy();
  private final Object journalLock = new Object();
  private final Map<Long, ArchiveJournalProof> validatedProofs = new HashMap<>();

  UnifiedArchiveInFlightStore(UnifiedArchiveDb db, ArchiveDomainCatalog catalog) {
    this(db, catalog, Long.MAX_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE);
  }

  UnifiedArchiveInFlightStore(UnifiedArchiveDb db, ArchiveDomainCatalog catalog,
      long maxEncodedBlockBytes) {
    this(db, catalog, maxEncodedBlockBytes, Long.MAX_VALUE, Integer.MAX_VALUE);
  }

  UnifiedArchiveInFlightStore(UnifiedArchiveDb db, ArchiveDomainCatalog catalog,
      long maxEncodedBlockBytes, long maxRecordsPerBlock) {
    this(db, catalog, maxEncodedBlockBytes, maxRecordsPerBlock, Integer.MAX_VALUE);
  }

  public UnifiedArchiveInFlightStore(UnifiedArchiveDb db, ArchiveDomainCatalog catalog,
      long maxEncodedBlockBytes, long maxRecordsPerBlock, int maxBlocks) {
    this(db, catalog, maxEncodedBlockBytes, maxRecordsPerBlock, maxBlocks, null);
  }

  public UnifiedArchiveInFlightStore(UnifiedArchiveDb db, ArchiveDomainCatalog catalog,
      long maxEncodedBlockBytes, long maxRecordsPerBlock, int maxBlocks,
      UnifiedArchiveDb.ProductionWritePermit writePermit) {
    if (db == null) {
      throw new NullPointerException("db");
    }
    if (catalog == null) {
      throw new NullPointerException("catalog");
    }
    if (maxEncodedBlockBytes <= 0L) {
      throw new IllegalArgumentException("archive journal encoded byte limit must be positive");
    }
    if (maxRecordsPerBlock <= 0L) {
      throw new IllegalArgumentException("archive journal record limit must be positive");
    }
    if (maxBlocks <= 0) {
      throw new IllegalArgumentException("archive journal block limit must be positive");
    }
    this.db = db;
    this.catalog = catalog;
    this.maxEncodedBlockBytes = maxEncodedBlockBytes;
    this.maxRecordsPerBlock = maxRecordsPerBlock;
    this.maxBlocks = maxBlocks;
    this.schemaChecksum = db.getSchemaChecksum();
    this.writePermit = writePermit;
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
    List<ArchiveInFlightBlock> validated;
    synchronized (journalLock) {
      validated = scanValidatedBlocksLocked();
    }
    for (ArchiveInFlightBlock block : validated) {
      consumer.accept(block);
    }
  }

  private List<ArchiveInFlightBlock> scanValidatedBlocksLocked() {
    Map<Long, ArchiveJournalProof> scannedProofs = new HashMap<>();
    List<ArchiveInFlightBlock> validated;
    try {
      try (UnifiedArchiveReadView view = db.openScanView()) {
        validateKnownKeys(view);
        validated = scanAlignedBlocks(view, scannedProofs);
      }
    } catch (RuntimeException | Error e) {
      validatedProofs.clear();
      throw e;
    }
    validatedProofs.clear();
    validatedProofs.putAll(scannedProofs);
    return validated;
  }

  private void validateKnownKeys(UnifiedArchiveReadView view) {
    byte[] blockPrefix = ArchiveInFlightCodec.blockPrefix();
    byte[] tokenPrefix = ArchiveInFlightCodec.tokenPrefix();
    byte[] acknowledgementPrefix = ArchiveInFlightCodec.acknowledgementPrefix();
    try {
      UnifiedArchiveIterator iterator = view.newIterator(UnifiedArchiveColumnFamily.INFLIGHT);
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
          throw corruption("UNIFIED_V1 in-flight store has an unknown key");
        }
        iterator.next();
      }
      ArchiveRocksIterators.requireOk(iterator, "loadBlocks: classify UNIFIED_V1 journal keys");
    } catch (ArchiveJournalCorruptionException e) {
      throw e;
    } catch (ArchiveException e) {
      throw corruption("UNIFIED_V1 in-flight store key scan is invalid", e);
    }
  }

  private List<ArchiveInFlightBlock> scanAlignedBlocks(UnifiedArchiveReadView view,
      Map<Long, ArchiveJournalProof> scannedProofs) {
    byte[] blockPrefix = ArchiveInFlightCodec.blockPrefix();
    byte[] tokenPrefix = ArchiveInFlightCodec.tokenPrefix();
    byte[] acknowledgementPrefix = ArchiveInFlightCodec.acknowledgementPrefix();
    List<ArchiveInFlightBlock> validated = new ArrayList<>();
    long retainedBytes = 0L;
    long retainedRecords = 0L;
    UnifiedArchiveIterator blocks = view.newIterator(UnifiedArchiveColumnFamily.INFLIGHT);
    UnifiedArchiveIterator tokens = view.newIterator(UnifiedArchiveColumnFamily.INFLIGHT);
    UnifiedArchiveIterator acknowledgements =
        view.newIterator(UnifiedArchiveColumnFamily.INFLIGHT);
    blocks.seek(blockPrefix);
    tokens.seek(tokenPrefix);
    acknowledgements.seek(acknowledgementPrefix);
    while (hasPrefix(blocks, blockPrefix)) {
      long blockNum = ArchiveInFlightCodec.blockNumOfKey(blocks.key());
      if (validated.size() >= maxBlocks) {
        throw new ArchiveJournalLimitException(
            "archive journal block count exceeds configured limit "
            + maxBlocks);
      }
      long remainingBytes = maxEncodedBlockBytes - retainedBytes;
      long remainingRecords = maxRecordsPerBlock - retainedRecords;
      byte[] journalKey = blocks.key();
      ArchiveJournalProof proof;
      byte[] payload;
      ArchiveInFlightBlock block;
      ArchiveJournalProof acknowledgement;
      try {
        proof = requireLifecycleProof(view, tokens, tokenPrefix, blockNum, "proof");
        requireCurrentProofIdentity(blockNum, proof);
        if (proof.getPayloadLength() > remainingBytes) {
          throw new ArchiveJournalLimitException(
              "archive journal encoded bytes exceed configured limit "
                  + remainingBytes + ": encodedBytes=" + proof.getPayloadLength());
        }
        long nativeReadPeak = addSaturated(
            proof.getPayloadLength(), proof.getPayloadLength());
        if (nativeReadPeak > remainingBytes) {
          throw new ArchiveJournalLimitException(
              "archive journal Java/native read peak exceeds configured limit "
                  + remainingBytes + ": readPeakBytes=" + nativeReadPeak);
        }
        payload = readProofBoundBlockValue(view, journalKey, proof, blockNum);
        proof.requireMatches(journalKey, payload);
        long decodeRetainedLimit = remainingBytes - payload.length;
        block = ArchiveInFlightCodec.decodeBlock(
            payload, remainingRecords, decodeRetainedLimit);
        validateJournalEnvelope(blockNum, block);
        if (!proof.getToken().equals(block.getJournalToken())) {
          throw corruption("UNIFIED_V1 journal proof token mismatch for block " + blockNum);
        }
        requireCurrentSchema(blockNum, proof, block);
        acknowledgement = optionalAcknowledgement(
            view, acknowledgements, acknowledgementPrefix, blockNum);
        if (acknowledgement != null && !acknowledgement.equals(proof)) {
          throw corruption("UNIFIED_V1 acknowledgement proof mismatch for block " + blockNum);
        }
        ArchiveInFlightValidator.validateProofBound(block, catalog, dynamicKeyPolicy);
      } catch (ArchiveJournalLimitException | ArchiveJournalCorruptionException e) {
        throw e;
      } catch (ArchiveException e) {
        throw corruptionWithCause(
            "UNIFIED_V1 journal bundle is invalid for block " + blockNum, e);
      }
      if (acknowledgement != null) {
        block = block.withJournalState(
            ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED);
      }
      retainedBytes = addSaturated(retainedBytes, block.estimatedRetainedBytes());
      retainedRecords = addSaturated(retainedRecords, block.getRecords().size());
      if (retainedBytes > maxEncodedBlockBytes) {
        throw new ArchiveJournalLimitException(
            "archive journal retained bytes exceed configured limit "
            + maxEncodedBlockBytes + ": retainedBytes=" + retainedBytes);
      }
      if (retainedRecords > maxRecordsPerBlock) {
        throw new ArchiveJournalLimitException(
            "archive journal record count exceeds configured limit "
            + maxRecordsPerBlock + ": retainedRecords=" + retainedRecords);
      }
      validated.add(block);
      scannedProofs.put(blockNum, proof);
      payload = null;
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
      throw corruption("UNIFIED_V1 in-flight store has an orphan lifecycle row");
    }
    return validated;
  }

  private static byte[] readProofBoundBlockValue(UnifiedArchiveReadView view,
      byte[] journalKey, ArchiveJournalProof proof, long blockNum) {
    try {
      byte[] payload = view.getPreaccountedExact(
          UnifiedArchiveColumnFamily.INFLIGHT, journalKey, proof.getPayloadLength(),
          "archive journal payload for block " + blockNum);
      if (payload == null) {
        throw corruption("UNIFIED_V1 journal payload is missing for block " + blockNum);
      }
      return payload;
    } catch (ArchiveJournalCorruptionException e) {
      throw e;
    } catch (ArchiveException e) {
      throw corruptionWithCause(
          "UNIFIED_V1 journal payload is invalid for block " + blockNum, e);
    }
  }

  private void validateJournalEnvelope(long blockNum, ArchiveInFlightBlock block) {
    if (block.getRange().getBlockNum() != blockNum) {
      throw new ArchiveException("archive in-flight block key/value mismatch for block "
          + blockNum);
    }
    if (block.getJournalState() != ArchiveInFlightBlock.JournalState.JOURNALED) {
      throw new ArchiveException("UNIFIED_V1 journal payload is not immutable JOURNALED "
          + "state for block " + blockNum);
    }
  }

  private void requireCurrentSchema(long blockNum, ArchiveJournalProof proof,
      ArchiveInFlightBlock block) {
    requireCurrentSchema(blockNum, proof.getToken(), block.getRange());
  }

  private void requireCurrentSchema(long blockNum, ArchiveJournalToken token,
      ArchiveBlockRange range) {
    if (!hasCurrentSchema(token, range)) {
      throw corruption("UNIFIED_V1 journal schema checksum mismatch for block " + blockNum);
    }
  }

  private boolean hasCurrentSchema(ArchiveJournalToken token, ArchiveBlockRange range) {
    return MessageDigest.isEqual(schemaChecksum, token.getSchemaChecksum())
        && MessageDigest.isEqual(schemaChecksum, range.getSchemaChecksum());
  }

  private static ArchiveJournalProof requireLifecycleProof(UnifiedArchiveReadView view,
      UnifiedArchiveIterator iterator, byte[] prefix, long blockNum, String kind) {
    try {
      if (!hasPrefix(iterator, prefix)) {
        throw corruption("UNIFIED_V1 journal " + kind + " is missing for block " + blockNum);
      }
      long lifecycleBlockNum = ArchiveInFlightCodec.blockNumOfTokenKey(iterator.key());
      if (lifecycleBlockNum != blockNum) {
        throw corruption("UNIFIED_V1 journal " + kind
            + " key/value mismatch for block " + blockNum);
      }
      byte[] value = view.getBounded(UnifiedArchiveColumnFamily.INFLIGHT, iterator.key(),
          ArchiveInFlightCodec.LIFECYCLE_VALUE_BYTES,
          "archive journal " + kind + " for block " + blockNum);
      ArchiveJournalProof proof = decodeProof(value, kind, blockNum);
      if (proof.getToken().getBlockNum() != blockNum) {
        throw corruption("UNIFIED_V1 journal " + kind
            + " key/value mismatch for block " + blockNum);
      }
      return proof;
    } catch (ArchiveJournalCorruptionException e) {
      throw e;
    } catch (ArchiveException e) {
      throw corruptionWithCause("UNIFIED_V1 journal " + kind
          + " is invalid for block " + blockNum, e);
    }
  }

  private static ArchiveJournalProof optionalAcknowledgement(UnifiedArchiveReadView view,
      UnifiedArchiveIterator iterator, byte[] prefix, long blockNum) {
    if (!hasPrefix(iterator, prefix)) {
      return null;
    }
    long acknowledgementBlockNum =
        ArchiveInFlightCodec.blockNumOfAcknowledgementKey(iterator.key());
    if (acknowledgementBlockNum < blockNum) {
      throw corruption("UNIFIED_V1 in-flight store has an orphan lifecycle row");
    }
    if (acknowledgementBlockNum > blockNum) {
      return null;
    }
    try {
      byte[] value = view.getBounded(UnifiedArchiveColumnFamily.INFLIGHT, iterator.key(),
          ArchiveInFlightCodec.LIFECYCLE_VALUE_BYTES,
          "archive journal acknowledgement for block " + blockNum);
      ArchiveJournalProof acknowledgement = decodeProof(value, "acknowledgement", blockNum);
      if (acknowledgement.getToken().getBlockNum() != blockNum) {
        throw corruption("UNIFIED_V1 journal acknowledgement key/value mismatch for block "
            + blockNum);
      }
      return acknowledgement;
    } catch (ArchiveJournalCorruptionException e) {
      throw e;
    } catch (ArchiveException e) {
      throw corruptionWithCause(
          "UNIFIED_V1 journal acknowledgement is invalid for block " + blockNum, e);
    }
  }

  private static ArchiveJournalProof decodeProof(byte[] value, String kind, long blockNum) {
    try {
      return ArchiveInFlightCodec.decodeProof(value);
    } catch (ArchiveException e) {
      throw corruptionWithCause(
          "UNIFIED_V1 journal " + kind + " is invalid for block " + blockNum, e);
    }
  }

  private static boolean hasPrefix(UnifiedArchiveIterator iterator, byte[] prefix) {
    return iterator.isValid() && ArchiveInFlightCodec.startsWith(iterator.key(), prefix);
  }

  private static long addSaturated(long left, long right) {
    return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
  }

  @Override
  public void putBlock(ArchiveInFlightBlock block) {
    synchronized (journalLock) {
      putBlockLocked(block);
    }
  }

  private void putBlockLocked(ArchiveInFlightBlock block) {
    requireBlockWithinLimits(block);
    ArchiveInFlightValidator.validateForWrite(block, catalog, dynamicKeyPolicy);
    if (!hasCurrentSchema(block.getJournalToken(), block.getRange())) {
      throw new ArchiveException("archive journal schema checksum does not match UNIFIED_V1 DB");
    }
    long blockNum = block.getRange().getBlockNum();
    ArchiveInFlightBlock journaled = block.getJournalState()
        == ArchiveInFlightBlock.JournalState.JOURNALED
        ? block : block.withJournalState(ArchiveInFlightBlock.JournalState.JOURNALED);
    byte[] journalKey = ArchiveInFlightCodec.blockKey(blockNum);
    byte[] value = ArchiveInFlightCodec.encodeBlock(journaled);
    if (value.length > maxEncodedBlockBytes) {
      throw new ArchiveJournalLimitException(
          "archive journal encoded bytes exceed configured limit "
          + maxEncodedBlockBytes + ": encodedBytes=" + value.length);
    }
    ArchiveJournalProof validatedProof =
        ArchiveJournalProof.create(block.getJournalToken(), journalKey, value);
    byte[] proof = ArchiveInFlightCodec.encodeProof(validatedProof);
    byte[] acknowledgement = block.getJournalState()
        == ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED ? proof : null;
    long startedNanos = ArchiveMetrics.startTimer();
    runPersistentMutation(() -> db.putJournalBlockDurably(
        journalKey, value,
        ArchiveInFlightCodec.tokenKey(blockNum), proof,
        ArchiveInFlightCodec.acknowledgementKey(blockNum), acknowledgement));
    validatedProofs.put(blockNum, validatedProof);
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
    synchronized (journalLock) {
      acknowledgeBlockLocked(token, true);
    }
  }

  @Override
  public void acknowledgeLoadedBlock(ArchiveInFlightBlock block) {
    if (block == null) {
      throw new NullPointerException("block");
    }
    if (block.getJournalState() != ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED) {
      throw new ArchiveException("archive acknowledgement requires canonical-committed state");
    }
    synchronized (journalLock) {
      acknowledgeBlockLocked(block.getJournalToken(), false);
    }
  }

  private void acknowledgeBlockLocked(ArchiveJournalToken token, boolean allowValidationScan) {
    long blockNum = token.getBlockNum();
    byte[] journalKey = ArchiveInFlightCodec.blockKey(blockNum);
    ArchiveJournalProof validatedProof = validatedProofs.get(blockNum);
    if (validatedProof == null && allowValidationScan) {
      scanValidatedBlocksLocked();
      validatedProof = validatedProofs.get(blockNum);
    }
    if (validatedProof == null) {
      throw new ArchiveException("archive acknowledgement has no journal block " + blockNum);
    }
    if (!token.equals(validatedProof.getToken())) {
      throw new ArchiveException("archive acknowledgement token mismatch for block " + blockNum);
    }
    if (validatedProof.getPayloadLength() > maxEncodedBlockBytes) {
      throw new ArchiveJournalLimitException(
          "archive journal encoded bytes exceed configured limit "
              + maxEncodedBlockBytes + ": encodedBytes="
              + validatedProof.getPayloadLength());
    }
    byte[] proof = ArchiveInFlightCodec.encodeProof(validatedProof);
    long startedNanos = ArchiveMetrics.startTimer();
    runPersistentMutation(() -> db.acknowledgeJournalWalOnly(
        journalKey, ArchiveInFlightCodec.tokenKey(blockNum), proof,
        ArchiveInFlightCodec.acknowledgementKey(blockNum), proof));
    ArchiveMetrics.journalAcknowledged(proof.length, startedNanos);
  }

  @Override
  public void deleteBlock(long blockNum) {
    synchronized (journalLock) {
      deleteBlockLocked(blockNum);
    }
  }

  @Override
  public void deleteLoadedBlock(ArchiveInFlightBlock block) {
    if (block == null) {
      throw new NullPointerException("block");
    }
    synchronized (journalLock) {
      deleteLoadedBlockLocked(block);
    }
  }

  private void deleteLoadedBlockLocked(ArchiveInFlightBlock block) {
    long blockNum = block.getRange().getBlockNum();
    ArchiveJournalProof expectedProof = validatedProofs.get(blockNum);
    if (expectedProof == null) {
      throw new ArchiveException(
          "archive validated journal proof is missing for loaded block " + blockNum);
    }
    if (!expectedProof.getToken().equals(block.getJournalToken())
        || expectedProof.getPayloadLength() != block.encodedBlockBytes()) {
      throw corruption("UNIFIED_V1 loaded journal proof mismatch for block " + blockNum);
    }
    byte[] journalKey = ArchiveInFlightCodec.blockKey(blockNum);
    byte[] tokenKey = ArchiveInFlightCodec.tokenKey(blockNum);
    byte[] acknowledgementKey = ArchiveInFlightCodec.acknowledgementKey(blockNum);
    byte[] token;
    byte[] acknowledgement;
    try (UnifiedArchiveReadView view = db.openScanView()) {
      token = view.getBounded(UnifiedArchiveColumnFamily.INFLIGHT, tokenKey,
          ArchiveInFlightCodec.LIFECYCLE_VALUE_BYTES,
          "archive loaded journal token for block " + blockNum);
      acknowledgement = view.getBounded(
          UnifiedArchiveColumnFamily.INFLIGHT, acknowledgementKey,
          ArchiveInFlightCodec.LIFECYCLE_VALUE_BYTES,
          "archive loaded journal acknowledgement for block " + blockNum);
    }
    if (token == null) {
      throw corruption("UNIFIED_V1 loaded journal bundle is missing for block " + blockNum);
    }
    ArchiveJournalProof decodedProof = decodeProof(token, "proof", blockNum);
    if (!decodedProof.equals(expectedProof)) {
      throw corruption("UNIFIED_V1 loaded journal proof changed for block " + blockNum);
    }
    boolean acknowledgementExpected = block.getJournalState()
        == ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED;
    if (acknowledgementExpected != (acknowledgement != null)) {
      throw corruption(
          "UNIFIED_V1 loaded journal acknowledgement state mismatch for block " + blockNum);
    }
    if (acknowledgement != null && !decodeProof(
        acknowledgement, "acknowledgement", blockNum).equals(expectedProof)) {
      throw corruption("UNIFIED_V1 acknowledgement mismatch for block " + blockNum);
    }
    runPersistentMutation(() -> db.deleteVerifiedJournalBlockDurably(
        journalKey, expectedProof, maxEncodedBlockBytes,
        tokenKey, token, acknowledgementKey, acknowledgement));
    validatedProofs.remove(blockNum);
  }

  private void deleteBlockLocked(long blockNum) {
    byte[] journalKey = ArchiveInFlightCodec.blockKey(blockNum);
    byte[] tokenKey = ArchiveInFlightCodec.tokenKey(blockNum);
    byte[] acknowledgementKey = ArchiveInFlightCodec.acknowledgementKey(blockNum);
    ArchiveJournalProof validatedProof = validatedProofs.get(blockNum);
    byte[] token;
    byte[] acknowledgement;
    try (UnifiedArchiveReadView view = db.openScanView()) {
      token = view.getBounded(UnifiedArchiveColumnFamily.INFLIGHT, tokenKey,
          ArchiveInFlightCodec.LIFECYCLE_VALUE_BYTES,
          "archive journal token for block " + blockNum);
      acknowledgement = view.getBounded(
          UnifiedArchiveColumnFamily.INFLIGHT, acknowledgementKey,
          ArchiveInFlightCodec.LIFECYCLE_VALUE_BYTES,
          "archive journal acknowledgement for block " + blockNum);
    }
    if (token == null && acknowledgement == null && validatedProof == null) {
      // No lifecycle evidence exists. Preserve idempotent delete semantics without probing an
      // untrusted payload value; startup validation remains responsible for orphan payload rows.
      validatedProofs.remove(blockNum);
      return;
    }
    if (token == null) {
      throw new ArchiveException("UNIFIED_V1 journal lifecycle row is orphaned for block "
          + blockNum);
    }
    ArchiveJournalProof decodedProof = decodeProof(token, "proof", blockNum);
    requireCurrentProofIdentity(blockNum, decodedProof);
    if (decodedProof.getPayloadLength() > maxEncodedBlockBytes) {
      throw new ArchiveJournalLimitException(
          "archive journal encoded bytes exceed configured limit "
              + maxEncodedBlockBytes + ": encodedBytes=" + decodedProof.getPayloadLength());
    }
    if (acknowledgement != null && !decodeProof(
        acknowledgement, "acknowledgement", blockNum).equals(decodedProof)) {
      throw corruption("UNIFIED_V1 acknowledgement mismatch for block " + blockNum);
    }
    if (validatedProof == null) {
      throw new ArchiveException(
          "UNIFIED_V1 journal proof must be startup-validated before deletion for block "
              + blockNum);
    }
    if (!decodedProof.equals(validatedProof)) {
      throw corruption("UNIFIED_V1 validated journal proof mismatch for block " + blockNum);
    }
    ArchiveJournalProof proofVerifier = validatedProof;
    runPersistentMutation(() -> db.deleteVerifiedJournalBlockDurably(
        journalKey, proofVerifier, maxEncodedBlockBytes,
        tokenKey, token, acknowledgementKey, acknowledgement));
    validatedProofs.remove(blockNum);
  }

  private void requireCurrentProofIdentity(long blockNum, ArchiveJournalProof proof) {
    ArchiveJournalToken proofToken = proof.getToken();
    if (proofToken.getBlockNum() != blockNum) {
      throw corruption("UNIFIED_V1 journal proof key/value mismatch for block " + blockNum);
    }
    if (!MessageDigest.isEqual(schemaChecksum, proofToken.getSchemaChecksum())) {
      throw corruption("UNIFIED_V1 journal proof schema checksum mismatch for block " + blockNum);
    }
  }

  @Override
  public void onBlockPublished(long blockNum) {
    synchronized (journalLock) {
      validatedProofs.remove(blockNum);
    }
  }

  private void runPersistentMutation(Runnable mutation) {
    if (writePermit == null) {
      mutation.run();
    } else {
      db.withProductionWritePermit(writePermit, mutation);
    }
  }

  private void requireBlockWithinLimits(ArchiveInFlightBlock block) {
    if (block == null) {
      throw new ArchiveException("archive in-flight block is invalid");
    }
    long recordCount = block.getRecords().size();
    if (recordCount > maxRecordsPerBlock) {
      throw new ArchiveJournalLimitException(
          "archive in-flight record count exceeds configured limit "
          + maxRecordsPerBlock + ": recordCount=" + recordCount);
    }
    long retainedBytes = block.estimatedRetainedBytes();
    if (retainedBytes > maxEncodedBlockBytes) {
      throw new ArchiveJournalLimitException(
          "archive journal retained bytes exceed configured limit "
          + maxEncodedBlockBytes + ": retainedBytes=" + retainedBytes);
    }
    long encodedBytes = ArchiveInFlightCodec.encodedBlockSize(block);
    if (encodedBytes > maxEncodedBlockBytes) {
      throw new ArchiveJournalLimitException(
          "archive journal encoded bytes exceed configured limit "
              + maxEncodedBlockBytes + ": encodedBytes=" + encodedBytes);
    }
  }

  private static ArchiveJournalCorruptionException corruption(String message) {
    return new ArchiveJournalCorruptionException(message);
  }

  private static ArchiveJournalCorruptionException corruption(
      String message, Throwable cause) {
    return new ArchiveJournalCorruptionException(message, cause);
  }

  private static ArchiveJournalCorruptionException corruptionWithCause(
      String message, ArchiveException cause) {
    String detail = cause.getMessage();
    return corruption(detail == null ? message : message + ": " + detail, cause);
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

  @Override
  public void ownerCloseSucceeded() {
    synchronized (journalLock) {
      validatedProofs.clear();
    }
  }
}
