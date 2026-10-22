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
 * A node that runs a binary older than 4.8.3 never clears stats[39] at maintenance (version 39
 * is not in its fork enum), so the per-slot bits accumulate a union across maintenance periods
 * that can exceed the activation threshold while no single period ever reached it. When such a
 * node is upgraded to the 4.8.3 binary, which does know version 39, that stale union would be
 * read directly and activate fork 39 (and accept a CLOSE_EXCHANGE proposal that canonical nodes
 * reject) before the threshold is met in a single period.
 *
 * <p>Manager.init() calls resetStatsAboveLatestVersion() once per binary upgrade (guarded by a
 * local FORK_STATS_TRACKED_VERSION marker) to drop that inherited union. These tests drive a real
 * restart: the Spring context is closed and reopened on the same database directory, so init()
 * runs exactly as it does on a node restart.
 *
 * <p>Fork 39 here is VERSION_4_8_3 (rate 80 -> ceil(0.8 * 27) = 22 of 27 slots), gating the
 * CLOSE_EXCHANGE (101) proposal. Mainnet latestVersion is 38.
 */
public class ForkStatsStartupResetTest extends VMTestBase {

  private static final int SR_COUNT = 27;
  private static final int THRESHOLD = 22;          // ceil(80 * 27 / 100)
  private static final int VERSION = ForkBlockVersionEnum.VERSION_4_8_3.getValue();
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
   * Upgrade from an older binary: the inherited union already clears the threshold, but the first
   * start of the 4.8.3 binary zeros it, so fork 39 is not active and the proposal is rejected.
   * The already-activated lower version 38 is left untouched.
   */
  @Test
  public void upgradeFromOldBinaryClearsStaleUnion() {
    simulateOldBinaryUpgrade(onesAt(THRESHOLD));

    assertEquals(0, ones(VERSION));
    assertEquals(SR_COUNT, properties().statsByVersion(VERSION).length);
    assertFalse(fork().pass(ForkBlockVersionEnum.VERSION_4_8_3));
    assertThrows(ContractValidateException.class, () -> ProposalUtil.validator(
        properties(), fork(), ProposalType.CLOSE_EXCHANGE.getCode(), 1));
    assertArrayEquals(onesAt(SR_COUNT), properties().statsByVersion(38));
    assertTrue(fork().pass(ForkBlockVersionEnum.VERSION_4_8_2_3));
    assertEquals(38, properties().getLatestVersion());
  }

  /**
   * After the one-time clear, a restart must not zero votes the 4.8.3 binary has legitimately
   * accumulated in the current period: the marker suppresses the second clear, so the node keeps
   * its count and does not fall behind its peers.
   */
  @Test
  public void restartDoesNotReclearAccumulatedVotes() {
    simulateOldBinaryUpgrade(new byte[SR_COUNT]);
    // The upgraded binary accumulates this period's votes, one slot short of the threshold.
    properties().statsByVersion(VERSION, onesAt(THRESHOLD - 1));

    restart();

    assertEquals(THRESHOLD - 1, ones(VERSION));
    assertFalse(fork().pass(ForkBlockVersionEnum.VERSION_4_8_3));
  }

  /**
   * A node whose inherited union was cleared reaches activation once the threshold number of
   * distinct slots vote within a single period, and the next block persists latestVersion.
   */
  @Test
  public void clearedNodeReachesThresholdInOnePeriod() {
    simulateOldBinaryUpgrade(onesAt(THRESHOLD));

    for (int i = 0; i < THRESHOLD - 1; i++) {
      fork().update(block(VERSION, witnesses.get(i)));
    }
    assertFalse(fork().pass(ForkBlockVersionEnum.VERSION_4_8_3));

    fork().update(block(VERSION, witnesses.get(THRESHOLD - 1)));
    assertTrue(fork().pass(ForkBlockVersionEnum.VERSION_4_8_3));

    fork().update(block(VERSION, witnesses.get(THRESHOLD)));
    assertEquals(VERSION, properties().getLatestVersion());
  }

  /**
   * Once fork 39 is activated (latestVersion == 39), its stats are never cleared on restart.
   */
  @Test
  public void activatedVersionIsUntouched() {
    properties().delete(MARKER_KEY);
    properties().saveLatestVersion(VERSION);
    properties().statsByVersion(VERSION, onesAt(THRESHOLD));

    restart();

    assertEquals(THRESHOLD, ones(VERSION));
    assertTrue(fork().pass(ForkBlockVersionEnum.VERSION_4_8_3));
    assertEquals(VERSION, properties().getLatestVersion());
  }

  private void simulateOldBinaryUpgrade(byte[] inheritedStats) {
    properties().delete(MARKER_KEY);
    properties().statsByVersion(VERSION, inheritedStats);
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
