package org.tron.core.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.archive.txnum.ArchiveTxPosition;

public class RocksDbArchiveInFlightStoreTest {

  private static final int TOKEN_ENCODED_SIZE = Long.BYTES
      + Integer.BYTES + ArchiveBlockRange.BLOCK_HASH_LENGTH
      + Integer.BYTES + ArchiveJournalToken.GENERATION_NONCE_LENGTH
      + Integer.BYTES + ArchiveBlockRange.SCHEMA_CHECKSUM_LENGTH;
  private static final int BLOCK_HASH_LENGTH_OFFSET =
      2 + TOKEN_ENCODED_SIZE + 5 * Long.BYTES + Integer.BYTES + 1;
  private static final int POSITION_COUNT_OFFSET = BLOCK_HASH_LENGTH_OFFSET
      + Integer.BYTES + ArchiveBlockRange.BLOCK_HASH_LENGTH
      + Integer.BYTES + ArchiveBlockRange.SCHEMA_CHECKSUM_LENGTH;
  private static final int SYSTEM_POSITION_ENCODED_SIZE = 2 * Long.BYTES + 2 + Integer.BYTES
      + 2 * Integer.BYTES;
  private static final int RECORD_COUNT_OFFSET = POSITION_COUNT_OFFSET + Integer.BYTES
      + 2 * SYSTEM_POSITION_ENCODED_SIZE;

  private Path dir;
  private RocksDbArchiveInFlightStore store;

  @Before
  public void setUp() throws IOException {
    dir = Files.createTempDirectory("archive-inflight-test");
    store = new RocksDbArchiveInFlightStore(dir.toString());
  }

  @After
  public void tearDown() {
    if (store != null) {
      store.close();
    }
    deleteRecursively(dir.toFile());
  }

  @Test
  public void putBlockSurvivesRestart() {
    ArchiveInFlightBlock block = block(7);
    store.putBlock(block);

    store.close();
    store = new RocksDbArchiveInFlightStore(dir.toString());

    assertEquals(1, store.loadBlocks().size());
    ArchiveInFlightBlock loaded = store.loadBlocks().get(0);
    assertEquals(7, loaded.getRange().getBlockNum());
    assertEquals(block.getJournalToken(), loaded.getJournalToken());
    assertEquals(ArchiveInFlightBlock.JournalState.JOURNALED, loaded.getJournalState());
  }

  @Test
  public void compactAcknowledgementSurvivesRestartWithoutRewritingJournalPayload()
      throws Exception {
    ArchiveInFlightBlock block = block(7);
    store.putBlock(block);
    store.close();
    store = null;
    byte[] journalBefore = readRaw(ArchiveInFlightCodec.blockKey(7));

    store = new RocksDbArchiveInFlightStore(dir.toString());
    store.acknowledgeBlock(block.withJournalState(
        ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED));
    store.close();
    store = null;

    assertArrayEquals(journalBefore, readRaw(ArchiveInFlightCodec.blockKey(7)));
    assertNotNull(readRaw(ArchiveInFlightCodec.acknowledgementKey(7)));

    store = new RocksDbArchiveInFlightStore(dir.toString());

    ArchiveInFlightBlock loaded = store.loadBlocks().get(0);
    assertEquals(block.getJournalToken(), loaded.getJournalToken());
    assertEquals(ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED,
        loaded.getJournalState());
  }

  @Test
  public void openRejectsCorruptBlockValue() throws Exception {
    closeForRawEdit();
    putRaw(ArchiveInFlightCodec.blockKey(7), new byte[] {2});

    assertOpenFails("decode failed");
  }

  @Test
  public void openRejectsOversizedByteLengthBeforeAllocation() throws Exception {
    byte[] encoded = ArchiveInFlightCodec.encodeBlock(block(7));
    setInt(encoded, BLOCK_HASH_LENGTH_OFFSET, Integer.MAX_VALUE);

    closeForRawEdit();
    putRaw(ArchiveInFlightCodec.blockKey(7), encoded);

    assertOpenFails("exceeds remaining value bytes");
  }

  @Test
  public void openRejectsOversizedPositionCountBeforeAllocation() throws Exception {
    byte[] encoded = ArchiveInFlightCodec.encodeBlock(block(7));
    setInt(encoded, POSITION_COUNT_OFFSET, Integer.MAX_VALUE);

    closeForRawEdit();
    putRaw(ArchiveInFlightCodec.blockKey(7), encoded);

    assertOpenFails("position count exceeds remaining value bytes");
  }

  @Test
  public void openRejectsOversizedRecordCountBeforeAllocation() throws Exception {
    byte[] encoded = ArchiveInFlightCodec.encodeBlock(block(7));
    setInt(encoded, RECORD_COUNT_OFFSET, Integer.MAX_VALUE);

    closeForRawEdit();
    putRaw(ArchiveInFlightCodec.blockKey(7), encoded);

    assertOpenFails("record count exceeds remaining value bytes");
  }

  @Test
  public void openRejectsBlockKeyValueMismatch() throws Exception {
    closeForRawEdit();
    putRaw(ArchiveInFlightCodec.blockKey(7),
        ArchiveInFlightCodec.encodeBlock(block(8)));

    assertOpenFails("key/value mismatch");
  }

  @Test
  public void openRejectsRecordPositionNotPersistedInBlock() throws Exception {
    ArchiveTxPosition wrongPosition = new ArchiveTxPosition(
        0, 7, ArchivePhase.BLOCK_FINALIZE, ArchiveSource.NORMAL, -1, null);
    ArchiveInFlightBlock block = blockWithRecord(7, new ArchiveChangeRecord(
        wrongPosition, ArchiveDomain.ACCOUNT, address(),
        DomainValue.tombstone(), DomainValue.tombstone()));

    closeForRawEdit();
    putRaw(ArchiveInFlightCodec.blockKey(7), ArchiveInFlightCodec.encodeBlock(block));

    assertOpenFails("record position does not match persisted position");
  }

  @Test
  public void openRejectsRecordWithInvalidCanonicalKey() throws Exception {
    ArchiveInFlightBlock block = blockWithRecord(7, new ArchiveChangeRecord(
        preparePosition(7), ArchiveDomain.ACCOUNT, new byte[] {0x41},
        DomainValue.tombstone(), DomainValue.tombstone()));

    closeForRawEdit();
    putRaw(ArchiveInFlightCodec.blockKey(7), ArchiveInFlightCodec.encodeBlock(block));

    assertOpenFails("key must be 21 bytes");
  }

  @Test
  public void openRejectsTombstoneWithPayload() throws Exception {
    ArchiveInFlightBlock block = blockWithRecord(7, new ArchiveChangeRecord(
        preparePosition(7), ArchiveDomain.ACCOUNT, address(),
        DomainValue.present(new byte[] {0x55, 0x66, 0x77}), DomainValue.tombstone()));
    byte[] encoded = ArchiveInFlightCodec.encodeBlock(block);
    int flagOffset = indexOf(encoded,
        new byte[] {0, 0, 0, 0, 3, 0x55, 0x66, 0x77});
    assertTrue(flagOffset >= 0);
    encoded[flagOffset] = 1;

    closeForRawEdit();
    putRaw(ArchiveInFlightCodec.blockKey(7), encoded);

    assertOpenFails("tombstone value must be empty");
  }

  @Test
  public void openRejectsExcludedDynamicPropertyRecord() throws Exception {
    ArchiveInFlightBlock block = blockWithRecord(7, new ArchiveChangeRecord(
        preparePosition(7), ArchiveDomain.DYNAMIC_PROPERTIES,
        "state_flag".getBytes(StandardCharsets.US_ASCII),
        DomainValue.tombstone(), DomainValue.present(new byte[] {1})));

    closeForRawEdit();
    putRaw(ArchiveInFlightCodec.blockKey(7), ArchiveInFlightCodec.encodeBlock(block));

    assertOpenFails("dynamic property is not archived");
  }

  @Test
  public void openRejectsUserTxPositionAfterFinalize() throws Exception {
    ArchiveBlockRange range = new ArchiveBlockRange(
        7, 0, 2, 0, 1, blockHash(7), 1, ArchiveSource.NORMAL, checksum(7));
    ArchiveInFlightBlock block = new ArchiveInFlightBlock(range, Arrays.asList(
        preparePosition(7),
        new ArchiveTxPosition(1, 7, ArchivePhase.BLOCK_FINALIZE, ArchiveSource.NORMAL, -1,
            null),
        new ArchiveTxPosition(2, 7, ArchivePhase.USER_TX, ArchiveSource.NORMAL, 0, txId(7))),
        Collections.emptyList());

    closeForRawEdit();
    putRaw(ArchiveInFlightCodec.blockKey(7), ArchiveInFlightCodec.encodeBlock(block));

    assertOpenFails("finalize txNum must be last");
  }

  @Test
  public void openRejectsPositionBlockHashMismatch() throws Exception {
    ArchiveBlockRange range = range(7);
    ArchiveInFlightBlock block = new ArchiveInFlightBlock(range, Arrays.asList(
        new ArchiveTxPosition(0, 7, ArchivePhase.BLOCK_PREPARE, ArchiveSource.NORMAL, -1,
            null, blockHash(99)),
        finalizePosition(7)), Collections.emptyList());

    closeForRawEdit();
    putRaw(ArchiveInFlightCodec.blockKey(7), ArchiveInFlightCodec.encodeBlock(block));

    assertOpenFails("position block hash mismatch");
  }

  @Test
  public void openRejectsDuplicateUserTxId() throws Exception {
    ArchiveBlockRange range = new ArchiveBlockRange(
        7, 0, 3, 0, 3, blockHash(7), 2, ArchiveSource.NORMAL, checksum(7));
    ArchiveInFlightBlock block = new ArchiveInFlightBlock(range, Arrays.asList(
        preparePosition(7),
        new ArchiveTxPosition(1, 7, ArchivePhase.USER_TX, ArchiveSource.NORMAL, 0, txId(7)),
        new ArchiveTxPosition(2, 7, ArchivePhase.USER_TX, ArchiveSource.NORMAL, 1, txId(7)),
        new ArchiveTxPosition(3, 7, ArchivePhase.BLOCK_FINALIZE, ArchiveSource.NORMAL, -1,
            null)), Collections.emptyList());

    closeForRawEdit();
    putRaw(ArchiveInFlightCodec.blockKey(7), ArchiveInFlightCodec.encodeBlock(block));

    assertOpenFails("duplicate user txId");
  }

  @Test
  public void openRejectsDuplicateChangesetRecords() throws Exception {
    ArchiveBlockRange range = new ArchiveBlockRange(
        7, 0, 2, 0, 2, blockHash(7), 1, ArchiveSource.NORMAL, checksum(7));
    ArchiveTxPosition user = new ArchiveTxPosition(
        1, 7, ArchivePhase.USER_TX, ArchiveSource.NORMAL, 0, txId(7));
    ArchiveInFlightBlock block = new ArchiveInFlightBlock(range, Arrays.asList(
        preparePosition(7), user,
        new ArchiveTxPosition(2, 7, ArchivePhase.BLOCK_FINALIZE, ArchiveSource.NORMAL, -1,
            null)), Arrays.asList(
        new ArchiveChangeRecord(user, ArchiveDomain.ACCOUNT, address(),
            DomainValue.tombstone(), DomainValue.tombstone()),
        new ArchiveChangeRecord(user, ArchiveDomain.ACCOUNT, address(),
            DomainValue.tombstone(), DomainValue.tombstone())));

    closeForRawEdit();
    putRaw(ArchiveInFlightCodec.blockKey(7), ArchiveInFlightCodec.encodeBlock(block));

    assertOpenFails("duplicate changeset row");
  }

  @Test
  public void openRejectsUnknownPrefix() throws Exception {
    closeForRawEdit();
    putRaw(new byte[] {0x7f}, new byte[] {1});

    assertOpenFails("unknown key prefix");
  }

  @Test
  public void openRejectsOrphanAcknowledgement() throws Exception {
    ArchiveInFlightBlock block = block(7);
    closeForRawEdit();
    putRaw(ArchiveInFlightCodec.acknowledgementKey(7),
        ArchiveInFlightCodec.encodeAcknowledgement(block.getJournalToken()));

    assertOpenFails("has no journal block 7");
  }

  @Test
  public void openRejectsOrphanJournalToken() throws Exception {
    ArchiveInFlightBlock block = block(7);
    closeForRawEdit();
    putRaw(ArchiveInFlightCodec.tokenKey(7),
        ArchiveInFlightCodec.encodeAcknowledgement(block.getJournalToken()));

    assertOpenFails("token has no journal block 7");
  }

  @Test
  public void openRejectsJournalTokenThatDoesNotMatchBlock() throws Exception {
    ArchiveInFlightBlock block = block(7);
    ArchiveInFlightBlock other = block(8);
    ArchiveJournalToken mismatched = new ArchiveJournalToken(
        7L, other.getJournalToken().getBlockHash(),
        other.getJournalToken().getGenerationNonce(),
        other.getJournalToken().getSchemaChecksum());
    closeForRawEdit();
    putRaw(ArchiveInFlightCodec.blockKey(7), ArchiveInFlightCodec.encodeBlock(block));
    putRaw(ArchiveInFlightCodec.tokenKey(7),
        ArchiveInFlightCodec.encodeAcknowledgement(mismatched));

    assertOpenFails("journal token mismatch for block 7");
  }

  @Test
  public void deleteRemovesJournalAndAcknowledgementTogether() throws Exception {
    ArchiveInFlightBlock block = block(7);
    store.putBlock(block);
    store.acknowledgeBlock(block.withJournalState(
        ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED));
    store.deleteBlock(7);
    store.close();
    store = null;

    assertNull(readRaw(ArchiveInFlightCodec.blockKey(7)));
    assertNull(readRaw(ArchiveInFlightCodec.acknowledgementKey(7)));
    store = new RocksDbArchiveInFlightStore(dir.toString());
    assertTrue(store.loadBlocks().isEmpty());
  }

  @Test
  public void openRejectsNegativeBlockKey() throws Exception {
    closeForRawEdit();
    putRaw(rawBlockKey(-1), new byte[] {1});

    assertOpenFails("non-negative");
  }

  @Test
  public void encodeRejectsNegativeBlockNumber() {
    ArchiveException ex = assertThrows(ArchiveException.class,
        () -> ArchiveInFlightCodec.encodeBlock(block(-1)));

    assertTrue(ex.getMessage().contains("non-negative"));
  }

  private void closeForRawEdit() {
    store.close();
    store = null;
  }

  private void putRaw(byte[] key, byte[] value) throws RocksDBException {
    RocksDB.loadLibrary();
    try (Options options = new Options().setCreateIfMissing(false);
        RocksDB rawDb = RocksDB.open(options, dir.toString())) {
      rawDb.put(key, value);
    }
  }

  private byte[] readRaw(byte[] key) throws RocksDBException {
    RocksDB.loadLibrary();
    try (Options options = new Options().setCreateIfMissing(false);
        RocksDB rawDb = RocksDB.openReadOnly(options, dir.toString())) {
      return rawDb.get(key);
    }
  }

  private void assertOpenFails(String expectedMessage) {
    ArchiveException ex = assertThrows(ArchiveException.class, () -> {
      try (RocksDbArchiveInFlightStore ignored =
          new RocksDbArchiveInFlightStore(dir.toString())) {
      }
    });
    assertTrue(ex.getMessage().contains(expectedMessage));
  }

  private static ArchiveInFlightBlock block(long blockNum) {
    return new ArchiveInFlightBlock(range(blockNum), positions(blockNum),
        Collections.emptyList());
  }

  private static ArchiveInFlightBlock blockWithRecord(long blockNum, ArchiveChangeRecord record) {
    return new ArchiveInFlightBlock(range(blockNum), positions(blockNum),
        Collections.singletonList(record));
  }

  private static ArchiveBlockRange range(long blockNum) {
    return new ArchiveBlockRange(
        blockNum, 0, 1, 0, 1, blockHash(blockNum), 0, ArchiveSource.NORMAL,
        checksum((int) blockNum));
  }

  private static java.util.List<ArchiveTxPosition> positions(long blockNum) {
    return Arrays.asList(preparePosition(blockNum), finalizePosition(blockNum));
  }

  private static ArchiveTxPosition preparePosition(long blockNum) {
    return new ArchiveTxPosition(0, blockNum, ArchivePhase.BLOCK_PREPARE,
        ArchiveSource.NORMAL, -1, null);
  }

  private static ArchiveTxPosition finalizePosition(long blockNum) {
    return new ArchiveTxPosition(1, blockNum, ArchivePhase.BLOCK_FINALIZE,
        ArchiveSource.NORMAL, -1, null);
  }

  private static byte[] address() {
    byte[] address = new byte[21];
    address[0] = 0x41;
    return address;
  }

  private static byte[] txId(int seed) {
    byte[] txId = new byte[ArchiveBlockRange.BLOCK_HASH_LENGTH];
    txId[ArchiveBlockRange.BLOCK_HASH_LENGTH - 1] = (byte) seed;
    return txId;
  }

  private static byte[] blockHash(long seed) {
    byte[] hash = new byte[ArchiveBlockRange.BLOCK_HASH_LENGTH];
    hash[ArchiveBlockRange.BLOCK_HASH_LENGTH - 1] = (byte) seed;
    return hash;
  }

  private static byte[] checksum(int seed) {
    byte[] checksum = new byte[ArchiveBlockRange.SCHEMA_CHECKSUM_LENGTH];
    checksum[ArchiveBlockRange.SCHEMA_CHECKSUM_LENGTH - 1] = (byte) seed;
    return checksum;
  }

  private static byte[] rawBlockKey(long blockNum) {
    byte[] key = new byte[1 + Long.BYTES];
    key[0] = 0x40;
    for (int i = Long.BYTES - 1; i >= 0; i--) {
      key[1 + i] = (byte) blockNum;
      blockNum >>>= Byte.SIZE;
    }
    return key;
  }

  private static void setInt(byte[] target, int offset, int value) {
    target[offset] = (byte) (value >>> 24);
    target[offset + 1] = (byte) (value >>> 16);
    target[offset + 2] = (byte) (value >>> 8);
    target[offset + 3] = (byte) value;
  }

  private static int indexOf(byte[] target, byte[] pattern) {
    for (int i = 0; i <= target.length - pattern.length; i++) {
      boolean matched = true;
      for (int j = 0; j < pattern.length; j++) {
        if (target[i + j] != pattern[j]) {
          matched = false;
          break;
        }
      }
      if (matched) {
        return i;
      }
    }
    return -1;
  }

  private static void deleteRecursively(File f) {
    File[] children = f.listFiles();
    if (children != null) {
      for (File c : children) {
        deleteRecursively(c);
      }
    }
    f.delete();
  }
}
