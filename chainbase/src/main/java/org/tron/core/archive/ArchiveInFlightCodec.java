package org.tron.core.archive;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.archive.txnum.ArchiveCoordinates;
import org.tron.core.archive.txnum.ArchiveTxPosition;

final class ArchiveInFlightCodec {

  private static final byte VALUE_VERSION = 2;
  private static final byte BLOCK_PREFIX = 0x40;
  private static final byte ACK_PREFIX = 0x41;
  private static final byte TOKEN_PREFIX = 0x42;

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
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream out = new DataOutputStream(bytes);
      out.writeByte(VALUE_VERSION);
      out.writeByte(block.getJournalState().ordinal());
      writeToken(out, block.getJournalToken());
      writeRange(out, block.getRange());
      writePositions(out, block.getPositions());
      writeRecords(out, block.getRecords());
      out.flush();
      return bytes.toByteArray();
    } catch (IOException e) {
      throw new ArchiveException("archive in-flight block encode failed", e);
    }
  }

  static ArchiveInFlightBlock decodeBlock(byte[] value) {
    try {
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(value));
      requireVersion(in.readByte());
      ArchiveInFlightBlock.JournalState state = journalStateAt(in.readUnsignedByte());
      ArchiveJournalToken token = readToken(in);
      ArchiveBlockRange range = readRange(in);
      List<ArchiveTxPosition> positions = readPositions(in);
      List<ArchiveChangeRecord> records = readRecords(in);
      if (in.available() != 0) {
        throw new ArchiveException("archive in-flight block has trailing bytes");
      }
      return new ArchiveInFlightBlock(range, positions, records, token, state);
    } catch (IOException e) {
      throw new ArchiveException("archive in-flight block decode failed", e);
    }
  }

  static byte[] encodeAcknowledgement(ArchiveJournalToken token) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream out = new DataOutputStream(bytes);
      writeToken(out, token);
      out.flush();
      return bytes.toByteArray();
    } catch (IOException e) {
      throw new ArchiveException("archive acknowledgement encode failed", e);
    }
  }

  static ArchiveJournalToken decodeAcknowledgement(byte[] value) {
    try {
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(value));
      ArchiveJournalToken token = readToken(in);
      if (in.available() != 0) {
        throw new ArchiveException("archive acknowledgement has trailing bytes");
      }
      return token;
    } catch (IOException e) {
      throw new ArchiveException("archive acknowledgement decode failed", e);
    }
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

  private static void writeRange(DataOutputStream out, ArchiveBlockRange range)
      throws IOException {
    out.writeLong(range.getBlockNum());
    out.writeLong(range.getFirstTxNum());
    out.writeLong(range.getLastTxNum());
    out.writeLong(range.getPrepareTxNum());
    out.writeLong(range.getFinalizeTxNum());
    out.writeInt(range.getUserTxCount());
    out.writeByte(range.getSource().ordinal());
    writeBytes(out, range.getBlockHash());
    writeBytes(out, range.getSchemaChecksum());
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

  private static void writePositions(DataOutputStream out, List<ArchiveTxPosition> positions)
      throws IOException {
    out.writeInt(positions.size());
    for (ArchiveTxPosition position : positions) {
      writePosition(out, position);
    }
  }

  private static List<ArchiveTxPosition> readPositions(DataInputStream in) throws IOException {
    int count = boundedCount(in, in.readInt(), 30, "archive in-flight position count");
    List<ArchiveTxPosition> positions = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      positions.add(readPosition(in));
    }
    return positions;
  }

  private static void writePosition(DataOutputStream out, ArchiveTxPosition position)
      throws IOException {
    requirePositionTxId(position.getPhase(), position.getTxId(), "archive in-flight position");
    out.writeLong(position.getTxNum());
    out.writeLong(position.getBlockNum());
    out.writeByte(position.getPhase().ordinal());
    out.writeByte(position.getSource().ordinal());
    out.writeInt(position.getTxIndex());
    writeBytes(out, position.getTxId());
    writeBytes(out, position.getBlockHash());
  }

  private static ArchiveTxPosition readPosition(DataInputStream in) throws IOException {
    long txNum = in.readLong();
    long blockNum = in.readLong();
    ArchivePhase phase = phaseAt(in.readUnsignedByte());
    ArchiveSource source = sourceAt(in.readUnsignedByte());
    int txIndex = in.readInt();
    byte[] txId = readBytes(in);
    byte[] blockHash = readBytes(in);
    requirePositionTxId(phase, txId, "archive in-flight position");
    return new ArchiveTxPosition(txNum, blockNum, phase, source, txIndex, txId, blockHash);
  }

  private static void requirePositionTxId(ArchivePhase phase, byte[] txId, String what) {
    if (phase == ArchivePhase.USER_TX) {
      if (txId == null || txId.length != ArchiveBlockRange.BLOCK_HASH_LENGTH) {
        throw new ArchiveException(what + " user txId must be a 32-byte txId");
      }
      return;
    }
    if (txId != null && txId.length != 0) {
      throw new ArchiveException(what + " system txId must be empty");
    }
  }

  private static void writeRecords(DataOutputStream out, List<ArchiveChangeRecord> records)
      throws IOException {
    out.writeInt(records.size());
    for (ArchiveChangeRecord record : records) {
      writePosition(out, record.getPosition());
      out.writeInt(record.getDomain().getId());
      writeBytes(out, record.getCanonicalKey());
      writeDomainValue(out, record.getPrevValue());
      writeDomainValue(out, record.getValue());
    }
  }

  private static List<ArchiveChangeRecord> readRecords(DataInputStream in) throws IOException {
    int count = boundedCount(in, in.readInt(), 48, "archive in-flight record count");
    List<ArchiveChangeRecord> records = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      ArchiveTxPosition position = readPosition(in);
      ArchiveDomain domain = domainAt(in.readInt());
      byte[] canonicalKey = readBytes(in);
      DomainValue prevValue = readDomainValue(in);
      DomainValue value = readDomainValue(in);
      records.add(new ArchiveChangeRecord(position, domain, canonicalKey, prevValue, value));
    }
    return records;
  }

  private static void writeDomainValue(DataOutputStream out, DomainValue value)
      throws IOException {
    out.writeBoolean(value.isDeleted());
    writeBytes(out, value.getValue());
  }

  private static DomainValue readDomainValue(DataInputStream in) throws IOException {
    boolean deleted = in.readBoolean();
    byte[] value = readBytes(in);
    if (deleted) {
      if (value.length != 0) {
        throw new ArchiveException("archive in-flight tombstone value must be empty");
      }
      return DomainValue.tombstone();
    }
    return DomainValue.present(value);
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
