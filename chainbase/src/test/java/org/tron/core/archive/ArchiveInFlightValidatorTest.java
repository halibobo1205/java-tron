package org.tron.core.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.domain.ArchiveDomainCatalog;
import org.tron.core.archive.domain.ArchiveSchemaChecksum;
import org.tron.core.archive.domain.DefaultArchiveDomainCatalog;
import org.tron.core.archive.domain.DefaultArchiveDomainRegistry;
import org.tron.core.archive.domain.DynamicKeyPolicy;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.archive.txnum.ArchiveTxPosition;
import org.tron.protos.Protocol.Account;

public class ArchiveInFlightValidatorTest {

  private final ArchiveDomainCatalog catalog = new DefaultArchiveDomainCatalog();
  private final byte[] schemaChecksum =
      ArchiveSchemaChecksum.of(new DefaultArchiveDomainRegistry(), catalog);
  private final DynamicKeyPolicy dynamicKeyPolicy = new DynamicKeyPolicy();

  @Test
  public void validBlockPasses() {
    ArchiveInFlightValidator.validate(validBlock(), catalog, dynamicKeyPolicy);
  }

  @Test
  public void journalStateTransitionRetainsCachedBacklogEstimate() {
    ArchiveInFlightBlock journaled = validBlock();

    ArchiveInFlightBlock committed = journaled.withJournalState(
        ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED);

    assertEquals(journaled.estimatedRetainedBytes(), committed.estimatedRetainedBytes());
    assertEquals(journaled.encodedBlockBytes(), committed.encodedBlockBytes());
    assertEquals(journaled.temporalPublicationRetainedBytes(),
        committed.temporalPublicationRetainedBytes());
    assertEquals(journaled.temporalPublicationMutations(),
        committed.temporalPublicationMutations());
    assertEquals(ArchiveInFlightCodec.encodeBlock(committed).length,
        committed.encodedBlockBytes());
  }

  @Test
  public void decodePreflightRejectsInvalidFixedTokenHashLength() {
    byte[] encoded = ArchiveInFlightCodec.encodeBlock(validBlock());
    int tokenHashLengthOffset = 2 + Long.BYTES;
    ByteBuffer.wrap(encoded, tokenHashLengthOffset, Integer.BYTES)
        .putInt(ArchiveBlockRange.BLOCK_HASH_LENGTH + 1);

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> ArchiveInFlightCodec.decodeBlock(encoded, Long.MAX_VALUE, Long.MAX_VALUE));

    assertTrue(failure.getMessage().contains("journal token block hash must be 32 bytes"));
  }

  @Test
  public void decodeRejectsNonCanonicalDomainValueBoolean() {
    ArchiveInFlightBlock valid = validBlock();
    ArchiveChangeRecord codeRecord = new ArchiveChangeRecord(
        valid.getPositions().get(1), ArchiveDomain.CODE, accountKey(),
        DomainValue.present(new byte[0]), DomainValue.present(new byte[] {0x7f}));
    ArchiveInFlightBlock block = new ArchiveInFlightBlock(
        valid.getRange(), valid.getPositions(), Collections.singletonList(codeRecord));
    byte[] encoded = ArchiveInFlightCodec.encodeBlock(block);
    int previousDeletedFlagOffset = encoded.length - 11;
    assertEquals(0, encoded[previousDeletedFlagOffset]);
    encoded[previousDeletedFlagOffset] = 2;

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> ArchiveInFlightCodec.decodeBlock(encoded, Long.MAX_VALUE, Long.MAX_VALUE));

    assertTrue(failure.getMessage().contains("must be encoded as 0 or 1"));
  }

  @Test
  public void valueVersionThreeReferencesPositionOnceByTxNum() {
    ArchiveInFlightBlock block = validBlock();
    ArchiveChangeRecord record = block.getRecords().get(0);
    ArchiveInFlightBlock withoutRecords = new ArchiveInFlightBlock(
        block.getRange(), block.getPositions(), Collections.emptyList());

    byte[] encoded = ArchiveInFlightCodec.encodeBlock(block);
    long expectedRecordBytes = Long.BYTES + Integer.BYTES
        + Integer.BYTES + record.canonicalKeySize()
        + Byte.BYTES + Integer.BYTES + record.getPrevValue().size()
        + Byte.BYTES + Integer.BYTES + record.getValue().size();
    assertEquals(3, encoded[0]);
    assertEquals(expectedRecordBytes,
        encoded.length - ArchiveInFlightCodec.encodeBlock(withoutRecords).length);

    ArchiveInFlightBlock decoded = ArchiveInFlightCodec.decodeBlock(encoded);
    assertSame(decoded.getPositions().get(1), decoded.getRecords().get(0).getPosition());
  }

  @Test
  public void decodePreflightCountsTemporaryImmutableValueCopy() {
    ArchiveInFlightBlock valid = validBlock();
    ArchiveChangeRecord codeRecord = new ArchiveChangeRecord(
        valid.getPositions().get(1), ArchiveDomain.CODE, accountKey(),
        DomainValue.present(new byte[32]), DomainValue.present(new byte[64]));
    ArchiveInFlightBlock block = new ArchiveInFlightBlock(
        valid.getRange(), valid.getPositions(), Collections.singletonList(codeRecord));
    byte[] encoded = ArchiveInFlightCodec.encodeBlock(block);
    long decodeTransientBytes = codeRecord.canonicalKeySize()
        + Math.max(codeRecord.getPrevValue().size(), codeRecord.getValue().size());

    assertThrows(ArchiveJournalLimitException.class,
        () -> ArchiveInFlightCodec.decodeBlock(encoded, Long.MAX_VALUE,
            block.estimatedRetainedBytes() + decodeTransientBytes - 1L));
    ArchiveInFlightCodec.decodeBlock(encoded, Long.MAX_VALUE,
        block.estimatedRetainedBytes() + decodeTransientBytes);
  }

  @Test
  public void decodePreflightCountsLargestValidationWorkspaceAfterFullBlockRetention() {
    ArchiveInFlightBlock valid = validBlock();
    ArchiveChangeRecord largeFirst = new ArchiveChangeRecord(
        valid.getPositions().get(1), ArchiveDomain.CODE, accountKey(),
        DomainValue.present(new byte[32]), DomainValue.present(new byte[4_096]));
    byte[] secondKey = accountKey();
    secondKey[20] = 1;
    ArchiveChangeRecord smallLast = new ArchiveChangeRecord(
        valid.getPositions().get(1), ArchiveDomain.CODE, secondKey,
        DomainValue.present(new byte[0]), DomainValue.present(new byte[] {1}));
    ArchiveInFlightBlock block = new ArchiveInFlightBlock(
        valid.getRange(), valid.getPositions(), Arrays.asList(largeFirst, smallLast));
    byte[] encoded = ArchiveInFlightCodec.encodeBlock(block);
    long largestValidationWorkspace = largeFirst.canonicalKeySize()
        + Math.max(largeFirst.getPrevValue().size(), largeFirst.getValue().size());
    long requiredBytes = block.estimatedRetainedBytes() + largestValidationWorkspace;

    assertThrows(ArchiveJournalLimitException.class,
        () -> ArchiveInFlightCodec.decodeBlock(
            encoded, Long.MAX_VALUE, requiredBytes - 1L));
    ArchiveInFlightCodec.decodeBlock(encoded, Long.MAX_VALUE, requiredBytes);
  }

  @Test
  public void decodePreflightCountsDynamicKeyStringValidationWorkspace() {
    ArchiveInFlightBlock valid = validBlock();
    byte[] dynamicKey = new byte[128];
    Arrays.fill(dynamicKey, (byte) 'A');
    ArchiveChangeRecord record = new ArchiveChangeRecord(
        valid.getPositions().get(1), ArchiveDomain.DYNAMIC_PROPERTIES, dynamicKey,
        DomainValue.tombstone(), DomainValue.present(new byte[] {1}));
    ArchiveInFlightBlock block = new ArchiveInFlightBlock(
        valid.getRange(), valid.getPositions(), Collections.singletonList(record));
    byte[] encoded = ArchiveInFlightCodec.encodeBlock(block);
    long requiredBytes = block.estimatedRetainedBytes() + 3L * dynamicKey.length;

    assertThrows(ArchiveJournalLimitException.class,
        () -> ArchiveInFlightCodec.decodeBlock(
            encoded, Long.MAX_VALUE, requiredBytes - 1L));
    ArchiveInFlightCodec.decodeBlock(encoded, Long.MAX_VALUE, requiredBytes);
  }

  @Test
  public void encodingRejectsRecordWhosePositionWouldBeRebound() {
    ArchiveInFlightBlock valid = validBlock();
    ArchiveTxPosition user = valid.getPositions().get(1);
    ArchiveTxPosition mismatched = new ArchiveTxPosition(
        user.getTxNum(), user.getBlockNum(), ArchivePhase.BLOCK_FINALIZE,
        user.getSource(), -1, null);
    ArchiveChangeRecord record = new ArchiveChangeRecord(
        mismatched, ArchiveDomain.ACCOUNT, accountKey(),
        DomainValue.tombstone(), account(1));

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> new ArchiveInFlightBlock(
            valid.getRange(), valid.getPositions(), Collections.singletonList(record)));

    assertTrue(failure.getMessage().contains(
        "record position does not match persisted position"));
  }

  @Test
  public void rejectsRangeSpanThatDoesNotMatchUserCount() {
    ArchiveBlockRange range = range(0L, 0L, 1L, 1);
    ArchiveInFlightBlock block = new ArchiveInFlightBlock(range,
        Arrays.asList(system(0L, ArchivePhase.BLOCK_PREPARE),
            system(1L, ArchivePhase.BLOCK_FINALIZE)),
        Collections.emptyList());

    assertThrows(ArchiveException.class,
        () -> ArchiveInFlightValidator.validate(block, catalog, dynamicKeyPolicy));
  }

  @Test
  public void rejectsReservedMaximumTxCoordinateBeforeAnyClosedRangeLoop() {
    ArchiveBlockRange range = range(
        0L, Long.MAX_VALUE - 1L, Long.MAX_VALUE, 0);
    ArchiveInFlightBlock block = new ArchiveInFlightBlock(range,
        Arrays.asList(
            system(Long.MAX_VALUE - 1L, ArchivePhase.BLOCK_PREPARE),
            system(Long.MAX_VALUE, ArchivePhase.BLOCK_FINALIZE)),
        Collections.emptyList());

    assertThrows(ArchiveException.class,
        () -> ArchiveInFlightValidator.validate(block, catalog, dynamicKeyPolicy));
  }

  @Test
  public void rejectsDuplicateUserTransactionIds() {
    byte[] duplicate = hash(9);
    ArchiveBlockRange range = range(0L, 0L, 3L, 2);
    ArchiveInFlightBlock block = new ArchiveInFlightBlock(range,
        Arrays.asList(system(0L, ArchivePhase.BLOCK_PREPARE),
            user(1L, 0, duplicate), user(2L, 1, duplicate),
            system(3L, ArchivePhase.BLOCK_FINALIZE)),
        Collections.emptyList());

    assertThrows(ArchiveException.class,
        () -> ArchiveInFlightValidator.validate(block, catalog, dynamicKeyPolicy));
  }

  @Test
  public void rejectsUserPositionOutsideItsDeterministicTxNum() {
    ArchiveBlockRange range = range(0L, 0L, 2L, 1);
    ArchiveInFlightBlock block = new ArchiveInFlightBlock(range,
        Arrays.asList(system(0L, ArchivePhase.BLOCK_PREPARE),
            user(2L, 0, hash(9)), system(2L, ArchivePhase.BLOCK_FINALIZE)),
        Collections.emptyList());

    assertThrows(ArchiveException.class,
        () -> ArchiveInFlightValidator.validate(block, catalog, dynamicKeyPolicy));
  }

  @Test
  public void rejectsPositionWhoseBlockHashDiffersFromRange() {
    ArchiveInFlightBlock valid = validBlock();
    ArchiveTxPosition user = valid.getPositions().get(1);
    ArchiveTxPosition mismatched = new ArchiveTxPosition(
        user.getTxNum(), user.getBlockNum(), user.getPhase(), user.getSource(),
        user.getTxIndex(), user.getTxId(), hash(99));
    ArchiveInFlightBlock block = new ArchiveInFlightBlock(valid.getRange(),
        Arrays.asList(valid.getPositions().get(0), mismatched, valid.getPositions().get(2)),
        Collections.emptyList());

    assertThrows(ArchiveException.class,
        () -> ArchiveInFlightValidator.validate(block, catalog, dynamicKeyPolicy));
  }

  @Test
  public void rejectsCompleteButOutOfOrderPositionList() {
    ArchiveInFlightBlock valid = validBlock();
    ArchiveInFlightBlock block = new ArchiveInFlightBlock(valid.getRange(),
        Arrays.asList(valid.getPositions().get(1), valid.getPositions().get(2),
            valid.getPositions().get(0)), Collections.emptyList());

    ArchiveException failure = assertThrows(ArchiveException.class,
        () -> ArchiveInFlightValidator.validate(block, catalog, dynamicKeyPolicy));

    assertTrue(failure.getMessage().contains("not in txNum order"));
  }

  @Test
  public void rejectsDuplicateChangesetRows() {
    ArchiveInFlightBlock valid = validBlock();
    ArchiveChangeRecord record = valid.getRecords().get(0);
    ArchiveInFlightBlock block = new ArchiveInFlightBlock(
        valid.getRange(), valid.getPositions(), Arrays.asList(record, record));

    assertThrows(ArchiveException.class,
        () -> ArchiveInFlightValidator.validate(block, catalog, dynamicKeyPolicy));
  }

  @Test
  public void rejectsInvalidCanonicalKey() {
    ArchiveInFlightBlock valid = validBlock();
    ArchiveChangeRecord record = new ArchiveChangeRecord(
        valid.getPositions().get(1), ArchiveDomain.ACCOUNT, new byte[20],
        DomainValue.tombstone(), account(1));
    ArchiveInFlightBlock block = new ArchiveInFlightBlock(
        valid.getRange(), valid.getPositions(), Collections.singletonList(record));

    assertThrows(ArchiveException.class,
        () -> ArchiveInFlightValidator.validate(block, catalog, dynamicKeyPolicy));
  }

  @Test
  public void proofBoundValidationDoesNotReparseCanonicalProtobuf() {
    ArchiveInFlightBlock valid = validBlock();
    ArchiveChangeRecord malformed = new ArchiveChangeRecord(
        valid.getPositions().get(1), ArchiveDomain.ACCOUNT, accountKey(),
        DomainValue.tombstone(), DomainValue.present(new byte[] {(byte) 0xff, 0x01}));
    ArchiveInFlightBlock block = new ArchiveInFlightBlock(
        valid.getRange(), valid.getPositions(), Collections.singletonList(malformed));

    assertThrows(ArchiveException.class,
        () -> ArchiveInFlightValidator.validateForWrite(block, catalog, dynamicKeyPolicy));

    ArchiveInFlightValidator.validateProofBound(block, catalog, dynamicKeyPolicy);
  }

  @Test
  public void rejectsExcludedDynamicProperty() {
    ArchiveInFlightBlock valid = validBlock();
    ArchiveChangeRecord record = new ArchiveChangeRecord(
        valid.getPositions().get(1), ArchiveDomain.DYNAMIC_PROPERTIES,
        "ABI_MOVE_DONE".getBytes(StandardCharsets.US_ASCII),
        DomainValue.tombstone(), DomainValue.present(new byte[] {1}));
    ArchiveInFlightBlock block = new ArchiveInFlightBlock(
        valid.getRange(), valid.getPositions(), Collections.singletonList(record));

    assertThrows(ArchiveException.class,
        () -> ArchiveInFlightValidator.validate(block, catalog, dynamicKeyPolicy));
  }

  private ArchiveInFlightBlock validBlock() {
    ArchiveBlockRange range = range(0L, 0L, 2L, 1);
    ArchiveTxPosition prepare = system(0L, ArchivePhase.BLOCK_PREPARE);
    ArchiveTxPosition user = user(1L, 0, hash(9));
    ArchiveTxPosition finalize = system(2L, ArchivePhase.BLOCK_FINALIZE);
    ArchiveChangeRecord record = new ArchiveChangeRecord(
        user, ArchiveDomain.ACCOUNT, accountKey(),
        DomainValue.tombstone(), account(1));
    return new ArchiveInFlightBlock(
        range, Arrays.asList(prepare, user, finalize), Collections.singletonList(record));
  }

  private ArchiveBlockRange range(long blockNum, long firstTxNum, long lastTxNum,
      int userTxCount) {
    return new ArchiveBlockRange(
        blockNum, firstTxNum, lastTxNum, firstTxNum, lastTxNum,
        hash(1), userTxCount, ArchiveSource.NORMAL, schemaChecksum);
  }

  private static ArchiveTxPosition system(long txNum, ArchivePhase phase) {
    return new ArchiveTxPosition(
        txNum, 0L, phase, ArchiveSource.NORMAL, -1, null);
  }

  private static ArchiveTxPosition user(long txNum, int txIndex, byte[] txId) {
    return new ArchiveTxPosition(
        txNum, 0L, ArchivePhase.USER_TX, ArchiveSource.NORMAL, txIndex, txId);
  }

  private static DomainValue account(long balance) {
    return DomainValue.present(
        Account.newBuilder().setBalance(balance).build().toByteArray());
  }

  private static byte[] accountKey() {
    byte[] key = new byte[21];
    key[0] = 0x41;
    return key;
  }

  private static byte[] hash(int seed) {
    byte[] hash = new byte[ArchiveBlockRange.BLOCK_HASH_LENGTH];
    hash[hash.length - 1] = (byte) seed;
    return hash;
  }
}
