package org.tron.core.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.Test;
import org.tron.common.BaseMethodTest;
import org.tron.common.crypto.ECKey;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.LocalWitnesses;
import org.tron.common.utils.PublicMethod;
import org.tron.common.utils.ReflectUtils;
import org.tron.common.utils.Sha256Hash;
import org.tron.common.utils.Utils;
import org.tron.consensus.dpos.DposSlot;
import org.tron.core.archive.domain.ArchiveDomainCatalog;
import org.tron.core.archive.domain.ArchiveDomainRegistry;
import org.tron.core.archive.domain.ArchiveSchemaChecksum;
import org.tron.core.archive.domain.DefaultArchiveDomainCatalog;
import org.tron.core.archive.domain.DefaultArchiveDomainRegistry;
import org.tron.core.archive.query.ArchiveQueryLimits;
import org.tron.core.archive.reader.ArchiveReadThrough;
import org.tron.core.archive.temporal.UnifiedArchiveTemporalStore;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.archive.txnum.ArchiveTxNumIndex;
import org.tron.core.archive.txnum.ArchiveTxPosition;
import org.tron.core.archive.txnum.UnifiedArchiveTxNumIndex;
import org.tron.core.archive.unified.UnifiedArchiveDb;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.WitnessCapsule;
import org.tron.core.config.args.Args;
import org.tron.core.consensus.ConsensusService;
import org.tron.core.db.BlockGenerate;
import org.tron.core.exception.ValidateSignatureException;
import org.tron.protos.Protocol;
import org.tron.protos.Protocol.Block;

/**
 * Exercises Manager's real fork switch and switch-back recovery with persistent archive enabled.
 */
public class ManagerArchiveSwitchForkTest extends BaseMethodTest {

  private static final AtomicInteger PORT = new AtomicInteger();
  private static final long BASE_TIME = 1_533_529_947_843L;

  private final String localWitnessKey = PublicMethod.getRandomPrivateKey();
  private final BlockGenerate blockGenerate = new BlockGenerate();

  private DposSlot dposSlot;
  private DefaultArchiveService archiveService;
  private RecordingInFlightStore recordingInFlightStore;
  private Path unifiedDbPath;
  private byte[] schemaChecksum;

  @Override
  protected void afterInit() {
    Args.getInstance().setNodeListenPort(12_000 + PORT.incrementAndGet());
    BlockGenerate.setManager(dbManager);
    dposSlot = context.getBean(DposSlot.class);
    context.getBean(ConsensusService.class).start();

    LocalWitnesses localWitnesses = new LocalWitnesses();
    localWitnesses.setPrivateKeys(Arrays.asList(localWitnessKey));
    localWitnesses.initWitnessAccountAddress(null, true);
    Args.setLocalWitnesses(localWitnesses);

    byte[] address = PublicMethod.getAddressByteByPrivateKey(localWitnessKey);
    ByteString addressBytes = ByteString.copyFrom(address);
    chainBaseManager.getWitnessStore().put(address,
        new WitnessCapsule(addressBytes));
    chainBaseManager.addWitness(addressBytes);
    chainBaseManager.getAccountStore().put(address,
        new AccountCapsule(Protocol.Account.newBuilder().setAddress(addressBytes).build()));

    ArchiveDomainRegistry registry = new DefaultArchiveDomainRegistry();
    ArchiveDomainCatalog catalog = new DefaultArchiveDomainCatalog();
    schemaChecksum = ArchiveSchemaChecksum.of(registry, catalog);
    unifiedDbPath = temporaryFolder.getRoot().toPath().resolve("manager-switch-fork-unified");
    UnifiedArchiveDb unifiedDb = UnifiedArchiveDb.initialize(unifiedDbPath, schemaChecksum);
    UnifiedArchiveTxNumIndex txNumIndex =
        new UnifiedArchiveTxNumIndex(unifiedDb, schemaChecksum, false, false);
    UnifiedArchiveTemporalStore temporalStore =
        new UnifiedArchiveTemporalStore(unifiedDb, catalog);
    UnifiedArchiveInFlightStore inFlightStore =
        new UnifiedArchiveInFlightStore(unifiedDb, catalog);
    recordingInFlightStore = new RecordingInFlightStore(inFlightStore);
    UnifiedArchiveBackend backend =
        new UnifiedArchiveBackend(unifiedDb, txNumIndex, temporalStore);
    archiveService = new DefaultArchiveService(true, txNumIndex,
        ArchiveExecutionContextHolder.get(), temporalStore, recordingInFlightStore,
        registry, catalog, ArchiveReadThrough.NONE, ArchiveLifecycle.Phase.RUNNING,
        ArchiveQueryLimits.unlimited(), ArchivePublisherConfig.synchronous(), () -> { }, backend);
    ReflectUtils.setFieldValue(dbManager, "archiveService", archiveService);
  }

  @Override
  protected void beforeDestroy() {
    if (archiveService != null) {
      archiveService.close();
    }
  }

  @Test
  public void switchForkFailureRecapturesRecoveryBranchWithFreshPersistentTxNums()
      throws Exception {
    String bootstrapKey = PublicMethod.getRandomPrivateKey();
    byte[] bootstrapPrivateKey = ByteArray.fromHexString(bootstrapKey);
    ByteString bootstrapAddress = ByteString.copyFrom(
        ECKey.fromPrivate(bootstrapPrivateKey).getAddress());
    chainBaseManager.getAccountStore().put(bootstrapAddress.toByteArray(),
        new AccountCapsule(Protocol.Account.newBuilder()
            .setAddress(bootstrapAddress).build()));
    chainBaseManager.getWitnessScheduleStore().saveActiveWitnesses(new ArrayList<>());
    chainBaseManager.addWitness(bootstrapAddress);
    chainBaseManager.getWitnessStore().put(bootstrapAddress.toByteArray(),
        new WitnessCapsule(bootstrapAddress));

    Block bootstrapProto = blockGenerate.getSignedBlock(
        bootstrapAddress, BASE_TIME, bootstrapPrivateKey);
    BlockCapsule bootstrap = new BlockCapsule(bootstrapProto);
    dbManager.pushBlock(bootstrap);

    Map<ByteString, String> witnessKeys = addTestWitnesses();
    witnessKeys.put(bootstrapAddress, bootstrapKey);
    long base = chainBaseManager.getDynamicPropertiesStore().getLatestBlockHeaderNumber();

    BlockCapsule parent = signedEmptyBlock(BASE_TIME + 3_000L, base + 1L,
        chainBaseManager.getDynamicPropertiesStore().getLatestBlockHeaderHash().getByteString(),
        witnessKeys, true);
    dbManager.pushBlock(parent);

    // Build the competing branch while the witness schedule still reflects the common ancestor.
    // The old branch crosses a maintenance boundary, so constructing these blocks afterwards
    // would sign them against the wrong fork-local schedule.
    BlockCapsule forkOne = signedEmptyBlock(BASE_TIME + 6_001L, base + 2L,
        parent.getBlockId().getByteString(), witnessKeys, true);
    BlockCapsule forkTwo = signedEmptyBlock(BASE_TIME + 9_001L, base + 3L,
        forkOne.getBlockId().getByteString(), witnessKeys, true);
    BlockCapsule invalidForkHead = signedEmptyBlock(BASE_TIME + 12_000L, base + 4L,
        forkTwo.getBlockId().getByteString(), witnessKeys, false);

    BlockCapsule oldOne = signedEmptyBlock(BASE_TIME + 6_000L, base + 2L,
        parent.getBlockId().getByteString(), witnessKeys, true);
    dbManager.pushBlock(oldOne);
    BlockCapsule oldTwo = signedEmptyBlock(BASE_TIME + 9_000L, base + 3L,
        oldOne.getBlockId().getByteString(), witnessKeys, true);
    dbManager.pushBlock(oldTwo);
    assertEquals(oldTwo.getBlockId(),
        chainBaseManager.getDynamicPropertiesStore().getLatestBlockHeaderHash());

    ArchiveInFlightBlock originalOldOne = journalFor(oldOne);
    ArchiveInFlightBlock originalOldTwo = journalFor(oldTwo);
    assertEquals(ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED,
        originalOldOne.getJournalState());
    assertEquals(ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED,
        originalOldTwo.getJournalState());

    dbManager.pushBlock(forkOne);
    dbManager.pushBlock(forkTwo);

    assertThrows(ValidateSignatureException.class,
        () -> dbManager.pushBlock(invalidForkHead));

    assertEquals("switch-back recovery must restore the old canonical head",
        oldTwo.getBlockId(),
        chainBaseManager.getDynamicPropertiesStore().getLatestBlockHeaderHash());
    assertEquals(1L, recordingInFlightStore.deletedCount(originalOldOne.getJournalToken()));
    assertEquals(1L, recordingInFlightStore.deletedCount(originalOldTwo.getJournalToken()));
    assertDeletedCanonicalJournal(originalOldOne);
    assertDeletedCanonicalJournal(originalOldTwo);

    assertReplayCaptured(forkOne);
    assertReplayCaptured(forkTwo);
    assertFalse("the signature-invalid fork head must never reach archive commit",
        recordingInFlightStore.wasPut(invalidForkHead, ArchiveSource.REPLAY));

    ArchiveInFlightBlock recoveredOldOne = journalFor(oldOne);
    ArchiveInFlightBlock recoveredOldTwo = journalFor(oldTwo);
    assertRecoveredJournal(originalOldOne, recoveredOldOne);
    assertRecoveredJournal(originalOldTwo, recoveredOldTwo);

    archiveService.publishSolidifiedBlocks(oldTwo.getNum());
    archiveService.close();

    try (UnifiedArchiveTxNumIndex reopened = new UnifiedArchiveTxNumIndex(
        UnifiedArchiveDb.open(unifiedDbPath, schemaChecksum), schemaChecksum, true, false)) {
      assertPersistentCanonicalRanges(reopened,
          Arrays.asList(bootstrap, parent, oldOne, oldTwo),
          Arrays.asList(ArchiveSource.NORMAL, ArchiveSource.NORMAL,
              ArchiveSource.RECOVERY, ArchiveSource.RECOVERY),
          forkOne, forkTwo);
    }
  }

  private Map<ByteString, String> addTestWitnesses() {
    chainBaseManager.getWitnesses().clear();
    Map<ByteString, String> result = new HashMap<>();
    for (int i = 0; i < 2; i++) {
      ECKey key = new ECKey(Utils.getRandom());
      ByteString address = ByteString.copyFrom(key.getAddress());
      String privateKey = ByteArray.toHexString(key.getPrivKey().toByteArray());
      chainBaseManager.getWitnessStore().put(address.toByteArray(),
          new WitnessCapsule(address));
      chainBaseManager.addWitness(address);
      chainBaseManager.getAccountStore().put(address.toByteArray(),
          new AccountCapsule(Protocol.Account.newBuilder().setAddress(address).build()));
      result.put(address, privateKey);
    }
    return result;
  }

  private BlockCapsule signedEmptyBlock(long timestamp, long number, ByteString parentHash,
      Map<ByteString, String> witnessKeys, boolean validSignature) {
    ByteString witnessAddress = dposSlot.getScheduledWitness(dposSlot.getSlot(timestamp));
    BlockCapsule block = new BlockCapsule(number, Sha256Hash.wrap(parentHash), timestamp,
        witnessAddress);
    block.generatedByMyself = true;
    block.setMerkleRoot();
    String signingKey = validSignature
        ? witnessKeys.get(witnessAddress) : PublicMethod.getRandomPrivateKey();
    block.sign(ByteArray.fromHexString(signingKey));
    return block;
  }

  private ArchiveInFlightBlock journalFor(BlockCapsule block) {
    return recordingInFlightStore.loadBlocks().stream()
        .filter(candidate -> sameBlock(candidate, block))
        .findFirst()
        .orElseThrow(() -> new AssertionError(
            "missing archive journal for block " + block.getNum()));
  }

  private void assertDeletedCanonicalJournal(ArchiveInFlightBlock original) {
    List<ArchiveInFlightBlock> deleted =
        recordingInFlightStore.deleted(original.getJournalToken());
    assertEquals(1, deleted.size());
    assertEquals(ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED,
        deleted.get(0).getJournalState());
  }

  private void assertReplayCaptured(BlockCapsule block) {
    List<ArchiveInFlightBlock> replayed =
        recordingInFlightStore.puts(block, ArchiveSource.REPLAY);
    assertEquals("each successfully replayed fork block must be captured once", 1,
        replayed.size());
    assertEquals(2, replayed.get(0).getPositions().size());
    replayed.get(0).getPositions().forEach(
        position -> assertEquals(ArchiveSource.REPLAY, position.getSource()));
  }

  private void assertRecoveredJournal(ArchiveInFlightBlock original,
      ArchiveInFlightBlock recovered) {
    assertEquals(ArchiveSource.RECOVERY, recovered.getRange().getSource());
    assertEquals(ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED,
        recovered.getJournalState());
    assertNotEquals("recovery must create a new journal generation",
        original.getJournalToken(), recovered.getJournalToken());
    assertEquals(original.getRange().getFirstTxNum(), recovered.getRange().getFirstTxNum());
    assertEquals(original.getRange().getLastTxNum(), recovered.getRange().getLastTxNum());
    recovered.getPositions().forEach(
        position -> assertEquals(ArchiveSource.RECOVERY, position.getSource()));
  }

  private void assertPersistentCanonicalRanges(ArchiveTxNumIndex index,
      List<BlockCapsule> canonicalBlocks, List<ArchiveSource> expectedSources,
      BlockCapsule forkOne, BlockCapsule forkTwo) {
    assertEquals(canonicalBlocks.get(0).getNum(), index.getFirstArchivedBlock());
    assertEquals(canonicalBlocks.get(canonicalBlocks.size() - 1).getNum(),
        index.getLastArchivedBlock());

    long expectedTxNum = 0L;
    for (int i = 0; i < canonicalBlocks.size(); i++) {
      BlockCapsule block = canonicalBlocks.get(i);
      ArchiveSource source = expectedSources.get(i);
      ArchiveBlockRange range = index.getBlockRange(block.getNum())
          .orElseThrow(() -> new AssertionError(
              "missing persistent range for block " + block.getNum()));

      assertEquals("persistent ranges must be gap-free",
          expectedTxNum, range.getFirstTxNum());
      assertEquals(range.getFirstTxNum(), range.getPrepareTxNum());
      assertEquals(range.getFirstTxNum() + 1L, range.getFinalizeTxNum());
      assertEquals(range.getFinalizeTxNum(), range.getLastTxNum());
      assertEquals(0, range.getUserTxCount());
      assertEquals(source, range.getSource());
      assertArrayEquals(block.getBlockId().getBytes(), range.getBlockHash());

      for (long txNum = range.getFirstTxNum(); txNum <= range.getLastTxNum(); txNum++) {
        assertTrue("missing persistent position for txNum " + txNum,
            index.getPosition(txNum).isPresent());
        ArchiveTxPosition position = index.getPosition(txNum).get();
        assertEquals(txNum, position.getTxNum());
        assertEquals(block.getNum(), position.getBlockNum());
        assertEquals(source, position.getSource());
        assertArrayEquals(block.getBlockId().getBytes(), position.getBlockHash());
        assertEquals(txNum == range.getPrepareTxNum()
                ? ArchivePhase.BLOCK_PREPARE : ArchivePhase.BLOCK_FINALIZE,
            position.getPhase());
      }
      expectedTxNum = range.getLastTxNum() + 1L;
    }
    assertEquals(expectedTxNum, index.getNextTxNum());

    ArchiveBlockRange recoveredOne = index.getBlockRange(forkOne.getNum()).get();
    ArchiveBlockRange recoveredTwo = index.getBlockRange(forkTwo.getNum()).get();
    assertFalse(Arrays.equals(forkOne.getBlockId().getBytes(), recoveredOne.getBlockHash()));
    assertFalse(Arrays.equals(forkTwo.getBlockId().getBytes(), recoveredTwo.getBlockHash()));
  }

  private static boolean sameBlock(ArchiveInFlightBlock journal, BlockCapsule block) {
    return journal.getRange().getBlockNum() == block.getNum()
        && Arrays.equals(journal.getRange().getBlockHash(), block.getBlockId().getBytes());
  }

  private static final class RecordingInFlightStore implements ArchiveInFlightStore {

    private final ArchiveInFlightStore delegate;
    private final List<ArchiveInFlightBlock> puts = new ArrayList<>();
    private final List<ArchiveInFlightBlock> deletes = new ArrayList<>();

    private RecordingInFlightStore(ArchiveInFlightStore delegate) {
      this.delegate = delegate;
    }

    @Override
    public List<ArchiveInFlightBlock> loadBlocks() {
      return delegate.loadBlocks();
    }

    @Override
    public void forEachBlock(Consumer<ArchiveInFlightBlock> consumer) {
      delegate.forEachBlock(consumer);
    }

    @Override
    public void putBlock(ArchiveInFlightBlock block) {
      puts.add(block);
      delegate.putBlock(block);
    }

    @Override
    public void acknowledgeBlock(ArchiveInFlightBlock block) {
      delegate.acknowledgeBlock(block);
    }

    @Override
    public void acknowledgeBlock(ArchiveJournalToken token) {
      delegate.acknowledgeBlock(token);
    }

    @Override
    public void deleteBlock(long blockNum) {
      delegate.loadBlocks().stream()
          .filter(block -> block.getRange().getBlockNum() == blockNum)
          .findFirst()
          .ifPresent(deletes::add);
      delegate.deleteBlock(blockNum);
    }

    @Override
    public long usableSpaceBytes() {
      return delegate.usableSpaceBytes();
    }

    @Override
    public void close() {
      delegate.close();
    }

    private long deletedCount(ArchiveJournalToken token) {
      return deleted(token).size();
    }

    private List<ArchiveInFlightBlock> deleted(ArchiveJournalToken token) {
      List<ArchiveInFlightBlock> matches = new ArrayList<>();
      for (ArchiveInFlightBlock block : deletes) {
        if (block.getJournalToken().equals(token)) {
          matches.add(block);
        }
      }
      return matches;
    }

    private List<ArchiveInFlightBlock> puts(BlockCapsule block, ArchiveSource source) {
      List<ArchiveInFlightBlock> matches = new ArrayList<>();
      for (ArchiveInFlightBlock candidate : puts) {
        if (candidate.getRange().getSource() == source && sameBlock(candidate, block)) {
          matches.add(candidate);
        }
      }
      return matches;
    }

    private boolean wasPut(BlockCapsule block, ArchiveSource source) {
      return !puts(block, source).isEmpty();
    }
  }
}
