package org.tron.core.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;
import org.tron.common.BaseMethodTest;
import org.tron.common.crypto.ECKey;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.LocalWitnesses;
import org.tron.common.utils.PublicMethod;
import org.tron.common.utils.ReflectUtils;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.ChainBaseManager;
import org.tron.core.Wallet;
import org.tron.core.archive.ArchiveExecutionContextHolder;
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveSource;
import org.tron.core.archive.DefaultArchiveService;
import org.tron.core.archive.capture.ArchiveCaptureEngine;
import org.tron.core.archive.capture.ArchiveCaptureHolder;
import org.tron.core.archive.temporal.InMemoryArchiveTemporalStore;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.WitnessCapsule;
import org.tron.core.config.args.Args;
import org.tron.core.consensus.ConsensusService;
import org.tron.core.services.jsonrpc.ArchiveJsonRpcStateAdapter;
import org.tron.protos.Protocol;

/**
 * Integration test for the L2 Manager archive lifecycle hooks with archive ENABLED. The default
 * (disabled) path is a no-op and exercised by every other Manager test; this one injects an enabled
 * {@link DefaultArchiveService} so the begin/commit/abort/unwind hooks are actually invoked.
 */
public class ManagerArchiveLifecycleTest extends BaseMethodTest {

  private static final AtomicInteger PORT = new AtomicInteger(0);
  private final String privateKey = PublicMethod.getRandomPrivateKey();
  private ChainBaseManager chainManager;
  private DefaultArchiveService archiveService;

  @Override
  protected void afterInit() {
    Args.getInstance().setNodeListenPort(11000 + PORT.incrementAndGet());
    BlockGenerate.setManager(dbManager);
    context.getBean(ConsensusService.class).start();
    chainManager = dbManager.getChainBaseManager();

    LocalWitnesses localWitnesses = new LocalWitnesses();
    localWitnesses.setPrivateKeys(Arrays.asList(privateKey));
    localWitnesses.initWitnessAccountAddress(null, true);
    Args.setLocalWitnesses(localWitnesses);

    byte[] address = PublicMethod.getAddressByteByPrivateKey(privateKey);
    ByteString addressByte = ByteString.copyFrom(address);
    WitnessCapsule witnessCapsule = new WitnessCapsule(addressByte);
    chainManager.getWitnessStore().put(addressByte.toByteArray(), witnessCapsule);
    chainManager.addWitness(addressByte);
    AccountCapsule accountCapsule =
        new AccountCapsule(Protocol.Account.newBuilder().setAddress(addressByte).build());
    chainManager.getAccountStore().put(addressByte.toByteArray(), accountCapsule);

    // Inject an ENABLED archive service so the Manager hooks become active for this test.
    archiveService = new DefaultArchiveService(true);
    ReflectUtils.setFieldValue(dbManager, "archiveService", archiveService);
  }

  private BlockCapsule signedEmptyBlock() {
    BlockCapsule block = new BlockCapsule(
        1,
        Sha256Hash.wrap(chainManager.getGenesisBlockId().getByteString()),
        1,
        ByteString.copyFrom(ECKey.fromPrivate(
            ByteArray.fromHexString(Args.getLocalWitnesses().getPrivateKey())).getAddress()));
    block.setMerkleRoot();
    block.sign(ByteArray.fromHexString(Args.getLocalWitnesses().getPrivateKey()));
    return block;
  }

  @Test
  public void pushBlockAllocatesTxNumRangeAndEraseUnwinds() throws Exception {
    BlockCapsule block = signedEmptyBlock();

    dbManager.pushBlock(block);
    assertEquals(1, chainManager.getDynamicPropertiesStore().getLatestBlockHeaderNumber());

    // Archive committed a range for block 1: prepare + finalize system tx, no user tx.
    ArchiveBlockRange range = archiveService.getTxNumIndex().getBlockRange(1)
        .orElseThrow(() -> new AssertionError("archive has no committed range for block 1"));
    assertEquals(1, range.getBlockNum());
    assertEquals(0, range.getUserTxCount());
    assertTrue("finalize txNum must follow prepare txNum",
        range.getFinalizeTxNum() > range.getPrepareTxNum());
    // Execution context is clean once the block has been applied and committed.
    assertFalse(ArchiveExecutionContextHolder.get().current().isPresent());

    // eraseBlock unwinds the archive range after canonical fastPop.
    while (chainManager.getDynamicPropertiesStore().getLatestBlockHeaderNumber() > 0) {
      dbManager.eraseBlock();
    }
    assertFalse(archiveService.getTxNumIndex().getBlockRange(1).isPresent());
    // eraseBlock also unwinds the temporal store: block 1's drained changes are gone.
    assertEquals(0,
        ((InMemoryArchiveTemporalStore) archiveService.getTemporalStore()).changeCount());
  }

  @After
  public void clearArchiveCaptureHolder() {
    // The enabled DefaultArchiveService installs a process-wide capture engine; clear it so other
    // framework tests do not see a stale engine.
    ArchiveCaptureHolder.clear();
  }

  @Test
  public void archiveCapturesAndDrainsDomainWritesToTemporalStore() throws Exception {
    assertTrue("an enabled archive service must install a capture engine",
        ArchiveCaptureHolder.isActive());
    ArchiveCaptureEngine captureEngine = archiveService.getCaptureEngine();

    dbManager.pushBlock(signedEmptyBlock());

    // commitBlock drains the per-block capture buffer into the temporal store and clears it.
    assertTrue("capture buffer must be drained at commit", captureEngine.records().isEmpty());
    InMemoryArchiveTemporalStore temporalStore =
        (InMemoryArchiveTemporalStore) archiveService.getTemporalStore();
    assertTrue("block apply must persist at least one domain change to the temporal store",
        temporalStore.changeCount() > 0);
  }

  @Test
  public void historicalGetBalanceReadsCapturedAccountThroughAdapter() throws Exception {
    // Drive one captured block that writes an account balance, exactly as the Manager would:
    // beginBlock -> BLOCK_PREPARE (account write captured) -> BLOCK_FINALIZE -> commitBlock (drains
    // into the temporal store and commits the txNum range).
    BlockCapsule block = signedEmptyBlock();
    byte[] addr = new byte[21];
    addr[0] = 0x41;
    for (int i = 1; i < addr.length; i++) {
      addr[i] = (byte) i;
    }
    long expectedBalance = 123_456_789L;

    archiveService.beginBlock(block, ArchiveSource.NORMAL);
    archiveService.beginSystemTx(block, ArchivePhase.BLOCK_PREPARE);
    chainManager.getAccountStore().put(addr, new AccountCapsule(Protocol.Account.newBuilder()
        .setAddress(ByteString.copyFrom(addr)).setBalance(expectedBalance).build()));
    archiveService.endTx();
    archiveService.beginSystemTx(block, ArchivePhase.BLOCK_FINALIZE);
    archiveService.endTx();
    archiveService.commitBlock(block);

    // The resolver resolves a quantity tag without touching the wallet, then fetches the block by
    // number; a mock supplies that single block so the test needs no live block store.
    Wallet wallet = mock(Wallet.class);
    when(wallet.getBlockByNum(block.getNum())).thenReturn(block.getInstance());
    ArchiveJsonRpcStateAdapter adapter = new ArchiveJsonRpcStateAdapter(wallet, archiveService);

    String blockTag = "0x" + Long.toHexString(block.getNum());
    // Captured account: the historical balance is read back end-to-end (resolver -> reader ->
    // render) at the block's finalize txNum.
    assertEquals(ByteArray.toJsonHex(expectedBalance),
        adapter.getBalance(ByteArray.toHexString(addr), blockTag));

    // An account never written in this block is MISSING -> rendered as zero, not an archive gap.
    byte[] unknown = new byte[21];
    unknown[0] = 0x41;
    unknown[20] = (byte) 0xff;
    assertEquals(ByteArray.toJsonHex(0L),
        adapter.getBalance(ByteArray.toHexString(unknown), blockTag));
  }
}
