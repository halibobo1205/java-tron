package org.tron.core.archive.temporal;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.unified.UnifiedArchiveColumnFamily;

/** Fixed locator and key-bound integrity codec for out-of-line temporal payloads. */
final class ArchiveTemporalIntegrityCodec {

  static final long NO_HISTORY_TX_NUM = -1L;
  static final int LOCATOR_BYTES = 1 + Long.BYTES + Integer.BYTES + 32;
  static final int MAX_PAYLOAD_BYTES = 256 * 1024 * 1024;
  // Publication reserves eight value-sized working copies before persistence. With the factory
  // capping that reservation at MAX_PAYLOAD_BYTES, no valid writer can emit a larger single row.
  static final int MAX_STORED_PAYLOAD_BYTES = MAX_PAYLOAD_BYTES / 8;

  private static final byte LOCATOR_VERSION = 0x01;
  private static final byte HISTORY_TABLE = 0x01;
  private static final byte LATEST_TABLE = 0x02;
  private static final byte CHANGESET_TABLE = 0x03;
  private static final byte ANCHOR_TABLE = 0x04;
  private static final int LINK_OFFSET = 1;
  private static final int LENGTH_OFFSET = LINK_OFFSET + Long.BYTES;
  private static final int DIGEST_OFFSET = LENGTH_OFFSET + Integer.BYTES;
  private static final byte[] HASH_DOMAIN = new byte[] {
      't', 'r', 'o', 'n', '-', 'a', 'r', 'c', 'h', 'i', 'v', 'e', '-',
      't', 'e', 'm', 'p', 'o', 'r', 'a', 'l', '-', 'l', 'o', 'c', 'a', 't', 'o', 'r', '-',
      'v', '1'
  };
  private static final ThreadLocal<MessageDigest> DIGEST =
      ThreadLocal.withInitial(ArchiveTemporalIntegrityCodec::newDigest);

  private ArchiveTemporalIntegrityCodec() {
  }

  static byte[] encode(UnifiedArchiveColumnFamily columnFamily, byte[] rowKey,
      byte[] payload, long linkedTxNum) {
    requireRowKey(rowKey);
    requirePayload(payload);
    requireLinkedTxNum(linkedTxNum);
    byte[] locator = new byte[LOCATOR_BYTES];
    locator[0] = LOCATOR_VERSION;
    putLong(locator, LINK_OFFSET, linkedTxNum);
    putInt(locator, LENGTH_OFFSET, payload.length);
    byte[] digest = digest(columnFamily, rowKey, linkedTxNum, payload);
    System.arraycopy(digest, 0, locator, DIGEST_OFFSET, digest.length);
    return locator;
  }

  static byte[] payloadKey(UnifiedArchiveColumnFamily columnFamily, byte[] rowKey) {
    requireRowKey(rowKey);
    byte[] payloadKey = new byte[1 + rowKey.length];
    payloadKey[0] = tableId(columnFamily);
    System.arraycopy(rowKey, 0, payloadKey, 1, rowKey.length);
    return payloadKey;
  }

  static UnifiedArchiveColumnFamily columnFamilyOfPayloadKey(
      byte[] payloadKey, String operation) {
    requirePayloadKey(payloadKey, operation);
    switch (payloadKey[0]) {
      case HISTORY_TABLE:
        return UnifiedArchiveColumnFamily.HISTORY;
      case LATEST_TABLE:
        return UnifiedArchiveColumnFamily.LATEST;
      case CHANGESET_TABLE:
        return UnifiedArchiveColumnFamily.CHANGESET;
      case ANCHOR_TABLE:
        return UnifiedArchiveColumnFamily.COMMITMENT;
      default:
        throw new ArchiveException(operation + ": temporal payload table tag is invalid");
    }
  }

  static byte[] logicalKeyOfPayloadKey(byte[] payloadKey, String operation) {
    requirePayloadKey(payloadKey, operation);
    return Arrays.copyOfRange(payloadKey, 1, payloadKey.length);
  }

  static Locator decodeLocator(UnifiedArchiveColumnFamily columnFamily, byte[] rowKey,
      byte[] locator, String operation) {
    requireRowKey(rowKey);
    tableId(columnFamily);
    if (locator == null || locator.length != LOCATOR_BYTES
        || locator[0] != LOCATOR_VERSION) {
      throw new ArchiveException(operation + ": temporal locator encoding is invalid");
    }
    long linkedTxNum = longAt(locator, LINK_OFFSET);
    requireLinkedTxNum(linkedTxNum);
    int payloadBytes = intAt(locator, LENGTH_OFFSET);
    if (payloadBytes <= 0 || payloadBytes > MAX_STORED_PAYLOAD_BYTES) {
      throw new ArchiveException(operation + ": temporal payload length is invalid: "
          + payloadBytes);
    }
    return new Locator(
        linkedTxNum, payloadBytes,
        Arrays.copyOfRange(locator, DIGEST_OFFSET, LOCATOR_BYTES));
  }

  static DecodedRow decode(UnifiedArchiveColumnFamily columnFamily, byte[] rowKey,
      Locator locator, byte[] payload, String operation) {
    requireRowKey(rowKey);
    if (payload == null || payload.length != locator.payloadBytes) {
      throw new ArchiveException(operation + ": temporal payload length mismatch");
    }
    byte[] expected = digest(columnFamily, rowKey, locator.linkedTxNum, payload);
    if (!MessageDigest.isEqual(expected, locator.payloadDigest)) {
      throw new ArchiveException(operation + ": temporal payload digest mismatch");
    }
    return new DecodedRow(payload, locator.linkedTxNum);
  }

  private static byte[] digest(UnifiedArchiveColumnFamily columnFamily, byte[] rowKey,
      long linkedTxNum, byte[] payload) {
    MessageDigest digest = DIGEST.get();
    digest.reset();
    digest.update(HASH_DOMAIN);
    digest.update(tableId(columnFamily));
    updateInt(digest, rowKey.length);
    digest.update(rowKey);
    updateLong(digest, linkedTxNum);
    updateInt(digest, payload.length);
    digest.update(payload);
    return digest.digest();
  }

  private static byte tableId(UnifiedArchiveColumnFamily columnFamily) {
    if (columnFamily == UnifiedArchiveColumnFamily.HISTORY) {
      return HISTORY_TABLE;
    }
    if (columnFamily == UnifiedArchiveColumnFamily.LATEST) {
      return LATEST_TABLE;
    }
    if (columnFamily == UnifiedArchiveColumnFamily.CHANGESET) {
      return CHANGESET_TABLE;
    }
    if (columnFamily == UnifiedArchiveColumnFamily.COMMITMENT) {
      return ANCHOR_TABLE;
    }
    throw new ArchiveException("archive temporal integrity does not support column family "
        + (columnFamily == null ? "null" : columnFamily.getName()));
  }

  private static void requireRowKey(byte[] rowKey) {
    if (rowKey == null || rowKey.length == 0) {
      throw new ArchiveException("archive temporal locator row key is required");
    }
  }

  private static void requirePayloadKey(byte[] payloadKey, String operation) {
    if (payloadKey == null || payloadKey.length <= 1) {
      throw new ArchiveException(operation + ": temporal payload key is invalid");
    }
  }

  private static void requirePayload(byte[] payload) {
    if (payload == null || payload.length == 0) {
      throw new ArchiveException("archive temporal payload is required");
    }
    if (payload.length > MAX_STORED_PAYLOAD_BYTES) {
      throw new ArchiveException("archive temporal payload exceeds stored-value limit "
          + MAX_STORED_PAYLOAD_BYTES + ": payloadBytes=" + payload.length);
    }
  }

  private static void requireLinkedTxNum(long linkedTxNum) {
    if (linkedTxNum < NO_HISTORY_TX_NUM) {
      throw new ArchiveException("archive temporal integrity linked txNum is invalid");
    }
  }

  private static MessageDigest newDigest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new ArchiveException("archive temporal integrity digest is unavailable", e);
    }
  }

  private static void updateInt(MessageDigest digest, int value) {
    digest.update((byte) (value >>> 24));
    digest.update((byte) (value >>> 16));
    digest.update((byte) (value >>> 8));
    digest.update((byte) value);
  }

  private static void updateLong(MessageDigest digest, long value) {
    for (int shift = 56; shift >= 0; shift -= 8) {
      digest.update((byte) (value >>> shift));
    }
  }

  private static void putInt(byte[] output, int offset, int value) {
    output[offset] = (byte) (value >>> 24);
    output[offset + 1] = (byte) (value >>> 16);
    output[offset + 2] = (byte) (value >>> 8);
    output[offset + 3] = (byte) value;
  }

  private static void putLong(byte[] output, int offset, long value) {
    for (int i = 0; i < Long.BYTES; i++) {
      output[offset + i] = (byte) (value >>> (56 - i * 8));
    }
  }

  private static int intAt(byte[] input, int offset) {
    return (input[offset] & 0xff) << 24
        | (input[offset + 1] & 0xff) << 16
        | (input[offset + 2] & 0xff) << 8
        | input[offset + 3] & 0xff;
  }

  private static long longAt(byte[] input, int offset) {
    long value = 0L;
    for (int i = 0; i < Long.BYTES; i++) {
      value = (value << 8) | (input[offset + i] & 0xffL);
    }
    return value;
  }

  static final class Locator {

    private final long linkedTxNum;
    private final int payloadBytes;
    private final byte[] payloadDigest;

    private Locator(long linkedTxNum, int payloadBytes, byte[] payloadDigest) {
      this.linkedTxNum = linkedTxNum;
      this.payloadBytes = payloadBytes;
      this.payloadDigest = payloadDigest;
    }

    long linkedTxNum() {
      return linkedTxNum;
    }

    int payloadBytes() {
      return payloadBytes;
    }
  }

  static final class DecodedRow {

    private final byte[] payload;
    private final long linkedTxNum;

    private DecodedRow(byte[] payload, long linkedTxNum) {
      this.payload = payload;
      this.linkedTxNum = linkedTxNum;
    }

    long linkedTxNum() {
      return linkedTxNum;
    }

    DomainValueView domainValue() {
      return new DomainValueView(payload);
    }

    byte[] payload() {
      return Arrays.copyOf(payload, payload.length);
    }

    /** Internal immutable view; callers must pass it only to APIs that copy synchronously. */
    byte[] payloadView() {
      return payload;
    }

    int payloadBytes() {
      return payload.length;
    }

    void updatePayloadDigest(MessageDigest digest) {
      digest.update(payload);
    }

    boolean payloadEquals(byte[] other) {
      return Arrays.equals(payload, other);
    }

    boolean payloadEquals(DecodedRow other) {
      return other != null && Arrays.equals(payload, other.payload);
    }
  }

  static final class DomainValueView {

    private final byte[] payload;

    private DomainValueView(byte[] payload) {
      this.payload = payload;
    }

    org.tron.core.archive.codec.DomainValue decode() {
      return ArchiveTemporalCodec.decodeValue(payload, 0, payload.length);
    }
  }
}
