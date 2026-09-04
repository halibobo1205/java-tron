package org.tron.core.archive;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.tron.common.math.StrictMathWrapper;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.archive.txnum.ArchiveCoordinates;
import org.tron.core.archive.txnum.ArchiveTxPosition;

final class ArchiveInFlightCodec {

  private static final byte VALUE_VERSION = 3;
  private static final byte PROOF_VERSION = 2;
  private static final byte BLOCK_PREFIX = 0x40;
  private static final byte ACK_PREFIX = 0x41;
  private static final byte TOKEN_PREFIX = 0x42;
  static final int LIFECYCLE_VALUE_BYTES = Byte.BYTES + Long.BYTES + 3 * Integer.BYTES
      + ArchiveBlockRange.BLOCK_HASH_LENGTH + ArchiveJournalToken.GENERATION_NONCE_LENGTH
      + ArchiveBlockRange.SCHEMA_CHECKSUM_LENGTH + Long.BYTES
      + ArchiveJournalProof.DIGEST_LENGTH;

  private ArchiveInFlightCodec() {
  }

  static byte[] blockPrefix() {
    return new byte[] {BLOCK_PREFIX};
  }

  static byte[] acknowledgementPrefix() {
    return new byte[] {ACK_PREFIX};
  }

  static byte[] tokenPrefix() {
    return new byte[] {TOKEN_PREFIX};
  }

  static byte[] blockKey(long blockNum) {
    ArchiveCoordinates.requireBlockNum(blockNum, "archive in-flight block number");
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(1 + Long.BYTES);
    DataOutputStream out = new DataOutputStream(bytes);
    try {
      out.writeByte(BLOCK_PREFIX);
      out.writeLong(blockNum);
      out.flush();
    } catch (IOException e) {
      throw new ArchiveException("archive in-flight block key encode failed", e);
    }
    return bytes.toByteArray();
  }

  static byte[] acknowledgementKey(long blockNum) {
    ArchiveCoordinates.requireBlockNum(blockNum, "archive acknowledgement block number");
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(1 + Long.BYTES);
    DataOutputStream out = new DataOutputStream(bytes);
    try {
      out.writeByte(ACK_PREFIX);
      out.writeLong(blockNum);
      out.flush();
    } catch (IOException e) {
      throw new ArchiveException("archive acknowledgement key encode failed", e);
    }
    return bytes.toByteArray();
  }

  static byte[] tokenKey(long blockNum) {
    return numberedKey(TOKEN_PREFIX, blockNum, "archive journal token");
  }

  private static byte[] numberedKey(byte prefix, long blockNum, String what) {
    ArchiveCoordinates.requireBlockNum(blockNum, what + " block number");
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(1 + Long.BYTES);
    DataOutputStream out = new DataOutputStream(bytes);
    try {
      out.writeByte(prefix);
      out.writeLong(blockNum);
      out.flush();
    } catch (IOException e) {
      throw new ArchiveException(what + " key encode failed", e);
    }
    return bytes.toByteArray();
  }

  static long blockNumOfKey(byte[] key) {
    if (key == null || key.length != 1 + Long.BYTES || key[0] != BLOCK_PREFIX) {
      throw new ArchiveException("archive in-flight block key is invalid");
    }
    try {
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(key, 1, Long.BYTES));
      long blockNum = in.readLong();
      ArchiveCoordinates.requireBlockNum(blockNum, "archive in-flight block number");
      return blockNum;
    } catch (IOException e) {
      throw new ArchiveException("archive in-flight block key decode failed", e);
    }
  }

  static long blockNumOfAcknowledgementKey(byte[] key) {
    if (key == null || key.length != 1 + Long.BYTES || key[0] != ACK_PREFIX) {
      throw new ArchiveException("archive acknowledgement key is invalid");
    }
    try {
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(key, 1, Long.BYTES));
      long blockNum = in.readLong();
      ArchiveCoordinates.requireBlockNum(blockNum, "archive acknowledgement block number");
      return blockNum;
    } catch (IOException e) {
      throw new ArchiveException("archive acknowledgement key decode failed", e);
    }
  }

  static long blockNumOfTokenKey(byte[] key) {
    if (key == null || key.length != 1 + Long.BYTES || key[0] != TOKEN_PREFIX) {
      throw new ArchiveException("archive journal token key is invalid");
    }
    try {
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(key, 1, Long.BYTES));
      long blockNum = in.readLong();
      ArchiveCoordinates.requireBlockNum(blockNum, "archive journal token block number");
      return blockNum;
    } catch (IOException e) {
      throw new ArchiveException("archive journal token key decode failed", e);
    }
  }

  static boolean startsWith(byte[] array, byte[] prefix) {
    if (array == null || array.length < prefix.length) {
      return false;
    }
    for (int i = 0; i < prefix.length; i++) {
      if (array[i] != prefix[i]) {
        return false;
      }
    }
    return true;
  }

  static byte[] encodeBlock(ArchiveInFlightBlock block) {
    ArchiveCoordinates.requireBlockNum(
        block.getRange().getBlockNum(), "archive in-flight block number");
    long encodedBytes = encodedBlockSize(block);
    if (encodedBytes > Integer.MAX_VALUE) {
      throw new ArchiveJournalLimitException(
          "archive in-flight encoded block exceeds Java array limit: bytes=" + encodedBytes);
    }
    ByteBuffer out = ByteBuffer.wrap(new byte[(int) encodedBytes]);
    out.put(VALUE_VERSION);
    out.put((byte) block.getJournalState().ordinal());
    writeToken(out, block.getJournalToken());
    writeRange(out, block.getRange());
    writePositions(out, block.getPositions());
    writeRecords(out, block.getPositions(), block.getRecords());
    if (out.hasRemaining()) {
      throw new ArchiveException("archive in-flight encoded size calculation mismatch: remaining="
          + out.remaining());
    }
    return out.array();
  }

  /** Exact encoded journal length without allocating the encoded payload. */
  static long encodedBlockSize(ArchiveInFlightBlock block) {
    return block.encodedBlockBytes();
  }

  static long calculateEncodedBlockSize(ArchiveInFlightBlock block) {
    long bytes = 2L;
    bytes = addSaturated(bytes, encodedTokenSize(block.getJournalToken()));
    bytes = addSaturated(bytes, encodedRangeSize(block.getRange()));
    bytes = addSaturated(bytes, Integer.BYTES);
    for (ArchiveTxPosition position : block.getPositions()) {
      bytes = addSaturated(bytes, encodedPositionSize(position));
    }
    bytes = addSaturated(bytes, Integer.BYTES);
    for (ArchiveChangeRecord record : block.getRecords()) {
      requireRecordPosition(block.getPositions(), record);
      bytes = addSaturated(bytes, Long.BYTES);
      bytes = addSaturated(bytes, Integer.BYTES);
      bytes = addSaturated(bytes, encodedBytesSize(record.canonicalKeySize()));
      bytes = addSaturated(bytes, encodedDomainValueSize(record.getPrevValue()));
      bytes = addSaturated(bytes, encodedDomainValueSize(record.getValue()));
    }
    return bytes;
  }

  private static long encodedTokenSize(ArchiveJournalToken token) {
    long bytes = Long.BYTES;
    bytes = addSaturated(bytes, encodedBytesSize(token.getBlockHash().length));
    bytes = addSaturated(bytes, encodedBytesSize(token.getGenerationNonce().length));
    return addSaturated(bytes, encodedBytesSize(token.getSchemaChecksum().length));
  }

  private static long encodedRangeSize(ArchiveBlockRange range) {
    long bytes = 5L * Long.BYTES + Integer.BYTES + Byte.BYTES;
    bytes = addSaturated(bytes, encodedBytesSize(range.getBlockHash().length));
    return addSaturated(bytes, encodedBytesSize(range.getSchemaChecksum().length));
  }

  private static long encodedPositionSize(ArchiveTxPosition position) {
    long bytes = 2L * Long.BYTES + 2L * Byte.BYTES + Integer.BYTES;
    bytes = addSaturated(bytes, encodedBytesSize(position.txIdSize()));
    return addSaturated(bytes, encodedBytesSize(position.blockHashSize()));
  }

  private static long encodedDomainValueSize(DomainValue value) {
    return addSaturated(Byte.BYTES, encodedBytesSize(value.size()));
  }

  private static long encodedBytesSize(int valueBytes) {
    return addSaturated(Integer.BYTES, valueBytes);
  }

  static ArchiveInFlightBlock decodeBlock(byte[] value) {
    return decodeBlock(value, Long.MAX_VALUE, Long.MAX_VALUE);
  }

  static ArchiveInFlightBlock decodeBlock(byte[] value, long maxRecords,
      long maxRetainedBytes) {
    if (value == null) {
      throw new ArchiveException("archive in-flight block value is missing");
    }
    if (maxRecords < 0L || maxRetainedBytes < 0L) {
      throw new IllegalArgumentException("archive in-flight decode limits must be non-negative");
    }
    preflightBlock(value, maxRecords, maxRetainedBytes);
    try {
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(value));
      requireVersion(in.readByte());
      ArchiveInFlightBlock.JournalState state = journalStateAt(in.readUnsignedByte());
      ArchiveJournalToken token = readToken(in);
      ArchiveBlockRange range = readRange(in);
      List<ArchiveTxPosition> positions = readPositions(in);
      List<ArchiveChangeRecord> records = readRecords(in, positions);
      if (in.available() != 0) {
        throw new ArchiveException("archive in-flight block has trailing bytes");
      }
      return ArchiveInFlightBlock.fromOwnedLists(range, positions, records, token, state);
    } catch (IOException e) {
      throw new ArchiveException("archive in-flight block decode failed", e);
    }
  }

  private static void preflightBlock(byte[] value, long maxRecords,
      long maxRetainedBytes) {
    try {
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(value));
      requireVersion(in.readByte());
      journalStateAt(in.readUnsignedByte());
      preflightToken(in);
      preflightRange(in);
      int positionCount = boundedCount(
          in, in.readInt(), 30, "archive in-flight position count");
      long retainedBytes = ArchiveResourceEstimator.estimatedPositionSetRetainedBytes(
          positionCount);
      requireRetainedBytes(retainedBytes, maxRetainedBytes);
      for (int i = 0; i < positionCount; i++) {
        int positionTransientBytes = preflightPosition(in);
        requireRetainedBytes(
            addSaturated(retainedBytes, positionTransientBytes), maxRetainedBytes);
      }
      int recordCount = boundedCount(
          in, in.readInt(), 26, "archive in-flight record count");
      if (recordCount > maxRecords) {
        throw new ArchiveJournalLimitException(
            "archive in-flight record count exceeds configured limit "
                + maxRecords + ": recordCount=" + recordCount);
      }
      long maxValidationTransientBytes = 0L;
      for (int i = 0; i < recordCount; i++) {
        ArchiveCoordinates.requireTxNum(
            in.readLong(), "archive in-flight record txNum");
        ArchiveDomain domain = domainAt(in.readInt());
        int keyBytes = preflightBytes(in);
        int prevValueBytes = preflightDomainValue(in);
        int valueBytes = preflightDomainValue(in);
        retainedBytes = addSaturated(retainedBytes,
            ArchiveInFlightBlock.estimatedRecordRetainedBytes(
                keyBytes, prevValueBytes, valueBytes));
        long decodeTransientBytes = addSaturated(
            keyBytes, StrictMathWrapper.max(prevValueBytes, valueBytes));
        requireRetainedBytes(
            addSaturated(retainedBytes, decodeTransientBytes), maxRetainedBytes);
        // DynamicKeyPolicy may retain the key copy while constructing a UTF-16 property name.
        long keyValidationBytes = domain == ArchiveDomain.DYNAMIC_PROPERTIES
            ? addSaturated(keyBytes, addSaturated(keyBytes, keyBytes)) : keyBytes;
        long valueValidationBytes = addSaturated(
            keyBytes, StrictMathWrapper.max(prevValueBytes, valueBytes));
        long validationTransientBytes =
            StrictMathWrapper.max(keyValidationBytes, valueValidationBytes);
        maxValidationTransientBytes = StrictMathWrapper.max(
            maxValidationTransientBytes, validationTransientBytes);
      }
      requireRetainedBytes(
          addSaturated(retainedBytes, maxValidationTransientBytes), maxRetainedBytes);
      if (in.available() != 0) {
        throw new ArchiveException("archive in-flight block has trailing bytes");
      }
    } catch (IOException e) {
      throw new ArchiveException("archive in-flight block preflight failed", e);
    }
  }

  private static void preflightToken(DataInputStream in) throws IOException {
    ArchiveCoordinates.requireBlockNum(
        in.readLong(), "archive in-flight journal token block number");
    preflightFixedBytes(in, ArchiveBlockRange.BLOCK_HASH_LENGTH,
        "archive in-flight journal token block hash");
    preflightFixedBytes(in, ArchiveJournalToken.GENERATION_NONCE_LENGTH,
        "archive in-flight journal generation nonce");
    preflightFixedBytes(in, ArchiveBlockRange.SCHEMA_CHECKSUM_LENGTH,
        "archive in-flight journal schema checksum");
  }

  private static void preflightRange(DataInputStream in) throws IOException {
    in.readLong();
    in.readLong();
    in.readLong();
    in.readLong();
    in.readLong();
    in.readInt();
    sourceAt(in.readUnsignedByte());
    preflightFixedBytes(in, ArchiveBlockRange.BLOCK_HASH_LENGTH,
        "archive in-flight range block hash");
    preflightFixedBytes(in, ArchiveBlockRange.SCHEMA_CHECKSUM_LENGTH,
        "archive in-flight range schema checksum");
  }

  private static int preflightPosition(DataInputStream in) throws IOException {
    in.readLong();
    in.readLong();
    ArchivePhase phase = phaseAt(in.readUnsignedByte());
    sourceAt(in.readUnsignedByte());
    in.readInt();
    int txIdBytes = preflightBytes(in);
    int blockHashBytes = preflightBytes(in);
    if (blockHashBytes != 0 && blockHashBytes != ArchiveBlockRange.BLOCK_HASH_LENGTH) {
      throw new ArchiveException("archive in-flight position block hash must be empty or "
          + ArchiveBlockRange.BLOCK_HASH_LENGTH + " bytes: actual=" + blockHashBytes);
    }
    if (phase == ArchivePhase.USER_TX || phase == ArchivePhase.USER_TX_VM) {
      if (txIdBytes != ArchiveBlockRange.BLOCK_HASH_LENGTH) {
        throw new ArchiveException(
            "archive in-flight position user txId must be a 32-byte txId");
      }
    } else if (txIdBytes != 0) {
      throw new ArchiveException("archive in-flight position system txId must be empty");
    }
    return txIdBytes + blockHashBytes;
  }

  private static int preflightDomainValue(DataInputStream in) throws IOException {
    boolean deleted = readBoolean(in, "archive in-flight domain-value deleted flag");
    int valueBytes = preflightBytes(in);
    if (deleted && valueBytes != 0) {
      throw new ArchiveException("archive in-flight tombstone value must be empty");
    }
    return valueBytes;
  }

  private static int preflightBytes(DataInputStream in) throws IOException {
    int length = nonNegativeCount(in.readInt(), "archive in-flight byte length");
    if (length > in.available()) {
      throw new ArchiveException("archive in-flight byte length exceeds remaining value bytes");
    }
    int skipped = in.skipBytes(length);
    if (skipped != length) {
      throw new ArchiveException("archive in-flight byte value is truncated");
    }
    return length;
  }

  private static void preflightFixedBytes(DataInputStream in, int expectedLength, String what)
      throws IOException {
    int actualLength = preflightBytes(in);
    if (actualLength != expectedLength) {
      throw new ArchiveException(
          what + " must be " + expectedLength + " bytes: actual=" + actualLength);
    }
  }

  private static void requireRetainedBytes(long retainedBytes, long maxRetainedBytes) {
    if (retainedBytes > maxRetainedBytes) {
      throw new ArchiveJournalLimitException(
          "archive journal retained bytes exceed configured limit "
          + maxRetainedBytes + ": retainedBytes=" + retainedBytes);
    }
  }

  private static long addSaturated(long left, long right) {
    return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
  }

  static byte[] encodeProof(ArchiveJournalProof proof) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream out = new DataOutputStream(bytes);
      out.writeByte(PROOF_VERSION);
      writeToken(out, proof.getToken());
      out.writeLong(proof.getPayloadLength());
      out.write(proof.getPayloadDigest());
      out.flush();
      return bytes.toByteArray();
    } catch (IOException e) {
      throw new ArchiveException("archive journal proof encode failed", e);
    }
  }

  static ArchiveJournalProof decodeProof(byte[] value) {
    if (value == null || value.length != LIFECYCLE_VALUE_BYTES) {
      throw new ArchiveException("archive journal proof has invalid length");
    }
    try {
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(value));
      if (in.readByte() != PROOF_VERSION) {
        throw new ArchiveException("archive journal proof version mismatch");
      }
      ArchiveJournalToken token = readToken(in);
      long payloadLength = in.readLong();
      byte[] payloadDigest = new byte[ArchiveJournalProof.DIGEST_LENGTH];
      in.readFully(payloadDigest);
      if (in.available() != 0) {
        throw new ArchiveException("archive journal proof has trailing bytes");
      }
      return new ArchiveJournalProof(token, payloadLength, payloadDigest);
    } catch (IOException e) {
      throw new ArchiveException("archive journal proof decode failed", e);
    }
  }

  private static void writeToken(ByteBuffer out, ArchiveJournalToken token) {
    out.putLong(token.getBlockNum());
    writeBytes(out, token.getBlockHash());
    writeBytes(out, token.getGenerationNonce());
    writeBytes(out, token.getSchemaChecksum());
  }

  private static void writeRange(ByteBuffer out, ArchiveBlockRange range) {
    out.putLong(range.getBlockNum());
    out.putLong(range.getFirstTxNum());
    out.putLong(range.getLastTxNum());
    out.putLong(range.getPrepareTxNum());
    out.putLong(range.getFinalizeTxNum());
    out.putInt(range.getUserTxCount());
    out.put((byte) range.getSource().ordinal());
    writeBytes(out, range.getBlockHash());
    writeBytes(out, range.getSchemaChecksum());
  }

  private static void writePositions(ByteBuffer out, List<ArchiveTxPosition> positions) {
    out.putInt(positions.size());
    for (ArchiveTxPosition position : positions) {
      writePosition(out, position);
    }
  }

  private static void writePosition(ByteBuffer out, ArchiveTxPosition position) {
    requirePositionTxId(
        position.getPhase(), position.txIdSize(), "archive in-flight position");
    out.putLong(position.getTxNum());
    out.putLong(position.getBlockNum());
    out.put((byte) position.getPhase().ordinal());
    out.put((byte) position.getSource().ordinal());
    out.putInt(position.getTxIndex());
    writePositionBytes(out, position, true);
    writePositionBytes(out, position, false);
  }

  private static void writePositionBytes(ByteBuffer out, ArchiveTxPosition position,
      boolean transactionId) {
    int length = transactionId ? position.txIdSize() : position.blockHashSize();
    out.putInt(length);
    int offset = out.position();
    if (transactionId) {
      position.copyTxIdTo(out.array(), offset);
    } else {
      position.copyBlockHashTo(out.array(), offset);
    }
    out.position(offset + length);
  }

  private static void writeRecords(ByteBuffer out, List<ArchiveTxPosition> positions,
      List<ArchiveChangeRecord> records) {
    out.putInt(records.size());
    for (ArchiveChangeRecord record : records) {
      requireRecordPosition(positions, record);
      out.putLong(record.getTxNum());
      out.putInt(record.getDomain().getId());
      out.putInt(record.canonicalKeySize());
      int keyOffset = out.position();
      record.copyCanonicalKeyTo(out.array(), keyOffset);
      out.position(keyOffset + record.canonicalKeySize());
      writeDomainValue(out, record.getPrevValue());
      writeDomainValue(out, record.getValue());
    }
  }

  private static void requireRecordPosition(List<ArchiveTxPosition> positions,
      ArchiveChangeRecord record) {
    ArchiveTxPosition persisted = positionForTxNum(positions, record.getTxNum());
    if (!persisted.contentEquals(record.getPosition())) {
      throw new ArchiveException(
          "archive in-flight record position does not match persisted position");
    }
  }

  private static void writeDomainValue(ByteBuffer out, DomainValue value) {
    out.put(value.isDeleted() ? (byte) 1 : (byte) 0);
    out.putInt(value.size());
    int valueOffset = out.position();
    value.copyValueTo(out.array(), valueOffset);
    out.position(valueOffset + value.size());
  }

  private static void writeBytes(ByteBuffer out, byte[] value) {
    out.putInt(value.length);
    out.put(value);
  }

  private static void writeToken(DataOutputStream out, ArchiveJournalToken token)
      throws IOException {
    out.writeLong(token.getBlockNum());
    writeBytes(out, token.getBlockHash());
    writeBytes(out, token.getGenerationNonce());
    writeBytes(out, token.getSchemaChecksum());
  }

  private static ArchiveJournalToken readToken(DataInputStream in) throws IOException {
    return new ArchiveJournalToken(
        in.readLong(), readBytes(in), readBytes(in), readBytes(in));
  }

  private static ArchiveBlockRange readRange(DataInputStream in) throws IOException {
    long blockNum = in.readLong();
    long firstTxNum = in.readLong();
    long lastTxNum = in.readLong();
    long prepareTxNum = in.readLong();
    long finalizeTxNum = in.readLong();
    int userTxCount = in.readInt();
    ArchiveSource source = sourceAt(in.readUnsignedByte());
    byte[] blockHash = readBytes(in);
    byte[] schemaChecksum = readBytes(in);
    return new ArchiveBlockRange(blockNum, firstTxNum, lastTxNum, prepareTxNum, finalizeTxNum,
        blockHash, userTxCount, source, schemaChecksum);
  }

  private static List<ArchiveTxPosition> readPositions(DataInputStream in) throws IOException {
    int count = boundedCount(in, in.readInt(), 30, "archive in-flight position count");
    List<ArchiveTxPosition> positions = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      positions.add(readPosition(in));
    }
    return positions;
  }

  private static ArchiveTxPosition readPosition(DataInputStream in) throws IOException {
    long txNum = in.readLong();
    long blockNum = in.readLong();
    ArchivePhase phase = phaseAt(in.readUnsignedByte());
    ArchiveSource source = sourceAt(in.readUnsignedByte());
    int txIndex = in.readInt();
    byte[] txId = readBytes(in);
    byte[] blockHash = readBytes(in);
    requirePositionTxId(phase, txId.length, "archive in-flight position");
    return new ArchiveTxPosition(txNum, blockNum, phase, source, txIndex, txId, blockHash);
  }

  private static void requirePositionTxId(ArchivePhase phase, int txIdSize, String what) {
    if (phase == ArchivePhase.USER_TX || phase == ArchivePhase.USER_TX_VM) {
      if (txIdSize != ArchiveBlockRange.BLOCK_HASH_LENGTH) {
        throw new ArchiveException(what + " user txId must be a 32-byte txId");
      }
      return;
    }
    if (txIdSize != 0) {
      throw new ArchiveException(what + " system txId must be empty");
    }
  }

  private static List<ArchiveChangeRecord> readRecords(DataInputStream in,
      List<ArchiveTxPosition> positions) throws IOException {
    int count = boundedCount(in, in.readInt(), 26, "archive in-flight record count");
    List<ArchiveChangeRecord> records = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      ArchiveTxPosition position = positionForTxNum(positions, in.readLong());
      ArchiveDomain domain = domainAt(in.readInt());
      byte[] canonicalKey = readBytes(in);
      DomainValue prevValue = readDomainValue(in);
      DomainValue value = readDomainValue(in);
      records.add(new ArchiveChangeRecord(position, domain, canonicalKey, prevValue, value));
    }
    return records;
  }

  private static ArchiveTxPosition positionForTxNum(List<ArchiveTxPosition> positions,
      long txNum) {
    ArchiveCoordinates.requireTxNum(txNum, "archive in-flight record txNum");
    if (positions.isEmpty()) {
      throw new ArchiveException(
          "archive in-flight record references a missing tx-position " + txNum);
    }
    long firstTxNum = positions.get(0).getTxNum();
    ArchiveCoordinates.requireTxNum(firstTxNum, "archive in-flight first position txNum");
    if (txNum < firstTxNum) {
      throw new ArchiveException(
          "archive in-flight record references a missing tx-position " + txNum);
    }
    long offset = txNum - firstTxNum;
    if (offset >= positions.size()) {
      throw new ArchiveException(
          "archive in-flight record references a missing tx-position " + txNum);
    }
    ArchiveTxPosition position = positions.get((int) offset);
    if (position.getTxNum() != txNum) {
      throw new ArchiveException(
          "archive in-flight record references a missing tx-position " + txNum);
    }
    return position;
  }

  private static DomainValue readDomainValue(DataInputStream in) throws IOException {
    boolean deleted = readBoolean(in, "archive in-flight domain-value deleted flag");
    byte[] value = readBytes(in);
    if (deleted) {
      if (value.length != 0) {
        throw new ArchiveException("archive in-flight tombstone value must be empty");
      }
      return DomainValue.tombstone();
    }
    return DomainValue.present(value);
  }

  private static boolean readBoolean(DataInputStream in, String what) throws IOException {
    int encoded = in.readUnsignedByte();
    if (encoded == 0) {
      return false;
    }
    if (encoded == 1) {
      return true;
    }
    throw new ArchiveException(what + " must be encoded as 0 or 1: actual=" + encoded);
  }

  private static void writeBytes(DataOutputStream out, byte[] value) throws IOException {
    out.writeInt(value.length);
    out.write(value);
  }

  private static byte[] readBytes(DataInputStream in) throws IOException {
    int length = nonNegativeCount(in.readInt(), "archive in-flight byte length");
    if (length > in.available()) {
      throw new ArchiveException("archive in-flight byte length exceeds remaining value bytes");
    }
    byte[] value = new byte[length];
    in.readFully(value);
    return value;
  }

  private static int boundedCount(DataInputStream in, int count, int minBytesPerEntry,
      String what) throws IOException {
    nonNegativeCount(count, what);
    if (count > in.available() / minBytesPerEntry) {
      throw new ArchiveException(what + " exceeds remaining value bytes");
    }
    return count;
  }

  private static int nonNegativeCount(int count, String what) {
    if (count < 0) {
      throw new ArchiveException(what + " is negative");
    }
    return count;
  }

  private static void requireVersion(byte version) {
    if (version != VALUE_VERSION) {
      throw new ArchiveException("archive in-flight block has unsupported value version "
          + version);
    }
  }

  private static ArchiveSource sourceAt(int ordinal) {
    ArchiveSource[] values = ArchiveSource.values();
    if (ordinal >= values.length) {
      throw new ArchiveException("archive in-flight source ordinal is invalid: " + ordinal);
    }
    return values[ordinal];
  }

  private static ArchivePhase phaseAt(int ordinal) {
    ArchivePhase[] values = ArchivePhase.values();
    if (ordinal >= values.length) {
      throw new ArchiveException("archive in-flight phase ordinal is invalid: " + ordinal);
    }
    return values[ordinal];
  }

  private static ArchiveInFlightBlock.JournalState journalStateAt(int ordinal) {
    ArchiveInFlightBlock.JournalState[] values = ArchiveInFlightBlock.JournalState.values();
    if (ordinal >= values.length) {
      throw new ArchiveException("archive in-flight journal state ordinal is invalid: "
          + ordinal);
    }
    return values[ordinal];
  }

  private static ArchiveDomain domainAt(int id) {
    for (ArchiveDomain domain : ArchiveDomain.values()) {
      if (domain.getId() == id) {
        return domain;
      }
    }
    throw new ArchiveException("archive in-flight domain id is invalid: " + id);
  }
}
