package org.tron.core.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.tron.common.BaseMethodTest;
import org.tron.common.utils.ReflectUtils;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.domain.DefaultArchiveDomainCatalog;
import org.tron.core.archive.domain.DefaultArchiveDomainRegistry;
import org.tron.core.archive.query.ArchiveQueryLimits;
import org.tron.core.archive.temporal.InMemoryArchiveTemporalStore;
import org.tron.core.archive.txnum.InMemoryArchiveTxNumIndex;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.config.args.Args;
import org.tron.core.db2.ISession;
import org.tron.core.db2.core.Chainbase;
import org.tron.core.db2.core.SnapshotManager;
import org.tron.core.store.DynamicPropertiesStore;
import org.tron.protos.Protocol.Account;

/** Real canonical snapshots/checkpoints with the Manager journal/publication ordering. */
public class ArchiveBatchFlushIntegrationTest extends BaseMethodTest {

  private final byte[] accountKey = new byte[21];
  private final InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
  private final InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
  private DefaultArchiveService archive;
  private SnapshotManager snapshots;

  @Override
  protected void beforeContext() {
    Args.getInstance().getStorage().setCheckpointVersion(2);
    Args.getInstance().getStorage().setCheckpointSync(true);
  }

  @Override
  protected void afterInit() {
    snapshots = context.getBean(SnapshotManager.class);
    while (snapshots.size() > 0) {
      snapshots.pop();
    }
    snapshots.enable();
    snapshots.setUnChecked(false);
    snapshots.setMaxSize(2);
    snapshots.setMaxFlushCount(3);
    accountKey[0] = 0x41;
    accountKey[20] = 0x01;
    chainBaseManager.getAccountStore().put(accountKey, new AccountCapsule(
        Account.newBuilder().setAddress(ByteString.copyFrom(accountKey)).setBalance(100).build()));
    ArchivePublisherConfig publisher = new ArchivePublisherConfig(
        true, true, 3, 16, 128L * 1024L * 1024L, 256L * 1024L * 1024L,
        1_000_000L, 2_000_000L, 0L, 0L, 1_000L, 1_000L);
    archive = new DefaultArchiveService(true, index, ArchiveExecutionContextHolder.get(),
        temporal, new InMemoryArchiveInFlightStore(), new DefaultArchiveDomainRegistry(),
        new DefaultArchiveDomainCatalog(), ArchiveLifecycle.Phase.RUNNING,
        ArchiveQueryLimits.unlimited(), publisher, () -> { });
    ReflectUtils.setFieldValue(dbManager, "archiveService", archive);
  }

  @Override
  protected void beforeDestroy() {
    if (archive != null) {
      archive.close();
    }
  }

  @Test
  public void unpublishableSoftTailAllowsNextSessionToFlushCanonicalBatch() throws Exception {
    BoundedArchivePublisher publisher = ReflectUtils.getFieldValue(archive, "publisher");
    for (long height = 1; height <= 9; height++) {
      appendBlock(height);
      assertTrue(publisher.awaitIdle(5, TimeUnit.SECONDS));
      assertEquals(height, chainBaseManager.getAccountStore().get(accountKey).getBalance());
      if (height == 5) {
        assertEquals(2, snapshots.getPendingFlushCount());
        assertFalse(archive.hasCommittedBlock(1));
        assertEquals(100, rootBalance());
      }
      if (height == 6 || height == 9) {
        long durableHeight = height - 3;
        assertEquals(0, snapshots.getPendingFlushCount());
        assertEquals(durableHeight, rootBalance());
        assertEquals(durableHeight, index.getLastArchivedBlock());
        assertFalse(archive.hasCommittedBlock(durableHeight + 1));
        for (long archived = 1; archived <= durableHeight; archived++) {
          long txNum = index.getBlockRange(archived).get().getFinalizeTxNum();
          byte[] value = temporal.getAsOf(ArchiveDomain.ACCOUNT, accountKey, txNum)
              .get().getValue();
          assertEquals(archived, Account.parseFrom(value).getBalance());
        }
      }
    }
    archive.validateAvailable();
  }

  private void appendBlock(long height) {
    DynamicPropertiesStore properties = chainBaseManager.getDynamicPropertiesStore();
    BlockCapsule block = new BlockCapsule(height, properties.getLatestBlockHeaderHash(),
        height * 3_000L, ByteString.EMPTY);
    archive.awaitWriterCapacity();
    try (ArchiveWorkLease writer = archive.acquireWriterLease();
        ArchiveMutationLease mutation = archive.acquireMutationReadLease()) {
      writer.start();
      archive.beginBlock(block, ArchiveSource.NORMAL);
      ArchiveJournalToken token;
      try (ISession session = snapshots.buildSession()) {
        archive.beginSystemTx(block, ArchivePhase.BLOCK_PREPARE);
        archive.endTx();
        archive.beginSystemTx(block, ArchivePhase.BLOCK_FINALIZE);
        AccountCapsule account = chainBaseManager.getAccountStore().get(accountKey);
        account.setBalance(height);
        chainBaseManager.getAccountStore().put(accountKey, account);
        properties.saveLatestBlockHeaderNumber(height);
        properties.saveLatestBlockHeaderHash(block.getBlockId().getByteString());
        properties.saveLatestSolidifiedBlockNum(height);
        chainBaseManager.getBlockStore().put(block.getBlockId().getBytes(), block);
        chainBaseManager.getBlockIndexStore().put(block.getBlockId());
        archive.endTx();
        token = archive.commitBlockJournaled(block, 0);
        session.commit();
      }
      archive.acknowledgeCanonicalCommit(token);
      ReflectUtils.invokeMethod(dbManager, "publishArchiveSolidifiedOrFailStop",
          new Class<?>[]{BlockCapsule.class, String.class}, block, "batch-flush test");
    }
  }

  private long rootBalance() throws Exception {
    Chainbase database = (Chainbase) chainBaseManager.getAccountStore().getRevokingDB();
    return Account.parseFrom(database.getHead().getRoot().get(accountKey)).getBalance();
  }
}
