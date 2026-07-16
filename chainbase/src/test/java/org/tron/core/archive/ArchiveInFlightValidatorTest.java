package org.tron.core.archive;

import static org.junit.Assert.assertThrows;

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
        valid.getRecords());

    assertThrows(ArchiveException.class,
        () -> ArchiveInFlightValidator.validate(block, catalog, dynamicKeyPolicy));
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
