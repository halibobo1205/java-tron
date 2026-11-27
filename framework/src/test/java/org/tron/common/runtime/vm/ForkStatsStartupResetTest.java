package org.tron.common.runtime.vm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.tron.common.application.ApplicationFactory;
import org.tron.common.application.TronApplicationContext;
import org.tron.common.utils.ForkController;
import org.tron.common.utils.PeerManagerStateResetter;
import org.tron.core.ChainBaseManager;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.config.DefaultConfig;
import org.tron.core.config.Parameter.ForkBlockVersionEnum;
import org.tron.core.db.Manager;
import org.tron.core.exception.ContractValidateException;
import org.tron.core.store.DynamicPropertiesStore;
import org.tron.core.utils.ProposalUtil;
import org.tron.core.utils.ProposalUtil.ProposalType;
import org.tron.core.vm.config.ConfigLoader;
import org.tron.core.vm.config.VMConfig;
import org.tron.protos.Protocol.Block;
import org.tron.protos.Protocol.BlockHeader;

/**
 * A binary that does not know fork version V still writes its slot bit into stats[V] for every vV
 * block, but never clears it at maintenance (reset only walks its own fork enum), so the per-slot
 * bits accumulate a union across maintenance periods that can exceed the activation threshold while
 * no single period ever reached it. When such a node is upgraded to a binary that does know V, that
 * stale union would be read directly and activate fork V (and accept proposals that canonical nodes
 * reject) before the threshold is met in a single period.
 *
 * <p>Manager.init() calls resetStatsAboveLatestVersion() once per binary upgrade (guarded by a
 * local FORK_STATS_TRACKED_VERSION marker) to drop that inherited union. These tests drive a real
 * restart: the Spring context is closed and reopened on the same database directory, so init()
 * runs exactly as it does on a node restart.
 *
 * <p>Two forks are covered. Fork 39 is VERSION_4_8_2_4 (rate 70 -> ceil(0.7 * 27) = 19 of 27
 * slots), gating ALLOW_OPTIMIZE_TVM_STORAGE_KEY (102); its stale union comes from 4.8.2.3 nodes.
 * Fork 40 is VERSION_4_8_3 (rate 80 -> ceil(0.8 * 27) = 22 of 27 slots), gating CLOSE_EXCHANGE
 * (101); its stale union comes from 4.8.2.4 nodes. Mainnet latestVersion is 38.
 */
public class ForkStatsStartupResetTest extends VMTestBase {

  private static final int SR_COUNT = 27;
  private static final int FORK_39 = ForkBlockVersionEnum.VERSION_4_8_2_4.getValue();
  private static final int FORK_39_THRESHOLD = 19; // ceil(70 * 27 / 100)
  private static final int FORK_40 = ForkBlockVersionEnum.VERSION_4_8_3.getValue();
  private static final int FORK_40_THRESHOLD = 22; // ceil(80 * 27 / 100)
  private static final byte[] MARKER_KEY = "FORK_STATS_TRACKED_VERSION".getBytes();

  private VMConfig.Snapshot savedGlobalConfig;
  private boolean savedConfigLoaderDisabled;
  private List<ByteString> witnesses;

  @Override
  protected void beforeContext() {
    savedGlobalConfig = (VMConfig.Snapshot) ReflectionTestUtils.getField(VMConfig.class,
        "globalSnapshot");
    VMConfig.setGlobalSnapshot(new VMConfig.Snapshot());
    savedConfigLoaderDisabled = ConfigLoader.disable;
    ConfigLoader.disable = false;
  }

  @Override
  protected void afterInit() {
    super.afterInit();
    long interval = properties().getMaintenanceTimeInterval();
    long forkTime = ((ForkBlockVersionEnum.VERSION_4_8_3.getHardForkTime() - 1)
        / interval + 1) * interval;
    properties().saveLatestBlockHeaderTimestamp(forkTime + 1);
    witnesses = new ArrayList<>();
    for (int i = 0; i < SR_COUNT; i++) {
      byte[] address = new byte[21];
      address[0] = 0x41;
      address[20] = (byte) (i + 1);
      witnesses.add(ByteString.copyFrom(address));
    }
    chainBaseManager.getWitnessScheduleStore().saveActiveWitnesses(witnesses);
    properties().saveLatestVersion(38);
    properties().statsByVersion(38, onesAt(SR_COUNT));
  }

  @Override
  protected void beforeDestroy() {
    VMConfig.setGlobalSnapshot(savedGlobalConfig);
    ConfigLoader.disable = savedConfigLoaderDisabled;
  }

  /**
   * Upgrade from 4.8.2.4: the inherited fork-40 union already clears the threshold, but the first
   * start of this binary zeros it, so fork 40 is not active and the proposal is rejected. The
   * already-activated lower version 38 is left untouched.
   */
  @Test
  public void upgradeFromOldBinaryClearsStaleUnion() {
    simulateOldBinaryUpgrade(FORK_40, onesAt(FORK_40_THRESHOLD));

    assertEquals(0, ones(FORK_40));
    assertEquals(SR_COUNT, properties().statsByVersion(FORK_40).length);
    assertFalse(fork().pass(ForkBlockVersionEnum.VERSION_4_8_3));
    assertThrows(ContractValidateException.class, () -> ProposalUtil.validator(
        properties(), fork(), ProposalType.CLOSE_EXCHANGE.getCode(), 1));
    assertArrayEquals(onesAt(SR_COUNT), properties().statsByVersion(38));
    assertTrue(fork().pass(ForkBlockVersionEnum.VERSION_4_8_2_3));
    assertEquals(38, properties().getLatestVersion());
  }

  /**
   * The mainnet case: a 4.8.2.3 node carries a fork-39 union of 22 slots while canonical nodes
   * count 18. Its first start on this binary zeros the union, so proposal 102 is rejected by the
   * fork gate, as it is on canonical nodes.
   */
  @Test
  public void upgradeFromOldBinaryClearsStaleFork39Union() {
    simulateOldBinaryUpgrade(FORK_39, onesAt(22));

    assertEquals(0, ones(FORK_39));
    assertFalse(fork().pass(ForkBlockVersionEnum.VERSION_4_8_2_4));
    ContractValidateException e = assertThrows(ContractValidateException.class,
        () -> ProposalUtil.validator(properties(), fork(),
            ProposalType.ALLOW_OPTIMIZE_TVM_STORAGE_KEY.getCode(), 1));
    assertEquals("Bad chain parameter id [ALLOW_OPTIMIZE_TVM_STORAGE_KEY]", e.getMessage());
    assertEquals(38, properties().getLatestVersion());
  }

  /**
   * A node upgraded from 4.8.2.4 also loses its legitimate fork-39 votes on first start, but the
   * v39 producers rewrite them as they take their next slots, so it is back in step within one
   * round and activates as soon as the threshold is met.
   */
  @Test
  public void fork39VotesRefillWithinOneRound() {
    simulateOldBinaryUpgrade(FORK_39, onesAt(FORK_39_THRESHOLD - 1));
    assertEquals(0, ones(FORK_39));

    for (int i = 0; i < FORK_39_THRESHOLD - 1; i++) {
      fork().update(block(FORK_39, witnesses.get(i)));
    }
    assertEquals(FORK_39_THRESHOLD - 1, ones(FORK_39));
    assertFalse(fork().pass(ForkBlockVersionEnum.VERSION_4_8_2_4));

    fork().update(block(FORK_39, witnesses.get(FORK_39_THRESHOLD - 1)));
    assertTrue(fork().pass(ForkBlockVersionEnum.VERSION_4_8_2_4));
  }

  /**
   * The startup reset cannot repair a node that already latched fork 39 from a stale union before
   * it ran this binary: latestVersion is 39, so the reset leaves it alone and proposal 102 is still
   * accepted locally. Such a node has to be resynced from a canonical snapshot.
   */
  @Test
  public void staleFork39LatchSurvivesRestart() throws ContractValidateException {
    properties().delete(MARKER_KEY);
    properties().saveLatestVersion(FORK_39);
    properties().statsByVersion(FORK_39, onesAt(SR_COUNT));

    restart();

    assertEquals(FORK_39, properties().getLatestVersion());
    assertTrue(fork().pass(ForkBlockVersionEnum.VERSION_4_8_2_4));
    ProposalUtil.validator(properties(), fork(),
        ProposalType.ALLOW_OPTIMIZE_TVM_STORAGE_KEY.getCode(), 1);
  }

  /**
   * After the one-time clear, a restart must not zero votes this binary has legitimately
   * accumulated in the current period: the marker suppresses the second clear, so the node keeps
   * its count and does not fall behind its peers.
   */
  @Test
  public void restartDoesNotReclearAccumulatedVotes() {
    simulateOldBinaryUpgrade(FORK_40, new byte[SR_COUNT]);
    // The upgraded binary accumulates this period's votes, one slot short of the threshold.
    properties().statsByVersion(FORK_40, onesAt(FORK_40_THRESHOLD - 1));

    restart();

    assertEquals(FORK_40_THRESHOLD - 1, ones(FORK_40));
    assertFalse(fork().pass(ForkBlockVersionEnum.VERSION_4_8_3));
  }

  /**
   * A node whose inherited union was cleared reaches activation once the threshold number of
   * distinct slots vote within a single period, and the next block persists latestVersion.
   */
  @Test
  public void clearedNodeReachesThresholdInOnePeriod() {
    simulateOldBinaryUpgrade(FORK_40, onesAt(FORK_40_THRESHOLD));

    for (int i = 0; i < FORK_40_THRESHOLD - 1; i++) {
      fork().update(block(FORK_40, witnesses.get(i)));
    }
    assertFalse(fork().pass(ForkBlockVersionEnum.VERSION_4_8_3));

    fork().update(block(FORK_40, witnesses.get(FORK_40_THRESHOLD - 1)));
    assertTrue(fork().pass(ForkBlockVersionEnum.VERSION_4_8_3));

    fork().update(block(FORK_40, witnesses.get(FORK_40_THRESHOLD)));
    assertEquals(FORK_40, properties().getLatestVersion());
  }

  /**
   * Once fork 40 is activated (latestVersion == 40), its stats are never cleared on restart.
   */
  @Test
  public void activatedVersionIsUntouched() {
    properties().delete(MARKER_KEY);
    properties().saveLatestVersion(FORK_40);
    properties().statsByVersion(FORK_40, onesAt(FORK_40_THRESHOLD));

    restart();

    assertEquals(FORK_40_THRESHOLD, ones(FORK_40));
    assertTrue(fork().pass(ForkBlockVersionEnum.VERSION_4_8_3));
    assertEquals(FORK_40, properties().getLatestVersion());
  }

  private void simulateOldBinaryUpgrade(int version, byte[] inheritedStats) {
    properties().delete(MARKER_KEY);
    properties().statsByVersion(version, inheritedStats);
    restart();
  }

  private void restart() {
    context.close();
    PeerManagerStateResetter.reset();
    context = new TronApplicationContext(DefaultConfig.class);
    appT = ApplicationFactory.create(context);
    dbManager = context.getBean(Manager.class);
    chainBaseManager = context.getBean(ChainBaseManager.class);
  }

  private ForkController fork() {
    return chainBaseManager.getForkController();
  }

  private DynamicPropertiesStore properties() {
    return dbManager.getDynamicPropertiesStore();
  }

  private BlockCapsule block(int version, ByteString witness) {
    BlockHeader.raw raw = BlockHeader.raw.newBuilder()
        .setNumber(properties().getLatestBlockHeaderNumber() + 1)
        .setTimestamp(properties().getLatestBlockHeaderTimestamp() + 3000)
        .setVersion(version)
        .setWitnessAddress(witness)
        .build();
    return new BlockCapsule(Block.newBuilder()
        .setBlockHeader(BlockHeader.newBuilder().setRawData(raw)).build());
  }

  private static byte[] onesAt(int count) {
    byte[] stats = new byte[SR_COUNT];
    Arrays.fill(stats, 0, count, (byte) 1);
    return stats;
  }

  private int ones(int version) {
    byte[] stats = properties().statsByVersion(version);
    if (stats == null) {
      return 0;
    }
    int count = 0;
    for (byte b : stats) {
      if (b == 1) {
        count++;
      }
    }
    return count;
  }
}
