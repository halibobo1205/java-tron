package org.tron.core.archive;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.domain.ArchiveDomainCatalog;
import org.tron.core.archive.domain.ArchiveDomainDescriptor;
import org.tron.core.archive.domain.DynamicKeyPolicy;
import org.tron.core.archive.domain.HistoryPolicy;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.archive.txnum.ArchiveCoordinates;
import org.tron.core.archive.txnum.ArchiveTxPosition;

/** Validates durable in-flight blocks independently of their physical storage layout. */
final class ArchiveInFlightValidator {

  private ArchiveInFlightValidator() {
  }

  static void validate(ArchiveInFlightBlock block, ArchiveDomainCatalog catalog,
      DynamicKeyPolicy dynamicKeyPolicy) {
    validateForWrite(block, catalog, dynamicKeyPolicy);
  }

  static void validateForWrite(ArchiveInFlightBlock block, ArchiveDomainCatalog catalog,
      DynamicKeyPolicy dynamicKeyPolicy) {
    validate(block, catalog, dynamicKeyPolicy, false);
  }

  /**
   * Validates a decoded block only after its v2 durable proof, token, and current schema have been
   * checked. Canonical protobuf parsing happened before that proof was authored, so repeating it on
   * startup would introduce an unbounded object-graph allocation outside the journal byte budget.
   */
  static void validateProofBound(ArchiveInFlightBlock block, ArchiveDomainCatalog catalog,
      DynamicKeyPolicy dynamicKeyPolicy) {
    validate(block, catalog, dynamicKeyPolicy, true);
  }

  private static void validate(ArchiveInFlightBlock block, ArchiveDomainCatalog catalog,
      DynamicKeyPolicy dynamicKeyPolicy, boolean proofBound) {
    if (block == null || block.getRange() == null) {
      throw new ArchiveException("archive in-flight block is invalid");
    }
    ArchiveBlockRange range = block.getRange();
    validateRange(range);
    validatePositions(range, block.getPositions());
    validateRecords(
        range, block.getPositions(), block.getRecords(), catalog, dynamicKeyPolicy, proofBound);
  }

  private static void validateRange(ArchiveBlockRange range) {
    ArchiveCoordinates.requireBlockNum(
        range.getBlockNum(), "archive in-flight range block number");
    ArchiveCoordinates.requireTxNum(
        range.getFirstTxNum(), "archive in-flight range first txNum");
    ArchiveCoordinates.requireTxNum(
        range.getLastTxNum(), "archive in-flight range last txNum");
    ArchiveCoordinates.requireTxNum(
        range.getPrepareTxNum(), "archive in-flight range prepare txNum");
    ArchiveCoordinates.requireTxNum(
        range.getFinalizeTxNum(), "archive in-flight range finalize txNum");
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

  private static void validatePositions(ArchiveBlockRange range,
      List<ArchiveTxPosition> positions) {
    if (positions == null) {
      throw new ArchiveException("archive in-flight positions are missing");
    }
    long expectedCount = range.getLastTxNum() - range.getFirstTxNum() + 1;
    if (expectedCount > Integer.MAX_VALUE || positions.size() != (int) expectedCount) {
      throw new ArchiveException("archive in-flight position count does not match block range");
    }
    Set<Integer> userIndexes = new HashSet<>();
    Set<ByteArrayKey> userTxIds = new HashSet<>();
    boolean sawPrepare = false;
    boolean sawFinalize = false;
    int userCount = 0;
    for (int positionIndex = 0; positionIndex < positions.size(); positionIndex++) {
      ArchiveTxPosition position = positions.get(positionIndex);
      validatePosition(range, position);
      long expectedTxNum = range.getFirstTxNum() + positionIndex;
      if (position.getTxNum() != expectedTxNum) {
        throw new ArchiveException("archive in-flight positions are not in txNum order");
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
  }

  private static void validatePosition(ArchiveBlockRange range, ArchiveTxPosition position) {
    if (position == null) {
      throw new ArchiveException("archive in-flight position is missing");
    }
    ArchiveCoordinates.requireTxNum(
        position.getTxNum(), "archive in-flight position txNum");
    ArchiveCoordinates.requireBlockNum(
        position.getBlockNum(), "archive in-flight position block number");
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
      List<ArchiveTxPosition> positions, List<ArchiveChangeRecord> records,
      ArchiveDomainCatalog catalog, DynamicKeyPolicy dynamicKeyPolicy, boolean proofBound) {
    if (records == null) {
      throw new ArchiveException("archive in-flight records are missing");
    }
    Set<ChangeKey> changes = new HashSet<>();
    for (ArchiveChangeRecord record : records) {
      if (record == null || record.getPosition() == null || record.getDomain() == null) {
        throw new ArchiveException("archive in-flight record is invalid");
      }
      validatePosition(range, record.getPosition());
      int positionIndex = (int) (record.getTxNum() - range.getFirstTxNum());
      ArchiveTxPosition persisted = positions.get(positionIndex);
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
      byte[] canonicalKey = record.getCanonicalKey();
      descriptor.getKeyCodec().validate(canonicalKey);
      validateKeyPolicy(record.getDomain(), canonicalKey, dynamicKeyPolicy);
      if (proofBound) {
        descriptor.getValueCodec().validateProofBound(record.getPrevValue());
        descriptor.getValueCodec().validateProofBound(record.getValue());
      } else {
        descriptor.getValueCodec().validate(record.getPrevValue());
        descriptor.getValueCodec().validate(record.getValue());
      }
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
    return left != null && left.contentEquals(right);
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
}
